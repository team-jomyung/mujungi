package kr.mujoong.core.textai;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "mujoong.text-ai")
public record TextAiProperties(Duration requestTimeout, Retry retry, Result result, Scenario scenario) {
    /** 기존 U5a 호출자의 생성자 계약을 유지한다. 결과 설정은 결과 경계에서 필수 검사한다. */
    public TextAiProperties(Duration requestTimeout, Retry retry) {
        this(requestTimeout, retry, null, null);
    }
    /** 장면 설정이 없으면 장면 호출도 공통 호출 상한을 쓴다(기존 호출자 · 시험). */
    public TextAiProperties(Duration requestTimeout, Retry retry, Result result) {
        this(requestTimeout, retry, result, null);
    }
    /** 연습 상황 생성 · 검토 호출 한 번의 상한(FT-1030-n). 없으면 공통 상한이다. */
    public Duration scenarioRequestTimeout() {
        return scenario == null ? requestTimeout : scenario.requestTimeout();
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
    /** 연습 상황 생성 · 검토는 그래프 맥락 · 최근 노출 · 수정 요청이 붙어 공통 10초에 자주 걸려 따로 둔다(2026-10-30 실키, FT-1030-n). */
    public record Scenario(Duration requestTimeout) {
        public Scenario {
            Objects.requireNonNull(requestTimeout, "mujoong.text-ai.scenario.request-timeout");
            if (requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("mujoong.text-ai.scenario.request-timeout must be positive");
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
