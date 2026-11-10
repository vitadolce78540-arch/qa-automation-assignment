"""⑤ 영향 분석: 이번 변경 때문에 다시 돌려야 하는 기존 자동화 스크립트 선정.

전체 회귀를 매번 돌리지 않고, 변경된 기능·정책에 걸린 스크립트만 고른다.

  1차 (코드)  : 스크립트 메타데이터(기능 태그, 관련 정책 조항)와 이번 요구사항의 태그·인용 조항이 겹치면 선정
  2차 (코드)  : 스크립트가 검증하는 문구(asserted_texts)가 변경 '전' 기획 문장에 들어 있으면 "기획 변경으로 갱신 필요 예상"
  3차 (LLM)   : 태그로는 안 잡히는 연관성 추천. 추천한 스크립트가 실제로 있는지만 코드로 확인하고, 추천 사유를 리포트에 남긴다
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .llm import LLM, load_prompt
from .spec_diff import Change
from .verifier import Verification

SUGGEST_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "suggestions": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"script_id": {"type": "string"}, "reason": {"type": "string"}},
                "required": ["script_id", "reason"],
            },
        },
    },
    "required": ["suggestions"],
}


@dataclass
class ScriptInfo:
    id: str
    path: str
    title: str
    feature_tags: list[str]
    policy_clauses: list[str]
    asserted_texts: list[str]


@dataclass
class Selection:
    script: ScriptInfo
    reasons: list[str]
    expect_update: list[str] = field(default_factory=list)   # 기획 변경으로 기대값이 바뀌었을 가능성


@dataclass
class ImpactResult:
    selected: list[Selection]
    not_selected: list[ScriptInfo]
    rejected_suggestions: list[str]


def load_registry(path: Path) -> tuple[list[ScriptInfo], list[str]]:
    """(스크립트 목록, 기능 태그 어휘). 어휘는 LLM 이 쓸 수 있는 태그의 전체 목록이다."""
    data = json.loads(path.read_text(encoding="utf-8"))
    scripts = [ScriptInfo(**item) for item in data["scripts"]]
    vocabulary = sorted(set(data.get("feature_vocabulary", [])) | {t for s in scripts for t in s.feature_tags})
    return scripts, vocabulary


def select(registry: list[ScriptInfo], verification: Verification, changes: list[Change],
           llm: LLM | None = None) -> ImpactResult:
    tags, clauses = verification.impacted_tags(), verification.cited_clauses()
    changed_before = [c for c in changes if c.kind in ("modified", "removed")]
    picked: dict[str, Selection] = {}

    for script in registry:
        reasons = []
        if hit := sorted(tags & set(script.feature_tags)):
            reasons.append(f"기능 태그 일치 {hit}")
        if hit := sorted(clauses & set(script.policy_clauses)):
            reasons.append(f"정책 조항 일치 {hit}")
        if reasons:
            picked[script.id] = Selection(script, reasons)

    for selection in picked.values():
        for change in changed_before:
            for text in selection.script.asserted_texts:
                if text and text in change.before and text not in change.after:
                    selection.expect_update.append(f"{change.id}: 기대값 '{text}' 이(가) 기획에서 바뀜")

    rejected: list[str] = []
    if llm is not None:
        rejected = _llm_suggestions(llm, registry, verification, picked)

    not_selected = [s for s in registry if s.id not in picked]
    return ImpactResult(list(picked.values()), not_selected, rejected)


def _llm_suggestions(llm: LLM, registry: list[ScriptInfo], verification: Verification,
                     picked: dict[str, Selection]) -> list[str]:
    by_id = {s.id: s for s in registry}
    candidates = [{"script_id": s.id, "title": s.title, "feature_tags": s.feature_tags}
                  for s in registry if s.id not in picked]
    if not candidates:
        return []
    reqs = [{"id": r["id"], "summary": r["summary"], "feature_tags": r["feature_tags"]}
            for r in verification.requirements]
    result = llm.complete_json(
        key="impact",
        system=load_prompt("impact_suggest"),
        user=(f"<requirements>\n{json.dumps(reqs, ensure_ascii=False, indent=2)}\n</requirements>\n\n"
              f"<candidate_scripts>\n{json.dumps(candidates, ensure_ascii=False, indent=2)}\n</candidate_scripts>"),
        schema=SUGGEST_SCHEMA,
    )
    rejected = []
    for s in result["suggestions"]:
        if s["script_id"] in picked:
            continue
        if s["script_id"] not in by_id:
            rejected.append(f"{s['script_id']}: 등록되지 않은 스크립트 (추천 무시)")
            continue
        picked[s["script_id"]] = Selection(by_id[s["script_id"]], [f"AI 추천: {s['reason']}"])
    return rejected
