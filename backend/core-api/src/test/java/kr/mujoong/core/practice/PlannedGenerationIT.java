package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.textai.*;
import org.junit.jupiter.api.*;

@Tag("scenario-generation") class PlannedGenerationIT {
    StructuredFixture f;ScenarioGraphTest graphFixture;
    final JsonCodec json=JsonCodec.shared();
    @BeforeEach void open(){
        f=new StructuredFixture();graphFixture=new ScenarioGraphTest();
        var seed=graphFixture.seed;
        var goal=new GoalContext(kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView.Kind.CANDIDATE,"plan-aware",1,"잠깐 쉬자",new kr.mujoong.core.practicegoal.FourElements(f.legacy.opportunity,"FRIEND_CLOSE","멈추기","잠깐 쉬자"),seed.seedId(),null,null);
        f.legacy.current.set(new PracticeSessionContextPort.SessionContext(f.legacy.session,f.legacy.guardian,f.legacy.child,true,List.of(new PracticeSessionContextPort.GoalSnapshot(f.legacy.goal,goal.carrierKey(),1,"FRIEND_CLOSE",goal))));
        when(f.graph.skillContext(any(),any(),any())).thenReturn(Optional.of(new ChildKnowledgeService.SkillContext(seed,"graph-v7")));
        when(f.graph.variantPlan(any(),any(),any(),any(),any())).thenAnswer(c->new VariantPlanner().plan(seed,c.getArgument(3),c.getArgument(4)));
        when(f.provider.generate(any())).thenAnswer(c->{
            ScenarioScriptProvider.Call call=c.getArgument(0);var input=call.input();
            if(!input.containsKey("structureContext"))return ScenarioGenerationTest.generated(scene(new VariantPlanner.Context("놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE"),null,"base","BASE"));
            var base=input.containsKey("baseToPreserve")?json.read(json.write(input.get("baseToPreserve")),ScenarioScript.Scene.class):scene(new VariantPlanner.Context("놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE"),null,"base","BASE");
            if(!input.containsKey("variantPlan"))return ScenarioGenerationTest.generated(base);
            var plan=json.read(json.write(input.get("variantPlan")),VariantPlanner.Plan.class);
            return ScenarioGenerationTest.generated(base,scene(plan.context(),plan,"variant","VARIANT"));
        });
    }
    ScenarioScript.Scene scene(VariantPlanner.Context c,VariantPlanner.Plan p,String key,String kind){
        var seed=graphFixture.seed;var preservation=new ScenarioScript.Preservation(seed.seedId(),seed.invariantSentence(),seed.preservationRule(),p==null?"SAME_CONTEXT":p.grade(),p==null?"none":p.changedField(),c.participants(),c.situation());
        return new ScenarioScript.Scene(key,kind,"놀이 중. "+f.legacy.opportunity+" 이제 그만할래.","이제 그만할래.","친구가 쉬려 해.",List.of(),List.of(),c.relationship(),c.place(),c.topic(),preservation,null);
    }
    @AfterEach void close(){graphFixture.close();f.close();}
    @Test void variantCanOnlyBeBuiltFromPlanActuallyDeliveredToCorrectionAndReview(){
        var result=f.legacy.service.generate(f.legacy.original());assertThat(result.scenes()).hasSize(2);assertThat(result.generation().calls()).isEqualTo(4);
        var calls=org.mockito.ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(f.provider,times(2)).generate(calls.capture());
        assertThat(calls.getAllValues().getFirst().input()).containsEntry("baseOnly",true).doesNotContainKey("variantPlan");
        var corrected=calls.getAllValues().getLast().input();assertThat(corrected).containsKeys("variantPlan","baseToPreserve");
        verify(f.provider,times(2)).review(calls.capture());assertThat(calls.getAllValues().getLast().input().get("variantPlan")).isEqualTo(corrected.get("variantPlan"));
        assertThat(json.write(corrected.get("variantPlan"))).doesNotContain(f.legacy.guardian.toString(),f.legacy.child.toString(),"sourceActivityId");
        verify(f.graph).variantPlan(any(),any(),any(),eq(new VariantPlanner.Context("놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE")),any());
    }
    @Test void actualGraphVersionSurvivesDatabaseAndFirstPackage(){
        var result=f.legacy.service.generate(f.legacy.original());assertThat(result.generation().kgVersion()).isEqualTo("graph-v7");
        var stored=f.legacy.repository.require(result.scenarioId()).scenario();assertThat(stored.generation().kgVersion()).isEqualTo("graph-v7");
        var goal=f.legacy.current.get().goals().getFirst();var frozen=new kr.mujoong.core.practice.start.FrozenGoal(goal.goalSelectionId(),"목표",goal.behaviorKey(),1,goal.goalContext().criteria(),1,1,1,false,1,1,1,goal.goalContext());
        var snapshot=new kr.mujoong.core.practice.start.PreparedPracticePackageMapper.PreparedSnapshot(f.legacy.session,UUID.randomUUID(),List.of(frozen),stored);
        var first=new kr.mujoong.core.practice.start.PreparedPracticePackageMapper().map(snapshot).firstScenario();
        assertThat(json.write(first)).contains("graph-v7");assertThat(kr.mujoong.core.web.support.WebFixtures.schemaErrors("practice-scenario.schema.json",json.write(first))).isEmpty();
    }
    @Test void limitedRemainingTimeIssuesOnlyAlreadyApprovedGraphBase() {
        doAnswer(c->{f.legacy.clock.advance(java.time.Duration.ofSeconds(5));return ScenarioGenerationTest.generated(scene(new VariantPlanner.Context("놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE"),null,"base","BASE"));}).when(f.provider).generate(any());
        doAnswer(c->{f.legacy.clock.advance(java.time.Duration.ofSeconds(1));return ScenarioGenerationTest.approve();}).when(f.provider).review(any());
        var result=f.legacy.service.generate(f.legacy.original());
        assertThat(result.scenes()).hasSize(1);
        assertThat(result.generation().graphContextUsed()).isTrue();
        assertThat(result.generation().kgVersion()).isEqualTo("graph-v7");
        assertThat(result.selfReview().verdict()).isEqualTo("APPROVE");
        verify(f.provider,times(1)).generate(any());verify(f.provider,times(1)).review(any());
    }
    @Test void limitedTimeNeverIssuesRejectedGraphBase() {
        doAnswer(c->{f.legacy.clock.advance(java.time.Duration.ofSeconds(5));return ScenarioGenerationTest.generated(scene(new VariantPlanner.Context("놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE"),null,"base","BASE"));}).when(f.provider).generate(any());
        doAnswer(c->{f.legacy.clock.advance(java.time.Duration.ofSeconds(1));return ScenarioGenerationTest.reject("FORBIDDEN_CONTENT");}).when(f.provider).review(any());
        assertThatThrownBy(()->f.legacy.service.generate(f.legacy.original())).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);
        assertThat(f.legacy.db.jdbc().queryForObject("select count(*) from core.core_scenario where guardian_id=:g",Map.of("g",f.legacy.guardian),Integer.class)).isZero();
    }
    /**
     * JIRA-283(2026-10-27 사람 결정): 그래프는 정상이고 변형 계획만 비었으면, 승인 · 서버 검사를 통과한 첫 대본을 다시 만들지 않고 그래프 판과 함께
     * 발급한다. 이전 규칙(그래프 없이 새로 만들기 · 판 null)은 호출 2번이 더 들어 실키에서 30초 마감을 자주 넘겼다. 그래프 읽기 실패는 아래
     * failedPlanCannotMakeInputIndependentVariantIssuable이 계속 지킨다(그래프 없이 새로 만든다).
     */
    @Test void missingPlanIssuesTheApprovedGraphBaseWithItsVersion(){
        doReturn(Optional.empty()).when(f.graph).variantPlan(any(),any(),any(),any(),any());
        var result=f.legacy.service.generate(f.legacy.original());assertThat(result.scenes()).hasSize(1);assertThat(result.generation().graphContextUsed()).isTrue();assertThat(result.generation().kgVersion()).isEqualTo("graph-v7");
        verify(f.provider,times(1)).generate(any());
    }
    @Test void failedPlanCannotMakeInputIndependentVariantIssuable(){
        doThrow(new IllegalStateException("synthetic graph unavailable")).when(f.graph).variantPlan(any(),any(),any(),any(),any());
        var result=f.legacy.service.generate(f.legacy.original());assertThat(result.generation().graphContextUsed()).isFalse();verify(f.provider,times(2)).generate(any());
    }
    @Test void rejectedBaseChangingCoordinatesCannotUseUndeliveredNewPlanForVariantReview(){
        when(f.provider.review(any())).thenReturn(ScenarioGenerationTest.reject("AGE_MISMATCH"),ScenarioGenerationTest.approve());
        doAnswer(c->{ScenarioScriptProvider.Call call=c.getArgument(0);var input=call.input();
            if(!input.containsKey("variantPlan"))return ScenarioGenerationTest.generated(scene(new VariantPlanner.Context("놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE"),null,"base","BASE"));
            var received=json.read(json.write(input.get("variantPlan")),VariantPlanner.Plan.class);
            var changedBase=scene(new VariantPlanner.Context("그림","도서관","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE"),null,"base","BASE");
            return ScenarioGenerationTest.generated(changedBase,scene(received.context(),received,"variant","VARIANT"));
        }).when(f.provider).generate(any());
        var result=f.legacy.service.generate(f.legacy.original());assertThat(result.scenes()).hasSize(1);assertThat(result.generation().calls()).isEqualTo(5);
        var generated=org.mockito.ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(f.provider,times(2)).generate(generated.capture());
        Object delivered=generated.getAllValues().getLast().input().get("variantPlan");
        var reviews=org.mockito.ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(f.provider,times(3)).review(reviews.capture());
        assertThat(reviews.getAllValues()).allSatisfy(call->assertThat(call.input().get("variantPlan")).isEqualTo(delivered));
        verify(f.graph,times(2)).variantPlan(any(),any(),any(),any(),any());
    }
}
