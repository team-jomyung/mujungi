package kr.mujoong.contract.event.payload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;

/**
 * SessionRecordCaptured payload(계약 6절). U4가 종료 상태에서 한 번 발행한다. U1은 생산하지 않고 전달 틀 · 재무장(A1) · 삭제 참가자의
 * payload 비우기(BR5.14)만 다루므로 기록 본문({@code record}) · 종료 시각 묶음({@code termination})은 해석하지 않은 채 둔다(U4가 모양을 소유).
 * 대화 원문을 담을 수 있어 로그에 쓰지 않는다(BR5.13).
 */
public record SessionRecordCaptured(
        UUID sessionId,
        UUID guardianId,
        UUID childId,
        ActivityType activityType,
        SessionStatus finalStatus,
        String endReason,
        Boolean confirmed,
        Instant startedAt,
        Instant endedAt,
        Map<String, Object> termination,
        UUID snapshotId,
        Integer snapshotVersion,
        String snapshotHash,
        Map<String, Object> record)
        implements EventPayload {

    private static final Pattern SNAPSHOT_HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    @Override
    public EventType eventType() {
        return EventType.SESSION_RECORD_CAPTURED;
    }

    @Override
    public UUID aggregateId() {
        return sessionId;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (sessionId == null) {
            problems.add("sessionId:missing");
        }
        if (guardianId == null) {
            problems.add("guardianId:missing");
        }
        if (childId == null) {
            problems.add("childId:missing");
        }
        if (activityType == null || activityType == ActivityType.UNRECOGNIZED) {
            problems.add("activityType:invalid");
        }
        if (finalStatus == null || !finalStatus.isTerminal()) {
            problems.add("finalStatus:invalid");
        }
        if (snapshotId == null) {
            problems.add("snapshotId:missing");
        }
        if (snapshotHash == null || !SNAPSHOT_HASH.matcher(snapshotHash).matches()) {
            problems.add("snapshotHash:invalid");
        }
        return problems;
    }
}
