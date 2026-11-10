package baemin.order.pages;

import baemin.order.core.Log;
import baemin.order.core.Target;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.util.List;

import static org.junit.Assert.assertFalse;

/** 카테고리(치킨) 가게 목록 화면. */
public class StoreListPage extends BasePage {

    /**
     * 가게 카드.
     * [실기기 확인] 홈의 가게 목록 카드가 content-desc "총 30개 가게 중 1번째" 형식이었다.
     * [확인 필요] 카테고리 목록 화면도 같은 형식인지.
     */
    static final Target STORE_CARD = Target.of("가게 카드",
            Target.descMatches("^총 [0-9]+개 가게 중 [0-9]+번째.*$"));

    private final String category;

    public StoreListPage(Ui ui, String category) {
        super(ui, STORE_CARD);
        this.category = category;
        assertCategorySelected();
    }

    /**
     * 목록이 떴다는 것만으로는 '치킨' 목록인지 알 수 없다 (다른 카테고리를 눌렀어도 가게 카드는 보인다).
     * [확인 필요] 상단 카테고리 탭의 selected 상태로 확인한다.
     */
    private void assertCategorySelected() {
        Target tab = Target.labeled("카테고리 탭 '" + category + "'", category);
        ui.waitUntil("'" + category + "' 탭 선택 상태", Timeouts.SHORT,
                () -> ui.find(tab, Timeouts.SHORT).getAttribute("selected"),
                "true"::equals);
    }

    /**
     * 화면에서 '첫 번째로 보이는' 가게를 연다.
     * 요소 순서가 아니라 화면 좌표(위→아래)로 판단한다. 접근성 트리 순서는 화면 배치와 다를 수 있다.
     * 진입 전에 가게명을 읽어 두고, 가게 상세 화면에서 같은 가게인지 확인한다.
     */
    public StorePage openFirstVisibleStore(OrderDraft draft) {
        List<WebElement> cards = ui.findAllVisibleTopDown(STORE_CARD);
        assertFalse("'" + category + "' 목록에 보이는 가게가 없음", cards.isEmpty());
        WebElement first = cards.get(0);
        draft.storeName(firstText(first));
        Log.info("-----첫 번째 가게: " + (draft.hasStoreName() ? draft.storeName() : "(이름 읽기 실패)")
                + " / " + label(first));
        first.click();
        return new StorePage(ui, draft);
    }

    /** 카드 안 첫 번째 텍스트(가게명). [확인 필요] 카드 내부 구성 */
    private static String firstText(WebElement card) {
        for (WebElement text : card.findElements(By.className("android.widget.TextView"))) {
            String value = text.getText();
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
