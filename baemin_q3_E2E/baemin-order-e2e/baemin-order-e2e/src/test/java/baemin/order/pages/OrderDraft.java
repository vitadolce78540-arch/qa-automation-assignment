package baemin.order.pages;

/**
 * 시나리오 진행 중 화면에서 읽은 값을 모아 두는 컨텍스트.
 *
 * 이 테스트는 고정 테스트 데이터가 아니라 "첫 번째로 보이는 가게의 메뉴"를 고르므로 기대값을 미리 알 수 없다.
 * 그래서 앞 화면에서 읽은 값(가게명·메뉴명·금액)을 기록해 두고, 뒤 화면에서 같은 값인지 확인한다.
 * 기존 스크립트처럼 마지막 화면의 글자 하나만 확인하면 엉뚱한 가게·메뉴로 진행돼도 통과하기 때문이다.
 */
public final class OrderDraft {

    private String storeName = "";
    private String menuName = "";
    private int menuAmount;
    private int cartOrderAmount;

    public String storeName() {
        return storeName;
    }

    public String menuName() {
        return menuName;
    }

    public int menuAmount() {
        return menuAmount;
    }

    public int cartOrderAmount() {
        return cartOrderAmount;
    }

    void storeName(String value) {
        this.storeName = value;
    }

    void menuName(String value) {
        this.menuName = value;
    }

    void menuAmount(int value) {
        this.menuAmount = value;
    }

    void cartOrderAmount(int value) {
        this.cartOrderAmount = value;
    }

    public boolean hasStoreName() {
        return !storeName.isBlank();
    }

    public boolean hasMenuName() {
        return !menuName.isBlank();
    }

    @Override
    public String toString() {
        return String.format("가게='%s', 메뉴='%s', 메뉴금액=%,d원, 장바구니 주문금액=%,d원",
                storeName, menuName, menuAmount, cartOrderAmount);
    }
}
