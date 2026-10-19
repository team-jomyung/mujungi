package kr.mujoong.core.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.common.db.DbTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** U6 FR1 · FR4 · FR10: 운영 DB 역할로 유일성과 장면 불변식을 검증한다. */
@Tag("result-report")
@Tag("db")
class SchemaIT {
    private DbTestSupport db;
    @BeforeEach void open() { db = DbTestSupport.coreApp(); }
    @AfterEach void close() { db.close(); }

    @Test void createsAllTenTablesWithCorePrivileges() {
        for (String table : new String[]{"result_job", "result_job_attempt", "session_result_state", "activity_result",
                "structured_experience", "scene_judgment", "report", "report_activity", "report_section", "report_citation"}) {
            assertThat(db.jdbc().getJdbcTemplate().queryForObject(
                    "select has_table_privilege(current_user, ?, 'SELECT,INSERT,UPDATE,DELETE')", Boolean.class,
                    "core.core_" + table)).isTrue();
        }
    }

    @Test void allowsOnlyOneActiveJobPerSubject() {
        UUID subject = UUID.randomUUID();
        db.inRolledBackTx(tx -> {
            job(subject, "REQUESTED");
            assertThatThrownBy(() -> job(subject, "REQUESTED")).isInstanceOf(DataIntegrityViolationException.class);
            return null;
        });
    }

    @Test void allowsNewJobAfterDone() {
        db.inRolledBackTx(tx -> {
            UUID subject = UUID.randomUUID();
            job(subject, "DONE");
            job(subject, "REQUESTED");
            assertThat(db.jdbc().queryForObject("select count(*) from core.core_result_job where subject_id=:id",
                    Map.of("id", subject), Integer.class)).isEqualTo(2);
            return null;
        });
    }

    @Test void forbidsNullValueWithNonNotDoneChip() { invalidScene(null, "NEXT_TIME", "ACTIVITY_ENDED"); }
    @Test void forbidsNotDoneWithoutReason() { invalidScene(null, "NOT_DONE", null); }
    @Test void forbidsReasonForJudgedScene() { invalidScene("functional", "DONE_IMMEDIATE", "ACTIVITY_ENDED"); }

    @Test void reportKeyIsUniquePerGuardian() {
        db.inRolledBackTx(tx -> {
            UUID guardian = UUID.randomUUID();
            Map<String,Object> p = Map.of("id", UUID.randomUUID(), "g", guardian, "c", UUID.randomUUID());
            String sql = "insert into core.core_report(id,guardian_id,child_id,idempotency_key,request_hash,status,"
                    + "period_from,period_to,created_at) values(:id,:g,:c,'same','hash','GENERATING','2026-01-01','2026-01-02','2026-01-02Z')";
            db.jdbc().update(sql, p);
            assertThatThrownBy(() -> db.jdbc().update(sql, Map.of("id",UUID.randomUUID(),"g",guardian,"c",UUID.randomUUID())))
                    .isInstanceOf(DataIntegrityViolationException.class);
            return null;
        });
    }

    @Test void existingSupportItemsKeepNullableTitleAndNoNewForeignKeyTargetsOldTables() {
        assertThat(db.jdbc().getJdbcTemplate().queryForObject("select is_nullable from information_schema.columns "
                + "where table_schema='core' and table_name='core_support_item' and column_name='title'", String.class))
                .isEqualTo("YES");
        assertThat(db.jdbc().getJdbcTemplate().queryForObject("select count(*) from pg_constraint where contype='f' "
                + "and conrelid='core.core_session_result_state'::regclass and confrelid='core.core_activity_record'::regclass", Integer.class))
                .isZero();
    }

    private UUID job(UUID subject, String status) {
        UUID id = UUID.randomUUID();
        db.jdbc().update("insert into core.core_result_job(id,job_type,subject_id,guardian_id,idempotency_key,trigger_type,status,"
                + "next_attempt_at,created_at,updated_at) values(:id,'SESSION_RESULT',:s,:g,:key,'RECONCILE',:status,"
                + "'2026-01-01Z','2026-01-01Z','2026-01-01Z')", Map.of("id",id,"s",subject,"g",UUID.randomUUID(),
                "key",id.toString(),"status",status));
        return id;
    }

    private void invalidScene(String value, String chip, String reason) {
        db.inRolledBackTx(tx -> {
            UUID session=UUID.randomUUID(), result=UUID.randomUUID(), job=job(session,"RUNNING");
            db.jdbc().update("insert into core.core_activity_result(id,session_id,version,job_id,result_level,sentences_json,"
                    + "help_json,tips_json,matching_log_json,pending_candidates_json,created_at) "
                    + "values(:id,:s,1,:job,'PARTIAL','[]','[]','[]','{}','[]','2026-01-01Z')",Map.of("id",result,"s",session,"job",job));
            assertThatThrownBy(() -> db.jdbc().getJdbcTemplate().update("insert into core.core_scene_judgment "
                    + "(id,result_id,session_id,goal_selection_id,goal_order,scene_no,exposure_type,value,scene_chip,not_done_reason,"
                    + "differs_from_live,created_at) values(?,?,?,?,1,1,'ORIGINAL',?,?,?,false,'2026-01-01Z')",
                    UUID.randomUUID(),result,session,UUID.randomUUID(),value,chip,reason))
                    .isInstanceOf(DataIntegrityViolationException.class);
            return null;
        });
    }
}
