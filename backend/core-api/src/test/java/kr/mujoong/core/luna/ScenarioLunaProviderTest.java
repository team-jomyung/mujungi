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
    @Test void generationAndReviewUseDifferentCallsSchemasAndPrompts(){when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")),output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));assertThat(provider.generate(call()).script().scenes()).hasSize(1);assertThat(provider.review(call()).review().approved()).isTrue();var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client,times(2)).call(captor.capture());var values=captor.getAllValues();assertThat(values.get(0).purpose()).isEqualTo(LunaPurpose.SCENARIO_GENERATION);assertThat(values.get(1).purpose()).isEqualTo(LunaPurpose.SCENARIO_REVIEW);assertThat(values.get(0).promptVersion()).isEqualTo("scenario-generation.v4").isEqualTo(ScenarioScriptProvider.GENERATION_PROMPT_VERSION);assertThat(values.get(0).instructions().lines().findFirst()).contains("scenario-generation.v4");assertThat(values.get(1).promptVersion()).isEqualTo("scenario-review.v2").isEqualTo(ScenarioScriptProvider.REVIEW_PROMPT_VERSION);assertThat(values.get(1).instructions().lines().findFirst()).contains("scenario-review.v2");assertThat(values).allMatch(v->v.outputMode()==LunaRequest.OutputMode.CONTRACT_SINGLE_CALL);}
    /** 실제 검토가 도움 자료를 정답 선제 제시로 읽고 BASE 단독 검토에 변형 사유를 내던 오판을 v2 지시가 막는지 문장으로 고정한다. */
    @Test void reviewV2TellsWhichFieldsTheChildHearsFirstAndWhenVariantReasonsApply(){
        when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));
        provider.review(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        String instructions=captor.getValue().instructions();
        assertThat(instructions).contains("처음 들리는 것은 sceneDescription과 opportunitySentence뿐이다")
                .contains("focusPoints와 expressionExamples는 아이가 도움에 동의한 뒤에만")
                .contains("ANSWER_INCLUDED와 EARLY_CUE는 처음 들리는 두 필드만 본다")
                .contains("VARIANT 장면이 없는 대본에는 UNCHANGED_VARIANT·TRANSFER_MISMATCH를 쓰지 않는다")
                .doesNotContain("scenario-review.v1");
        assertThat(captor.getValue().input()).isEqualTo(Map.of("age",8));
    }
    /** 실제 생성에서 BASE 절반이 서버 포함 검사에 걸리고 처음 들리는 설명에 핵심 단서가 들어갔던 것을 v4 지시가 말하는지 고정한다. */
    @Test void generationV4StatesTheVerbatimInclusionAndTheNoCueRuleForTheFirstHeardFields(){
        when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")));
        provider.generate(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        assertThat(captor.getValue().instructions().lines().findFirst()).contains("scenario-generation.v4");
        assertThat(captor.getValue().instructions())
                .contains("BASE의 sceneDescription에는 입력 opportunityCondition도 글자 그대로 넣는다")
                .contains("핵심 단서·상대의 속마음·해야 할 행동을 설명하지 않고")
                .doesNotContain("scenario-generation.v3");
    }
    @Test void aliasesAndOriginalDeadlineAreCarriedPrivately(){var request=call();when(client.call(any())).thenReturn(output("{\"scenes\":[]}"));provider.generate(request);var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());assertThat(captor.getValue().aliases()).containsEntry("합성아이","아이");assertThat(captor.getValue().deadline()).isEqualTo(request.deadline());assertThat(request.toString()).doesNotContain("합성아이");}
    @Test void transientFailureIsTranslatedWithoutInnerRetry(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.RATE_LIMITED,1,Optional.of(Duration.ofSeconds(1))));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->{assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.TRANSIENT);assertThat(e.retryAfter()).contains(Duration.ofSeconds(1));});verify(client).call(any());}
    @Test void schemaFailureIsContentCorrectionNotTechnical(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.SCHEMA_INVALID,1));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.CONTENT));}
    @Test void authFailureNeverBecomesRetryable(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.AUTH_FAILED,1));assertThatThrownBy(()->provider.review(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.PERMANENT));}
    @Test void invalidApprovalCannotBecomeValidTypedReview(){when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[\"PRESSURE\"]}"));assertThatThrownBy(()->provider.review(call())).isInstanceOf(ScenarioScriptProvider.Failure.class);}
}
