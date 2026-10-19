package kr.mujoong.core.result;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practicegoal.*;
import kr.mujoong.core.web.support.WebFixtures;
import org.junit.jupiter.api.*;

@Tag("goal-result") @Tag("db")
class GoalResultResultApiIT extends WebTestSupport {
    List<UUID> generatedGuardians=new ArrayList<>();
    @AfterEach void removeGeneratedGoals(){for(UUID guardian:generatedGuardians)app.bean(GoalCandidateRepository.class).deleteGuardian(guardian);generatedGuardians.clear();}
    record Fixture(kr.mujoong.core.support.BusinessFixtures.Family family,UUID session,UUID result,GoalCandidateRepository.Version version){}
    Fixture prepare(){
        var f=family();generatedGuardians.add(f.guardianId());UUID session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var clock=app.clock();
        var states=app.bean(SessionResultStateRepository.class);states.insert(session,f.guardianId(),f.childId(),"FREE_TALK",true,clock.now());
        var job=app.bean(ResultJobRepository.class).submit("SESSION_RESULT",session,f.guardianId(),session.toString(),"RECONCILE",null,clock.now());states.attach(session,job.id(),1,clock.now());
        var draft=new GoalCandidateRepository.Draft("물어보기","합성 놀이 장면","함께 물어보기",new FourElements("놀이","친구","참여","말하기"),"NONE","내부 전용 사유",List.of(new GoalCandidateRepository.Evidence(1,"같이 블록 쌓자")),null);
        var result=app.bean(ActivityResultRepository.class).insert(session,job.id(),"FULL","[]","[]","[]",null,"{}",JsonCodec.shared().write(CandidateSeedDelivery.Pending.of(List.of(draft))),clock.now());
        db.jdbc().update("update core.core_session_result_state set job_status='DONE' where session_id=:s",Map.of("s",session));
        var version=db.inTx(t->app.bean(GoalCandidateRepository.class).register(f.childId(),session,result.id()+":0",draft,clock.now()));return new Fixture(f,session,result.id(),version);
    }
    @Test void generatedCandidatePublishesDisplayEvidenceOnly(){var f=prepare();var user=WebFixtures.guardianFor(app,f.family().guardianId());var response=checked("GET /api/sessions/{sessionId}/result",app.get("/api/sessions/"+f.session()+"/result").session(user.session()));assertThat(response.status()).isEqualTo(200);assertThat(response.body()).contains("displayEvidence","합성 놀이 장면").doesNotContain("reasonText","fourElements","skillRef","내부 전용 사유");}
    @Test void v2ApiPreservesRawTimingAndArchivedSelectedName(){
        var f=prepare();var goals=app.bean(GoalCandidateRepository.class);UUID selected=db.inTx(t->goals.select(f.family().guardianId(),f.version(),app.clock().now()));goals.delete(selected,app.clock().now(),app.clock().now().plusSeconds(30));UUID scene=UUID.randomUUID();
        String practice=JsonCodec.shared().write(Map.of("flowVersion",PracticeRecordV2.VERSION,"scenes",List.of(Map.of("sceneRunId",scene.toString(),"goalRef",selected.toString(),"sceneKind","VARIANT","startedAt",app.clock().now().toString()))));
        db.jdbc().update("update core.core_activity_record set activity_type='PRACTICE',practice_json=cast(:practice as jsonb) where session_id=:s",Map.of("s",f.session(),"practice",practice));
        app.bean(SceneJudgmentRepository.class).insert(f.result(),f.session(),new SceneJudgmentRepository.Scene(selected,1,3,"NEW_SCENARIO",null,"functional","AFTER_REASK",null,null,true,null,"DONE_IMMEDIATE",scene,PracticeRecordV2.VERSION,List.of("REASK_RESOLVED"),null),app.clock().now());
        var user=WebFixtures.guardianFor(app,f.family().guardianId());var response=checked("GET /api/sessions/{sessionId}/result",app.get("/api/sessions/"+f.session()+"/result").session(user.session()));assertThat(response.status()).isEqualTo(200);assertThat(response.body()).contains("AFTER_REASK","DONE_IMMEDIATE","물어보기").doesNotContain("differsFromLive","liveLabel","rationale");
    }
    @Test void newVersionHandleCanBeSelectedWithoutNewRequestFields(){var f=prepare();var user=WebFixtures.guardianFor(app,f.family().guardianId());var response=checked("POST /api/goal-selections",app.post("/api/goal-selections").session(user.session()).csrf(user.csrf()).json(JsonCodec.shared().write(Map.of("recommendationId",f.version().id()))));assertThat(response.status()).isEqualTo(201);assertThat(response.json()).containsEntry("displayName","물어보기");assertThat(response.body()).doesNotContain("fourElements","skillRef","reasonText");}
    @Test void sameHandleRetryReturnsSameSelection(){var f=prepare();var user=WebFixtures.guardianFor(app,f.family().guardianId());String body=JsonCodec.shared().write(Map.of("recommendationId",f.version().id()));var first=app.post("/api/goal-selections").session(user.session()).csrf(user.csrf()).json(body).send();var next=app.post("/api/goal-selections").session(user.session()).csrf(user.csrf()).json(body).send();assertThat(next.status()).isEqualTo(201);assertThat(next.json().get("goalSelectionId")).isEqualTo(first.json().get("goalSelectionId"));}
    @Test void anotherGuardianCannotSelectGeneratedHandle(){var f=prepare();var user=WebFixtures.guardianFor(app,family().guardianId());assertThat(app.post("/api/goal-selections").session(user.session()).csrf(user.csrf()).json(JsonCodec.shared().write(Map.of("recommendationId",f.version().id()))).send().status()).isEqualTo(404);}
    @Test void csrfStillRequiredForGeneratedGoals(){var f=prepare();var user=WebFixtures.guardianFor(app,f.family().guardianId());assertThat(app.post("/api/goal-selections").session(user.session()).json(JsonCodec.shared().write(Map.of("recommendationId",f.version().id()))).send().status()).isEqualTo(403);}
    @Test void replacementResultHidesPreviousCandidatesBeforeCallback(){var f=prepare();app.bean(ActivityResultRepository.class).insert(f.session(),app.bean(ResultJobRepository.class).find(db.jdbc().queryForObject("select current_job_id from core.core_session_result_state where session_id=:s",Map.of("s",f.session()),UUID.class)).orElseThrow().id(),"FULL","[]","[]","[]",null,"{}","{\"format\":\"goal-candidates-v1\",\"candidates\":[]}",app.clock().now());var user=WebFixtures.guardianFor(app,f.family().guardianId());var response=app.get("/api/sessions/"+f.session()+"/result").session(user.session()).send();assertThat(response.json().get("candidates")).isEqualTo(List.of());}
}
