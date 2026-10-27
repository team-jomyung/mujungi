package kr.mujoong.core.usage;

/** Luna 호출부는 이 포트에 작은 불변 값만 넘긴다. DB 저장 · 재시도는 수집기 책임이고 호출부를 기다리게 하지 않는다. */
public interface AiUsageCollector {

    /** 받지 못하면 false. 호출부는 결과와 상관없이 하던 일을 계속한다. */
    boolean offer(AiUsageRecord record);

    default boolean enabled() {
        return true;
    }

    AiUsageCollector NOOP = new AiUsageCollector() {
        @Override
        public boolean offer(AiUsageRecord record) {
            return true;
        }

        @Override
        public boolean enabled() {
            return false;
        }
    };
}
