package kr.mujoong.core.record;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.event.payload.SessionRecordCaptured;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.identity.Guardian;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;
import kr.mujoong.core.messaging.handler.InboxHandler;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.record.ActivityRecordRepository.NewRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * SessionRecordCaptured 처리기 (U4a ActivityRecord · 계약 6절 · ADR-010 §2.5 · FR3.5 · 설계 R5 · R6 · U4a-J6 · J7).
 *
 * <ol>
 *   <li>보호자 행을 첫 잠금(FOR SHARE)으로 읽는다. 보호자가 없거나 ACTIVE가 아니면(계정 삭제 접수 뒤 도착) 저장 · 발행 없이 APP_ERROR
 *       {@code RecordAfterDeletion} 한 줄을 남기고 끝낸다(AC1.6.8 · U2 design 늦은 이벤트 폐기). 삭제 지휘의 접수와 같은 행을 잠그므로
 *       접수가 먼저 커밋되면 이 단계에서 걸러지고, 이 트랜잭션이 먼저면 접수가 기다렸다가 뒤에 참가자가 지운다.
 *   <li>세션 사본이 없거나 보호자 · 아이가 다르면 저장하지 않고 APP_ERROR {@code RecordWithoutSession} 한 줄을 남긴다(지워진 사본 · 잘못된 이벤트).
 *   <li>{@code snapshotHash}가 {@code sha256:} + record 정규화 JSON의 SHA-256과 다르면 처리 실패({@link SnapshotHashMismatchException}) —
 *       Inbox 재호출을 타고 DEAD로 남는다(조용히 버리지 않는다).
 *   <li>DECLINED는 머리 정보만 저장한다(발화 · 구간 · 연습 · 원음은 받아도 비운다).
 *   <li>같은 세션이 이미 있으면: 해시가 같으면 아무것도 하지 않고(ActivityRecorded를 다시 내지 않음), 다르면 첫 기록을 두고 APP_ERROR
 *       {@code EventConflict} 한 줄.
 *   <li>새로 넣었으면 같은 트랜잭션에서 ActivityRecorded를 낸다(구독자는 커밋 뒤 받는다).
 * </ol>
 *
 * <p>대화 원문은 로그 · 예외 메시지에 넣지 않는다(BR5.13). Inbox payload는 전달 뒤 비워진다(BR5.11).
 */
@Component
public class SessionRecordCapturedHandler implements InboxHandler {

    static final String HASH_PREFIX = "sha256:";

    private final GuardianWriteLock guardianLock;
    private final ActivityCopyRepository activities;
    private final ActivityRecordRepository records;
    private final ApplicationEventPublisher publisher;
    private final EventLogger events;
    private final JsonCodec json;

    public SessionRecordCapturedHandler(
            GuardianWriteLock guardianLock,
            ActivityCopyRepository activities,
            ActivityRecordRepository records,
            ApplicationEventPublisher publisher,
            EventLogger events,
            JsonCodec json) {
        this.guardianLock = Objects.requireNonNull(guardianLock, "guardianLock");
        this.activities = Objects.requireNonNull(activities, "activities");
        this.records = Objects.requireNonNull(records, "records");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.events = Objects.requireNonNull(events, "events");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public EventType eventType() {
        return EventType.SESSION_RECORD_CAPTURED;
    }

    @Override
    public Result handle(InboxEvent event, Instant now) {
        SessionRecordCaptured payload = json.read(event.payloadJson(), SessionRecordCaptured.class);
        if (payload == null || !payload.problems().isEmpty()) {
            return Result.done();
        }
        Optional<String> guardianStatus = guardianLock.statusForShare(payload.guardianId());
        if (guardianStatus.isEmpty() || !Guardian.STATUS_ACTIVE.equals(guardianStatus.get())) {
            events.appError(EventLog.Component.ACTIVITY_COORDINATOR, "RecordAfterDeletion");
            return Result.done();
        }
        Optional<ActivityCopy> copy = activities.findById(payload.sessionId());
        if (copy.isEmpty() || !copy.get().guardianId().equals(payload.guardianId())
                || !copy.get().childId().equals(payload.childId())) {
            events.appError(EventLog.Component.ACTIVITY_COORDINATOR, "RecordWithoutSession");
            return Result.done();
        }
        if (!snapshotHash(payload.record()).equals(payload.snapshotHash())) {
            throw new SnapshotHashMismatchException();
        }
        NewRecord record = toRecord(payload, copy.get());
        Optional<String> existing = records.insertIfAbsent(record, now);
        if (existing.isEmpty()) {
            publisher.publishEvent(new ActivityRecorded(record.sessionId(), record.guardianId(), record.childId(),
                    record.activityType(), record.finalStatus(), now));
        } else if (!existing.get().equals(payload.snapshotHash())) {
            events.appError(EventLog.Component.ACTIVITY_COORDINATOR, "EventConflict");
        }
        return Result.done();
    }

    /** record 객체의 정규화 JSON 해시(계약 6절 snapshotHash). record가 없으면 JSON null의 해시다. */
    String snapshotHash(Map<String, Object> record) {
        return HASH_PREFIX + json.sha256OfCanonical(json.write(record));
    }

    private NewRecord toRecord(SessionRecordCaptured payload, ActivityCopy copy) {
        boolean declined = payload.finalStatus() == SessionStatus.DECLINED;
        Map<String, Object> body = payload.record() == null || declined ? Map.of() : payload.record();
        Map<String, Object> scope = body.get("recordScope") instanceof Map<?, ?> m ? cast(m) : Map.of();
        Instant listedAt = payload.startedAt() != null ? payload.startedAt() : copy.requestedAt();
        return new NewRecord(
                payload.sessionId(),
                payload.guardianId(),
                payload.childId(),
                payload.activityType().name(),
                payload.finalStatus().name(),
                payload.endReason(),
                Boolean.TRUE.equals(payload.confirmed()),
                payload.startedAt(),
                payload.endedAt(),
                listedAt,
                instant(scope.get("from")),
                instant(scope.get("to")),
                json.write(list(body.get("utterances"))),
                json.write(list(body.get("gaps"))),
                body.get("practice") instanceof Map<?, ?> practice ? json.write(practice) : null,
                json.write(list(body.get("audio"))),
                payload.termination() == null ? null : json.write(payload.termination()),
                payload.snapshotId(),
                payload.snapshotVersion(),
                payload.snapshotHash());
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Instant instant(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** 스냅숏 해시가 내용과 다르다(내용 · 해시 값은 메시지에 넣지 않는다). */
    public static final class SnapshotHashMismatchException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public SnapshotHashMismatchException() {
            super("SessionRecordCaptured snapshotHash가 record 내용과 다르다");
        }
    }
}
