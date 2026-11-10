package bmart.stock.support;

import java.util.UUID;

import static bmart.stock.support.StockAssertions.assertStatus;

/**
 * 테스트 데이터 준비.
 * 테스트마다 고유 ID 의 상품·고객을 새로 만들어 테스트 간 데이터가 섞이지 않게 한다
 * (실행 순서 무관, 병렬 실행 가능, 실서버에서 다른 사람의 테스트와 섞여도 안전).
 */
public final class TestData {

    private final String baseUrl;
    private final StockApi admin;

    public TestData(String baseUrl) {
        this.baseUrl = baseUrl;
        this.admin = new StockApi(baseUrl);
    }

    /** 재고를 지정해 테스트 전용 상품을 만든다. 준비는 운영자 API 로 → 스텁·실서버에서 같은 코드가 동작. */
    public String product(int stock) {
        return product(stock, "item");
    }

    public String product(int stock, String name) {
        String productId = "qa-" + name + "-" + shortId();
        assertStatus(admin.adminSetStock(productId, stock), 200);
        return productId;
    }

    public String customer(String tag) {
        return "cust-" + tag + "-" + shortId();
    }

    /** 동시성 테스트용: 스레드마다 독립된 클라이언트(= 다른 고객의 앱). */
    public StockApi newClient() {
        return new StockApi(baseUrl);
    }

    public static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
