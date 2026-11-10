package baemin.order.pages;

import baemin.order.core.Log;
import baemin.order.core.Target;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;
import org.openqa.selenium.WebElement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 결제 화면 (주문서). 이 테스트의 종착점이다.
 *
 * 결제 버튼은 누르지 않는다. 실계정으로 실행되므로 실제 결제·주문이 생기면 안 된다.
 * 대신 "결제할 수 있는 상태인지"(결제수단 영역, 금액 일치, 결제 버튼 활성화)를 검증한다.
 */
public class CheckoutPage extends BasePage {

    /** [확인 필요] 하단 결제 버튼. 예: "21,000원 결제하기" */
    static final Target PAY_BUTTON = Target.labeledMatches("결제하기 버튼", "^[0-9]{1,3}(,[0-9]{3})*원 결제하기$");

    /** [확인 필요] 결제수단 영역 제목 */
    static final Target PAYMENT_METHOD_SECTION = Target.labeled("결제수단 영역", "결제수단");

    public CheckoutPage(Ui ui) {
        super(ui, CartPage.ORDER_BUTTON, PAY_BUTTON, Timeouts.NETWORK);
    }

    /**
     * 결제 화면 검증
     *   1. 장바구니에서 확인한 가게로 주문서가 열렸는지
     *   2. 결제수단 영역이 있는지
     *   3. 결제 금액이 장바구니 주문 금액과 같은지 (자동 적용 쿠폰이 없는 테스트 계정 전제)
     *   4. 결제 버튼이 활성화되어 결제 가능한 상태인지
     */
    public CheckoutPage assertReadyToPay(OrderDraft draft) {
        if (draft.hasStoreName()) {
            ui.find(Target.labeled("주문서 가게명", draft.storeName()), Timeouts.SHORT);
        }
        ui.scrollDownTo(PAYMENT_METHOD_SECTION);

        WebElement payButton = ui.find(PAY_BUTTON, Timeouts.SHORT);
        int payAmount = parseWon(label(payButton), "결제 금액");
        assertEquals("결제 금액이 장바구니 주문 금액과 다름 (쿠폰 자동 적용 여부 확인)",
                draft.cartOrderAmount(), payAmount);
        assertTrue("결제하기 버튼이 비활성 상태", payButton.isEnabled());

        Log.info(String.format("-----결제 화면 확인: 결제 금액 %,d원 (결제는 진행하지 않음)", payAmount));
        return this;
    }
}
