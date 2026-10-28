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
import java.time.Duration;
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
 * Realtime V7 새 연습 흐름 표 통합 시험 (U12 FR4.1 · BR7.1 · BR7.3 · BR7.4 · 편차 U12-D2). 표 · 열(원문 칸 없음) · 값 검사 제약 · 외래 키 · 색인 ·
 * 앱 역할 DML 권한, 그리고 V4 레거시 표 다섯 개가 그대로인지 확인한다. 시험마다 되돌린다. 증거 종류: 실제 DB(Testcontainers), AI는 부르지 않는다.
 */
@Tag("practice-flow")
@Tag("db")
class PracticeFlowSchemaIT {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String INSERT_RUN = "insert into realtime.realtime_practice_scene_run (id, session_id, guardian_id,"
            + " goal_ref, goal_index, scenario_ref, scene_kind, started_at) values (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_HELP = "insert into realtime.realtime_practice_help (id, scene_run_id, guardian_id, kind,"
            + " role, example_given, consent_answer, content_chars, occurred_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** V4 레거시 표 다섯 개(BR10.1): 이 파일이 건드리지 않았음을 열 수로 고정한다. */
    private static final List<String> LEGACY_TABLES = List.of("realtime_session_goal", "realtime_practice_attempt",
            "realtime_practice_response", "realtime_practice_cue", "realtime_practice_evaluation");

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

    private UUID insertRun(String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        update(connection, INSERT_RUN, id, session, guardian, UUID.randomUUID(), 0, UUID.randomUUID(), kind, T0);
        return id;
    }

    private int insertHelp(UUID run, String kind, String role, String consent, int chars) throws SQLException {
        return update(connection, INSERT_HELP, UUID.randomUUID(), run, guardian, kind, role, false, consent, chars, T0);
    }

    @Test
    void theTablesHaveExactlyTheDesignedColumnsAndNoColumnCanHoldAnUtterance() throws SQLException {
        assertThat(columns("realtime_practice_scene_run")).containsExactly("id", "session_id", "guardian_id", "goal_ref",
                "goal_index", "scenario_ref", "scene_kind", "started_at", "ended_at", "live_timing", "variant_skip_reason",
                "retry_count", "reexplain_count", "facts", "fact_ats");
        assertThat(columns("realtime_practice_help")).containsExactly("id", "scene_run_id", "guardian_id", "kind", "role",
                "example_given", "consent_answer", "content_chars", "occurred_at");
        assertThat(columns("realtime_practice_preparation")).containsExactly("scene_run_id", "provided_at", "attempted_at");
        // 원문 칸은 없다(R-F14 · U12-D2): 긴 글을 받을 수 있는 text 형식 열 자체가 없다(facts는 계약 코드로 제한한 배열이다)
        for (String table : List.of("realtime_practice_scene_run", "realtime_practice_help", "realtime_practice_preparation")) {
            assertThat(single(connection, "select count(*) from information_schema.columns where table_schema = 'realtime'"
                    + " and table_name = '" + table + "' and data_type = 'text'")).as(table).isEqualTo(0L);
            assertThat(single(connection, "select has_table_privilege('realtime." + table + "', 'SELECT,INSERT,UPDATE,DELETE')"))
                    .as(table).isEqualTo(true);
            assertThat(single(connection, "select has_table_privilege('realtime." + table + "', 'TRUNCATE')"))
                    .as(table).isEqualTo(false);
        }
    }

    @Test
    void theIndexesExistAndNoDatabaseDefaultFillsATimestamp() throws SQLException {
        assertThat(query(connection, "select indexname from pg_indexes where schemaname = 'realtime' and indexname in"
                + " ('realtime_practice_scene_run_guardian_idx', 'realtime_practice_scene_run_session_idx',"
                + " 'realtime_practice_help_guardian_idx', 'realtime_practice_help_scene_run_idx') order by indexname"))
                .extracting(row -> row.get(0)).containsExactly("realtime_practice_help_guardian_idx",
                        "realtime_practice_help_scene_run_idx", "realtime_practice_scene_run_guardian_idx",
                        "realtime_practice_scene_run_session_idx");
        // R2: 시각은 앱 MujoongClock 값만 바인딩한다(DB 기본값 없음)
        assertThat(single(connection, "select count(*) from information_schema.columns where table_schema = 'realtime'"
                + " and table_name in ('realtime_practice_scene_run', 'realtime_practice_help', 'realtime_practice_preparation')"
                + " and data_type like 'timestamp%' and column_name <> 'fact_ats' and column_default is not null"))
                .isEqualTo(0L);
    }

    @Test
    void baseAndSeveralVariantRunsOfOneGoalAreStoredWhichV4CouldNot() throws SQLException {
        UUID goal = UUID.randomUUID();
        UUID scenario = UUID.randomUUID();
        for (String kind : List.of("BASE", "VARIANT", "VARIANT")) {
            update(connection, INSERT_RUN, UUID.randomUUID(), session, guardian, goal, 0, scenario, kind, T0);
        }

        assertThat(single(connection, "select count(*) from realtime.realtime_practice_scene_run where goal_ref = ?", goal))
                .isEqualTo(3L);
    }

    @Test
    void factsAcceptOnlyContractKindsAndNeedOneTimestampEach() throws SQLException {
        UUID run = insertRun("BASE");

        assertThat(update(connection, "update realtime.realtime_practice_scene_run set facts = ARRAY['REASK_RESOLVED',"
                + " 'VARIANT_SKIPPED']::text[], fact_ats = ARRAY[?, ?]::timestamptz[] where id = ?", T0, T0, run)).isEqualTo(1);
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_practice_scene_run set facts = ARRAY['아이가 한 말']::text[],"
                        + " fact_ats = ARRAY[?]::timestamptz[] where id = ?", T0, run));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_practice_scene_run set facts = ARRAY['TIMEOUT']::text[] where id = ?", run));
    }

    @Test
    void preparationUsesAnAdditiveTableAndLeavesOldFactsChecksIntact() throws SQLException {
        UUID run = insertRun("BASE");
        assertThat(update(connection, "insert into realtime.realtime_practice_preparation"
                + " (scene_run_id, provided_at, attempted_at) values (?, ?, ?)", run, T0, T0)).isEqualTo(1);
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_practice_scene_run set facts = ARRAY['PREPRACTICE_PROVIDED']::text[],"
                        + " fact_ats = ARRAY[?]::timestamptz[] where id = ?", T0, run));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_practice_preparation set provided_at = null, attempted_at = null where scene_run_id = ?", run));
        expectSqlState(connection, FOREIGN_KEY_VIOLATION, () -> update(connection,
                "insert into realtime.realtime_practice_preparation (scene_run_id, provided_at) values (?, ?)", UUID.randomUUID(), T0));
        assertThat(update(connection, "delete from realtime.realtime_practice_scene_run where id = ?", run)).isEqualTo(1);
        assertThat(single(connection, "select count(*) from realtime.realtime_practice_preparation where scene_run_id = ?", run))
                .isEqualTo(0L);
    }

    @Test
    void valueChecksRejectUnknownEnumsNegativeCountsAndAnEndBeforeTheStart() throws SQLException {
        UUID run = insertRun("BASE");
        expectSqlState(connection, CHECK_VIOLATION, () -> insertRun("SIDE"));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_practice_scene_run set live_timing = 'LATE' where id = ?", run));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_practice_scene_run set variant_skip_reason = 'BORED' where id = ?", run));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_practice_scene_run set retry_count = -1 where id = ?", run));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_practice_scene_run set goal_index = -1 where id = ?", run));
        expectSqlState(connection, CHECK_VIOLATION, () -> update(connection,
                "update realtime.realtime_practice_scene_run set ended_at = ? where id = ?", T0.minus(Duration.ofSeconds(1)), run));
        for (String timing : List.of("IMMEDIATE", "AFTER_REASK", "AFTER_HELP", "NONE", "ABSTAINED")) {
            assertThat(update(connection, "update realtime.realtime_practice_scene_run set live_timing = ? where id = ?", timing,
                    run)).isEqualTo(1);
        }
        for (String reason : List.of("NO_VARIANT", "DECLINED", "TIME_LIMIT", "FOCUS_LIMIT", "END_OR_REST")) {
            assertThat(update(connection, "update realtime.realtime_practice_scene_run set variant_skip_reason = ? where id = ?",
                    reason, run)).isEqualTo(1);
        }
    }

    @Test
    void helpRowsStoreCodesAndLengthOnlyAndRejectUnknownValues() throws SQLException {
        UUID run = insertRun("BASE");

        assertThat(insertHelp(run, "ROLE_SWITCH", "COACH", null, 0)).isEqualTo(1);
        assertThat(insertHelp(run, "EXPRESSION_EXAMPLE", "COACH", "ACCEPT", 18)).isEqualTo(1);
        assertThat(insertHelp(run, "ROLE_RETURN", "PEER", "REQUESTED", 0)).isEqualTo(1);
        expectSqlState(connection, CHECK_VIOLATION, () -> insertHelp(run, "LECTURE", "COACH", null, 0));
        expectSqlState(connection, CHECK_VIOLATION, () -> insertHelp(run, "FOCUS_HELP", "BOT", null, 0));
        expectSqlState(connection, CHECK_VIOLATION, () -> insertHelp(run, "FOCUS_HELP", "COACH", "MAYBE", 0));
        expectSqlState(connection, CHECK_VIOLATION, () -> insertHelp(run, "FOCUS_HELP", "COACH", null, -1));
    }

    @Test
    void foreignKeysProtectTheSessionAndTheRunAndDeletionMustGoHelpThenRunThenSession() throws SQLException {
        expectSqlState(connection, FOREIGN_KEY_VIOLATION, () -> update(connection, INSERT_RUN, UUID.randomUUID(), UUID.randomUUID(),
                guardian, UUID.randomUUID(), 0, UUID.randomUUID(), "BASE", T0));
        UUID run = insertRun("BASE");
        expectSqlState(connection, FOREIGN_KEY_VIOLATION, () -> insertHelp(UUID.randomUUID(), "FOCUS_HELP", "COACH", null, 0));
        insertHelp(run, "FOCUS_HELP", "COACH", null, 3);

        // ON DELETE RESTRICT: 삭제 참여자가 도움 → 장면 실행 → 세션(200) 순서로 지워야 한다(BR7.4)
        expectSqlState(connection, FOREIGN_KEY_VIOLATION,
                () -> update(connection, "delete from realtime.realtime_practice_scene_run where id = ?", run));
        expectSqlState(connection, FOREIGN_KEY_VIOLATION,
                () -> update(connection, "delete from realtime.realtime_activity_session where id = ?", session));
        expectSqlState(connection, UNIQUE_VIOLATION, () -> update(connection, INSERT_RUN, run, session, guardian,
                UUID.randomUUID(), 0, UUID.randomUUID(), "BASE", T0));
    }

    @Test
    void theV4LegacyTablesKeepTheirColumnsAndTheirUniqueRuleThatTheNewTablesAvoid() throws SQLException {
        assertThat(single(connection, "select count(*) from information_schema.columns where table_schema = 'realtime'"
                + " and table_name in ('realtime_session_goal', 'realtime_practice_attempt', 'realtime_practice_response',"
                + " 'realtime_practice_cue', 'realtime_practice_evaluation')")).isEqualTo(26L);
        for (String table : LEGACY_TABLES) {
            assertThat(single(connection, "select count(*) from pg_tables where schemaname = 'realtime' and tablename = '" + table
                    + "'")).as(table).isEqualTo(1L);
        }
        // 새 표는 레거시 표를 참조하지 않는다(BR10.1)
        assertThat(single(connection, "select count(*) from pg_constraint where contype = 'f' and conrelid in"
                + " ('realtime.realtime_practice_scene_run'::regclass, 'realtime.realtime_practice_help'::regclass)"
                + " and confrelid::regclass::text in ('realtime.realtime_session_goal', 'realtime.realtime_practice_attempt')"))
                .isEqualTo(0L);
        assertThat((Long) single(connection, "select count(*) from pg_constraint where contype = 'u' and conrelid ="
                + " 'realtime.realtime_practice_attempt'::regclass")).isGreaterThanOrEqualTo(1L);
    }

    private List<String> columns(String table) throws SQLException {
        return query(connection, "select column_name from information_schema.columns where table_schema = 'realtime'"
                + " and table_name = '" + table + "' order by ordinal_position").stream().map(row -> (String) row.get(0)).toList();
    }
}
