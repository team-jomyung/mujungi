package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.MigrationSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

@Tag("u14") @Tag("db")
class U14MigrationIT {
    DbTestSupport db;
    @BeforeEach void open(){db=DbTestSupport.coreApp();}
    @AfterEach void close(){if(db!=null)db.close();}
    String constraint(String table,String column){return db.jdbc().queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conname=:name",Map.of("name",table+"_"+column+"_check"),String.class);}
    @Test void allowsFourAttempts(){assertThat(constraint("core_result_job","attempt_count")).contains("4");}
    @Test void allowsFourthAttemptRow(){assertThat(constraint("core_result_job_attempt","attempt_no")).contains("4");}
    @Test void allowsAnyPositiveSceneNumber(){assertThat(constraint("core_scene_judgment","scene_no")).contains("scene_no > 0").doesNotContain("ARRAY");}
    @Test void preservesAfterReask(){assertThat(constraint("core_scene_judgment","timing")).contains("AFTER_REASK");}
    @Test void preservesLegacyBehaviorConstraints(){assertThat(db.jdbc().queryForObject("select count(*) from pg_constraint where conrelid='core.core_practice_goal_selection'::regclass and contype='f'",Map.of(),Integer.class)).isEqualTo(3);}
    @Test void newColumnsAreOptionalForLegacyWrites(){assertThat(db.jdbc().queryForList("select is_nullable from information_schema.columns where table_schema='core' and table_name='core_scene_judgment' and column_name in ('scene_run_id','flow_version','facts_json','variant_skip_reason')",Map.of(),String.class)).hasSize(4).containsOnly("YES");}
    @Test void activeSelectionHasUniqueCandidateIndex(){assertThat(db.jdbc().queryForObject("select indexdef from pg_indexes where schemaname='core' and indexname='core_saved_goal_active_candidate_idx'",Map.of(),String.class)).contains("UNIQUE","child_id, candidate_id","ACTIVE");}
    @Test void fourthAttemptAcceptedAndFifthRejected(){db.inRolledBackTx(t->{
        UUID id=UUID.randomUUID();
        db.jdbc().update("insert into core.core_result_job(id,job_type,subject_id,guardian_id,idempotency_key,trigger_type,status,attempt_count,next_attempt_at,created_at,updated_at) values(:id,'SESSION_RESULT',:id,:id,:key,'RECONCILE','REQUESTED',4,now(),now(),now())",Map.of("id",id,"key",id.toString()));
        assertThatThrownBy(()->db.jdbc().update("update core.core_result_job set attempt_count=5 where id=:id",Map.of("id",id))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);return null;
    });}
    @Test void upgradePreservesV8RowsAndOldAppCanStillWrite(){
        try(var pg=MujoongPostgres.builder().migrate(false).start()){
            Flyway.configure().configuration(pg.flyway(MigrationSet.CORE).getConfiguration()).target("8").load().migrate();
            var jdbc=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(pg.dataSource(MujoongPostgres.Role.CORE_APP));
            UUID guardian=UUID.randomUUID(),child=UUID.randomUUID(),job=UUID.randomUUID(),result=UUID.randomUUID();
            var p=Map.of("g",guardian,"c",child,"j",job,"r",result,"key",job.toString());
            jdbc.update("insert into core.core_guardian(id,status,created_at,updated_at) values(:g,'ACTIVE',now(),now())",p);
            jdbc.update("insert into core.core_child(id,guardian_id,display_name,age,interests_json,status,created_at,updated_at) values(:c,:g,'합성',8,'[]','ACTIVE',now(),now())",p);
            jdbc.update("insert into core.core_result_job(id,job_type,subject_id,guardian_id,idempotency_key,trigger_type,status,attempt_count,next_attempt_at,created_at,updated_at) values(:j,'SESSION_RESULT',:j,:g,:key,'RECONCILE','DONE',3,now(),now(),now())",p);
            jdbc.update("insert into core.core_result_job_attempt(id,job_id,attempt_no,started_at) values(:j,:j,3,now())",p);
            jdbc.update("insert into core.core_activity_result(id,session_id,version,job_id,result_level,sentences_json,help_json,tips_json,matching_log_json,pending_candidates_json,created_at) values(:r,:j,1,:j,'PARTIAL','[]','[]','[]','{}','[]',now())",p);
            jdbc.update("insert into core.core_scene_judgment(id,result_id,session_id,goal_selection_id,goal_order,scene_no,exposure_type,value,timing,differs_from_live,scene_chip,created_at) values(:r,:r,:j,:r,1,2,'ORIGINAL','functional','AFTER_HELP',false,'DONE_AFTER_HELP',now())",p);
            jdbc.update("insert into core.core_goal_recommendation(id,child_id,source_session_id,behavior_key,behavior_version,display_name,reason_text,status,created_at) values(:j,:c,:j,'ACCEPT_STOP',1,'합성 목표','합성 근거','ACTIVE',now())",p);
            jdbc.update("insert into core.core_practice_goal_selection(id,guardian_id,child_id,recommendation_id,behavior_key,behavior_version,display_name,is_favorite,sort_order,status,selected_at) values(:j,:g,:c,:j,'ACCEPT_STOP',1,'합성 목표',false,1,'ACTIVE',now())",p);
            var before=snapshot(jdbc);
            pg.flyway(MigrationSet.CORE).migrate();
            assertThat(snapshot(jdbc)).isEqualTo(before);
            // 구 앱이 알던 열과 값만 사용하는 INSERT/UPDATE가 계속 성공한다.
            jdbc.update("update core.core_result_job set attempt_count=2 where id=:j",p);
            jdbc.update("update core.core_result_job_attempt set attempt_no=2 where id=:j",p);
            jdbc.update("update core.core_scene_judgment set scene_no=1,timing='IMMEDIATE' where id=:r",p);
            jdbc.update("update core.core_practice_goal_selection set is_favorite=true where id=:j",p);
            assertThat(jdbc.queryForObject("select timing from core.core_scene_judgment where id=:r",p,String.class)).isEqualTo("IMMEDIATE");
        }
    }
    java.util.List<String> snapshot(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc){
        var rows=new java.util.ArrayList<String>();
        for(String table:java.util.List.of("result_job","result_job_attempt","activity_result","scene_judgment","goal_recommendation","practice_goal_selection")){
            // 新 nullable 열은 제외하고 V8에 존재하던 모든 행/값을 비교한다.
            rows.addAll(jdbc.queryForList("select (to_jsonb(t)-'scene_run_id'-'flow_version'-'facts_json'-'variant_skip_reason')::text from core.core_"+table+" t order by id",Map.of(),String.class));
        }
        return rows;
    }
}
