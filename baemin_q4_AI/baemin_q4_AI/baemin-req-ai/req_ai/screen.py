"""화면 덤프(UiAutomator XML) → LLM 에 넘길 요소 목록.

LLM 에는 스크린샷 대신 요소 목록을 준다.
  - 토큰이 적고, 요소마다 e1, e2 같은 짧은 ID 를 붙여 "어떤 요소를 눌러라"를 정확히 지정할 수 있다.
  - LLM 이 고른 ID 가 현재 화면에 실제로 있는지 코드로 확인할 수 있다 (환각 차단).
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Iterable


@dataclass(frozen=True)
class Element:
    eid: str
    cls: str
    text: str
    desc: str
    resource_id: str
    clickable: bool
    enabled: bool
    selected: bool
    checked: bool
    scrollable: bool
    bounds: tuple[int, int, int, int]

    @property
    def label(self) -> str:
        return self.text or self.desc

    def describe(self) -> str:
        parts = [self.eid, f"[{self.cls.rsplit('.', 1)[-1]}]"]
        if self.text:
            parts.append(f'text="{self.text}"')
        if self.desc:
            parts.append(f'desc="{self.desc}"')
        if self.resource_id:
            parts.append(f"id={self.resource_id.split(':id/')[-1]}")
        flags = [name for name, on in (("clickable", self.clickable), ("selected", self.selected),
                                       ("checked", self.checked), ("scrollable", self.scrollable)) if on]
        if not self.enabled:
            flags.append("disabled")
        if flags:
            parts.append(" ".join(flags))
        return " ".join(parts)


@dataclass
class Screen:
    xml: str
    elements: list[Element]

    def get(self, eid: str) -> Element | None:
        return next((e for e in self.elements if e.eid == eid), None)

    def signature(self) -> str:
        """같은 화면인지 판단하는 값 (루프 감지용). 위치는 빼고 구성만 본다."""
        return "|".join(f"{e.resource_id}:{e.label}" for e in self.elements)

    def to_prompt(self) -> str:
        return "\n".join(e.describe() for e in self.elements)

    def count(self, *, resource_id: str = "", desc: str = "", text: str = "") -> int:
        """정확히 일치하는 노드 수 (로케이터 유일성 검증용). 목록에서 빠진 노드까지 포함해 원본 XML 기준으로 센다."""
        n = 0
        for node in ET.fromstring(self.xml).iter("node"):
            if resource_id and node.get("resource-id") != resource_id:
                continue
            if desc and node.get("content-desc") != desc:
                continue
            if text and node.get("text") != text:
                continue
            n += 1
        return n


def _bounds(raw: str) -> tuple[int, int, int, int]:
    nums = [int(n) for n in re.findall(r"-?\d+", raw or "")]
    return (nums + [0, 0, 0, 0])[:4]  # type: ignore[return-value]


def _is_useful(node: ET.Element) -> bool:
    return bool(node.get("text") or node.get("content-desc") or node.get("clickable") == "true"
                or node.get("scrollable") == "true")


def _skip_package(node: ET.Element, packages: Iterable[str]) -> bool:
    return node.get("package", "") in packages


def parse(xml: str, ignore_packages: Iterable[str] = ("com.android.systemui",)) -> Screen:
    root = ET.fromstring(xml)
    elements: list[Element] = []
    for node in root.iter("node"):
        if _skip_package(node, ignore_packages) or not _is_useful(node):
            continue
        elements.append(Element(
            eid=f"e{len(elements) + 1}",
            cls=node.get("class", ""),
            text=node.get("text", ""),
            desc=node.get("content-desc", ""),
            resource_id=node.get("resource-id", ""),
            clickable=node.get("clickable") == "true",
            enabled=node.get("enabled", "true") == "true",
            selected=node.get("selected") == "true",
            checked=node.get("checked") == "true",
            scrollable=node.get("scrollable") == "true",
            bounds=_bounds(node.get("bounds", "")),
        ))
    return Screen(xml=xml, elements=elements)
