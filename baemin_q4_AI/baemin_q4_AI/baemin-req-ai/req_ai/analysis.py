"""②④ LLM 단계: 변경점 + 관련 정책 조항 → 요구사항 분석 → TC 생성.

LLM 에 주는 것은 '변경된 문장'과 '검색된 정책 조항'뿐이다. 기획서 전체나 정책서 전체를 주지 않는다.
결과가 믿을 만한지는 verifier 가 코드로 판정한다.
"""
from __future__ import annotations

import json
from typing import Any

from .llm import LLM, load_prompt
from .policy import Clause
from .spec_diff import Change

POLICY_REF = {
    "type": "object",
    "properties": {
        "clause_id": {"type": "string"},
        "quote": {"type": "string", "description": "조항 본문에서 그대로 복사한 문구"},
        "relation": {"type": "string", "enum": ["근거", "제약", "충돌"]},
    },
    "required": ["clause_id", "quote", "relation"],
}


def requirements_schema(feature_tags: list[str]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "requirements": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string", "description": "R1, R2 ..."},
                        "change_ids": {"type": "array", "items": {"type": "string"}},
                        "summary": {"type": "string"},
                        "spec_evidence": {"type": "string", "description": "변경 문장을 그대로 복사 (삭제면 이전 문장)"},
                        "feature_tags": {"type": "array", "items": {"type": "string", "enum": feature_tags}},
                        "policy_refs": {"type": "array", "items": POLICY_REF},
                        "derived_conditions": {
                            "type": "array",
                            "description": "정책 조항에서 파생되는 경계·예외 조건 (기획서에는 없지만 지켜야 하는 것)",
                            "items": {
                                "type": "object",
                                "properties": {"clause_id": {"type": "string"}, "condition": {"type": "string"}},
                                "required": ["clause_id", "condition"],
                            },
                        },
                        "risk": {"type": "string", "enum": ["high", "medium", "low"]},
                        "destructive": {"type": "boolean"},
                    },
                    "required": ["id", "change_ids", "summary", "spec_evidence", "feature_tags", "policy_refs",
                                 "derived_conditions", "risk", "destructive"],
                },
            },
            "policy_issues": {
                "type": "array",
                "description": "기획 문장과 정책 조항이 충돌하는 것으로 보이는 항목",
                "items": {
                    "type": "object",
                    "properties": {
                        "requirement_id": {"type": "string"},
                        "clause_id": {"type": "string"},
                        "quote": {"type": "string"},
                        "issue": {"type": "string"},
                    },
                    "required": ["requirement_id", "clause_id", "quote", "issue"],
                },
            },
            "open_questions": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["requirements", "policy_issues", "open_questions"],
    }


def testcases_schema(feature_tags: list[str]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "testcases": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string", "description": "TC-01, TC-02 ..."},
                        "requirement_ids": {"type": "array", "items": {"type": "string"}},
                        "type": {"type": "string", "enum": ["기능", "정책-경계", "정책-예외"]},
                        "title": {"type": "string"},
                        "precondition": {"type": "string"},
                        "steps": {"type": "array", "items": {"type": "string"}},
                        "expected": {"type": "array", "items": {"type": "string"}},
                        "spec_evidence": {"type": "string", "description": "근거 기획 문장 (정책만으로 만든 TC 면 빈 문자열)"},
                        "policy_refs": {"type": "array", "items": POLICY_REF},
                        "feature_tags": {"type": "array", "items": {"type": "string", "enum": feature_tags}},
                        "automatable": {"type": "boolean"},
                        "manual_reason": {"type": "string"},
                    },
                    "required": ["id", "requirement_ids", "type", "title", "precondition", "steps", "expected",
                                 "spec_evidence", "policy_refs", "feature_tags", "automatable"],
                },
            },
        },
        "required": ["testcases"],
    }


def _clauses_block(clauses: list[Clause]) -> str:
    return "\n".join(f"[{c.id}] ({c.section}) {c.text}" for c in clauses) or "(관련 조항 없음)"


def analyze_changes(llm: LLM, changes: list[Change], related: dict[str, list[Clause]],
                    feature_tags: list[str]) -> dict[str, Any]:
    blocks = []
    for ch in changes:
        blocks.append(
            f"<change id=\"{ch.id}\" kind=\"{ch.kind}\" section=\"{ch.section}\">\n"
            f"  이전: {ch.before or '(없음)'}\n  변경: {ch.after or '(삭제됨)'}\n"
            f"  <related_policy>\n{_clauses_block(related[ch.id])}\n  </related_policy>\n</change>")
    return llm.complete_json(
        key="analyze",
        system=load_prompt("analyze_requirements"),
        user="<changes>\n" + "\n".join(blocks) + "\n</changes>\n\n<feature_tags>" + ", ".join(feature_tags)
             + "</feature_tags>",
        schema=requirements_schema(feature_tags),
    )


def generate_testcases(llm: LLM, requirements: dict[str, Any], feature_tags: list[str]) -> dict[str, Any]:
    return llm.complete_json(
        key="generate",
        system=load_prompt("generate_tc"),
        user=f"<requirements>\n{json.dumps(requirements, ensure_ascii=False, indent=2)}\n</requirements>",
        schema=testcases_schema(feature_tags),
    )
