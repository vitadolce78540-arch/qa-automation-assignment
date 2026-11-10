"""driver fixture.
  QA_DEVICE=fake   : 가상 앱(FakeDevice) 위에서 실행 (데모, 파이프라인 리허설)
  그 외            : 실제 Appium. APPIUM_CAPS_JSON 에 Appium Inspector 에 넣는 JSON 그대로
"""
import json
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))


@pytest.fixture
def driver():
    if os.environ.get("QA_DEVICE") == "fake":
        sys.path.insert(0, os.environ["QA_PROJECT_ROOT"])
        from req_ai.device import FakeDevice
        from req_ai.fake_driver import FakeDriver

        drv = FakeDriver(FakeDevice(Path(os.environ["QA_FAKE_FLOW"])))
    else:
        from appium import webdriver
        from appium.options.android import UiAutomator2Options

        caps = json.loads(os.environ["APPIUM_CAPS_JSON"])
        drv = webdriver.Remote(os.environ.get("APPIUM_SERVER", "http://127.0.0.1:4723"),
                               options=UiAutomator2Options().load_capabilities(caps))
        drv.implicitly_wait(0)
        package = caps["appium:appPackage"]
        drv.terminate_app(package)
        drv.activate_app(package)
    yield drv
    drv.quit()
