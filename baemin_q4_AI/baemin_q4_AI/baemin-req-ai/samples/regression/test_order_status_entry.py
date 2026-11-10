from appium.webdriver.common.appiumby import AppiumBy
from helpers import click, find


def test_enter_order_status(driver):
    click(driver, (AppiumBy.ACCESSIBILITY_ID, "하단탭바 주문내역탭"))
    click(driver, (AppiumBy.ID, "com.sampleapp:id/order_item_ongoing"))
    assert find(driver, (AppiumBy.ID, "com.sampleapp:id/screen_title")).text == "주문 현황"
