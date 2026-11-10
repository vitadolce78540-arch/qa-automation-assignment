package baemin.order.core;

import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Appium 조작 공통 계층. Page Object 는 driver 를 직접 다루지 않고 이 클래스만 쓴다.
 *
 * 기존 스크립트와 다른 점
 *   - Thread.sleep 으로 시간을 때우지 않고 "보인다 / 클릭 가능하다 / 사라졌다 / 값이 바뀌었다" 조건을 기다린다.
 *   - implicitlyWait(20초) 대신 explicit 대기만 쓴다. implicit 이 켜져 있으면 "없으면 넘어가는" 확인
 *     (try { isDisplayed } catch) 이 매번 20초씩 걸려, 기존 스크립트의 측정 시간이 부풀려진다.
 *   - 좌표 클릭(swipe(537, 2127, ...))을 쓰지 않는다. 해상도가 다른 단말에서 다른 곳을 누른다.
 *   - 못 찾으면 "무엇을, 몇 ms 기다렸는지, 현재 액티비티"가 담긴 AssertionError 로 실패한다.
 */
public final class Ui {

    private static final int MAX_SWIPES = 12;

    private final AndroidDriver driver;

    public Ui(AndroidDriver driver) {
        this.driver = driver;
    }

    public AndroidDriver driver() {
        return driver;
    }

    // ---------------------------------------------------------------- 찾기

    /** 후보 중 하나가 화면에 보일 때까지 대기. */
    public WebElement find(Target target, Duration timeout) {
        return findOptional(target, timeout).orElseThrow(() -> new AssertionError(String.format(
                "[%s] %dms 안에 보이지 않음 (후보=%s, activity=%s)",
                target.what(), timeout.toMillis(), target.candidates(), currentActivity())));
    }

    public WebElement find(Target target) {
        return find(target, Timeouts.DEFAULT);
    }

    /** 있으면 반환, 시간 안에 안 나타나면 empty. 타임세일 팝업처럼 "뜰 수도 있는" 요소용. */
    public Optional<WebElement> findOptional(Target target, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Optional<WebElement> visible = firstVisible(target);
            if (visible.isPresent()) {
                return visible;
            }
            pause();
        } while (System.nanoTime() < deadline);
        return Optional.empty();
    }

    /** 대기 없이 지금 보이는지만 확인. */
    public boolean isShown(Target target) {
        return firstVisible(target).isPresent();
    }

    /** 지금 보이는 요소 전부를 화면 위→아래 순서로 반환 ("첫 번째로 보이는 가게" 판단용). */
    public List<WebElement> findAllVisibleTopDown(Target target) {
        List<WebElement> visible = new ArrayList<>();
        for (By by : target.candidates()) {
            for (WebElement element : driver.findElements(by)) {
                if (isDisplayedSafely(element)) {
                    visible.add(element);
                }
            }
            if (!visible.isEmpty()) {
                break;   // 먼저 매칭된 후보 방식만 사용 (desc/text 중복 집계 방지)
            }
        }
        visible.sort(Comparator.comparingInt(element -> element.getLocation().getY()));
        return visible;
    }

    // ---------------------------------------------------------------- 조작

    /**
     * 보이고 + 활성화될 때까지 기다린 뒤 클릭.
     * Compose 리컴포지션으로 요소가 다시 그려져 Stale 이 나면 다시 찾아 한 번 더 시도한다.
     */
    public void click(Target target, Duration timeout) {
        for (int attempt = 1; ; attempt++) {
            WebElement element = waitUntil(target.what() + " 클릭 가능", timeout,
                    () -> firstVisible(target).filter(WebElement::isEnabled).orElse(null),
                    Objects::nonNull);
            try {
                element.click();
                Log.info("click: " + target.what());
                return;
            } catch (StaleElementReferenceException e) {
                if (attempt >= 2) {
                    throw new AssertionError("[" + target.what() + "] 화면 갱신으로 요소가 계속 바뀌어 클릭 실패", e);
                }
            }
        }
    }

    public void click(Target target) {
        click(target, Timeouts.DEFAULT);
    }

    /** 있으면 클릭하고 true, 없으면 false. */
    public boolean clickIfShown(Target target, Duration timeout) {
        if (findOptional(target, timeout).isEmpty()) {
            return false;
        }
        click(target, Timeouts.SHORT);
        return true;
    }

    public void waitGone(Target target, Duration timeout) {
        waitUntil(target.what() + " 사라짐", timeout, () -> isShown(target), shown -> !shown);
    }

    /**
     * 화면을 아래로 스크롤하며 target 을 찾는다.
     * 기존 upSwipe() 의 TouchAction swipe 는 Appium 2 에서 제거됐으므로 mobile: scrollGesture 를 쓰고,
     * 더 내릴 수 없다는 응답(false)이 오면 즉시 실패시켜 무한 스와이프를 막는다.
     */
    public WebElement scrollDownTo(Target target) {
        for (int swipe = 0; swipe <= MAX_SWIPES; swipe++) {
            Optional<WebElement> found = firstVisible(target);
            if (found.isPresent()) {
                return found.get();
            }
            if (!scrollContent("down")) {
                return find(target, Timeouts.SHORT);   // 끝에 도달. 마지막으로 한 번 더 확인 후 실패 메시지
            }
        }
        throw new AssertionError("[" + target.what() + "] " + MAX_SWIPES + "회 스크롤해도 찾지 못함");
    }

    /** 화면 콘텐츠 영역(상태바·하단 탭 제외)을 한 번 스크롤. 더 스크롤할 수 있으면 true. */
    public boolean scrollContent(String direction) {
        Dimension size = driver.manage().window().getSize();
        Object canScrollMore = driver.executeScript("mobile: scrollGesture", Map.of(
                "left", size.getWidth() / 10,
                "top", size.getHeight() / 5,
                "width", size.getWidth() * 8 / 10,
                "height", size.getHeight() * 3 / 5,
                "direction", direction,
                "percent", 0.7));
        return !Boolean.FALSE.equals(canScrollMore);
    }

    public void back() {
        driver.navigate().back();
    }

    // ---------------------------------------------------------------- 폴링

    /**
     * 관찰값이 조건을 만족할 때까지 폴링. 실패하면 마지막 관찰값을 메시지에 남겨
     * "아예 안 바뀐 것"과 "다른 값으로 바뀐 것"을 구분할 수 있게 한다.
     */
    public <T> T waitUntil(String what, Duration timeout, Supplier<T> observe, Predicate<T> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T last = null;
        while (true) {
            try {
                last = observe.get();
                if (condition.test(last)) {
                    return last;
                }
            } catch (StaleElementReferenceException | NoSuchElementException e) {
                // 화면 갱신 중. 다음 폴링에서 다시 관찰
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(String.format("[%s] %dms 동안 조건 미충족 (마지막 관찰값=%s, activity=%s)",
                        what, timeout.toMillis(), last, currentActivity()));
            }
            pause();
        }
    }

    public String currentActivity() {
        try {
            return driver.currentActivity();
        } catch (RuntimeException e) {
            return "(조회 실패)";
        }
    }

    // ---------------------------------------------------------------- 내부

    private Optional<WebElement> firstVisible(Target target) {
        for (By by : target.candidates()) {
            for (WebElement element : driver.findElements(by)) {
                if (isDisplayedSafely(element)) {
                    return Optional.of(element);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isDisplayedSafely(WebElement element) {
        try {
            return element.isDisplayed();
        } catch (StaleElementReferenceException e) {
            return false;
        }
    }

    /** 폴링 간격 전용. 결과를 기다리는 용도로 쓰지 않는다. */
    private static void pause() {
        try {
            Thread.sleep(Timeouts.POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }
}
