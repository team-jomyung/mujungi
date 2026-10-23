package kr.mujoong.realtime.luna;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Realtime Luna 호출 설정 {@code mujoong.luna.*} (U9 BR1.5 · SD3 · SD12 · NFR3.4 · NFR3.10). 값은 application.yml에만 있고 코드 기본값은
 * 모델 이름 하나뿐이다(env가 빈 값일 때 BR1.5). 시간 값이 아니다 — 시도 시한은 {@code mujoong.timing.judgment.live-budget}(BR5.1).
 *
 * @param model 요청 모델 이름(env {@code OPENAI_JUDGE_MODEL}, 비면 {@value #DEFAULT_MODEL})
 * @param baseUrl Responses API 기준 주소({@code {baseUrl}/responses}). 프로필 규칙은 {@link LunaHosts}
 * @param maxResponseBytes 응답 본문 바이트 상한(넘으면 LENGTH_EXCEEDED, NFR3.10)
 * @param purpose 목적별 출력 한도 · 추론 강도
 * @param enabled realtime Luna 스위치(U11 BR12.3, 기본 {@code false}). 시간 값이 아니라 R-M5 대상이 아니다. 키가 있어도 이 값이 false면 꺼진다
 * @param judgeEndpoint 진행 중 판정을 부를 끝점(FT-1023-d, env {@code LUNA_JUDGE_ENDPOINT}, 기본 {@code responses}). 시간 값 · 비밀이 아니다.
 *     모르는 값이면 바인딩이 실패해 기동하지 않는다. 비면 RESPONSES다
 */
@ConfigurationProperties(prefix = "mujoong.luna")
public record LunaProperties(String model, URI baseUrl, int maxResponseBytes, Purposes purpose,
        boolean enabled, JudgeEndpoint judgeEndpoint) {

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
        judgeEndpoint = judgeEndpoint == null ? JudgeEndpoint.RESPONSES : judgeEndpoint;
    }

    /** 목적의 출력 한도 · 추론 강도. */
    public PurposeOptions options(LunaPurpose lunaPurpose) {
        return switch (lunaPurpose) {
            case JUDGMENT_LIVE -> purpose.judgmentLive();
        };
    }

    /**
     * 진행 중 판정 끝점(FT-1023-d). 같은 공급자(OpenAI) · 같은 키 · 같은 최소 입력 필드지만 엔드포인트가 다르므로, 실제 아동 데이터로
     * DECISIONS를 켜기 전에 {@code infra/RUNBOOK.md}의 R-F15 확인 기록에 Decisions 끝점 확인을 남긴다.
     */
    public enum JudgeEndpoint {
        /** Responses API 구조화 출력(U9 기본 경로). */
        RESPONSES,
        /** Decisions API {@code choice} 질문. */
        DECISIONS
    }

    /** realtime 목적의 설정. */
    public record Purposes(PurposeOptions judgmentLive) {

        public Purposes {
            Objects.requireNonNull(judgmentLive, "mujoong.luna.purpose.judgment-live");
        }
    }

    /**
     * 목적별 출력 한도 · 추론 강도(시간 값 아님).
     *
     * @param maxOutputTokens 요청 {@code max_output_tokens}(1 이상)
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
