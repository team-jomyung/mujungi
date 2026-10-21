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
        var seed=graphFixture.seed;var preservation=new ScenarioScript.Preservation(seed.seedId(),seed.invariantSentence(),seed.preservationRule(),p==null?"SAME_CONTEXT":p.grade(),p==null?"none":p.changedField(),c.participants(),c.situation(),c.relationship());
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
    @Test void missingPlanGetsFreshStructureFreeBaseAndNullVersion(){
        doReturn(Optional.empty()).when(f.graph).variantPlan(any(),any(),any(),any(),any());
        var result=f.legacy.service.generate(f.legacy.original());assertThat(result.scenes()).hasSize(1);assertThat(result.generation().graphContextUsed()).isFalse();assertThat(result.generation().kgVersion()).isNull();
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
    static final VariantPlanner.Context ALLOWED=new VariantPlanner.Context("놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE");
    /** 실제 Luna 후보 목표처럼 상대를 자유 한국어로 둔다(FourElements.partner · GoalSnapshot.relationType). */
    void freeKoreanGoal(){
        var seed=graphFixture.seed;
        var goal=new GoalContext(kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView.Kind.CANDIDATE,"plan-aware",1,"잠깐 쉬자",new kr.mujoong.core.practicegoal.FourElements(f.legacy.opportunity,"같은 반 친구","멈추기","잠깐 쉬자"),seed.seedId(),null,null);
        f.legacy.current.set(new PracticeSessionContextPort.SessionContext(f.legacy.session,f.legacy.guardian,f.legacy.child,true,List.of(new PracticeSessionContextPort.GoalSnapshot(f.legacy.goal,goal.carrierKey(),1,"같은 반 친구",goal))));
    }
    /** 모델이 v3 규칙대로 답하는 가짜 공급자: relation은 목표 상대 그대로, 좌표는 허용 어휘와 받은 변형 계획에서. */
    void answerLikeV3(ScenarioScript.Scene firstBase){
        doAnswer(c->{ScenarioScriptProvider.Call call=c.getArgument(0);var input=call.input();
            var base=input.containsKey("baseToPreserve")?json.read(json.write(input.get("baseToPreserve")),ScenarioScript.Scene.class):firstBase;
            if(!input.containsKey("variantPlan"))return ScenarioGenerationTest.generated(base);
            var plan=json.read(json.write(input.get("variantPlan")),VariantPlanner.Plan.class);
            return ScenarioGenerationTest.generated(base,relational(scene(plan.context(),plan,"variant","VARIANT")));
        }).when(f.provider).generate(any());
    }
    @Test void freeKoreanPartnerWithAllowedCoordinatesYieldsApprovedVariant(){
        freeKoreanGoal();answerLikeV3(relational(scene(ALLOWED,null,"base","BASE")));
        var result=f.legacy.service.generate(f.legacy.original());
        assertThat(result.scenes()).hasSize(2);assertThat(result.generation().calls()).isEqualTo(4);assertThat(result.generation().graphContextUsed()).isTrue();
        assertThat(result.generation().promptVersion()).isEqualTo("scenario-generation.v3");
        assertThat(result.scenes()).allSatisfy(s->assertThat(s.relation()).isEqualTo("같은 반 친구"));
        assertThat(result.scenes().getLast().preservation().relationship()).isEqualTo("FRIEND_CLOSE");
        verify(f.graph).variantPlan(any(),any(),any(),eq(ALLOWED),any());
    }
    @Test void structureContextCarriesSortedVocabularyThatSurvivesSanitizing(){
        freeKoreanGoal();answerLikeV3(relational(scene(ALLOWED,null,"base","BASE")));
        f.legacy.service.generate(f.legacy.original());
        var calls=org.mockito.ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(f.provider,times(2)).generate(calls.capture());
        @SuppressWarnings("unchecked") var context=(Map<Object,Object>)calls.getAllValues().getFirst().input().get("structureContext");
        assertThat(context.keySet()).containsExactly("skillRef","signature","invariantSentence","preservationRule","relationship","situation",
                "allowedTopics","allowedPlaces","allowedParticipants","allowedRelationships","allowedSituations");
        assertThat(context.get("allowedTopics")).isEqualTo(List.of("그림","놀이","독서"));
        assertThat(context.get("allowedPlaces")).isEqualTo(List.of("교실","도서관","운동장"));
        assertThat(context.get("allowedParticipants")).isEqualTo(List.of("아이와 또래","아이와 모둠원","아이와 친구"));
        assertThat(context.get("allowedRelationships")).isEqualTo(KnowledgeSeed.RELATIONSHIPS.stream().sorted().toList());
        assertThat(context.get("allowedSituations")).isEqualTo(KnowledgeSeed.SITUATIONS.stream().sorted().toList());
        assertThat(context.keySet()).noneMatch(k->kr.mujoong.core.luna.LunaInputSanitizer.FORBIDDEN_KEYS.contains(String.valueOf(k).toLowerCase(Locale.ROOT).replace("_","").replace("-","")));
    }
    @Test void oldExposureWithoutRelationshipCoordinateIsLeftOutInsteadOfDroppingTheGraph(){
        freeKoreanGoal();answerLikeV3(relational(scene(ALLOWED,null,"base","BASE")));
        var old=new ScenarioRepository.ExposureText("예전 장면","예전 기회",graphFixture.seed.seedId(),new VariantPlanner.Context("놀이","교실","아이와 친구",null,"FRIEND_PRIVATE"));
        f.legacy.repository=spy(f.legacy.repository);doReturn(List.of(old)).when(f.legacy.repository).recentExposed(any(),any(),any(),any());f.connectService();
        var result=f.legacy.service.generate(f.legacy.original());
        assertThat(result.scenes()).hasSize(2);assertThat(result.generation().graphContextUsed()).isTrue();
        verify(f.graph).variantPlan(any(),any(),any(),eq(ALLOWED),eq(List.of()));
    }
    @Test void baseWithoutPreservationIsCorrectedOnceAndIssuesOnlyTheApprovedBase(){
        freeKoreanGoal();var missing=relational(scene(ALLOWED,null,"base","BASE"));
        var bare=new ScenarioScript.Scene(missing.sceneKey(),missing.sceneKind(),missing.sceneDescription(),missing.opportunitySentence(),missing.reexplanation(),List.of(),List.of(),missing.relation(),missing.place(),missing.topic(),null,null);
        doReturn(ScenarioGenerationTest.generated(bare),ScenarioGenerationTest.generated(missing)).when(f.provider).generate(any());
        var result=f.legacy.service.generate(f.legacy.original());
        assertThat(result.scenes()).hasSize(1);assertThat(result.generation().calls()).isEqualTo(4);assertThat(result.generation().graphContextUsed()).isTrue();
    }
    @Test void baseWithoutPreservationTwiceFailsTheGraphPathStart(){
        freeKoreanGoal();var missing=relational(scene(ALLOWED,null,"base","BASE"));
        var bare=new ScenarioScript.Scene(missing.sceneKey(),missing.sceneKind(),missing.sceneDescription(),missing.opportunitySentence(),missing.reexplanation(),List.of(),List.of(),missing.relation(),missing.place(),missing.topic(),null,null);
        doReturn(ScenarioGenerationTest.generated(bare)).when(f.provider).generate(any());
        assertThatThrownBy(()->f.legacy.service.generate(f.legacy.original())).isInstanceOfSatisfying(kr.mujoong.core.common.error.CoreApiException.class,
                e->assertThat(e.code()).isEqualTo(kr.mujoong.contract.error.ErrorCode.SCENARIO_GEN_FAILED));
        verify(f.provider,times(2)).generate(any());
    }
    /** 계획 대역을 덮어쓰지 않는다: 기본 대역이 부르는 실제 VariantPlanner의 어휘 검사가 '블록 놀이'를 거절해야 이 결과가 나온다. */
    @Test void baseOutsideVocabularyFallsBackToGraphFreeBase(){
        var outside=new VariantPlanner.Context("블록 놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE");
        freeKoreanGoal();answerLikeV3(relational(scene(outside,null,"base","BASE")));
        var result=f.legacy.service.generate(f.legacy.original());
        assertThat(result.scenes()).hasSize(1);assertThat(result.generation().graphContextUsed()).isFalse();assertThat(result.generation().calls()).isEqualTo(4);
        verify(f.graph).variantPlan(any(),any(),any(),eq(outside),any());
        assertThat(new VariantPlanner().plan(graphFixture.seed,outside,List.of())).isEmpty();
    }
    /** 목표 상대(scene.relation)는 Luna가 쓴 자유 한국어 그대로 두고, 관계 좌표만 열거값으로 둔다. */
    ScenarioScript.Scene relational(ScenarioScript.Scene s){
        return new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),s.focusPoints(),s.expressionExamples(),"같은 반 친구",s.place(),s.topic(),s.preservation(),null);
    }
}
