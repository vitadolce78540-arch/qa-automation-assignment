package bmart.stock;

import bmart.stock.support.BmartApiExtension;
import bmart.stock.support.StockApi;
import bmart.stock.support.TestData;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static bmart.stock.support.StockAssertions.assertError;
import static bmart.stock.support.StockAssertions.assertOrderPaid;
import static bmart.stock.support.StockAssertions.assertStatus;
import static bmart.stock.support.StockAssertions.assertStock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("정상 케이스: 재고 조회 · 장바구니 · 결제 차감")
@ExtendWith(BmartApiExtension.class)
class StockAndOrderTest {

    @Test
    @DisplayName("TC01 재고 조회 응답 필드와 값")
    void tc01_getStock(StockApi api, TestData data) {
        String product = data.product(10);

        JsonPath body = assertStock(api, product, 10);

        assertEquals(product, body.getString("productId"));
        for (String field : List.of("productId", "quantity", "status", "orderable", "version")) {
            assertNotNull(body.get(field), "missing field: " + field);
        }
    }

    @Test
    @DisplayName("TC02 존재하지 않는 상품 조회는 404")
    void tc02_unknownProduct(StockApi api) {
        assertError(api.getStock("no-such-" + TestData.shortId()), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("TC03 장바구니 담기 시점에는 재고가 차감되지 않는다")
    void tc03_cartDoesNotReserve(StockApi api, TestData data) {
        String product = data.product(5);
        long versionBefore = assertStock(api, product, 5).getLong("version");

        for (int i = 0; i < 5; i++) {                       // 여러 고객이 장바구니에 담는 상황 = 조회만 발생
            assertStatus(api.getStock(product), 200);
        }

        assertEquals(versionBefore, assertStock(api, product, 5).getLong("version"));
    }

    @Test
    @DisplayName("TC04 결제 완료 시 주문 수량만큼 즉시 차감")
    void tc04_orderDeductsStock(StockApi api, TestData data) {
        String product = data.product(10);
        String customer = data.customer("c");

        JsonPath order = assertOrderPaid(api.createOrder(customer, product, 3), customer, product, 3);

        assertEquals(7, order.getInt("remainingStock"));
        assertStock(api, product, 7);
    }

    @Test
    @DisplayName("TC05 재고보다 많이 주문하면 거절되고 재고는 그대로")
    void tc05_insufficientStock(StockApi api, TestData data) {
        String product = data.product(2);

        JsonPath body = assertError(api.createOrder(data.customer("c"), product, 3), 409, "INSUFFICIENT_STOCK");

        assertEquals(2, body.getInt("availableQuantity"));
        assertStock(api, product, 2);
    }

    static Stream<Arguments> badQuantities() {
        return Stream.of(arguments(0), arguments(-1), arguments("2"), arguments((Object) null), arguments(1.5));
    }

    @ParameterizedTest(name = "TC06 잘못된 주문 수량({0})은 400이고 재고는 그대로")
    @MethodSource("badQuantities")
    void tc06_invalidQuantity(Object badQuantity, StockApi api, TestData data) {
        String product = data.product(5);

        assertError(api.createOrder(data.customer("c"), product, badQuantity), 400, "INVALID_REQUEST");

        assertStock(api, product, 5);
    }

    @Test
    @DisplayName("TC07 존재하지 않는 상품 주문은 404")
    void tc07_orderUnknownProduct(StockApi api, TestData data) {
        assertError(api.createOrder(data.customer("c"), "no-such-" + TestData.shortId(), 1), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("TC08 같은 결제 요청이 재전송되어도 한 번만 차감된다")
    void tc08_idempotentPayment(StockApi api, TestData data) {
        // 결제 버튼 연타·네트워크 재시도로 같은 요청이 두 번 와도 주문·차감은 1회여야 한다
        String product = data.product(5);
        String customer = data.customer("c");
        String key = "pay-" + TestData.shortId();

        JsonPath first = assertOrderPaid(api.createOrder(customer, product, 2, key), customer, product, 2);
        JsonPath replay = assertStatus(api.createOrder(customer, product, 2, key), 200);

        assertEquals(first.getString("orderId"), replay.getString("orderId"));
        assertTrue(replay.getBoolean("replayed"));
        assertStock(api, product, 3);
    }
}
