package kr.mujoong.realtime.session;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;

/**
 * Realtime 세션 행을 처음 쓸 때의 값(BR6.6 ④ · entities ActivitySession). 상태는 늘 PREPARING · status_version 1 ·
 * row_version 0이고 시각은 저장소가 받은 {@code now}다(consent_checked_at도 시작 명령을 받은 시각, entities).
 *
 * @param id Core가 만든 sessionId(같은 값)
 * @param startIdempotencyKey Core의 idempotencyKey(같은 보호자 범위의 뜻, 1 ~ {@value #MAX_KEY_LENGTH}자)
 * @param closingContentVersionId 시작 때 고정한 마무리 인사 버전(core 논리 참조, 없으면 null)
 * @param introContentVersionId 인트로 버전(U1은 null, U4가 채움)
 */
public record NewActivitySession(
        UUID id,
        UUID guardianId,
        UUID childId,
        UUID deviceId,
        ActivityType activityType,
        String startIdempotencyKey,
        ConsentSnapshot consentSnapshot,
        ChildContext childContext,
        UUID closingContentVersionId,
        UUID introContentVersionId) {

    /** V1 열 길이 {@code start_idempotency_key varchar(100)}. */
    public static final int MAX_KEY_LENGTH = 100;

    public NewActivitySession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(deviceId, "deviceId");
        if (activityType == null || activityType == ActivityType.UNRECOGNIZED) {
            throw new IllegalArgumentException("activityType이 알려진 값이 아니다");
        }
        if (startIdempotencyKey == null || startIdempotencyKey.isBlank() || startIdempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("startIdempotencyKey는 1 ~ " + MAX_KEY_LENGTH + "자여야 한다");
        }
        Objects.requireNonNull(consentSnapshot, "consentSnapshot");
        Objects.requireNonNull(childContext, "childContext");
    }

    /** 문자열 표현에 아이 맥락 · 동의 값을 넣지 않는다(로그 위생, BR8.7). */
    @Override
    public String toString() {
        return "NewActivitySession[id=" + id + ", activityType=" + activityType + "]";
    }
}
