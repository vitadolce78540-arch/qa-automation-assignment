package baemin.order.pages;

import baemin.order.core.Target;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;

import java.time.Duration;

/**
 * 모든 화면의 공통 부모.
 *
 * Page Object 를 만드는 순간 "그 화면에 도착했는지"를 확인한다.
 * 기존 스크립트는 다음 요소를 못 찾을 때 비로소 실패해, 어느 화면에서 어긋났는지 알 수 없었다.
 *
 * 로케이터 표기
 *   [실기기 확인] 2026-11 S24 Ultra(Android 17)에서 실제 요소 트리로 확인한 값
 *   [확인 필요]   흐름을 끝까지 따라가 확인하지 못한 값. 첫 실행 전 Appium Inspector 로 맞춘다
 */
public abstract class BasePage {

    protected final Ui ui;

    protected BasePage(Ui ui, Target screenAnchor, Duration arrivalTimeout) {
        this(ui, null, screenAnchor, arrivalTimeout);
    }

    /**
     * @param leftScreen 떠나온 화면에만 있는 요소. 같은 문구가 두 화면에 모두 있을 때(예: 홈의 '음식배달' 버튼과
     *                   음식배달 화면 제목) 이전 화면이 사라진 것까지 확인해 "도착했다"는 오판을 막는다.
     */
    protected BasePage(Ui ui, Target leftScreen, Target screenAnchor, Duration arrivalTimeout) {
        this.ui = ui;
        if (leftScreen != null) {
            ui.waitGone(leftScreen, arrivalTimeout);
        }
        TimeSalePopup.closeIfShown(ui);      // 화면 전환 시 뜨는 인앱 메시지 안전망
        ui.find(screenAnchor, arrivalTimeout);
    }

    protected BasePage(Ui ui, Target screenAnchor) {
        this(ui, screenAnchor, Timeouts.NETWORK);
    }

    /** "18,000원 담기" → 18000. 숫자가 없으면 비교 전에 명확히 실패시킨다. */
    protected static int parseWon(String text, String what) {
        String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            throw new AssertionError("[" + what + "] 금액을 읽을 수 없음: '" + text + "'");
        }
        return Integer.parseInt(digits);
    }

    /** Compose 요소는 text 대신 content-desc 에 값이 있는 경우가 많아 둘 다 확인한다. */
    protected static String label(org.openqa.selenium.WebElement element) {
        String text = element.getText();
        if (text != null && !text.isBlank()) {
            return text.trim();
        }
        String desc = element.getAttribute("content-desc");
        return desc == null ? "" : desc.trim();
    }
}
