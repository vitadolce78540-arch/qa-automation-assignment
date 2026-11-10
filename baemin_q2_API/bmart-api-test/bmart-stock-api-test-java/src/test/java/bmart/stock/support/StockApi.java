package bmart.stock.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * 재고 API 호출을 한곳에 모은 RestAssured 클라이언트.
 * 테스트는 URL·헤더를 직접 다루지 않고 업무 언어(createOrder, cancelOrder ...)로만 호출한다.
 * 인스턴스 하나 = 고객 한 명의 앱/브라우저 탭.
 */
public final class StockApi {

    private final RequestSpecification spec;

    public StockApi(String baseUrl) {
        this.spec = new RequestSpecBuilder()
                .setBaseUri(baseUrl)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }

    /** 고객: 재고 조회 (장바구니 담기 시점에도 이 조회만 일어난다). */
    public Response getStock(String productId) {
        return given(spec).get("/v1/products/{productId}/stock", productId);
    }

    /** 고객: 주문 생성 = 결제 완료 → 재고 차감. quantity 는 잘못된 타입 검증을 위해 Object. */
    public Response createOrder(String customerId, String productId, Object quantity) {
        return createOrder(customerId, productId, quantity, null);
    }

    public Response createOrder(String customerId, String productId, Object quantity, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("productId", productId);
        body.put("quantity", quantity);
        RequestSpecification request = given(spec).body(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.post("/v1/orders");
    }

    /** 고객: 결제 완료 후 주문 취소 → 재고 복구. */
    public Response cancelOrder(String orderId) {
        return given(spec).post("/v1/orders/{orderId}/cancel", orderId);
    }

    /** 운영자: 재고 수동 수정. 테스트 데이터 준비에도 사용한다. */
    public Response adminSetStock(String productId, int quantity) {
        Map<String, Object> body = Map.of("quantity", quantity, "operatorId", "op-qa");
        return given(spec).body(body).put("/v1/admin/products/{productId}/stock", productId);
    }
}
