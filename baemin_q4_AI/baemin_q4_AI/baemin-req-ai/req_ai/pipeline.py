"""전체 흐름.

  ① 변경점 추출(코드) → ② 요구사항 분석(LLM) + ③ 정책 대조(검색 코드 + LLM)
  → ④ TC 생성(LLM) + 검증(코드) → ⑤ 영향 스크립트 선정(코드 + LLM 추천)
  → ⑥ 신규 TC 에이전트 실행 → 스크립트 변환 → 코드 게이트 → 기존·신규 스크립트 실행 → 리포트
"""
from __future__ import annotations

import json
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from . import analysis, code_checker, exporter, impact, policy, runner, spec_diff, verifier
from .agent import AgentConfig, TCResult, TestAgent
from .device import Device
from .llm import LLM


@dataclass
class Inputs:
    spec_old: str
    spec_new: str
    policy_md: str
    registry: Path
    regression_dir: Path
    screens_dir: Path | None = None
    app_package: str = ""
    run_env: dict[str, str] = field(default_factory=dict)   # pytest 실행 환경 (가상/실제 단말)


@dataclass
class Result:
    changes: list[spec_diff.Change]
    related: dict[str, list[policy.Clause]]
    requirements: dict[str, Any]
    testcases: dict[str, Any]
    verification: verifier.Verification
    impact: impact.ImpactResult
    agent_runs: list[TCResult] = field(default_factory=list)
    manual: list[dict[str, str]] = field(default_factory=list)
    exports: list[exporter.ExportResult] = field(default_factory=list)
    code_report: code_checker.CheckReport | None = None
    script_runs: list[runner.ScriptRun] = field(default_factory=list)


def run(inputs: Inputs, llm: LLM, device: Device, reset_app: Callable[[], None], out: Path,
        agent_config: AgentConfig | None = None) -> Result:
    out.mkdir(parents=True, exist_ok=True)

    # ① 변경점
    changes = spec_diff.diff_specs(inputs.spec_old, inputs.spec_new)
    # ③ 정책 검색 (변경 문장마다 관련 조항 후보)
    clauses = policy.load_policy(inputs.policy_md)
    related = {c.id: policy.search(clauses, f"{c.section} {c.after or c.before}") for c in changes}
    registry, vocabulary = impact.load_registry(inputs.registry)

    # ②④ LLM 분석·생성 → 코드 검증
    requirements = analysis.analyze_changes(llm, changes, related, vocabulary)
    testcases = analysis.generate_testcases(llm, requirements, vocabulary)
    v = verifier.verify(changes, inputs.spec_old, inputs.spec_new, clauses, requirements, testcases, vocabulary)

    # ⑤ 영향 분석
    selected = impact.select(registry, v, changes, llm)
    result = Result(changes, related, requirements, testcases, v, selected)

    # ⑥-1 신규 TC: AI 에이전트 실행 → 통과하면 스크립트 변환
    config = agent_config or AgentConfig()
    config.artifacts_dir = config.artifacts_dir or out / "artifacts"
    agent = TestAgent(llm, device, config)
    for tc in v.accepted:
        if not tc["automatable"]:
            result.manual.append({"id": tc["id"], "title": tc["title"], "reason": tc.get("manual_reason") or ""})
            continue
        reset_app()
        run_result = agent.run(tc)
        result.agent_runs.append(run_result)
        if run_result.status == "pass":
            result.exports.append(exporter.export(run_result, tc))

    # ⑥-2 생성 스크립트 → 코드 게이트
    generated_dir = out / "generated"
    generated: Path | None = None
    if any(e.ok for e in result.exports):
        generated_dir.mkdir(exist_ok=True)
        generated = generated_dir / "test_generated_from_tc.py"
        generated.write_text(exporter.build_module([e for e in result.exports if e.ok]), encoding="utf-8")
        shutil.copy(inputs.regression_dir / "conftest.py", generated_dir / "conftest.py")
        if inputs.screens_dir:
            screens = code_checker.load_screens(inputs.screens_dir)
            result.code_report = code_checker.check_code(generated, screens, inputs.app_package)

    # ⑥-3 실행: 영향받는 기존 스크립트 + (게이트를 통과한) 신규 스크립트
    files = [inputs.regression_dir / s.script.path for s in selected.selected]
    if files:
        outcomes = runner.run_pytest(files, out / "junit_regression.xml", inputs.run_env, inputs.regression_dir)
        by_stem = {Path(s.script.path).stem: s for s in selected.selected}
        for node, (status, message) in outcomes.items():
            sel = by_stem.get(node.split("::")[0].split(".")[-1])
            if sel:
                result.script_runs.append(runner.ScriptRun(sel.script.id, node, status, message,
                                                           runner.classify(status, bool(sel.expect_update))))
    if generated and result.code_report and result.code_report.verdict != "차단":
        outcomes = runner.run_pytest([generated], out / "junit_generated.xml", inputs.run_env, generated_dir)
        for node, (status, message) in outcomes.items():
            result.script_runs.append(runner.ScriptRun("NEW", node, status, message, runner.classify(status, False)))

    _save(result, out)
    return result


def _save(r: Result, out: Path) -> None:
    from .report import render

    def dump(name: str, data: Any) -> None:
        (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

    dump("changes.json", [c.to_dict() for c in r.changes])
    dump("requirements.json", r.requirements)
    dump("testcases.json", r.testcases)
    dump("verification.json", r.verification.to_dict())
    dump("agent_runs.json", [x.to_dict() for x in r.agent_runs])
    (out / "report.md").write_text(render(r), encoding="utf-8")
