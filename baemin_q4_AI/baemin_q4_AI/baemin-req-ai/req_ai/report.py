"""QA 담당자가 읽는 리포트 (Jira 코멘트·PR 코멘트에 그대로 붙일 수 있는 Markdown)."""
from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .pipeline import Result

KIND = {"added": "추가", "modified": "변경", "removed": "삭제"}
STATUS = {"pass": "PASS", "fail": "FAIL", "blocked": "BLOCKED", "inconclusive": "판단 불가"}


def render(r: "Result") -> str:
    v, imp = r.verification, r.impact
    auto = sum(1 for t in v.accepted if t["automatable"])
    defects = [s for s in r.script_runs if s.verdict == "결함 의심"] + [a for a in r.agent_runs if a.status == "fail"]
    L = [
        "# 요구사항 변경 분석 리포트",
        "",
        "## 요약",
        "",
        f"- 기획 변경 {len(r.changes)}건 → 요구사항 {len(v.requirements)}건"
        + (f" (분석 누락 변경점 {len(v.unanalyzed_changes)}건)" if v.unanalyzed_changes else ""),
        f"- 정책 충돌 의심 {len(v.policy_issues)}건, 기획 확인 질문 {len(v.open_questions)}건",
        f"- TC 생성 {len(r.testcases['testcases'])}건 → 채택 {len(v.accepted)}건 (자동 {auto} / 수동 {len(v.accepted) - auto}), "
        f"반려 {len(v.rejected)}건",
        f"- 커버리지 누락: 요구사항 {len(v.requirement_gaps)}건, 정책 조항 {len(v.policy_gaps)}건",
        f"- 기존 스크립트 {len(imp.selected) + len(imp.not_selected)}개 중 {len(imp.selected)}개 선정 실행",
        f"- 결함 의심 {len(defects)}건, 스크립트 갱신 필요 "
        f"{sum(1 for s in r.script_runs if s.verdict.startswith('스크립트 갱신'))}건",
        "",
        "## 1. 기획 변경점",
        "",
        "| ID | 구분 | 영역 | 이전 | 변경 |",
        "| --- | --- | --- | --- | --- |",
    ]
    L += [f"| {c.id} | {KIND[c.kind]} | {c.section} | {c.before or '-'} | {c.after or '-'} |" for c in r.changes]
    L.append("")

    L += ["## 2. 정책 검토", ""]
    if v.policy_issues:
        L += ["**정책 충돌 의심 (기획 확인 전까지 해당 요구사항 TC 보류)**", ""]
        L += [f"- {i['requirement_id']} ↔ [{i['clause_id']}] \"{i['quote']}\"  \n  {i['issue']}" for i in v.policy_issues]
        L.append("")
    derived = [(req["id"], d) for req in v.requirements for d in req["derived_conditions"]]
    if derived:
        L += ["**정책에서 파생된 확인 조건 (기획서에는 없는 것)**", ""]
        L += [f"- {rid} [{d['clause_id']}] {d['condition']}" for rid, d in derived]
        L.append("")
    if v.open_questions:
        L += ["**기획 확인 질문**", ""] + [f"- {q}" for q in v.open_questions] + [""]

    if v.invalid_requirements or v.rejected or v.dropped_policy_claims:
        L += ["## 3. 반려된 AI 생성 결과", ""]
        L += [f"- 요구사항 {x['id']}: {'; '.join(x['reasons'])}" for x in v.invalid_requirements]
        L += [f"- {x['tc']['id']} {x['tc']['title']}: {'; '.join(x['reasons'])}" for x in v.rejected]
        L += [f"- 정책 인용 제외: {x}" for x in v.dropped_policy_claims]
        L.append("")

    if v.requirement_gaps or v.policy_gaps or v.unanalyzed_changes:
        L += ["## 4. 커버리지 누락 (사람이 보완할 항목)", ""]
        L += [f"- 분석되지 않은 변경점 {c}" for c in v.unanalyzed_changes]
        L += [f"- 요구사항 {g['id']} {g['summary']}: TC 없음" for g in v.requirement_gaps]
        L += [f"- {g['requirement_id']} 정책 [{g['clause_id']}] \"{g['quote']}\": 검증 TC 없음" for g in v.policy_gaps]
        L.append("")
    if v.warnings:
        L += ["**경고**", ""] + [f"- {w}" for w in v.warnings] + [""]

    L += ["## 5. 채택된 TC", "", "| TC | 유형 | 제목 | 근거 | 수행 |", "| --- | --- | --- | --- | --- |"]
    manual = {m["id"]: m["reason"] for m in r.manual}
    for tc in v.accepted:
        basis = ", ".join(["기획"] * bool(tc["spec_evidence"]) + [p["clause_id"] for p in tc["policy_refs"]])
        how = f"수동 ({manual[tc['id']]})" if tc["id"] in manual else "AI 에이전트"
        L.append(f"| {tc['id']} | {tc['type']} | {tc['title']} | {basis} | {how} |")
    L.append("")

    L += ["## 6. 영향 분석: 기존 스크립트 선정", ""]
    for s in imp.selected:
        L.append(f"- **{s.script.id}** {s.script.title}: {'; '.join(s.reasons)}")
        L += [f"  - 기대값 변경 예상: {u}" for u in s.expect_update]
    if imp.not_selected:
        L.append(f"- 미선정: {', '.join(f'{s.id} {s.title}' for s in imp.not_selected)}")
    L += [f"- {x}" for x in imp.rejected_suggestions]
    L.append("")

    L += ["## 7. 신규 TC 실행 (AI 에이전트)", "", "| TC | 결과 | 사유 |", "| --- | --- | --- |"]
    L += [f"| {a.tc_id} | {STATUS[a.status]} | {a.reason} |" for a in r.agent_runs]
    L.append("")
    for a in r.agent_runs:
        notes = [f"{s.index}.{s.action['type']}" + (f"[{s.outcome}] {s.note}" if s.outcome != "done" else "")
                 for s in a.steps]
        checks = [f"{'OK' if c['passed'] else 'NG'} '{c['expected']}'/실제 '{c['actual']}'"
                  for s in a.steps for c in s.checks]
        L.append(f"- {a.tc_id}: {' → '.join(notes)}" + (f"  \n  확인: {', '.join(checks)}" if checks else ""))
    L.append("")

    if r.code_report:
        L += [f"## 8. 생성 스크립트 코드 검증: {r.code_report.verdict}", ""]
        L += [f"- [{f.severity}] {f.rule} (line {f.line})" for f in r.code_report.findings] or ["- 위반 없음"]
        L.append("")

    L += ["## 9. 스크립트 실행 결과", "", "| 스크립트 | 테스트 | 결과 | 판정 | 메시지 |", "| --- | --- | --- | --- | --- |"]
    for s in r.script_runs:
        L.append(f"| {s.script_id} | {s.test.split('::')[-1]} | {s.status} | {s.verdict} | {s.message.replace('|', '/')} |")
    L.append("")
    return "\n".join(L)
