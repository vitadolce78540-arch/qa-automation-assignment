package baemin.order.pages;

import baemin.order.core.Log;
import baemin.order.core.Target;
import baemin.order.core.Timeouts;
import baemin.order.core.Ui;
import org.openqa.selenium.WebElement;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 메뉴 상세(옵션 선택) 화면.
 * 원본 코드에는 이 단계가 없어, 필수 옵션이 있는 메뉴에서는 담기 버튼이 비활성인 채 멈췄다.
 */
public class MenuDetailPage extends BasePage {

    /** [확인 필요] 하단 담기 버튼. 예: "18,000원 담기" */
    static final Target ADD_BUTTON = Target.labeledMatches("담기 버튼", "^[0-9]{1,3}(,[0-9]{3})*원 담기$");

    /** [확인 필요] 필수 옵션 그룹 배지 */
    static final Target REQUIRED_BADGE = Target.labeled("필수 옵션 배지", "필수");

    /** 선택 가능한 옵션(라디오·체크박스) */
    static final Target CHECKABLE = Target.of("선택 가능한 옵션", Target.checkable());

    /** [확인 필요] 다른 가게 메뉴가 장바구니에 있을 때 뜨는 다이얼로그 */
    static final Target OTHER_STORE_DIALOG = Target.labeledMatches("다른 가게 담기 확인 다이얼로그", ".*다른 가게.*");

    private static final int MAX_OPTION_ROUNDS = 5;

    private final OrderDraft draft;

    public MenuDetailPage(Ui ui, OrderDraft draft) {
        super(ui, ADD_BUTTON);
        this.draft = draft;
        if (draft.hasMenuName()) {
            ui.find(Target.labeled("메뉴명 '" + draft.menuName() + "'", draft.menuName()), Timeouts.SHORT);
        }
    }

    /**
     * 필수 옵션 그룹마다 첫 번째 선택지를 고른다. 담기 버튼이 활성화될 때까지 스크롤하며 반복한다.
     * 옵션 구성은 가게마다 다르므로, 선택 후 '선택됨' 상태가 실제로 반영됐는지까지 확인한다.
     */
    public MenuDetailPage selectRequiredOptions() {
        for (int round = 0; round < MAX_OPTION_ROUNDS && !addButtonEnabled(); round++) {
            for (WebElement badge : ui.findAllVisibleTopDown(REQUIRED_BADGE)) {
                firstOptionBelow(badge).ifPresent(this::check);
            }
            if (!addButtonEnabled() && !ui.scrollContent("down")) {
                break;
            }
        }
        ui.waitUntil("담기 버튼 활성화 (필수 옵션 선택 완료)", Timeouts.SHORT,
                this::addButtonEnabled, Boolean.TRUE::equals);
        return this;
    }

    /**
     * 담기 버튼의 금액을 기록하고 담는다.
     * 다른 가게 메뉴가 남아 있으면 '다른 가게' 다이얼로그가 뜨는데, 이는 사전 장바구니 정리가 실패했다는 뜻이므로
     * 기존 스크립트처럼 catch 로 넘기지 않고 원인을 남겨 실패시킨다.
     */
    public StorePage addToCart() {
        WebElement addButton = ui.find(ADD_BUTTON, Timeouts.SHORT);
        draft.menuAmount(parseWon(label(addButton), "담기 금액"));
        Log.info("-----담기 금액: " + draft.menuAmount() + "원");
        ui.click(ADD_BUTTON);
        if (ui.findOptional(OTHER_STORE_DIALOG, Timeouts.OPTIONAL).isPresent()) {
            throw new AssertionError("다른 가게 메뉴가 장바구니에 남아 있음: 사전 장바구니 정리 실패");
        }
        return new StorePage(ui, draft);
    }

    private boolean addButtonEnabled() {
        return ui.findOptional(ADD_BUTTON, Duration.ZERO).map(WebElement::isEnabled).orElse(false);
    }

    /** 배지 아래쪽에 있는 첫 번째 선택지 (화면 좌표 기준). */
    private Optional<WebElement> firstOptionBelow(WebElement badge) {
        int badgeY = badge.getLocation().getY();
        List<WebElement> options = ui.findAllVisibleTopDown(CHECKABLE);
        return options.stream()
                .filter(option -> option.getLocation().getY() > badgeY)
                .min(Comparator.comparingInt(option -> option.getLocation().getY()));
    }

    private void check(WebElement option) {
        if ("true".equals(option.getAttribute("checked"))) {
            return;   // 이미 기본 선택된 그룹
        }
        option.click();
        ui.waitUntil("옵션 선택 반영", Timeouts.SHORT, () -> option.getAttribute("checked"), "true"::equals);
    }
}
