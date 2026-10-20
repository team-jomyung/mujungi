package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.MigrationSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("scenario-generation") @Tag("db")
class ScenarioMigrationIT {
    @Test void v9RowsAndOldInsertSurviveAndNewColumnsAreNullable() {
        try(var pg=MujoongPostgres.builder().migrate(false).start()) {
            Flyway.configure().configuration(pg.flyway(MigrationSet.CORE).getConfiguration()).target("9").load().migrate();
            var jdbc=new NamedParameterJdbcTemplate(pg.dataSource(MujoongPostgres.Role.CORE_APP));
            UUID g=UUID.randomUUID(),c=UUID.randomUUID(); var p=Map.of("g",g,"c",c,"id",UUID.randomUUID());
            jdbc.update("insert into core.core_guardian(id,status,created_at,updated_at) values(:g,'ACTIVE',now(),now())",p);
            jdbc.update("insert into core.core_child(id,guardian_id,display_name,age,interests_json,status,created_at,updated_at) values(:c,:g,'합성',8,'[]','ACTIVE',now(),now())",p);
            insert(jdbc,p);
            String before=jdbc.queryForObject("select to_jsonb(s)::text from core.core_scenario s where id=:id",p,String.class);
            pg.flyway(MigrationSet.CORE).migrate();
            String after=jdbc.queryForObject("select (to_jsonb(s)-'script_version'-'scenes_json'-'self_review_json'-'generation_json'-'goal_context_json')::text from core.core_scenario s where id=:id",p,String.class);
            assertThat(after).isEqualTo(before);
            assertThat(jdbc.queryForList("select is_nullable from information_schema.columns where table_schema='core' and table_name='core_scenario' and column_name in ('script_version','scenes_json','self_review_json','generation_json','goal_context_json')",Map.of(),String.class)).hasSize(5).containsOnly("YES");
            var next=Map.of("g",g,"c",c,"id",UUID.randomUUID()); insert(jdbc,next);
            assertThat(jdbc.queryForObject("select script_version from core.core_scenario where id=:id",next,String.class)).isNull();
            jdbc.update("update core.core_scenario set script_version='scenario-script.v1',scenes_json='[]',self_review_json='{\"verdict\":\"APPROVE\"}',generation_json='{\"graphContextUsed\":false}',goal_context_json='{\"kind\":\"CANDIDATE\"}' where id=:id",next);
            assertThat(jdbc.queryForObject("select goal_context_json->>'kind' from core.core_scenario where id=:id",next,String.class)).isEqualTo("CANDIDATE");
        }
    }
    static void insert(NamedParameterJdbcTemplate jdbc,Map<String,UUID> p) {
        jdbc.update("""
            insert into core.core_scenario(id,guardian_id,child_id,session_id,goal_selection_id,behavior_key,behavior_version,
            kind,relation_type,opportunity_condition,scenario_text,partner_role_text,trigger_script,validation_json,
            content_fingerprint,status,model_version,prompt_version,created_at)
            values(:id,:g,:c,:id,:id,'ACCEPT_STOP',1,'ORIGINAL','PEER','기회','상황','역할','말',
            '{"goalIncluded":true,"triggerIncluded":true,"forbiddenFound":false}',cast(:id as text),'ISSUED','fake','fake',now())
            """,p);
    }
}
