package kr.mujoong.realtime.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mujoong.limits.ai-usage")
public record AiUsageLimitsProperties(int queueCapacity, int batchSize) {
    public AiUsageLimitsProperties {
        if (queueCapacity < 1 || batchSize < 1 || batchSize > queueCapacity) {
            throw new IllegalArgumentException("사용량 큐·배치 크기 오류");
        }
    }
}
