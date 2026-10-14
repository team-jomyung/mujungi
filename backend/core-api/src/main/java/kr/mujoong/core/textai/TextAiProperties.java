package kr.mujoong.core.textai;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "mujoong.text-ai")
public record TextAiProperties(Duration requestTimeout, Retry retry, Result result) {
    /** 기존 U5a 호출자의 생성자 계약을 유지한다. 결과 설정은 결과 경계에서 필수 검사한다. */
    public TextAiProperties(Duration requestTimeout, Retry retry) {
        this(requestTimeout, retry, null);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
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
    /** U6 BR1.3: 결과 호출은 U5a 재시도 설정을 상속하지 않는다. */
    public record Result(Duration requestTimeout, double temperatureJudgment, double temperatureGeneration,
            String promptVersion) {
        public Result {
            Objects.requireNonNull(requestTimeout, "mujoong.text-ai.result.request-timeout");
            if (requestTimeout.isNegative() || requestTimeout.isZero() || temperatureJudgment != 0
                    || temperatureGeneration < 0 || temperatureGeneration > 1
                    || promptVersion == null || !promptVersion.matches("[A-Za-z0-9._-]{1,80}")) {
                throw new IllegalArgumentException("mujoong.text-ai.result 설정이 올바르지 않다");
            }
        }
    }
}
