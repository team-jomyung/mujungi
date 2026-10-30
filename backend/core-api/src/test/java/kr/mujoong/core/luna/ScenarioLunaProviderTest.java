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
    @Test void generationAndReviewUseDifferentCallsSchemasAndPrompts(){when(client.call(any())).thenReturn(output(LunaTestFixtures.example("ai-scenario-output.schema.json")),output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));assertThat(provider.generate(call()).script().scenes()).hasSize(1);assertThat(provider.review(call()).review().approved()).isTrue();var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client,times(2)).call(captor.capture());var values=captor.getAllValues();assertThat(values.get(0).purpose()).isEqualTo(LunaPurpose.SCENARIO_GENERATION);assertThat(values.get(1).purpose()).isEqualTo(LunaPurpose.SCENARIO_REVIEW);assertThat(values.get(0).promptVersion()).isEqualTo("scenario-generation.v7");assertThat(values.get(1).promptVersion()).isEqualTo("scenario-review.v6");assertThat(values).allMatch(v->v.outputMode()==LunaRequest.OutputMode.CONTRACT_SINGLE_CALL);}
    // 첫 초안부터 검토의 EARLY_CUE · ANSWER_INCLUDED 경계를 점검하게 한다(FT-1030-d): 생성 호출이 v6 지시 원문을 싣는다
    @Test void generationCarriesTheFirstDraftCueBoundaryCheck(){when(client.call(any())).thenReturn(output("{\"scenes\":[]}"));provider.generate(call());var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());var instructions=captor.getValue().instructions();assertThat(instructions).startsWith("scenario-generation.v7\n").contains("9. 출력 전 단서 경계 점검","이 점검은 첫 생성부터 적용한다","10. 수정 요청 처리");assertThat(instructions.indexOf("9. 출력 전 단서 경계 점검")).isLessThan(instructions.indexOf("10. 수정 요청 처리"));}
    // 장면 불완전 거절은 빠진 사실을 지목할 수 있을 때만 낸다(FT-1030-e): 검토 호출이 v5 지시 원문을 싣는다
    @Test void reviewCarriesTheIncompleteSceneThreshold(){when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));provider.review(call());var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());var instructions=captor.getValue().instructions();assertThat(instructions).startsWith("scenario-review.v6\n").contains("INCOMPLETE_SCENE의 문턱","빠졌다고 보는 사실을 한 문장으로 지목할 수 있는가","다른 사유의 기준을 낮추지 않는다");assertThat(instructions.indexOf("INCOMPLETE_SCENE의 문턱")).isLessThan(instructions.indexOf("허용 사유 코드는 다음뿐이다."));}
    // FT-1030-j · k: 생성 v7은 한국어로만 쓰고, 어려움을 적은 불변 문장은 조건으로 갖추며, 목표 표현의 장면 사실은 안내에 사실로 준다. 검토 v6도 같은 경계로 본다
    @Test void v7AndV6CarryKoreanOnlyInvariantConditionAndFactBoundary(){when(client.call(any())).thenReturn(output("{\"scenes\":[]}"),output("{\"verdict\":\"APPROVE\",\"reasons\":[]}"));provider.generate(call());provider.review(call());var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client,times(2)).call(captor.capture());var generation=captor.getAllValues().get(0).instructions();var review=captor.getAllValues().get(1).instructions();assertThat(generation).contains("장소와 활동을 모두 history와 다른 것으로 고른다","한국어로만 쓴다","그 실수가 생길 수 있는 조건을 장면에 갖추라는 뜻이다","목표 표현 문장을 그대로 옮기거나","FOREIGN_SCRIPT이면");assertThat(review).contains("그 실수가 생길 수 있는 조건","ANSWER_INCLUDED · EARLY_CUE가 아니다");}
    @Test void aliasesAndOriginalDeadlineAreCarriedPrivately(){var request=call();when(client.call(any())).thenReturn(output("{\"scenes\":[]}"));provider.generate(request);var captor=ArgumentCaptor.forClass(LunaRequest.class);verify(client).call(captor.capture());assertThat(captor.getValue().aliases()).containsEntry("합성아이","아이");assertThat(captor.getValue().deadline()).isEqualTo(request.deadline());assertThat(request.toString()).doesNotContain("합성아이");}
    @Test void transientFailureIsTranslatedWithoutInnerRetry(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.RATE_LIMITED,1,Optional.of(Duration.ofSeconds(1))));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->{assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.TRANSIENT);assertThat(e.retryAfter()).contains(Duration.ofSeconds(1));});verify(client).call(any());}
    @Test void schemaFailureIsContentCorrectionNotTechnical(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.SCHEMA_INVALID,1));assertThatThrownBy(()->provider.generate(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.CONTENT));}
    @Test void authFailureNeverBecomesRetryable(){when(client.call(any())).thenThrow(new LunaException(LunaFailureReason.AUTH_FAILED,1));assertThatThrownBy(()->provider.review(call())).isInstanceOfSatisfying(ScenarioScriptProvider.Failure.class,e->assertThat(e.kind()).isEqualTo(ScenarioScriptProvider.Failure.Kind.PERMANENT));}
    @Test void invalidApprovalCannotBecomeValidTypedReview(){when(client.call(any())).thenReturn(output("{\"verdict\":\"APPROVE\",\"reasons\":[\"PRESSURE\"]}"));assertThatThrownBy(()->provider.review(call())).isInstanceOf(ScenarioScriptProvider.Failure.class);}
}
