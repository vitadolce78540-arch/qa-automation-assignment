package baemin.order.pages;

import baemin.order.core.Target;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;

/** 홈 화면. */
public class HomePage extends BasePage {

    /** [실기기 확인] 하단 탭바 홈 탭 (content-desc) */
    static final Target ANCHOR = Target.labeled("홈 화면(하단탭바 홈탭)", "하단탭바 홈탭");

    /** [실기기 확인] 음식배달 영역 하단의 더보기 (content-desc) */
    static final Target FOOD_DELIVERY_MORE = Target.labeled("음식배달에서 더보기", "음식배달에서 더보기");

    /** [실기기 확인] 상단 장바구니 아이콘 (content-desc) */
    static final Target CART_ICON = Target.labeled("장바구니 아이콘", "장바구니");

    public HomePage(Ui ui) {
        super(ui, ANCHOR, Timeouts.APP_LAUNCH);
    }

    public FoodDeliveryPage openFoodDeliveryMore() {
        ui.scrollDownTo(FOOD_DELIVERY_MORE);   // 배너 구성에 따라 화면 아래에 있을 수 있음
        ui.click(FOOD_DELIVERY_MORE);
        return new FoodDeliveryPage(ui);
    }

    public CartPage openCart() {
        ui.click(CART_ICON);
        return new CartPage(ui);
    }
}
