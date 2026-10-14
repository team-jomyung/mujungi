package kr.mujoong.core.notification.transport;

import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.core.notification.PushEventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 웹 푸시 평문 계약 전체. 아이 정보나 결과 내용은 추가하지 않는다. */
public record PushPayload(
        PushEventType type, ActivityType activityType, UUID sessionId, Instant sentAt) {
    public PushPayload {
        Objects.requireNonNull(type);
        Objects.requireNonNull(activityType);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(sentAt);
    }
}
