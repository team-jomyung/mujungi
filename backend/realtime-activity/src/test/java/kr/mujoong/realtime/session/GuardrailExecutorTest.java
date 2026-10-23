package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.guardrail.GuardrailEventRecord;
import kr.mujoong.realtime.guardrail.GuardrailVerdict;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 제브 게이트의 실행기 연결 시험 (U13 W1 ~ W5, 호출 지점 ① ~ ⑧): 위치(inspect 뒤 · recheck 앞), 승인 시점, '생각 중' 유지, 연습 통제 중 호출 0,
 * teardown 취소, 재지시 응답의 폐기 훅, begin 예외, 모드 OFF 불변. 제브 · 공급자는 모두 대역이다(실제 호출 없음, R-F1 · R-M11).
 */
@Tag("jev-guardrail")
@org.junit.jupiter.api.Tag("service-guardrail")
class GuardrailExecutorTest {

    private static final byte[] AUDIO = new byte[4800];
    private static final String CLEAN = "공룡을 좋아하는구나";

    private final SessionHarness h = new SessionHarness();
    private final FakeGuardrailClient guard = h.useGuardrail();
    private int childItems;

    private GuardrailCoordinator coordinator() {
        return h.executor.guardrail();
    }

    @Test
    void timestampedUnitsAreCheckedAndSentBeforeTheResponseEndsInOrder() {
        h.activate();
        childSpeaks();
        timedText("첫 조각", 0, 100);
        timedText("다음 조각", 100, 200);
        timedText("마지막 조각", 200, 300);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[14400]));
        h.drain();
        assertThat(guard.count()).isEqualTo(1);
        assertThat(guard.last().request().candidate()).isEqualTo("첫 조각");
        assertThat(guard.last().request().recentContext()).isEmpty();
        assertThat(h.link.outputs()).isEmpty();
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        // 검사가 도는 동안 쌓인 두 조각은 제브 한 번에 묶어 보내고, 한 번의 ALLOW로 순서대로 나간다
        assertThat(guard.count()).isEqualTo(2);
        assertThat(guard.last().request().candidate()).isEqualTo("다음 조각마지막 조각");
        assertThat(guard.last().request().recentContext()).singleElement()
                .satisfies(turn -> assertThat(turn.text()).isEqualTo("첫 조각"));
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(3);
        assertThat(guard.count()).isEqualTo(2);
        assertThat(coordinator().hasQueued()).isFalse();
    }

    @Test
    void aStreamedResponseIsOneGuardianRowShownWhenTheResponseEnds() {
        h.activate(); childSpeaks();
        timedText("첫 조각", 0, 100);
        timedText("다음 조각", 100, 200);
        timedText("마지막 조각", 200, 300);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[14400]));
        h.drain();
        guard.allow(); h.drain();
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(3);
        // 단위가 나가는 동안은 한 행이 확정 전(PARTIAL · 텍스트 비움)으로만 보인다
        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.PARTIAL);
            assertThat(row.text()).isEmpty();
        });
        // 응답이 끝나면(조용 시간) 통과한 전사가 한 행으로 한꺼번에 나온다
        h.advance(Duration.ofMillis(800));
        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
            assertThat(row.approvedForDisplay()).isTrue();
            assertThat(row.text()).isEqualTo("첫 조각다음 조각마지막 조각");
        });
    }

    @Test
    void theStreamedAiRowIsShownAtOnceWhenTheChildStartsSpeakingBeforeTheResponseEnds() {
        h.activate(); childSpeaks();
        timedText("안내하는 말", 0, 100);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        guard.allow(); h.drain();
        assertThat(aiRows()).singleElement().satisfies(row ->
                assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.PARTIAL));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-2", "잠깐"));
        h.drain();
        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
            assertThat(row.text()).isEqualTo("안내하는 말");
        });
    }

    @Test
    void aNextResponseDoesNotCloseThePreviousRowWhileItsLastUnitIsStillBeingChecked() {
        h.activate(); childSpeaks();
        timedText("앞 응답의 말", 0, 100);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        // 앞 응답의 마지막 단위를 검사하는 중에 응답이 끝났다
        h.advance(Duration.ofMillis(800));
        assertThat(aiRows()).singleElement().satisfies(row ->
                assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.PARTIAL));
        // 늦게 온 허용이 앞 응답의 행을 완성한다
        guard.allow(); h.drain();
        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
            assertThat(row.approvedForDisplay()).isTrue();
            assertThat(row.text()).isEqualTo("앞 응답의 말");
        });
    }

    @Test
    void aBlockedStreamedResponseKeepsOnlyWhatWasAlreadyApprovedInItsRow() {
        h.activate(); childSpeaks();
        timedText("안전한 말", 0, 100);
        timedText("금지된 말", 100, 200);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[9600]));
        h.drain();
        guard.allow(); h.drain();
        guard.block(); h.drain();
        assertThat(aiRows()).singleElement().satisfies(row -> {
            assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
            assertThat(row.text()).isEqualTo("안전한 말").doesNotContain("금지");
        });
    }

    @Test
    void aBatchStopsAtTheCandidateLengthLimitAndTheRestIsCheckedInTheNextCall() {
        h.activate(); childSpeaks();
        String wide = "가".repeat(2_000);
        timedText("첫 조각", 0, 100);
        timedText(wide, 100, 200);
        timedText(wide, 200, 300);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[14400]));
        h.drain();
        guard.allow(); h.drain();
        // 둘째 호출은 첫 넓은 조각 하나뿐이다(묶으면 4,000자로 상한 3,000자를 넘는다)
        assertThat(guard.count()).isEqualTo(2);
        assertThat(guard.last().request().candidate()).isEqualTo(wide);
        guard.allow(); h.drain();
        assertThat(guard.count()).isEqualTo(3);
        assertThat(h.link.outputs()).hasSize(2);
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(3);
    }

    @Test
    void blockingABatchClosesEveryUnitInItWithoutPlayingAnyAndInvalidatesTheRest() {
        h.activate(); childSpeaks();
        timedText("첫 조각", 0, 100);
        timedText("가", 100, 200);
        timedText("나", 200, 300);
        timedText("다", 300, 400);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[19200]));
        h.drain();
        guard.allow(); h.drain();
        assertThat(guard.last().request().candidate()).isEqualTo("가나다");
        guard.block(); h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(coordinator().hasQueued()).isFalse();
        assertThat(aiRows()).as("닫지 못한 AI 행이 없다")
                .noneMatch(entry -> entry.assemblyStatus() != TranscriptEntry.AssemblyStatus.FINAL);
    }

    @Test
    void blockingAStreamingUnitDropsTheRemainingQueuedAudio() {
        h.activate(); childSpeaks();
        timedText("안전한 말", 0, 100);
        timedText("금지된 말", 100, 200);
        timedText("나머지", 200, 300);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[14400]));
        h.drain();
        guard.allow(); h.drain();
        guard.block(); h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(guard.count()).isEqualTo(2);
        assertThat(coordinator().hasQueued()).isFalse();
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);
    }

    @Test
    void theCheckStartsWhenTheTranscriptArrivesAndSoundFollowsAsItArrivesButNeverBeforeItsAllowOrPastItsEnd() {
        h.activate(); childSpeaks();
        timedText("조각", 0, 200);
        assertThat(guard.count()).as("새 말의 음성이 시작되기 전에는 검사하지 않는다(낡은 꼬리 여유 구간)").isZero();
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        // 전사가 음성보다 먼저 오므로, 그 전사가 가리키는 음성(200ms)이 다 오기 전에 검사를 시작한다
        assertThat(guard.count()).isEqualTo(1);
        assertThat(h.link.outputs()).as("ALLOW 전에는 음성이 나가지 않는다").isEmpty();

        guard.allow(); h.drain();
        assertThat(sentMs()).as("ALLOW가 나면 그때까지 도착한 음성(100ms)을 바로 낸다").isEqualTo(100L);

        // 이어서 오는 음성은 도착하는 대로 나가되, 통과한 전사가 끝나는 위치(200ms)를 넘어서는 나가지 않는다
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[48_000]));
        h.drain();
        assertThat(sentMs()).isEqualTo(200L);
    }

    @Test
    void anAllowThatArrivesAfterTheResponseEndedStillSendsTheSoundKeptForIt() {
        h.activate(); childSpeaks();
        timedText("끝난 응답", 0, 200);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[9_600]));
        h.drain();
        assertThat(guard.count()).isEqualTo(1);
        // 응답(조용 0.8초)이 먼저 끝났지만 검사가 아직 돌고 있다: 음성은 버퍼에 남아 있어야 한다
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).isEmpty();
        guard.allow(); h.drain();
        assertThat(sentMs()).isEqualTo(200L);
        assertThat(coordinator().hasQueued()).isFalse();
    }

    @Test
    void aBlockedUnitNeverSendsItsSoundNorTheSoundAfterIt() {
        h.activate(); childSpeaks();
        timedText("막힐 말", 0, 200);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[9_600]));
        h.drain();
        guard.block(); h.drain();
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[9_600]));
        h.drain();
        assertThat(h.link.outputs()).isEmpty();
    }

    private long sentMs() {
        return h.link.outputs().stream().mapToLong(bundle -> bundle.totalMs()).sum();
    }

    @Test
    void aTranscriptLeadingAudioByMoreThanTheQuietWindowIsNotDiscarded() {
        h.activate(); childSpeaks();
        timedText("조금 뒤 들릴 말", 0, 100);
        h.drain();
        h.advanceAlive(Duration.ofMillis(1000));
        assertThat(guard.count()).isZero();
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        assertThat(guard.count()).isEqualTo(1);
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(1);
    }

    @Test
    void aLateAllowForAStreamingUnitCannotPlayAfterTheChildInterrupts() {
        h.activate(); childSpeaks();
        timedText("검사 중인 조각", 0, 100);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        assertThat(guard.count()).isEqualTo(1);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("item-1", "공룡"));
        h.drain();
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-2", "잠깐"));
        h.drain();
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void aStreamingBacklogOverflowInvalidatesThePendingAllowAndClosesEveryQueuedUnit() {
        h.activate(); childSpeaks();
        for (int index = 0; index < 130; index++) {
            timedText("출력 조각", index * 100L, (index + 1) * 100L);
            h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
            h.drain();
        }
        assertThat(guard.count()).isEqualTo(1);
        assertThat(coordinator().hasQueued()).isFalse();
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).isEmpty();
        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.IDLE);
    }

    @Test
    void aUnitAllowedAfterTheChildStartedSpeakingAgainIsLoggedAsAfterChildInput() {
        h.activate(); childSpeaks();
        timedText("앞 응답의 말", 0, 100);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        // 앞 응답의 음성이 다 온 뒤, 마지막 단위를 검사하는 동안 아이가 계속 말한다
        h.advance(Duration.ofMillis(300));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-1", " 있잖아"));
        h.drain();

        guard.allow(); h.drain();

        assertThat(h.link.outputs()).isNotEmpty();
        assertThat(errorKinds()).contains("OutputBundleAfterChildInput");
    }

    @Test
    void aUnitSentBeforeTheChildSpeaksAgainIsNotLoggedAsAfterChildInput() {
        h.activate();
        childFinal("공룡 좋아");
        timedText("앞 응답의 말", 0, 100);
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();

        guard.allow(); h.drain();

        assertThat(h.link.outputs()).isNotEmpty();
        assertThat(errorKinds()).doesNotContain("OutputBundleAfterChildInput");
    }

    private List<String> errorKinds() {
        return h.logs.events(kr.mujoong.contract.log.EventCode.APP_ERROR).stream()
                .map(event -> String.valueOf(kr.mujoong.testsupport.log.LogCapture.attrs(event).get("errorKind"))).toList();
    }

    private void timedText(String text, long start, long end) {
        long margin = h.settings.newSpeechMargin().toMillis();
        if (start == 0) {
            h.provider.last().newSpeechMargin(null);
            String instructionId = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).getLast().eventId();
            h.provider.last().emit(new ProviderEvent.InstructionsAppended(instructionId, 0, 0));
        }
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text, start == 0 ? 0 : start + margin, end + margin));
        if (start == 0) h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
    }

    // ---- 위치 · 승인 시점 ----

    @Test
    void theCheckSitsAfterInspectAndBeforeRecheckSoNothingGoesOutUntilAnAllow() {
        h.activate();
        childSpeaks();

        say(CLEAN);

        assertThat(guard.count()).isEqualTo(1);
        assertThat(h.link.outputs()).isEmpty();
        assertThat(guard.last().request().candidate()).isEqualTo(CLEAN);
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.IDLE);
    }

    @Test
    void aStructurallyBadOrForbiddenSegmentIsDiscardedByInspectBeforeTheCheckerIsEverCalled() {
        h.activate();
        childSpeaks();

        say("검사해 보니 비정상이에요");

        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).isEqualTo(1.0);
    }

    @Test
    void theGuardianViewApprovalHappensOnlyAfterAnAllowAndABlockedRowStaysUnapproved() {
        h.activate();
        childSpeaks();
        say(CLEAN);

        TranscriptEntry waiting = onlyAiRow();
        assertThat(waiting.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.PARTIAL);
        assertThat(waiting.approvedForDisplay()).isFalse();

        guard.allow();
        h.drain();
        TranscriptEntry approved = onlyAiRow();
        assertThat(approved.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(approved.approvedForDisplay()).isTrue();
        assertThat(approved.text()).isEqualTo(CLEAN);
    }

    @Test
    void aBlockedSegmentSendsZeroBytesLeavesAnUnapprovedFinalRowAndCountsTheDiscard() {
        h.activate();
        childSpeaks();
        say(CLEAN);

        guard.block();
        h.drain();

        assertThat(h.link.outputs()).isEmpty();
        TranscriptEntry row = onlyAiRow();
        assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(row.approvedForDisplay()).isFalse();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "guardrail_block")).isEqualTo(1.0);
        assertThat(records()).singleElement().satisfies(event -> {
            assertThat(event.decision()).isEqualTo(GuardrailEventRecord.Decision.BLOCK);
            assertThat(event.mode()).isEqualTo(kr.mujoong.realtime.guardrail.GuardrailMode.REAL);
        });
    }

    // ---- 생각 중 유지 ----

    @Test
    void theDeviceKeepsThinkingWhileTheCheckIsPendingAndListensAgainOnlyAfterThePlaybackEnds() {
        h.activate();
        childFinal("공룡이 좋아");
        long listeningBefore = views().stream().filter(view -> view == View.LISTENING).count();
        say(CLEAN);

        assertThat(coordinator().holdsThinking()).isTrue();
        h.advanceAlive(Duration.ofMillis(500));
        assertThat(views().stream().filter(view -> view == View.LISTENING).count()).isEqualTo(listeningBefore);

        guard.allow();
        h.drain();
        assertThat(coordinator().holdsThinking()).isFalse();
    }

    // ---- 무효 ----

    @Test
    void aNewInputGroupOpenedAfterTheRequestVoidsALateAllowButATailPieceOfTheSameGroupDoesNot() {
        h.activate();
        childSpeaks();
        say(CLEAN);
        // 같은 묶음의 꼬리 조각: 새 말이 아니다
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-1", "를 좋아해"));
        h.drain();
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);

        // 새 묶음: 아이가 말을 새로 시작했다
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("item-1", "공룡을 좋아해", null));
        h.drain();
        h.settleSend();
        say("티라노는 정말 힘이 세지");
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-9", "근데"));
        h.drain();
        guard.allow();
        h.drain();

        assertThat(h.link.outputs()).hasSize(1);
        assertThat(records()).extracting(GuardrailEventRecord::reasonCode).contains("CHILD_SPOKE");
    }

    @Test
    void aSessionTearingDownCancelsTheRequestClosesTheHeldRowAndDropsTheLateResult() {
        h.activate();
        childSpeaks();
        say(CLEAN);
        CompletableFuture<GuardrailVerdict> future = guard.last().future();

        h.mailbox.putSafety(new SessionMailbox.DeletionStop());
        h.drain();

        assertThat(h.removed).isTrue();
        assertThat(future.isCancelled()).isTrue();
        TranscriptEntry row = onlyAiRow();
        assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(row.approvedForDisplay()).isFalse();
        assertThat(h.link.outputs()).isEmpty();
    }

    // ---- 연습 통제 · OFF ----

    @Test
    void whilePracticeControlsTheOutputTheOriginalTextPlaysThroughTheOldGateWithoutCallingTheChecker() {
        SessionHarness practice = practiceHarness();
        FakeGuardrailClient practiceGuard = practice.guard;
        practice.activate();

        practice.speak("내 차례야.", 4800);
        practice.advance(Duration.ofMillis(801));

        assertThat(practiceGuard.count()).isZero();
        assertThat(practice.link.outputs()).hasSize(1);
    }

    @Test
    void whilePracticeControlsTheOutputAChangedTextIsStillDiscardedAsAGatePatternWithoutCallingTheChecker() {
        SessionHarness practice = practiceHarness();
        FakeGuardrailClient practiceGuard = practice.guard;
        practice.activate();

        practice.speak("내 차례야. 이렇게 말해 봐.", 4800);
        practice.advance(Duration.ofMillis(801));

        assertThat(practiceGuard.count()).isZero();
        assertThat(practice.link.outputs()).isEmpty();
        assertThat(practice.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).isEqualTo(1.0);
    }

    private static SessionHarness practiceHarness() {
        SessionHarness practice = new SessionHarness();
        practice.useGuardrail();
        Controlled extension = new Controlled();
        practice.activityType = ActivityType.PRACTICE;
        practice.practice = ignored -> extension;
        return practice;
    }

    @Test
    void withoutAGuardrailSupportTheFlowIsUnchangedAndNoCheckStateExists() {
        SessionHarness plain = new SessionHarness();
        plain.activate();
        plain.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-1", "공룡"));
        plain.drain();

        plain.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(CLEAN));
        plain.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        plain.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        plain.drain();
        plain.advance(Duration.ofMillis(801));

        assertThat(plain.link.outputs()).hasSize(1);
        assertThat(plain.executor.guardrail().enabled()).isFalse();
        assertThat(plain.store.guardrails).isEmpty();
    }

    // ---- 재지시 ----

    @Test
    void theFirstBlockOfAServerPromptedAnswerReplaysThatPromptAndAFreeAnswerGetsTheFreeTurnTemplate() {
        // 참여 뒤 첫 응답은 서버 지시(ResponseStart.FIRST)가 만든다 — 막히면 같은 지시를 다시 보낸다
        h.activate();
        say(CLEAN);
        guard.block();
        h.drain();
        h.settleSend();

        List<String> contents = appended();
        assertThat(contents).hasSize(2);
        assertThat(contents.get(1)).isEqualTo(contents.get(0));
        assertThat(contents.get(1)).doesNotContain(InstructionTemplates.INTERJECTION_LEAD);
        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.RETRYING);

        // 아이가 말한 뒤의 자유 응답은 기억한 지시가 없어 새 템플릿을 쓴다
        guard.allow(guard.last());
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("c-free", "공룡이 좋아", null));
        h.drain();
        assertThat(coordinator().retries()).isZero();
    }

    @Test
    void aFreeAnswerBlockedOnceSendsTheRetryFreeTurnTemplateAndASecondBlockAbandonsWithTheFillerAndNoThirdInstruction() {
        h.activate();
        childFinal("공룡이 좋아");
        h.settleSend();
        say(CLEAN);
        int before = appended().size();

        guard.block();
        h.drain();
        h.settleSend();

        List<String> contents = appended();
        assertThat(contents).hasSize(before + 1);
        assertThat(contents.getLast()).isEqualTo(InstructionTemplates.Retry.RETRY_FREE_TURN.content(h.activityType));
        assertThat(contents.getLast()).doesNotContain(CLEAN);
        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.RETRYING);
        assertThat(coordinator().holdsThinking()).isTrue();

        say("다른 말로 이어 갈게");
        assertThat(guard.count()).isEqualTo(2);
        guard.block();
        h.drain();
        h.settleSend();

        assertThat(appended()).hasSize(before + 1);
        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.link.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::contentCode)
                .contains(PlayApprovedContent.WAITING_FILLER);
    }

    @Test
    void aRetryWhoseNewSpeechNeverStartsAbandonsTheTurnAndReleasesThinking() {
        h.activate();
        childFinal("공룡이 좋아");
        h.settleSend();
        say(CLEAN);
        guard.block();
        h.drain();
        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.RETRYING);

        // 새 말이 오지 않는다: 확인 사건 만기 두 번 뒤 NEW_SPEECH_NOT_OBSERVED
        h.advanceAlive(Duration.ofSeconds(11));
        h.advanceAlive(Duration.ofSeconds(11));

        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(coordinator().holdsThinking()).isFalse();
        assertThat(coordinator().retries()).isEqualTo(1);
        assertThat(guard.count()).isEqualTo(1);
    }

    @Test
    void anEmptyRetryWindowDoesNotAbandonEarly() {
        h.activate();
        childFinal("공룡이 좋아");
        h.settleSend();
        say(CLEAN);
        guard.block();
        h.drain();

        // 응답이 아직 오지 않은 것은 폐기가 아니다: 시한 안에서는 RETRYING을 그대로 둔다
        h.advanceAlive(Duration.ofSeconds(3));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-x", "음"));
        h.drain();

        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.RETRYING);
    }

    // ---- 재지시 응답이 begin 앞에서 폐기되는 길 (discard() 한 지점의 훅) ----

    @Test
    void aRetryResponseDiscardedByAForbiddenPatternLeavesRetryingAndNeverCallsTheCheckerAgain() {
        RetryResponse retry = blockedOnce();

        say("검사해 보니 비정상이에요");

        retry.assertReleased();
    }

    @Test
    void aRetryResponseDiscardedAsIncompleteBecauseItHasNoTranscriptLeavesRetrying() {
        RetryResponse retry = blockedOnce();

        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        h.advance(Duration.ofMillis(801));

        retry.assertReleased();
    }

    @Test
    void aRetryResponseThatNeverHasAudioIsAbandonedByTheQuietCheckAndLeavesRetrying() {
        RetryResponse retry = blockedOnce();

        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("소리 없는 말"));
        h.drain();
        h.advance(Duration.ofSeconds(2));
        h.advance(Duration.ofSeconds(2));

        retry.assertReleased();
    }

    @Test
    void aProviderErrorDuringTheRetryResponseDiscardsTheOpenSegmentAndLeavesRetrying() {
        RetryResponse retry = blockedOnce();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(CLEAN));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();

        h.provider.last().emit(new ProviderEvent.Error("synthetic_provider_error"));
        h.drain();

        retry.assertReleased();
    }

    @Test
    void anExceptionFromTheCheckerPortIsAFailClosedAbandonThatNeverStrandsTheDevice() {
        h.activate();
        childFinal("공룡이 좋아");
        h.settleSend();
        guard.throwOnCheck(new IllegalStateException("합성 어댑터 결함"));

        say(CLEAN);

        assertThat(h.link.outputs()).isEmpty();
        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.IDLE);
        assertThat(coordinator().holdsThinking()).isFalse();
        TranscriptEntry row = onlyAiRow();
        assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(row.approvedForDisplay()).isFalse();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "guardrail_error")).isEqualTo(1.0);
    }

    // ---- 대기열 · 지연 기록 ----

    @Test
    void aSecondSegmentSealedWhileTheFirstIsBeingCheckedWaitsAndIsCheckedAfterTheFirstIsSent() {
        h.activate();
        childFinal("공룡이 좋아");
        h.settleSend();
        say(CLEAN);
        FakeGuardrailClient.Call first = guard.last();

        say("티라노도 좋아하니");
        assertThat(guard.count()).isEqualTo(1);
        assertThat(coordinator().hasQueued()).isTrue();

        guard.allow(first);
        h.drain();

        assertThat(h.link.outputs()).hasSize(1);
        assertThat(guard.count()).isEqualTo(2);
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(2);
        assertThat(aiRows()).allSatisfy(row -> assertThat(row.approvedForDisplay()).isTrue());
    }

    // ---- 도우미 ----

    /** 자유 응답이 한 번 막혀 재지시를 보낸 직후의 상태(RETRYING). */
    private RetryResponse blockedOnce() {
        h.activate();
        childFinal("공룡이 좋아");
        h.settleSend();
        say(CLEAN);
        guard.block();
        h.drain();
        h.settleSend();
        assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.RETRYING);
        return new RetryResponse();
    }

    private final class RetryResponse {
        void assertReleased() {
            assertThat(coordinator().state()).isEqualTo(GuardrailCoordinator.State.IDLE);
            assertThat(coordinator().holdsThinking()).isFalse();
            assertThat(guard.count()).isEqualTo(1);
            assertThat(h.link.outputs()).isEmpty();
            // 훅은 마무리를 하지 않는다: 폐기의 기존 마무리가 대기 문구나 듣기 복귀를 맡는다(재지시는 더 없다)
            assertThat(coordinator().retries()).isEqualTo(1);
        }
    }

    private void childSpeaks() {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-1", "공룡"));
        h.drain();
    }

    private void childFinal(String text) {
        h.childSays("child-" + (++childItems), text);
    }

    /** 공급자가 말하고(전사 + 음성 두 조각) 조용 시간을 지나 구간이 봉인되게 한다. */
    private void say(String text) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        h.advance(Duration.ofMillis(801));
    }

    private List<View> views() {
        return h.link.sent(SessionState.class).stream().map(SessionState::view).toList();
    }

    private List<String> appended() {
        return h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).stream()
                .map(ProviderRequestFactory.InstructionsAppend::content).toList();
    }

    private List<GuardrailEventRecord> records() {
        h.guardrailRecorder.tick();
        return List.copyOf(h.store.guardrails);
    }

    private List<TranscriptEntry> aiRows() {
        h.recorder.tick();
        return h.store.transcripts.stream().filter(row -> row.speaker() == TranscriptEntry.Speaker.AI).toList();
    }

    private TranscriptEntry onlyAiRow() {
        List<TranscriptEntry> rows = aiRows();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    /** 출력을 쥔 최소 연습 확장점(원문 "내 차례야."만 읽는다). */
    private static final class Controlled implements PracticeSessionExtension {
        PracticeOutput out;
        private final kr.mujoong.realtime.voice.PracticeSpeech speech = new kr.mujoong.realtime.voice.PracticeSpeech(
                UUID.randomUUID(), UUID.randomUUID(), 1, kr.mujoong.realtime.practicelegacy.PracticePorts.OutputKind.SCENARIO, "친구",
                "내 차례야.");

        kr.mujoong.realtime.voice.PracticeSpeech speech() {
            return speech;
        }

        @Override
        public boolean controlsOutput() {
            return true;
        }

        @Override
        public Signal onActive(PracticeContext context, PracticeOutput output) {
            out = output;
            output.speak(speech);
            return Signal.NONE;
        }

        @Override
        public Signal onChildUtterance(Utterance utterance, PracticeOutput output) {
            return Signal.NONE;
        }

        @Override
        public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output) {
            return Signal.NONE;
        }

        @Override
        public void onSettle(EndContext end) {
            // 정리할 것이 없다
        }

        @Override
        public Object practiceRecord() {
            return null;
        }
    }
}
