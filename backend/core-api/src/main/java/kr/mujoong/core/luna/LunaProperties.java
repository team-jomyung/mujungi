package kr.mujoong.core.luna;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Core Luna 호출 설정 {@code mujoong.luna.*} (U9 BR1.5 · SD3 · SD12 · NFR3.4 · NFR3.10). 값은 application.yml에만 있고 코드 기본값은 모델
 * 이름 하나뿐이다(env가 빈 값일 때 BR1.5). 시간 값이 아니다 — 시도 시한은 기존 {@code mujoong.text-ai.*} 키를 쓴다(BR5.2).
 *
 * @param model 요청 모델 이름(env {@code OPENAI_ANALYSIS_MODEL}, 비면 {@value #DEFAULT_MODEL})
 * @param baseUrl Responses API 기준 주소({@code {baseUrl}/responses}로 보낸다). 프로필 규칙은 {@link LunaHosts}
 * @param maxResponseBytes 응답 본문 바이트 상한(넘으면 LENGTH_EXCEEDED, NFR3.10)
 * @param purpose 목적별 출력 한도 · 추론 강도
 */
@ConfigurationProperties(prefix = "mujoong.luna")
public record LunaProperties(String model, URI baseUrl, int maxResponseBytes, Purposes purpose) {

    /** env 모델 이름이 비었을 때 쓰는 이름(BR1.5). */
    public static final String DEFAULT_MODEL = "gpt-6-luna";
    /** 공급자가 받는 추론 강도(entities PurposeOptions). */
    public static final Set<String> REASONING_EFFORTS = Set.of("none", "minimal", "low", "medium", "high");

    private static final Pattern MODEL_FORMAT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public LunaProperties {
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        if (!MODEL_FORMAT.matcher(model).matches()) {
            throw new IllegalArgumentException("mujoong.luna.model 형식이 올바르지 않다");
        }
        Objects.requireNonNull(baseUrl, "mujoong.luna.base-url");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("mujoong.luna.max-response-bytes는 양수여야 한다");
        }
        Objects.requireNonNull(purpose, "mujoong.luna.purpose");
    }

    /** 목적의 출력 한도 · 추론 강도. */
    public PurposeOptions options(LunaPurpose lunaPurpose) {
        return purpose.of(lunaPurpose);
    }

    /** core 목적 여섯 가지의 설정(목적마다 키가 따로 있어 대조 시험이 키 집합을 본다, SD12). */
    public record Purposes(
            PurposeOptions scenarioGeneration,
            PurposeOptions scenarioReview,
            PurposeOptions seedGeneration,
            PurposeOptions resultText,
            PurposeOptions goalCandidates,
            PurposeOptions rejudgment) {

        public Purposes {
            Objects.requireNonNull(scenarioGeneration, "mujoong.luna.purpose.scenario-generation");
            Objects.requireNonNull(scenarioReview, "mujoong.luna.purpose.scenario-review");
            Objects.requireNonNull(seedGeneration, "mujoong.luna.purpose.seed-generation");
            Objects.requireNonNull(resultText, "mujoong.luna.purpose.result-text");
            Objects.requireNonNull(goalCandidates, "mujoong.luna.purpose.goal-candidates");
            Objects.requireNonNull(rejudgment, "mujoong.luna.purpose.rejudgment");
        }

        /** 목적의 설정. */
        public PurposeOptions of(LunaPurpose lunaPurpose) {
            return switch (lunaPurpose) {
                case SCENARIO_GENERATION -> scenarioGeneration;
                case SCENARIO_REVIEW -> scenarioReview;
                case SEED_GENERATION -> seedGeneration;
                case RESULT_TEXT -> resultText;
                case GOAL_CANDIDATES -> goalCandidates;
                case REJUDGMENT -> rejudgment;
            };
        }
    }

    /**
     * 목적별 출력 한도 · 추론 강도(시간 값 아님).
     *
     * @param maxOutputTokens 요청 {@code max_output_tokens}(1 이상, 완료 보장이 아니다)
     * @param reasoningEffort 요청 {@code reasoning.effort}
     */
    public record PurposeOptions(int maxOutputTokens, String reasoningEffort) {

        public PurposeOptions {
            if (maxOutputTokens < 1) {
                throw new IllegalArgumentException("max-output-tokens는 1 이상이어야 한다");
            }
            if (reasoningEffort == null || !REASONING_EFFORTS.contains(reasoningEffort)) {
                throw new IllegalArgumentException("reasoning-effort 값이 올바르지 않다");
            }
        }
    }
}
