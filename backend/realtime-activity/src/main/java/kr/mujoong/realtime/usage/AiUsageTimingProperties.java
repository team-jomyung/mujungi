package kr.mujoong.realtime.usage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mujoong.timing.ai-usage")
public record AiUsageTimingProperties(Duration flushInterval, Duration pendingTtl, Duration shutdownWait,
        Duration writeTimeout) {
    public AiUsageTimingProperties {
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
