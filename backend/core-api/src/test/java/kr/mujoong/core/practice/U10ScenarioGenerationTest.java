package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.mujoong.core.textai.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

@Tag("u10")
class U10ScenarioGenerationTest {
    ScenarioScriptProvider provider;MutableClock clock;ExecutorService executor;ScenarioScriptGenerator generator;
    static final String OPPORTUNITY="친구가 멈추려 해.";
    static ScenarioScript.Scene base(){return new ScenarioScript.Scene("base","BASE","블록 놀이 중이야. "+OPPORTUNITY+" 이제 그만할래.","이제 그만할래.","친구가 쉬려고 해.",List.of("친구의 말을 들어보자"),List.of("잠깐 쉬자"),"PEER","교실","놀이",null,null);}
    static ScenarioScriptValidation.Rules rules(){return new ScenarioScriptValidation.Rules("PEER",OPPORTUNITY,"잠깐 쉬자",Set.of("합성아이"),null,x->false,(a,b)->false);}
    static ScenarioScriptProvider.Generated generated(ScenarioScript.Scene...scenes){return new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(scenes)),"fake");}
    static ScenarioScriptProvider.Reviewed approve(){return new ScenarioScriptProvider.Reviewed(new ScenarioReview("APPROVE",List.of()),"fake-review");}
    static ScenarioScriptProvider.Reviewed reject(String reason){return new ScenarioScriptProvider.Reviewed(new ScenarioReview("REJECT",List.of(reason)),"fake-review");}
    static ScenarioScriptProvider.Failure failure(ScenarioScriptProvider.Failure.Kind kind){return new ScenarioScriptProvider.Failure(kind,Optional.empty());}
    @BeforeEach void open(){provider=mock(ScenarioScriptProvider.class);clock=new MutableClock();executor=Executors.newVirtualThreadPerTaskExecutor();generator=new ScenarioScriptGenerator(provider,new ScenarioScriptValidation(new ForbiddenExpressionPolicy()),new TextAiProperties(Duration.ofSeconds(3),new TextAiProperties.Retry(1,Duration.ofMillis(250))),executor,clock::advance);when(provider.generate(any())).thenReturn(generated(base()));when(provider.review(any())).thenReturn(approve());}
    @AfterEach void close(){executor.shutdownNow();}
    ScenarioScriptGenerator.Input input(){return new ScenarioScriptGenerator.Input(Map.of("age",8,"history",List.of()),Map.of("합성아이","아이"),rules(),false,"{}");}
    ScenarioScriptGenerator.Result run(){return generator.generate(input(),new ScenarioDeadline(clock,clock.now().plusSeconds(15)));}
    @Test void normalNeedsSeparateReviewAndServerRole(){var result=run();verify(provider).generate(any());verify(provider).review(any());assertThat(result.details().generation().calls()).isEqualTo(2);assertThat(result.details().review().verdict()).isEqualTo("APPROVE");assertThat(result.details().scenes().getFirst().partnerRole()).isEqualTo(ScenarioContentPolicy.PARTNER_ROLE);}
    @Test void rejectionUsesOneContentCorrectionThenSeparateReview(){when(provider.review(any())).thenReturn(reject("AGE_MISMATCH"),approve());assertThat(run().details().generation().calls()).isEqualTo(4);verify(provider,times(2)).generate(any());verify(provider,times(2)).review(any());}
    @Test void twiceRejectedNeverReturnsIssuableScript(){when(provider.review(any())).thenReturn(reject("REPETITION"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());verify(provider,times(2)).review(any());}
    @Test void uncertainIsNotApproval(){when(provider.review(any())).thenReturn(new ScenarioScriptProvider.Reviewed(new ScenarioReview("UNCERTAIN",List.of()),"fake"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());}
    @Test void schemaFailureUsesContentNotTechnicalRetry(){when(provider.generate(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.CONTENT)).thenReturn(generated(base()));assertThat(run().details().generation().calls()).isEqualTo(3);verify(provider,times(2)).generate(any());verify(provider).review(any());}
    @Test void oneTechnicalRetryIsSharedBetweenGenerationAndReview(){when(provider.generate(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT)).thenReturn(generated(base()));assertThat(run().details().generation().calls()).isEqualTo(3);assertThat(clock.elapsedSince(0)).isGreaterThanOrEqualTo(Duration.ZERO);}
    @Test void secondTechnicalFailureDoesNotRetryAgain(){when(provider.generate(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT)).thenReturn(generated(base()));when(provider.review(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());verify(provider).review(any());}
    @Test void permanentFailureDoesNotRegenerate(){when(provider.generate(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.PERMANENT));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider).generate(any());verify(provider,never()).review(any());}
    @Test void approvedButInvalidBaseStillFailsIndependentServerChecks(){var bad=new ScenarioScript.Scene("base","BASE","정답은 잠깐 쉬자", "말", "설명",null,null,"PEER","교실","놀이",null,null);when(provider.generate(any())).thenReturn(generated(bad));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());verify(provider,times(2)).review(any());}
    @Test void invalidVariantIsCorrectedOnceThenBaseOnlyReviewed(){var base=base();var variant=new ScenarioScript.Scene("variant","VARIANT",base.sceneDescription(),base.opportunitySentence(),base.reexplanation(),null,null,"PEER","운동장","놀이",null,null);when(provider.generate(any())).thenReturn(generated(base,variant));var result=run();assertThat(result.details().scenes()).hasSize(1);assertThat(result.details().generation().calls()).isEqualTo(5);var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(provider,times(3)).review(calls.capture());assertThat(((ScenarioScript)calls.getAllValues().getLast().input().get("script")).scenes()).hasSize(1);verify(provider,times(2)).generate(any());}
    @Test void baseOnlyMustReceiveItsOwnApproval(){var base=base();var variant=new ScenarioScript.Scene("variant","VARIANT",base.sceneDescription(),base.opportunitySentence(),base.reexplanation(),null,null,"PEER","운동장","놀이",null,null);when(provider.generate(any())).thenReturn(generated(base,variant));when(provider.review(any())).thenReturn(approve(),approve(),reject("AGE_MISMATCH"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(3)).review(any());}
    @Test void ageAndSameHistoryReachEveryReviewAndCorrection(){when(provider.review(any())).thenReturn(reject("AGE_MISMATCH"),approve());run();var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(provider,times(2)).review(calls.capture());assertThat(calls.getAllValues()).allSatisfy(c->assertThat(c.input()).containsEntry("age",8).containsEntry("history",List.of()));}
}
