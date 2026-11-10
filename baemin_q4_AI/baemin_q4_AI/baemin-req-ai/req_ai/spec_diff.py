"""① 기획서 변경점 추출 (LLM 없음).

기획서 전체를 매번 LLM 에 넣으면 이미 검증한 부분까지 TC 가 다시 나오고 결과도 매번 달라진다.
이전 버전과 문장 단위로 비교해 추가·변경·삭제된 문장만 다음 단계로 넘긴다.
"""
from __future__ import annotations

import difflib
import re
from dataclasses import asdict, dataclass

MODIFIED_SIMILARITY = 0.5   # 이 이상 비슷하면 '삭제+추가'가 아니라 '변경'으로 본다


@dataclass(frozen=True)
class Sentence:
    section: str
    text: str


@dataclass
class Change:
    id: str
    kind: str          # added | modified | removed
    section: str
    before: str
    after: str

    def to_dict(self) -> dict:
        return asdict(self)


def split_sentences(markdown: str) -> list[Sentence]:
    """'## 섹션' 아래의 목록·문단을 문장 단위로 나눈다. 제목과 인용문은 제외."""
    section = ""
    result: list[Sentence] = []
    for raw in markdown.splitlines():
        line = raw.strip()
        if not line or line.startswith(">") or line.startswith("# "):
            continue
        if line.startswith("## "):
            section = line[3:].strip()
            continue
        line = re.sub(r"^[-*]\s+", "", line)
        for sentence in re.split(r"(?<=[.다])\s+(?=\S)", line):
            if sentence.strip():
                result.append(Sentence(section, sentence.strip()))
    return result


def _similarity(a: str, b: str) -> float:
    return difflib.SequenceMatcher(None, a, b).ratio()


def diff_specs(old: str, new: str) -> list[Change]:
    before, after = split_sentences(old), split_sentences(new)
    matcher = difflib.SequenceMatcher(None, [s.text for s in before], [s.text for s in after], autojunk=False)
    changes: list[Change] = []

    def add(kind: str, section: str, b: str, a: str) -> None:
        changes.append(Change(f"C{len(changes) + 1}", kind, section, b, a))

    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            continue
        olds, news = list(before[i1:i2]), list(after[j1:j2])
        # 바뀐 구간 안에서 가장 비슷한 문장끼리 '변경'으로 짝짓는다
        for o in list(olds):
            best = max(news, key=lambda n: _similarity(o.text, n.text), default=None)
            if best is not None and _similarity(o.text, best.text) >= MODIFIED_SIMILARITY:
                add("modified", best.section, o.text, best.text)
                olds.remove(o)
                news.remove(best)
        for o in olds:
            add("removed", o.section, o.text, "")
        for n in news:
            add("added", n.section, "", n.text)
    return changes
