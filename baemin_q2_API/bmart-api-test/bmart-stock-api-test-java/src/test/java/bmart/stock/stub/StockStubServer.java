package bmart.stock.stub;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** 재고 API 를 흉내 내는 WireMock 서버. 테스트에서는 BmartApiExtension 이 한 번만 띄운다. */
public final class StockStubServer {

    private final WireMockServer server;

    private StockStubServer(int port, long paymentDelayMs, boolean unsafe) {
        StockStore store = new StockStore(paymentDelayMs, unsafe);
        var config = options()
                .containerThreads(64)          // 피크 타임 동시 요청을 실제로 병렬 처리
                .jettyAcceptQueueSize(256)
                .disableRequestJournal()       // 요청 기록을 쌓지 않음 (메모리·성능)
                .notifier(new ConsoleNotifier(false))
                .extensions(new StockStubTransformer(store));
        this.server = new WireMockServer(port == 0 ? config.dynamicPort() : config.port(port));
    }

    public static StockStubServer start(int port, long paymentDelayMs, boolean unsafe) {
        StockStubServer stub = new StockStubServer(port, paymentDelayMs, unsafe);
        stub.server.start();
        stub.registerRoutes();
        return stub;
    }

    /** 요청 매칭은 WireMock stub 으로 정의하고, 응답 생성은 변환 확장에 위임한다. */
    private void registerRoutes() {
        String t = StockStubTransformer.NAME;
        server.stubFor(get(urlPathMatching("/v1/products/[^/]+/stock")).willReturn(aResponse().withTransformers(t)));
        server.stubFor(put(urlPathMatching("/v1/admin/products/[^/]+/stock")).willReturn(aResponse().withTransformers(t)));
        server.stubFor(post(urlPathEqualTo("/v1/orders")).willReturn(aResponse().withTransformers(t)));
        server.stubFor(post(urlPathMatching("/v1/orders/[^/]+/cancel")).willReturn(aResponse().withTransformers(t)));
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    public void stop() {
        server.stop();
    }
}
