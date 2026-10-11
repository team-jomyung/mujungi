package kr.mujoong.core.messaging;

import static kr.mujoong.core.common.db.ClockParams.instant;
import static kr.mujoong.core.common.db.ClockParams.integer;
import static kr.mujoong.core.common.db.ClockParams.text;
import static kr.mujoong.core.common.db.ClockParams.uuid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Core Inbox {@code core.core_inbox_event} 데이터 접근 (U1 3.5 Step 15 · CoreMessaging · BR5.5 ~ BR5.9 · BR5.11 · BR5.12 · BR5.14 ·
 * RD-9 · PD-8 SQ-16 · SQ-17 · SQ-18).
 *
 * <ul>
 *   <li>수신: {@code INSERT ... ON CONFLICT (event_id) DO NOTHING}. 새 행이 아니면 호출자가 {@link #findById}로 payload_hash를 비교한다
 *       (같으면 200 중복, 다르면 {@link #recordConflict} 뒤 409, BR5.5).
 *   <li>RECEIVED 행은 next_attempt_at = received_at으로 넣어 방금 받은 행도 폴러에 잡힌다(BR5.6 · V1 검사).
 *   <li>처리기 호출 전 선점은 조건부 UPDATE 한 문장이고 행 수 1인 호출만 처리기를 부른다(BR5.7). 두 입구(응답 뒤 제출 · 재호출 폴러)가
 *       이 문을 함께 쓴다.
 *   <li>PROCESSED · IGNORED가 되면 payload를 {@code {}}로 비운다(BR5.11).
 * </ul>
 */
@Repository
public class CoreInboxRepository {

    /** 수신 행의 원천 앱(V1 검사). */
    public static final String SOURCE_REALTIME = "REALTIME";

    private static final String COLUMNS = """
            event_id, source_service, event_type, schema_version, occurred_at, aggregate_id, guardian_id, payload_json,
            payload_hash, status, retry_count, next_attempt_at, received_at, processed_at, last_error, purged_at, trace_id
            """;

    private static final RowMapper<InboxEvent> MAPPER = CoreInboxRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public CoreInboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 새 이벤트를 RECEIVED로 넣는다(BR5.6: next_attempt_at = received_at). 같은 event_id가 이미 있으면 아무것도 하지 않는다.
     *
     * @return 새로 넣었으면 true
     */
    public boolean insertReceived(NewInboxEvent event) {
        MapSqlParameterSource params = insertParams(event);
        int inserted = jdbc.update("""
                insert into core.core_inbox_event (event_id, source_service, event_type, schema_version, occurred_at,
                    aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count, next_attempt_at, received_at,
                    trace_id)
                values (:eventId, 'REALTIME', :eventType, :schemaVersion, :occurredAt, :aggregateId, :guardianId,
                    cast(:payload as jsonb), :payloadHash, 'RECEIVED', 0, :receivedAt, :receivedAt, :traceId)
                on conflict (event_id) do nothing
                """, params);
        return inserted == 1;
    }

    /**
     * 처리기가 없거나 상위 schemaVersion인 새 이벤트를 처음부터 IGNORED로 넣는다(BR5.9). payload는 비우고 해시만 남긴다(BR5.11).
     *
     * @return 새로 넣었으면 true
     */
    public boolean insertIgnored(NewInboxEvent event) {
        MapSqlParameterSource params = insertParams(event);
        int inserted = jdbc.update("""
                insert into core.core_inbox_event (event_id, source_service, event_type, schema_version, occurred_at,
                    aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count, received_at, processed_at,
                    purged_at, trace_id)
                values (:eventId, 'REALTIME', :eventType, :schemaVersion, :occurredAt, :aggregateId, :guardianId,
                    '{}'::jsonb, :payloadHash, 'IGNORED', 0, :receivedAt, :receivedAt, :receivedAt, :traceId)
                on conflict (event_id) do nothing
                """, params);
        return inserted == 1;
    }

    /** event_id로 행을 읽는다(SQ-17). */
    public Optional<InboxEvent> findById(UUID eventId) {
        List<InboxEvent> rows = jdbc.query("select " + COLUMNS + " from core.core_inbox_event where event_id = :eventId",
                uuid(new MapSqlParameterSource(), "eventId", Objects.requireNonNull(eventId, "eventId")), MAPPER);
        return rows.stream().findFirst();
    }

    /** 같은 event_id에 다른 내용: 기존 행은 그대로 두고 last_error만 남긴다(BR5.5). */
    public int recordConflict(UUID eventId, String lastError) {
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "eventId", Objects.requireNonNull(eventId, "eventId"));
        text(params, "lastError", requireText(lastError, "lastError"));
        return jdbc.update("update core.core_inbox_event set last_error = :lastError where event_id = :eventId", params);
    }

    /**
     * 처리기 호출 전 선점(BR5.7 · SQ-17): RECEIVED이고 도래한 행의 next_attempt_at을 {@code now + claimLease}로 올린다.
     *
     * @return 1이면 이 호출이 처리기를 부른다, 0이면 다른 호출이 선점했거나 이미 끝났다
     */
    public int claim(UUID eventId, Instant now, Duration claimLease) {
        Objects.requireNonNull(now, "now");
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        instant(params, "leaseUntil", now.plus(positive(claimLease, "claimLease")));
        return jdbc.update("""
                update core.core_inbox_event set next_attempt_at = :leaseUntil
                 where event_id = :eventId and status = 'RECEIVED' and next_attempt_at <= :now
                """, params);
    }

    /** 재호출 폴러 후보(SQ-16): RECEIVED이고 도래한 행을 {@code next_attempt_at, received_at} 순으로 {@code batchSize}건. 잠그지 않는다. */
    public List<InboxEvent> findDue(Instant now, int batchSize) {
        MapSqlParameterSource params = ClockParams.at(now);
        integer(params, "limit", positive(batchSize, "batchSize"));
        return jdbc.query("select " + COLUMNS + """
                 from core.core_inbox_event
                where status = 'RECEIVED' and next_attempt_at <= :now
                order by next_attempt_at, received_at, event_id
                limit :limit
                """, params, MAPPER);
    }

    /** 처리기 트랜잭션 안에서 PROCESSED로 표시하고 payload를 비운다(BR5.7 · BR5.11). */
    public int markProcessed(UUID eventId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        return jdbc.update("""
                update core.core_inbox_event
                   set status = 'PROCESSED', processed_at = :now, payload_json = '{}'::jsonb, purged_at = :now
                 where event_id = :eventId and status = 'RECEIVED'
                """, params);
    }

    /**
     * 처리기 실패(BR5.8): retry_count를 {@code expectedRetryCount + 1}로, 다음 호출 시각을 {@code nextAttemptAt}으로 쓴다. retry_count는
     * V1 검사로 5를 넘지 못한다. {@code lastError}는 예외 종류만(payload 없음).
     */
    public int recordHandlerFailure(UUID eventId, int expectedRetryCount, Instant nextAttemptAt, String lastError) {
        if (expectedRetryCount < 0) {
            throw new IllegalArgumentException("expectedRetryCount는 0 이상이어야 한다");
        }
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "eventId", Objects.requireNonNull(eventId, "eventId"));
        integer(params, "expected", expectedRetryCount);
        integer(params, "next", expectedRetryCount + 1);
        instant(params, "nextAttemptAt", Objects.requireNonNull(nextAttemptAt, "nextAttemptAt"));
        text(params, "lastError", requireText(lastError, "lastError"));
        return jdbc.update("""
                update core.core_inbox_event
                   set retry_count = :next, next_attempt_at = :nextAttemptAt, last_error = :lastError
                 where event_id = :eventId and status = 'RECEIVED' and retry_count = :expected
                """, params);
    }

    /** 재호출 상한을 넘긴 처리기 실패는 DEAD(BR5.8, retry_count 유지, 사람이 확인). */
    public int markDead(UUID eventId, String lastError) {
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "eventId", Objects.requireNonNull(eventId, "eventId"));
        text(params, "lastError", requireText(lastError, "lastError"));
        return jdbc.update("""
                update core.core_inbox_event set status = 'DEAD', last_error = :lastError
                 where event_id = :eventId and status = 'RECEIVED'
                """, params);
    }

    /** 지금 DEAD 행 수({@code mujoong_inbox_dead} · METRIC_SNAPSHOT inboxDead, OD-12 · NFR4.14 · J322). 상태 색인을 탄다. */
    public long countDead() {
        Long dead = jdbc.queryForObject("select count(*) from core.core_inbox_event where status = 'DEAD'",
                new MapSqlParameterSource(), Long.class);
        return dead == null ? 0L : dead;
    }

    /** S3c: 모든 RECEIVED 행의 다음 시도 시각을 now로 당긴다(BR5.7 · BR5.12, retry_count 유지). HTTP 수신을 열기 전에 부른다. */
    public int pullForwardReceived(Instant now) {
        return jdbc.update("update core.core_inbox_event set next_attempt_at = :now where status = 'RECEIVED'",
                ClockParams.at(now));
    }

    /** BR5.11 정리: 받은 지 보존 기간이 지난 DEAD 행의 payload를 {@code batchSize}건까지 비운다. */
    public int purgeDeadOlderThan(Instant now, Duration retention, int batchSize) {
        Objects.requireNonNull(now, "now");
        MapSqlParameterSource params = ClockParams.at(now);
        instant(params, "cutoff", now.minus(positive(retention, "retention")));
        integer(params, "limit", positive(batchSize, "batchSize"));
        return jdbc.update("""
                update core.core_inbox_event
                   set payload_json = '{}'::jsonb, purged_at = :now
                 where event_id in (select event_id from core.core_inbox_event
                                     where status = 'DEAD' and received_at <= :cutoff and purged_at is null
                                     order by received_at, event_id
                                     limit :limit)
                   and purged_at is null
                """, params);
    }

    /** BR5.14 (a): 보호자의 처리 끝(PROCESSED · IGNORED · DEAD) 행을 지운다. RECEIVED는 남긴다. 멱등이다. */
    public int deleteFinishedByGuardian(UUID guardianId) {
        return jdbc.update("""
                delete from core.core_inbox_event
                 where guardian_id = :guardianId and status in ('PROCESSED', 'IGNORED', 'DEAD')
                """, uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")));
    }

    /**
     * BR5.14 (c): 대화 원문이 든 이벤트(SessionRecordCaptured)는 상태와 무관하게 payload를 비우고, RECEIVED이면 IGNORED로 닫는다. 멱등이다.
     *
     * @return 바뀐 행 수
     */
    public int closeTranscriptEventsForGuardian(UUID guardianId, EventType eventType, Instant now) {
        if (eventType != EventType.SESSION_RECORD_CAPTURED) {
            throw new IllegalArgumentException("BR5.14 (c)는 대화 원문이 든 SessionRecordCaptured만 대상이다");
        }
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        text(params, "eventType", eventType.wire());
        return jdbc.update("""
                update core.core_inbox_event
                   set payload_json = '{}'::jsonb,
                       purged_at = coalesce(purged_at, :now),
                       processed_at = case when status = 'RECEIVED' then :now else processed_at end,
                       status = case when status = 'RECEIVED' then 'IGNORED' else status end
                 where guardian_id = :guardianId and event_type = :eventType
                   and (status = 'RECEIVED' or purged_at is null)
                """, params);
    }

    // ------------------------------------------------------------------ 내부

    private static MapSqlParameterSource insertParams(NewInboxEvent event) {
        Objects.requireNonNull(event, "event");
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "eventId", event.eventId());
        text(params, "eventType", event.eventType());
        integer(params, "schemaVersion", event.schemaVersion());
        instant(params, "occurredAt", event.occurredAt());
        uuid(params, "aggregateId", event.aggregateId());
        uuid(params, "guardianId", event.guardianId());
        text(params, "payload", event.payloadJson());
        text(params, "payloadHash", event.payloadHash());
        instant(params, "receivedAt", event.receivedAt());
        text(params, "traceId", event.traceId());
        return params;
    }

    private static InboxEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new InboxEvent(
                ClockParams.uuid(rs, "event_id"),
                rs.getString("source_service"),
                rs.getString("event_type"),
                rs.getInt("schema_version"),
                instant(rs, "occurred_at"),
                ClockParams.uuid(rs, "aggregate_id"),
                ClockParams.uuid(rs, "guardian_id"),
                rs.getString("payload_json"),
                rs.getString("payload_hash"),
                rs.getString("status"),
                rs.getInt("retry_count"),
                instant(rs, "next_attempt_at"),
                instant(rs, "received_at"),
                instant(rs, "processed_at"),
                rs.getString("last_error"),
                instant(rs, "purged_at"),
                rs.getString("trace_id"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 비어 있다");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "는 0보다 커야 한다");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "는 0보다 커야 한다");
        }
        return value;
    }

    // ------------------------------------------------------------------ 값 형식

    /**
     * 받은 이벤트(봉투 필수 7필드 + traceId · A3, guardian_id · A4). guardianId는 payload의 guardianId가 먼저이고, 옛
     * DeviceStatusChanged는 기기 행에서 찾은 값이다(RD-11). eventType은 받은 문자열 그대로다(모르는 값도 IGNORED로 기록, BR5.9).
     */
    public record NewInboxEvent(
            UUID eventId,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            UUID aggregateId,
            UUID guardianId,
            String payloadJson,
            String payloadHash,
            String traceId,
            Instant receivedAt) {

        public NewInboxEvent {
            Objects.requireNonNull(eventId, "eventId");
            requireText(eventType, "eventType");
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schemaVersion은 1 이상이어야 한다");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(aggregateId, "aggregateId");
            requireText(payloadJson, "payloadJson");
            requireText(payloadHash, "payloadHash");
            if (traceId != null && !TraceIds.isValid(traceId)) {
                throw new IllegalArgumentException("traceId 형식이 틀렸다(OD-7)");
            }
            Objects.requireNonNull(receivedAt, "receivedAt");
        }

        /** payload는 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "NewInboxEvent[eventId=" + eventId + ", eventType=" + eventType + "]";
        }
    }

    /** {@code core_inbox_event} 한 행. */
    public record InboxEvent(
            UUID eventId,
            String sourceService,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            UUID aggregateId,
            UUID guardianId,
            String payloadJson,
            String payloadHash,
            String status,
            int retryCount,
            Instant nextAttemptAt,
            Instant receivedAt,
            Instant processedAt,
            String lastError,
            Instant purgedAt,
            String traceId) {

        /** payload는 문자열 표현에 내지 않는다(대화 원문이 담길 수 있다, BR5.13). */
        @Override
        public String toString() {
            return "InboxEvent[eventId=" + eventId + ", eventType=" + eventType + ", status=" + status + ", retryCount="
                    + retryCount + "]";
        }
    }
}
