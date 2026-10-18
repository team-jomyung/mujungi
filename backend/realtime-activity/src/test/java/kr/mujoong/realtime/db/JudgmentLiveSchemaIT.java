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
 * Realtime V5 진행용 판정 기록 표 통합 시험 (U11 FR2.1 · FR2.2 · BR2.1 · BR2.3 · BR2.5). 표 · 열 · 값 검사 제약 · 외래 키 · guardian 색인 ·
 * 앱 역할 DML 권한을 확인한다. 시험마다 되돌린다.
 */
@Tag("judgment-turn")
@Tag("db")
class JudgmentLiveSchemaIT {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String INSERT = "insert into realtime.realtime_judgment_live (id, guardian_id, session_id, judgment_point,"
            + " trigger_kind, input_transcript_seq, option_set_version, prompt_version, model, outcome, abstain_reason, turn_opened,"
            + " first_option, first_probability, second_option, second_probability, latency_millis, next_action,"
            + " held_output_transcript, created_at) values (?, ?, ?, ?, ?, 0, 'judgment-v1', 'prompt-v1', null, ?, ?, true, ?, ?, ?, ?, 120,"
            + " ?, ?, ?)";

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

    private int insertConfirmed(UUID id) throws SQLException {
        return update(connection, INSERT, id, guardian, session, "J6", "STOP_CONFIRM_CANDIDATE", "CONFIRMED", null, "END_CLEAR",
                0.8, "REST", 0.1, "CLOSE", null, T0);
    }

    @Test
    void theTableHasExactlyTheJudgmentLiveRecordColumnsAndTheAppRoleGetsDmlOnly() throws SQLException {
        assertThat(query(connection, "select column_name from information_schema.columns where table_schema = 'realtime'"
                + " and table_name = 'realtime_judgment_live' order by ordinal_position")).extracting(row -> (String) row.get(0))
                .containsExactly("id", "guardian_id", "session_id", "judgment_point", "trigger_kind", "goal_ref", "scene_ref",
                        "input_transcript_seq", "option_set_version", "prompt_version", "model", "outcome", "abstain_reason",
                        "turn_opened", "first_option", "first_probability", "second_option", "second_probability",
                        "latency_millis", "next_action", "held_output_transcript", "created_at");
        assertThat(single(connection, "select has_table_privilege('realtime.realtime_judgment_live', 'SELECT,INSERT,UPDATE,DELETE')"))
                .isEqualTo(true);
        assertThat(single(connection, "select has_table_privilege('realtime.realtime_judgment_live', 'TRUNCATE')")).isEqualTo(false);
        assertThat(single(connection, "select count(*) from pg_indexes where schemaname = 'realtime'"
                + " and indexname = 'realtime_judgment_live_guardian_idx'")).isEqualTo(1L);
    }

    @Test
    void aConfirmedRowIsStoredAndTheSameIdOverwritesItForIdempotentRetry() throws SQLException {
        UUID id = UUID.randomUUID();
        assertThat(insertConfirmed(id)).isEqualTo(1);

        int overwritten = update(connection, INSERT.replace(") values (", ") values (").concat(
                " on conflict (id) do update set outcome = excluded.outcome, abstain_reason = excluded.abstain_reason,"
                        + " first_option = excluded.first_option, first_probability = excluded.first_probability,"
                        + " second_option = excluded.second_option, second_probability = excluded.second_probability"),
                id, guardian, session, "J6", "STOP_CONFIRM_CANDIDATE", "ABSTAINED", "LOW_MARGIN", null, null, null, null,
                "ASK_STOP_CONFIRM", null, T0);

        assertThat(overwritten).isEqualTo(1);
        assertThat(query(connection, "select outcome, abstain_reason from realtime.realtime_judgment_live where id = ?", id))
                .containsExactly(List.of("ABSTAINED", "LOW_MARGIN"));
    }

    @Test
    void anUnknownSessionIsRefusedByTheForeignKeyAndPlainDuplicateIdsByTheKey() throws SQLException {
        UUID id = UUID.randomUUID();
        insertConfirmed(id);

        expectSqlState(connection, FOREIGN_KEY_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian,
                UUID.randomUUID(), "J1", "JOIN_UNCLEAR", "FAULT_CANCELLED", null, null, null, null, null, "NONE", null, T0));
        expectSqlState(connection, UNIQUE_VIOLATION, () -> insertConfirmed(id));
    }

    @Test
    void outcomeAndOptionsMustAgreeAndAbstainedNeedsAReason() throws SQLException {
        // CONFIRMED인데 선택지가 없다
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian, session, "J1",
                "JOIN_UNCLEAR", "CONFIRMED", null, null, null, null, null, "START_JOIN", null, T0));
        // 선택지가 있는데 ABSTAINED(확률이 있어도 확정으로 쓰지 않는다 — 그래도 사유가 필요하다)
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian, session, "J1",
                "JOIN_UNCLEAR", "ABSTAINED", null, null, null, null, null, "REPROMPT_JOIN", null, T0));
        // 사유가 있는데 CONFIRMED
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian, session, "J6",
                "STOP_CONFIRM_CANDIDATE", "CONFIRMED", "TIMEOUT", "END_CLEAR", 0.8, "REST", 0.1, "CLOSE", null, T0));
    }

    @Test
    void valueChecksRejectUnknownEnumsAndOutOfRangeNumbersAndOverlongHeldText() throws SQLException {
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian, session, "J7",
                "JOIN_UNCLEAR", "FAULT_CANCELLED", null, null, null, null, null, "NONE", null, T0));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian, session, "J1",
                "SOMETHING", "FAULT_CANCELLED", null, null, null, null, null, "NONE", null, T0));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian, session, "J1",
                "JOIN_UNCLEAR", "FAULT_CANCELLED", null, null, null, null, null, "SEND_EMAIL", null, T0));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian, session, "J1",
                "JOIN_UNCLEAR", "ABSTAINED", "BORED", null, null, null, null, "REPROMPT_JOIN", null, T0));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian, session, "J6",
                "STOP_CONFIRM_CANDIDATE", "CONFIRMED", null, "END_CLEAR", 1.5, "REST", 0.1, "CLOSE", null, T0));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection, INSERT, UUID.randomUUID(), guardian, session, "J1",
                "JOIN_UNCLEAR", "FAULT_CANCELLED", null, null, null, null, null, "NONE", "가".repeat(2001), T0));
        // 2000자는 허용
        assertThat(update(connection, INSERT, UUID.randomUUID(), guardian, session, "J1", "JOIN_UNCLEAR", "FAULT_CANCELLED", null,
                null, null, null, null, "NONE", "가".repeat(2000), T0)).isEqualTo(1);
    }
}
