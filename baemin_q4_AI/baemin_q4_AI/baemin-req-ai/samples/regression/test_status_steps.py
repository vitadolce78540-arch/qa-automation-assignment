from appium.webdriver.common.appiumby import AppiumBy
from helpers import click, find


def test_status_steps(driver):
    click(driver, (AppiumBy.ACCESSIBILITY_ID, "하단탭바 주문내역탭"))
    click(driver, (AppiumBy.ID, "com.sampleapp:id/order_item_ongoing"))
    steps = {"waiting": "접수 대기", "cooking": "조리 중", "delivering": "배달 중", "done": "배달 완료"}
    for key, label in steps.items():
        assert find(driver, (AppiumBy.ID, f"com.sampleapp:id/status_step_{key}")).text == label
    assert find(driver, (AppiumBy.ID, "com.sampleapp:id/status_step_waiting")).get_attribute("selected") == "true"
