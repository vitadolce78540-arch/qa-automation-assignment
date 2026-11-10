package baemin.order.pages;

import baemin.order.core.Config;
import baemin.order.core.Log;
import baemin.order.core.Target;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;

import java.time.Duration;

/**
 * 앱 실행 시 뜨는 타임세일 팝업(전면 인앱 메시지).
 *
 * [실기기 확인] 실행 직후 Braze HTML 인앱 메시지(com.sampleapp:id/com_braze_inappmessage_html)가
 * 화면 전체를 덮는 것을 확인했다. 팝업 내용은 WebView 라 X 버튼의 접근성 이름이 캠페인마다 다를 수 있다.
 *
 * 처리 순서
 *   1. 팝업 컨테이너가 보이는지 확인 (없으면 바로 통과. 기존처럼 try-catch 로 20초 기다리지 않는다)
 *   2. X 버튼 후보를 순서대로 찾아 누른다
 *   3. X 를 못 찾으면 뒤로가기 (Braze 인앱 메시지 기본 동작이 뒤로가기로 닫힘)
 *   4. 팝업이 '실제로 사라졌는지' 확인한다 (누르고 끝내지 않는다)
 */
public final class TimeSalePopup {

    /** [실기기 확인] 전면 인앱 메시지 컨테이너 */
    static final Target CONTAINER = Target.of("타임세일 팝업",
            Target.resourceId(Config.APP_PACKAGE, "com_braze_inappmessage_html"));

    /** [확인 필요] X(닫기) 버튼. 캠페인 HTML 에 따라 접근성 이름이 다를 수 있어 후보를 둔다 */
    static final Target CLOSE = Target.of("타임세일 팝업 X 버튼",
            Target.desc("닫기"), Target.text("닫기"),
            Target.desc("close"), Target.text("✕"), Target.text("X"));

    private TimeSalePopup() {
    }

    /** 팝업이 떠 있으면 닫고 true. 팝업이 없으면 false. */
    public static boolean closeIfShown(Ui ui) {
        return closeIfShown(ui, Duration.ZERO);
    }

    /** 앱 실행 직후처럼 팝업이 '곧 뜰 수 있는' 시점에는 잠깐 기다려 본다. */
    public static boolean closeIfShown(Ui ui, Duration appearTimeout) {
        if (ui.findOptional(CONTAINER, appearTimeout).isEmpty()) {
            return false;
        }
        if (ui.clickIfShown(CLOSE, Timeouts.SHORT)) {
            Log.info("-----TimeSalePopup closed (X)");
        } else {
            ui.back();
            Log.info("-----TimeSalePopup closed (X 없음 → 뒤로가기)");
        }
        ui.waitGone(CONTAINER, Timeouts.SHORT);
        return true;
    }
}
