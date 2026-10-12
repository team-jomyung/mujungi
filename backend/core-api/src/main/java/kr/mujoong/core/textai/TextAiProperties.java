package kr.mujoong.core.textai;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "mujoong.text-ai")
public record TextAiProperties(Duration requestTimeout, Retry retry) {
    public TextAiProperties {
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(retry, "retry");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Text AI timeout must be positive");
        }
    }
    public record Retry(int maxRetries, Duration backoff) {
        public Retry {
            Objects.requireNonNull(backoff, "backoff");
            if (maxRetries < 0 || maxRetries > 1 || backoff.isNegative()) {
                throw new IllegalArgumentException("Invalid Text AI retry policy");
            }
        }
    }
}
