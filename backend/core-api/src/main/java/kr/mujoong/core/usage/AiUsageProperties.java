package kr.mujoong.core.usage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 켜기 · 끄기 스위치. 기본은 꺼짐이다. */
@ConfigurationProperties("mujoong.ai-usage")
public record AiUsageProperties(boolean enabled) {

    /** 저장 작업 시간(timing.md 16절). */
    @ConfigurationProperties("mujoong.timing.ai-usage")
    public record Timing(Duration flushInterval, Duration pendingTtl, Duration shutdownWait, Duration writeTimeout) {

        public Timing {
            for (Duration value : new Duration[] {flushInterval, pendingTtl, shutdownWait, writeTimeout}) {
                if (value == null || value.isNegative() || value.isZero()) {
                    throw new IllegalArgumentException("사용량 시간 설정은 0보다 커야 한다");
                }
            }
            if (pendingTtl.compareTo(flushInterval) < 0) {
                throw new IllegalArgumentException("사용량 보관 시간은 저장 주기 이상이어야 한다");
            }
        }
    }

    /** 대기 기록 수 상한 · 한 번에 저장하는 수. */
    @ConfigurationProperties("mujoong.limits.ai-usage")
    public record Limits(int queueCapacity, int batchSize) {

        public Limits {
            if (queueCapacity < 1 || batchSize < 1 || batchSize > queueCapacity) {
                throw new IllegalArgumentException("사용량 큐 · 배치 크기 오류");
            }
        }
    }
}
