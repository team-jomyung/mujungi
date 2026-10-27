package kr.mujoong.core.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;

/**
 * Responses API 응답 본문에서 사용량만 읽는다. 업무 응답이 거절 · 스키마 오류여도 받은 사용량은 그대로 기록하고, 본문이 없거나 깨졌으면 미확인이다.
 * 캐시 읽기 · 캐시 쓰기는 입력 토큰의, 추론 토큰은 출력 토큰의 일부라 전체에 다시 더하지 않는다. 캐시 쓰기는 별도 단가라 따로 보관한다.
 */
public final class ResponsesUsage {

    private ResponsesUsage() {
    }

    /** 전송 시도 한 번의 정보. 본문이 없으면(시간 초과 · 전송 오류) {@code body}는 null이다. */
    public record Attempt(UUID callId, int attemptNo, String purpose, AiUsageLink link, String promptVersion,
            String requestedModel, String outcome, String body, Instant observedAt) {
    }

    public static AiUsageRecord read(JsonCodec json, Attempt attempt) {
        Map<?, ?> root = parse(json, attempt.body());
        Map<?, ?> usage = object(root.get("usage"));
        Long input = number(usage.get("input_tokens"));
        Long output = number(usage.get("output_tokens"));
        Map<?, ?> inputDetails = object(usage.get("input_tokens_details"));
        Long cached = number(inputDetails.get("cached_tokens"));
        Long cacheWrite = number(inputDetails.get("cache_write_tokens"));
        Long reasoning = number(object(usage.get("output_tokens_details")).get("reasoning_tokens"));
        if (cached != null && input != null && cached > input) {
            cached = null;
        }
        if (cacheWrite != null && input != null && (cached == null ? 0 : cached) + cacheWrite > input) {
            cacheWrite = null;
        }
        if (reasoning != null && output != null && reasoning > output) {
            reasoning = null;
        }
        AiUsageRecord.Status status = input != null && output != null ? AiUsageRecord.Status.COMPLETE
                : input != null || output != null ? AiUsageRecord.Status.PARTIAL : AiUsageRecord.Status.UNKNOWN;
        String model = text(root.get("model"));
        return new AiUsageRecord(UUID.randomUUID(), attempt.callId(), attempt.attemptNo(), attempt.purpose(), attempt.link(),
                attempt.promptVersion(), model == null ? attempt.requestedModel() : model, text(root.get("id")), attempt.outcome(),
                input, output, cached, cacheWrite, reasoning, status, attempt.observedAt());
    }

    private static Map<?, ?> parse(JsonCodec json, String body) {
        if (body == null) {
            return Map.of();
        }
        try {
            Map<?, ?> root = json.read(body, Map.class);
            return root == null ? Map.of() : root;
        } catch (RuntimeException malformed) {
            // JSON이 아닌 본문은 사용량을 알 수 없다(원인 메시지는 본문 일부를 담을 수 있어 버린다)
            return Map.of();
        }
    }

    private static Map<?, ?> object(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() && s.length() <= 200 ? s : null;
    }

    private static Long number(Object value) {
        if (!(value instanceof Number n)) {
            return null;
        }
        try {
            long result = new BigDecimal(n.toString()).longValueExact();
            return result < 0 ? null : result;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }
}
