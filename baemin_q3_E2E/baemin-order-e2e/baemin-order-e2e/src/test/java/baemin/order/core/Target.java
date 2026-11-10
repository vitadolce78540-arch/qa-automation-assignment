package baemin.order.core;

import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

import java.util.List;

/**
 * 화면 요소 하나에 대한 "이름 + 찾는 방법(후보 목록)".
 *
 * 배민 앱은 대부분 Jetpack Compose 화면이라 resource-id 가 거의 없고, 같은 요소가 버전에 따라
 * content-desc 또는 text 로 노출된다 (실기기 확인: "음식배달에서 더보기"는 content-desc).
 * 그래서 후보를 여러 개 두고 먼저 보이는 것을 쓴다. 이름(what)은 실패 메시지에 그대로 쓰인다.
 *
 * 셀렉터 규칙
 *   - 정확히 일치(description / text)를 기본으로 한다. 부분 일치(textContains)는 쓰지 않는다.
 *   - 패턴이 꼭 필요하면 정규식으로 범위를 좁힌다 (예: "^[0-9,]+원 담기$").
 *   - 기존 스크립트처럼 인덱스가 들어간 XPath( (//...)[2] )는 화면 구성이 바뀌면 다른 요소를 가리키므로 쓰지 않는다.
 */
public record Target(String what, List<By> candidates) {

    public static Target of(String what, By... candidates) {
        return new Target(what, List.of(candidates));
    }

    /** content-desc 또는 text 가 정확히 일치 */
    public static Target labeled(String what, String exact) {
        return of(what, desc(exact), text(exact));
    }

    /** content-desc 또는 text 가 정규식과 일치 */
    public static Target labeledMatches(String what, String regex) {
        return of(what, descMatches(regex), textMatches(regex));
    }

    public static By desc(String exact) {
        return ui("new UiSelector().description(\"" + escape(exact) + "\")");
    }

    public static By text(String exact) {
        return ui("new UiSelector().text(\"" + escape(exact) + "\")");
    }

    public static By descMatches(String regex) {
        return ui("new UiSelector().descriptionMatches(\"" + escape(regex) + "\")");
    }

    public static By textMatches(String regex) {
        return ui("new UiSelector().textMatches(\"" + escape(regex) + "\")");
    }

    /** 라디오 버튼·체크박스처럼 선택 상태를 가진 요소 */
    public static By checkable() {
        return ui("new UiSelector().checkable(true)");
    }

    public static By resourceId(String packageName, String id) {
        return AppiumBy.id(packageName + ":id/" + id);
    }

    private static By ui(String uiSelector) {
        return AppiumBy.androidUIAutomator(uiSelector);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
