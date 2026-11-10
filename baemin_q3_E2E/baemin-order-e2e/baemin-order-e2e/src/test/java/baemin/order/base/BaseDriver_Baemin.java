package baemin.order.base;

import baemin.order.core.Config;
import baemin.order.core.DriverSessionRule;
import baemin.order.core.Log;
import baemin.order.core.Ui;
import io.appium.java_client.android.AndroidDriver;
import org.junit.Rule;
import org.junit.rules.TestName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * osc_e2e 의 BaseDriver_iAPv21_SKT 구조(공통 부모 클래스 + 테스트 클래스 상속)를 따른다.
 *
 * 바꾼 점
 *   - static driver / static result / static TCID → 인스턴스 필드. 테스트 간 상태가 섞이지 않고 병렬 실행이 가능하다.
 *   - 세션 생성·종료·실패 스크린샷은 DriverSessionRule 이 담당 (실패 판정을 문자열이 아닌 실제 예외로).
 *   - Password(), Consume_restore() 같은 화면 조작은 부모에 두지 않고 Page Object 로 옮겼다.
 *     부모 클래스에는 "모든 테스트에 공통인 실행 인프라"만 남긴다.
 */
public abstract class BaseDriver_Baemin {

    @Rule
    public final DriverSessionRule session = new DriverSessionRule();

    @Rule
    public final TestName testName = new TestName();

    protected AndroidDriver driver() {
        return session.driver();
    }

    protected Ui ui() {
        return new Ui(session.driver());
    }

    /** 기존 ResultPrint() 형식 유지. 성공했을 때만 호출되므로 PASS 를 별도 변수로 관리하지 않는다. */
    protected void printPass(String tcid) {
        Log.info("-----------------------------------");
        Log.info(" --- " + tcid + " PASS ---");
        Log.info("-----------------------------------");
    }

    /**
     * 기존 스크립트의 CSV 기록(C:\PP_SKT_CSV\...) 기능. 경로는 -Presult.csv 로 주입하고,
     * 쓰기 실패를 catch 후 무시하지 않고 로그로 남긴다.
     */
    protected void appendResultCsv(String tcid, String metricName, double seconds) {
        if (Config.RESULT_CSV.isBlank()) {
            return;
        }
        String line = String.join(",",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")),
                tcid, metricName, String.format("%.2f", seconds)) + System.lineSeparator();
        try {
            Path csv = Path.of(Config.RESULT_CSV);
            if (csv.getParent() != null) {
                Files.createDirectories(csv.getParent());
            }
            Files.writeString(csv, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.info("결과 CSV 기록 실패: " + e.getMessage());
        }
    }
}
