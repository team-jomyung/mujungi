package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.startup.SchemaVersionCheck;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.MigrationSet;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("u5b")
@Tag("db")
class PracticeMigrationIT {
    private static final List<String> TABLES = List.of("realtime_session_goal", "realtime_practice_attempt",
            "realtime_practice_response", "realtime_practice_cue", "realtime_practice_evaluation");

    @Test
    void upgradingFromV3PreservesTheExistingSessionAndAppliesOnlyV4() {
        try (var db = MujoongPostgres.builder().migrate(false).start()) {
            db.flyway(MigrationSet.CORE).migrate();
            Flyway.configure().configuration(db.flyway(MigrationSet.REALTIME).getConfiguration())
                    .target("3").load().migrate();
            var jdbc = new JdbcTemplate(db.dataSource(Role.REALTIME_APP));
            UUID session = UUID.randomUUID();
            Timestamp now = Timestamp.from(PracticeModelTest.T);
            jdbc.update("insert into realtime.realtime_activity_session"
                    + "(id,guardian_id,child_id,device_id,activity_type,status,status_version,start_idempotency_key,"
                    + "consent_checked_at,consent_snapshot_json,policy_version,data_collection_allowed,"
                    + "transcript_storage_allowed,created_at,updated_at)"
                    + " values(?,?,?,?,'FREE_TALK','ACTIVE',1,?,?,?::jsonb,'1',true,true,?,?)",
                    session, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(),
                    now, "{\"policyVersion\":\"1\",\"voiceAndText\":true,\"guardianView\":true,"
                            + "\"reportSharing\":false,\"rawAudio\":false}", now, now);

            assertThat(db.flyway(MigrationSet.REALTIME).migrate().migrationsExecuted).isEqualTo(1);

            assertThat(jdbc.queryForObject("select status from realtime.realtime_activity_session where id=?",
                    String.class, session)).isEqualTo("ACTIVE");
            assertThat(new SchemaVersionCheck(jdbc).verify()).isEqualTo("4");
            assertThat(db.flyway(MigrationSet.REALTIME).migrate().migrationsExecuted).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"realtime_session_goal,7", "realtime_practice_attempt,5", "realtime_practice_response,7",
            "realtime_practice_cue,4", "realtime_practice_evaluation,3"})
    void newTablesHaveExpectedColumnsAndOnlyAppDmlPrivileges(String table, int columns) {
        var jdbc = PracticeDb.JDBC;
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns"
                + " where table_schema='realtime' and table_name=?", Integer.class, table)).isEqualTo(columns);
        assertThat(jdbc.queryForObject("select has_table_privilege(?, 'SELECT,INSERT,UPDATE,DELETE')",
                Boolean.class, "realtime." + table)).isTrue();
        assertThat(jdbc.queryForObject("select has_table_privilege(?, 'TRUNCATE')",
                Boolean.class, "realtime." + table)).isFalse();
    }

    @Test
    void goalSelectionAndOrderAreUniqueWithinASession() {
        var db = new PracticeDb();
        assertThatThrownBy(() -> PracticeDb.JDBC.update("insert into realtime.realtime_session_goal"
                + "(id,session_id,guardian_id,goal_selection_id,goal_order,data)"
                + " select ?,session_id,guardian_id,goal_selection_id,2,data from realtime.realtime_session_goal where id=?",
                UUID.randomUUID(), db.goal.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> PracticeDb.JDBC.update("insert into realtime.realtime_session_goal"
                + "(id,session_id,guardian_id,goal_selection_id,goal_order,data)"
                + " select ?,session_id,guardian_id,?,goal_order,data from realtime.realtime_session_goal where id=?",
                UUID.randomUUID(), UUID.randomUUID(), db.goal.id())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void responseCannotReferenceAnotherScenarioOrPretendAPendingResultIsFinal() {
        var db = new PracticeDb();
        var response = db.pending();
        assertThatThrownBy(() -> PracticeDb.JDBC.update(
                "update realtime.realtime_practice_response set scenario_id=? where id=?",
                UUID.randomUUID(), response.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> PracticeDb.JDBC.update("update realtime.realtime_practice_response"
                + " set data=data || '{\"evaluationState\":\"FINALIZED\"}'::jsonb where id=?", response.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(db.repository.response(response.id())).contains(response);
    }

    @Test
    void v4IsRecordedOnceAndEveryPracticeForeignKeyPreservesItsParent() {
        assertThat(PracticeDb.JDBC.queryForList("select script from flyway.realtime_schema_history where version='4'",
                String.class)).containsExactly("V4__u5b_practice.sql");
        assertThat(PracticeDb.JDBC.queryForList("select c.confdeltype::text from pg_constraint c"
                + " join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace"
                + " where n.nspname='realtime' and c.contype='f'"
                + " and t.relname in ('" + String.join("','", TABLES) + "')", String.class))
                .hasSize(5).containsOnly("a");
    }
}
