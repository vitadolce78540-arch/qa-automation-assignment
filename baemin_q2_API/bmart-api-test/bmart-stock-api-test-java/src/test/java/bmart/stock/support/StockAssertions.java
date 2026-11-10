package bmart.stock.support;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 검증 헬퍼. 실패 메시지에 응답 상태·본문을 남겨, 리포트만 보고 원인을 추적할 수 있게 한다. */
public final class StockAssertions {

    private StockAssertions() {
    }

    private static String dump(Response r) {
        return "-> " + r.statusCode() + " " + r.asString();
    }

    public static JsonPath assertStatus(Response r, int expected) {
        assertEquals(expected, r.statusCode(), () -> "unexpected status " + dump(r));
        return r.jsonPath();
    }

    public static JsonPath assertError(Response r, int status, String code) {
        JsonPath body = assertStatus(r, status);
        assertEquals(code, body.getString("code"), () -> "unexpected error code " + dump(r));
        return body;
    }

    public static JsonPath assertOrderPaid(Response r, String customerId, String productId, int quantity) {
        JsonPath body = assertStatus(r, 201);
        assertEquals("PAID", body.getString("status"), () -> dump(r));
        assertNotNull(body.getString("orderId"), () -> dump(r));
        assertEquals(customerId, body.getString("customerId"), () -> dump(r));
        assertEquals(productId, body.getString("productId"), () -> dump(r));
        assertEquals(quantity, body.getInt("quantity"), () -> dump(r));
        return body;
    }

    /** 재고 수량과, 수량에서 파생되는 고객 화면 상태(품절 배지·주문 버튼)를 함께 검증한다. */
    public static JsonPath assertStock(StockApi api, String productId, int quantity) {
        Response r = api.getStock(productId);
        JsonPath body = assertStatus(r, 200);
        assertEquals(quantity, body.getInt("quantity"), () -> "stock of " + productId + " " + dump(r));
        assertTrue(body.getInt("quantity") >= 0, () -> "negative stock " + dump(r));
        if (quantity == 0) {
            assertEquals("SOLD_OUT", body.getString("status"), () -> dump(r));   // "품절" 배지
            assertFalse(body.getBoolean("orderable"), () -> dump(r));            // 주문 버튼 비활성화
        } else {
            assertEquals("AVAILABLE", body.getString("status"), () -> dump(r));
            assertTrue(body.getBoolean("orderable"), () -> dump(r));
        }
        return body;
    }
}
