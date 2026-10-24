package kr.mujoong.realtime.usage;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;

/** Responses 사용량만 해석한다. 거절·스키마 오류 응답이어도 반환된 사용량은 독립적으로 기록한다. */
public final class LunaUsage {
    private LunaUsage() { }

    public static AiUsageRecord read(JsonCodec json, String body, UUID callId, UUID turnId,
            String requestedModel, Instant capturedAt) {
        Map<?, ?> root;
        try { root = body == null ? Map.of() : json.read(body, Map.class); }
        catch (RuntimeException malformed) { root = Map.of(); }
        if (root == null) root = Map.of();
        Map<?, ?> usage = object(root.get("usage"));
        Long input = number(usage.get("input_tokens")), output = number(usage.get("output_tokens"));
        Long cached = number(object(usage.get("input_tokens_details")).get("cached_tokens"));
        Long reasoning = number(object(usage.get("output_tokens_details")).get("reasoning_tokens"));
        if (cached != null && input != null && cached > input) cached = null;
        if (reasoning != null && output != null && reasoning > output) reasoning = null;
        AiUsageRecord.Status status = input != null && output != null ? AiUsageRecord.Status.COMPLETE
                : input != null || output != null ? AiUsageRecord.Status.PARTIAL : AiUsageRecord.Status.UNKNOWN;
        String model = text(root.get("model"));
        return new AiUsageRecord(callId, AiUsageRecord.Source.LUNA, null, turnId, null, text(root.get("id")),
                model == null ? requestedModel : model, input, output, cached, reasoning, null, status, 1, capturedAt);
    }

    private static Map<?, ?> object(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() && s.length() <= 200 ? s : null;
    }
    private static Long number(Object value) {
        if (!(value instanceof Number n)) return null;
        try {
            java.math.BigDecimal decimal = new java.math.BigDecimal(n.toString());
            long result = decimal.longValueExact();
            return result < 0 ? null : result;
        } catch (ArithmeticException | NumberFormatException invalid) { return null; }
    }
}
