from appium.webdriver.common.appiumby import AppiumBy
from helpers import find


def test_home_tabs(driver):
    for tab in ("하단탭바 홈탭", "하단탭바 찜탭", "하단탭바 주문내역탭", "하단탭바 마이배민탭"):
        find(driver, (AppiumBy.ACCESSIBILITY_ID, tab))
