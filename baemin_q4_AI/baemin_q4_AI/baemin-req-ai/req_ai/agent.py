"""4단계: AI 에이전트가 TC 를 읽고 앱을 직접 조작해 수행한다.

관찰(화면 요소 목록) → 판단(LLM, 다음 행동 1개) → 가드레일 검사(코드) → 실행 → 기록 을 반복한다.

LLM 에게 맡기는 것  : 어떤 요소를 누를지, 어떤 요소의 어떤 값을 확인할지 '고르는' 일
코드가 판정하는 것  : 고른 요소가 실제로 있는지, 눌러도 되는 요소인지, 확인 결과가 기대값과 같은지
=> LLM 이 "통과입니다"라고 말해도 그대로 믿지 않는다. 모든 기대결과가 코드 비교로 확인돼야 PASS.
"""
from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from .device import Device
from .llm import LLM, load_prompt
from .screen import Element, Screen

DECISION_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "observation": {"type": "string", "description": "현재 화면이 어떤 화면인지 한 줄 요약"},
        "action": {
            "type": "object",
            "properties": {
                "type": {"type": "string", "enum": ["tap", "type", "scroll", "back", "verify", "finish"]},
                "element": {"type": "string", "description": "tap/type 대상 요소 ID (예: e3)"},
                "text": {"type": "string", "description": "type 에 입력할 값"},
                "direction": {"type": "string", "enum": ["up", "down"]},
            },
            "required": ["type"],
        },
        "checks": {
            "type": "array",
            "description": "verify 일 때: 어떤 요소의 어떤 값을 확인할지",
            "items": {
                "type": "object",
                "properties": {
                    "expected_index": {"type": "integer", "description": "TC 기대결과 목록의 번호 (0부터)"},
                    "element": {"type": "string"},
                    "property": {"type": "string", "enum": ["text", "desc", "selected", "enabled", "exists"]},
                    "match": {"type": "string", "enum": ["exact", "contains", "regex"]},
                    "expected": {"type": "string"},
                },
                "required": ["expected_index", "element", "property", "match", "expected"],
            },
        },
        "finish": {
            "type": "object",
            "properties": {
                "result": {"type": "string", "enum": ["pass", "fail", "blocked"]},
                "reason": {"type": "string"},
            },
            "required": ["result", "reason"],
        },
        "reason": {"type": "string", "description": "이 행동을 고른 이유 (한 문장)"},
    },
    "required": ["observation", "action", "reason"],
}

# 되돌릴 수 없는 동작. 테스트 환경 허용(allow_destructive) 없이는 누르지 않는다.
DEFAULT_DANGER_PATTERNS = [r"결제하기$", r"^주문 취소$", r"^취소하기$", r"^탈퇴", r"^삭제$", r"주문하기$"]


@dataclass
class AgentConfig:
    max_steps: int = 15
    max_invalid: int = 2              # LLM 이 없는 요소를 고른 횟수 한도
    loop_limit: int = 3               # 같은 화면·같은 행동 반복 한도
    allow_destructive: bool = False
    danger_patterns: list[str] = field(default_factory=lambda: list(DEFAULT_DANGER_PATTERNS))
    artifacts_dir: Path | None = None


@dataclass
class StepRecord:
    index: int
    observation: str
    action: dict[str, Any]
    reason: str
    outcome: str                       # done | rejected | blocked
    note: str = ""
    element: dict[str, Any] | None = None
    checks: list[dict[str, Any]] = field(default_factory=list)
    screen_xml: str = ""               # 이 단계 직전 화면 (스크립트 변환 시 로케이터 검증에 사용)


@dataclass
class TCResult:
    tc_id: str
    title: str
    status: str                        # pass | fail | blocked | inconclusive
    reason: str
    steps: list[StepRecord]
    verified_expected: list[int]

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        for step in data["steps"]:
            step.pop("screen_xml", None)
        return data


def check_value(element: Element | None, prop: str, match: str, expected: str) -> tuple[bool, str]:
    """기대결과 비교는 LLM 이 아니라 여기서 한다."""
    if prop == "exists":
        actual = "true" if element is not None else "false"
    elif element is None:
        return False, "(요소 없음)"
    else:
        actual = {
            "text": element.text,
            "desc": element.desc,
            "selected": str(element.selected).lower(),
            "enabled": str(element.enabled).lower(),
        }[prop]
    if match == "exact":
        ok = actual == expected
    elif match == "contains":
        ok = expected in actual
    else:
        ok = re.fullmatch(expected, actual) is not None
    return ok, actual


class TestAgent:
    __test__ = False   # pytest 가 테스트 클래스로 수집하지 않도록
    def __init__(self, llm: LLM, device: Device, config: AgentConfig | None = None) -> None:
        self.llm = llm
        self.device = device
        self.config = config or AgentConfig()
        self.system_prompt = load_prompt("agent_step")

    def run(self, tc: dict[str, Any]) -> TCResult:
        steps: list[StepRecord] = []
        verified: set[int] = set()
        failed_checks: list[str] = []
        invalid = 0
        recent: list[str] = []

        for index in range(1, self.config.max_steps + 1):
            screen = self.device.screen()
            decision = self.llm.complete_json(
                key=f"agent/{tc['id']}/{index:02d}",
                system=self.system_prompt,
                user=self._user_message(tc, screen, steps, verified),
                schema=DECISION_SCHEMA,
            )
            action = decision["action"]
            record = StepRecord(index=index, observation=decision.get("observation", ""), action=action,
                                reason=decision.get("reason", ""), outcome="done", screen_xml=screen.xml)
            steps.append(record)
            kind = action["type"]

            # 루프 감지: 같은 화면에서 같은 행동을 반복하면 중단
            recent.append(screen.signature() + json.dumps(action, sort_keys=True, ensure_ascii=False))
            if len(recent) >= self.config.loop_limit and len(set(recent[-self.config.loop_limit:])) == 1:
                record.outcome, record.note = "blocked", "반복 행동 중단"
                return self._result(tc, "inconclusive", "같은 화면에서 같은 행동 반복 (진행 불가)", steps, verified)

            if kind == "finish":
                return self._finish(tc, decision.get("finish") or {}, steps, verified, failed_checks)

            if kind == "verify":
                for check in decision.get("checks") or []:
                    element = screen.get(check["element"])
                    if element is None and check["property"] != "exists":
                        record.outcome, record.note = "rejected", f"현재 화면에 없는 요소: {check['element']}"
                        invalid += 1
                        continue
                    ok, actual = check_value(element, check["property"], check["match"], check["expected"])
                    record.checks.append({**check, "actual": actual, "passed": ok,
                                          "element_info": _element_info(element)})
                    if ok:
                        verified.add(check["expected_index"])
                    else:
                        failed_checks.append(
                            f"기대결과[{check['expected_index']}] {check['property']} "
                            f"기대='{check['expected']}' 실제='{actual}'")
                if invalid > self.config.max_invalid:
                    return self._result(tc, "inconclusive", "LLM 이 화면에 없는 요소를 반복 선택", steps, verified)
                continue

            if kind in ("tap", "type"):
                element = screen.get(action.get("element", ""))
                if element is None:
                    record.outcome, record.note = "rejected", f"현재 화면에 없는 요소: {action.get('element')}"
                    invalid += 1
                    if invalid > self.config.max_invalid:
                        return self._result(tc, "inconclusive", "LLM 이 화면에 없는 요소를 반복 선택", steps, verified)
                    continue
                record.element = _element_info(element)
                if not element.enabled:
                    record.outcome, record.note = "rejected", "비활성 요소"
                    continue
                if self._is_dangerous(element) and not self.config.allow_destructive:
                    record.outcome = "blocked"
                    record.note = f"되돌릴 수 없는 동작 차단: '{element.label}' (테스트 환경 허용 필요)"
                    return self._result(tc, "blocked", record.note, steps, verified)
                if kind == "tap":
                    self.device.tap(element)
                else:
                    self.device.type_text(element, action.get("text", ""))
            elif kind == "scroll":
                self.device.scroll(action.get("direction", "down"))
            elif kind == "back":
                self.device.back()

        return self._result(tc, "inconclusive", f"최대 {self.config.max_steps}단계 안에 끝나지 않음", steps, verified)

    # ------------------------------------------------------------------ 내부

    def _finish(self, tc: dict[str, Any], finish: dict[str, Any], steps: list[StepRecord],
                verified: set[int], failed_checks: list[str]) -> TCResult:
        claimed = finish.get("result", "fail")
        if failed_checks:
            # 코드 비교에서 한 번이라도 어긋났으면 LLM 판단과 무관하게 FAIL
            return self._result(tc, "fail", "; ".join(failed_checks), steps, verified)
        missing = [i for i in range(len(tc["expected"])) if i not in verified]
        if claimed == "pass" and missing:
            return self._result(tc, "inconclusive",
                                f"LLM 은 통과라고 했지만 확인되지 않은 기대결과가 있음: {missing}", steps, verified)
        if claimed == "pass":
            return self._result(tc, "pass", finish.get("reason", ""), steps, verified)
        return self._result(tc, claimed, finish.get("reason", ""), steps, verified)

    def _is_dangerous(self, element: Element) -> bool:
        return any(re.search(p, element.label) for p in self.config.danger_patterns)

    def _user_message(self, tc: dict[str, Any], screen: Screen, steps: list[StepRecord],
                      verified: set[int]) -> str:
        history = "\n".join(
            f"{s.index}. {s.action['type']} {s.action.get('element', '')} → {s.outcome}"
            + (f" ({s.note})" if s.note else "")
            for s in steps[-8:]) or "(없음)"
        remaining = [f"[{i}] {e}" for i, e in enumerate(tc["expected"]) if i not in verified]
        return (
            f"<testcase>\n{json.dumps(tc, ensure_ascii=False, indent=2)}\n</testcase>\n\n"
            f"<history>\n{history}\n</history>\n\n"
            f"<unverified_expected>\n" + ("\n".join(remaining) or "(모두 확인됨)") + "\n</unverified_expected>\n\n"
            f"<screen>\n{screen.to_prompt()}\n</screen>"
        )

    def _result(self, tc: dict[str, Any], status: str, reason: str, steps: list[StepRecord],
                verified: set[int]) -> TCResult:
        result = TCResult(tc_id=tc["id"], title=tc["title"], status=status, reason=reason, steps=steps,
                          verified_expected=sorted(verified))
        if self.config.artifacts_dir and status != "pass":
            out = self.config.artifacts_dir / tc["id"]
            out.mkdir(parents=True, exist_ok=True)
            self.device.screenshot(out / "last_screen")
        return result


def _element_info(element: Element | None) -> dict[str, Any] | None:
    if element is None:
        return None
    return {"text": element.text, "desc": element.desc, "resource_id": element.resource_id,
            "class": element.cls}
