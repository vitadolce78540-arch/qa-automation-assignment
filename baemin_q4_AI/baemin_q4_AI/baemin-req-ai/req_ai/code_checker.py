"""6단계: AI 가 만든 테스트 코드 검증 게이트.

에이전트가 변환한 스크립트뿐 아니라, Cursor·Claude 로 직접 생성한 테스트 코드(Java/Kotlin/Python)에도 그대로 쓴다.

  규칙 검사     : 팀 E2E 코딩 규칙 위반 (sleep, 부분 일치 셀렉터, 인덱스 XPath, 좌표 탭, implicit wait, 하드코딩 비밀값)
  로케이터 검증 : 코드 속 셀렉터를 실제 화면 덤프(XML)에 대조
                  - 어느 화면에서도 0건 → 환각 로케이터 (앱에 없는 요소)
                  - 어떤 화면에서 2건 이상 → 모호한 로케이터
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from .screen import Screen, parse

RULES: list[tuple[str, str, str]] = [
    # (심각도, 규칙 이름, 정규식)
    ("block", "고정 대기 (sleep)", r"\b(Thread\.sleep|time\.sleep|SystemClock\.sleep)\s*\("),
    ("block", "부분 일치 셀렉터", r"\b(textContains|descriptionContains|descContains)\s*\("),
    ("block", "인덱스 XPath", r"\)\s*\[\s*\d+\s*\]|//[\w.]+\[\s*\d+\s*\]"),
    ("block", "좌표 탭/스와이프", r"\b(swipe|tap|press)\s*\(\s*\d+\s*,\s*\d+|clickGesture\"?\s*,\s*\{?\s*\"?x\"?\s*[:=]\s*\d+"),
    ("block", "implicit wait 사용", r"implicitly_?[Ww]ait\s*\(\s*[1-9]"),
    ("block", "비밀값 하드코딩", r"(?i)(password|passwd|pin|secret|token|api_?key)\s*[=:]\s*[\"'][^\"']{3,}[\"']"),
    ("warn", "의미 없는 검증 (항상 참)", r"assert(True|That)?\s*\(\s*true\s*\)|\bassert\s+True\b"),
    ("warn", "예외 무시 (빈 catch/except)", r"catch\s*\([^)]*\)\s*\{\s*\}|except[^:]*:\s*(pass|\.\.\.)\s*$"),
]

LOCATOR_PATTERNS: list[tuple[str, str]] = [
    ("resource_id", r'AppiumBy\.ID\s*,\s*"([^"]+)"'),
    ("resource_id", r'AppiumBy\.id\(\s*"([^"]+)"\s*\)'),
    ("resource_id", r'By\.res\(\s*"([^"]+)"\s*\)'),
    ("desc", r'AppiumBy\.ACCESSIBILITY_ID\s*,\s*"([^"]+)"'),
    ("desc", r'AppiumBy\.accessibilityId\(\s*"([^"]+)"\s*\)'),
    ("desc", r'\.description\(\\?"([^"\\]+)\\?"\)'),
    ("desc", r'By\.desc\(\s*"([^"]+)"\s*\)'),
    ("text", r'\.text\(\\?"([^"\\]+)\\?"\)'),
    ("text", r'By\.text\(\s*"([^"]+)"\s*\)'),
]


@dataclass
class Finding:
    severity: str           # block | warn
    rule: str
    line: int
    snippet: str


@dataclass
class CheckReport:
    file: str
    findings: list[Finding] = field(default_factory=list)

    @property
    def verdict(self) -> str:
        if any(f.severity == "block" for f in self.findings):
            return "차단"
        if self.findings:
            return "보완 필요"
        return "통과"


def load_screens(screens_dir: Path) -> dict[str, Screen]:
    return {p.stem: parse(p.read_text(encoding="utf-8")) for p in sorted(screens_dir.glob("*.xml"))}


def check_code(path: Path, screens: dict[str, Screen], app_package: str = "") -> CheckReport:
    report = CheckReport(file=str(path))
    lines = path.read_text(encoding="utf-8").splitlines()

    for no, line in enumerate(lines, start=1):
        code = line.split("#", 1)[0] if path.suffix == ".py" else re.sub(r"//\s.*$", "", line)
        for severity, rule, pattern in RULES:
            if re.search(pattern, code):
                report.findings.append(Finding(severity, rule, no, line.strip()))

        for kind, pattern in LOCATOR_PATTERNS:
            for value in re.findall(pattern, code):
                report.findings.extend(_check_locator(kind, value, no, line.strip(), screens, app_package))
    return report


def _check_locator(kind: str, value: str, line_no: int, snippet: str, screens: dict[str, Screen],
                   app_package: str) -> list[Finding]:
    if kind == "resource_id" and ":id/" not in value and app_package:
        value = f"{app_package}:id/{value}"
    counts = {name: screen.count(**{kind: value}) for name, screen in screens.items()}
    if not any(counts.values()):
        return [Finding("block", f"환각 로케이터: {kind}='{value}' 가 어떤 화면에도 없음", line_no, snippet)]
    ambiguous = [f"{name}({n}개)" for name, n in counts.items() if n > 1]
    if ambiguous:
        return [Finding("warn", f"모호한 로케이터: {kind}='{value}' 가 여러 요소와 일치 → {', '.join(ambiguous)}",
                        line_no, snippet)]
    return []
