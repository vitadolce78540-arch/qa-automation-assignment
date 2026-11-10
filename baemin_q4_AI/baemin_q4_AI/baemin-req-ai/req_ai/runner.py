"""⑥ 선정된 기존 스크립트 + 새로 생성된 스크립트 실행 (pytest).

실패를 그대로 "결함"으로 보고하지 않는다. 기획이 바뀌어 기대값이 달라진 스크립트일 수 있기 때문이다.
  - 영향 분석에서 '기획 변경으로 갱신 필요 예상'으로 표시된 스크립트가 실패 → 스크립트 갱신 필요
  - 그 외 실패 → 결함 의심
"""
from __future__ import annotations

import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass
class ScriptRun:
    script_id: str
    test: str
    status: str          # passed | failed | error | skipped
    message: str
    verdict: str = ""    # 통과 | 결함 의심 | 스크립트 갱신 필요


def run_pytest(files: list[Path], junit: Path, env_extra: dict[str, str], cwd: Path) -> dict[str, tuple[str, str]]:
    """파일별로 모은 결과 {nodeid: (status, message)}"""
    env = {**os.environ, **env_extra}
    cmd = [sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider", f"--junitxml={junit}",
           *[str(f) for f in files]]
    subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    results: dict[str, tuple[str, str]] = {}
    for case in ET.parse(junit).getroot().iter("testcase"):
        node = f"{case.get('classname', '')}::{case.get('name')}"
        status, message = "passed", ""
        for tag in ("failure", "error", "skipped"):
            child = case.find(tag)
            if child is not None:
                status = {"failure": "failed", "error": "error", "skipped": "skipped"}[tag]
                message = (child.get("message") or "").splitlines()[0][:300]
                break
        results[node] = (status, message)
    return results


def classify(status: str, expect_update: bool) -> str:
    if status == "passed":
        return "통과"
    if expect_update:
        return "스크립트 갱신 필요 (기획 변경)"
    return "결함 의심"
