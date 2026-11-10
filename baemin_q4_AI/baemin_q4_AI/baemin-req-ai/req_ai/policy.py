"""③ 정책 문서 검색과 정책 근거 확인 (LLM 없음).

정책서를 조항 단위로 읽고, 변경 문장마다 관련 조항을 찾아 LLM 에 함께 넘긴다.
LLM 이 정책 전체를 기억하거나 추측하지 않게 하고, 인용한 조항이 실제로 있는지는 코드로 확인한다.

검색은 형태소 분석기 없이 "정책 섹션 이름 매칭 + 글자 2-gram 유사도"로 후보를 고른다.
조항 수가 수백 개를 넘으면 임베딩 검색으로 바꾸면 된다 (인터페이스는 같다).
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass

CLAUSE_LINE = re.compile(r"^\s*[-*]\s*\[(?P<id>[A-Z]+(?:-[A-Z]+)*-\d+)\]\s*(?P<text>.+?)\s*$")


@dataclass(frozen=True)
class Clause:
    id: str
    section: str
    text: str


def load_policy(markdown: str) -> dict[str, Clause]:
    section, clauses = "", {}
    for line in markdown.splitlines():
        if line.startswith("## "):
            section = line[3:].strip()
            continue
        m = CLAUSE_LINE.match(line)
        if m:
            clauses[m["id"]] = Clause(m["id"], section, m["text"])
    return clauses


def _grams(text: str) -> set[str]:
    compact = re.sub(r"[\s\W_]+", "", text)
    return {compact[i:i + 2] for i in range(len(compact) - 1)}


def search(clauses: dict[str, Clause], query: str, top_k: int = 3, min_score: float = 0.15) -> list[Clause]:
    """두 가지를 합친다.
    1) 섹션 매칭: 문장에 정책 섹션 이름(예: '쿠폰', '주문 취소')이 있으면 그 섹션 조항 전체
       - "쿠폰 여러 장 표시"처럼 글자는 안 겹치지만 같은 주제의 조항("주문당 1장")을 놓치지 않기 위함
    2) 유사도: 글자 2-gram 코사인 유사도 상위 top_k
    """
    compact_query = re.sub(r"\s+", "", query)
    picked: dict[str, Clause] = {}
    for clause in clauses.values():
        if re.sub(r"\s+", "", clause.section) in compact_query:
            picked[clause.id] = clause

    q = _grams(query)
    scored = []
    for clause in clauses.values():
        c = _grams(clause.section + " " + clause.text)
        if q and c:
            score = len(q & c) / math.sqrt(len(q) * len(c))
            if score >= min_score:
                scored.append((score, clause))
    scored.sort(key=lambda x: (-x[0], x[1].id))
    for _, clause in scored[:top_k]:
        picked.setdefault(clause.id, clause)
    return sorted(picked.values(), key=lambda c: c.id)


def _norm(text: str) -> str:
    return re.sub(r"\s+", " ", text.replace("“", "\"").replace("”", "\"")).strip().rstrip(".")


def quote_matches(clauses: dict[str, Clause], clause_id: str, quote: str) -> bool:
    """조항 ID 가 있고, 인용문이 그 조항 본문의 일부여야 한다 (다른 조항 문장을 붙여 쓰는 것도 차단)."""
    clause = clauses.get(clause_id)
    return clause is not None and bool(_norm(quote)) and _norm(quote) in _norm(clause.text)
