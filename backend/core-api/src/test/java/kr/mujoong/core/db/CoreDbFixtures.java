package kr.mujoong.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kr.mujoong.testsupport.db.MujoongPostgres.MigrationSet;
import org.flywaydb.core.api.MigrationVersion;

/**
 * Step 14 core DB 시험의 공통 픽스처(시험 소스 전용). 행은 시험마다 새 UUID · 새 코드로 만들고, 호출한 시험이 트랜잭션을 되돌려
 * 공용 인스턴스(MujoongPostgres.shared)에 남기지 않는다. 시각은 고정 값을 바인딩한다(DB 시각 함수 없음, R2).
 */
final class CoreDbFixtures {

    /** 시험 고정 시각. */
    static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    private static final SecureRandom RANDOM = new SecureRandom();
    /** BR1.1 코드 알파벳(31자). */
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private CoreDbFixtures() {
    }

    @FunctionalInterface
    interface SqlAction {
        void run() throws SQLException;
    }

    /** 한 문장을 저장점 안에서 실행해 정확한 SQLSTATE로 실패하는지 보고, 트랜잭션은 저장점으로 되돌려 계속 쓴다. */
    static void expectSqlState(Connection connection, String sqlState, SqlAction action) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            action.run();
            fail("SQLSTATE " + sqlState + "를 기대했지만 문장이 성공했다");
        } catch (SQLException e) {
            assertThat(e.getSQLState()).as("SQLSTATE").isEqualTo(sqlState);
        } finally {
            connection.rollback(savepoint);
        }
    }

    /** 자동 커밋 연결에서 문장이 정확한 SQLSTATE로 실패하는지 본다. */
    static void expectSqlStateAutoCommit(String sqlState, SqlAction action) {
        try {
            action.run();
            fail("SQLSTATE " + sqlState + "를 기대했지만 문장이 성공했다");
        } catch (SQLException e) {
            assertThat(e.getSQLState()).as("SQLSTATE").isEqualTo(sqlState);
        }
    }

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** BR1.1 알파벳의 무작위 8자 코드. */
    static String randomCode() {
        char[] code = new char[8];
        for (int i = 0; i < code.length; i++) {
            code[i] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
        }
        return new String(code);
    }

    /** SHA-256 16진수 모양의 무작위 64자. */
    static String randomHash() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 32자 소문자 16진수 traceId 모양. */
    static String randomTraceId() {
        return randomHash().substring(0, 32);
    }

    static int update(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    static List<List<Object>> query(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columns);
                    for (int i = 1; i <= columns; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    static Object single(Connection connection, String sql, Object... params) throws SQLException {
        List<List<Object>> rows = query(connection, sql, params);
        assertThat(rows).as("한 행 한 열: " + sql).hasSize(1);
        return rows.get(0).get(0);
    }

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    /**
     * 저장소 마이그레이션 위치의 버전(V*__*.sql) 목록을 버전 순서로 돌려준다. 시험이 마이그레이션 개수 · 최고 버전을 숫자로 적지 않고
     * 파일에서 읽게 해, 마이그레이션을 더해도 시험을 고치지 않게 한다.
     */
    static List<String> migrationVersions(Path repoRoot, MigrationSet set) throws IOException {
        try (Stream<Path> files = Files.list(repoRoot.resolve(set.relativeLocation()))) {
            List<String> versions = files.map(file -> VERSIONED_MIGRATION.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group(1).replace('_', '.'))
                    .sorted(Comparator.comparing(MigrationVersion::fromVersion))
                    .toList();
            assertThat(versions).as("마이그레이션 파일: " + set.relativeLocation()).isNotEmpty();
            return versions;
        }
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value instanceof Instant instant) {
                statement.setObject(i + 1, ts(instant));
            } else if (value instanceof NullOf nullOf) {
                statement.setNull(i + 1, nullOf.sqlType());
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    /** 형이 있는 NULL 인자. */
    record NullOf(int sqlType) {
        static final NullOf UUID = new NullOf(Types.OTHER);
        static final NullOf TIMESTAMP = new NullOf(Types.TIMESTAMP_WITH_TIMEZONE);
        static final NullOf TEXT = new NullOf(Types.VARCHAR);
        static final NullOf INTEGER = new NullOf(Types.INTEGER);
    }

    // ------------------------------------------------------------------ 행 만들기

    static UUID insertGuardian(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        update(connection,
                "insert into core.core_guardian (id, status, created_at, updated_at) values (?, 'ACTIVE', ?, ?)",
                id, T0, T0);
        return id;
    }

    static UUID insertChild(Connection connection, UUID guardianId) throws SQLException {
        UUID id = UUID.randomUUID();
        update(connection,
                "insert into core.core_child (id, guardian_id, display_name, age, interests_json, status, created_at, updated_at)"
                        + " values (?, ?, '시험아이', 8, '[\"공룡\"]'::jsonb, 'ACTIVE', ?, ?)",
                id, guardianId, T0, T0);
        return id;
    }

    /** core_device 한 행의 값(검사 · 색인 시험이 고쳐 넣는다). */
    record DeviceRow(
            UUID id,
            UUID guardianId,
            String code,
            String status,
            String bootstrapHash,
            String tokenHash,
            Instant registeredAt,
            Instant releasedAt,
            String releaseReason) {

        static DeviceRow unregistered() {
            return new DeviceRow(UUID.randomUUID(), null, randomCode(), "UNREGISTERED", randomHash(), null, null, null, null);
        }

        static DeviceRow registered(UUID guardianId) {
            return new DeviceRow(UUID.randomUUID(), guardianId, randomCode(), "REGISTERED", randomHash(), randomHash(), T0,
                    null, null);
        }

        static DeviceRow released(UUID guardianId) {
            return new DeviceRow(UUID.randomUUID(), guardianId, randomCode(), "RELEASED", randomHash(), null, T0, T0,
                    "REREGISTERED");
        }

        DeviceRow withCode(String value) {
            return new DeviceRow(id, guardianId, value, status, bootstrapHash, tokenHash, registeredAt, releasedAt,
                    releaseReason);
        }

        DeviceRow withStatus(String value) {
            return new DeviceRow(id, guardianId, code, value, bootstrapHash, tokenHash, registeredAt, releasedAt,
                    releaseReason);
        }

        DeviceRow withGuardian(UUID value) {
            return new DeviceRow(id, value, code, status, bootstrapHash, tokenHash, registeredAt, releasedAt, releaseReason);
        }

        DeviceRow withBootstrapHash(String value) {
            return new DeviceRow(id, guardianId, code, status, value, tokenHash, registeredAt, releasedAt, releaseReason);
        }

        DeviceRow withTokenHash(String value) {
            return new DeviceRow(id, guardianId, code, status, bootstrapHash, value, registeredAt, releasedAt,
                    releaseReason);
        }

        DeviceRow withRegisteredAt(Instant value) {
            return new DeviceRow(id, guardianId, code, status, bootstrapHash, tokenHash, value, releasedAt, releaseReason);
        }

        DeviceRow withRelease(Instant at, String reason) {
            return new DeviceRow(id, guardianId, code, status, bootstrapHash, tokenHash, registeredAt, at, reason);
        }
    }

    static void insertDevice(Connection connection, DeviceRow row) throws SQLException {
        update(connection,
                "insert into core.core_device (id, guardian_id, registration_code, code_issued_at, code_expires_at,"
                        + " bootstrap_secret_hash, device_token_hash, registration_status, registered_at, released_at,"
                        + " release_reason, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(),
                row.guardianId() == null ? NullOf.UUID : row.guardianId(),
                row.code(),
                T0,
                T0.plusSeconds(86_400),
                row.bootstrapHash(),
                row.tokenHash() == null ? NullOf.TEXT : row.tokenHash(),
                row.status(),
                row.registeredAt() == null ? NullOf.TIMESTAMP : row.registeredAt(),
                row.releasedAt() == null ? NullOf.TIMESTAMP : row.releasedAt(),
                row.releaseReason() == null ? NullOf.TEXT : row.releaseReason(),
                T0,
                T0);
    }

    /** core_activity 한 행의 값. */
    record ActivityRow(
            UUID sessionId,
            UUID guardianId,
            UUID childId,
            UUID deviceId,
            String status,
            String idempotencyKey,
            boolean localFailed,
            Integer startResultCode,
            String failureCode) {

        static ActivityRow requested(UUID guardianId, UUID childId, UUID deviceId) {
            return new ActivityRow(UUID.randomUUID(), guardianId, childId, deviceId, "REQUESTED",
                    UUID.randomUUID().toString(), false, null, null);
        }

        ActivityRow withStatus(String value) {
            return new ActivityRow(sessionId, guardianId, childId, deviceId, value, idempotencyKey, localFailed,
                    startResultCode, failureCode);
        }

        ActivityRow withKey(String value) {
            return new ActivityRow(sessionId, guardianId, childId, deviceId, status, value, localFailed, startResultCode,
                    failureCode);
        }

        ActivityRow withLocalFailed(boolean value, Integer resultCode) {
            return new ActivityRow(sessionId, guardianId, childId, deviceId, status, idempotencyKey, value, resultCode,
                    failureCode);
        }

        ActivityRow withFailureCode(String value) {
            return new ActivityRow(sessionId, guardianId, childId, deviceId, status, idempotencyKey, localFailed,
                    startResultCode, value);
        }

        ActivityRow withNewSessionId() {
            return new ActivityRow(UUID.randomUUID(), guardianId, childId, deviceId, status, idempotencyKey, localFailed,
                    startResultCode, failureCode);
        }
    }

    static void insertActivity(Connection connection, ActivityRow row) throws SQLException {
        update(connection,
                "insert into core.core_activity (session_id, guardian_id, child_id, device_id, activity_type, status,"
                        + " status_version, failure_code, local_failed, start_result_code, start_idempotency_key,"
                        + " request_hash, requested_at, created_at, updated_at)"
                        + " values (?, ?, ?, ?, 'FREE_TALK', ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.sessionId(),
                row.guardianId(),
                row.childId(),
                row.deviceId(),
                row.status(),
                row.failureCode() == null ? NullOf.TEXT : row.failureCode(),
                row.localFailed(),
                row.startResultCode() == null ? NullOf.INTEGER : row.startResultCode(),
                row.idempotencyKey(),
                randomHash(),
                T0,
                T0,
                T0);
    }

    /** 보호자 · 아이 · 등록 기기 한 벌(활동 시험의 전제). */
    record Household(UUID guardianId, UUID childId, UUID deviceId) {
    }

    static Household insertHousehold(Connection connection) throws SQLException {
        UUID guardianId = insertGuardian(connection);
        UUID childId = insertChild(connection, guardianId);
        DeviceRow device = DeviceRow.registered(guardianId);
        insertDevice(connection, device);
        return new Household(guardianId, childId, device.id());
    }

    static void insertOutbox(Connection connection, UUID eventId, String status, String payloadJson, Instant purgedAt,
            String traceId) throws SQLException {
        update(connection,
                "insert into core.core_outbox_event (event_id, event_type, schema_version, occurred_at, aggregate_type,"
                        + " aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count, available_at,"
                        + " purged_at, created_at, trace_id)"
                        + " values (?, 'DeviceReleased', 1, ?, 'Device', ?, ?, ?::jsonb, ?, ?, 0, ?, ?, ?, ?)",
                eventId,
                T0,
                UUID.randomUUID(),
                UUID.randomUUID(),
                payloadJson,
                randomHash(),
                status,
                T0,
                purgedAt == null ? NullOf.TIMESTAMP : purgedAt,
                T0,
                traceId == null ? NullOf.TEXT : traceId);
    }

    static void insertInbox(Connection connection, UUID eventId, String status, Instant nextAttemptAt, String traceId)
            throws SQLException {
        update(connection,
                "insert into core.core_inbox_event (event_id, source_service, event_type, schema_version, occurred_at,"
                        + " aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count, next_attempt_at,"
                        + " received_at, trace_id)"
                        + " values (?, 'REALTIME', 'DeviceStatusChanged', 1, ?, ?, ?, '{\"state\":\"CONNECTED\"}'::jsonb, ?, ?,"
                        + " 0, ?, ?, ?)",
                eventId,
                T0,
                UUID.randomUUID(),
                UUID.randomUUID(),
                randomHash(),
                status,
                nextAttemptAt == null ? NullOf.TIMESTAMP : nextAttemptAt,
                T0,
                traceId == null ? NullOf.TEXT : traceId);
    }
}
