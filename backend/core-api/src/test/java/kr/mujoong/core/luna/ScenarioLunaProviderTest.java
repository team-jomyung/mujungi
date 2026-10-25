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
    LunaResult output(String text){return new LunaResult(text,"fake","scenario-generation.v3",Duration.ZERO,1,false);}
    @Test void generationAndReviewUseDifferentCallsSchemasAndPrompts(){when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")),output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));assertThat(provider.generate(call()).script().scenes()).hasSize(1);assertThat(provider.review(call()).review().approved()).isTrue();var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client,times(2)).call(captor.capture());var values=captor.getAllValues();assertThat(values.get(0).purpose()).isEqualTo(LunaPurpose.SCENARIO_GENERATION);assertThat(values.get(1).purpose()).isEqualTo(LunaPurpose.SCENARIO_REVIEW);assertThat(values.get(0).promptVersion()).isEqualTo("scenario-generation.v3");assertThat(values.get(1).promptVersion()).isEqualTo("scenario-review.v1");assertThat(values).allMatch(v->v.outputMode()==LunaRequest.OutputMode.CONTRACT_SINGLE_CALL);}
    @Test void aliasesAndOriginalDeadlineAreCarriedPrivately(){var request=call();when(client.call(any())).thenReturn(output("{\"scenes\":[]}"));provider.generate(request);var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());assertThat(captor.getValue().aliases()).containsEntry("합성아이","아이");assertThat(captor.getValue().deadline()).isEqualTo(request.deadline());assertThat(request.toString()).doesNotContain("합성아이");}
    @Test void transientFailureIsTranslatedWithoutInnerRetry(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.RATE_LIMITED,1,Optional.of(Duration.ofSeconds(1))));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->{assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.TRANSIENT);assertThat(e.retryAfter()).contains(Duration.ofSeconds(1));});verify(client).call(any());}
    @Test void schemaFailureIsContentCorrectionNotTechnical(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.SCHEMA_INVALID,1));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.CONTENT));}
    @Test void authFailureNeverBecomesRetryable(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.AUTH_FAILED,1));assertThatThrownBy(()->provider.review(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.PERMANENT));}
    @Test void generationSendsTheDirectAskPromptAndReviewSendsTheMainReviewV1WhileOldVersionsStayAsHistory() throws java.io.IOException {
        when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")),output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));
        var scene=provider.generate(call()).script().scenes().getFirst();
        provider.review(call());
        var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client,times(2)).call(captor.capture());
        String generation=captor.getAllValues().get(0).instructions(),review=captor.getAllValues().get(1).instructions();
        assertThat(generation).startsWith("scenario-generation.v3").contains("opportunityRelay","askPrompt","reaskPrompt","helpSpeech","friendReaction","네 말로 해 볼래?")
                .doesNotContain("무중이가 친구 역할을 해 볼게");
        // C안 칸은 검토에 보내지 않고 서버가 칸마다 검사한다(설계 4.4). 검토 지시는 main 그대로다
        assertThat(review).startsWith("scenario-review.v1").contains("spokenIntroduction","INCOMPLETE_SCENE","ANSWER_INCLUDED","EARLY_CUE","PRESSURE")
                .doesNotContain("opportunityRelay","friendReaction","helpSpeech");
        assertThat(LunaTextAiProvider.REVIEW_INSTRUCTIONS).isEqualTo("prompts/scenario/review.v1.txt");
        assertThat(new org.springframework.core.io.ClassPathResource("prompts/scenario/review.v2.txt").exists()).as("출시하지 않은 검토 v2는 지웠다").isFalse();
        assertThat(scene.askPrompt()).as("계약 예시의 새 칸을 읽는다").isEqualTo("너라면 친구한테 뭐라고 말할래?");
        assertThat(scene.friendReaction()).isNotBlank();
        // 옛 판은 지우거나 고치지 않는다(같은 판 이름을 다른 내용으로 바꾸지 않음, 설계 4.1)
        for(String old:java.util.List.of("prompts/scenario/generation.v2.txt","prompts/scenario/review.v1.txt"))
            assertThat(new org.springframework.core.io.ClassPathResource(old).exists()).as(old).isTrue();
        assertThat(new org.springframework.core.io.ClassPathResource("prompts/scenario/generation.v2.txt").getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("scenario-generation.v2").contains("무중이가 친구 역할을 해 볼게");
    }
    @Test void theContractExampleSceneKeepsEveryHeardFieldUnderTheServerChecks(){
        when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")));
        var scene=provider.generate(call()).script().scenes().getFirst();
        var rules=new kr.mujoong.core.practice.ScenarioScriptValidation.Rules(scene.relation(),"빨간 블록은 친구한테 다 있다",
                scene.expressionExamples().getFirst(),java.util.Set.of(),null,x->false,(a,b)->true);
        var validation=new kr.mujoong.core.practice.ScenarioScriptValidation(new kr.mujoong.core.textai.ForbiddenExpressionPolicy());
        // 계약 예시는 전하기 규칙(간접 인용 · 해석 금지 · 충실도)과 그 밖의 칸별 규칙을 모두 지킨다
        assertThat(validation.optionalSpokenIssues(scene,rules)).isEmpty();
        assertThat(validation.inspect(new kr.mujoong.core.textai.ScenarioScript(java.util.List.of(scene)),rules))
                .isEqualTo(kr.mujoong.core.practice.ScenarioScriptValidation.Result.VALID);
    }
    @Test void invalidApprovalCannotBecomeValidTypedReview(){when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[\"PRESSURE\"]}"));assertThatThrownBy(()->provider.review(call())).isInstanceOf(ScenarioScriptProvider.Failure.class);}
}
