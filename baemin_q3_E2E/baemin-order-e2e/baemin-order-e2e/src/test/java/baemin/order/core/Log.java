package baemin.order.core;

import java.time.LocalTime;
import java.util.function.Supplier;

/**
 * 단계 로그. 기존 스크립트의 System.out.println("-----EnterPaymentPage") 형식을 유지하되,
 * 단계 이름을 기억해 실패 메시지·실패 아티팩트에 "어느 단계에서" 실패했는지 남긴다.
 */
public final class Log {

    private static final ThreadLocal<String> CURRENT_STEP = ThreadLocal.withInitial(() -> "(시작 전)");

    private Log() {
    }

    public static <T> T step(String name, Supplier<T> block) {
        CURRENT_STEP.set(name);
        info("-----" + name);
        long started = System.nanoTime();
        try {
            T result = block.get();
            info(String.format("-----%s 완료 (%.2fs)", name, (System.nanoTime() - started) / 1e9));
            return result;
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError("STEP 실패 [" + name + "] " + e.getMessage(), e);
        }
    }

    public static void step(String name, Runnable block) {
        step(name, () -> {
            block.run();
            return null;
        });
    }

    public static String currentStep() {
        return CURRENT_STEP.get();
    }

    public static void reset() {
        CURRENT_STEP.remove();
    }

    public static void info(String message) {
        System.out.println("[" + LocalTime.now() + "] " + message);
    }
}
