package bmart.stock.support;

import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 동시 요청 실행기.
 * 모든 스레드를 CyclicBarrier 로 같은 출발선에 세운 뒤, 각 요청을 지정한 오프셋(ms)만큼 늦게 보낸다.
 * "두 요청이 수십 ms 간격으로 도달"하는 상황을 오프셋으로 재현한다.
 */
public final class Concurrency {

    private Concurrency() {
    }

    /** 보낼 요청 하나: 이름, 출발선 기준 지연(ms), 실제 호출. */
    public record Shot(String label, long offsetMs, Supplier<Response> call) {
    }

    /** 결과: 이름, 응답, 출발선 기준 실제 송신 시각(ms). */
    public record Result(String label, Response response, double sentAtMs) {
        public int status() {
            return response.statusCode();
        }

        @Override
        public String toString() {
            return String.format("(%s, %d, %.1fms)", label, status(), sentAtMs);
        }
    }

    public static List<Result> fire(List<Shot> shots) {
        AtomicLong t0 = new AtomicLong();
        CyclicBarrier barrier = new CyclicBarrier(shots.size(), () -> t0.set(System.nanoTime()));
        ExecutorService pool = Executors.newFixedThreadPool(shots.size());
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (Shot shot : shots) {
                futures.add(pool.submit(() -> {
                    barrier.await();                          // 모든 스레드가 준비될 때까지 대기
                    if (shot.offsetMs() > 0) {
                        Thread.sleep(shot.offsetMs());
                    }
                    double sentAt = (System.nanoTime() - t0.get()) / 1_000_000.0;
                    return new Result(shot.label(), shot.call().get(), sentAt);
                }));
            }
            List<Result> results = new ArrayList<>();
            for (Future<Result> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("concurrent requests failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    public static List<Result> succeeded(List<Result> results) {
        return results.stream().filter(r -> r.status() == 201).toList();
    }

    public static List<Result> rejected(List<Result> results) {
        return results.stream().filter(r -> r.status() != 201).toList();
    }
}
