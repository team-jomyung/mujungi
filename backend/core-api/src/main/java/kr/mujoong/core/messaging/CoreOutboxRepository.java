package kr.mujoong.core.messaging;

import static kr.mujoong.core.common.db.ClockParams.instant;
import static kr.mujoong.core.common.db.ClockParams.integer;
import static kr.mujoong.core.common.db.ClockParams.text;
import static kr.mujoong.core.common.db.ClockParams.textArray;
import static kr.mujoong.core.common.db.ClockParams.uuid;
import static kr.mujoong.core.common.db.ClockParams.uuidArray;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Core Outbox {@code core.core_outbox_event} 데이터 접근 (U1 3.5 Step 15 · CoreMessaging · BR5.1 ~ BR5.3 · BR5.11 · BR5.14 · RD-8 ·
 * RD-10 · PD-8 SQ-15 · SQ-18).
 *
 * <ul>
 *   <li>기록: 업무 변경과 같은 트랜잭션에서만 쓴다(BR5.1). 활성 트랜잭션이 없으면 거부한다(전파 MANDATORY와 같은 뜻).
 *   <li>선점(Tx-A): PENDING이고 도래한 행을 {@code created_at, event_id} 순으로 batch건 {@code FOR UPDATE SKIP LOCKED}로 고르고 같은
 *       문장에서 {@code available_at}을 {@code now + claim-lease}로 올린다. 한 문장이라 따로 트랜잭션이 없어도 원자적이고 두 폴러가
 *       같은 행을 집지 않는다. 전송은 트랜잭션 밖이다(RD-8 · NFR7.6).
 *   <li>결과(Tx-B): 행 하나짜리 문장이고 조건은 {@code status = 'PENDING'}이라 삭제 참가자가 끼어들면 0행으로 끝난다.
 *   <li>PUBLISHED가 되면 payload를 {@code {}}로 비우고 purged_at을 채운다(BR5.11 · V1 검사).
 * </ul>
 */
@Repository
public class CoreOutboxRepository {

    private static final String COLUMNS = """
            event_id, event_type, schema_version, occurred_at, aggregate_type, aggregate_id, guardian_id, payload_json,
            payload_hash, status, retry_count, available_at, published_at, last_error, purged_at, created_at, trace_id
            """;

    /** SQL의 정렬({@code created_at, event_id})과 같은 순서. uuid의 바이트 순서는 소문자 표준 문자열 순서와 같다. */
    static final Comparator<OutboxEvent> CLAIM_ORDER = Comparator.comparing(OutboxEvent::createdAt)
            .thenComparing(event -> event.eventId().toString());

    private static final RowMapper<OutboxEvent> MAPPER = CoreOutboxRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public CoreOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * PENDING 행을 넣는다(BR5.1: available_at = created_at = now). 활성 트랜잭션 안에서만 부를 수 있다.
     *
     * @throws IllegalTransactionStateException 트랜잭션 밖에서 부르면(업무는 바뀌었는데 이벤트가 따로 커밋되는 일을 막는다)
     */
    public void insert(NewOutboxEvent event, Instant now) {
        Objects.requireNonNull(event, "event");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Outbox는 업무 변경과 같은 트랜잭션에서만 쓴다(BR5.1, 전파 MANDATORY)");
        }
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "eventId", event.eventId());
        text(params, "eventType", event.eventType());
        integer(params, "schemaVersion", event.schemaVersion());
        instant(params, "occurredAt", event.occurredAt());
        text(params, "aggregateType", event.aggregateType());
        uuid(params, "aggregateId", event.aggregateId());
        uuid(params, "guardianId", event.guardianId());
        text(params, "payload", event.payloadJson());
        text(params, "payloadHash", event.payloadHash());
        text(params, "traceId", event.traceId());
        jdbc.update("""
                insert into core.core_outbox_event (event_id, event_type, schema_version, occurred_at, aggregate_type,
                    aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count, available_at, created_at,
                    trace_id)
                values (:eventId, :eventType, :schemaVersion, :occurredAt, :aggregateType, :aggregateId, :guardianId,
                    cast(:payload as jsonb), :payloadHash, 'PENDING', 0, :now, :now, :traceId)
                """, params);
    }

    /**
     * 보낼 때가 된 PENDING 행을 선점한다(RD-8 Tx-A · SQ-15). 재시도 대기 중(available_at이 미래)인 행은 뒤 행을 막지 않는다(BR5.2).
     *
     * @return 선점한 행(available_at은 선점 만료 시각), {@code created_at, event_id} 순
     */
    public List<OutboxEvent> claimDue(Instant now, Duration claimLease, int batchSize) {
        Objects.requireNonNull(now, "now");
        MapSqlParameterSource params = ClockParams.at(now);
        instant(params, "leaseUntil", now.plus(positive(claimLease, "claimLease")));
        integer(params, "limit", positive(batchSize, "batchSize"));
        List<OutboxEvent> claimed = jdbc.query("""
                with picked as (
                    select event_id from core.core_outbox_event
                     where status = 'PENDING' and available_at <= :now
                     order by created_at, event_id
                     limit :limit
                     for update skip locked)
                update core.core_outbox_event o
                   set available_at = :leaseUntil
                  from picked
                 where o.event_id = picked.event_id
                returning o.event_id, o.event_type, o.schema_version, o.occurred_at, o.aggregate_type, o.aggregate_id,
                    o.guardian_id, o.payload_json, o.payload_hash, o.status, o.retry_count, o.available_at, o.published_at,
                    o.last_error, o.purged_at, o.created_at, o.trace_id
                """, params, MAPPER);
        return claimed.stream().sorted(CLAIM_ORDER).toList();
    }

    /** 200 → PUBLISHED, payload 비움(BR5.3 · BR5.11). */
    public int markPublished(UUID eventId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "eventId", Objects.requireNonNull(eventId, "eventId"));
        return jdbc.update("""
                update core.core_outbox_event
                   set status = 'PUBLISHED', published_at = :now, payload_json = '{}'::jsonb, purged_at = :now
                 where event_id = :eventId and status = 'PENDING'
                """, params);
    }

    /** 즉시 FAILED(409 · 400) 또는 재시도 상한 초과(BR5.3). {@code lastError}는 코드 · 예외 종류만(값 없음). */
    public int markFailed(UUID eventId, String lastError) {
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "eventId", Objects.requireNonNull(eventId, "eventId"));
        text(params, "lastError", requireText(lastError, "lastError"));
        return jdbc.update("""
                update core.core_outbox_event
                   set status = 'FAILED', last_error = :lastError
                 where event_id = :eventId and status = 'PENDING'
                """, params);
    }

    /**
     * 재시도 대기(BR5.3): retry_count를 {@code expectedRetryCount + 1}로, 다음 시도 시각을 {@code nextAvailableAt}으로 쓴다. 선점한 행의
     * retry_count가 그 사이 바뀌었으면 0행이다. retry_count는 V1 검사로 20을 넘지 못한다.
     */
    public int recordRetry(UUID eventId, int expectedRetryCount, Instant nextAvailableAt, String lastError) {
        if (expectedRetryCount < 0) {
            throw new IllegalArgumentException("expectedRetryCount는 0 이상이어야 한다");
        }
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "eventId", Objects.requireNonNull(eventId, "eventId"));
        integer(params, "expected", expectedRetryCount);
        integer(params, "next", expectedRetryCount + 1);
        instant(params, "nextAvailableAt", Objects.requireNonNull(nextAvailableAt, "nextAvailableAt"));
        text(params, "lastError", requireText(lastError, "lastError"));
        return jdbc.update("""
                update core.core_outbox_event
                   set retry_count = :next, available_at = :nextAvailableAt, last_error = :lastError
                 where event_id = :eventId and status = 'PENDING' and retry_count = :expected
                """, params);
    }

    /** 정지 요청 때 아직 보내지 않은 선점 행을 되돌린다(RD-8 6). */
    public int releaseClaims(Collection<UUID> eventIds, Instant now) {
        if (Objects.requireNonNull(eventIds, "eventIds").isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = ClockParams.at(now);
        uuidArray(params, "eventIds", eventIds);
        return jdbc.update("""
                update core.core_outbox_event set available_at = :now
                 where event_id = any(:eventIds) and status = 'PENDING' and available_at > :now
                """, params);
    }

    /** S3f: 크래시로 남은 선점 · 재시도 대기를 걷어 첫 사이클부터 대상이 되게 한다(retry_count 유지, RD-12 · R3-5). */
    public int releaseAllClaims(Instant now) {
        return jdbc.update("""
                update core.core_outbox_event set available_at = :now
                 where status = 'PENDING' and available_at > :now
                """, ClockParams.at(now));
    }

    /**
     * A1 재무장(RD-10): FAILED인 제어 이벤트 중 제외 last_error가 아니고 payload를 아직 비우지 않은 행을 PENDING · retry_count 0 ·
     * available_at = now로 되돌린다(last_error는 둔다). 시작 복구(S3d)가 부른다.
     */
    public int rearmFailedControlEvents(Set<EventType> controlEventTypes, Set<String> excludedLastErrors, Instant now) {
        return rearm(null, controlEventTypes, excludedLastErrors, now);
    }

    /** {@link #rearmFailedControlEvents}를 한 보호자의 행으로 한정한다(BR5.14 삭제 참가자는 FAILED 제어 이벤트를 지우지 않고 되살린다). */
    public int rearmFailedControlEventsForGuardian(
            UUID guardianId, Set<EventType> controlEventTypes, Set<String> excludedLastErrors, Instant now) {
        return rearm(Objects.requireNonNull(guardianId, "guardianId"), controlEventTypes, excludedLastErrors, now);
    }

    /** BR5.11 정리: 보존 기간이 지난 FAILED 행의 payload를 {@code batchSize}건까지 비운다. */
    public int purgeFailedOlderThan(Instant now, Duration retention, int batchSize) {
        Objects.requireNonNull(now, "now");
        MapSqlParameterSource params = ClockParams.at(now);
        instant(params, "cutoff", now.minus(positive(retention, "retention")));
        integer(params, "limit", positive(batchSize, "batchSize"));
        return jdbc.update("""
                update core.core_outbox_event
                   set payload_json = '{}'::jsonb, purged_at = :now
                 where event_id in (select event_id from core.core_outbox_event
                                     where status = 'FAILED' and created_at <= :cutoff and purged_at is null
                                     order by created_at, event_id
                                     limit :limit)
                   and purged_at is null
                """, params);
    }

    /**
     * BR5.14 (a): 보호자의 전달 끝(PUBLISHED · FAILED) 행을 지운다. PENDING은 남긴다(해제 · 삭제 신호가 사라지지 않게). 호출자는 먼저
     * {@link #rearmFailedControlEventsForGuardian}으로 FAILED 제어 이벤트를 되살린다. 멱등이다.
     */
    public int deleteDeliveredByGuardian(UUID guardianId) {
        return jdbc.update("""
                delete from core.core_outbox_event
                 where guardian_id = :guardianId and status in ('PUBLISHED', 'FAILED')
                """, uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")));
    }

    /** event_id로 행을 읽는다. */
    public Optional<OutboxEvent> findById(UUID eventId) {
        List<OutboxEvent> rows = jdbc.query("select " + COLUMNS + " from core.core_outbox_event where event_id = :eventId",
                uuid(new MapSqlParameterSource(), "eventId", Objects.requireNonNull(eventId, "eventId")), MAPPER);
        return rows.stream().findFirst();
    }

    // ------------------------------------------------------------------ 내부

    private int rearm(UUID guardianId, Set<EventType> controlEventTypes, Set<String> excludedLastErrors, Instant now) {
        Objects.requireNonNull(controlEventTypes, "controlEventTypes");
        Objects.requireNonNull(excludedLastErrors, "excludedLastErrors");
        List<String> types = new ArrayList<>();
        for (EventType type : controlEventTypes) {
            if (type == null || !type.isControlEvent()) {
                throw new IllegalArgumentException("재무장은 제어 이벤트만 대상이다(A1)");
            }
            types.add(type.wire());
        }
        if (types.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = ClockParams.at(now);
        textArray(params, "types", types);
        textArray(params, "excluded", excludedLastErrors);
        uuid(params, "guardianId", guardianId);
        return jdbc.update("""
                update core.core_outbox_event
                   set status = 'PENDING', retry_count = 0, available_at = :now
                 where status = 'FAILED' and event_type = any(:types) and purged_at is null
                   and (last_error is null or not (last_error = any(:excluded)))
                   and (cast(:guardianId as uuid) is null or guardian_id = :guardianId)
                """, params);
    }

    private static OutboxEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(
                ClockParams.uuid(rs, "event_id"),
                rs.getString("event_type"),
                rs.getInt("schema_version"),
                instant(rs, "occurred_at"),
                rs.getString("aggregate_type"),
                ClockParams.uuid(rs, "aggregate_id"),
                ClockParams.uuid(rs, "guardian_id"),
                rs.getString("payload_json"),
                rs.getString("payload_hash"),
                rs.getString("status"),
                rs.getInt("retry_count"),
                instant(rs, "available_at"),
                instant(rs, "published_at"),
                rs.getString("last_error"),
                instant(rs, "purged_at"),
                instant(rs, "created_at"),
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
     * 새 Outbox 행(BR5.1 · A3 · A4). 보호자 근거는 필수다(A4 · RD-11: U1 이벤트는 모두 보호자가 있다). traceId는 형식에 맞을 때만 둔다
     * (OD-7, 32자 소문자 16진수).
     *
     * @param payloadJson 정규화한 payload JSON
     * @param payloadHash 정규화 payload의 SHA-256 16진수
     */
    public record NewOutboxEvent(
            UUID eventId,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            String aggregateType,
            UUID aggregateId,
            UUID guardianId,
            String payloadJson,
            String payloadHash,
            String traceId) {

        public NewOutboxEvent {
            Objects.requireNonNull(eventId, "eventId");
            requireText(eventType, "eventType");
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schemaVersion은 1 이상이어야 한다");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(aggregateType, "aggregateType");
            Objects.requireNonNull(aggregateId, "aggregateId");
            Objects.requireNonNull(guardianId, "guardianId(A4: Outbox 행의 보호자 근거는 필수다)");
            requireText(payloadJson, "payloadJson");
            requireText(payloadHash, "payloadHash");
            if (traceId != null && !TraceIds.isValid(traceId)) {
                throw new IllegalArgumentException("traceId 형식이 틀렸다(OD-7)");
            }
        }

        /** payload는 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "NewOutboxEvent[eventId=" + eventId + ", eventType=" + eventType + "]";
        }
    }

    /** {@code core_outbox_event} 한 행. */
    public record OutboxEvent(
            UUID eventId,
            String eventType,
            int schemaVersion,
            Instant occurredAt,
            String aggregateType,
            UUID aggregateId,
            UUID guardianId,
            String payloadJson,
            String payloadHash,
            String status,
            int retryCount,
            Instant availableAt,
            Instant publishedAt,
            String lastError,
            Instant purgedAt,
            Instant createdAt,
            String traceId) {

        /** payload는 문자열 표현에 내지 않는다(전사 · 개인정보가 담길 수 있다, BR5.13). */
        @Override
        public String toString() {
            return "OutboxEvent[eventId=" + eventId + ", eventType=" + eventType + ", status=" + status + ", retryCount="
                    + retryCount + "]";
        }
    }
}
