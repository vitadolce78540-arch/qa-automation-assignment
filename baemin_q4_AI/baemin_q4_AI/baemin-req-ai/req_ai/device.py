"""단말 조작 계층.

- AppiumDevice : 실제 단말 (Appium 2 + UiAutomator2)
- FakeDevice   : 화면 덤프 XML 과 전이 정의(flow.json)로 움직이는 가짜 단말. 단말 없이 에이전트 루프를 시연·테스트한다.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

from .screen import Element, Screen, parse


class Device(Protocol):
    def screen(self) -> Screen: ...
    def tap(self, element: Element) -> None: ...
    def type_text(self, element: Element, text: str) -> None: ...
    def scroll(self, direction: str) -> None: ...
    def back(self) -> None: ...
    def screenshot(self, path: Path) -> None: ...


@dataclass
class FakeDevice:
    flow_file: Path
    current: str = ""
    history: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.flow = json.loads(self.flow_file.read_text(encoding="utf-8"))
        self.current = self.current or self.flow["start"]

    def reset(self) -> None:
        self.current, self.history = self.flow["start"], []

    def screen(self) -> Screen:
        xml_file = self.flow_file.parent / self.flow["screens"][self.current]["xml"]
        return parse(xml_file.read_text(encoding="utf-8"))

    def tap(self, element: Element) -> None:
        transitions = self.flow["screens"][self.current].get("transitions", {})
        for key in (f"id:{element.resource_id}", f"desc:{element.desc}", f"text:{element.text}"):
            if key in transitions:
                target = transitions[key]
                if target == "@back":
                    self.back()
                else:
                    self.history.append(self.current)
                    self.current = target
                return
        # 정의되지 않은 탭은 화면 변화 없음 (실제 앱에서 아무 반응이 없는 경우와 같다)

    def type_text(self, element: Element, text: str) -> None:
        pass

    def scroll(self, direction: str) -> None:
        pass

    def back(self) -> None:
        if self.history:
            self.current = self.history.pop()

    def screenshot(self, path: Path) -> None:
        path.with_suffix(".xml").write_text(self.screen().xml, encoding="utf-8")


class AppiumDevice:
    """실제 단말. 요소는 화면 좌표 중심을 탭한다 (에이전트가 고른 요소를 그대로 누르기 위해).
    회귀 스크립트로 변환할 때는 좌표가 아니라 로케이터로 바꾼다 (exporter 참고)."""

    def __init__(self, server: str, capabilities: dict) -> None:
        from appium import webdriver
        from appium.options.android import UiAutomator2Options

        self.driver = webdriver.Remote(server, options=UiAutomator2Options().load_capabilities(capabilities))
        self.driver.implicitly_wait(0)

    def screen(self) -> Screen:
        return parse(self.driver.page_source)

    def tap(self, element: Element) -> None:
        x1, y1, x2, y2 = element.bounds
        self.driver.execute_script("mobile: clickGesture", {"x": (x1 + x2) // 2, "y": (y1 + y2) // 2})

    def type_text(self, element: Element, text: str) -> None:
        self.tap(element)
        self.driver.execute_script("mobile: type", {"text": text})

    def scroll(self, direction: str) -> None:
        size = self.driver.get_window_size()
        self.driver.execute_script("mobile: scrollGesture", {
            "left": size["width"] // 10, "top": size["height"] // 5,
            "width": size["width"] * 8 // 10, "height": size["height"] * 3 // 5,
            "direction": direction, "percent": 0.7})

    def back(self) -> None:
        self.driver.back()

    def screenshot(self, path: Path) -> None:
        self.driver.get_screenshot_as_file(str(path.with_suffix(".png")))
        path.with_suffix(".xml").write_text(self.driver.page_source, encoding="utf-8")

    def quit(self) -> None:
        self.driver.quit()
