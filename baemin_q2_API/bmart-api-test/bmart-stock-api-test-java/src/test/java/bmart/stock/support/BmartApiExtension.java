package bmart.stock.support;

import bmart.stock.stub.StockStubServer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * 공통 fixture 역할을 하는 JUnit 5 확장.
 *
 * <ul>
 *   <li>서버: -Dbmart.baseUrl(또는 BMART_BASE_URL)이 있으면 그 서버(QA/STG)를, 없으면 WireMock 스텁을
 *       전체 실행에서 딱 한 번 띄우고 모든 테스트가 끝나면 내린다(Root Store + CloseableResource).</li>
 *   <li>파라미터 주입: 테스트 메서드에 {@link StockApi}, {@link TestData} 를 선언하면 주입한다.
 *       StockApi 를 두 개 선언하면 서로 다른 인스턴스(= 기본 탭 / 다른 탭)가 들어간다.</li>
 * </ul>
 */
public class BmartApiExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(BmartApiExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
        Class<?> type = pc.getParameter().getType();
        return type == StockApi.class || type == TestData.class;
    }

    @Override
    public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
        String baseUrl = baseUrl(ec);
        return pc.getParameter().getType() == StockApi.class ? new StockApi(baseUrl) : new TestData(baseUrl);
    }

    private static String baseUrl(ExtensionContext ec) {
        String external = System.getProperty("bmart.baseUrl", System.getenv("BMART_BASE_URL"));
        if (external != null && !external.isBlank()) {
            return external;
        }
        return ec.getRoot().getStore(NS)
                .getOrComputeIfAbsent("stub", key -> new StubResource(), StubResource.class)
                .baseUrl();
    }

    /** 전체 테스트 실행 동안 한 번만 존재하는 스텁 서버. 실행이 끝나면 JUnit 이 close() 를 호출한다. */
    static final class StubResource implements ExtensionContext.Store.CloseableResource {
        private final StockStubServer server = StockStubServer.start(
                0,
                Long.getLong("stub.delayMs", 30L),
                Boolean.getBoolean("stub.unsafe"));

        String baseUrl() {
            return server.baseUrl();
        }

        @Override
        public void close() {
            server.stop();
        }
    }
}
