"""기존 회귀 스크립트 공용 헬퍼 (조건 대기만 사용)."""
import os

from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

TIMEOUT = float(os.environ.get("QA_TIMEOUT", "10"))


def find(driver, by):
    return WebDriverWait(driver, TIMEOUT).until(EC.visibility_of_element_located(by))


def click(driver, by):
    WebDriverWait(driver, TIMEOUT).until(EC.element_to_be_clickable(by)).click()
