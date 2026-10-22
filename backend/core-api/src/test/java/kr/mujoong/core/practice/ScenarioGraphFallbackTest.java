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
    ScenarioScript.Scene scene(VariantPlanner.Context c,String key,String kind){return new ScenarioScript.Scene(key,kind,"놀이 중. "+ScenarioGenerationTest.OPPORTUNITY+" 이제 그만할래.","BASE".equals(kind)?"휴, 오래 했다.":"","BASE".equals(kind)?"이제 그만할래.":"나 이제 그만 놀래.","쉬려 해.",List.of(),List.of(),c.relationship(),c.place(),c.topic(),new ScenarioScript.Preservation("seed:S1",f.seed.invariantSentence(),f.seed.preservationRule(),plan.grade(),plan.changedField(),c.participants(),c.situation(),c.relationship()),null);}
    ScenarioScriptGenerator.Result run(){graph=f.open();var rules=new ScenarioScriptValidation.Rules("FRIEND_CLOSE",ScenarioGenerationTest.OPPORTUNITY,"잠깐 쉬자",Set.of(),null,x->false,graph);var fallback=ScenarioGenerationTest.base();fallback=new ScenarioScript.Scene(fallback.sceneKey(),fallback.sceneKind(),"새 기본 장면. "+fallback.sceneDescription(),fallback.partnerOpener(),fallback.opportunitySentence(),fallback.reexplanation(),fallback.focusPoints(),fallback.expressionExamples(),"FRIEND_CLOSE",fallback.place(),fallback.topic(),null,null);doReturn(ScenarioGenerationTest.generated(fallback)).when(provider).generate(argThat(c->c!=null&&Boolean.TRUE.equals(c.input().get("baseOnly"))));return generator.generate(new ScenarioScriptGenerator.Input(Map.of("structureContext",Map.of("skillRef","seed:S1")),Map.of(),rules,true,"{}"),new ScenarioDeadline(f.clock,f.clock.now().plusSeconds(15)));}
    void assertFreshFallback(){var result=run();assertThat(result.details().generation().graphContextUsed()).isFalse();assertThat(result.legacy().scenarioText()).startsWith("새 기본 장면.");assertThat(result.details().generation().calls()).isEqualTo(4);var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(provider,times(2)).generate(calls.capture());verify(provider,times(2)).review(calls.capture());assertThat(calls.getAllValues().get(1).input()).doesNotContainKeys("structureContext","variantPlan","baseToPreserve");assertThat(calls.getAllValues().getLast().input()).doesNotContainKeys("structureContext","variantPlan");}
    @Test void successfulSkillThenEmptyPlanRegeneratesWithoutGraph(){assertFreshFallback();}
    @Test void successfulSkillThenPlanFailureRegeneratesWithoutGraph(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("synthetic unavailable"));assertFreshFallback();}
    @Test void successfulSkillThenLatePlanRegeneratesWithoutGraph(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenAnswer(c->{f.clock.advance(Duration.ofSeconds(2));return Optional.of(plan);});assertFreshFallback();}
    @Test void fallbackStillRequiresItsOwnReview(){when(provider.review(any())).thenReturn(ScenarioGenerationTest.approve(),ScenarioGenerationTest.reject("AGE_MISMATCH"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void noContentSlotAfterBaseFailureDoesNotAddThirdGeneration(){var bad=new ScenarioScript.Scene("base","BASE","잘못된 장면","말","설명",List.of(),List.of(),"FRIEND_CLOSE","교실","놀이",null,null);when(provider.generate(any())).thenReturn(ScenarioGenerationTest.generated(bad),new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base,variant)),"fake"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());}
    @Test void fifteenSecondExpiryDuringPlanCannotRegenerate(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenAnswer(c->{f.clock.advance(Duration.ofSeconds(15));return Optional.empty();});assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider).generate(any());}
    /**
     * 실측 F1(docs/practice-dialogue-standard.md 14절): 단독 승인된 BASE를 다음 생성이 고쳐 쓰면 검토 전에 되돌리고, 변형이 거절되면 같은 BASE를 추가 검토
     * 없이 발급한다. 사유 코드가 그 길을 그대로 남기고 저장 기록에도 발급 경로가 남는다.
     */
    @Test void notesTellThatTheBaseWasPutBackAndIssuedAloneWithoutAnotherReview(){
        when(f.service.variantPlan(any(),any(),any(),any(),any())).thenReturn(Optional.of(plan));
        var rewritten=new ScenarioScript.Scene(base.sceneKey(),base.sceneKind(),base.sceneDescription(),base.partnerOpener(),base.opportunitySentence(),base.reexplanation(),List.of("고쳐 쓴 요점"),base.expressionExamples(),base.relation(),base.place(),base.topic(),base.preservation(),null);
        when(provider.generate(any())).thenReturn(ScenarioGenerationTest.generated(base),ScenarioGenerationTest.generated(rewritten,variant));
        when(provider.review(any())).thenReturn(ScenarioGenerationTest.approve(),ScenarioGenerationTest.reject("UNCHANGED_VARIANT"));
        var result=run();
        assertThat(result.details().scenes()).hasSize(1);
        assertThat(result.details().scenes().getFirst().focusPoints()).isEqualTo(base.focusPoints());
        assertThat(result.notes()).containsExactly(ScenarioScriptGenerator.Note.BASE_APPROVED_ALONE,ScenarioScriptGenerator.Note.BASE_REPLACED,
                ScenarioScriptGenerator.Note.VARIANT_REJECTED,ScenarioScriptGenerator.Note.REVIEW_REUSED,ScenarioScriptGenerator.Note.BASE_ONLY_EARLIER_REVIEW);
        assertThat(result.details().generation().basePreservation()).isEqualTo("BASE_ONLY_EARLIER_REVIEW");
        assertThat(result.details().generation().calls()).isEqualTo(4);
        verify(provider,times(2)).review(any());
    }
    /** 변형까지 승인되면 되돌린 BASE와 생성된 VARIANT가 함께 발급된다(검토는 되돌린 모습을 본다). */
    @Test void aPutBackBaseIsReviewedWithTheGeneratedVariantAndIssuedTogether(){
        when(f.service.variantPlan(any(),any(),any(),any(),any())).thenReturn(Optional.of(plan));
        var rewritten=new ScenarioScript.Scene(base.sceneKey(),base.sceneKind(),base.sceneDescription(),base.partnerOpener(),base.opportunitySentence(),base.reexplanation(),List.of("고쳐 쓴 요점"),base.expressionExamples(),base.relation(),base.place(),base.topic(),base.preservation(),null);
        when(provider.generate(any())).thenReturn(ScenarioGenerationTest.generated(base),ScenarioGenerationTest.generated(rewritten,variant));
        var result=run();
        assertThat(result.details().scenes()).extracting(ScenarioScript.Scene::sceneKind).containsExactly("BASE","VARIANT");
        assertThat(result.notes()).containsExactly(ScenarioScriptGenerator.Note.BASE_APPROVED_ALONE,ScenarioScriptGenerator.Note.BASE_REPLACED);
        assertThat(result.details().generation().basePreservation()).isEqualTo("BASE_REPLACED");
        var reviews=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(provider,times(2)).review(reviews.capture());
        assertThat(((ScenarioScript)reviews.getAllValues().getLast().input().get("script")).scenes().getFirst()).isEqualTo(base);
    }
    @Test void contentMismatchWithHealthyGraphKeepsSeparatelyApprovedBaseAndTrue(){when(f.service.variantPlan(any(),any(),any(),any(),any())).thenReturn(Optional.of(plan));var unchanged=scene(ScenarioGraphContext.coordinates(base),"variant","VARIANT");when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base,unchanged)),"fake"));var result=run();assertThat(result.details().generation().graphContextUsed()).isTrue();assertThat(result.details().scenes()).hasSize(1);assertThat(result.details().generation().calls()).isEqualTo(5);}
}
