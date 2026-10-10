package kr.mujoong.core.activity;

import static kr.mujoong.core.common.db.ClockParams.instant;
import static kr.mujoong.core.common.db.ClockParams.integer;
import static kr.mujoong.core.common.db.ClockParams.nullableInt;
import static kr.mujoong.core.common.db.ClockParams.text;
import static kr.mujoong.core.common.db.ClockParams.uuid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Core 세션 사본 {@code core.core_activity} 데이터 접근 (U1 3.5 Step 15 · ActivityCoordinator · PD-8 SQ-7 ~ SQ-9 · RD-22).
 *
 * <p>사본은 Realtime의 SessionStateChanged로만 진행한다(BR6.14 · BR6.17). 예외는 Core가 직접 닫는 로컬 FAILED 셋(BR6.6 · BR6.7 ·
 * BR6.12 ①)과 BR6.1 ②의 대조다. 모든 쓰기는 조건부 한 문장이고 호출자가 행 수를 본다(BR5.10). Core는 세션 원본 표에 쓰지 않는다
 * (BR6.17, ArchUnit). 트랜잭션은 열지 않는다(RD-22).
 */
@Repository
public class ActivityCopyRepository {

    /** 진행 중(종료 아님) 상태 목록. 부분 유일 색인 {@code core_activity_in_progress_guardian_uk}의 술어와 같다(PD-8 SQ-7). */
    public static final Set<SessionStatus> IN_PROGRESS = EnumSet.of(SessionStatus.REQUESTED, SessionStatus.PREPARING,
            SessionStatus.DEVICE_ACK, SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE, SessionStatus.CLOSING);

    /** 로컬 FAILED가 기록할 수 있는 시작 응답 코드(V1 검사 · entities.md start_result_code). */
    public static final Set<Integer> LOCAL_FAILED_RESULT_CODES = Set.of(202, 409, 423, 503);

    private static final String COLUMNS = """
            session_id, guardian_id, child_id, device_id, activity_type, status, status_version, end_reason, failure_code,
            confirmed, local_failed, start_result_code, start_idempotency_key, request_hash, requested_at, started_at,
            ended_at, observed_at, created_at, updated_at
            """;

    private static final String IN_PROGRESS_SQL =
            "status in ('REQUESTED', 'PREPARING', 'DEVICE_ACK', 'WAITING_CHILD', 'ACTIVE', 'CLOSING')";

    private static final RowMapper<ActivityCopy> MAPPER = ActivityCopyRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public ActivityCopyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * REQUESTED 사본을 넣는다(BR6.1 · BR6.2 · BR6.3, status_version 0). 같은 (보호자, 키) 유일 위반이나 보호자당 진행 중 하나의 부분
     * 유일 위반이면 오류 대신 {@code false}다({@code ON CONFLICT DO NOTHING}이라 트랜잭션이 깨지지 않는다). 그때 호출자는
     * {@link #findByIdempotencyKey}로 다시 읽어 같은 키 행이 있으면 재생하고(BR6.2), 없을 때만 409 SESSION_IN_PROGRESS다(BR6.3).
     *
     * @return 넣었으면 true
     */
    public boolean insertRequested(NewActivity activity) {
        Objects.requireNonNull(activity, "activity");
        MapSqlParameterSource params = ClockParams.at(activity.requestedAt());
        uuid(params, "sessionId", activity.sessionId());
        uuid(params, "guardianId", activity.guardianId());
        uuid(params, "childId", activity.childId());
        uuid(params, "deviceId", activity.deviceId());
        text(params, "activityType", activity.activityType().name());
        text(params, "key", activity.idempotencyKey());
        text(params, "requestHash", activity.requestHash());
        int inserted = jdbc.update("""
                insert into core.core_activity (session_id, guardian_id, child_id, device_id, activity_type, status,
                    status_version, confirmed, local_failed, start_idempotency_key, request_hash, requested_at, created_at,
                    updated_at)
                values (:sessionId, :guardianId, :childId, :deviceId, :activityType, 'REQUESTED', 0, false, false, :key,
                    :requestHash, :now, :now, :now)
                on conflict do nothing
                """, params);
        return inserted == 1;
    }

    /** 같은 (보호자, 키)의 사본(BR6.1 ⓪′ · BR6.2 · SQ-7). */
    public Optional<ActivityCopy> findByIdempotencyKey(UUID guardianId, String idempotencyKey) {
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "guardianId",
                Objects.requireNonNull(guardianId, "guardianId"));
        text(params, "key", requireText(idempotencyKey, "idempotencyKey"));
        return single("select " + COLUMNS
                + " from core.core_activity where guardian_id = :guardianId and start_idempotency_key = :key", params);
    }

    /** 보호자의 진행 중 사본(BR6.1 ② · SQ-7). 부분 유일 색인이라 최대 1행이다. */
    public Optional<ActivityCopy> findInProgressByGuardian(UUID guardianId) {
        return single("select " + COLUMNS + " from core.core_activity where guardian_id = :guardianId and " + IN_PROGRESS_SQL,
                uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")));
    }

    /** 소유 확인 읽기(BR6.9 · BR6.15 · BR8.5 · SQ-8): 남의 세션은 없는 것과 같다. */
    public Optional<ActivityCopy> findOwned(UUID sessionId, UUID guardianId) {
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "sessionId",
                Objects.requireNonNull(sessionId, "sessionId"));
        uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        return single("select " + COLUMNS
                + " from core.core_activity where session_id = :sessionId and guardian_id = :guardianId", params);
    }

    /** id로 사본을 읽는다(처리기의 0행 재읽기, BR5.10). */
    public Optional<ActivityCopy> findById(UUID sessionId) {
        return single("select " + COLUMNS + " from core.core_activity where session_id = :sessionId",
                uuid(new MapSqlParameterSource(), "sessionId", Objects.requireNonNull(sessionId, "sessionId")));
    }

    /**
     * SessionStateChanged를 사본에 반영한다(BR5.10 · BR6.14 · SQ-9). 조건은 {@code status_version < :v}이고 로컬 FAILED 사본은 바뀌지
     * 않는다. started_at은 처음 ACTIVE가 된 이벤트의 at, ended_at은 종료 상태 이벤트의 at, observed_at은 반영한 시각({@code now})이다.
     *
     * @return 바뀐 행 수(0이면 호출자가 {@link #findById}로 다시 읽어 로컬 FAILED 재취소 여부를 정한다)
     */
    public int applyStateChange(StateChange change, Instant now) {
        Objects.requireNonNull(change, "change");
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "sessionId", change.sessionId());
        text(params, "status", change.status().name());
        integer(params, "statusVersion", Math.toIntExact(change.statusVersion()));
        text(params, "endReason", wireOrNull(change.endReason()));
        text(params, "failureCode", wireOrNull(change.failureCode()));
        params.addValue("confirmed", change.confirmed());
        instant(params, "at", change.at());
        params.addValue("becomesActive", change.status() == SessionStatus.ACTIVE);
        params.addValue("becomesTerminal", change.status().isTerminal());
        return jdbc.update("""
                update core.core_activity
                   set status = :status, status_version = :statusVersion, end_reason = :endReason,
                       failure_code = :failureCode, confirmed = :confirmed,
                       started_at = case when :becomesActive and started_at is null then :at else started_at end,
                       ended_at = case when :becomesTerminal then :at else ended_at end,
                       observed_at = :now, updated_at = :now
                 where session_id = :sessionId and status_version < :statusVersion
                   and not (status = 'FAILED' and local_failed)
                """, params);
    }

    /**
     * 낡은 ACTIVE 이벤트라도 started_at이 비어 있으면 그 열만 채운다(BR6.14). status · status_version은 건드리지 않고 로컬 FAILED
     * 사본에는 쓰지 않는다.
     */
    public int fillStartedAtIfMissing(UUID sessionId, Instant at, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "sessionId", Objects.requireNonNull(sessionId, "sessionId"));
        instant(params, "at", Objects.requireNonNull(at, "at"));
        return jdbc.update("""
                update core.core_activity
                   set started_at = :at, updated_at = :now
                 where session_id = :sessionId and started_at is null and not local_failed
                """, params);
    }

    /**
     * 사본을 로컬 FAILED로 닫는다(end_reason FAILED · failure_code 비움 · local_failed · start_result_code · ended_at = now). 조건은
     * {@code status = :expectedStatus AND status_version = :expectedStatusVersion}이라 그 사이 Realtime 이벤트가 사본을 앞섰으면 0행이다.
     *
     * <ul>
     *   <li>BR6.6 · BR6.7 · BR6.12 ①: {@code (REQUESTED, 0)}, 코드 409 · 423 · 503 · 202.
     *   <li>BR6.1 ② 대조에서 Realtime이 모르는 세션: 대조한 상태 · 버전, 코드 202. status_version은 줄이지 않는다.
     * </ul>
     *
     * @return 바뀐 행 수
     */
    public int closeLocalFailed(
            UUID sessionId, SessionStatus expectedStatus, long expectedStatusVersion, int startResultCode, Instant now) {
        if (expectedStatus == null || !IN_PROGRESS.contains(expectedStatus)) {
            throw new IllegalArgumentException("로컬 FAILED는 진행 중 사본만 닫는다");
        }
        if (!LOCAL_FAILED_RESULT_CODES.contains(startResultCode)) {
            throw new IllegalArgumentException("start_result_code는 202 · 409 · 423 · 503 중 하나여야 한다");
        }
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "sessionId", Objects.requireNonNull(sessionId, "sessionId"));
        text(params, "expectedStatus", expectedStatus.name());
        integer(params, "expectedStatusVersion", Math.toIntExact(expectedStatusVersion));
        integer(params, "startResultCode", startResultCode);
        return jdbc.update("""
                update core.core_activity
                   set status = 'FAILED', end_reason = 'FAILED', failure_code = null, local_failed = true,
                       start_result_code = :startResultCode, ended_at = :now, updated_at = :now
                 where session_id = :sessionId and status = :expectedStatus and status_version = :expectedStatusVersion
                   and not local_failed
                """, params);
    }

    /**
     * PREPARING 시한이 지난 REQUESTED 사본(BR6.12 ① · {@code requested_at <= now - session.preparing-timeout}). 오래된 것부터
     * {@code limit}건이다. 닫기는 {@link #closeLocalFailed}로 한 건씩 조건부로 한다.
     */
    public List<ActivityCopy> findRequestedDueBefore(Instant cutoff, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit은 0보다 커야 한다");
        }
        MapSqlParameterSource params = instant(new MapSqlParameterSource(), "cutoff", Objects.requireNonNull(cutoff, "cutoff"));
        integer(params, "limit", limit);
        return jdbc.query("select " + COLUMNS + """
                 from core.core_activity
                where status = 'REQUESTED' and status_version = 0 and requested_at <= :cutoff
                order by requested_at, session_id
                limit :limit
                """, params, MAPPER);
    }

    // ------------------------------------------------------------------ 내부

    private Optional<ActivityCopy> single(String sql, MapSqlParameterSource params) {
        List<ActivityCopy> rows = jdbc.query(sql, params, MAPPER);
        if (rows.size() > 1) {
            throw new IllegalStateException("core_activity 단건 질의가 여러 행을 돌려줬다");
        }
        return rows.stream().findFirst();
    }

    private static ActivityCopy map(ResultSet rs, int rowNum) throws SQLException {
        return new ActivityCopy(
                ClockParams.uuid(rs, "session_id"),
                ClockParams.uuid(rs, "guardian_id"),
                ClockParams.uuid(rs, "child_id"),
                ClockParams.uuid(rs, "device_id"),
                ActivityType.fromWire(rs.getString("activity_type")),
                SessionStatus.fromWire(rs.getString("status")),
                rs.getInt("status_version"),
                rs.getString("end_reason"),
                rs.getString("failure_code"),
                rs.getBoolean("confirmed"),
                rs.getBoolean("local_failed"),
                nullableInt(rs, "start_result_code"),
                rs.getString("start_idempotency_key"),
                rs.getString("request_hash"),
                instant(rs, "requested_at"),
                instant(rs, "started_at"),
                instant(rs, "ended_at"),
                instant(rs, "observed_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static String wireOrNull(Enum<?> value) {
        return value == null || "UNRECOGNIZED".equals(value.name()) ? null : value.name();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 비어 있다");
        }
        return value;
    }

    // ------------------------------------------------------------------ 값 형식

    /**
     * 새 REQUESTED 사본(BR6.1 · J11 sessionId는 Core 발급).
     *
     * @param requestHash 시작 요청 본문의 SHA-256(BR6.2 IDEMPOTENCY_CONFLICT 판정)
     * @param requestedAt 요청 시각(Clock) — created_at · updated_at도 이 값
     */
    public record NewActivity(
            UUID sessionId,
            UUID guardianId,
            UUID childId,
            UUID deviceId,
            ActivityType activityType,
            String idempotencyKey,
            String requestHash,
            Instant requestedAt) {

        public NewActivity {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(guardianId, "guardianId");
            Objects.requireNonNull(childId, "childId");
            Objects.requireNonNull(deviceId, "deviceId");
            if (activityType == null || activityType == ActivityType.UNRECOGNIZED) {
                throw new IllegalArgumentException("activityType이 없거나 모르는 값이다");
            }
            requireText(idempotencyKey, "idempotencyKey");
            if (idempotencyKey.length() > 100) {
                throw new IllegalArgumentException("idempotencyKey는 100자 이하여야 한다");
            }
            requireText(requestHash, "requestHash");
            Objects.requireNonNull(requestedAt, "requestedAt");
        }
    }

    /**
     * 사본에 반영할 SessionStateChanged(BR5.10 · BR6.14). endReason · failureCode의 모르는 값은 비워 쓴다(계약 10절).
     *
     * @param statusVersion 1 이상(Realtime이 매기는 단조 값)
     */
    public record StateChange(
            UUID sessionId,
            SessionStatus status,
            long statusVersion,
            EndReason endReason,
            FailureCode failureCode,
            boolean confirmed,
            Instant at) {

        public StateChange {
            Objects.requireNonNull(sessionId, "sessionId");
            if (status == null || status == SessionStatus.UNRECOGNIZED || status == SessionStatus.REQUESTED) {
                throw new IllegalArgumentException("사본에 반영할 수 없는 status다");
            }
            if (statusVersion < 1) {
                throw new IllegalArgumentException("statusVersion은 1 이상이어야 한다");
            }
            Objects.requireNonNull(at, "at");
        }
    }

    /** {@code core_activity} 한 행. */
    public record ActivityCopy(
            UUID sessionId,
            UUID guardianId,
            UUID childId,
            UUID deviceId,
            ActivityType activityType,
            SessionStatus status,
            int statusVersion,
            String endReason,
            String failureCode,
            boolean confirmed,
            boolean localFailed,
            Integer startResultCode,
            String idempotencyKey,
            String requestHash,
            Instant requestedAt,
            Instant startedAt,
            Instant endedAt,
            Instant observedAt,
            Instant createdAt,
            Instant updatedAt) {

        /** 종료 아닌 상태인가(BR6.1 ②). */
        public boolean isInProgress() {
            return IN_PROGRESS.contains(status);
        }

        /** 멱등 키는 문자열 표현에 내지 않는다(요청 식별 값). */
        @Override
        public String toString() {
            return "ActivityCopy[sessionId=" + sessionId + ", status=" + status + ", statusVersion=" + statusVersion
                    + ", localFailed=" + localFailed + "]";
        }
    }
}
