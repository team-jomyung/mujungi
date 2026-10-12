package kr.mujoong.core.device;

import static kr.mujoong.core.common.db.ClockParams.instant;
import static kr.mujoong.core.common.db.ClockParams.integer;
import static kr.mujoong.core.common.db.ClockParams.nullableInt;
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
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.SqlErrors;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code core.core_device} 데이터 접근 (U1 3.5 Step 15 · DeviceRegistry · PD-8 SQ-1 ~ SQ-6 · SQ-18 · RD-22).
 *
 * <p>규약:
 *
 * <ul>
 *   <li>상태를 바꾸는 쓰기는 조건부 UPDATE 한 문장이고 호출자가 행 수로 승자를 가린다(BR2.3 · BR2.5 · BR4.1). 모든 UPDATE는
 *       {@code updated_at = :now, row_version = row_version + 1}을 쓴다. 시각은 호출자가 Clock에서 읽어 넘긴 {@code now}다(R2).
 *   <li>트랜잭션은 열지 않는다. 경계는 서비스 메서드 한 곳이다(RD-22). 격리 수준은 READ COMMITTED(J83)이다.
 *   <li>잠금은 규칙이 정한 곳만 쓴다: 보호자 FOR SHARE(BR2.2) → 코드 행 FOR UPDATE → 옛 기기 FOR UPDATE(BR2.5) 순서이고,
 *       claim은 bootstrap 행을 FOR UPDATE로 잠근다(BR3.1).
 *   <li>질의 술어는 부분 유일 색인의 술어({@code registration_status = 'REGISTERED' · 'UNREGISTERED'})를 그대로 담는다(PD-8).
 *   <li>해시 · 등록 코드는 로그 · 문자열 표현에 내지 않는다(BR8.7 · BR5.13).
 * </ul>
 */
@Repository
public class DeviceJdbcRepository {

    /** 보호자당 REGISTERED 하나의 부분 유일 색인(V1). */
    public static final String ACTIVE_GUARDIAN_INDEX = "core_device_active_guardian_uk";
    /** 발급 중 코드 유일의 부분 유일 색인(V1). */
    public static final String PENDING_CODE_INDEX = "core_device_pending_code_uk";

    private static final String COLUMNS = """
            id, guardian_id, registration_code, code_issued_at, code_expires_at,
            (device_token_hash is not null) as token_issued, token_issued_at, first_connected_at,
            registration_status, registered_at, released_at, release_reason,
            reported_connection_state, reported_at, last_seen_at, connection_since, rtt_ms, recent_disconnect_count,
            created_at, updated_at, row_version
            """;

    private static final RowMapper<DeviceRecord> DEVICE_MAPPER = DeviceJdbcRepository::mapDevice;

    private final NamedParameterJdbcTemplate jdbc;

    public DeviceJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    // ------------------------------------------------------------------ 코드 발급 · 상태 · 만료 (BR1.2 ~ BR1.4 · BR1.7)

    /**
     * 새 UNREGISTERED 행을 넣는다(SQ-2). 발급 중 코드가 겹치면(부분 유일 색인) 오류 대신 {@code false}를 돌려줘 같은 트랜잭션에서
     * 새로 뽑게 한다(BR1.2 · {@code ON CONFLICT DO NOTHING}이라 트랜잭션이 깨지지 않는다).
     *
     * @return 넣었으면 true, 유일 충돌이면 false
     */
    public boolean insertPending(PendingDevice device) {
        Objects.requireNonNull(device, "device");
        MapSqlParameterSource params = ClockParams.at(device.issuedAt());
        uuid(params, "id", device.id());
        text(params, "code", device.registrationCode());
        instant(params, "expiresAt", device.codeExpiresAt());
        text(params, "bootstrapHash", device.bootstrapSecretHash());
        int inserted = jdbc.update("""
                insert into core.core_device (id, registration_code, code_issued_at, code_expires_at, bootstrap_secret_hash,
                    registration_status, recent_disconnect_count, created_at, updated_at, row_version)
                values (:id, :code, :now, :expiresAt, :bootstrapHash, 'UNREGISTERED', 0, :now, :now, 0)
                on conflict do nothing
                """, params);
        return inserted == 1;
    }

    /** bootstrap 비밀 해시로 행을 읽는다(SQ-1, 잠금 없음). */
    public Optional<DeviceRecord> findByBootstrapHash(String bootstrapSecretHash) {
        return single("select " + COLUMNS + " from core.core_device where bootstrap_secret_hash = :hash",
                text(new MapSqlParameterSource(), "hash", requireText(bootstrapSecretHash, "bootstrapSecretHash")));
    }

    /** bootstrap 비밀 해시로 행을 FOR UPDATE로 잠그고 읽는다(BR3.1 claim). */
    public Optional<DeviceRecord> lockByBootstrapHash(String bootstrapSecretHash) {
        return single("select " + COLUMNS + " from core.core_device where bootstrap_secret_hash = :hash for update",
                text(new MapSqlParameterSource(), "hash", requireText(bootstrapSecretHash, "bootstrapSecretHash")));
    }

    /** id로 행을 읽는다. */
    public Optional<DeviceRecord> findById(UUID deviceId) {
        return single("select " + COLUMNS + " from core.core_device where id = :id",
                uuid(new MapSqlParameterSource(), "id", Objects.requireNonNull(deviceId, "deviceId")));
    }

    /**
     * 만료된 UNREGISTERED 행 하나를 EXPIRED로 바꾼다(BR1.4, 조회가 만료를 발견했을 때). 유효 기간은
     * {@code code_expires_at > now}(BR2.3)이고 그 밖은 만료다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    public int markExpiredIfDue(UUID deviceId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "id", Objects.requireNonNull(deviceId, "deviceId"));
        return jdbc.update("""
                update core.core_device
                   set registration_status = 'EXPIRED', updated_at = :now, row_version = row_version + 1
                 where id = :id and registration_status = 'UNREGISTERED' and code_expires_at <= :now
                """, params);
    }

    /** 정리 작업: 만료된 UNREGISTERED 행을 {@code batchSize}건까지 EXPIRED로 바꾼다(BR1.4 · SQ-18). */
    public int expireDueCodes(Instant now, int batchSize) {
        MapSqlParameterSource params = ClockParams.at(now);
        integer(params, "limit", positive(batchSize, "batchSize"));
        return jdbc.update("""
                update core.core_device
                   set registration_status = 'EXPIRED', updated_at = :now, row_version = row_version + 1
                 where id in (select id from core.core_device
                               where registration_status = 'UNREGISTERED' and code_expires_at <= :now
                               order by code_expires_at, id
                               limit :limit)
                   and registration_status = 'UNREGISTERED'
                """, params);
    }

    /**
     * 정리 작업: 만료 뒤 {@code retention}(7일)이 지난 EXPIRED 행을 {@code batchSize}건까지 지운다(BR1.4 · SQ-18). EXPIRED 행은 보호자가
     * 없어(V1 검사) 활동 행이 가리키지 않는다.
     */
    public int deleteExpired(Instant now, Duration retention, int batchSize) {
        Objects.requireNonNull(now, "now");
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention은 0보다 커야 한다");
        }
        MapSqlParameterSource params = instant(new MapSqlParameterSource(), "cutoff", now.minus(retention));
        integer(params, "limit", positive(batchSize, "batchSize"));
        return jdbc.update("""
                delete from core.core_device
                 where id in (select id from core.core_device
                               where registration_status = 'EXPIRED' and code_expires_at <= :cutoff
                               order by code_expires_at, id
                               limit :limit)
                   and registration_status = 'EXPIRED'
                """, params);
    }

    // ------------------------------------------------------------------ 보호자 등록 (BR2.1 ~ BR2.5)

    /**
     * 등록 트랜잭션의 첫 잠금: 보호자 행을 FOR SHARE로 읽어 상태를 돌려준다(BR2.2). 삭제 접수는 같은 행을 갱신하므로 접수와 등록
     * 커밋이 겹치지 않는다.
     */
    public Optional<String> lockGuardianStatusForShare(UUID guardianId) {
        // 보호자 행 잠금 SQL은 한 곳(GuardianWriteLock)에 있다. 등록 트랜잭션의 첫 잠금이라는 순서는 여기서 지킨다(U2 2차 리뷰 5)
        return GuardianWriteLock.statusForShare(jdbc, guardianId);
    }

    /** 코드의 등록 가능한 행(UNREGISTERED · 만료 전, 최대 1행)을 FOR UPDATE로 잠그고 읽는다(BR2.1 ① · BR2.5 (1) · SQ-3). */
    public Optional<DeviceRecord> lockPendingByCode(String registrationCode, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        text(params, "code", requireText(registrationCode, "registrationCode"));
        return single("select " + COLUMNS + """
                 from core.core_device
                where registration_code = :code and registration_status = 'UNREGISTERED' and code_expires_at > :now
                for update
                """, params);
    }

    /**
     * 같은 코드의 REGISTERED · RELEASED 행(BR2.1 ②). 만료 뒤 같은 코드가 다시 발급될 수 있어 여러 행일 수 있다. REGISTERED가 먼저,
     * 그다음 최근 등록 순이다.
     */
    public List<DeviceRecord> findBoundByCode(String registrationCode) {
        MapSqlParameterSource params = text(new MapSqlParameterSource(), "code",
                requireText(registrationCode, "registrationCode"));
        return jdbc.query("select " + COLUMNS + """
                 from core.core_device
                where registration_code = :code and registration_status in ('REGISTERED', 'RELEASED')
                order by (registration_status = 'REGISTERED') desc, registered_at desc nulls last, id
                """, params, DEVICE_MAPPER);
    }

    /** 보호자의 활성(REGISTERED) 기기(SQ-6, 잠금 없음 — 04 · 05 · 시작 검사 읽기). */
    public Optional<DeviceRecord> findActiveByGuardian(UUID guardianId) {
        return single("select " + COLUMNS
                        + " from core.core_device where guardian_id = :guardianId and registration_status = 'REGISTERED'",
                uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")));
    }

    /** 보호자의 활성 기기를 FOR UPDATE로 잠그고 읽는다(BR2.5 (2) · BR2.10 (1)). 락 순서상 코드 행 다음이다. */
    public Optional<DeviceRecord> lockActiveByGuardian(UUID guardianId) {
        return single("select " + COLUMNS + " from core.core_device"
                        + " where guardian_id = :guardianId and registration_status = 'REGISTERED' for update",
                uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")));
    }

    /**
     * REGISTERED 행을 RELEASED로 바꾸고 토큰 해시를 비운다(BR2.5 (3) · BR2.10 (1) · BR3.3). 같은 트랜잭션에서 호출자가 DeviceReleased
     * Outbox를 쓴다. 보호자당 REGISTERED 하나의 색인은 지연할 수 없으므로 교체 등록에서는 이 문장이 {@link #register}보다 먼저다.
     *
     * @return 바뀐 행 수(1이어야 성공)
     */
    public int releaseRegistered(UUID deviceId, DeviceReleased.Reason reason, Instant now) {
        if (reason == null || reason == DeviceReleased.Reason.UNRECOGNIZED) {
            throw new IllegalArgumentException("해제 사유는 REREGISTERED 또는 ACCOUNT_DELETED여야 한다");
        }
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "id", Objects.requireNonNull(deviceId, "deviceId"));
        text(params, "reason", reason.name());
        return jdbc.update("""
                update core.core_device
                   set registration_status = 'RELEASED', release_reason = :reason, released_at = :now,
                       device_token_hash = null, updated_at = :now, row_version = row_version + 1
                 where id = :id and registration_status = 'REGISTERED'
                """, params);
    }

    /**
     * 코드 행을 보호자의 REGISTERED 기기로 바꾼다(BR2.3, 조건: UNREGISTERED · 만료 전). 동시에 두 계정이 같은 코드를 보내면 한 문장만 1행이다.
     * 보호자에게 이미 REGISTERED 기기가 있으면(부분 유일 색인 23505) {@link ActiveDeviceConflictException}이고 트랜잭션은 되돌려야
     * 한다(BR2.4: 409 DEVICE_ALREADY_ACTIVE).
     *
     * @return 바뀐 행 수(1이어야 성공, 0이면 호출자가 행을 다시 읽어 BR2.1 ② ③으로 답한다)
     */
    public int register(UUID deviceId, UUID guardianId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "id", Objects.requireNonNull(deviceId, "deviceId"));
        uuid(params, "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
        try {
            return jdbc.update("""
                    update core.core_device
                       set guardian_id = :guardianId, registration_status = 'REGISTERED', registered_at = :now,
                           updated_at = :now, row_version = row_version + 1
                     where id = :id and registration_status = 'UNREGISTERED' and code_expires_at > :now
                    """, params);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e, ACTIVE_GUARDIAN_INDEX)) {
                throw new ActiveDeviceConflictException(e);
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ 기기 토큰 (BR3.1 ~ BR3.5)

    /**
     * 첫 연결 전인 REGISTERED 행에 새 토큰 해시를 쓴다(BR3.1 · BR3.3 회전: 앞 해시는 덮어써 무효).
     *
     * @return 바뀐 행 수(0이면 claim이 닫혔거나 등록 상태가 아니다)
     */
    public int rotateToken(UUID deviceId, String deviceTokenHash, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "id", Objects.requireNonNull(deviceId, "deviceId"));
        text(params, "tokenHash", requireText(deviceTokenHash, "deviceTokenHash"));
        return jdbc.update("""
                update core.core_device
                   set device_token_hash = :tokenHash, token_issued_at = :now, updated_at = :now, row_version = row_version + 1
                 where id = :id and registration_status = 'REGISTERED' and first_connected_at is null
                """, params);
    }

    /**
     * 기기 토큰 해시가 REGISTERED 행과 맞으면 기기 · 보호자 · 아이를 한 질의로 돌려준다(BR3.4 · BR3.5 · SQ-4). 결과는 캐시하지 않는다
     * (편차 (j)).
     */
    public Optional<TokenOwner> findTokenOwner(String deviceTokenHash) {
        MapSqlParameterSource params = text(new MapSqlParameterSource(), "tokenHash",
                requireText(deviceTokenHash, "deviceTokenHash"));
        List<TokenOwner> rows = jdbc.query("""
                select d.id, d.guardian_id, c.id as child_id, d.registered_at, d.first_connected_at
                  from core.core_device d
                  left join core.core_child c on c.guardian_id = d.guardian_id
                 where d.device_token_hash = :tokenHash and d.registration_status = 'REGISTERED'
                """, params, (rs, rowNum) -> new TokenOwner(
                        ClockParams.uuid(rs, "id"),
                        ClockParams.uuid(rs, "guardian_id"),
                        ClockParams.uuid(rs, "child_id"),
                        instant(rs, "registered_at"),
                        instant(rs, "first_connected_at")));
        return rows.stream().findFirst();
    }

    /** 첫 WSS 인증 시각을 비어 있을 때만 채운다(BR3.2 · BR3.4). 값이 생기면 claim이 닫힌다. */
    public int markFirstConnected(UUID deviceId, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "id", Objects.requireNonNull(deviceId, "deviceId"));
        return jdbc.update("""
                update core.core_device
                   set first_connected_at = :now, updated_at = :now, row_version = row_version + 1
                 where id = :id and registration_status = 'REGISTERED' and first_connected_at is null
                """, params);
    }

    // ------------------------------------------------------------------ 연결 상태 사본 (BR4.1 · BR4.2)

    /**
     * Realtime 보고를 사본에 반영한다(BR4.1 · BR4.2 · SQ-5). 보고 시각 {@code report.reportedAt()}이 사본보다 늦을 때만, REGISTERED
     * 행에만 쓴다. 읽고 비교하지 않는 한 문장이라 두 경로(HTTP 보고 · DeviceStatusChanged 처리기)가 겹쳐도 reported_at이 줄지 않는다.
     * rtt · 끊김 수 · 마지막 수신이 없으면 기존 값을 둔다(COALESCE).
     *
     * @return 바뀐 행 수(0이면 늦은 · 같은 시각 보고이거나 등록 상태가 아니다 — 버린다)
     */
    public int applyConnectionReport(UUID deviceId, ConnectionReport report, Instant now) {
        Objects.requireNonNull(report, "report");
        MapSqlParameterSource params = ClockParams.at(now);
        uuid(params, "id", Objects.requireNonNull(deviceId, "deviceId"));
        text(params, "state", report.state().name());
        instant(params, "reportedAt", report.reportedAt());
        integer(params, "rttMs", report.rttMs());
        integer(params, "recentDisconnects", report.recentDisconnects());
        instant(params, "since", report.since());
        instant(params, "lastSeenAt", report.lastSeenAt());
        return jdbc.update("""
                update core.core_device
                   set reported_connection_state = :state,
                       rtt_ms = coalesce(:rttMs, rtt_ms),
                       recent_disconnect_count = coalesce(:recentDisconnects, recent_disconnect_count),
                       connection_since = :since,
                       last_seen_at = coalesce(:lastSeenAt, last_seen_at),
                       reported_at = :reportedAt,
                       updated_at = :now, row_version = row_version + 1
                 where id = :id and registration_status = 'REGISTERED'
                   and (reported_at is null or reported_at < :reportedAt)
                """, params);
    }

    /** 기기 행의 보호자(A4 · RD-11: payload에 guardianId가 없는 옛 DeviceStatusChanged의 대체 출처). */
    public Optional<UUID> findGuardianId(UUID deviceId) {
        MapSqlParameterSource params = uuid(new MapSqlParameterSource(), "id", Objects.requireNonNull(deviceId, "deviceId"));
        List<UUID> rows = jdbc.query("select guardian_id from core.core_device where id = :id and guardian_id is not null",
                params, (rs, rowNum) -> ClockParams.uuid(rs, "guardian_id"));
        return rows.stream().findFirst();
    }

    // ------------------------------------------------------------------ 계정 삭제 참가자 (BR2.10 · J58)

    /**
     * 보호자의 기기 행을 상태와 상관없이 모두 지운다(BR2.10 (2), 재등록으로 RELEASED가 된 옛 행 포함). 호출자는 먼저 REGISTERED 행을
     * {@link #releaseRegistered}로 해제하고 DeviceReleased를 쓴다. 활동 행이 남아 있으면 외래 키(RESTRICT) 위반 23503으로 실패한다
     * (삭제 지휘가 순서를 고쳐 다시 부른다, 멱등).
     *
     * @return 지운 행 수
     */
    public int deleteAllByGuardian(UUID guardianId) {
        return jdbc.update("delete from core.core_device where guardian_id = :guardianId",
                uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId")));
    }

    // ------------------------------------------------------------------ 내부

    private Optional<DeviceRecord> single(String sql, MapSqlParameterSource params) {
        List<DeviceRecord> rows = jdbc.query(sql, params, DEVICE_MAPPER);
        if (rows.size() > 1) {
            throw new IllegalStateException("core_device 단건 질의가 여러 행을 돌려줬다");
        }
        return rows.stream().findFirst();
    }

    private static DeviceRecord mapDevice(ResultSet rs, int rowNum) throws SQLException {
        String reportedState = rs.getString("reported_connection_state");
        return new DeviceRecord(
                ClockParams.uuid(rs, "id"),
                ClockParams.uuid(rs, "guardian_id"),
                rs.getString("registration_code"),
                instant(rs, "code_issued_at"),
                instant(rs, "code_expires_at"),
                rs.getBoolean("token_issued"),
                instant(rs, "token_issued_at"),
                instant(rs, "first_connected_at"),
                RegistrationStatus.fromWire(rs.getString("registration_status")),
                instant(rs, "registered_at"),
                instant(rs, "released_at"),
                rs.getString("release_reason"),
                reportedState == null ? null : ConnectionState.fromWire(reportedState),
                instant(rs, "reported_at"),
                instant(rs, "last_seen_at"),
                instant(rs, "connection_since"),
                nullableInt(rs, "rtt_ms"),
                rs.getInt("recent_disconnect_count"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getLong("row_version"));
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

    // ------------------------------------------------------------------ 값 형식

    /**
     * 새 UNREGISTERED 행(BR1.2 · BR1.5). 비밀 원문은 없고 SHA-256 해시만 있다.
     *
     * @param issuedAt 발급 시각(Clock) — created_at · updated_at도 이 값
     * @param codeExpiresAt {@code issuedAt + bootstrap.code-ttl}
     */
    public record PendingDevice(
            UUID id, String registrationCode, String bootstrapSecretHash, Instant issuedAt, Instant codeExpiresAt) {

        public PendingDevice {
            Objects.requireNonNull(id, "id");
            requireText(registrationCode, "registrationCode");
            requireText(bootstrapSecretHash, "bootstrapSecretHash");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(codeExpiresAt, "codeExpiresAt");
            if (!codeExpiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("codeExpiresAt은 issuedAt보다 뒤여야 한다");
            }
        }

        /** 코드 · 해시는 문자열 표현에 내지 않는다(BR8.7). */
        @Override
        public String toString() {
            return "PendingDevice[id=" + id + ", issuedAt=" + issuedAt + ", codeExpiresAt=" + codeExpiresAt + "]";
        }
    }

    /**
     * {@code core_device} 한 행(해시 원문은 담지 않고 토큰 발급 여부만 담는다).
     *
     * @param reportedConnectionState 사본이 없으면 null(BR4.3 ②)
     */
    public record DeviceRecord(
            UUID id,
            UUID guardianId,
            String registrationCode,
            Instant codeIssuedAt,
            Instant codeExpiresAt,
            boolean tokenIssued,
            Instant tokenIssuedAt,
            Instant firstConnectedAt,
            RegistrationStatus registrationStatus,
            Instant registeredAt,
            Instant releasedAt,
            String releaseReason,
            ConnectionState reportedConnectionState,
            Instant reportedAt,
            Instant lastSeenAt,
            Instant connectionSince,
            Integer rttMs,
            int recentDisconnectCount,
            Instant createdAt,
            Instant updatedAt,
            long rowVersion) {

        /** 코드가 {@code now}에 아직 유효한가(BR2.3: {@code code_expires_at > now}). */
        public boolean isCodeValidAt(Instant now) {
            return codeExpiresAt.isAfter(Objects.requireNonNull(now, "now"));
        }

        /** 05 · 등록 응답의 '코드 앞 4자리'(BR2.7). 로그에는 쓰지 않는다. */
        public String codePrefix() {
            return registrationCode.substring(0, Math.min(4, registrationCode.length()));
        }

        /** 사본이 한 번이라도 있었는가(BR4.3 ②). */
        public boolean hasConnectionCopy() {
            return reportedConnectionState != null && reportedAt != null;
        }

        /** 등록 코드는 앞 4자도 문자열 표현에 내지 않는다(BR8.7보다 엄격, 3.3 추가 동작). */
        @Override
        public String toString() {
            return "DeviceRecord[id=" + id + ", registrationStatus=" + registrationStatus
                    + ", reportedConnectionState=" + reportedConnectionState + ", rowVersion=" + rowVersion + "]";
        }
    }

    /**
     * Realtime이 보고한 연결 상태(BR4.1 · 편차 (i)). 상태와 보고 시각은 필수이고 나머지는 없으면 기존 사본 값을 둔다.
     *
     * @param reportedAt HTTP 보고는 reportedAt, 이벤트 경로는 Inbox 행의 occurred_at(같은 Realtime 시계)
     */
    public record ConnectionReport(
            ConnectionState state,
            Instant reportedAt,
            Integer rttMs,
            Integer recentDisconnects,
            Instant since,
            Instant lastSeenAt) {

        public ConnectionReport {
            if (state == null || state == ConnectionState.UNRECOGNIZED) {
                throw new IllegalArgumentException("보고 state가 없거나 모르는 값이다");
            }
            Objects.requireNonNull(reportedAt, "reportedAt");
            if (rttMs != null && rttMs < 0) {
                throw new IllegalArgumentException("rttMs는 0 이상이어야 한다");
            }
            if (recentDisconnects != null && recentDisconnects < 0) {
                throw new IllegalArgumentException("recentDisconnects는 0 이상이어야 한다");
            }
        }
    }

    /**
     * 토큰 확인 결과(BR3.4 · BR3.5).
     *
     * @param childId 보호자의 아이(없으면 null — U1 시드에는 늘 있다)
     */
    public record TokenOwner(UUID deviceId, UUID guardianId, UUID childId, Instant registeredAt, Instant firstConnectedAt) {

        /** 첫 연결 전인가(claim이 아직 열려 있다). */
        public boolean isBeforeFirstConnection() {
            return firstConnectedAt == null;
        }
    }

    /** 보호자에게 이미 REGISTERED 기기가 있다(부분 유일 색인 위반, BR2.4 → 409 DEVICE_ALREADY_ACTIVE). */
    public static final class ActiveDeviceConflictException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ActiveDeviceConflictException(Throwable cause) {
            super("보호자에게 이미 활성 기기가 있다(" + ACTIVE_GUARDIAN_INDEX + ")", cause);
        }
    }
}
