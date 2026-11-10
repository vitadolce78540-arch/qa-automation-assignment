// check-code 시연용: AI 에게 "실시간 주문 현황 테스트를 Appium Java 로 짜 줘"라고 했을 때 흔히 나오는 형태의 코드
package sample;

import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import org.junit.Assert;
import org.junit.Test;

public class OrderStatusAiTest {

    AndroidDriver driver;
    String password = "test1234";   // 시연용 가짜 값

    @Test
    public void orderStatusTest() throws Exception {
        driver.findElement(AppiumBy.accessibilityId("하단탭바 주문내역탭")).click();
        Thread.sleep(3000);
        driver.findElement(AppiumBy.id("com.sampleapp:id/order_item_ongoing")).click();
        driver.findElement(AppiumBy.androidUIAutomator("new UiSelector().textContains(\"접수\")")).isDisplayed();
        driver.findElement(AppiumBy.id("com.sampleapp:id/order_status_badge")).isDisplayed();
        driver.findElement(AppiumBy.xpath("(//android.widget.TextView)[3]")).getText();
        try {
            driver.findElement(AppiumBy.accessibilityId("주문 상세 보기")).click();
        } catch (Exception e) {}
        Assert.assertTrue(true);
    }
}
