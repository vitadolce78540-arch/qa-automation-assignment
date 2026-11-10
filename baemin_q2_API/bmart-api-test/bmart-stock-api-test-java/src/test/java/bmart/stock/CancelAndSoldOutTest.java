package bmart.stock;

import bmart.stock.support.BmartApiExtension;
import bmart.stock.support.StockApi;
import bmart.stock.support.TestData;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static bmart.stock.support.StockAssertions.assertError;
import static bmart.stock.support.StockAssertions.assertOrderPaid;
import static bmart.stock.support.StockAssertions.assertStatus;
import static bmart.stock.support.StockAssertions.assertStock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("품절 전환 · 재고 복구 · 운영자 수정")
@ExtendWith(BmartApiExtension.class)
class CancelAndSoldOutTest {

    // ---------- 품절 전환 ----------

    @Test
    @DisplayName("TC09 마지막 재고 주문 시 품절로 전환된다")
    void tc09_lastItemSoldOut(StockApi api, TestData data) {
        String product = data.product(2);
        String customer = data.customer("c");

        JsonPath order = assertOrderPaid(api.createOrder(customer, product, 2), customer, product, 2);

        assertEquals(0, order.getInt("remainingStock"));
        assertStock(api, product, 0);                              // status=SOLD_OUT, orderable=false 까지 확인
    }

    @Test
    @DisplayName("TC10 품절 상품 주문은 SOLD_OUT으로 거절된다")
    void tc10_orderSoldOutProduct(StockApi api, TestData data) {
        String product = data.product(0);

        assertError(api.createOrder(data.customer("c"), product, 1), 409, "SOLD_OUT");

        assertStock(api, product, 0);
    }

    @Test
    @DisplayName("TC11 품절이 다른 탭에서 조회 중인 고객에게도 즉시 반영된다")
    void tc11_soldOutVisibleInOtherTab(StockApi api, StockApi otherTab, TestData data) {
        String product = data.product(1);
        String buyer = data.customer("buyer");
        String viewer = data.customer("viewer");
        long versionBefore = assertStock(otherTab, product, 1).getLong("version");   // 다른 탭: 구매 가능 상태로 보는 중

        assertOrderPaid(api.createOrder(buyer, product, 1), buyer, product, 1);

        long versionAfter = assertStock(otherTab, product, 0).getLong("version");    // 다시 조회하자마자 품절
        assertTrue(versionAfter > versionBefore);
        assertError(otherTab.createOrder(viewer, product, 1), 409, "SOLD_OUT");
    }

    // ---------- 재고 복구 ----------

    @Test
    @DisplayName("TC12 주문 취소 시 차감된 재고가 즉시 복구된다")
    void tc12_cancelRestoresStock(StockApi api, TestData data) {
        String product = data.product(10);
        String customer = data.customer("c");
        String orderId = assertOrderPaid(api.createOrder(customer, product, 4), customer, product, 4).getString("orderId");
        assertStock(api, product, 6);

        JsonPath body = assertStatus(api.cancelOrder(orderId), 200);

        assertEquals("CANCELLED", body.getString("status"));
        assertEquals(4, body.getInt("restoredQuantity"));
        assertEquals(10, body.getInt("currentStock"));
        assertStock(api, product, 10);
    }

    @Test
    @DisplayName("TC13 품절 상품을 취소하면 즉시 다른 고객이 구매할 수 있다")
    void tc13_cancelReopensSoldOut(StockApi api, TestData data) {
        String product = data.product(1);
        String a = data.customer("a");
        String b = data.customer("b");
        String orderId = assertOrderPaid(api.createOrder(a, product, 1), a, product, 1).getString("orderId");
        assertError(api.createOrder(b, product, 1), 409, "SOLD_OUT");

        assertStatus(api.cancelOrder(orderId), 200);

        assertStock(api, product, 1);                              // 품절 해제, 주문 버튼 활성화
        assertOrderPaid(api.createOrder(b, product, 1), b, product, 1);
        assertStock(api, product, 0);
    }

    @Test
    @DisplayName("TC14 같은 주문을 두 번 취소해도 재고는 한 번만 복구된다")
    void tc14_doubleCancel(StockApi api, TestData data) {
        String product = data.product(5);
        String customer = data.customer("c");
        String orderId = assertOrderPaid(api.createOrder(customer, product, 2), customer, product, 2).getString("orderId");

        assertStatus(api.cancelOrder(orderId), 200);
        assertError(api.cancelOrder(orderId), 409, "ALREADY_CANCELLED");

        assertStock(api, product, 5);                              // 7 이 되면 이중 복구 결함
    }

    @Test
    @DisplayName("TC15 존재하지 않는 주문 취소는 404")
    void tc15_cancelUnknownOrder(StockApi api) {
        assertError(api.cancelOrder("ord_does_not_exist"), 404, "ORDER_NOT_FOUND");
    }

    // ---------- 운영자 수동 수정 ----------

    @Test
    @DisplayName("TC16 운영자가 재고를 수정하면 즉시 반영되고 품절이 해제된다")
    void tc16_adminRestock(StockApi api, StockApi otherTab, TestData data) {
        String product = data.product(0);
        assertStock(otherTab, product, 0);

        assertStatus(api.adminSetStock(product, 20), 200);

        assertStock(otherTab, product, 20);                        // 조회 중인 고객 화면에도 바로 반영
        String customer = data.customer("c");
        assertOrderPaid(api.createOrder(customer, product, 3), customer, product, 3);
        assertStock(api, product, 17);
    }

    @Test
    @DisplayName("TC17 운영자 재고는 음수로 수정할 수 없다")
    void tc17_adminNegativeStock(StockApi api, TestData data) {
        String product = data.product(3);

        assertError(api.adminSetStock(product, -1), 400, "INVALID_REQUEST");

        assertStock(api, product, 3);
    }
}
