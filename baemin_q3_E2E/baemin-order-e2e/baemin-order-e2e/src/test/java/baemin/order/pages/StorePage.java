package baemin.order.pages;

import baemin.order.core.Config;
import baemin.order.core.Log;
import baemin.order.core.Target;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

/** 가게 상세 화면 (메뉴 목록, 하단 장바구니 바). */
public class StorePage extends BasePage {

    /** [확인 필요] 가게 상세 화면에만 있는 탭/정보 영역 */
    static final Target ANCHOR = Target.labeledMatches("가게 상세 화면", "^(가게정보|가게 정보|정보·원산지)$");

    /** [확인 필요] 메뉴 항목. testTag 노출 시 resource-id 를 1순위로 쓴다 */
    static final Target MENU_ITEM = Target.of("메뉴 항목",
            Target.resourceId(Config.APP_PACKAGE, "menu_item"),
            Target.descMatches("^.+ [0-9]{1,3}(,[0-9]{3})*원.*$"));

    /** [확인 필요] 메뉴를 담은 뒤 하단에 뜨는 장바구니 바 */
    static final Target CART_BAR = Target.labeledMatches("장바구니 보기 바", "^.*장바구니 보기.*$");

    private final OrderDraft draft;

    public StorePage(Ui ui, OrderDraft draft) {
        super(ui, StoreListPage.STORE_CARD, ANCHOR, Timeouts.NETWORK);
        this.draft = draft;
        if (draft.hasStoreName()) {
            // 목록에서 누른 가게와 같은 가게에 들어왔는지 확인
            ui.find(Target.labeled("가게명 '" + draft.storeName() + "'", draft.storeName()), Timeouts.SHORT);
        }
    }

    /** 메뉴 목록에서 첫 번째로 보이는 메뉴를 연다. 진입 전에 메뉴명을 읽어 둔다. */
    public MenuDetailPage openFirstMenu() {
        WebElement menu = ui.scrollDownTo(MENU_ITEM);
        draft.menuName(firstText(menu));
        Log.info("-----첫 번째 메뉴: " + (draft.hasMenuName() ? draft.menuName() : "(이름 읽기 실패)"));
        menu.click();
        return new MenuDetailPage(ui, draft);
    }

    /** 담기 후 하단 장바구니 바가 나타난 것을 확인한 뒤 장바구니로 이동한다. */
    public CartPage openCart() {
        ui.click(CART_BAR, Timeouts.SHORT);
        return new CartPage(ui, CART_BAR);
    }

    private static String firstText(WebElement row) {
        for (WebElement text : row.findElements(By.className("android.widget.TextView"))) {
            String value = text.getText();
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
