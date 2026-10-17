package kr.mujoong.realtime.db;

import static kr.mujoong.realtime.db.RealtimeDbFixtures.T0;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.expectSqlState;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.insertSession;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.query;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.single;
import static kr.mujoong.realtime.db.RealtimeDbFixtures.update;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.db.RealtimeDbFixtures.SessionRow;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Realtime V6 제브 검사 기록 표 통합 시험 (U13 FR6.1 · BR8.1 · BR8.2). 표 · 열(전사 원문 칸 없음) · 값 검사 제약 · 외래 키 · guardian 색인 ·
 * 앱 역할 DML 권한을 확인한다. 시험마다 되돌린다. 증거 종류: 실제 DB(Testcontainers), 제브는 부르지 않는다.
 */
@Tag("u13")
@Tag("db")
class GuardrailEventSchemaIT {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String INSERT = "insert into realtime.guardrail_event (id, guardian_id, session_id, mode, source_kind,"
            + " decision, reason_code, policy_version, latency_ms, created_at) values (?, ?, ?, ?, ?, ?, ?, 'jev-guardrail-v1', ?, ?)";

    private static MujoongPostgres postgres;
    private Connection connection;
    private UUID guardian;
    private UUID session;

    @BeforeAll
    static void startDatabase() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @BeforeEach
    void openTransactionWithASession() throws SQLException {
        connection = postgres.connect(Role.REALTIME_APP);
        connection.setAutoCommit(false);
        SessionRow row = SessionRow.preparing();
        guardian = row.guardianId();
        session = row.id();
        insertSession(connection, row);
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    private int insertRow(UUID id, String mode, String source, String decision, String reason, int latency) throws SQLException {
        return update(connection, INSERT, id, guardian, session, mode, source, decision, reason, latency, T0);
    }

    @Test
    void theTableHasExactlyTheGuardrailEventColumnsAndNoColumnCanHoldATranscript() throws SQLException {
        List<String> columns = query(connection, "select column_name from information_schema.columns where table_schema = 'realtime'"
                + " and table_name = 'guardrail_event' order by ordinal_position").stream().map(row -> (String) row.get(0)).toList();

        assertThat(columns).containsExactly("id", "guardian_id", "session_id", "mode", "source_kind", "decision", "reason_code",
                "policy_version", "latency_ms", "created_at");
        // 전사 원문 · 후보 문장 · 맥락 칸은 없다(R-F14): 긴 글을 받을 수 있는 text 형식 열 자체가 없다
        assertThat(single(connection, "select count(*) from information_schema.columns where table_schema = 'realtime'"
                + " and table_name = 'guardrail_event' and data_type = 'text'")).isEqualTo(0L);
        assertThat(single(connection, "select has_table_privilege('realtime.guardrail_event', 'SELECT,INSERT,UPDATE,DELETE')"))
                .isEqualTo(true);
        assertThat(single(connection, "select has_table_privilege('realtime.guardrail_event', 'TRUNCATE')")).isEqualTo(false);
    }

    @Test
    void theGuardianIndexExistsAndNoDatabaseDefaultFillsTheTimestamp() throws SQLException {
        assertThat(single(connection, "select count(*) from pg_indexes where schemaname = 'realtime'"
                + " and indexname = 'guardrail_event_guardian_idx'")).isEqualTo(1L);
        // R2: 시각은 앱 MujoongClock 값만 바인딩한다(DB 기본값 없음)
        assertThat(single(connection, "select column_default from information_schema.columns where table_schema = 'realtime'"
                + " and table_name = 'guardrail_event' and column_name = 'created_at'")).isNull();
    }

    @Test
    void everyAllowedDecisionAndSourceKindIsStored() throws SQLException {
        for (String decision : List.of("ALLOW", "BLOCK", "REVIEW", "TIMEOUT", "ERROR", "VOIDED", "QUEUE_FULL")) {
            assertThat(insertRow(UUID.randomUUID(), "REAL", "NEW_UTTERANCE", decision, "REASON_" + decision, 120)).isEqualTo(1);
        }
        assertThat(insertRow(UUID.randomUUID(), "STUB", "APPROVED_VERBATIM_SKIPPED", "ALLOW", "APPROVED_VERBATIM", 0)).isEqualTo(1);

        assertThat(single(connection, "select count(*) from realtime.guardrail_event where guardian_id = ?", guardian)).isEqualTo(8L);
    }

    @Test
    void theSameIdOverwritesForIdempotentRetryButAPlainDuplicateInsertIsRefused() throws SQLException {
        UUID id = UUID.randomUUID();
        insertRow(id, "REAL", "NEW_UTTERANCE", "ALLOW", "OK", 100);

        int overwritten = update(connection, INSERT.concat(" on conflict (id) do update set decision = excluded.decision,"
                + " reason_code = excluded.reason_code, latency_ms = excluded.latency_ms"),
                id, guardian, session, "REAL", "NEW_UTTERANCE", "BLOCK", "SELF_HARM", 640, T0);

        assertThat(overwritten).isEqualTo(1);
        assertThat(query(connection, "select decision, reason_code, latency_ms from realtime.guardrail_event where id = ?", id))
                .containsExactly(List.of("BLOCK", "SELF_HARM", 640));
        expectSqlState(connection, UNIQUE_VIOLATION, () -> insertRow(id, "REAL", "NEW_UTTERANCE", "ALLOW", "OK", 1));
    }

    @Test
    void anUnknownSessionIsRefusedByTheForeignKeyAndTheSessionCannotBeDeletedWhileARowPointsAtIt() throws SQLException {
        expectSqlState(connection, FOREIGN_KEY_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian,
                UUID.randomUUID(), "REAL", "NEW_UTTERANCE", "ALLOW", "OK", 1, T0));

        insertRow(UUID.randomUUID(), "REAL", "NEW_UTTERANCE", "ALLOW", "OK", 1);
        // ON DELETE RESTRICT: 삭제 참여자가 세션 행(200)보다 먼저 지워야 한다(BR8.3)
        expectSqlState(connection, FOREIGN_KEY_VIOLATION,
                () -> update(connection, "delete from realtime.realtime_activity_session where id = ?", session));
    }

    @Test
    void valueChecksRejectOffModeUnknownEnumsBadReasonCodesAndNegativeLatency() throws SQLException {
        // OFF는 제브 단계를 건너뛰므로 기록이 없다
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertRow(UUID.randomUUID(), "OFF", "NEW_UTTERANCE", "ALLOW", "OK", 1));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertRow(UUID.randomUUID(), "REAL", "MODEL_CLAIMED", "ALLOW", "OK", 1));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertRow(UUID.randomUUID(), "REAL", "NEW_UTTERANCE", "MAYBE", "OK", 1));
        // 사유 코드는 문장 · 원문이 아니라 대문자 코드만
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertRow(UUID.randomUUID(), "REAL", "NEW_UTTERANCE", "BLOCK", "아이가 한 말 그대로", 1));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertRow(UUID.randomUUID(), "REAL", "NEW_UTTERANCE", "BLOCK", "lower_case", 1));
        expectSqlState(connection, CHECK_VIOLATION,
                () -> insertRow(UUID.randomUUID(), "REAL", "NEW_UTTERANCE", "ALLOW", "OK", -1));
    }
}
