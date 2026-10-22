package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.luna.adapter.LunaTextAiProvider;
import kr.mujoong.core.textai.ScenarioScriptProvider;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

@Tag("scenario-generation")
class ScenarioLunaProviderTest {
    CoreLunaClient client;LunaTextAiProvider provider;MutableClock clock;
    @BeforeEach void open(){client=mock(CoreLunaClient.class);clock=new MutableClock();provider=new LunaTextAiProvider(client,clock,JsonCodec.shared());}
    ScenarioScriptProvider.Call call(){return new ScenarioScriptProvider.Call(Map.of("age",8),Map.of("합성아이","아이"),clock.now().plusSeconds(15));}
    LunaResult output(String text){return new LunaResult(text,"fake","scenario-generation.v2",Duration.ZERO,1,false);}
    @Test void generationAndReviewUseDifferentCallsSchemasAndPrompts(){when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")),output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));assertThat(provider.generate(call()).script().scenes()).hasSize(1);assertThat(provider.review(call()).review().approved()).isTrue();var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client,times(2)).call(captor.capture());var values=captor.getAllValues();assertThat(values.get(0).purpose()).isEqualTo(LunaPurpose.SCENARIO_GENERATION);assertThat(values.get(1).purpose()).isEqualTo(LunaPurpose.SCENARIO_REVIEW);assertThat(values.get(0).promptVersion()).isEqualTo("scenario-generation.v5").isEqualTo(ScenarioScriptProvider.GENERATION_PROMPT_VERSION);assertThat(values.get(0).instructions().lines().findFirst()).contains("scenario-generation.v5");assertThat(values.get(1).promptVersion()).isEqualTo("scenario-review.v3").isEqualTo(ScenarioScriptProvider.REVIEW_PROMPT_VERSION);assertThat(values.get(1).instructions().lines().findFirst()).contains("scenario-review.v3");assertThat(values).allMatch(v->v.outputMode()==LunaRequest.OutputMode.CONTRACT_SINGLE_CALL);}
    /** 실제 검토가 도움 자료를 정답 선제 제시로 읽고 BASE 단독 검토에 변형 사유를 내던 오판을 막으면서, 동의 없이 들리는 다시 설명은 처음 들리는 필드와 같은 기준으로 보는지 문장으로 고정한다. */
    @Test void reviewV3TellsWhichFieldsTheChildHearsWithoutConsentAndWhenVariantReasonsApply(){
        when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));
        provider.review(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        String instructions=captor.getValue().instructions();
        assertThat(instructions).contains("도움 동의 없이 아이에게 들리는 것은 sceneDescription·opportunitySentence·reexplanation 세 필드다")
                .contains("focusPoints와 expressionExamples는 아이가 도움에 동의한 뒤에만")
                .contains("ANSWER_INCLUDED와 EARLY_CUE는 동의 없이 들리는 세 필드를 같은 기준으로 본다")
                .contains("목표 표현이나 거의 같은 문장")
                .contains("해야 할 행동·말해야 할 내용을 설명해 버리면 실패다")
                .contains("VARIANT 장면이 없는 대본에는 UNCHANGED_VARIANT·TRANSFER_MISMATCH를 쓰지 않는다")
                .doesNotContain("scenario-review.v1").doesNotContain("scenario-review.v2")
                .as("v2의 다시 설명 예외(목표 표현을 그대로 담았을 때만)는 없어야 한다").doesNotContain("그대로 담았을 때만");
        assertThat(captor.getValue().input()).isEqualTo(Map.of("age",8));
    }
    /** 실측 EARLY_CUE("친구는 공룡 이름을 잘 모르고…")처럼 서술자가 상대의 지식을 설명한 문장이 '장면 사실' 허용으로 빠지지 않게 허용 범위를 좁혀 적는지 고정한다. */
    @Test void reviewV3NarrowsTheAllowedSceneFactsAndCallsNarratedInnerStatesACue(){
        when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));
        provider.review(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        assertThat(captor.getValue().instructions())
                .contains("서술자가 상대의 지식·감정·의도를 직접 설명하는 것은 장면 사실이 아니라 핵심 단서 설명이다")
                .contains("눈에 보이는 장면 사실(장소·물건·상대의 대사·행동)")
                .contains("opportunityCondition을 글자 그대로 옮긴 부분뿐이다")
                .contains("reexplanation은 무슨 일이 있었는지 장면 사실을 더 쉽게 다시 말하는 데까지만 허용한다")
                .doesNotContain("상황 사실로 단서가 드러나게");
    }
    /** 실제 생성에서 BASE 절반이 서버 포함 검사에 걸리고 처음 들리는 설명에 핵심 단서가 들어갔던 것을 막는 지시를, 검토 v3과 같은 기준(동의 없이 들리는 세 필드 · 눈에 보이는 장면 사실)으로 v5가 말하는지 고정한다. */
    @Test void generationV5StatesTheVerbatimInclusionAndTheReviewV3NoCueRuleForTheFieldsHeardWithoutConsent(){
        when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")));
        provider.generate(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        assertThat(captor.getValue().instructions().lines().findFirst()).contains("scenario-generation.v5");
        assertThat(captor.getValue().instructions())
                .contains("BASE의 sceneDescription에는 입력 opportunityCondition도 글자 그대로 넣는다")
                .contains("sceneDescription·opportunitySentence·reexplanation은 도움 동의 없이 아이에게 들리는 말이라")
                .contains("핵심 단서·상대의 속마음·해야 할 행동·말해야 할 내용을 설명하지 않고")
                .contains("눈에 보이는 장면 사실(장소·물건·상대의 대사·행동)로만 드러낸다")
                .contains("서술자가 상대의 지식·감정·의도를 직접 설명하지 않는다")
                .contains("BASE에 글자 그대로 넣는 opportunityCondition은 바꾸지 않는다")
                .doesNotContain("상황 사실로만 드러낸다")
                .doesNotContain("scenario-generation.v3").doesNotContain("scenario-generation.v4");
    }
    @Test void aliasesAndOriginalDeadlineAreCarriedPrivately(){var request=call();when(client.call(any())).thenReturn(output("{\"scenes\":[]}"));provider.generate(request);var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());assertThat(captor.getValue().aliases()).containsEntry("합성아이","아이");assertThat(captor.getValue().deadline()).isEqualTo(request.deadline());assertThat(request.toString()).doesNotContain("합성아이");}
    @Test void transientFailureIsTranslatedWithoutInnerRetry(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.RATE_LIMITED,1,Optional.of(Duration.ofSeconds(1))));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->{assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.TRANSIENT);assertThat(e.retryAfter()).contains(Duration.ofSeconds(1));});verify(client).call(any());}
    @Test void schemaFailureIsContentCorrectionNotTechnical(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.SCHEMA_INVALID,1));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.CONTENT));}
    @Test void authFailureNeverBecomesRetryable(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.AUTH_FAILED,1));assertThatThrownBy(()->provider.review(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.PERMANENT));}
    @Test void invalidApprovalCannotBecomeValidTypedReview(){when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[\"PRESSURE\"]}"));assertThatThrownBy(()->provider.review(call())).isInstanceOf(ScenarioScriptProvider.Failure.class);}
}
