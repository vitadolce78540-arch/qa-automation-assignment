package baemin.order;

import baemin.order.base.BaseDriver_Baemin;
import baemin.order.core.Config;
import baemin.order.core.Log;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;
import baemin.order.pages.CartPage;
import baemin.order.pages.CheckoutPage;
import baemin.order.pages.HomePage;
import baemin.order.pages.MenuDetailPage;
import baemin.order.pages.OrderDraft;
import baemin.order.pages.StoreListPage;
import baemin.order.pages.StorePage;
import baemin.order.pages.TimeSalePopup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static baemin.order.core.Log.step;

/* -----------------------------------------------------------------------------
 * @ TCID = Baemin_Order_Chicken_ToPayment
 * 앱 실행 → 타임세일 팝업 닫기 → 음식배달 더보기 → 치킨(없으면 메뉴 전체보기 → 치킨)
 * → 첫 번째 가게 → 첫 번째 메뉴 → 장바구니 → 주문 → 결제 화면 (결제는 하지 않음)
 *
 * 전제: 로그인되어 있고 배송지가 설정된 테스트 계정, 자동 적용 쿠폰 없음
 * -----------------------------------------------------------------------------
 */
public class Baemin_Order_E2E_Test extends BaseDriver_Baemin {

    private static final String TCID = "Baemin_Order_Chicken_ToPayment";
    private static final String CATEGORY = "치킨";

    private Ui ui;

    @Before
    public void setUp() {
        ui = ui();
        // 사전 조건: 장바구니 비우기. 이전 실행이 남긴 메뉴가 있으면 '다른 가게' 다이얼로그로 흐름이 달라진다
        TimeSalePopup.closeIfShown(ui, Timeouts.OPTIONAL);
        new HomePage(ui).openCart().removeAll();
    }

    @After
    public void tearDown() {
        // 실계정이므로 테스트가 담은 메뉴를 남기지 않는다. 정리 실패는 결과를 바꾸지 않고 로그만 남긴다
        if (!Config.CLEANUP_CART) {
            return;
        }
        try {
            relaunchApp();
            TimeSalePopup.closeIfShown(ui, Timeouts.OPTIONAL);
            new HomePage(ui).openCart().removeAll();
        } catch (RuntimeException | AssertionError e) {
            Log.info("장바구니 정리 실패 (다음 실행의 setUp 에서 다시 정리됨): " + e.getMessage());
        }
    }

    @Test
    public void Baemin_Order_Chicken_ToPayment() {
        OrderDraft draft = new OrderDraft();

        HomePage home = step("1. 앱 실행 · 타임세일 팝업 닫기", () -> {
            relaunchApp();
            boolean closed = TimeSalePopup.closeIfShown(ui, Timeouts.OPTIONAL);
            Log.info("-----타임세일 팝업 " + (closed ? "닫음" : "없음"));
            return new HomePage(ui);
        });

        StoreListPage storeList = step("2. 음식배달 더보기 → 치킨", () ->
                home.openFoodDeliveryMore()
                        .selectCategory(CATEGORY));

        StorePage store = step("3. 첫 번째 가게 진입", () ->
                storeList.openFirstVisibleStore(draft));

        MenuDetailPage menu = step("4. 첫 번째 메뉴 선택 · 필수 옵션", () ->
                store.openFirstMenu()
                        .selectRequiredOptions());

        CartPage cart = step("5. 장바구니 담기 · 확인", () ->
                menu.addToCart()
                        .openCart()
                        .assertContains(draft));

        // 기존 iAPv21 스크립트의 화면 진입 시간 측정을 유지하되, sleep 시간을 빼는 계산 없이
        // '주문하기 클릭 ~ 결제 버튼 표시'를 조건 대기로 측정한다 (대기가 끝나는 즉시 측정 종료)
        long started = System.nanoTime();
        CheckoutPage checkout = step("6. 주문하기 → 결제 화면 진입", cart::proceedToCheckout);
        double enterSeconds = (System.nanoTime() - started) / 1e9;

        step("7. 결제 화면 검증 (결제는 하지 않음)", () ->
                checkout.assertReadyToPay(draft));

        Log.info("--------------------------------------------------");
        Log.info(String.format(" / Baemin 결제화면 진입 E2E Result / %.2fs", enterSeconds));
        Log.info(" / " + draft);
        Log.info("--------------------------------------------------");
        appendResultCsv(TCID, "checkout_enter_sec", enterSeconds);
        printPass(TCID);
    }

    /** 앱을 종료 후 다시 실행해 항상 첫 화면에서 시작한다 (이전 화면 상태에 의존하지 않음). */
    private void relaunchApp() {
        driver().executeScript("mobile: terminateApp", Map.of("appId", Config.APP_PACKAGE));
        driver().executeScript("mobile: activateApp", Map.of("appId", Config.APP_PACKAGE));
    }
}
