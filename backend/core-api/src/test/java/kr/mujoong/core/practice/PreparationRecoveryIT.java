package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.start.*;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import org.junit.jupiter.api.*;

@Tag("scenario-generation") class PreparationRecoveryIT {
    StructuredFixture f;PracticeStartService start;PracticePreparationRepository preparations;PracticeStartRequest request;
    @BeforeEach void open(){
        f=new StructuredFixture();preparations=new PracticePreparationRepository(f.legacy.db.jdbc(),JsonCodec.shared());
        var goals=mock(PracticeGoalRepository.class);var activities=mock(kr.mujoong.core.activity.StartActivityService.class);
        when(goals.find(f.legacy.guardian,f.legacy.goal)).thenReturn(Optional.of(new GoalSelection(f.legacy.goal,f.legacy.guardian,f.legacy.child,UUID.randomUUID(),UUID.randomUUID(),"ACCEPT_STOP",1,"목표",true,1,"ACTIVE",f.legacy.clock.now(),null,null)));
        start=new PracticeStartService(f.accounts,f.consent,goals,f.legacy.knowledge,preparations,f.legacy.repository,f.legacy.service,activities,f.legacy.db.tx(),f.legacy.clock,new ScenarioConfiguration.ScenarioLimits(Duration.ofSeconds(15)));
        request=new PracticeStartRequest(UUID.randomUUID(),List.of(f.legacy.goal));
    }
    @AfterEach void close(){f.legacy.db.jdbc().update("delete from core.core_practice_preparation where guardian_id=:g",Map.of("g",f.legacy.guardian));f.close();}
    void fails(){f.legacy.error(()->start.prepare(f.legacy.guardian,request),kr.mujoong.contract.error.ErrorCode.SCENARIO_GEN_FAILED);}
    void recovered(){doReturn(f.generated("독서")).when(f.provider).generate(any());var result=start.prepare(f.legacy.guardian,request);assertThat(preparations.find(f.legacy.guardian,request.idempotencyKey()).orElseThrow().status()).isEqualTo("READY");assertThat(result.firstScenario().scenarioText()).contains("독서");}
    @Test void exhaustedTechnicalRetryDoesNotPermanentlyFailRealPreparation(){when(f.provider.generate(any())).thenThrow(ScenarioGenerationTest.failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT));fails();assertThat(preparations.find(f.legacy.guardian,request.idempotencyKey()).orElseThrow().status()).isEqualTo("PREPARING");recovered();}
    @Test void deadlineFailureCanRecoverWithSameKeyAndNewLease(){when(f.provider.generate(any())).thenAnswer(c->{f.legacy.clock.advance(Duration.ofSeconds(15));return f.generated("그림");});fails();recovered();}
    @Test void duplicateContentMissCanRecoverSamePreparationKey(){f.legacy.service.generate(f.legacy.original());fails();assertThat(preparations.find(f.legacy.guardian,request.idempotencyKey()).orElseThrow().status()).isEqualTo("PREPARING");recovered();}
    @Test void twiceRejectedContentRemainsPermanentlyFailed(){when(f.provider.review(any())).thenReturn(ScenarioGenerationTest.reject("AGE_MISMATCH"));fails();assertThat(preparations.find(f.legacy.guardian,request.idempotencyKey()).orElseThrow().status()).isEqualTo("FAILED");clearInvocations(f.provider);fails();verifyNoInteractions(f.provider);}
    @Test void graphFailureAfterContentSlotDoesNotPermanentlyFailPreparation(){
        var reader=mock(kr.mujoong.core.practicegoal.SavedGoalReader.class);
        var view=new kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView(f.legacy.goal,f.legacy.child,kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView.Kind.CANDIDATE,"graph-retry",1,"잠깐 쉬자",new kr.mujoong.core.practicegoal.FourElements(f.legacy.opportunity,"PEER","멈추기","잠깐 쉬자"),"seed:S1",null,null);
        when(reader.find(f.legacy.child,f.legacy.goal)).thenReturn(Optional.of(view));
        var goals=mock(PracticeGoalRepository.class);
        when(goals.preparationMetadata(any(),any(),any())).thenReturn(Optional.of(new kr.mujoong.core.practicegoal.PracticeGoalRepository.PreparationMetadata("목표",false,1)));
        start=new PracticeStartService(f.accounts,f.consent,goals,f.legacy.knowledge,preparations,f.legacy.repository,f.legacy.service,mock(kr.mujoong.core.activity.StartActivityService.class),f.legacy.db.tx(),f.legacy.clock,new ScenarioConfiguration.ScenarioLimits(Duration.ofSeconds(15)));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(start,"connectSavedGoals",reader,f.privacy,new kr.mujoong.core.luna.LunaInputSanitizer(JsonCodec.shared()));
        var graph=new ScenarioGraphTest();try{
            when(f.graph.skillContext(any(),any(),any())).thenReturn(Optional.of(new kr.mujoong.core.knowledge.ChildKnowledgeService.SkillContext(graph.seed,"v1")));
            when(f.graph.variantPlan(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("synthetic unavailable"));
            var base=f.generated("그림").script().scenes().getFirst();
            var preserved=new kr.mujoong.core.textai.ScenarioScript.Scene(base.sceneKey(),base.sceneKind(),base.sceneDescription(),base.partnerOpener(),base.opportunitySentence(),base.reexplanation(),base.focusPoints(),base.expressionExamples(),base.relation(),base.place(),base.topic(),new kr.mujoong.core.textai.ScenarioScript.Preservation("seed:S1",graph.seed.invariantSentence(),graph.seed.preservationRule(),"SAME_CONTEXT","none","아이와 친구","FRIEND_PRIVATE","FRIEND_CLOSE"),null);
            var invalid=new kr.mujoong.core.textai.ScenarioScript.Scene("base","BASE","기회 없는 장면","말","설명",List.of(),List.of(),"PEER","교실","놀이",null,null);
            doReturn(ScenarioGenerationTest.generated(invalid),ScenarioGenerationTest.generated(preserved)).when(f.provider).generate(any());
            fails();assertThat(preparations.find(f.legacy.guardian,request.idempotencyKey()).orElseThrow().status()).isEqualTo("PREPARING");
            when(f.graph.skillContext(any(),any(),any())).thenReturn(Optional.empty());recovered();
        }finally{graph.close();}
    }
}
