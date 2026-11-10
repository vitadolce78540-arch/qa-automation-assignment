package baemin.order.core;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;

/**
 * 실행 설정.
 * 기존 BaseDriver 는 UDID·Appium 주소·스크린샷 경로(C:\ProgramData\Jenkins\...)가 코드에 고정되어
 * 단말이나 Jenkins 노드가 바뀔 때마다 코드를 고쳐야 했다. 여기서는 -P(시스템 프로퍼티)·환경 변수로 주입한다.
 */
public final class Config {

    private Config() {
    }

    /** Appium 2 이상은 /wd/hub 경로가 없다 (기존 스크립트는 Appium 1 기준 /wd/hub). */
    public static final URL APPIUM_SERVER = url(value("appium.server", "http://127.0.0.1:4723"));

    /** adb devices 의 단말 ID. 비우면 Appium 이 연결된 단말을 고른다. */
    public static final String UDID = value("udid", "");

    /** 실기기에서 확인한 배달의민족 패키지명 */
    public static final String APP_PACKAGE = value("app.package", "com.sampleapp");

    /** 실기기에서 확인한 실행 후 메인 액티비티 */
    public static final String APP_ACTIVITY = value("app.activity",
            "com.baemin.presentation.ui.root.ui.RootContainerActivity");

    /** 느린 단말·Jenkins 노드에서 모든 대기 시간을 일괄 확대 (예: 1.5) */
    public static final double TIMEOUT_SCALE = Double.parseDouble(value("timeout.scale", "1.0"));

    /** 실패 시 스크린샷·화면 XML·logcat 저장 위치 */
    public static final Path ARTIFACTS_DIR = Path.of(value("artifacts.dir", "build/test-artifacts"));

    /** 결제 화면 진입 시간 누적 기록 CSV. 비우면 기록하지 않는다. */
    public static final String RESULT_CSV = value("result.csv", "");

    /** 테스트 후 장바구니를 비울지 (실계정으로 돌릴 때 장바구니가 쌓이지 않도록 기본 true) */
    public static final boolean CLEANUP_CART = Boolean.parseBoolean(value("cleanup.cart", "true"));

    private static String value(String key, String defaultValue) {
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv(key.replace('.', '_').toUpperCase());
        return (env != null && !env.isBlank()) ? env : defaultValue;
    }

    private static URL url(String value) {
        try {
            return URI.create(value).toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("appium.server 형식 오류: " + value, e);
        }
    }
}
