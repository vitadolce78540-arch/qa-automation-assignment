package baemin.order.pages;

import baemin.order.core.Log;
import baemin.order.core.Target;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;

/**
 * 음식배달 화면 (카테고리 아이콘 목록).
 * 요구사항: 치킨을 찾고, 없으면 '메뉴 전체보기'에서 치킨을 선택한다.
 */
public class FoodDeliveryPage extends BasePage {

    /** [확인 필요] 화면 제목 */
    static final Target ANCHOR = Target.labeled("음식배달 화면", "음식배달");

    /** [확인 필요] 메뉴 전체보기 */
    static final Target ALL_MENUS = Target.labeled("메뉴 전체보기", "메뉴 전체보기");

    public FoodDeliveryPage(Ui ui) {
        // 홈에도 '음식배달' 버튼이 있으므로, 홈의 '음식배달에서 더보기'가 사라진 뒤 제목을 확인한다
        super(ui, HomePage.FOOD_DELIVERY_MORE, ANCHOR, Timeouts.NETWORK);
    }

    /**
     * 카테고리가 바로 보이면 선택하고, 없으면 메뉴 전체보기를 거쳐 선택한다.
     * 어느 경로로 갔는지 로그에 남겨, 실패 시 분기 때문인지 바로 알 수 있게 한다.
     *
     * 원본의 textContains("치킨")은 '치킨' 이 들어간 배너·가게명까지 잡는다. 여기서는 정확히 '치킨'인 요소만 찾는다.
     */
    public StoreListPage selectCategory(String category) {
        Target categoryIcon = Target.labeled("카테고리 '" + category + "'", category);
        if (ui.findOptional(categoryIcon, Timeouts.OPTIONAL).isPresent()) {
            Log.info("-----'" + category + "' 카테고리 바로 선택");
            ui.click(categoryIcon);
        } else {
            Log.info("-----'" + category + "' 카테고리 없음 → 메뉴 전체보기");
            ui.click(ALL_MENUS);
            new AllMenusPage(ui).select(category);
        }
        return new StoreListPage(ui, category);
    }
}
