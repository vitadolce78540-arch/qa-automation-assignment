package bmart.stock.stub;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 재고 처리 규칙을 구현한 메모리 저장소. WireMock 확장(StockStubTransformer)이 요청마다 호출한다.
 *
 * <ul>
 *   <li>상품별 Lock 으로 재고 변경(주문·취소·운영자 수정)을 직렬화 → 서버 도착 순(선착순) 처리</li>
 *   <li>paymentDelayMs: 결제 승인 지연을 흉내 내 경쟁 구간(race window)을 일부러 넓힘</li>
 *   <li>unsafe=true: Lock 없이 "조회 → 지연 → 저장" 하는 결함 버전. 동시성 테스트의 결함 검출력 확인용</li>
 * </ul>
 */
public class StockStore {

    /** 스텁 응답: HTTP 상태 + JSON 본문. */
    public record StubResponse(int status, Map<String, Object> body) {
    }

    private static final class Product {
        int quantity;
        long version;
    }

    private final long paymentDelayMs;
    private final boolean unsafe;
    private final Map<String, Product> products = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> orders = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();   // customerId|key -> orderId
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public StockStore(long paymentDelayMs, boolean unsafe) {
        this.paymentDelayMs = paymentDelayMs;
        this.unsafe = unsafe;
    }

    private ReentrantLock lock(String productId) {
        return locks.computeIfAbsent(productId, k -> new ReentrantLock(true));
    }

    // ---------- 재고 조회 ----------
    public StubResponse getStock(String productId) {
        if (!products.containsKey(productId)) {
            return error(404, "PRODUCT_NOT_FOUND");
        }
        return new StubResponse(200, view(productId));
    }

    // ---------- 운영자 재고 수정 (절대값 덮어쓰기, 주문과 같은 Lock 으로 직렬화) ----------
    public StubResponse adminSetStock(String productId, Map<String, Object> body) {
        Long q = asLong(body.get("quantity"));
        if (q == null || q < 0) {
            return error(400, "INVALID_REQUEST", "message", "quantity must be an integer >= 0");
        }
        ReentrantLock lock = lock(productId);
        lock.lock();
        try {
            products.computeIfAbsent(productId, k -> new Product());
            setQuantity(productId, q.intValue());
            return new StubResponse(200, view(productId));
        } finally {
            lock.unlock();
        }
    }

    // ---------- 주문 생성 (결제 완료 → 재고 차감) ----------
    public StubResponse createOrder(Map<String, Object> body, String idemKey) {
        Object customerId = body.get("customerId");
        Object productId = body.get("productId");
        if (!(customerId instanceof String c) || c.isBlank() || !(productId instanceof String p) || p.isBlank()) {
            return error(400, "INVALID_REQUEST", "message", "customerId and productId are required");
        }
        Long qty = asLong(body.get("quantity"));
        if (qty == null || qty < 1) {
            return error(400, "INVALID_REQUEST", "message", "quantity must be an integer >= 1");
        }
        if (!products.containsKey(p)) {
            return error(404, "PRODUCT_NOT_FOUND");
        }
        if (unsafe) {
            return createOrderUnsafe(c, p, qty.intValue());
        }

        ReentrantLock lock = lock(p);
        lock.lock();
        try {
            String idemId = idemKey == null ? null : c + "|" + idemKey;
            if (idemId != null && idempotency.containsKey(idemId)) {          // 같은 결제 재요청 → 1회만 처리
                Map<String, Object> replay = new LinkedHashMap<>(orders.get(idempotency.get(idemId)));
                replay.put("replayed", true);
                return new StubResponse(200, replay);
            }
            int stock = products.get(p).quantity;
            if (stock == 0) {
                return error(409, "SOLD_OUT", "productId", p);
            }
            if (stock < qty) {
                return error(409, "INSUFFICIENT_STOCK", "availableQuantity", stock);
            }
            sleep(paymentDelayMs);                                             // 결제 승인 지연(경쟁 구간)
            setQuantity(p, stock - qty.intValue());
            Map<String, Object> order = newOrder(c, p, qty.intValue());
            if (idemId != null) {
                idempotency.put(idemId, (String) order.get("orderId"));
            }
            return new StubResponse(201, order);
        } finally {
            lock.unlock();
        }
    }

    private StubResponse createOrderUnsafe(String customerId, String productId, int qty) {
        int stock = products.get(productId).quantity;                         // 1) 조회
        if (stock == 0) {
            return error(409, "SOLD_OUT", "productId", productId);
        }
        if (stock < qty) {
            return error(409, "INSUFFICIENT_STOCK", "availableQuantity", stock);
        }
        sleep(paymentDelayMs);                                                // 2) 그 사이 다른 요청도 같은 재고를 봄
        setQuantity(productId, Math.max(stock - qty, 0));                     // 3) 저장 → 초과 판매
        return new StubResponse(201, newOrder(customerId, productId, qty));
    }

    // ---------- 주문 취소 (재고 복구) ----------
    public StubResponse cancelOrder(String orderId) {
        Map<String, Object> order = orders.get(orderId);
        if (order == null) {
            return error(404, "ORDER_NOT_FOUND");
        }
        String productId = (String) order.get("productId");
        ReentrantLock lock = lock(productId);
        lock.lock();
        try {
            if ("CANCELLED".equals(order.get("status"))) {                    // 이중 복구 방지
                return error(409, "ALREADY_CANCELLED", "orderId", orderId);
            }
            order.put("status", "CANCELLED");
            int qty = (Integer) order.get("quantity");
            setQuantity(productId, products.get(productId).quantity + qty);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderId", orderId);
            body.put("status", "CANCELLED");
            body.put("restoredQuantity", qty);
            body.put("currentStock", products.get(productId).quantity);
            return new StubResponse(200, body);
        } finally {
            lock.unlock();
        }
    }

    // ---------- 내부 유틸 ----------
    private Map<String, Object> view(String productId) {
        Product p = products.get(productId);
        boolean soldOut = p.quantity == 0;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("productId", productId);
        v.put("quantity", p.quantity);
        v.put("status", soldOut ? "SOLD_OUT" : "AVAILABLE");
        v.put("orderable", !soldOut);          // 고객 화면 주문 버튼 활성화 여부
        v.put("version", p.version);           // 변경될 때마다 +1 (다른 탭 동기화 기준)
        return v;
    }

    private void setQuantity(String productId, int quantity) {
        Product p = products.get(productId);
        p.quantity = quantity;
        p.version++;
    }

    private Map<String, Object> newOrder(String customerId, String productId, int qty) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", "ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        order.put("status", "PAID");
        order.put("customerId", customerId);
        order.put("productId", productId);
        order.put("quantity", qty);
        order.put("remainingStock", products.get(productId).quantity);
        orders.put((String) order.get("orderId"), order);
        return new LinkedHashMap<>(order);
    }

    /** 정수(Integer/Long)만 인정. "2", 1.5, true, null 은 null 반환 → 400 처리. */
    private static Long asLong(Object value) {
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Long l) {
            return l;
        }
        return null;
    }

    private static StubResponse error(int status, String code, Object... extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            body.put((String) extra[i], extra[i + 1]);
        }
        return new StubResponse(status, body);
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
