package baemin.order.pages;

import baemin.order.core.Log;
import baemin.order.core.Target;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/** 장바구니 화면. */
public class CartPage extends BasePage {

    /** [확인 필요] 하단 주문 버튼. 예: "21,000원 배달 주문하기" */
    static final Target ORDER_BUTTON = Target.labeledMatches("주문하기 버튼",
            "^[0-9]{1,3}(,[0-9]{3})*원 (배달 |포장 )?주문하기$");

    /** [확인 필요] 빈 장바구니 안내 */
    static final Target EMPTY = Target.labeledMatches("빈 장바구니 안내", "^.*(장바구니가 비었|담긴 메뉴가 없).*$");

    /** [확인 필요] 전체 삭제 / 삭제 확인 */
    static final Target REMOVE_ALL = Target.labeled("전체삭제 버튼", "전체삭제");
    static final Target CONFIRM_REMOVE = Target.labeledMatches("삭제 확인 버튼", "^(삭제|확인)$");

    /** [확인 필요] 수량 표시. 담은 직후이므로 1 이어야 한다 */
    static final Target QUANTITY_ONE = Target.labeledMatches("수량 1", "^(수량 )?1(개)?$");

    /** 장바구니는 '주문 버튼이 있는 화면' 또는 '빈 장바구니 화면' 둘 중 하나다 */
    static final Target ANCHOR = anyOf("장바구니 화면", ORDER_BUTTON, EMPTY);

    /**
     * @param leftScreen 떠나온 화면의 요소. 홈의 장바구니 아이콘과 이름이 같아, 이전 화면이 사라진 것까지 확인한다.
     */
    public CartPage(Ui ui, Target leftScreen) {
        super(ui, leftScreen, ANCHOR, Timeouts.NETWORK);
    }

    public CartPage(Ui ui) {
        this(ui, HomePage.ANCHOR);
    }

    /**
     * 방금 담은 가게·메뉴가 수량 1로 들어 있는지 확인하고, 주문 금액을 기록한다.
     * 원본은 '주문하기' 글자만 찾아 눌러, 다른 메뉴·수량·금액이어도 알 수 없었다.
     */
    public CartPage assertContains(OrderDraft draft) {
        if (draft.hasStoreName()) {
            ui.find(Target.labeled("장바구니 가게명", draft.storeName()), Timeouts.SHORT);
        }
        if (draft.hasMenuName()) {
            ui.find(Target.labeled("장바구니 메뉴명", draft.menuName()), Timeouts.SHORT);
        }
        ui.find(QUANTITY_ONE, Timeouts.SHORT);

        int orderAmount = parseWon(label(ui.find(ORDER_BUTTON, Timeouts.SHORT)), "장바구니 주문 금액");
        assertTrue(String.format("주문 금액(%,d원)이 담은 메뉴 금액(%,d원)보다 작음", orderAmount, draft.menuAmount()),
                orderAmount >= draft.menuAmount());
        draft.cartOrderAmount(orderAmount);
        Log.info("-----장바구니 확인: " + draft);
        return this;
    }

    public CheckoutPage proceedToCheckout() {
        ui.click(ORDER_BUTTON);
        return new CheckoutPage(ui);
    }

    /** 장바구니를 비운다. 이미 비어 있으면 아무것도 하지 않는다 (사전 조건·정리 공용). */
    public void removeAll() {
        if (ui.isShown(EMPTY)) {
            return;
        }
        ui.click(REMOVE_ALL);
        ui.clickIfShown(CONFIRM_REMOVE, Timeouts.SHORT);
        ui.find(EMPTY, Timeouts.SHORT);
        Log.info("-----장바구니 비움");
    }

    private static Target anyOf(String what, Target... targets) {
        List<org.openqa.selenium.By> candidates = new ArrayList<>();
        for (Target target : targets) {
            candidates.addAll(target.candidates());
        }
        return new Target(what, List.copyOf(candidates));
    }
}
