package kr.mujoong.realtime.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code realtime.realtime_activity_session} 데이터 접근 (U1 3.5 Step 16 · BR6.6 · BR6.8 · BR6.13 · RD-22 · PD-8 · J83).
 *
 * <p>명시 SQL(JDBC)로 쓰고 행 수를 확인한다 — 문장 하나가 규칙 하나와 맞고 JPA 더티 체킹이 문장을 바꾸지 않는다.
 * <ul>
 *   <li>INSERT(BR6.6 ④): 늘 PREPARING · status_version 1 · row_version 0. 같은 id나 보호자 · 기기 진행 중 부분 유일 색인과
 *       부딪히면 아무것도 하지 않고 false다(충돌 대상을 정하지 않은 {@code ON CONFLICT DO NOTHING}이라 트랜잭션이 깨지지 않는다).
 *       호출자는 id로 다시 읽어 행이 있으면 202 재생, 없으면 409 SESSION_IN_PROGRESS로 답한다.
 *   <li>전이(BR6.8): {@code UPDATE ... WHERE id = :id AND status = :from} 한 문장, status_version · row_version을 함께 올린다.
 *       0행이면 다른 전이가 이긴 것이다. 종료 상태에서 나가는 전이는 받지 않는다(종료는 되돌아가지 않음).
 *   <li>출발 상태가 둘 이상인 전이(→ FAILED의 시한 · 취소 · 재시작): 행을 {@code FOR UPDATE}로 읽어 현재 상태로 열 값을 정한 뒤
 *       {@code WHERE status = :읽은 상태}로 쓴다({@link #transitionFromAny}).
 * </ul>
 * 시각은 호출자가 Clock에서 읽어 넘긴 값만 쓴다(R2). 트랜잭션 경계는 서비스 계층이다(RD-22).
 */
@Repository
public class ActivitySessionRepository {

    /** 진행 중(비종료) 상태. 부분 유일 색인 술어와 같은 목록이다(BR6.3 · V1). */
    public static final Set<SessionStatus> IN_PROGRESS = EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK,
            SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE, SessionStatus.CLOSING);

    private static final String COLUMNS = "id, guardian_id, child_id, device_id, activity_type, status, status_version,"
            + " start_idempotency_key, gpt_live_session_id, consent_version, consent_checked_at, consent_snapshot_json,"
            + " policy_version, data_collection_allowed, transcript_storage_allowed, child_context_json,"
            + " intro_content_version_id, closing_content_version_id, end_reason, end_detail, termination_requested_at,"
            + " output_stopped_at, transport_closed_at, provider_closed_at, started_at, ended_at, row_version, created_at,"
            + " updated_at";

    private static final String INSERT = "insert into realtime.realtime_activity_session (id, guardian_id, child_id,"
            + " device_id, activity_type, status, status_version, start_idempotency_key, consent_checked_at,"
            + " consent_snapshot_json, policy_version, data_collection_allowed, transcript_storage_allowed,"
            + " child_context_json, intro_content_version_id, closing_content_version_id, row_version, created_at,"
            + " updated_at)"
            + " values (:id, :guardianId, :childId, :deviceId, :activityType, 'PREPARING', 1, :startIdempotencyKey, :now,"
            + " cast(:consentSnapshot as jsonb), :policyVersion, :voiceAndText, :voiceAndText,"
            + " cast(:childContext as jsonb), :introContentVersionId, :closingContentVersionId, 0, :now, :now)"
            // 충돌 대상을 정하지 않아 id 충돌과 보호자 · 기기 진행 중 부분 유일 색인 충돌을 모두 0행으로 받는다(BR6.6 ④)
            + " on conflict do nothing";

    private static final String SELECT_BY_ID = "select " + COLUMNS + " from realtime.realtime_activity_session"
            + " where id = :id";

    private static final String SELECT_FOR_UPDATE = SELECT_BY_ID + " for update";

    private static final String SELECT_IN_PROGRESS = "select " + COLUMNS + " from realtime.realtime_activity_session"
            + " where (guardian_id = :guardianId or device_id = :deviceId) and status = any(:statuses)"
            + " order by created_at, id";

    private static final String SELECT_BY_STATUS = "select " + COLUMNS + " from realtime.realtime_activity_session"
            + " where status = any(:statuses) order by created_at, id";

    /** 바꾸는 열 묶음. null 인자는 기존 값을 남긴다. started_at은 처음 ACTIVE 값을 남긴다. */
    private static final String CHANGE_SET = " updated_at = :now, row_version = row_version + 1,"
            + " end_reason = coalesce(:endReason, end_reason),"
            + " end_detail = coalesce(:endDetail, end_detail),"
            + " gpt_live_session_id = coalesce(:providerSessionId, gpt_live_session_id),"
            + " started_at = coalesce(started_at, :startedAt),"
            + " ended_at = coalesce(:endedAt, ended_at),"
            + " termination_requested_at = coalesce(:terminationRequestedAt, termination_requested_at),"
            + " output_stopped_at = coalesce(:outputStoppedAt, output_stopped_at),"
            + " transport_closed_at = coalesce(:transportClosedAt, transport_closed_at),"
            + " provider_closed_at = coalesce(:providerClosedAt, provider_closed_at)";

    private static final String TRANSITION = "update realtime.realtime_activity_session"
            + " set status = :to, status_version = status_version + 1," + CHANGE_SET
            + " where id = :id and status = :from"
            + " returning " + COLUMNS;

    private static final String ANNOTATE = "update realtime.realtime_activity_session set" + CHANGE_SET
            + " where id = :id and status = :expected"
            + " returning " + COLUMNS;

    private static final int MULTI_ORIGIN_ATTEMPTS = 2;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public ActivitySessionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = JsonCodec.shared();
    }

    /**
     * 새 세션 행을 PREPARING으로 쓴다(BR6.6 ④). 같은 id 또는 진행 중 보호자 · 기기 충돌이면 쓰지 않고 false다.
     *
     * @param now 시작 명령을 받은 시각(consent_checked_at · created_at · updated_at)
     * @return 새로 썼으면 true
     */
    public boolean insert(NewActivitySession session, Instant now) {
        Objects.requireNonNull(session, "session");
        ConsentSnapshot consent = session.consentSnapshot();
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", session.id());
        ClockParams.uuid(params, "guardianId", session.guardianId());
        ClockParams.uuid(params, "childId", session.childId());
        ClockParams.uuid(params, "deviceId", session.deviceId());
        ClockParams.text(params, "activityType", session.activityType().name());
        ClockParams.text(params, "startIdempotencyKey", session.startIdempotencyKey());
        ClockParams.text(params, "consentSnapshot", json.write(consent));
        ClockParams.text(params, "policyVersion", consent.policyVersion());
        params.addValue("voiceAndText", consent.voiceAndText());
        ClockParams.text(params, "childContext", json.write(session.childContext()));
        ClockParams.uuid(params, "introContentVersionId", session.introContentVersionId());
        ClockParams.uuid(params, "closingContentVersionId", session.closingContentVersionId());
        return jdbc.update(INSERT, params) == 1;
    }

    /** id로 읽는다. */
    public Optional<ActivitySessionRow> find(UUID id) {
        return single(jdbc.query(SELECT_BY_ID, idParam(id), ROW_MAPPER));
    }

    /** id로 읽고 행을 잠근다(BR6.8 다중 출발 전이). 활성 트랜잭션 안에서만 부른다. */
    public Optional<ActivitySessionRow> findForUpdate(UUID id) {
        Transactions.requireActive("ActivitySessionRepository.findForUpdate");
        return single(jdbc.query(SELECT_FOR_UPDATE, idParam(id), ROW_MAPPER));
    }

    /** 보호자 또는 기기의 진행 중 세션(BR6.6 ③ 2차 방어). */
    public List<ActivitySessionRow> findInProgress(UUID guardianId, UUID deviceId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        ClockParams.uuid(params, "deviceId", Objects.requireNonNull(deviceId, "deviceId"));
        ClockParams.textArray(params, "statuses", names(IN_PROGRESS));
        return jdbc.query(SELECT_IN_PROGRESS, params, ROW_MAPPER);
    }

    /** 진행 중(비종료) 세션 전부(BR6.13 S3a 재시작 정리 · R3-6 고아 점검). 만든 순서. */
    public List<ActivitySessionRow> findInProgress() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.textArray(params, "statuses", names(IN_PROGRESS));
        return jdbc.query(SELECT_BY_STATUS, params, ROW_MAPPER);
    }

    /**
     * 한 출발 상태에서의 전이(BR6.8). 이긴 쪽만 바뀐 행을 받는다.
     *
     * @return 이겼으면 바뀐 행, 행 수 0(다른 전이가 이김 · 행 없음)이면 비어 있음
     */
    public Optional<ActivitySessionRow> transition(UUID id, SessionStatus from, SessionStatus to, Instant now,
            SessionChange change) {
        requireTransition(from, to);
        MapSqlParameterSource params = changeParams(id, now, change);
        ClockParams.text(params, "from", from.name());
        ClockParams.text(params, "to", to.name());
        return single(jdbc.query(TRANSITION, params, ROW_MAPPER));
    }

    /**
     * 출발 상태가 둘 이상인 전이(BR6.8: → FAILED의 시한 · 취소 · 재시작). 활성 트랜잭션 안에서 행을 {@code FOR UPDATE}로 읽어
     * 출발 집합에 있으면 그 상태로 {@code changeFor}가 정한 열 값과 함께 전이한다. 0행이면 한 번 다시 읽어 시도한다.
     *
     * @param changeFor 읽은 현재 상태 → 함께 쓸 열 값(예: DEVICE_ACK이면 실패 코드 DEVICE_NO_ACK)
     */
    public MultiOriginTransition transitionFromAny(UUID id, Set<SessionStatus> origins, SessionStatus to, Instant now,
            Function<SessionStatus, SessionChange> changeFor) {
        Transactions.requireActive("ActivitySessionRepository.transitionFromAny");
        Objects.requireNonNull(changeFor, "changeFor");
        if (origins == null || origins.isEmpty()) {
            throw new IllegalArgumentException("출발 상태 집합이 비어 있다");
        }
        Optional<ActivitySessionRow> current = Optional.empty();
        for (int attempt = 0; attempt < MULTI_ORIGIN_ATTEMPTS; attempt++) {
            current = findForUpdate(id);
            if (current.isEmpty()) {
                return MultiOriginTransition.notFound();
            }
            SessionStatus read = current.get().status();
            if (!origins.contains(read)) {
                return MultiOriginTransition.notInOrigins(current.get());
            }
            Optional<ActivitySessionRow> changed = transition(id, read, to, now, changeFor.apply(read));
            if (changed.isPresent()) {
                return MultiOriginTransition.transitioned(read, changed.get());
            }
        }
        return MultiOriginTransition.notInOrigins(current.orElseThrow());
    }

    /**
     * 상태는 그대로 두고 열 값만 쓴다(예: CLOSING 중 출력 멈춤 · 전송 닫힘 시각, 공급자 세션 ID). row_version만 오른다.
     *
     * @param expected 이 상태일 때만 쓴다(늦은 기록이 종료 뒤 행을 바꾸지 않게)
     * @return 썼으면 바뀐 행
     */
    public Optional<ActivitySessionRow> annotate(UUID id, SessionStatus expected, Instant now, SessionChange change) {
        Objects.requireNonNull(expected, "expected");
        MapSqlParameterSource params = changeParams(id, now, change);
        ClockParams.text(params, "expected", expected.name());
        return single(jdbc.query(ANNOTATE, params, ROW_MAPPER));
    }

    private static void requireTransition(SessionStatus from, SessionStatus to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isTerminal()) {
            throw new IllegalArgumentException("종료 상태에서는 전이하지 않는다: " + from);
        }
        if (from == to || to == SessionStatus.REQUESTED || to == SessionStatus.UNRECOGNIZED
                || from == SessionStatus.REQUESTED || from == SessionStatus.UNRECOGNIZED) {
            throw new IllegalArgumentException("허용되지 않는 전이 형태: " + from + " -> " + to);
        }
    }

    private static MapSqlParameterSource changeParams(UUID id, Instant now, SessionChange change) {
        Objects.requireNonNull(change, "change");
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.uuid(params, "id", Objects.requireNonNull(id, "id"));
        ClockParams.text(params, "endReason", change.endReason() == null ? null : change.endReason().name());
        ClockParams.text(params, "endDetail", change.endDetailValue());
        ClockParams.text(params, "providerSessionId", change.providerSessionId());
        ClockParams.instant(params, "startedAt", change.startedAt());
        ClockParams.instant(params, "endedAt", change.endedAt());
        ClockParams.instant(params, "terminationRequestedAt", change.terminationRequestedAt());
        ClockParams.instant(params, "outputStoppedAt", change.outputStoppedAt());
        ClockParams.instant(params, "transportClosedAt", change.transportClosedAt());
        ClockParams.instant(params, "providerClosedAt", change.providerClosedAt());
        return params;
    }

    private static MapSqlParameterSource idParam(UUID id) {
        return ClockParams.uuid(new MapSqlParameterSource(), "id", Objects.requireNonNull(id, "id"));
    }

    private static List<String> names(Set<SessionStatus> statuses) {
        return statuses.stream().map(Enum::name).sorted().toList();
    }

    private static <T> Optional<T> single(List<T> rows) {
        if (rows.size() > 1) {
            throw new IllegalStateException("기본 키 질의가 여러 행을 돌려줬다");
        }
        return rows.stream().findFirst();
    }

    private static final RowMapper<ActivitySessionRow> ROW_MAPPER = ActivitySessionRepository::mapRow;

    private static ActivitySessionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        String endReason = rs.getString("end_reason");
        return new ActivitySessionRow(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "guardian_id"),
                ClockParams.uuid(rs, "child_id"),
                ClockParams.uuid(rs, "device_id"),
                ActivityType.fromWire(rs.getString("activity_type")),
                SessionStatus.fromWire(rs.getString("status")),
                rs.getInt("status_version"),
                rs.getString("start_idempotency_key"),
                rs.getString("gpt_live_session_id"),
                ClockParams.nullableInt(rs, "consent_version"),
                ClockParams.instant(rs, "consent_checked_at"),
                rs.getString("consent_snapshot_json"),
                rs.getString("policy_version"),
                rs.getBoolean("data_collection_allowed"),
                rs.getBoolean("transcript_storage_allowed"),
                rs.getString("child_context_json"),
                ClockParams.uuid(rs, "intro_content_version_id"),
                ClockParams.uuid(rs, "closing_content_version_id"),
                endReason == null ? null : EndReason.fromWire(endReason),
                rs.getString("end_detail"),
                ClockParams.instant(rs, "termination_requested_at"),
                ClockParams.instant(rs, "output_stopped_at"),
                ClockParams.instant(rs, "transport_closed_at"),
                ClockParams.instant(rs, "provider_closed_at"),
                ClockParams.instant(rs, "started_at"),
                ClockParams.instant(rs, "ended_at"),
                rs.getLong("row_version"),
                ClockParams.instant(rs, "created_at"),
                ClockParams.instant(rs, "updated_at"));
    }

    /**
     * 다중 출발 전이 결과.
     *
     * @param outcome 결과 종류
     * @param from 전이했으면 출발 상태(아니면 null)
     * @param row 전이했으면 바뀐 행, 출발 집합 밖이면 읽은 행, 행이 없으면 null
     */
    public record MultiOriginTransition(Outcome outcome, SessionStatus from, ActivitySessionRow row) {

        /** 결과 종류. */
        public enum Outcome {
            /** 이 호출이 전이를 이겼다. */
            TRANSITIONED,
            /** 현재 상태가 출발 집합 밖이다(이미 다른 전이가 끝남 · 20초 시한이 만난 ACTIVE 등). 무시한다. */
            NOT_IN_ORIGINS,
            /** 행이 없다. */
            NOT_FOUND
        }

        static MultiOriginTransition transitioned(SessionStatus from, ActivitySessionRow row) {
            return new MultiOriginTransition(Outcome.TRANSITIONED, from, row);
        }

        static MultiOriginTransition notInOrigins(ActivitySessionRow row) {
            return new MultiOriginTransition(Outcome.NOT_IN_ORIGINS, null, row);
        }

        static MultiOriginTransition notFound() {
            return new MultiOriginTransition(Outcome.NOT_FOUND, null, null);
        }

        /** 전이를 이겼는가. */
        public boolean transitioned() {
            return outcome == Outcome.TRANSITIONED;
        }
    }
}
