"""AI 에이전트 실행 기록에서 생성한 회귀 스크립트. 직접 수정하지 말고 다시 생성한다."""
import os
import re

from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

TIMEOUT = float(os.environ.get("QA_TIMEOUT", "10"))


def find(driver, by):
    return WebDriverWait(driver, TIMEOUT).until(EC.visibility_of_element_located(by))


def click(driver, by):
    WebDriverWait(driver, TIMEOUT).until(EC.element_to_be_clickable(by)).click()


def scroll(driver, direction):
    size = driver.get_window_size()
    driver.execute_script("mobile: scrollGesture", {
        "left": size["width"] // 10, "top": size["height"] // 5,
        "width": size["width"] * 8 // 10, "height": size["height"] * 3 // 5,
        "direction": direction, "percent": 0.7})


def test_tc_01(driver):
    """가게 접수 전 '도착 시간 계산 중' 표시

    기획: 가게가 접수하기 전에는 예상 도착 시간 대신 "도착 시간 계산 중"을 표시한다.
    정책 [DLV-01]: 예상 도착 시간은 가게가 주문을 접수한 뒤에 산정한다
    AI 에이전트 실행 기록에서 자동 변환됨. 로케이터는 실행 당시 화면에서 유일성을 확인했다.
    """
    # 기대결과
    #   [0] 예상 도착 시간 영역에 '도착 시간 계산 중'이 표시된다
    click(driver, (AppiumBy.ACCESSIBILITY_ID, "하단탭바 주문내역탭"))  # 주문내역 탭으로 이동
    click(driver, (AppiumBy.ID, "com.sampleapp:id/order_item_ongoing"))  # 진행 중 주문을 연다
    el = find(driver, (AppiumBy.ID, "com.sampleapp:id/eta_text"))  # 기대결과[0]
    assert el.text == '도착 시간 계산 중'


def test_tc_02(driver):
    """주문 후 5분 이내·접수 대기 상태에서 취소 버튼 노출

    기획: 주문 취소 버튼은 주문 후 5분 이내이면서 접수 대기 상태일 때만 노출한다.
    정책 [ORD-CANCEL-02]: 주문 후 5분이 지나면 앱에서 직접 취소할 수 없으며 고객센터를 통해 취소한다
    AI 에이전트 실행 기록에서 자동 변환됨. 로케이터는 실행 당시 화면에서 유일성을 확인했다.
    """
    # 기대결과
    #   [0] 현재 상태가 '접수 대기'로 강조 표시된다
    #   [1] 주문 취소 버튼이 정상적으로 표시된다
    click(driver, (AppiumBy.ACCESSIBILITY_ID, "하단탭바 주문내역탭"))  # 주문내역 탭으로 이동
    click(driver, (AppiumBy.ID, "com.sampleapp:id/order_item_ongoing"))  # 진행 중 주문을 연다
    el = find(driver, (AppiumBy.ID, "com.sampleapp:id/status_step_waiting"))  # 기대결과[0]
    assert el.get_attribute("selected") == 'true'
    el = find(driver, (AppiumBy.ID, "com.sampleapp:id/order_cancel_button"))  # 기대결과[1]
    assert el.text == '주문 취소'
    el = find(driver, (AppiumBy.ID, "com.sampleapp:id/order_cancel_button"))  # 기대결과[1]
    assert el.get_attribute("enabled") == 'true'


def test_tc_04(driver):
    """결제 정보에 쿠폰명과 할인 금액 표시

    기획: 쿠폰이 적용된 주문은 결제 정보에 적용된 쿠폰명과 할인 금액을 표시한다.
    AI 에이전트 실행 기록에서 자동 변환됨. 로케이터는 실행 당시 화면에서 유일성을 확인했다.
    """
    # 기대결과
    #   [0] 적용된 쿠폰명이 표시된다
    #   [1] 할인 금액 1,000원이 표시된다
    click(driver, (AppiumBy.ACCESSIBILITY_ID, "하단탭바 주문내역탭"))  # 주문내역 탭으로 이동
    click(driver, (AppiumBy.ID, "com.sampleapp:id/order_item_ongoing"))  # 진행 중 주문을 연다
    el = find(driver, (AppiumBy.ID, "com.sampleapp:id/coupon_name"))  # 기대결과[0]
    assert '쿠폰' in el.text
    el = find(driver, (AppiumBy.ID, "com.sampleapp:id/discount_amount"))  # 기대결과[1]
    assert '1,000원' in el.text


def test_tc_06(driver):
    """쿠폰 할인 반영 결제 금액

    기획: 쿠폰이 적용된 주문은 결제 정보에 적용된 쿠폰명과 할인 금액을 표시한다.
    정책 [PAY-01]: 결제 금액은 주문 금액과 배달팁의 합에서 할인 금액을 뺀 값이다
    AI 에이전트 실행 기록에서 자동 변환됨. 로케이터는 실행 당시 화면에서 유일성을 확인했다.
    """
    # 기대결과
    #   [0] 결제 금액이 주문 금액 + 배달팁 - 할인 금액인 20,000원으로 표시된다
    click(driver, (AppiumBy.ACCESSIBILITY_ID, "하단탭바 주문내역탭"))  # 주문내역 탭으로 이동
    click(driver, (AppiumBy.ID, "com.sampleapp:id/order_item_ongoing"))  # 진행 중 주문을 연다
    el = find(driver, (AppiumBy.ID, "com.sampleapp:id/paid_amount"))  # 기대결과[0]
    assert el.text == '결제 금액 20,000원'
