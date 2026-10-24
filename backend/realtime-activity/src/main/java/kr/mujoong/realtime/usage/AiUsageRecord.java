package kr.mujoong.realtime.usage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 원문 없이 전달하는 공급자 사용량. null은 미확인이며 실제 0과 다르다. capturedAt은 사용량 수신 시각이다. */
public record AiUsageRecord(UUID id, Source source, UUID sessionId, UUID turnId,
        String providerSessionId, String responseId, String model, Long inputTokens,
        Long outputTokens, Long cachedInputTokens, Long reasoningTokens, Double voiceSeconds,
        Status status, long revision, Instant capturedAt) {

    public enum Source { VOICE, LUNA }
    public enum Status { UNKNOWN, PARTIAL, COMPLETE }

    public AiUsageRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(source);
        Objects.requireNonNull(status);
        Objects.requireNonNull(capturedAt);
        if (sessionId == null && turnId == null) {
            throw new IllegalArgumentException("사용량 연결 식별자가 필요하다");
        }
        if (revision < 0 || invalid(inputTokens) || invalid(outputTokens) || invalid(cachedInputTokens)
                || invalid(reasoningTokens) || voiceSeconds != null && (!Double.isFinite(voiceSeconds) || voiceSeconds < 0)) {
            throw new IllegalArgumentException("사용량은 유효한 0 이상의 값이어야 한다");
        }
        if (cachedInputTokens != null && inputTokens != null && cachedInputTokens > inputTokens
                || reasoningTokens != null && outputTokens != null && reasoningTokens > outputTokens) {
            throw new IllegalArgumentException("세부 토큰은 전체 토큰을 넘을 수 없다");
        }
        if (status == Status.COMPLETE && (source == Source.VOICE ? voiceSeconds == null : inputTokens == null || outputTokens == null)) {
            throw new IllegalArgumentException("완료 사용량에 필수 값이 없다");
        }
        for (String value : new String[] { providerSessionId, responseId, model }) {
            if (value != null && value.length() > 200) throw new IllegalArgumentException("사용량 식별자 길이 초과");
        }
    }

    private static boolean invalid(Long value) { return value != null && value < 0; }

    @Override
    public String toString() {
        return "AiUsageRecord[source=" + source + ", status=" + status + ", revision=" + revision + "]";
    }
}
