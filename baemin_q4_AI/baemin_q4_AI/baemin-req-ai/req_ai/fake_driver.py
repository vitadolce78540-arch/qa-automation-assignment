"""Appium WebDriver 흉내. 기존 pytest + Appium 스크립트를 '코드 수정 없이' 가상 앱(FakeDevice) 위에서 실행한다.

데모·CI 리허설용이다. 실제 단말에서는 conftest 가 진짜 Appium driver 를 넘겨주므로 스크립트는 그대로다.
지원 로케이터: id, accessibility id, -android uiautomator (text("..") / description(".."))
"""
from __future__ import annotations

import re

from selenium.common.exceptions import NoSuchElementException

from .device import FakeDevice
from .screen import Element


class FakeElement:
    def __init__(self, element: Element, device: FakeDevice) -> None:
        self._e, self._device = element, device

    @property
    def text(self) -> str:
        return self._e.text

    def click(self) -> None:
        self._device.tap(self._e)

    def is_displayed(self) -> bool:
        return True

    def is_enabled(self) -> bool:
        return self._e.enabled

    def get_attribute(self, name: str) -> str | None:
        return {
            "text": self._e.text,
            "content-desc": self._e.desc,
            "resource-id": self._e.resource_id,
            "selected": str(self._e.selected).lower(),
            "enabled": str(self._e.enabled).lower(),
            "checked": str(self._e.checked).lower(),
        }.get(name)


class FakeDriver:
    def __init__(self, device: FakeDevice) -> None:
        self.device = device

    def find_elements(self, by: str, value: str) -> list[FakeElement]:
        return [FakeElement(e, self.device) for e in self.device.screen().elements if _match(e, by, value)]

    def find_element(self, by: str, value: str) -> FakeElement:
        found = self.find_elements(by, value)
        if not found:
            raise NoSuchElementException(f"{by}={value} (screen={self.device.current})")
        return found[0]

    def back(self) -> None:
        self.device.back()

    def terminate_app(self, app_id: str) -> None:
        self.device.reset()

    def activate_app(self, app_id: str) -> None:
        pass

    def get_window_size(self) -> dict[str, int]:
        return {"width": 1080, "height": 2340}

    def execute_script(self, script: str, args: dict | None = None) -> bool:
        return False

    def implicitly_wait(self, seconds: float) -> None:
        pass

    def quit(self) -> None:
        pass


def _match(e: Element, by: str, value: str) -> bool:
    if by == "id":
        return e.resource_id == value
    if by == "accessibility id":
        return e.desc == value
    if by == "-android uiautomator":
        if m := re.fullmatch(r'new UiSelector\(\)\.text\("(.*)"\)', value):
            return e.text == m.group(1).replace('\\"', '"')
        if m := re.fullmatch(r'new UiSelector\(\)\.description\("(.*)"\)', value):
            return e.desc == m.group(1).replace('\\"', '"')
        raise NotImplementedError(f"가상 드라이버가 지원하지 않는 UiSelector: {value}")
    raise NotImplementedError(f"가상 드라이버가 지원하지 않는 로케이터: {by}")
