package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.practice.PracticeSessionContextPort.*;
import kr.mujoong.core.textai.GeneratedScenario;
import org.junit.jupiter.api.*;

@Tag("scenario-generation") @Tag("db")
class ScenarioRepositoryIT {
    DbTestSupport db; ScenarioRepository repo; UUID g,c;
    final Duration timeout=Duration.ofMillis(500);
    @BeforeEach void open(){db=DbTestSupport.coreApp();repo=new ScenarioRepository(db.jdbc(),JsonCodec.shared());g=db.insertGuardian("ACTIVE");c=db.insertChild(g);}
    @AfterEach void close(){if(db!=null){db.jdbc().update("delete from core.core_scenario_request where guardian_id=:g",Map.of("g",g));db.jdbc().update("delete from core.core_scenario where guardian_id=:g",Map.of("g",g));db.close();}}
    Scenario save(String text,ScenarioRepository.Details details){
        UUID s=UUID.randomUUID(),goal=UUID.randomUUID(); var ctx=new SessionContext(s,g,c,true,List.of(new GoalSnapshot(goal,"ACCEPT_STOP",1,"PEER")));
        var req=new ScenarioService.Request(s,goal,"ORIGINAL",null);
        return db.inTx(tx->{var claim=repo.claim(req.key(),ctx,req,DbTestSupport.EPOCH,DbTestSupport.EPOCH.plusSeconds(15));
            return repo.save(req.key(),claim.claimId(),ctx,ctx.goals().getFirst(),req,new GeneratedScenario(text,"친구의 말","PEER","기회","fake","fake"),DbTestSupport.EPOCH,details);});
    }
    void expose(Scenario s){repo.expose(s.scenarioId(),UUID.randomUUID(),DbTestSupport.EPOCH);}
    List<ScenarioRepository.ExposureText> history(){return repo.recentExposed(g,c,DbTestSupport.EPOCH,timeout);}
    @Test void newExtensionRoundTripsWithGoalContextAndLegacyFields(){
        var details=new ScenarioRepository.Details("scenario-script.v1",List.of(ScenarioContractTest.scene().withPartnerRole(ScenarioContentPolicy.PARTNER_ROLE)),new Scenario.Review("APPROVE","fake","scenario-review.v1"),new Scenario.Generation("fake","scenario-generation.v2",false,2),"{\"kind\":\"CANDIDATE\"}");
        var saved=save("새 상황",details);var loaded=repo.require(saved.scenarioId());assertThat(loaded.scenario()).isEqualTo(saved);assertThat(loaded.goalContextJson()).contains("CANDIDATE");
        assertThat(saved.partnerRoleText()).isEqualTo(ScenarioContentPolicy.PARTNER_ROLE);
    }
    /** v3 그래프 경로 대본: 목표 상대(relation)는 자유 한국어, 관계 좌표는 preservation.relationship 열거값으로 저장 · 노출 이력에 남는다. */
    @Test void v3GraphScriptRoundTripsAndExposesItsEnumeratedRelationshipCoordinate(){
        var base=ScenarioContractTest.graphBase("아이와 친구","CLASSMATE");
        var details=new ScenarioRepository.Details("scenario-script.v1",List.of(base.withPartnerRole(ScenarioContentPolicy.PARTNER_ROLE)),new Scenario.Review("APPROVE","fake","scenario-review.v1"),
                new Scenario.Generation("fake",kr.mujoong.core.textai.ScenarioScriptProvider.GENERATION_PROMPT_VERSION,true,4,"graph-v7"),
                JsonCodec.shared().write(new GoalContext(kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView.Kind.CANDIDATE,"synthetic-candidate",2,"잠깐 쉬자",
                        new kr.mujoong.core.practicegoal.FourElements("기회","같은 반 친구","멈추기","잠깐 쉬자"),"seed:S1",null,null)));
        var saved=save("그래프 상황",details);var loaded=repo.require(saved.scenarioId());
        assertThat(loaded.scenario()).isEqualTo(saved);
        assertThat(loaded.scenario().generation().promptVersion()).isEqualTo("scenario-generation.v6");
        assertThat(loaded.scenario().scenes().getFirst().relation()).isEqualTo("같은 반 친구");
        expose(saved);
        assertThat(history()).singleElement().satisfies(e->{
            assertThat(e.skillRef()).isEqualTo("seed:S1");
            assertThat(e.graphContext()).isEqualTo(new kr.mujoong.core.knowledge.VariantPlanner.Context("놀이","교실","아이와 친구","CLASSMATE","SCHOOL_GROUP"));
        });
    }
    @Test void legacySaveStillHasAbsentExtensions(){var value=save("기존 상황",null);assertThat(repo.require(value.scenarioId()).scenario()).isEqualTo(value);assertThat(value.scenes()).isNull();}
    @Test void issuedIsNotExposureAndEmptyHistoryIsLegitimate(){save("아직 발급만",null);assertThat(history()).isEmpty();}
    @Test void exposedOldSessionsAreComparedAndFutureIsExcluded(){var old=save("과거 노출",null);expose(old);var future=save("미래 노출",null);repo.expose(future.scenarioId(),UUID.randomUUID(),DbTestSupport.EPOCH.plusSeconds(1));assertThat(history()).extracting(ScenarioRepository.ExposureText::scenarioText).containsExactly(old.scenarioText());}
    @Test void guardianAndChildBothScopeTheRead(){var value=save("아이 전용",null);expose(value);assertThat(repo.recentExposed(UUID.randomUUID(),c,DbTestSupport.EPOCH,timeout)).isEmpty();assertThat(repo.recentExposed(g,UUID.randomUUID(),DbTestSupport.EPOCH,timeout)).isEmpty();}
    @Test void tieOrderingAndThreeLimitMatchPostgresOrder(){for(int i=0;i<5;i++)expose(save("노출 "+i,null));var expected=db.jdbc().queryForList("select scenario_text from core.core_scenario where guardian_id=:g order by exposed_at desc,id desc limit 3",Map.of("g",g),String.class);assertThat(history()).extracting(ScenarioRepository.ExposureText::scenarioText).containsExactlyElementsOf(expected);}
    @Test void oversizedRowsFailRatherThanBeingTruncated(){var value=save("긴 노출",null);expose(value);db.jdbc().update("update core.core_scenario set trigger_script=:text where id=:id",Map.of("text","가".repeat(2001),"id",value.scenarioId()));assertThatThrownBy(this::history).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void historyDoesNotRecordExposureAndLocalTimeoutDoesNotLeak(){var value=save("미노출",null);String before=db.jdbc().getJdbcTemplate().queryForObject("show statement_timeout",String.class);history();assertThat(repo.require(value.scenarioId()).status()).isEqualTo("ISSUED");assertThat(db.jdbc().getJdbcTemplate().queryForObject("show statement_timeout",String.class)).isEqualTo(before);assertThatThrownBy(()->repo.recentExposed(g,c,DbTestSupport.EPOCH,Duration.ZERO)).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void databaseReadErrorPropagatesInsteadOfEmpty(){var broken=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(new org.springframework.jdbc.datasource.AbstractDataSource(){@Override public java.sql.Connection getConnection()throws java.sql.SQLException{throw new java.sql.SQLException("synthetic unavailable");}@Override public java.sql.Connection getConnection(String u,String p)throws java.sql.SQLException{return getConnection();}});assertThatThrownBy(()->new ScenarioRepository(broken,JsonCodec.shared()).recentExposed(g,c,DbTestSupport.EPOCH,timeout)).isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);}
    @Test void storedSidecarsDisappearWithTheParentRow(){var value=save("삭제 대상",new ScenarioRepository.Details("scenario-script.v1",List.of(ScenarioContractTest.scene()),null,null,"{}"));db.jdbc().update("delete from core.core_scenario where id=:id",Map.of("id",value.scenarioId()));assertThat(repo.find(value.scenarioId())).isEmpty();assertThat(new ScenarioRepository.ExposureText("이름을 남기지 않음","말").toString()).doesNotContain("이름");}
}
