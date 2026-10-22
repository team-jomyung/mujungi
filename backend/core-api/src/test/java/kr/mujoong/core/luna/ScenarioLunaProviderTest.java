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
    @Test void generationAndReviewUseDifferentCallsSchemasAndPrompts(){when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")),output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));assertThat(provider.generate(call()).script().scenes()).hasSize(1);assertThat(provider.review(call()).review().approved()).isTrue();var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client,times(2)).call(captor.capture());var values=captor.getAllValues();assertThat(values.get(0).purpose()).isEqualTo(LunaPurpose.SCENARIO_GENERATION);assertThat(values.get(1).purpose()).isEqualTo(LunaPurpose.SCENARIO_REVIEW);assertThat(values.get(0).promptVersion()).isEqualTo("scenario-generation.v6").isEqualTo(ScenarioScriptProvider.GENERATION_PROMPT_VERSION);assertThat(values.get(0).instructions().lines().findFirst()).contains("scenario-generation.v6");assertThat(values.get(1).promptVersion()).isEqualTo("scenario-review.v4").isEqualTo(ScenarioScriptProvider.REVIEW_PROMPT_VERSION);assertThat(values.get(1).instructions().lines().findFirst()).contains("scenario-review.v4");assertThat(values).allMatch(v->v.outputMode()==LunaRequest.OutputMode.CONTRACT_SINGLE_CALL);}
    /**
     * 대본 형식 v6: 검토 v4가 아이에게 들리는 순서(무중의 설명 → 친구 첫 대사 → 친구 기회 문장)와 동의 없이 들리는 네 필드를 같은 기준으로 보는지,
     * 도움 자료는 실패가 아니고 VARIANT 없는 대본에 변형 사유를 쓰지 않는지 문장으로 고정한다(docs/practice-dialogue-standard.md).
     */
    @Test void reviewV4TellsTheHeardOrderAndTheFourFieldsHeardWithoutConsent(){
        when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));
        provider.review(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        String instructions=captor.getValue().instructions();
        assertThat(instructions).contains("sceneDescription(무중이 역할 밖에서 하는 장면 설명) → partnerOpener(친구 첫 대사, VARIANT는 비어 있을 수 있다) → opportunitySentence(친구의 기회 문장) 순서로 처음 들린다")
                .contains("도움 동의 없이 아이에게 들리는 것은 sceneDescription·partnerOpener·opportunitySentence·reexplanation 네 필드다")
                .contains("focusPoints와 expressionExamples는 아이가 도움에 동의한 뒤에만")
                .contains("ANSWER_INCLUDED와 EARLY_CUE는 동의 없이 들리는 네 필드를 같은 기준으로 본다")
                .contains("목표 표현이나 거의 같은 문장")
                .contains("해야 할 행동·말해야 할 내용을 설명해 버리면 실패다")
                .contains("VARIANT 장면이 없는 대본에는 UNCHANGED_VARIANT·TRANSFER_MISMATCH를 쓰지 않는다")
                .doesNotContain("scenario-review.v1").doesNotContain("scenario-review.v2").doesNotContain("scenario-review.v3")
                .as("v2의 다시 설명 예외(목표 표현을 그대로 담았을 때만)는 없어야 한다").doesNotContain("그대로 담았을 때만");
        assertThat(captor.getValue().input()).isEqualTo(Map.of("age",8));
    }
    /** 서버가 더는 기회 조건을 설명에 넣으라고 하지 않으므로 그 예외를 없애고, 기회 문장이 들리는 대사인지 · 평가 · 칭찬이 없는지 보게 하는지 고정한다. */
    @Test void reviewV4DropsTheVerbatimConditionExceptionAndChecksThatTheCueIsAnAudibleLineWithoutPraise(){
        when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));
        provider.review(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        assertThat(captor.getValue().instructions())
                .contains("서술자가 상대의 지식·감정·의도를 직접 설명하는 것은 장면 사실이 아니라 핵심 단서 설명이다")
                .contains("opportunityCondition은 생성 조건이라 그 문장을 옮겨 썼다고 해서 예외가 되지 않는다")
                .contains("reexplanation은 무슨 일이 있었는지 장면 사실을 더 쉽게 다시 말하는 데까지만 허용한다")
                .contains("partnerOpener와 opportunitySentence는 친구가 실제로 하는 대사여야 한다")
                .contains("소리만 듣고는 단서를 알 수 없으면 INCOMPLETE_SCENE")
                .contains("opportunityCondition의 상황이 실제로 생기지 않으면 GOAL_MISMATCH")
                .contains("칭찬하거나 재촉하는 말이 있으면 PRESSURE다")
                .doesNotContain("opportunityCondition을 글자 그대로 옮긴 부분뿐이다");
    }
    /**
     * 실제 생성이 무대 지문("친구가 초록색 크레용을 네 쪽으로 내민다")을 기회 문장으로 쓰고 기회 조건 문장을 설명에 옮겼던 것을 막는 지시를 v6가 말하는지
     * 고정한다: 들리는 순서, 기회 문장 = 단서가 들리는 친구 대사, 기회 조건은 옮기지 않음, 형식 예(내용은 베끼지 않음), VARIANT 첫 대사는 빈 문자열.
     */
    @Test void generationV6StatesTheHeardOrderTheAudibleCueLineAndTheNoCopyConditionRule(){
        when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")));
        provider.generate(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        assertThat(captor.getValue().instructions().lines().findFirst()).contains("scenario-generation.v6");
        assertThat(captor.getValue().instructions())
                .contains("opportunityCondition은 장면이 만들어야 할 상황 조건이지 아이에게 들려줄 문장이 아니다")
                .contains("어느 필드에도 글자 그대로 옮겨 쓰지 말고")
                .contains("sceneDescription(무중이 역할 밖에서 하는 장면 설명) → partnerOpener(친구 역할의 첫 대사) → opportunitySentence(친구 역할의 기회 문장) 순서로 들린다")
                .contains("partnerOpener는 친구가 장면을 여는 짧은 대사 한 마디이고 BASE에는 반드시 쓴다")
                .contains("단서가 이 대사 속에서 귀로 들려야 한다")
                .contains("무대 지문 · 해설로 쓰지 않는다")
                .contains("형식 예(이 소재 · 내용은 따라 쓰지 않는다)")
                .contains("VARIANT는 partnerOpener를 빈 문자열로 두고")
                .contains("서버가 끝에 '이어서 해 볼까?'를 붙여 제안하므로 질문으로 끝내지 않는다")
                .contains("눈에 보이는 장면 사실과 친구의 대사로만 드러낸다")
                .contains("서술자가 상대의 지식·감정·의도를 직접 설명하지 않는다")
                .contains("칭찬 · 평가 · 압박을 넣지 않는다")
                .doesNotContain("글자 그대로 넣는다")
                .as("기준 대화의 크레용 예를 그대로 베끼게 하지 않는다").doesNotContain("크레용").doesNotContain("초록색")
                .doesNotContain("scenario-generation.v4").doesNotContain("scenario-generation.v5");
    }
    @Test void aliasesAndOriginalDeadlineAreCarriedPrivately(){var request=call();when(client.call(any())).thenReturn(output("{\"scenes\":[]}"));provider.generate(request);var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());assertThat(captor.getValue().aliases()).containsEntry("합성아이","아이");assertThat(captor.getValue().deadline()).isEqualTo(request.deadline());assertThat(request.toString()).doesNotContain("합성아이");}
    @Test void transientFailureIsTranslatedWithoutInnerRetry(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.RATE_LIMITED,1,Optional.of(Duration.ofSeconds(1))));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->{assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.TRANSIENT);assertThat(e.retryAfter()).contains(Duration.ofSeconds(1));});verify(client).call(any());}
    @Test void schemaFailureIsContentCorrectionNotTechnical(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.SCHEMA_INVALID,1));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.CONTENT));}
    @Test void authFailureNeverBecomesRetryable(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.AUTH_FAILED,1));assertThatThrownBy(()->provider.review(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.PERMANENT));}
    @Test void invalidApprovalCannotBecomeValidTypedReview(){when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[\"PRESSURE\"]}"));assertThatThrownBy(()->provider.review(call())).isInstanceOf(ScenarioScriptProvider.Failure.class);}
}
