"""③④ 검증 (LLM 없음): AI 가 낸 요구사항·정책 대조·TC 를 그대로 믿지 않고 코드로 확인한다.

  기획 근거   : spec_evidence 가 새 기획서(삭제 건은 이전 기획서)에 실제로 있는가
  정책 근거   : 인용한 조항 ID 가 있고, 인용문이 그 조항 본문과 일치하는가
  추적성      : 요구사항 → 변경점, TC → 요구사항 연결이 유효한가
  커버리지    : 변경점마다 요구사항, 요구사항마다 TC, 정책 조항(근거·제약)마다 TC 가 있는가
  기대결과    : "정상적으로" 같은 판정 불가 표현 경고
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from .policy import Clause, quote_matches
from .spec_diff import Change

VAGUE = re.compile(r"(정상(적으로)?\s*(동작|작동|표시)|문제\s*없|잘\s*(된|동작)|올바르게|적절(히|하게))")


def _norm(text: str) -> str:
    text = re.sub(r"^[\s\-*>#]+", "", text.strip(), flags=re.M)
    text = text.replace("“", "\"").replace("”", "\"")
    return re.sub(r"\s+", " ", text).strip().rstrip(".")


def in_text(sentence: str, document: str) -> bool:
    s = _norm(sentence)
    return bool(s) and s in _norm(document)


@dataclass
class Verification:
    requirements: list[dict[str, Any]] = field(default_factory=list)        # 채택
    invalid_requirements: list[dict[str, Any]] = field(default_factory=list)
    policy_issues: list[dict[str, Any]] = field(default_factory=list)       # 인용 확인된 충돌
    dropped_policy_claims: list[str] = field(default_factory=list)          # 인용이 틀려 버린 정책 주장
    accepted: list[dict[str, Any]] = field(default_factory=list)
    rejected: list[dict[str, Any]] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    unanalyzed_changes: list[str] = field(default_factory=list)
    requirement_gaps: list[dict[str, Any]] = field(default_factory=list)
    policy_gaps: list[dict[str, Any]] = field(default_factory=list)
    open_questions: list[str] = field(default_factory=list)

    def impacted_tags(self) -> set[str]:
        return {t for r in self.requirements for t in r["feature_tags"]}

    def cited_clauses(self) -> set[str]:
        return {p["clause_id"] for r in self.requirements for p in r["policy_refs"]}

    def to_dict(self) -> dict[str, Any]:
        return {k: v for k, v in self.__dict__.items()}


def verify(changes: list[Change], spec_old: str, spec_new: str, clauses: dict[str, Clause],
           requirements: dict[str, Any], testcases: dict[str, Any], feature_tags: list[str]) -> Verification:
    v = Verification(open_questions=list(requirements.get("open_questions", [])))
    change_ids = {c.id for c in changes}
    removed_ids = {c.id for c in changes if c.kind == "removed"}

    # 1) 요구사항
    valid: dict[str, dict[str, Any]] = {}
    for req in requirements["requirements"]:
        reasons = []
        bad_changes = [c for c in req["change_ids"] if c not in change_ids]
        if not req["change_ids"] or bad_changes:
            reasons.append(f"변경점 연결 오류 {bad_changes or '(없음)'}")
        source = spec_old if set(req["change_ids"]) <= removed_ids else spec_new
        if not in_text(req["spec_evidence"], source):
            reasons.append("기획 근거 문장이 기획서에 없음")
        if reasons:
            v.invalid_requirements.append({**req, "reasons": reasons})
            continue
        refs = []
        for ref in req["policy_refs"]:
            if quote_matches(clauses, ref["clause_id"], ref["quote"]):
                refs.append(ref)
            else:
                v.dropped_policy_claims.append(f"{req['id']} → [{ref['clause_id']}] \"{ref['quote']}\" (조항 없음 또는 인용 불일치)")
        conditions = [d for d in req["derived_conditions"] if d["clause_id"] in {r["clause_id"] for r in refs}]
        unknown_tags = [t for t in req["feature_tags"] if t not in feature_tags]
        if unknown_tags:
            v.warnings.append(f"{req['id']}: 등록되지 않은 기능 태그 {unknown_tags}")
        valid[req["id"]] = {**req, "policy_refs": refs, "derived_conditions": conditions,
                            "feature_tags": [t for t in req["feature_tags"] if t in feature_tags]}
    v.requirements = list(valid.values())

    analyzed = {c for r in v.requirements for c in r["change_ids"]}
    v.unanalyzed_changes = sorted(change_ids - analyzed)

    # 2) 정책 충돌: 인용이 맞는 것만 남긴다
    for issue in requirements.get("policy_issues", []):
        if issue["requirement_id"] in valid and quote_matches(clauses, issue["clause_id"], issue["quote"]):
            v.policy_issues.append(issue)
        else:
            v.dropped_policy_claims.append(f"충돌 주장 {issue['requirement_id']} → [{issue['clause_id']}] (근거 확인 실패)")

    # 3) TC
    for tc in testcases["testcases"]:
        reasons = []
        unknown = [r for r in tc["requirement_ids"] if r not in valid]
        if not tc["requirement_ids"] or unknown:
            reasons.append(f"유효하지 않은 요구사항 참조 {unknown or '(없음)'}")
        bad_refs = [r["clause_id"] for r in tc["policy_refs"] if not quote_matches(clauses, r["clause_id"], r["quote"])]
        if bad_refs:
            reasons.append(f"정책 인용 불일치 {bad_refs}")
        has_spec = bool(tc["spec_evidence"]) and (in_text(tc["spec_evidence"], spec_new)
                                                  or in_text(tc["spec_evidence"], spec_old))
        if tc["spec_evidence"] and not has_spec:
            reasons.append(f"기획 근거 문장이 기획서에 없음: \"{tc['spec_evidence']}\"")
        if not tc["spec_evidence"] and not tc["policy_refs"]:
            reasons.append("근거 없음 (기획·정책 어느 쪽도 인용하지 않음)")
        if not tc["steps"] or not tc["expected"]:
            reasons.append("절차 또는 기대결과 없음")
        if reasons:
            v.rejected.append({"tc": tc, "reasons": reasons})
            continue
        for expected in tc["expected"]:
            if VAGUE.search(expected):
                v.warnings.append(f"{tc['id']}: 판정 기준이 모호한 기대결과 \"{expected}\"")
        destructive = any(valid[r]["destructive"] for r in tc["requirement_ids"])
        v.accepted.append({**tc, "test_env_only": destructive})

    # 4) 커버리지
    covered_reqs = {r for tc in v.accepted for r in tc["requirement_ids"]}
    covered_clauses = {(r, p["clause_id"]) for tc in v.accepted for r in tc["requirement_ids"] for p in tc["policy_refs"]}
    for req in v.requirements:
        if req["id"] not in covered_reqs:
            v.requirement_gaps.append({"id": req["id"], "summary": req["summary"]})
        for ref in req["policy_refs"]:
            if ref["relation"] in ("근거", "제약") and (req["id"], ref["clause_id"]) not in covered_clauses:
                v.policy_gaps.append({"requirement_id": req["id"], "clause_id": ref["clause_id"], "quote": ref["quote"]})
    return v
