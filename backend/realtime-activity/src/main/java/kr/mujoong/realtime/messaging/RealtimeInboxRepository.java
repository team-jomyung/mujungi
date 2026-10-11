package kr.mujoong.realtime.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.realtime.common.db.ClockParams;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_inbox_event} 데이터 접근 (U1 3.5 Step 16 · BR5.5 ~ BR5.9 · BR5.11 · BR5.12 · RD-9 · A3 · A4 ·
 * SQ-16 · SQ-17 · SQ-18). Core Inbox와 같은 규칙을 앱마다 따로 둔다(LC-11).
 *
 * <ul>
 *   <li>수신(BR5.6): {@code INSERT ... ON CONFLICT (event_id) DO NOTHING}. 새 행이면 RECEIVED(next_attempt_at = received_at)
 *       또는 처음부터 IGNORED(payload 비움, BR5.9)다. 이미 있으면 false이고 호출자가 payload_hash로 중복 · 충돌을 가린다(BR5.5).
 *   <li>선점(BR5.7 · SQ-17): 처리기 호출 전 {@code next_attempt_at}을 선점 끝 시각으로 올리는 조건부 UPDATE의 행 수가 1일 때만
 *       부른다 — 첫 비동기 호출과 재호출 폴러가 같은 행을 두 번 부르지 않는다.
 *   <li>결과: PROCESSED(payload 비움) · 재시도(1 · 2 · 4 · 8 · 16초) · DEAD(retry_count 유지, BR5.8).
 * </ul>
 * 시각은 호출자가 Clock에서 읽은 값만 쓴다(R2).
 */
@Repository
public class RealtimeInboxRepository {

    /** 보낸 앱(V1 CHECK). */
    public static final String SOURCE_CORE = "CORE";

    private static final String COLUMNS = "event_id, source_service, event_type, schema_version, occurred_at,"
            + " aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count, next_attempt_at, received_at,"
            + " processed_at, last_error, purged_at, trace_id";

    private static final String INSERT = "insert into realtime.realtime_inbox_event (event_id, source_service,"
            + " event_type, schema_version, occurred_at, aggregate_id, guardian_id, payload_json, payload_hash, status,"
            + " retry_count, next_attempt_at, received_at, purged_at, trace_id)"
            + " values (:eventId, 'CORE', :eventType, :schemaVersion, :occurredAt, :aggregateId, :guardianId,"
            + " cast(:payload as jsonb), :payloadHash, :status, 0, :nextAttemptAt, :receivedAt, :purgedAt, :traceId)"
            + " on conflict (event_id) do nothing";

    private static final String SELECT_BY_ID = "select " + COLUMNS + " from realtime.realtime_inbox_event"
            + " where event_id = :eventId";

    private static final String RECORD_CONFLICT = "update realtime.realtime_inbox_event set last_error = :lastError"
            + " where event_id = :eventId";

    private static final String CLAIM = "update realtime.realtime_inbox_event set next_attempt_at = :leaseUntil"
            + " where event_id = :eventId and status = 'RECEIVED' and next_attempt_at <= :now";

    private static final String SELECT_DUE = "select " + COLUMNS + " from realtime.realtime_inbox_event"
            + " where status = 'RECEIVED' and next_attempt_at <= :now"
            + " order by next_attempt_at, received_at limit :limit";

    private static final String MARK_PROCESSED = "update realtime.realtime_inbox_event"
            + " set status = 'PROCESSED', processed_at = :now, payload_json = cast('{}' as jsonb), purged_at = :now"
            + " where event_id = :eventId and status = 'RECEIVED'";

    private static final String SCHEDULE_RETRY = "update realtime.realtime_inbox_event"
            + " set retry_count = retry_count + 1, next_attempt_at = :nextAttemptAt, last_error = :lastError"
            + " where event_id = :eventId and status = 'RECEIVED' and retry_count = :expectedRetryCount";

    private static final String MARK_DEAD = "update realtime.realtime_inbox_event"
            + " set status = 'DEAD', last_error = :lastError"
            + " where event_id = :eventId and status = 'RECEIVED'";

    private static final String PULL_FORWARD = "update realtime.realtime_inbox_event set next_attempt_at = :now"
            + " where status = 'RECEIVED'";

    private static final String PURGE_DEAD = "update realtime.realtime_inbox_event"
            + " set payload_json = cast('{}' as jsonb), purged_at = :now"
            + " where event_id in (select event_id from realtime.realtime_inbox_event"
            + " where status = 'DEAD' and received_at < :cutoff and purged_at is null"
            + " order by received_at, event_id limit :limit)";

    private static final String EMPTY_PAYLOAD = "{}";

    private final NamedParameterJdbcTemplate jdbc;

    public RealtimeInboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 새 eventId면 수락 기록을 쓴다(BR5.6 · BR5.9). 호출자는 이 트랜잭션을 커밋한 뒤에만 200을 돌려준다.
     *
     * @return 새로 썼으면 true, 같은 eventId가 이미 있으면 false
     */
    public boolean insertIfAbsent(NewInboxEvent event) {
        Objects.requireNonNull(event, "event");
        EventEnvelope envelope = event.envelope();
        Instant receivedAt = event.receivedAt();
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "eventId", envelope.eventId());
        ClockParams.text(params, "eventType", envelope.eventType());
        params.addValue("schemaVersion", envelope.schemaVersion());
        ClockParams.instant(params, "occurredAt", envelope.occurredAt());
        ClockParams.uuid(params, "aggregateId", envelope.aggregateId());
        ClockParams.uuid(params, "guardianId", event.guardianId());
        ClockParams.text(params, "payload", event.ignored() ? EMPTY_PAYLOAD : envelope.payloadJson());
        ClockParams.text(params, "payloadHash", event.payloadHash());
        ClockParams.text(params, "status",
                (event.ignored() ? InboxEventRow.Status.IGNORED : InboxEventRow.Status.RECEIVED).name());
        ClockParams.instant(params, "nextAttemptAt", event.ignored() ? null : receivedAt);
        ClockParams.instant(params, "receivedAt", receivedAt);
        ClockParams.instant(params, "purgedAt", event.ignored() ? receivedAt : null);
        ClockParams.text(params, "traceId", envelope.traceId());
        return jdbc.update(INSERT, params) == 1;
    }

    /** event_id로 읽는다. */
    public Optional<InboxEventRow> find(UUID eventId) {
        MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "eventId",
                Objects.requireNonNull(eventId, "eventId"));
        return jdbc.query(SELECT_BY_ID, params, ROW_MAPPER).stream().findFirst();
    }

    /** 같은 eventId에 다른 내용이 왔다(BR5.5): 기존 행은 그대로 두고 last_error에 EVENT_CONFLICT만 남긴다. */
    public boolean recordConflict(UUID eventId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        ClockParams.text(params, "lastError", LastError.EVENT_CONFLICT);
        return jdbc.update(RECORD_CONFLICT, params) == 1;
    }

    /**
     * 처리기 호출 전 선점(BR5.7 · SQ-17). RECEIVED이고 next_attempt_at이 도래한 행만 {@code now + claimLease}로 올린다.
     *
     * @return 이 호출이 선점했으면 true(그때만 처리기를 부른다)
     */
    public boolean claim(UUID eventId, Instant now, Duration claimLease) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        ClockParams.instant(params, "leaseUntil", now.plus(positive(claimLease)));
        return jdbc.update(CLAIM, params) == 1;
    }

    /** 재호출 폴러 후보(SQ-16): RECEIVED이고 도래한 행을 {@code next_attempt_at, received_at} 순으로 N건. */
    public List<InboxEventRow> findDue(Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit은 1 이상이어야 한다");
        }
        MapSqlParameterSource params = ClockParams.at(now);
        params.addValue("limit", limit);
        return jdbc.query(SELECT_DUE, params, ROW_MAPPER);
    }

    /** 처리기 업무 변경과 같은 트랜잭션에서 PROCESSED · payload 비움(BR5.7 · BR5.11). RECEIVED가 아니면 false. */
    public boolean markProcessed(UUID eventId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        return jdbc.update(MARK_PROCESSED, params) == 1;
    }

    /**
     * 처리기 실패 뒤 재호출 예약(BR5.8): retry_count + 1, next_attempt_at = 대기 끝. 읽은 retry_count와 같을 때만 쓴다.
     *
     * @param lastError 예외 종류 토큰(BR5.13)
     */
    public boolean scheduleRetry(UUID eventId, int expectedRetryCount, Instant nextAttemptAt, String lastError) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        params.addValue("expectedRetryCount", expectedRetryCount);
        ClockParams.instant(params, "nextAttemptAt", Objects.requireNonNull(nextAttemptAt, "nextAttemptAt"));
        ClockParams.text(params, "lastError", LastError.require(lastError));
        return jdbc.update(SCHEDULE_RETRY, params) == 1;
    }

    /** 6번째 실패: DEAD, retry_count 유지(BR5.8). RECEIVED가 아니면 false. */
    public boolean markDead(UUID eventId, String lastError) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        ClockParams.text(params, "lastError", LastError.require(lastError));
        return jdbc.update(MARK_DEAD, params) == 1;
    }

    /** 앱 시작 때 HTTP 수신 전(BR5.12 · S3c): 모든 RECEIVED 행의 next_attempt_at을 now로 당긴다(retry_count 유지). 바뀐 행 수. */
    public int pullForwardReceived(Instant now) {
        return jdbc.update(PULL_FORWARD, ClockParams.at(now));
    }

    /**
     * 지금 DEAD 행 수({@code mujoong_inbox_dead} · METRIC_SNAPSHOT inboxDead, OD-12 · NFR4.14 · 3.6 수정 J302). 상태가 앞선 색인
     * (realtime_inbox_event_poll_idx)을 탄다.
     */
    public long countDead() {
        Long dead = jdbc.queryForObject("select count(*) from realtime.realtime_inbox_event where status = 'DEAD'",
                new MapSqlParameterSource(), Long.class);
        return dead == null ? 0L : dead;
    }

    /** 정리(BR5.11 · SQ-18): {@code cutoff} 전에 받은 DEAD 행의 payload를 비운다(한 번에 {@code limit}행). 바뀐 행 수. */
    public int purgeDeadBefore(Instant cutoff, Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit은 1 이상이어야 한다");
        }
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.instant(params, "cutoff", Objects.requireNonNull(cutoff, "cutoff"));
        params.addValue("limit", limit);
        return jdbc.update(PURGE_DEAD, params);
    }

    private static Duration positive(Duration claimLease) {
        if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease는 0보다 커야 한다");
        }
        return claimLease;
    }

    private static final RowMapper<InboxEventRow> ROW_MAPPER = RealtimeInboxRepository::mapRow;

    private static InboxEventRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new InboxEventRow(
                ClockParams.uuid(rs, "event_id"),
                rs.getString("source_service"),
                rs.getString("event_type"),
                rs.getInt("schema_version"),
                ClockParams.instant(rs, "occurred_at"),
                ClockParams.uuid(rs, "aggregate_id"),
                ClockParams.uuid(rs, "guardian_id"),
                rs.getString("payload_json"),
                rs.getString("payload_hash"),
                InboxEventRow.Status.valueOf(rs.getString("status")),
                rs.getInt("retry_count"),
                ClockParams.instant(rs, "next_attempt_at"),
                ClockParams.instant(rs, "received_at"),
                ClockParams.instant(rs, "processed_at"),
                rs.getString("last_error"),
                ClockParams.instant(rs, "purged_at"),
                rs.getString("trace_id"));
    }
}
