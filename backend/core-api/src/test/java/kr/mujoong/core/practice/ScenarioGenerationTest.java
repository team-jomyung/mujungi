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

@Tag("scenario-generation")
class ScenarioGenerationTest {
    @Test void diagnosticsSeparateReviewRejectionWithoutIncludingSceneText(){
        var codes=new java.util.ArrayList<String>();generator.diagnostics(codes::add);
        when(provider.review(any())).thenReturn(reject("GOAL_MISMATCH"),approve());
        run();assertThat(codes).containsExactly("ScenarioReview_REJECT","ScenarioReview_GOAL_MISMATCH");
    }
    /** FT-1028-c: 거절 사유마다 걸린 점검 항목 · 필드를 고정 코드로 남긴다. */
    @Test void reviewFindingsAreRecordedAsFixedCodes(){
        var codes=new java.util.ArrayList<String>();generator.diagnostics(codes::add);
        var finding=new ScenarioReview.Finding("GOAL_MISMATCH","OPPORTUNITY","opportunitySentence");
        when(provider.review(any())).thenReturn(new ScenarioScriptProvider.Reviewed(new ScenarioReview("REJECT",List.of("GOAL_MISMATCH"),List.of(finding)),"fake-review"),approve());
        run();
        assertThat(codes).containsExactly("ScenarioReview_REJECT","ScenarioReview_GOAL_MISMATCH","ScenarioReviewFinding_GOAL_MISMATCH_OPPORTUNITY_opportunitySentence");
    }
    /** FT-1030-f: 수정 생성은 검토가 지목한 점검 항목 · 필드를 닫힌 값으로 받고, 지적이 없던 첫 생성 · 지적 없는 거절에는 넣지 않는다. */
    @Test void repairReceivesTheFieldsTheReviewPointedAt(){
        var finding=new ScenarioReview.Finding("GOAL_MISMATCH","OPPORTUNITY","opportunitySentence");
        when(provider.review(any())).thenReturn(new ScenarioScriptProvider.Reviewed(new ScenarioReview("REJECT",List.of("GOAL_MISMATCH"),List.of(finding)),"fake-review"),
                reject("INCOMPLETE_SCENE"),approve());
        run();
        var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);
        verify(provider,times(3)).generate(calls.capture());
        assertThat(calls.getAllValues().get(0).input()).doesNotContainKey("correctionFindings");
        assertThat(calls.getAllValues().get(1).input()).containsEntry("correctionReasons",List.of("GOAL_MISMATCH"))
                .containsEntry("correctionFindings",List.of(Map.of("reason","GOAL_MISMATCH","check","OPPORTUNITY","field","opportunitySentence")));
        // 다음 거절에 지적이 없으면 앞 라운드 지적을 끌고 가지 않는다
        assertThat(calls.getAllValues().get(2).input()).containsEntry("correctionReasons",List.of("INCOMPLETE_SCENE")).doesNotContainKey("correctionFindings");
    }
    /** FT-1028-c: 직전 라운드만큼 시간이 남지 않으면 검토를 끝낼 수 없는 생성 호출을 쓰지 않는다. */
    @Test void aRoundThatCannotFinishBeforeTheDeadlineIsNotStarted(){
        var codes=new java.util.ArrayList<String>();generator.diagnostics(codes::add);
        when(provider.review(any())).thenAnswer(c->{clock.advance(Duration.ofSeconds(5));return reject("INCOMPLETE_SCENE");});
        assertThatThrownBy(this::run).isInstanceOf(ScenarioScriptGenerator.ContentExhausted.class);
        verify(provider,times(2)).generate(any());verify(provider,times(2)).review(any());
        assertThat(codes).contains("ScenarioBudgetInsufficient");
    }
    @Test void aRoundThatFitsTheRemainingBudgetStillRuns(){
        when(provider.review(any())).thenAnswer(c->{clock.advance(Duration.ofSeconds(4));return reject("INCOMPLETE_SCENE");}).thenAnswer(c->{clock.advance(Duration.ofSeconds(4));return reject("INCOMPLETE_SCENE");}).thenReturn(approve());
        assertThat(run().details().generation().calls()).isEqualTo(6);
        verify(provider,times(3)).generate(any());
    }
    @Test void brokenDiagnosticSinkCannotChangeApprovedGeneration(){
        generator.diagnostics(code->{throw new IllegalStateException("unavailable");});
        when(provider.review(any())).thenReturn(reject("GOAL_MISMATCH"),approve());
        assertThat(run().details().scenes()).hasSize(1);
    }
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
    /** JIRA-283: 조건을 뜻만 같게 바꿔 쓴 대본은 서버가 원문을 덧붙여 검토받게 해, 고쳐 생성하느라 호출을 더 쓰지 않는다. */
    @Test void aParaphrasedConditionIsAppendedBeforeReviewInsteadOfCostingACorrection(){
        var b=base();var paraphrased=new ScenarioScript.Scene(b.sceneKey(),b.sceneKind(),"블록 놀이 중이야. 친구가 이제 쉬고 싶어 해. "+b.opportunitySentence(),
                b.opportunitySentence(),b.reexplanation(),b.focusPoints(),b.expressionExamples(),b.relation(),b.place(),b.topic(),null,null);
        when(provider.generate(any())).thenReturn(generated(paraphrased));
        var result=run();
        assertThat(result.details().generation().calls()).isEqualTo(2);
        assertThat(result.details().scenes().getFirst().sceneDescription()).endsWith(OPPORTUNITY);
        var reviewed=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(provider).review(reviewed.capture());
        assertThat(((ScenarioScript)reviewed.getValue().input().get("script")).scenes().getFirst().sceneDescription()).as("검토도 덧붙인 설명을 본다").endsWith(OPPORTUNITY);
    }
    @Test void aConditionAlreadyPresentIsNotAppendedAgain(){
        var result=run();
        assertThat(result.details().scenes().getFirst().sceneDescription()).isEqualTo(base().sceneDescription());
    }
    @Test void rejectionUsesOneContentCorrectionThenSeparateReview(){when(provider.review(any())).thenReturn(reject("AGE_MISMATCH"),approve());assertThat(run().details().generation().calls()).isEqualTo(4);verify(provider,times(2)).generate(any());verify(provider,times(2)).review(any());}
    @Test void twiceRejectedNeverReturnsIssuableScript(){when(provider.review(any())).thenReturn(reject("REPETITION"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());verify(provider,times(2)).review(any());}
    @Test void correctionReceivesTheRejectionReasonWithoutAcceptingTheRejectedScript(){
        when(provider.review(any())).thenReturn(reject("REPETITION"),approve());
        run();var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);
        verify(provider,times(2)).generate(calls.capture());
        assertThat(calls.getAllValues().getFirst().input()).doesNotContainKey("correctionReasons");
        assertThat(calls.getAllValues().getLast().input()).containsEntry("correctionReasons",List.of("REPETITION"));
        assertThat(calls.getAllValues().getLast().input().get("scriptToRepair")).isInstanceOf(ScenarioScript.class);
    }
    @Test void missingQuotedOpportunityIsIncludedBeforeIndependentReview(){
        var scene=base();var indirect=new ScenarioScript.Scene(scene.sceneKey(),scene.sceneKind(),"블록 놀이 중이야. "+OPPORTUNITY,
                scene.opportunitySentence(),scene.reexplanation(),scene.focusPoints(),scene.expressionExamples(),scene.relation(),scene.place(),scene.topic(),null,null);
        when(provider.generate(any())).thenReturn(generated(indirect),generated(scene));
        assertThat(run().details().generation().calls()).isEqualTo(2);
        var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(provider).review(calls.capture());
        var reviewed=(ScenarioScript)calls.getValue().input().get("script");
        assertThat(reviewed.scenes().getFirst().sceneDescription()).contains(scene.opportunitySentence());
    }
    @Test void repairableSceneRejectionGetsOneFinalReviewedAttemptWithinTheSixCallBudget(){
        when(provider.review(any())).thenReturn(reject("INCOMPLETE_SCENE"),reject("ANSWER_INCLUDED"),approve());
        var result=run();
        assertThat(result.details().generation().calls()).isEqualTo(6);
        verify(provider,times(3)).generate(any());verify(provider,times(3)).review(any());
        assertThat(result.details().review().verdict()).isEqualTo("APPROVE");
    }
    @Test void aGoalMismatchCanUseTheLastRepairSlotButStillRequiresApproval(){
        when(provider.review(any())).thenReturn(reject("INCOMPLETE_SCENE"),reject("GOAL_MISMATCH"),approve());
        assertThat(run().details().generation().calls()).isEqualTo(6);
        var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);
        verify(provider,times(3)).generate(calls.capture());
        assertThat(calls.getAllValues().getLast().input()).containsEntry("correctionReasons",List.of("GOAL_MISMATCH"));
        verify(provider,times(3)).review(any());
    }
    @Test void anUnrepairedGoalMismatchNeverIssuesAScene(){
        when(provider.review(any())).thenReturn(reject("GOAL_MISMATCH"));
        assertThatThrownBy(this::run).isInstanceOf(ScenarioScriptGenerator.ContentExhausted.class);
        verify(provider,times(3)).generate(any());verify(provider,times(3)).review(any());
    }
    @Test void includedPartnerLineNeverBypassesRejectionOrAnswerSafety(){
        var scene=base();var indirect=scene.withDescription("블록 놀이 중이야. "+OPPORTUNITY);
        when(provider.generate(any())).thenReturn(generated(indirect));when(provider.review(any())).thenReturn(reject("INCOMPLETE_SCENE"));
        assertThatThrownBy(this::run).isInstanceOf(ScenarioScriptGenerator.ContentExhausted.class);
        verify(provider,times(3)).review(any());
    }
    @Test void uncertainIsNotApproval(){when(provider.review(any())).thenReturn(new ScenarioScriptProvider.Reviewed(new ScenarioReview("UNCERTAIN",List.of()),"fake"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());}
    @Test void schemaFailureUsesContentNotTechnicalRetry(){when(provider.generate(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.CONTENT)).thenReturn(generated(base()));assertThat(run().details().generation().calls()).isEqualTo(3);verify(provider,times(2)).generate(any());verify(provider).review(any());}
    @Test void oneTechnicalRetryIsSharedBetweenGenerationAndReview(){when(provider.generate(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT)).thenReturn(generated(base()));assertThat(run().details().generation().calls()).isEqualTo(3);assertThat(clock.elapsedSince(0)).isGreaterThanOrEqualTo(Duration.ZERO);}
    @Test void secondTechnicalFailureDoesNotRetryAgain(){when(provider.generate(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT)).thenReturn(generated(base()));when(provider.review(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.TRANSIENT));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());verify(provider).review(any());}
    @Test void permanentFailureDoesNotRegenerate(){when(provider.generate(any())).thenThrow(failure(ScenarioScriptProvider.Failure.Kind.PERMANENT));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider).generate(any());verify(provider,never()).review(any());}
    @Test void invalidSceneStartFieldsAreDroppedBeforeReviewInsteadOfFailingThePracticeStart(){
        // FT-1023-g: 압박 · 정답이 든 여는 말은 그 필드만 빼고 진행한다(추가 호출 없음)
        var s=base();
        var withBadStart=new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),
                s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),null,null,null,"빨리 말해 봐");
        when(provider.generate(any())).thenReturn(generated(withBadStart));
        var issued=run().details().scenes().getFirst();
        assertThat(issued.openingLine()).isNull();
        assertThat(issued.sceneDescription()).isEqualTo(s.sceneDescription());
        var reviewed=org.mockito.ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);
        verify(provider,times(1)).generate(any());verify(provider,times(1)).review(reviewed.capture());
        assertThat(((ScenarioScript)reviewed.getValue().input().get("script")).scenes().getFirst().openingLine()).as("검토는 정리된 대본을 본다").isNull();
    }
    @Test void anOpeningLineThatIsTheOpportunitySentenceIsKept(){
        // scenario-generation.v3: 첫 말이 기회를 바로 열면 기회 문장과 같아도 된다
        var s=base();
        var same=new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),
                s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),null,null,null,s.opportunitySentence());
        when(provider.generate(any())).thenReturn(generated(same));
        assertThat(run().details().scenes().getFirst().openingLine()).isEqualTo(s.opportunitySentence());
    }
    @Test void validSceneStartFieldsAreKept(){
        var s=base();
        var withStart=new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),
                s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),null,null,null,"우와, 너도 블록 놀이 하러 왔어?");
        when(provider.generate(any())).thenReturn(generated(withStart));
        assertThat(run().details().scenes().getFirst().openingLine()).isEqualTo("우와, 너도 블록 놀이 하러 왔어?");
    }
    @Test void approvedButInvalidBaseStillFailsIndependentServerChecks(){var bad=new ScenarioScript.Scene("base","BASE","정답은 잠깐 쉬자", "말", "설명",null,null,"PEER","교실","놀이",null,null);when(provider.generate(any())).thenReturn(generated(bad));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(2)).generate(any());verify(provider,times(2)).review(any());}
    @Test void invalidVariantIsCorrectedOnceThenBaseOnlyReviewed(){var base=base();var variant=new ScenarioScript.Scene("variant","VARIANT",base.sceneDescription(),base.opportunitySentence(),base.reexplanation(),null,null,"PEER","운동장","놀이",null,null);when(provider.generate(any())).thenReturn(generated(base,variant));var result=run();assertThat(result.details().scenes()).hasSize(1);assertThat(result.details().generation().calls()).isEqualTo(5);var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(provider,times(3)).review(calls.capture());assertThat(((ScenarioScript)calls.getAllValues().getLast().input().get("script")).scenes()).hasSize(1);verify(provider,times(2)).generate(any());}
    @Test void baseOnlyMustReceiveItsOwnApproval(){var base=base();var variant=new ScenarioScript.Scene("variant","VARIANT",base.sceneDescription(),base.opportunitySentence(),base.reexplanation(),null,null,"PEER","운동장","놀이",null,null);when(provider.generate(any())).thenReturn(generated(base,variant));when(provider.review(any())).thenReturn(approve(),approve(),reject("AGE_MISMATCH"));assertThatThrownBy(this::run).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);verify(provider,times(3)).review(any());}
    @Test void ageAndSameHistoryReachEveryReviewAndCorrection(){when(provider.review(any())).thenReturn(reject("AGE_MISMATCH"),approve());run();var calls=ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);verify(provider,times(2)).review(calls.capture());assertThat(calls.getAllValues()).allSatisfy(c->assertThat(c.input()).containsEntry("age",8).containsEntry("history",List.of()));}
}
