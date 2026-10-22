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
    @Test void generationAndReviewUseDifferentCallsSchemasAndPrompts(){when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")),output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));assertThat(provider.generate(call()).script().scenes()).hasSize(1);assertThat(provider.review(call()).review().approved()).isTrue();var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client,times(2)).call(captor.capture());var values=captor.getAllValues();assertThat(values.get(0).purpose()).isEqualTo(LunaPurpose.SCENARIO_GENERATION);assertThat(values.get(1).purpose()).isEqualTo(LunaPurpose.SCENARIO_REVIEW);assertThat(values.get(0).promptVersion()).isEqualTo("scenario-generation.v7").isEqualTo(ScenarioScriptProvider.GENERATION_PROMPT_VERSION);assertThat(values.get(0).instructions().lines().findFirst()).contains("scenario-generation.v7");assertThat(values.get(1).promptVersion()).isEqualTo("scenario-review.v5").isEqualTo(ScenarioScriptProvider.REVIEW_PROMPT_VERSION);assertThat(values.get(1).instructions().lines().findFirst()).contains("scenario-review.v5");assertThat(values).allMatch(v->v.outputMode()==LunaRequest.OutputMode.CONTRACT_SINGLE_CALL);}
    /**
     * 대본 형식 v6 · v7: 검토 v5가 아이에게 들리는 순서(무중의 설명 → 친구 첫 대사 → 친구 기회 문장)와 동의 없이 들리는 네 필드를 같은 기준으로 보는지,
     * 도움 자료(빈칸 예문 포함)는 실패가 아니고 VARIANT 없는 대본에 변형 사유를 쓰지 않는지 문장으로 고정한다(docs/practice-dialogue-standard.md).
     */
    @Test void reviewV5TellsTheHeardOrderAndTheFourFieldsHeardWithoutConsent(){
        when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));
        provider.review(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        String instructions=captor.getValue().instructions();
        assertThat(instructions).contains("sceneDescription(무중이 역할 밖에서 하는 장면 설명) → partnerOpener(친구 첫 대사, VARIANT는 비어 있을 수 있다) → opportunitySentence(친구의 기회 문장) 순서로 처음 들린다")
                .contains("도움 동의 없이 아이에게 들리는 것은 sceneDescription·partnerOpener·opportunitySentence·reexplanation 네 필드다")
                .contains("focusPoints·expressionExamples·expressionFrames는 아이가 도움에 동의한 뒤에만")
                .contains("ANSWER_INCLUDED와 EARLY_CUE는 동의 없이 들리는 네 필드를 같은 기준으로 본다")
                .contains("목표 표현이나 거의 같은 문장")
                .contains("해야 할 행동·말해야 할 내용을 설명해 버리면 실패다")
                .contains("VARIANT 장면이 없는 대본에는 UNCHANGED_VARIANT·TRANSFER_MISMATCH를 쓰지 않는다")
                .doesNotContain("scenario-review.v1").doesNotContain("scenario-review.v2").doesNotContain("scenario-review.v3").doesNotContain("scenario-review.v4")
                .as("v2의 다시 설명 예외(목표 표현을 그대로 담았을 때만)는 없어야 한다").doesNotContain("그대로 담았을 때만");
        assertThat(captor.getValue().input()).isEqualTo(Map.of("age",8));
    }
    /** 서버가 더는 기회 조건을 설명에 넣으라고 하지 않으므로 그 예외를 없애고, 기회 문장이 들리는 대사인지 · 평가 · 칭찬이 없는지 보게 하는지 고정한다. */
    @Test void reviewV5DropsTheVerbatimConditionExceptionAndChecksThatTheCueIsAnAudibleLineWithoutPraise(){
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
     * 실측(docs/practice-dialogue-standard.md 12.2.1 · 14절 F2)에서 v6 대본이 설명으로 단서를 미리 말하고(s6 «무중이가 초록색 크레파스를 건넸어»), 친구가
     * 오해를 스스로 드러내고(s6 «…초록색인 줄 알았어. 이걸로 그리면 돼?»), 목표 일부를 먼저 말했던 것(x4 «…돌려줄게»)을 v5가 기존 사유 코드로
     * 거절하게 하고, 소재가 그대로인 변형 · 꾸밈말만 비운 빈칸 예문을 보게 하는지 문장으로 고정한다.
     */
    @Test void reviewV5RejectsNarratedCuesSelfRevealingLinesUnchangedMaterialAndTrivialFrames(){
        when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));
        provider.review(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        assertThat(captor.getValue().instructions())
                .contains("sceneDescription은 상황 사실과 아이가 한 일·원하는 것만 담는다")
                .contains("sceneDescription이 상대가 한 실수나 아이가 알아채야 할 단서를 말하면")
                .contains("친구가 자기 실수를 먼저 드러내거나('~인 줄 알았어') 맞는지 확인하면('이거 맞아?') 아이가 알아챌 것이 없어지므로 EARLY_CUE다")
                .contains("목표 표현·아이가 할 대답의 일부를 먼저 말하거나")
                .contains("VARIANT가 BASE의 물건·활동(소재)을 그대로 쓰거나 BASE와 같은 기회 문장을 쓰면 UNCHANGED_VARIANT다")
                .contains("꾸밈말(예: '잠깐')만 비워 사실상 정답 문장을 그대로 주면 INCOMPLETE_SCENE이다")
                .as("새 사유 코드는 없다").contains("허용 사유: ANSWER_INCLUDED, EARLY_CUE, PRESSURE, AGE_MISMATCH, GOAL_MISMATCH, REPETITION, FORBIDDEN_CONTENT, INCOMPLETE_SCENE, INVARIANT_MISMATCH, SKILL_MISMATCH, TRANSFER_MISMATCH, UNCHANGED_VARIANT.")
                .doesNotContain("말틀");
    }
    /**
     * v6 대본이 설명으로 단서를 말하고 · 첫 대사에 단서를 두고 · 친구가 실수를 드러내고 · 목표 일부를 먼저 말하고 · 변형이 소재를 바꾸지 않았던 것을
     * v7 지시가 막는지 고정한다(14절 F2). 들리는 순서 · 기회 조건을 옮기지 않음 · 무대 지문 금지 · 형식 예 · VARIANT 첫 대사 빈 문자열은 v6 그대로다.
     * 코치 전용 빈칸 예문(expressionFrames, F5)은 예시와 1:1이고 핵심 내용 낱말을 '뭐뭐'로 비운다.
     */
    @Test void generationV7StatesTheHeardOrderTheAudibleCueLineAndTheNoCopyConditionRule(){
        when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")));
        provider.generate(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());
        assertThat(captor.getValue().instructions().lines().findFirst()).contains("scenario-generation.v7");
        assertThat(captor.getValue().instructions())
                .contains("opportunityCondition은 장면이 만들어야 할 상황 조건이지 아이에게 들려줄 문장이 아니다")
                .contains("어느 필드에도 글자 그대로 옮겨 쓰지 말고")
                .contains("sceneDescription(무중이 역할 밖에서 하는 장면 설명) → partnerOpener(친구 역할의 첫 대사) → opportunitySentence(친구 역할의 기회 문장) 순서로 들린다")
                .contains("네가 방금 한 일이나 원하는 것만 말한다")
                .contains("상대가 한 실수, 아이가 알아채야 할 단서, 상대의 속마음은 쓰지 않는다")
                .contains("partnerOpener는 단서를 담지 않는 짧은 도입 대사 한 마디다")
                .contains("opportunitySentence에만 둔다")
                .contains("BASE에는 partnerOpener를 반드시 쓴다")
                .contains("친구가 자기 실수를 먼저 드러내거나")
                .contains("목표 표현이나 아이가 할 대답의 일부를 친구가 먼저 말하지 않는다")
                .contains("장면 속 물건과 활동(소재)을 바꾼다")
                .contains("BASE에 나온 물건을 다시 쓰지 않고, VARIANT의 opportunitySentence는 BASE의 opportunitySentence와 다른 문장으로 쓴다")
                .contains("expressionFrames는 expressionExamples와 같은 순서 · 같은 개수로")
                .contains("'뭐뭐'로 비운 빈칸 예문이다")
                .contains("꾸밈말 하나만 비우지 않는다")
                .contains("baseToPreserve가 있으면 BASE는 글자 하나 바꾸지 않고 그대로 쓴다")
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
                .doesNotContain("scenario-generation.v4").doesNotContain("scenario-generation.v5").doesNotContain("scenario-generation.v6")
                .doesNotContain("말틀");
    }
    @Test void aliasesAndOriginalDeadlineAreCarriedPrivately(){var request=call();when(client.call(any())).thenReturn(output("{\"scenes\":[]}"));provider.generate(request);var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());assertThat(captor.getValue().aliases()).containsEntry("합성아이","아이");assertThat(captor.getValue().deadline()).isEqualTo(request.deadline());assertThat(request.toString()).doesNotContain("합성아이");}
    @Test void transientFailureIsTranslatedWithoutInnerRetry(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.RATE_LIMITED,1,Optional.of(Duration.ofSeconds(1))));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->{assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.TRANSIENT);assertThat(e.retryAfter()).contains(Duration.ofSeconds(1));});verify(client).call(any());}
    @Test void schemaFailureIsContentCorrectionNotTechnical(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.SCHEMA_INVALID,1));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.CONTENT));}
    @Test void authFailureNeverBecomesRetryable(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.AUTH_FAILED,1));assertThatThrownBy(()->provider.review(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.PERMANENT));}
    @Test void invalidApprovalCannotBecomeValidTypedReview(){when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[\"PRESSURE\"]}"));assertThatThrownBy(()->provider.review(call())).isInstanceOf(ScenarioScriptProvider.Failure.class);}
}
