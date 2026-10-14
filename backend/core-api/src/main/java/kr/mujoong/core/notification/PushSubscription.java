package kr.mujoong.core.notification;

import java.time.Instant;
import java.util.UUID;

/** 브라우저 하나의 푸시 구독. endpoint와 수신 키는 문자열 표현에 싣지 않는다(U7 · SD-11). */
public record PushSubscription(UUID id, UUID guardianId, String endpoint, String p256dh, String auth,
        String platform, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
    @Override
    public String toString() {
        return "PushSubscription[id=" + id + ", credentials=REDACTED]";
    }
}
