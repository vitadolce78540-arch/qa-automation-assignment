package bmart.stock;

import bmart.stock.support.BmartApiExtension;
import bmart.stock.support.Concurrency.Result;
import bmart.stock.support.Concurrency.Shot;
import bmart.stock.support.StockApi;
import bmart.stock.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static bmart.stock.support.Concurrency.fire;
import static bmart.stock.support.Concurrency.rejected;
import static bmart.stock.support.Concurrency.succeeded;
import static bmart.stock.support.StockAssertions.assertStock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 동시성: 같은 상품에 대한 동시 주문·운영자 수정의 재고 정합성.
 * 경쟁 상태(race condition)는 매번 드러나지 않으므로 같은 시나리오를 라운드마다 새 상품으로 반복한다.
 */
@Tag("concurrency")
@DisplayName("동시성: 피크 타임 동시 주문")
@ExtendWith(BmartApiExtension.class)
class ConcurrencyTest {

    private static final int ROUNDS = 10;

    @ParameterizedTest(name = "TC18 재고 1개에 두 고객이 {0}ms 간격으로 결제하면 1건만 성공한다")
    @ValueSource(ints = {0, 10, 30, 50})
    void tc18_lastItemTwoBuyers(int gapMs, StockApi api, TestData data) {
        for (int round = 0; round < ROUNDS; round++) {
            String product = data.product(1, "last-one");
            String a = data.customer("A");
            String b = data.customer("B");
            StockApi clientA = data.newClient();
            StockApi clientB = data.newClient();

            List<Result> results = fire(List.of(
                    new Shot("A", 0, () -> clientA.createOrder(a, product, 1)),
                    new Shot("B", gapMs, () -> clientB.createOrder(b, product, 1))));
            List<Result> ok = succeeded(results);
            List<Result> no = rejected(results);
            String ctx = "round=" + round + " gap=" + gapMs + "ms " + results;

            // 1건 성공 / 1건 품절
            assertEquals(1, ok.size(), "초과 판매 또는 전부 실패: " + ctx);
            assertEquals(1, no.size(), ctx);
            assertEquals(409, no.get(0).status(), ctx);
            assertEquals("SOLD_OUT", no.get(0).response().jsonPath().getString("code"), ctx);

            // 선착순: 간격이 있으면 먼저 보낸 A 가 이겨야 한다 (gap 0 은 누가 이겨도 됨)
            if (gapMs >= 10) {
                assertEquals("A", ok.get(0).label(), "선착순 위반: " + ctx);
            }

            // 최종 상태: 재고 0, 품절 배지, 주문 버튼 비활성화
            assertStock(api, product, 0);
        }
    }

    @Test
    @DisplayName("TC19 재고 5개에 20명이 동시에 결제하면 정확히 5건만 성공한다")
    void tc19_peakTime(StockApi api, TestData data) {
        int stock = 5;
        int buyers = 20;
        String product = data.product(stock, "peak-time");

        List<Shot> shots = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String customer = data.customer("p" + i);
            StockApi client = data.newClient();
            shots.add(new Shot(customer, 0, () -> client.createOrder(customer, product, 1)));
        }
        List<Result> results = fire(shots);
        List<Result> ok = succeeded(results);
        List<Result> no = rejected(results);

        assertEquals(stock, ok.size(), () -> "results=" + results);
        assertEquals(buyers - stock, no.size());
        assertTrue(no.stream().allMatch(r -> "SOLD_OUT".equals(r.response().jsonPath().getString("code"))));
        assertEquals(stock, ok.stream().map(r -> r.response().jsonPath().getString("orderId")).distinct().count(),
                "주문 ID 중복");
        assertEquals(stock, ok.stream().mapToInt(r -> r.response().jsonPath().getInt("quantity")).sum(),
                "판매량 = 초기 재고");
        assertStock(api, product, 0);
    }

    @Test
    @DisplayName("TC20 남은 재고보다 큰 수량이 동시에 오면 한 건은 수량 부족으로 거절된다")
    void tc20_partialQuantityRace(StockApi api, TestData data) {
        for (int round = 0; round < ROUNDS; round++) {
            String product = data.product(3);
            String a = data.customer("A");
            String b = data.customer("B");
            StockApi clientA = data.newClient();
            StockApi clientB = data.newClient();

            List<Result> results = fire(List.of(
                    new Shot("A", 0, () -> clientA.createOrder(a, product, 2)),
                    new Shot("B", 0, () -> clientB.createOrder(b, product, 2))));
            List<Result> no = rejected(results);

            assertEquals(1, succeeded(results).size(), () -> "results=" + results);
            assertEquals(1, no.size());
            assertEquals("INSUFFICIENT_STOCK", no.get(0).response().jsonPath().getString("code"));
            assertEquals(1, no.get(0).response().jsonPath().getInt("availableQuantity"));
            assertStock(api, product, 1);
        }
    }

    /**
     * 처리 순서 정의: 같은 상품의 재고 변경은 서버 도착 순으로 직렬 처리하고,
     * 운영자 입력값은 '현재 실물 재고'로 보고 절대값으로 덮어쓴다.
     */
    @ParameterizedTest(name = "TC21 운영자 수정과 주문이 겹치면 도착 순서대로 처리된다 ({0} 먼저 → 최종 {1})")
    @CsvSource({
            "admin, 9",     // 운영자 10개로 수정 → 주문 1개 차감 → 9
            "order, 10"     // 주문 1개 차감(0) → 운영자 10개로 덮어쓰기 → 10
    })
    void tc21_adminVsOrder(String first, int expectedFinal, StockApi api, TestData data) {
        String product = data.product(1);
        String customer = data.customer("c");
        StockApi admin = data.newClient();
        StockApi shopper = data.newClient();
        long adminOffset = "admin".equals(first) ? 0 : 10;
        long orderOffset = "admin".equals(first) ? 20 : 0;

        Map<String, Result> byLabel = fire(List.of(
                new Shot("admin", adminOffset, () -> admin.adminSetStock(product, 10)),
                new Shot("order", orderOffset, () -> shopper.createOrder(customer, product, 1))))
                .stream().collect(Collectors.toMap(Result::label, Function.identity()));

        assertEquals(200, byLabel.get("admin").status());
        assertEquals(201, byLabel.get("order").status());
        assertStock(api, product, expectedFinal);
    }
}
