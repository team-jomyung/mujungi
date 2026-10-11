package kr.mujoong.realtime.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_outbox_event} 데이터 접근 (U1 3.5 Step 16 · BR5.1 ~ BR5.3 · BR5.11 · BR5.12 · RD-8 · RD-10 · A1 · A3 ·
 * A4 · SQ-15 · SQ-18). Core Outbox와 같은 규칙을 앱마다 따로 둔다(LC-11).
 *
 * <ul>
 *   <li>기록(BR5.1): 업무 변경과 같은 트랜잭션에서만 쓴다(MANDATORY). 커밋 전에는 발송 대상이 아니고, 되돌려지면 이벤트도 없다.
 *   <li>선점(RD-8 Tx-A · SQ-15): 보낼 때가 된 PENDING 행을 {@code created_at, event_id} 순으로 N건
 *       {@code FOR UPDATE SKIP LOCKED}로 골라 {@code available_at}을 선점 끝 시각으로 올리는 한 문장이다. 보내는 동안 잠금 ·
 *       연결을 쥐지 않고, 재시도 대기 행(available_at이 미래)은 뒤 행을 막지 않는다.
 *   <li>결과(RD-8 Tx-B): 행 하나 · 조건 {@code status = 'PENDING'}의 짧은 문장. 삭제 참가자가 행을 지웠으면 0행으로 끝난다.
 * </ul>
 * 시각은 호출자가 Clock에서 읽은 값만 쓴다(R2).
 */
@Repository
public class RealtimeOutboxRepository {

    private static final String COLUMNS = "event_id, event_type, schema_version, occurred_at, aggregate_type,"
            + " aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count, available_at, published_at,"
            + " last_error, purged_at, created_at, trace_id";

    private static final String INSERT = "insert into realtime.realtime_outbox_event (event_id, event_type,"
            + " schema_version, occurred_at, aggregate_type, aggregate_id, guardian_id, payload_json, payload_hash, status,"
            + " retry_count, available_at, created_at, trace_id)"
            + " values (:eventId, :eventType, :schemaVersion, :occurredAt, :aggregateType, :aggregateId, :guardianId,"
            + " cast(:payload as jsonb), :payloadHash, 'PENDING', 0, :now, :now, :traceId)";

    private static final String SELECT_BY_ID = "select " + COLUMNS + " from realtime.realtime_outbox_event"
            + " where event_id = :eventId";

    private static final String CLAIM_DUE = "with picked as ("
            + " select event_id from realtime.realtime_outbox_event"
            + " where status = 'PENDING' and available_at <= :now"
            + " order by created_at, event_id limit :limit for update skip locked),"
            + " claimed as (update realtime.realtime_outbox_event o set available_at = :leaseUntil"
            + " from picked where o.event_id = picked.event_id returning o.*)"
            + " select " + COLUMNS + " from claimed order by created_at, event_id";

    private static final String MARK_PUBLISHED = "update realtime.realtime_outbox_event"
            + " set status = 'PUBLISHED', published_at = :now, payload_json = cast('{}' as jsonb), purged_at = :now"
            + " where event_id = :eventId and status = 'PENDING'";

    private static final String SCHEDULE_RETRY = "update realtime.realtime_outbox_event"
            + " set retry_count = retry_count + 1, available_at = :availableAt, last_error = :lastError"
            + " where event_id = :eventId and status = 'PENDING' and retry_count = :expectedRetryCount";

    private static final String MARK_FAILED = "update realtime.realtime_outbox_event"
            + " set status = 'FAILED', last_error = :lastError"
            + " where event_id = :eventId and status = 'PENDING'";

    private static final String RELEASE_CLAIMS = "update realtime.realtime_outbox_event set available_at = :now"
            + " where status = 'PENDING' and available_at > :now";

    private static final String RELEASE_GIVEN_CLAIMS = RELEASE_CLAIMS + " and event_id = any(:eventIds)";

    private static final String REARM_CONTROL_EVENTS = "update realtime.realtime_outbox_event"
            + " set status = 'PENDING', retry_count = 0, available_at = :now"
            + " where status = 'FAILED' and event_type = any(:types) and purged_at is null"
            + " and (last_error is null or last_error <> all(:excludedErrors))";

    private static final String BACKLOG = "select count(*) filter (where status = 'PENDING') as pending,"
            + " min(created_at) filter (where status = 'PENDING') as oldest_pending,"
            + " count(*) filter (where status = 'FAILED') as failed"
            + " from realtime.realtime_outbox_event where status in ('PENDING', 'FAILED')";

    private static final String PURGE_FAILED = "update realtime.realtime_outbox_event"
            + " set payload_json = cast('{}' as jsonb), purged_at = :now"
            + " where event_id in (select event_id from realtime.realtime_outbox_event"
            + " where status = 'FAILED' and created_at < :cutoff and purged_at is null"
            + " order by created_at, event_id limit :limit)";

    /** A1 재무장에서 빼는 last_error(즉시 FAILED · 계정 삭제로 닫은 행, RD-10). */
    public static final List<String> REARM_EXCLUDED_ERRORS =
            List.of(LastError.EVENT_CONFLICT, LastError.INVALID_ENVELOPE, LastError.ACCOUNT_DELETED);

    /** A1 재무장 대상 이벤트 종류: Realtime → Core 제어 이벤트(이벤트 종류 등록 표의 속성 한 곳, RD-10). */
    public static final List<String> REARM_EVENT_TYPES = Arrays.stream(EventType.values())
            .filter(EventType::isControlEvent)
            .filter(type -> type.direction() == EventType.Direction.REALTIME_TO_CORE)
            .map(EventType::wire)
            .sorted()
            .toList();

    private final NamedParameterJdbcTemplate jdbc;

    public RealtimeOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 업무 변경과 같은 트랜잭션에서 PENDING 행을 쓴다(BR5.1). {@code available_at = created_at = now}.
     *
     * @param now 업무 트랜잭션의 시각(Clock)
     */
    public void insert(NewOutboxEvent event, Instant now) {
        Objects.requireNonNull(event, "event");
        Transactions.requireActive("RealtimeOutboxRepository.insert");
        EventEnvelope envelope = event.envelope();
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "eventId", envelope.eventId());
        ClockParams.text(params, "eventType", envelope.eventType());
        params.addValue("schemaVersion", envelope.schemaVersion());
        ClockParams.instant(params, "occurredAt", envelope.occurredAt());
        ClockParams.text(params, "aggregateType", envelope.aggregateType());
        ClockParams.uuid(params, "aggregateId", envelope.aggregateId());
        ClockParams.uuid(params, "guardianId", event.guardianId());
        ClockParams.text(params, "payload", envelope.payloadJson());
        ClockParams.text(params, "payloadHash", event.payloadHash());
        ClockParams.text(params, "traceId", envelope.traceId());
        jdbc.update(INSERT, params);
    }

    /** event_id로 읽는다. */
    public Optional<OutboxEventRow> find(UUID eventId) {
        MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "eventId",
                Objects.requireNonNull(eventId, "eventId"));
        return jdbc.query(SELECT_BY_ID, params, ROW_MAPPER).stream().findFirst();
    }

    /**
     * 보낼 행을 선점한다(RD-8 Tx-A · SQ-15). 트랜잭션 밖에서 부르면 이 한 문장이 곧 짧은 트랜잭션이다.
     *
     * @param limit {@code mujoong.outbox.batch-size}
     * @param claimLease {@code mujoong.outbox.claim-lease}(선점 동안 다른 폴러에게 보이지 않음)
     * @return 선점한 행({@code created_at, event_id} 순)
     */
    public List<OutboxEventRow> claimDue(Instant now, int limit, Duration claimLease) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit은 1 이상이어야 한다");
        }
        if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease는 0보다 커야 한다");
        }
        MapSqlParameterSource params = ClockParams.at(now);
        params.addValue("limit", limit);
        ClockParams.instant(params, "leaseUntil", now.plus(claimLease));
        return jdbc.query(CLAIM_DUE, params, ROW_MAPPER);
    }

    /** 200 → PUBLISHED, payload {@code {}} · purged_at(BR5.3 · BR5.11). PENDING이 아니면 false. */
    public boolean markPublished(UUID eventId, Instant now) {
        return jdbc.update(MARK_PUBLISHED, idParams(eventId, now)) == 1;
    }

    /**
     * 재시도 대기(BR5.3): retry_count + 1, available_at = 대기 끝. 선점 때 읽은 retry_count와 같을 때만 쓴다(같은 실패를 두 번
     * 세지 않음). retry_count 상한(V1 CHECK 20)은 호출자가 넘기 전에 {@link #markFailed}로 간다.
     *
     * @param expectedRetryCount 선점 때 읽은 retry_count
     * @param lastError 상태 코드 · 예외 종류 토큰(BR5.13)
     */
    public boolean scheduleRetry(UUID eventId, int expectedRetryCount, Instant availableAt, String lastError) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        params.addValue("expectedRetryCount", expectedRetryCount);
        ClockParams.instant(params, "availableAt", Objects.requireNonNull(availableAt, "availableAt"));
        ClockParams.text(params, "lastError", LastError.require(lastError));
        return jdbc.update(SCHEDULE_RETRY, params) == 1;
    }

    /** FAILED(409 · 400 즉시, 또는 상한 뒤 실패, BR5.3). retry_count는 그대로 남는다. PENDING이 아니면 false. */
    public boolean markFailed(UUID eventId, String lastError) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        ClockParams.text(params, "lastError", LastError.require(lastError));
        return jdbc.update(MARK_FAILED, params) == 1;
    }

    /** 크래시 뒤 선점 해제(S3f · R3-5): available_at이 미래인 PENDING을 now로 당긴다(retry_count 유지). 바뀐 행 수. */
    public int releaseClaims(Instant now) {
        return jdbc.update(RELEASE_CLAIMS, ClockParams.at(now));
    }

    /** 정상 정지 때 아직 보내지 않은 선점 행만 되돌린다(RD-8 6). 바뀐 행 수. */
    public int releaseClaims(Collection<UUID> eventIds, Instant now) {
        Objects.requireNonNull(eventIds, "eventIds");
        if (eventIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuidArray(params, "eventIds", eventIds);
        return jdbc.update(RELEASE_GIVEN_CLAIMS, params);
    }

    /**
     * A1 재무장(RD-10): FAILED 제어 이벤트를 PENDING · retry_count 0 · available_at now로 되돌린다. 즉시 FAILED(EVENT_CONFLICT ·
     * INVALID_ENVELOPE) · 계정 삭제로 닫은 행(ACCOUNT_DELETED) · payload를 비운 행과 일반 이벤트는 그대로 둔다. last_error는 남긴다.
     *
     * @return 되돌린 행 수(STARTUP_RECOVERY의 rearmedControlEvents)
     */
    public int rearmControlEvents(Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.textArray(params, "types", REARM_EVENT_TYPES);
        ClockParams.textArray(params, "excludedErrors", REARM_EXCLUDED_ERRORS);
        return jdbc.update(REARM_CONTROL_EVENTS, params);
    }

    /**
     * 정리(BR5.11 · SQ-18): {@code cutoff} 전에 만든 FAILED 행의 payload를 비운다(한 번에 {@code limit}행). 바뀐 행 수.
     *
     * @param cutoff now − {@code mujoong.retention.failed-payload}
     */
    public int purgeFailedBefore(Instant cutoff, Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit은 1 이상이어야 한다");
        }
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.instant(params, "cutoff", Objects.requireNonNull(cutoff, "cutoff"));
        params.addValue("limit", limit);
        return jdbc.update(PURGE_FAILED, params);
    }

    /**
     * 적체 지표(OD-12 · NFR7.8 · 3.6 수정 J302): PENDING 수와 그중 가장 오래된 created_at(선점으로 밀린 available_at과 무관), FAILED 수.
     * 상태가 앞선 색인(realtime_outbox_event_poll_idx)을 타는 한 문장이다(METRIC_SNAPSHOT · {@code mujoong_outbox_*} 게이지).
     */
    public Backlog backlog() {
        return jdbc.queryForObject(BACKLOG, new MapSqlParameterSource(), (rs, rowNum) -> new Backlog(rs.getLong("pending"),
                Optional.ofNullable(ClockParams.instant(rs, "oldest_pending")), rs.getLong("failed")));
    }

    /** Outbox 적체: PENDING 수 · 가장 오래된 PENDING의 created_at(없으면 빈 값) · FAILED 수. */
    public record Backlog(long pending, Optional<Instant> oldestPendingCreatedAt, long failed) {

        public Backlog {
            Objects.requireNonNull(oldestPendingCreatedAt, "oldestPendingCreatedAt");
        }
    }

    private static MapSqlParameterSource idParams(UUID eventId, Instant now) {
        return ClockParams.uuid(ClockParams.at(now), "eventId", Objects.requireNonNull(eventId, "eventId"));
    }

    private static final RowMapper<OutboxEventRow> ROW_MAPPER = RealtimeOutboxRepository::mapRow;

    private static OutboxEventRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEventRow(
                ClockParams.uuid(rs, "event_id"),
                rs.getString("event_type"),
                rs.getInt("schema_version"),
                ClockParams.instant(rs, "occurred_at"),
                rs.getString("aggregate_type"),
                ClockParams.uuid(rs, "aggregate_id"),
                ClockParams.uuid(rs, "guardian_id"),
                rs.getString("payload_json"),
                rs.getString("payload_hash"),
                OutboxEventRow.Status.valueOf(rs.getString("status")),
                rs.getInt("retry_count"),
                ClockParams.instant(rs, "available_at"),
                ClockParams.instant(rs, "published_at"),
                rs.getString("last_error"),
                ClockParams.instant(rs, "purged_at"),
                ClockParams.instant(rs, "created_at"),
                rs.getString("trace_id"));
    }
}
