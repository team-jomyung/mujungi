package kr.mujoong.realtime.usage;

/** 대화 경로는 이 포트에 작은 불변 값만 전달한다. DB · 재시도 · 완료 대기는 수집기 책임이다. */
@FunctionalInterface
public interface AiUsageCollector {
    boolean offer(AiUsageRecord record);

    default boolean enabled() { return true; }

    AiUsageCollector NOOP = new AiUsageCollector() {
        @Override public boolean offer(AiUsageRecord record) { return true; }
        @Override public boolean enabled() { return false; }
    };
}
