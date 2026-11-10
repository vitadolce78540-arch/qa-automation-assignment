"""5단계: 에이전트가 통과시킨 실행 기록 → 회귀용 Appium 스크립트(pytest).

에이전트 실행은 LLM 을 매번 호출하므로 느리고 비싸고, 같은 결과가 나온다는 보장도 없다.
한 번 통과한 경로는 LLM 없이 도는 일반 스크립트로 바꿔 CI 회귀에 넣는다.

로케이터는 에이전트가 누른 좌표가 아니라 요소 속성으로 만든다.
  우선순위: resource-id → content-desc → 정확한 text
  각 후보가 '그 단계의 화면에서 정확히 1개'일 때만 채택한다 (모호하면 변환을 멈추고 알린다).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .agent import StepRecord, TCResult
from .screen import parse


@dataclass
class ExportResult:
    tc_id: str
    code: str
    issues: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.issues


def choose_locator(info: dict[str, Any] | None, screen_xml: str) -> tuple[str, str] | None:
    """(전략, 값). 화면에서 유일하게 하나만 매칭되는 것만 반환."""
    if not info:
        return None
    screen = parse(screen_xml)
    if info.get("resource_id") and screen.count(resource_id=info["resource_id"]) == 1:
        return "id", info["resource_id"]
    if info.get("desc") and screen.count(desc=info["desc"]) == 1:
        return "accessibility_id", info["desc"]
    if info.get("text") and screen.count(text=info["text"]) == 1:
        return "text", info["text"]
    return None


def _by(locator: tuple[str, str]) -> str:
    strategy, value = locator
    if strategy == "id":
        return f"(AppiumBy.ID, {_q(value)})"
    if strategy == "accessibility_id":
        return f"(AppiumBy.ACCESSIBILITY_ID, {_q(value)})"
    selector = 'new UiSelector().text("' + value.replace("\\", "\\\\").replace('"', '\\"') + '")'
    return f"(AppiumBy.ANDROID_UIAUTOMATOR, {_q(selector)})"


def _q(value: str) -> str:
    """파이썬 문자열 리터럴 (항상 큰따옴표로 시작하도록 json 형식 사용)."""
    import json

    return json.dumps(value, ensure_ascii=False)


_PROPERTY_EXPR = {
    "text": "el.text",
    "desc": 'el.get_attribute("content-desc")',
    "selected": 'el.get_attribute("selected")',
    "enabled": 'el.get_attribute("enabled")',
}


def export(result: TCResult, tc: dict[str, Any]) -> ExportResult:
    if result.status != "pass":
        return ExportResult(result.tc_id, "", [f"PASS 가 아닌 실행({result.status})은 변환하지 않음"])

    issues: list[str] = []
    body: list[str] = []
    for step in result.steps:
        if step.outcome != "done":
            continue
        body.extend(_step_lines(step, issues))

    func = "test_" + result.tc_id.lower().replace("-", "_")
    expected = "\n".join(f"    #   [{i}] {e}" for i, e in enumerate(tc["expected"]))
    code = "\n".join([
        f"def {func}(driver):",
        f'    """{tc["title"]}',
        "",
        *_evidence_lines(tc),
        "    AI 에이전트 실행 기록에서 자동 변환됨. 로케이터는 실행 당시 화면에서 유일성을 확인했다.",
        '    """',
        "    # 기대결과",
        expected,
        *body,
        "",
    ])
    return ExportResult(result.tc_id, code, issues)


def _evidence_lines(tc: dict[str, Any]) -> list[str]:
    lines = []
    if tc.get("spec_evidence"):
        lines.append(f"    기획: {tc['spec_evidence']}")
    for ref in tc.get("policy_refs", []):
        lines.append(f"    정책 [{ref['clause_id']}]: {ref['quote']}")
    return lines


def _step_lines(step: StepRecord, issues: list[str]) -> list[str]:
    kind = step.action["type"]
    if kind == "tap":
        locator = choose_locator(step.element, step.screen_xml)
        if locator is None:
            issues.append(f"{step.index}단계: 유일한 로케이터를 만들 수 없음 {step.element}")
            return [f"    # TODO {step.index}단계: 로케이터 확인 필요 {step.element}"]
        return [f"    click(driver, {_by(locator)})  # {step.reason}"]
    if kind == "back":
        return ["    driver.back()"]
    if kind == "scroll":
        return [f'    scroll(driver, "{step.action.get("direction", "down")}")']
    if kind == "verify":
        lines: list[str] = []
        for check in step.checks:
            if not check["passed"]:
                continue
            locator = choose_locator(check.get("element_info"), step.screen_xml)
            if locator is None:
                issues.append(f"{step.index}단계: 확인 대상 요소의 유일한 로케이터를 만들 수 없음")
                continue
            prop, match, expected = check["property"], check["match"], check["expected"]
            if prop == "exists":
                lines.append(f"    find(driver, {_by(locator)})  # 기대결과[{check['expected_index']}]")
                continue
            lines.append(f"    el = find(driver, {_by(locator)})  # 기대결과[{check['expected_index']}]")
            actual = _PROPERTY_EXPR[prop]
            if match == "exact":
                lines.append(f"    assert {actual} == {expected!r}")
            elif match == "contains":
                lines.append(f"    assert {expected!r} in {actual}")
            else:
                lines.append(f"    assert re.fullmatch({expected!r}, {actual})")
        return lines
    return []


HEADER = '''"""AI 에이전트 실행 기록에서 생성한 회귀 스크립트. 직접 수정하지 말고 다시 생성한다."""
import os
import re

from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

TIMEOUT = float(os.environ.get("QA_TIMEOUT", "10"))


def find(driver, by):
    return WebDriverWait(driver, TIMEOUT).until(EC.visibility_of_element_located(by))


def click(driver, by):
    WebDriverWait(driver, TIMEOUT).until(EC.element_to_be_clickable(by)).click()


def scroll(driver, direction):
    size = driver.get_window_size()
    driver.execute_script("mobile: scrollGesture", {
        "left": size["width"] // 10, "top": size["height"] // 5,
        "width": size["width"] * 8 // 10, "height": size["height"] * 3 // 5,
        "direction": direction, "percent": 0.7})

'''


def build_module(exports: list[ExportResult]) -> str:
    return HEADER + "\n" + "\n\n".join(e.code for e in exports if e.code)
