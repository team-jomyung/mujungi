package kr.mujoong.realtime.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Step 14 realtime DB 시험의 공통 픽스처(시험 소스 전용). 시험마다 새 UUID로 행을 만들고 호출한 시험이 트랜잭션을 되돌린다.
 * 시각은 고정 값을 바인딩한다(DB 시각 함수 없음, R2).
 */
final class RealtimeDbFixtures {

    /** 시험 고정 시각. */
    static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    /** 정상 동의 스냅샷(다섯 값, 정책 '1.0', 음성 · 텍스트 동의 true). */
    static final String VALID_SNAPSHOT =
            "{\"policyVersion\":\"1.0\",\"voiceAndText\":true,\"guardianView\":true,\"reportSharing\":false,\"rawAudio\":false}";

    private static final SecureRandom RANDOM = new SecureRandom();

    private RealtimeDbFixtures() {
    }

    @FunctionalInterface
    interface SqlAction {
        void run() throws SQLException;
    }

    /** 저장점 안에서 실행해 정확한 SQLSTATE로 실패하는지 보고, 트랜잭션은 저장점으로 되돌려 계속 쓴다. */
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

    static String randomHash() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

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

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value instanceof Instant instant) {
                statement.setObject(i + 1, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
            } else if (value == null) {
                statement.setNull(i + 1, Types.OTHER);
            } else if (value instanceof NullTimestamp) {
                statement.setNull(i + 1, Types.TIMESTAMP_WITH_TIMEZONE);
            } else if (value instanceof NullText) {
                statement.setNull(i + 1, Types.VARCHAR);
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    /** 형이 있는 NULL 인자(시각). */
    enum NullTimestamp { VALUE }

    /** 형이 있는 NULL 인자(문자열). */
    enum NullText { VALUE }

    /** realtime_activity_session 한 행의 값(스냅샷 · 상태 시험이 고쳐 넣는다). */
    record SessionRow(
            UUID id,
            UUID guardianId,
            UUID deviceId,
            String status,
            int statusVersion,
            String snapshotJson,
            String policyVersion,
            boolean dataCollectionAllowed,
            boolean transcriptStorageAllowed) {

        static SessionRow preparing() {
            return new SessionRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PREPARING", 1, VALID_SNAPSHOT,
                    "1.0", true, true);
        }

        SessionRow withId(UUID value) {
            return new SessionRow(value, guardianId, deviceId, status, statusVersion, snapshotJson, policyVersion,
                    dataCollectionAllowed, transcriptStorageAllowed);
        }

        SessionRow withGuardian(UUID value) {
            return new SessionRow(id, value, deviceId, status, statusVersion, snapshotJson, policyVersion,
                    dataCollectionAllowed, transcriptStorageAllowed);
        }

        SessionRow withDevice(UUID value) {
            return new SessionRow(id, guardianId, value, status, statusVersion, snapshotJson, policyVersion,
                    dataCollectionAllowed, transcriptStorageAllowed);
        }

        SessionRow withStatus(String value, int version) {
            return new SessionRow(id, guardianId, deviceId, value, version, snapshotJson, policyVersion,
                    dataCollectionAllowed, transcriptStorageAllowed);
        }

        SessionRow withSnapshot(String json, String policy, boolean dataCollection, boolean transcriptStorage) {
            return new SessionRow(id, guardianId, deviceId, status, statusVersion, json, policy, dataCollection,
                    transcriptStorage);
        }
    }

    /** BR6.6 ④의 INSERT 모양(ON CONFLICT (id) DO NOTHING). 넣은 행 수를 돌려준다. */
    static int insertSession(Connection connection, SessionRow row) throws SQLException {
        return update(connection,
                "insert into realtime.realtime_activity_session (id, guardian_id, child_id, device_id, activity_type, status,"
                        + " status_version, start_idempotency_key, consent_checked_at, consent_snapshot_json, policy_version,"
                        + " data_collection_allowed, transcript_storage_allowed, child_context_json, row_version, created_at,"
                        + " updated_at)"
                        + " values (?, ?, ?, ?, 'FREE_TALK', ?, ?, ?, ?, ?::jsonb, ?, ?, ?,"
                        + " '{\"displayName\":\"하늘\",\"age\":8,\"interests\":[\"공룡\"]}'::jsonb, 0, ?, ?)"
                        + " on conflict (id) do nothing",
                row.id(),
                row.guardianId(),
                UUID.randomUUID(),
                row.deviceId(),
                row.status(),
                row.statusVersion(),
                UUID.randomUUID().toString(),
                T0,
                row.snapshotJson(),
                row.policyVersion(),
                row.dataCollectionAllowed(),
                row.transcriptStorageAllowed(),
                T0,
                T0);
    }

    /** 기록기의 PARTIAL · FINAL upsert 모양(BR7.5 · SQ-11). */
    static int upsertTranscript(Connection connection, UUID sessionId, int sequenceNo, String speaker, String text,
            String assemblyStatus, boolean approved) throws SQLException {
        return update(connection,
                "insert into realtime.realtime_transcript (id, session_id, sequence_no, speaker, text, assembly_status,"
                        + " approved_for_display, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " on conflict (session_id, sequence_no) do update set text = excluded.text,"
                        + " assembly_status = excluded.assembly_status, approved_for_display = excluded.approved_for_display",
                UUID.randomUUID(), sessionId, sequenceNo, speaker, text, assemblyStatus, approved, T0);
    }

    static void insertOutbox(Connection connection, UUID eventId, String status, String payloadJson, Instant purgedAt,
            String traceId) throws SQLException {
        update(connection,
                "insert into realtime.realtime_outbox_event (event_id, event_type, schema_version, occurred_at,"
                        + " aggregate_type, aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count,"
                        + " available_at, purged_at, created_at, trace_id)"
                        + " values (?, 'DeviceStatusChanged', 1, ?, 'Device', ?, ?, ?::jsonb, ?, ?, 0, ?, ?, ?, ?)",
                eventId,
                T0,
                UUID.randomUUID(),
                UUID.randomUUID(),
                payloadJson,
                randomHash(),
                status,
                T0,
                purgedAt == null ? NullTimestamp.VALUE : purgedAt,
                T0,
                traceId == null ? NullText.VALUE : traceId);
    }

    static void insertInbox(Connection connection, UUID eventId, String status, Instant nextAttemptAt, String traceId)
            throws SQLException {
        update(connection,
                "insert into realtime.realtime_inbox_event (event_id, source_service, event_type, schema_version,"
                        + " occurred_at, aggregate_id, guardian_id, payload_json, payload_hash, status, retry_count,"
                        + " next_attempt_at, received_at, trace_id)"
                        + " values (?, 'CORE', 'DeviceReleased', 1, ?, ?, ?, '{\"reason\":\"REREGISTERED\"}'::jsonb, ?, ?, 0,"
                        + " ?, ?, ?)",
                eventId,
                T0,
                UUID.randomUUID(),
                UUID.randomUUID(),
                randomHash(),
                status,
                nextAttemptAt == null ? NullTimestamp.VALUE : nextAttemptAt,
                T0,
                traceId == null ? NullText.VALUE : traceId);
    }
}
