package kr.mujoong.core.notification.transport;

import kr.mujoong.contract.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/** 행 잠금·HTTP와 분리한 수신처 상태 계산. */
public final class PushDeliveryLedger {
    private PushDeliveryLedger() {}

    public static Map<String, RecipientProgress> read(JsonCodec json, String source) {
        Map<?, ?> raw = json.read(source, Map.class);
        Map<String, RecipientProgress> values = new LinkedHashMap<>();
        raw.forEach(
                (key, value) ->
                        values.put(
                                java.util.UUID.fromString(key.toString()).toString(),
                                json.read(json.write(value), RecipientProgress.class)));
        return values;
    }

    public static String status(Map<String, RecipientProgress> recipients) {
        if (recipients.values().stream().anyMatch(RecipientProgress::pendingDelivery))
            return "REQUESTED";
        if (recipients.values().stream()
                .anyMatch(p -> p.status() == RecipientProgress.Status.FAILED)) return "FAILED";
        if (recipients.values().stream().anyMatch(p -> p.status() == RecipientProgress.Status.SENT))
            return "SENT";
        return "SKIPPED";
    }

    public static int retryIndex(Map<String, RecipientProgress> recipients, int backoffCount) {
        if (backoffCount < 1) throw new IllegalArgumentException("잘못된 푸시 재시도 일정");
        int attempted =
                recipients.values().stream()
                        .filter(RecipientProgress::pendingDelivery)
                        .mapToInt(RecipientProgress::attempt)
                        .max()
                        .orElse(1);
        return Math.min(backoffCount - 1, Math.max(0, attempted - 1));
    }
}
