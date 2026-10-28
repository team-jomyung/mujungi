package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.textai.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

@Tag("scenario-generation") class ScenarioGraphFallbackTest {
    final ScenarioGraphTest f=new ScenarioGraphTest();
    final ScenarioScriptProvider provider=mock(ScenarioScriptProvider.class);
    ScenarioScriptGenerator generator;
    ScenarioGraphContext.Session graph;
    VariantPlanner.Plan plan;
    ScenarioScript.Scene base,variant;
    @BeforeEach void setup(){f.setup();generator=new ScenarioScriptGenerator(provider,new ScenarioScriptValidation(new ForbiddenExpressionPolicy()),new TextAiProperties(Duration.ofSeconds(3),new TextAiProperties.Retry(1,Duration.ofMillis(250))),f.executor,f.clock::advance);var coordinates=new VariantPlanner.Context("놀이","교실","아이와 친구","FRIEND_CLOSE","FRIEND_PRIVATE");plan=new VariantPlanner().plan(f.seed,coordinates,List.of()).orElseThrow();base=scene(coordinates,"base","BASE");variant=scene(plan.context(),"variant","VARIANT");when(f.service.variantPlan(any(),any(),any(),any(),any())).thenReturn(Optional.empty());when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base,variant)),"fake"),ScenarioGenerationTest.generated(ScenarioGenerationTest.base()));when(provider.review(any())).thenReturn(ScenarioGenerationTest.approve());}
    @AfterEach void close(){f.close();}
    ScenarioScript.Scene scene(VariantPlanner.Context c,String key,String kind){return new ScenarioScript.Scene(key,kind,"놀이 중. "+ScenarioGenerationTest.OPPORTUNITY+" 이제 그만할래.","이제 그만할래.","쉬려 해.",List.of(),List.of(),c.relationship(),c.place(),c.topic(),new ScenarioScript.Preservation("seed:S1",f.seed.invariantSentence(),f.seed.preservationRule(),plan.grade(),plan.changedField(),c.participants(),c.situation()),null);}
    ScenarioScriptGenerator.Result run(){graph=f.open();var rules=new ScenarioScriptValidation.Rules("FRIEND_CLOSE",ScenarioGenerationTest.OPPORTUNITY,"잠깐 쉬자",Set.of(),null,x->false,graph);var fallback=ScenarioGenerationTest.base();fallback=new ScenarioScript.Scene(fallback.sceneKey(),fallback.sceneKind(),"새 기본 장면. "+fallback.sceneDescription(),fallback.opportunitySentence(),fallback.reexplanation(),fallback.focusPoints(),fallback.expressionExamples(),"FRIEND_CLOSE",fallback.place(),fallback.topic(),null,null);doReturn(ScenarioGenerationTest.generated(fallback)).when(provider).generate(argThat(c->c!=null&&Boolean.TRUE.equals(c.input().get("baseOnly"))));return generator.generate(new ScenarioScriptGenerator.Input(Map.of("structureContext",Map.of("skillRef","seed:S1")),Map.of(),rules,true,"{}"),new ScenarioDeadline(f.clock,f.clock.now().plusSeconds(15)));}
    void assertFreshFallback(){var result=run();assertThat(result.details().generation().graphContextUsed()).isFalse();assertThat(result.legacy().scenarioText()).startsWith("새 기본 장면.");assertThat(result.details().generation().calls()).isEqualTo(4);var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(provider,times(2)).generate(calls.capture());verify(provider,times(2)).review(calls.capture());assertThat(calls.getAllValues().get(1).input()).doesNotContainKeys("structureContext","variantPlan","baseToPreserve");assertThat(calls.getAllValues().getLast().input()).doesNotContainKeys("structureContext","variantPlan");}
    /** JIRA-283: 변형 계획이 비어도 첫 라운드 대본이 통째로 승인 · 서버 검사를 통과했으면 다시 만들지 않고 그대로 발급한다. */
    @Test void anApprovedFirstScriptIsIssuedWhenThePlanIsEmpty(){
        when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base)),"fake"));
        var result=run();
        assertThat(result.details().scenes()).extracting(ScenarioScript.Scene::sceneKind).containsExactly("BASE");
        assertThat(result.details().generation().graphContextUsed()).isTrue();
        assertThat(result.details().generation().calls()).isEqualTo(2);
        verify(provider,times(1)).generate(any());verify(provider,times(1)).review(any());}
    @Test void aFailedGraphReadStillRegeneratesWithoutGraphEvenIfTheFirstScriptWasApproved(){
        when(f.service.variantPlan(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("synthetic unavailable"));
        when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base)),"fake"));
        var result=run();
        assertThat(result.details().generation().graphContextUsed()).isFalse();
        assertThat(result.legacy().scenarioText()).startsWith("새 기본 장면.");}
    @Test void aRejectedFirstScriptStillRegeneratesWithoutGraphWhenThePlanIsEmpty(){
        when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base)),"fake"));
        when(provider.review(any())).thenReturn(ScenarioGenerationTest.reject("GOAL_MISMATCH"),ScenarioGenerationTest.approve());
        var result=run();
        assertThat(result.details().generation().graphContextUsed()).isFalse();
        assertThat(result.legacy().scenarioText()).startsWith("새 기본 장면.");}
    @Test void successfulSkillThenEmptyPlanRegeneratesWithoutGraph(){assertFreshFallback();}
    @Test void successfulSkillThenPlanFailureRegeneratesWithoutGraph(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("synthetic unavailable"));assertFreshFallback();}
    @Test void successfulSkillThenLatePlanRegeneratesWithoutGraph(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenAnswer(c->{f.clock.advance(Duration.ofSeconds(2));return Optional.of(plan);});assertFreshFallback();}
    @Test void fallbackStillRequiresItsOwnReview(){when(provider.review(any())).thenReturn(ScenarioGenerationTest.approve(),ScenarioGenerationTest.reject("AGE_MISMATCH"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void noContentSlotAfterBaseFailureDoesNotAddThirdGeneration(){var bad=new ScenarioScript.Scene("base","BASE","잘못된 장면","말","설명",List.of(),List.of(),"FRIEND_CLOSE","교실","놀이",null,null);when(provider.generate(any())).thenReturn(ScenarioGenerationTest.generated(bad),new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base,variant)),"fake"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());}
    @Test void fifteenSecondExpiryDuringPlanCannotRegenerate(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenAnswer(c->{f.clock.advance(Duration.ofSeconds(15));return Optional.empty();});assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider).generate(any());}
    /** JIRA-283: 변형 라운드 전에 통째로 승인 · 서버 검사를 통과한 대본은 변형 라운드가 실패해도 버리지 않고 그대로 발급한다. */
    void planReadyWithApprovedBaseFirst(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenReturn(Optional.of(plan));
        when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base)),"fake"),
                new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base,variant)),"fake"));}
    @Test void aRejectedVariantRoundIssuesTheScriptApprovedBeforeItWithoutRepair(){planReadyWithApprovedBaseFirst();
        when(provider.review(any())).thenReturn(ScenarioGenerationTest.approve(),ScenarioGenerationTest.reject("GOAL_MISMATCH"));
        var result=run();
        assertThat(result.details().scenes()).extracting(ScenarioScript.Scene::sceneKind).containsExactly("BASE");
        assertThat(result.details().generation().graphContextUsed()).isTrue();
        assertThat(result.details().generation().calls()).as("고쳐 생성 · 재검토 호출 없음").isEqualTo(4);
        verify(provider,times(2)).generate(any());verify(provider,times(2)).review(any());}
    @Test void aFailingVariantProviderStillIssuesTheApprovedScript(){planReadyWithApprovedBaseFirst();
        when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base)),"fake"))
                .thenThrow(new ScenarioScriptProvider.Failure(ScenarioScriptProvider.Failure.Kind.PERMANENT,Optional.empty()));
        var result=run();
        assertThat(result.details().scenes()).hasSize(1);
        verify(provider,times(1)).review(any());}
    @Test void anApprovedVariantRoundStillWins(){planReadyWithApprovedBaseFirst();
        var result=run();
        assertThat(result.details().scenes()).extracting(ScenarioScript.Scene::sceneKind).containsExactly("BASE","VARIANT");}
    @Test void theApprovedScriptIsNotIssuedAfterTheDeadline(){planReadyWithApprovedBaseFirst();
        when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base)),"fake"))
                .thenAnswer(c->{f.clock.advance(Duration.ofSeconds(15));return new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base,variant)),"fake");});
        assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void contentMismatchWithHealthyGraphKeepsSeparatelyApprovedBaseAndTrue(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenReturn(Optional.of(plan));var unchanged=scene(ScenarioGraphContext.coordinates(base),"variant","VARIANT");when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base,unchanged)),"fake"));var result=run();assertThat(result.details().generation().graphContextUsed()).isTrue();assertThat(result.details().scenes()).hasSize(1);assertThat(result.details().generation().calls()).isEqualTo(5);}
}
