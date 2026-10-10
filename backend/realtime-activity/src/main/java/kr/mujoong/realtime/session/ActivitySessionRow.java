package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;

/**
 * {@code realtime.realtime_activity_session} 한 행(29열, entities ActivitySession · E5). 저장소가 읽은 값이며 바꾸지 않는다.
 *
 * <p>U1은 FAILED의 실패 코드를 {@code end_detail}에 적으므로 {@link #failureCode()}가 그 값을 읽는다. 공급자 세션 ID · 동의 ·
 * 아이 맥락은 문자열 표현에 넣지 않는다(BR8.7 · NFR3).
 */
public record ActivitySessionRow(
        UUID id,
        UUID guardianId,
        UUID childId,
        UUID deviceId,
        ActivityType activityType,
        SessionStatus status,
        int statusVersion,
        String startIdempotencyKey,
        String providerSessionId,
        Integer consentVersion,
        Instant consentCheckedAt,
        String consentSnapshotJson,
        String policyVersion,
        boolean dataCollectionAllowed,
        boolean transcriptStorageAllowed,
        String childContextJson,
        UUID introContentVersionId,
        UUID closingContentVersionId,
        EndReason endReason,
        String endDetail,
        Instant terminationRequestedAt,
        Instant outputStoppedAt,
        Instant transportClosedAt,
        Instant providerClosedAt,
        Instant startedAt,
        Instant endedAt,
        long rowVersion,
        Instant createdAt,
        Instant updatedAt) {

    /** FAILED 행의 실패 코드({@code end_detail}). FAILED가 아니거나 비어 있으면 null(Core 취소 정리, BR6.7). */
    public FailureCode failureCode() {
        if (status != SessionStatus.FAILED || endDetail == null) {
            return null;
        }
        return FailureCode.fromWire(endDetail);
    }

    /** ENDED는 true, ENDED_UNCONFIRMED는 false, 그 밖은 null(SessionStateChanged.confirmed, BR6.8). */
    public Boolean confirmed() {
        return switch (status) {
            case ENDED -> Boolean.TRUE;
            case ENDED_UNCONFIRMED -> Boolean.FALSE;
            default -> null;
        };
    }

    /** 고정한 동의 스냅샷. */
    public ConsentSnapshot consentSnapshot() {
        return JsonCodec.shared().read(consentSnapshotJson, ConsentSnapshot.class);
    }

    /** 고정한 아이 맥락(없으면 null). */
    public ChildContext childContext() {
        return childContextJson == null ? null : JsonCodec.shared().read(childContextJson, ChildContext.class);
    }

    @Override
    public String toString() {
        return "ActivitySessionRow[id=" + id + ", status=" + status + ", statusVersion=" + statusVersion
                + ", rowVersion=" + rowVersion + "]";
    }
}
