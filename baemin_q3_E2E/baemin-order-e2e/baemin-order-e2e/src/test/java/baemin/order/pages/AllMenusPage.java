package baemin.order.pages;

import baemin.order.core.Target;
import baemin.order.core.Ui;

/** 메뉴 전체보기 화면 (전체 카테고리 목록). */
public class AllMenusPage extends BasePage {

    /** [확인 필요] 화면 제목 */
    static final Target ANCHOR = Target.labeledMatches("메뉴 전체보기 화면", "^(메뉴 전체보기|전체 메뉴)$");

    public AllMenusPage(Ui ui) {
        super(ui, ANCHOR);
    }

    /** 목록에서 카테고리를 정확히 일치하는 이름으로 찾아(필요하면 스크롤) 선택한다. */
    public void select(String category) {
        Target categoryItem = Target.labeled("전체보기 카테고리 '" + category + "'", category);
        ui.scrollDownTo(categoryItem);
        ui.click(categoryItem);
    }
}
