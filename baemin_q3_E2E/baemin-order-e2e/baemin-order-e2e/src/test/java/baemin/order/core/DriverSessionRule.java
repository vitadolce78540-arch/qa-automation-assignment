package baemin.order.core;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.openqa.selenium.OutputType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * Appium 세션 수명 + 실패 아티팩트 수집을 하나의 JUnit 4 Rule 로 묶는다.
 *
 * 기존 BaseDriver 는 @After 에서 result != "PASS" 로 실패를 판단했다.
 *   - 문자열을 != 로 비교해 의도대로 동작한다는 보장이 없고,
 *   - 예외로 중간에 끝나도 result 갱신에 의존하며,
 *   - 스크린샷 한 장만 남아 원인 분석이 어렵다.
 * Rule 은 테스트(@Before/@After 포함)를 감싸므로 실제 실패 여부를 정확히 알고,
 * 세션 종료 '전'에 스크린샷·화면 XML·logcat 을 함께 저장할 수 있다.
 */
public class DriverSessionRule implements TestRule {

    private AndroidDriver driver;

    public AndroidDriver driver() {
        if (driver == null) {
            throw new IllegalStateException("Appium 세션이 아직 없음 (Rule 밖에서 호출)");
        }
        return driver;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Log.reset();
                driver = createDriver();
                try {
                    base.evaluate();
                } catch (Throwable failure) {
                    saveArtifacts(description, failure);
                    throw failure;
                } finally {
                    driver.quit();
                    driver = null;
                }
            }
        };
    }

    private static AndroidDriver createDriver() {
        UiAutomator2Options options = new UiAutomator2Options()
                .setAppPackage(Config.APP_PACKAGE)
                .setAppActivity(Config.APP_ACTIVITY)
                .setAppWaitActivity("com.baemin.*")          // 스플래시를 거쳐 메인 액티비티가 뜨면 실행 완료
                .setAppWaitDuration(Timeouts.APP_LAUNCH)
                .setNoReset(true)                            // 로그인된 테스트 계정 유지 (앱 데이터 삭제 안 함)
                .setForceAppLaunch(true)                     // 이전 화면이 아닌 앱 첫 화면에서 시작
                .setDisableWindowAnimation(true)             // 애니메이션 중 클릭 무시 방지
                .setAutoGrantPermissions(true)               // 실행 중 권한 다이얼로그 방지
                .setUiautomator2ServerInstallTimeout(Duration.ofSeconds(60))
                .setNewCommandTimeout(Duration.ofSeconds(120));
        if (!Config.UDID.isBlank()) {
            options.setUdid(Config.UDID);
        }
        AndroidDriver driver = new AndroidDriver(Config.APPIUM_SERVER, options);
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);   // explicit 대기만 사용
        return driver;
    }

    private void saveArtifacts(Description description, Throwable failure) {
        Path dir = Config.ARTIFACTS_DIR.resolve(description.getMethodName() + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("failure.txt"),
                    "실패 단계: " + Log.currentStep()
                            + "\n액티비티: " + safeActivity()
                            + "\n원인: " + failure, StandardCharsets.UTF_8);
            Files.write(dir.resolve("screen.png"), driver.getScreenshotAs(OutputType.BYTES));
            Files.writeString(dir.resolve("hierarchy.xml"), driver.getPageSource(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("logcat.txt"), driver.manage().logs().get("logcat").getAll().stream()
                    .map(Object::toString).collect(Collectors.joining("\n")), StandardCharsets.UTF_8);
            Log.info("실패 아티팩트 저장: " + dir.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            // 아티팩트 저장 오류가 원래 실패 원인을 덮지 않도록 로그만 남긴다
            Log.info("실패 아티팩트 저장 중 오류: " + e);
        }
    }

    private String safeActivity() {
        try {
            return driver.currentActivity();
        } catch (RuntimeException e) {
            return "(조회 실패)";
        }
    }
}
