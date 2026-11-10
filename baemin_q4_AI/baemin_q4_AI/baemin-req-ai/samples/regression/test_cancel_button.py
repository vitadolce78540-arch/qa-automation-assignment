from appium.webdriver.common.appiumby import AppiumBy
from helpers import click, find


def test_cancel_button_visible_when_waiting(driver):
    click(driver, (AppiumBy.ACCESSIBILITY_ID, "하단탭바 주문내역탭"))
    click(driver, (AppiumBy.ID, "com.sampleapp:id/order_item_ongoing"))
    assert find(driver, (AppiumBy.ID, "com.sampleapp:id/status_step_waiting")).get_attribute("selected") == "true"
    button = find(driver, (AppiumBy.ID, "com.sampleapp:id/order_cancel_button"))
    assert button.text == "주문 취소"
    assert button.get_attribute("enabled") == "true"
