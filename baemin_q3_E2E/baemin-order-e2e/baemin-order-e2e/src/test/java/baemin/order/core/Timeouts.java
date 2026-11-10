package baemin.order.core;

import java.time.Duration;

/**
 * 목적별 대기 시간.
 * 기존 스크립트의 Thread.sleep(2000)·(2500)·(4000) 과 implicitlyWait(20초) 를 대체한다.
 * 값은 '상한선'이며 조건이 충족되면 즉시 다음 단계로 넘어간다.
 */
public final class Timeouts {

    private Timeouts() {
    }

    /** 있으면 처리하고 없으면 넘어가는 요소 (타임세일 팝업, 선택적 버튼) */
    public static final Duration OPTIONAL = scaled(3);
    /** 탭 직후 상태 반영 (옵션 선택, 장바구니 수량) */
    public static final Duration SHORT = scaled(3);
    /** 일반 화면 전환 */
    public static final Duration DEFAULT = scaled(10);
    /** 서버 데이터를 받아 그리는 화면 (가게 목록, 가게 상세, 장바구니) */
    public static final Duration NETWORK = scaled(15);
    /** 앱 콜드 스타트 (스플래시 → 홈) */
    public static final Duration APP_LAUNCH = scaled(30);
    /** 폴링 간격 */
    public static final Duration POLL = Duration.ofMillis(300);

    private static Duration scaled(long seconds) {
        return Duration.ofMillis(Math.round(seconds * 1000 * Config.TIMEOUT_SCALE));
    }
}
