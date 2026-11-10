package bmart.stock.stub;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WireMock 응답 변환 확장.
 *
 * <p>WireMock 의 일반 stub 매핑은 "요청 → 고정 응답"이라 재고처럼 요청마다 바뀌는 상태와
 * 동시성 규칙을 표현할 수 없다. 그래서 라우팅·요청 매칭은 WireMock 이 맡고(StockStubServer 의 stubFor),
 * 응답 본문은 이 확장이 StockStore 를 호출해 만든다.
 */
public class StockStubTransformer implements ResponseDefinitionTransformerV2 {

    public static final String NAME = "bmart-stock";

    private static final Pattern STOCK = Pattern.compile("^/v1/products/([^/]+)/stock$");
    private static final Pattern ADMIN_STOCK = Pattern.compile("^/v1/admin/products/([^/]+)/stock$");
    private static final Pattern CANCEL = Pattern.compile("^/v1/orders/([^/]+)/cancel$");

    private final StockStore store;

    public StockStubTransformer(StockStore store) {
        this.store = store;
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        Request request = serveEvent.getRequest();
        String path = URI.create(request.getUrl()).getPath();
        String method = request.getMethod().getName();

        StockStore.StubResponse result;
        try {
            result = route(method, path, request);
        } catch (RuntimeException e) {                       // JSON 파싱 실패 등
            result = new StockStore.StubResponse(400, Map.of("code", "INVALID_REQUEST", "message", String.valueOf(e.getMessage())));
        }
        return new ResponseDefinitionBuilder()
                .withStatus(result.status())
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBody(Json.write(result.body()))
                .build();
    }

    private StockStore.StubResponse route(String method, String path, Request request) {
        Matcher m;
        if ("GET".equals(method) && (m = STOCK.matcher(path)).matches()) {
            return store.getStock(m.group(1));
        }
        if ("PUT".equals(method) && (m = ADMIN_STOCK.matcher(path)).matches()) {
            return store.adminSetStock(m.group(1), body(request));
        }
        if ("POST".equals(method) && "/v1/orders".equals(path)) {
            return store.createOrder(body(request), request.getHeader("Idempotency-Key"));
        }
        if ("POST".equals(method) && (m = CANCEL.matcher(path)).matches()) {
            return store.cancelOrder(m.group(1));
        }
        return new StockStore.StubResponse(404, Map.of("code", "NOT_FOUND"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Request request) {
        String raw = request.getBodyAsString();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Object parsed = Json.read(raw, Object.class);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("body must be a JSON object");
        }
        return (Map<String, Object>) parsed;
    }

    @Override
    public boolean applyGlobally() {
        return false;     // withTransformers(NAME) 를 지정한 stub 에만 적용
    }

    @Override
    public String getName() {
        return NAME;
    }
}
