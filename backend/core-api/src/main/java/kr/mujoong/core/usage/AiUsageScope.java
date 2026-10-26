package kr.mujoong.core.usage;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 사용량 연결 대상을 호출 흐름에 싣는다. 작업 진입점(결과 작업 · 장면 생성 · 시드 생성)이 {@link #call}로 열고, Luna 호출부가 {@link #current}로
 * 읽는다. 기존 요청 · 게이트웨이 시그니처를 바꾸지 않으려는 선택이다.
 *
 * <p>스레드 지역 값이므로 다른 실행기로 일을 넘기는 곳은 {@link #propagate}로 감싸야 한다. 감싸지 않은 경로의 호출은 연결 없이 기록된다(연결 미확인).
 */
public final class AiUsageScope {

    private static final ThreadLocal<AiUsageLink> CURRENT = new ThreadLocal<>();

    private AiUsageScope() {
    }

    /** 안쪽 호출이 끝나면 바깥 값으로 되돌린다(중첩 허용). */
    public static <T> T call(AiUsageLink link, Supplier<T> work) {
        Objects.requireNonNull(link, "link");
        AiUsageLink outer = CURRENT.get();
        CURRENT.set(link);
        try {
            return work.get();
        } finally {
            restore(outer);
        }
    }

    public static Optional<AiUsageLink> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 지금 스레드의 연결 대상을 다른 스레드에서 실행할 작업에 옮긴다. 연결이 없으면 작업을 그대로 돌려준다. */
    public static <T> Callable<T> propagate(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        AiUsageLink link = CURRENT.get();
        if (link == null) {
            return task;
        }
        return () -> {
            AiUsageLink outer = CURRENT.get();
            CURRENT.set(link);
            try {
                return task.call();
            } finally {
                restore(outer);
            }
        };
    }

    private static void restore(AiUsageLink outer) {
        if (outer == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(outer);
        }
    }
}
