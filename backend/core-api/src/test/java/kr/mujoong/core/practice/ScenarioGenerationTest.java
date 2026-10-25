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
        // FT-1023-g: 기회 문장을 되풀이한 여는 말은 그 필드만 빼고 진행한다(추가 호출 없음)
        var s=base();
        var withBadStart=new ScenarioScript.Scene(s.sceneKey(),s.sceneKind(),s.sceneDescription(),s.opportunitySentence(),s.reexplanation(),
                s.focusPoints(),s.expressionExamples(),s.relation(),s.place(),s.topic(),null,null,null,s.opportunitySentence());
        when(provider.generate(any())).thenReturn(generated(withBadStart));
        var issued=run().details().scenes().getFirst();
        assertThat(issued.openingLine()).isNull();
        assertThat(issued.sceneDescription()).isEqualTo(s.sceneDescription());
        var reviewed=org.mockito.ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);
        verify(provider,times(1)).generate(any());verify(provider,times(1)).review(reviewed.capture());
        assertThat(((ScenarioScript)reviewed.getValue().input().get("script")).scenes().getFirst().openingLine()).as("검토는 정리된 대본을 본다").isNull();
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

    // ---- 연습 대화 C안(docs/practice-direct-ask.md 4.1 · 4.4): 판 기록, 검토 입력, 칸별 빼기 ----

    /** C안 장면 다섯 칸 이름(계약 이름). */
    static final List<String> DIRECT_ASK_KEYS = List.of("opportunityRelay", "askPrompt", "reaskPrompt", "helpSpeech", "friendReaction");

    @Test
    void anApprovedScriptIsRecordedAsScriptV2WithGenerationV3AndTheMainReviewV1() {
        var result = run();

        assertThat(result.details().scriptVersion()).isEqualTo("scenario-script.v2");
        assertThat(result.details().generation().promptVersion()).isEqualTo("scenario-generation.v3");
        assertThat(result.details().review().promptVersion()).isEqualTo("scenario-review.v1");
        assertThat(result.legacy().promptVersion()).as("core_scenario.prompt_version").isEqualTo("scenario-generation.v3");
        assertThat(List.of(ScenarioScriptProvider.SCRIPT_VERSION, ScenarioScriptProvider.GENERATION_PROMPT_VERSION,
                ScenarioScriptProvider.REVIEW_PROMPT_VERSION))
                .containsExactly("scenario-script.v2", "scenario-generation.v3", "scenario-review.v1");
    }

    @Test
    void theReviewSeesTheSceneWithoutTheFiveDirectAskFieldsWhileTheStoredSceneKeepsThem() {
        var direct = ScenarioDirectAskValidationTest.direct().withSpokenIntroduction("친구랑 블록 놀이를 하는 이야기야.");
        when(provider.generate(any())).thenReturn(generated(direct));

        var stored = run().details().scenes().getFirst();

        var reviewed = ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);
        verify(provider).review(reviewed.capture());
        var seen = ((ScenarioScript) reviewed.getValue().input().get("script")).scenes().getFirst();
        assertThat(java.util.Arrays.asList(seen.opportunityRelay(), seen.askPrompt(), seen.reaskPrompt(), seen.helpSpeech(),
                seen.friendReaction())).as("검토(main review.v1)는 C안 칸을 보지 않는다").containsOnlyNulls();
        assertThat(seen.spokenIntroduction()).as("상황 안내는 main도 검토한다").isEqualTo(direct.spokenIntroduction());
        assertThat(List.of(seen.sceneDescription(), seen.opportunitySentence(), seen.reexplanation()))
                .containsExactly(direct.sceneDescription(), direct.opportunitySentence(), direct.reexplanation());
        assertThat(stored.opportunityRelay()).isEqualTo(direct.opportunityRelay());
        assertThat(stored.askPrompt()).isEqualTo(direct.askPrompt());
        assertThat(stored.reaskPrompt()).isEqualTo(direct.reaskPrompt());
        assertThat(stored.helpSpeech()).isEqualTo(direct.helpSpeech());
        assertThat(stored.friendReaction()).isEqualTo(direct.friendReaction());
        assertThat(stored.partnerRole()).isEqualTo(ScenarioContentPolicy.PARTNER_ROLE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReviewInputLeavesTheFiveKeysOutOfTheJsonInsteadOfSendingNulls() {
        when(provider.generate(any())).thenReturn(generated(ScenarioDirectAskValidationTest.direct()
                .withSpokenIntroduction("친구랑 블록 놀이를 하는 이야기야.")));
        run();
        var reviewed = ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);
        verify(provider).review(reviewed.capture());
        var call = reviewed.getValue();

        String json = kr.mujoong.contract.json.JsonCodec.shared().write(call.input());
        assertThat(json).contains("\"spokenIntroduction\"", "\"sceneDescription\"");
        for (String key : DIRECT_ASK_KEYS) assertThat(json).as(key).doesNotContain("\"" + key + "\"");
        // 공급자로 가는 모양(호출부의 입력 정리 결과)도 같다
        var sent = new kr.mujoong.core.luna.LunaInputSanitizer(kr.mujoong.contract.json.JsonCodec.shared())
                .sanitize(call.input(), call.aliases(), kr.mujoong.core.luna.LunaPurpose.SCENARIO_REVIEW).payload();
        var scene = ((List<Map<String, Object>>) ((Map<String, Object>) sent.get("script")).get("scenes")).getFirst();
        assertThat(scene).containsKey("spokenIntroduction").doesNotContainKeys(DIRECT_ASK_KEYS.toArray(String[]::new));
    }

    @Test
    void aDirectAskFieldProblemIsIssuedOnTheFirstAttemptWithOnlyThatFieldRemoved() {
        var codes = new java.util.ArrayList<String>();
        generator.diagnostics(codes::add);
        var bad = ScenarioDirectAskValidationTest.with("reaction", "잘했어! 친구도 고마워할 거야.");
        when(provider.generate(any())).thenReturn(generated(bad));

        var result = run();

        assertThat(result.details().generation().calls()).as("생성 1 + 검토 1").isEqualTo(2);
        verify(provider).generate(any());
        verify(provider).review(any());
        assertThat(codes).containsExactly("ScenarioField_friendReaction_SPOKEN_PUNCTUATION");
        var stored = result.details().scenes().getFirst();
        assertThat(stored.friendReaction()).as("걸린 칸만 뺀다(realtime이 대체 문장을 읽는다)").isNull();
        assertThat(List.of(stored.opportunityRelay(), stored.askPrompt(), stored.reaskPrompt(), stored.helpSpeech()))
                .containsExactly(bad.opportunityRelay(), bad.askPrompt(), bad.reaskPrompt(), bad.helpSpeech());
        assertThat(kr.mujoong.contract.json.JsonCodec.shared().write(stored)).as("뺀 칸은 저장 JSON에 키가 없다")
                .doesNotContain("friendReaction").contains("opportunityRelay");
    }

    @Test
    void everyKindOfFieldProblemRemovesOnlyThatFieldWithAFixedDiagnostic() {
        var codes = new java.util.ArrayList<String>();
        generator.diagnostics(codes::add);
        var bad = ScenarioDirectAskValidationTest.direct()
                .withDirectAsk("친구가 처음 들어서 궁금해한대.", "너라면 선생님께 뭐라고 말씀드릴래?",
                        "친구가 그만하고 싶어 하는 것 같지? 어떤 말을 해 줄까?", "잠깐 쉬자고 말해 봐.", ScenarioDirectAskValidationTest.REACTION)
                .withSpokenIntroduction("친구랑 블록 놀이를 하고 있다.");
        when(provider.generate(any())).thenReturn(generated(bad));

        var stored = run().details().scenes().getFirst();

        assertThat(codes).containsExactly("ScenarioField_spokenIntroduction_SPOKEN_FORMAT",
                "ScenarioField_opportunityRelay_PARTNER_INTERPRETED", "ScenarioField_askPrompt_ASK_PROMPT_RELATION",
                "ScenarioField_reaskPrompt_PARTNER_INTERPRETED", "ScenarioField_helpSpeech_HELP_TAIL_MISSING");
        assertThat(java.util.Arrays.asList(stored.spokenIntroduction(), stored.opportunityRelay(), stored.askPrompt(),
                stored.reaskPrompt(), stored.helpSpeech())).containsOnlyNulls();
        assertThat(stored.friendReaction()).isEqualTo(ScenarioDirectAskValidationTest.REACTION);
        assertThat(codes).as("진단은 고정 코드뿐이다(대본 글이 없다)").allMatch(code -> code.matches("[A-Za-z_]+"));
        verify(provider).generate(any());
    }

    @Test
    void aLowFidelityRelayOrALeakedTargetIsRemovedWithoutAskingForACorrection() {
        var codes = new java.util.ArrayList<String>();
        generator.diagnostics(codes::add);
        var bad = ScenarioDirectAskValidationTest.with("relay", "그런데 친구가 다른 놀이가 하고 싶대.")
                .withDirectAsk("그런데 친구가 다른 놀이가 하고 싶대.", "너라면 잠깐 쉬자고 말할래?", ScenarioDirectAskValidationTest.REASK,
                        ScenarioDirectAskValidationTest.HELP, ScenarioDirectAskValidationTest.REACTION);
        when(provider.generate(any())).thenReturn(generated(bad));

        var stored = run().details().scenes().getFirst();

        assertThat(codes).containsExactly("ScenarioField_opportunityRelay_RELAY_LOW_FIDELITY",
                "ScenarioField_askPrompt_TARGET_EXPRESSION_DISCLOSED");
        assertThat(stored.opportunityRelay()).isNull();
        assertThat(stored.askPrompt()).isNull();
        var calls = ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);
        verify(provider).generate(calls.capture());
        assertThat(calls.getValue().input()).doesNotContainKey("correctionReasons");
    }

    @Test
    void aRequiredFieldProblemStillFailsTheSceneAndIsCorrectedAsBefore() {
        var codes = new java.util.ArrayList<String>();
        generator.diagnostics(codes::add);
        var direct = ScenarioDirectAskValidationTest.direct();
        var bad = direct.withDescription(direct.sceneDescription() + " 잠깐 쉬자.").withDirectAsk(direct.opportunityRelay(),
                direct.askPrompt(), direct.reaskPrompt(), direct.helpSpeech(), "잘했어.");
        when(provider.generate(any())).thenReturn(generated(bad), generated(direct));

        var result = run();

        assertThat(codes).as("실패한 시도의 C안 칸은 진단하지 않는다(발급할 때만)")
                .containsExactly("ScenarioRule_TARGET_EXPRESSION_DISCLOSED", "ScenarioValidation_INVALID_BASE");
        var calls = ArgumentCaptor.forClass(ScenarioScriptProvider.Call.class);
        verify(provider, times(2)).generate(calls.capture());
        assertThat(calls.getAllValues().getLast().input()).containsEntry("correctionReasons", List.of("INVALID_BASE"));
        assertThat(((ScenarioScript) calls.getAllValues().getLast().input().get("scriptToRepair")).scenes().getFirst()
                .friendReaction()).as("수정 요청에는 C안 칸이 그대로 실린다").isEqualTo("잘했어.");
        assertThat(result.details().scenes().getFirst().friendReaction()).isEqualTo(direct.friendReaction());
        assertThat(result.details().generation().calls()).isEqualTo(4);
    }

    @Test
    void aVariantSceneLosesOnlyItsOwnBadFieldsAndTheBaseKeepsAll() {
        var codes = new java.util.ArrayList<String>();
        generator.diagnostics(codes::add);
        var base = ScenarioDirectAskValidationTest.direct();
        var variant = new ScenarioScript.Scene("variant", "VARIANT", base.sceneDescription(), base.opportunitySentence(),
                base.reexplanation(), base.focusPoints(), base.expressionExamples(), "PEER", "운동장", "놀이", null, null)
                .withDirectAsk("친구가 처음 들어서 궁금해한대.", ScenarioDirectAskValidationTest.ASK, ScenarioDirectAskValidationTest.REASK,
                        ScenarioDirectAskValidationTest.HELP, "잘했어.");
        when(provider.generate(any())).thenReturn(generated(base, variant));
        var r = rules();
        var accepting = new ScenarioScriptValidation.Rules(r.relation(), r.opportunity(), r.targetExpression(), r.originalNames(), null,
                x -> false, (a, b) -> true);

        var result = generator.generate(new ScenarioScriptGenerator.Input(Map.of("age", 8, "history", List.of()), Map.of("합성아이", "아이"),
                accepting, false, "{}"), new ScenarioDeadline(clock, clock.now().plusSeconds(15)));

        assertThat(result.details().scenes()).hasSize(2);
        var storedBase = result.details().scenes().getFirst();
        var storedVariant = result.details().scenes().getLast();
        assertThat(List.of(storedBase.opportunityRelay(), storedBase.friendReaction())).containsExactly(base.opportunityRelay(),
                base.friendReaction());
        assertThat(storedVariant.opportunityRelay()).isNull();
        assertThat(storedVariant.friendReaction()).isNull();
        assertThat(storedVariant.askPrompt()).isEqualTo(ScenarioDirectAskValidationTest.ASK);
        assertThat(codes).containsExactly("ScenarioField_opportunityRelay_PARTNER_INTERPRETED",
                "ScenarioField_friendReaction_REACTION_PRAISE");
        assertThat(result.details().generation().calls()).isEqualTo(2);
    }

    @Test
    void theIncludedPartnerLineRepairKeepsTheDirectAskFields() {
        var direct = ScenarioDirectAskValidationTest.direct();
        var indirect = direct.withDescription("블록 놀이 중이야. " + OPPORTUNITY);
        when(provider.generate(any())).thenReturn(generated(indirect));

        var stored = run().details().scenes().getFirst();

        assertThat(stored.sceneDescription()).contains(direct.opportunitySentence());
        assertThat(stored.opportunityRelay()).isEqualTo(direct.opportunityRelay());
        assertThat(stored.helpSpeech()).isEqualTo(direct.helpSpeech());
    }

}
