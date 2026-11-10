package bmart.stock.stub;

/**
 * 스텁 서버 단독 실행: ./gradlew runStub -Pport=8080 [-Pstub.unsafe=true]
 * Postman·curl 로 직접 호출해 보거나, 다른 언어의 테스트를 붙일 때 사용한다.
 */
public final class StockStubMain {

    public static void main(String[] args) throws InterruptedException {
        int port = 8080;
        long delayMs = 30;
        boolean unsafe = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--delay-ms" -> delayMs = Long.parseLong(args[++i]);
                case "--unsafe" -> unsafe = true;
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        StockStubServer stub = StockStubServer.start(port, delayMs, unsafe);
        System.out.printf("B마트 재고 스텁(WireMock): %s (delay=%dms, unsafe=%s)%n", stub.baseUrl(), delayMs, unsafe);
        Runtime.getRuntime().addShutdownHook(new Thread(stub::stop));
        Thread.currentThread().join();
    }
}
