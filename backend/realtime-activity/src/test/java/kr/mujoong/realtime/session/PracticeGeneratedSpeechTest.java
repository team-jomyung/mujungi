package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.guardrail.GuardrailRequest;
import kr.mujoong.realtime.practicelegacy.PracticePorts;
import kr.mujoong.realtime.testing.FakeGuardrailClient;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.PracticeSpeech;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 생성형 역할 발화 슬롯의 실행부 연결 (U12 기능 설계 3.5 · BR5.4 ~ BR5.7): {@code speakGenerated}로 연 슬롯의 새 발화는 제브(연습 모드)가
 * ALLOW한 것만 재생되고, 막힘 · 제브 비활성은 0바이트이며, 슬롯은 어떤 원인으로 닫혀도 종료 사실이 한 번만 확장에 닿는다. 제브 · 공급자는 모두
 * 시험이 손으로 푸는 대역이다(실제 호출 없음, R-F1). 이 시험의 성공은 테스트 대역 · 합성 대화의 성공이며 실제 공급자 · 실제 기기 성공이 아니다
 * (R-M11).
 */
@Tag("practice-flow")
@org.junit.jupiter.api.Tag("service-guardrail")
class PracticeGeneratedSpeechTest {
    @Test
    void practiceDrainsOldTailBeforeSendingAndKeepsTheWholeShortNextUtterance() {
        start(true);h.provider.last().newSpeechMargin(null);
        UUID old=UUID.randomUUID();extension.out.speakGenerated(old,"PEER",INSTRUCTION);h.settleSend();
        h.speak("old tail",4800);extension.out.cancelSpeech(old);
        var speech=PracticeSpeech.scenario(UUID.randomUUID(),UUID.randomUUID(),1,"친구","응.");
        int requests=appended().size();extension.out.speak(speech);h.settleSend();
        assertThat(appended()).hasSize(requests);
        h.speak("late old tail",4800);h.advance(Duration.ofMillis(801));h.settleSend();
        assertThat(appended()).hasSize(requests+1);
        h.speak(speech.text(),960);h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.link.outputs().getFirst().totalMs()).isEqualTo(20);
        assertThat(guard.count()).isZero();
    }

    @Test
    void aStopDuringOldOutputDrainCannotSendTheDeferredPracticeInstruction() {
        start(true);UUID old=UUID.randomUUID();extension.out.speakGenerated(old,"PEER",INSTRUCTION);h.settleSend();
        h.speak("old tail",4800);extension.out.cancelSpeech(old);
        extension.out.speak(PracticeSpeech.scenario(UUID.randomUUID(),UUID.randomUUID(),1,"친구","응."));
        int requests=appended().size();
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("stop-drain","무중아 그만할래"));h.drain();
        h.advance(SessionHarness.SETTINGS.inputTranscriptQuiet().plusMillis(1));
        h.advance(Duration.ofMillis(801));h.settleSend();
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.CLOSING);
        assertThat(appended()).hasSize(requests);
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test void inputBeforeTheFirstGeneratedChunkYieldsWithoutAnInstructionTimeoutFailure(){
        start(true);UUID slot=UUID.randomUUID();extension.out.speakGenerated(slot,"PEER",INSTRUCTION);h.settleSend();
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("before-audio","무슨 놀이를 하고 싶어"));h.drain();
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.INTERRUPTED);
        h.advanceAlive(Duration.ofSeconds(11));
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.ACTIVE);
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.INTERRUPTED);
        assertThat(h.link.outputs()).isEmpty();assertThat(guard.count()).isZero();
    }
    @Test
    void childInputDuringUnplayedGeneratedAudioDiscardsOnlyTheAudioAndTheNextReplyStillNeedsJev() {
        start(true);UUID slot=UUID.randomUUID();
        extension.out.speakGenerated(slot,"PEER",INSTRUCTION);h.settleSend();
        h.speak("음, 편할 때",4800);h.advance(Duration.ofMillis(187));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("during-collection","무슨 놀이를 하고 싶어"));h.drain();
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.INTERRUPTED);
        assertThat(h.link.outputs()).isEmpty();assertThat(guard.count()).isZero();
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.ACTIVE);
        assertThat(kr.mujoong.testsupport.log.LogCapture.attrs(h.logs.last(
                kr.mujoong.contract.log.EventCode.OUTPUT_DISCARDED).orElseThrow()))
                .containsEntry("discardDetail", "INPUT_SUPERSEDED_UNPLAYED");
        h.speak(" 말해도 돼.",4800);h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).isEmpty();
        UUID next=UUID.randomUUID();extension.replySlot=next;
        h.childSays("reply","무슨 놀이를 하고 싶어");h.settleSend();say("나는 블록 놀이를 하고 싶어.");
        assertThat(guard.count()).as("discard=%s next=%s status=%s",h.metrics.registry().find("mujoong.gate.discard").counters().stream()
                .map(c->c.getId().getTags()+"="+c.count()).toList(),extension.endFacts(next),h.executor.status()).isEqualTo(1);assertThat(h.link.outputs()).isEmpty();
        guard.allow();h.drain();assertThat(h.link.outputs()).hasSize(1);
    }
    @Test
    void stopWhileCollectingConversationalAudioClosesAndNeverPlaysThePartialReply() {
        start(true);extension.out.speakGenerated(UUID.randomUUID(),"PEER",INSTRUCTION);h.settleSend();
        h.speak("음, 편할 때",4800);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("stop-partial","무중아 그만할래"));h.drain();
        h.advance(SessionHarness.SETTINGS.inputTranscriptQuiet().plusMillis(1));
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.CLOSING);
        assertThat(h.link.outputs()).isEmpty();assertThat(guard.count()).isZero();
    }
    @Test
    void restWhileCollectingConversationalAudioStillUsesTheImmediateRestPath() {
        var judge=h.useJudge();start(true);extension.out.speakGenerated(UUID.randomUUID(),"PEER",INSTRUCTION);h.settleSend();
        h.speak("음, 편할 때",4800);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("rest-partial","쉬고 싶어"));h.drain();
        h.advance(SessionHarness.SETTINGS.inputTranscriptQuiet().plusMillis(1));
        assertThat(extension.events).anySatisfy(event -> assertThat(event.kind())
                .isEqualTo(PracticeSessionExtension.ExtensionEvent.Kind.REST_REQUESTED));
        assertThat(judge.calls()).isEmpty();
        assertThat(h.link.outputs()).isEmpty();assertThat(guard.count()).isZero();
    }

    @Test void approvedMismatchIsDistinctFromAForbiddenPattern() {
        start(true);
        extension.recovery = true;
        PracticeSpeech speech = PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 1,
                "친구", "나는 블록 놀이를 하고 싶어.", PracticeSpeech.ReadingCue.SCENE);
        assertThat(extension.out.speak(speech)).isTrue(); h.settleSend();
        say("나는 카드 놀이를 하고 싶어.");
        assertThat(kr.mujoong.testsupport.log.LogCapture.attrs(h.logs.last(
                kr.mujoong.contract.log.EventCode.OUTPUT_DISCARDED).orElseThrow()))
                .containsEntry("discardDetail", "APPROVED_TEXT_MISMATCH")
                .containsEntry("generatedSpeech", false)
                .containsKey("expectedChars");
        assertThat(guard.count()).isZero(); assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.mismatches).singleElement().satisfies(event -> {
            assertThat(event.correlationId()).isEqualTo(speech.correlationId());
            assertThat(event.value()).isEqualTo(speech);
        });
        assertThat(extension.endFacts(speech.correlationId())).isEmpty();
    }

    private static final String INSTRUCTION = "친구로서 방금 말을 한 번 더 물어보세요";

    @Test
    void approvedUnitsPlayBeforeTheResponseEndsAndCompleteOnlyAfterAllPlayback() {
        var speech = startApprovedStream();
        approvedUnit("나는 블록 ", 0);
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(guard.count()).isZero();
        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(speech.correlationId())).isEmpty();

        approvedUnit("놀이를 하고 싶어.", 1);
        assertThat(h.link.outputs()).hasSize(2);
        h.advance(Duration.ofMillis(801));
        assertThat(extension.endFacts(speech.correlationId())).isEmpty();
        mark(h.link.outputs().getLast().playbackId(), PlaybackMark.Status.COMPLETED);
        mark(h.link.outputs().getLast().playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(speech.correlationId())).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(extension.mismatches).isEmpty();
        assertThat(guard.count()).isZero();
    }

    @Test
    void anApprovedMismatchBeforePlaybackUsesReadingRecoveryAndSendsNothing() {
        var speech = startApprovedStream();
        approvedUnit("나는 카드 ", 0);
        assertThat(h.link.outputs()).isEmpty();
        assertThat(guard.count()).isZero();
        assertThat(extension.mismatches).singleElement()
                .satisfies(event -> assertThat(event.correlationId()).isEqualTo(speech.correlationId()));
        assertThat(extension.endFacts(speech.correlationId())).isEmpty();
    }

    @Test
    void anApprovedMismatchAfterPlaybackDiscardsTheTailWithoutReplayingThePrefix() {
        var speech = startApprovedStream();
        approvedUnit("나는 블록 ", 0);
        approvedUnit("놀이가 싫어.", 1);
        approvedUnit("더 해봐.", 2);
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(extension.endFacts(speech.correlationId())).isEmpty();
        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.COMPLETED);
        h.advance(Duration.ofMillis(801));
        assertThat(extension.endFacts(speech.correlationId())).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(extension.mismatches).isEmpty();
        assertThat(guard.count()).isZero();
    }

    @Test
    void aTruncatedApprovedResponseClosesOnceAfterItsSentAudioCompletes() {
        var speech = startApprovedStream();
        approvedUnit("나는 블록 ", 0);
        h.advance(Duration.ofMillis(801));
        assertThat(extension.endFacts(speech.correlationId())).isEmpty();
        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(speech.correlationId())).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(extension.mismatches).isEmpty();
    }

    // ---- 장면 속 역할이 없는 연습(C안)의 승인 낭독 지시 (docs/practice-direct-ask.md 7.6) ----

    @Test
    void aPracticeWithoutRolesSendsTheReadingWithoutRoleOrQuotationWordingAndStillPlaysTheExactText() {
        extension.mujoong = true;
        start(true);
        var speech = PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 1, "무중",
                "그런데 친구가 손이 좀 아프대. 너라면 친구한테 뭐라고 말할래?", PracticeSpeech.ReadingCue.PEER);

        assertThat(extension.out.speak(speech)).isTrue();
        h.settleSend();

        assertThat(appended().getLast()).contains("<approved_speech>" + speech.text() + "</approved_speech>",
                "태그 안의 글자만 처음부터 끝까지 그대로 읽는다", "덧붙이지 않는다", "낭독문 마지막 글자에서 즉시 끝낸다", "다음 서버 지시를 기다린다")
                .doesNotContain("따옴표", "partnerRole", "배경 역할", "역할극");
        say(speech.text());
        assertThat(h.link.outputs()).as("원문과 같은 낭독은 그대로 제브 없이 재생된다").hasSize(1);
        assertThat(guard.count()).isZero();
        assertThat(extension.mismatches).isEmpty();
    }

    @Test
    void theRolelessReadingStillPlaysApprovedUnitsBeforeTheResponseEnds() {
        extension.mujoong = true;
        var speech = startApprovedStream();
        assertThat(appended().getLast()).doesNotContain("따옴표", "partnerRole");

        approvedUnit("나는 블록 ", 0);
        assertThat(h.link.outputs()).as("B1 조기 재생 그대로: 응답이 끝나기 전에 첫 단위가 나간다").hasSize(1);
        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(speech.correlationId())).isEmpty();
        approvedUnit("놀이를 하고 싶어.", 1);
        assertThat(h.link.outputs()).hasSize(2);
        h.advance(Duration.ofMillis(801));
        mark(h.link.outputs().getLast().playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(speech.correlationId())).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(extension.mismatches).isEmpty();
        assertThat(guard.count()).isZero();
    }

    @Test
    void aRoleDialoguePracticeKeepsTheRoleAndQuotationWordingOfTheReadingInstruction() {
        start(true);
        var speech = PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 1, "친구", "친구가 \"같이 놀자.\"라고 말한다.",
                PracticeSpeech.ReadingCue.PEER);

        assertThat(extension.out.speak(speech)).isTrue();
        h.settleSend();

        assertThat(appended().getLast()).contains("이전 대화와 역할극 응답 지시를 이어가지 않고", "partnerRole", "친구",
                "따옴표 안 상대 대사도 생략하거나 바꾸지 않는다", "<approved_speech>" + speech.text() + "</approved_speech>");
    }

    private PracticeSpeech startApprovedStream() {
        start(true);
        extension.recovery = true;
        var speech = PracticeSpeech.scenario(UUID.randomUUID(), UUID.randomUUID(), 1,
                "PEER", "나는 블록 놀이를 하고 싶어.", PracticeSpeech.ReadingCue.PEER);
        assertThat(extension.out.speak(speech)).isTrue();
        h.settleSend();
        h.provider.last().newSpeechMargin(null);
        var request = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).getLast();
        h.provider.last().emit(new ProviderEvent.InstructionsAppended(request.eventId(), 0, 0));
        return speech;
    }

    private void approvedUnit(String text, int index) {
        long margin = h.settings.newSpeechMargin().toMillis();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text,
                index == 0 ? 0 : margin + index * 100L, margin + (index + 1) * 100L));
        if (index == 0) h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[4800]));
        h.drain();
    }
    private static final String SPOKEN = "그거 줘 하면 어느 색을 말하는 건지 잘 모르겠어";

    private final SessionHarness h = new SessionHarness();
    private final Recording extension = new Recording();
    private FakeGuardrailClient guard;

    private void start(boolean withGuardrail) {
        if (withGuardrail) {
            guard = h.useGuardrail();
        }
        h.activityType = ActivityType.PRACTICE;
        h.practice = ignored -> extension;
        h.activate();
    }

    @Test
    void generatedUnitsPlayEarlyButCompleteThePracticeSlotOnlyOnceAfterTheWholeStream() {
        start(true);
        UUID slot = UUID.randomUUID();
        extension.out.speakGenerated(slot, "PEER", INSTRUCTION);
        h.settleSend();
        h.provider.last().newSpeechMargin(null);
        var request = h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).getLast();
        h.provider.last().emit(new ProviderEvent.InstructionsAppended(request.eventId(), 0, 0));
        long margin = h.settings.newSpeechMargin().toMillis();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("첫 조각", 0, margin + 100));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[(int) margin * 48]));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("다음 조각", margin + 100, margin + 200));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(new byte[9600]));
        h.drain();
        assertThat(guard.count()).isEqualTo(1);
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(guard.count()).isEqualTo(2);
        mark(h.link.outputs().getFirst().playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(slot)).isEmpty();
        guard.allow(); h.drain();
        assertThat(h.link.outputs()).hasSize(2);
        mark(h.link.outputs().getLast().playbackId(), PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(slot)).isEmpty();
        h.advance(Duration.ofMillis(801));
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.COMPLETED);
    }

    @Test
    void anAllowedGeneratedUtterancePlaysAndTheEndFactArrivesExactlyOnce() {
        start(true);
        UUID slot = UUID.randomUUID();

        assertThat(extension.out.speakGenerated(slot, "PEER", INSTRUCTION)).isTrue();
        h.settleSend();
        say(SPOKEN);

        assertThat(guard.count()).isEqualTo(1);
        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        assertThat(h.link.outputs()).isEmpty();
        guard.allow();
        h.drain();
        assertThat(h.link.outputs()).hasSize(1);

        UUID playback = h.link.outputs().getFirst().playbackId();
        mark(playback, PlaybackMark.Status.STARTED);
        mark(playback, PlaybackMark.Status.COMPLETED);
        mark(playback, PlaybackMark.Status.COMPLETED);

        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.COMPLETED);
        assertThat(extension.blocked).isEmpty();
    }

    @Test
    void aCoachSlotIsCheckedInTheCoachPracticeMode() {
        start(true);

        assertThat(extension.out.speakGenerated(UUID.randomUUID(), "COACH", INSTRUCTION)).isTrue();
        h.settleSend();
        say(SPOKEN);

        assertThat(guard.last().request().mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_COACH);
    }

    @Test
    void aBlockedUtteranceIsRetriedWithTheSameRoleInstructionAndASecondBlockSendsZeroBytesAndOneFailureFact() {
        start(true);
        UUID slot = UUID.randomUUID();
        extension.out.speakGenerated(slot, "PEER", INSTRUCTION);
        h.settleSend();
        say(SPOKEN);

        guard.block();
        h.drain();
        h.settleSend();

        List<String> contents = appended();
        assertThat(contents).hasSizeGreaterThanOrEqualTo(2);
        assertThat(contents.getLast()).isEqualTo(contents.get(contents.size() - 2)).contains(INSTRUCTION);
        assertThat(extension.endFacts(slot)).isEmpty();

        say("다시 한 말");
        assertThat(guard.count()).isEqualTo(2);
        guard.block();
        h.drain();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.blocked).singleElement().satisfies(event -> {
            assertThat(event.correlationId()).isEqualTo(slot);
            assertThat(event.mode()).isEqualTo(GuardrailRequest.Mode.PRACTICE_PEER);
        });
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.FAILED);
        var discarded = h.logs.events(kr.mujoong.contract.log.EventCode.OUTPUT_DISCARDED);
        assertThat(discarded).hasSize(2);
        assertThat(discarded).allSatisfy(e -> assertThat(kr.mujoong.testsupport.log.LogCapture.attrs(e))
                .containsEntry("discardDetail", "GUARDRAIL_BLOCK"));
    }

    @Test
    void withoutTheGuardrailStageAGeneratedUtteranceIsDiscardedFailClosed() {
        start(false);
        UUID slot = UUID.randomUUID();

        assertThat(extension.out.speakGenerated(slot, "PEER", INSTRUCTION)).isTrue();
        h.settleSend();
        say(SPOKEN);

        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.FAILED);
        assertThat(kr.mujoong.testsupport.log.LogCapture.attrs(h.logs.last(
                kr.mujoong.contract.log.EventCode.OUTPUT_DISCARDED).orElseThrow()))
                .containsEntry("discardDetail", "GUARDRAIL_DISABLED");
    }

    @Test
    void aChildWhoSpeaksDuringTheCheckClosesTheSlotAsInterruptedOnceAndALateAllowPlaysNothing() {
        start(true);
        UUID slot = UUID.randomUUID();
        extension.out.speakGenerated(slot, "PEER", INSTRUCTION);
        h.settleSend();
        say(SPOKEN);

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-9", "근데"));
        h.drain();
        guard.allow();
        h.drain();

        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.INTERRUPTED);
    }

    @Test
    void aRetryWhoseNewSpeechNeverStartsClosesTheSlotAsFailedOnce() {
        start(true);
        UUID slot = UUID.randomUUID();
        extension.out.speakGenerated(slot, "PEER", INSTRUCTION);
        h.settleSend();
        say(SPOKEN);
        guard.block();
        h.drain();

        h.advanceAlive(Duration.ofSeconds(11));
        h.advanceAlive(Duration.ofSeconds(11));

        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.FAILED);
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void anUtteranceWithoutAnOpenSlotIsStillDiscardedAndNeverReachesTheChecker() {
        start(true);

        say(SPOKEN);

        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void aRoleSwitchNoticeUsesTheApprovedSentenceThroughTheOldPathWithoutCallingTheChecker() {
        h.approvedSpeech.put("COACH_SWITCH", "이제 내가 도와줄게.");
        start(true);

        assertThat(extension.out.announceRoleSwitch("COACH")).isTrue();
        h.settleSend();
        say("이제 내가 도와줄게.");

        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(extension.out.announceRoleSwitch("PEER")).isFalse();
    }

    @Test
    void invalidRolesAndLongInstructionsAreRefusedWithoutOpeningASlot() {
        start(true);

        assertThat(extension.out.speakGenerated(UUID.randomUUID(), "TEACHER", INSTRUCTION)).isFalse();
        assertThat(extension.out.speakGenerated(UUID.randomUUID(), "PEER", "가".repeat(501))).isFalse();
        say(SPOKEN);

        assertThat(guard.count()).isZero();
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void aGeneratedSlotNeverMatchesTheOriginalTextSoTheCheckerCannotBeSkipped() {
        PracticeSpeech slot = PracticeSpeech.generatedSlot(UUID.randomUUID(), UUID.randomUUID(), 1, "PEER", INSTRUCTION);

        assertThat(slot.generated()).isTrue();
        assertThat(slot.matches(INSTRUCTION)).isFalse();
        assertThatThrownBy(() -> PracticeSpeech.generatedSlot(UUID.randomUUID(), UUID.randomUUID(), 1, "ANY", INSTRUCTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(slot.toString()).doesNotContain("친구로서");
    }

    private void say(String text) {
        h.speak(text, 4800);
        h.advance(Duration.ofMillis(801));
    }

    @Test
    void ordinaryInputDuringGeneratedIntroductionDoesNotSealPartialAudioAndStillRequiresJev() {
        start(true);extension.introduction=true;UUID slot=UUID.randomUUID();
        extension.out.speakGenerated(slot,"PEER",INSTRUCTION);h.settleSend();
        h.speak("오늘 연습할 목표는",4800);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("intro-overlap","응"));h.drain();
        h.advance(Duration.ofMillis(501));h.speak(null,4800);
        h.advance(Duration.ofMillis(501));h.speak(null,4800);h.advance(Duration.ofMillis(501));
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.ACTIVE);
        assertThat(extension.endFacts(slot)).isEmpty();assertThat(guard.count()).isZero();
        h.speak(" 의견 묻기야. 짧게 해 보자.",4800);h.advance(Duration.ofMillis(801));
        assertThat(guard.count()).isEqualTo(1);assertThat(h.link.outputs()).isEmpty();
        guard.allow();h.drain();assertThat(h.link.outputs()).hasSize(1);
        mark(h.link.outputs().getFirst().playbackId(),PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.COMPLETED);
    }

    @Test
    void ordinaryInputWhileIntroductionJevCheckIsPendingDoesNotCancelItsTicket() {
        start(true);extension.introduction=true;UUID slot=UUID.randomUUID();
        extension.out.speakGenerated(slot,"PEER",INSTRUCTION);h.settleSend();say(SPOKEN);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("intro-check","응, 알겠어"));h.drain();
        h.advance(Duration.ofMillis(501));h.childSays("intro-done","응, 알겠어");guard.allow();h.drain();
        assertThat(h.link.outputs()).hasSize(1);assertThat(extension.endFacts(slot)).isEmpty();
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.ACTIVE);
    }

    @Test
    void stopDuringIntroductionStillClosesImmediatelyAndLateJevAllowCannotPlay() {
        start(true);extension.introduction=true;
        extension.out.speakGenerated(UUID.randomUUID(),"PEER",INSTRUCTION);h.settleSend();say(SPOKEN);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("intro-stop","무중아 그만할래"));h.drain();
        h.advance(SessionHarness.SETTINGS.inputTranscriptQuiet().plusMillis(1));
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.CLOSING);
        guard.allow();h.drain();assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void restDuringIntroductionImmediatelyInterruptsOutputAndUsesTheExistingRestJudgment() {
        var judge=h.useJudge();start(true);extension.introduction=true;UUID slot=UUID.randomUUID();
        extension.out.speakGenerated(slot,"PEER",INSTRUCTION);h.settleSend();h.speak("오늘 연습할 목표는",4800);
        h.childSays("intro-rest","쉬고 싶어");
        assertThat(extension.events).anySatisfy(event -> assertThat(event.kind())
                .isEqualTo(PracticeSessionExtension.ExtensionEvent.Kind.REST_REQUESTED));
        assertThat(judge.calls()).isEmpty();
        h.speak(" 의견 묻기야. 해 보자.",4800);h.advance(Duration.ofMillis(801));
        assertThat(guard.count()).as("휴식 판단 중 이전 안내 조각은 검사·재생으로 가지 않는다").isZero();
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void forbiddenIntroAudioWithOverlappingInputStillFailsClosedBeforeJev() {
        start(true);extension.introduction=true;UUID slot=UUID.randomUUID();
        extension.out.speakGenerated(slot,"PEER",INSTRUCTION);h.settleSend();h.speak("검사해 보니",4800);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("intro-pattern","응"));h.drain();
        h.speak(" 비정상이에요",4800);h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).isEmpty();assertThat(guard.count()).isZero();
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.FAILED);
    }

    @Test
    void childInputWhileCollectingApprovedScriptDoesNotSealAPartialScene() {
        start(true);
        UUID slot=UUID.randomUUID();
        String text="친구와 놀이를 정하는 중이야. 친구가 공을 들고 있어.";
        extension.out.speak(PracticeSpeech.scenario(slot,UUID.randomUUID(),1,"PEER",text));
        h.settleSend();
        h.speak("친구와 놀이를 정하는",4800);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("overlap","응"));
        h.drain();
        assertThat(extension.endFacts(slot)).isEmpty();
        assertThat(h.link.outputs()).isEmpty();
        h.speak(" 중이야. 친구가 공을 들고 있어.",4800);
        h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(extension.endFacts(slot)).isEmpty();
        assertThat(guard.count()).as("전체 승인 원문 일치 경로만 기존대로 건너뜀").isZero();
        mark(h.link.outputs().getFirst().playbackId(),PlaybackMark.Status.COMPLETED);
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.COMPLETED);
    }

    @Test
    void overlappingInputCannotMakeAnAlteredApprovedScriptPlayable() {
        start(true);
        extension.recovery = true;
        UUID slot=UUID.randomUUID();
        extension.out.speak(PracticeSpeech.scenario(slot,UUID.randomUUID(),1,"PEER","친구가 공을 들고 있어."));
        h.settleSend();
        h.speak("친구가 공을",4800);
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("overlap","응"));
        h.drain();
        h.speak(" 들고 있어. 다른 말을 덧붙였어.",4800);
        h.advance(Duration.ofMillis(801));
        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.endFacts(slot)).isEmpty();
        assertThat(extension.mismatches).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(PracticeSessionExtension.ExtensionEvent.Kind.ASYNC_RESULT);
            assertThat(event.correlationId()).isEqualTo(slot);
            assertThat(((PracticeSpeech)event.value()).text()).isEqualTo("친구가 공을 들고 있어.");
        });
        assertThat(guard.count()).isZero();
    }

    @Test
    void aStopRequestStillStopsWhileApprovedSpeechIsBeingCollected() {
        start(true);
        extension.out.speak(PracticeSpeech.scenario(UUID.randomUUID(),UUID.randomUUID(),1,"PEER","친구가 공을 들고 있어."));
        h.settleSend();
        h.speak("친구가 공을",4800);
        h.childSays("stop","무중아 그만할래");
        assertThat(h.executor.status()).isEqualTo(kr.mujoong.contract.status.SessionStatus.CLOSING);
        assertThat(h.link.outputs()).isEmpty();
    }

    @Test
    void aForbiddenPatternIsNotReclassifiedAsARetryableReadingMismatch() {
        start(true);UUID slot=UUID.randomUUID();
        extension.out.speak(PracticeSpeech.scenario(slot,UUID.randomUUID(),1,"PEER","친구가 공을 들고 있어."));
        h.settleSend();say("검사해 보니 비정상이에요");
        assertThat(h.link.outputs()).isEmpty();
        assertThat(extension.mismatches).isEmpty();
        assertThat(extension.endFacts(slot)).containsExactly(PracticePorts.Playback.FAILED);
    }

    private void mark(UUID id, PlaybackMark.Status status) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(id, status, null, h.clock.now(), null, null), h.clock.now()));
        h.drain();
    }

    private List<String> appended() {
        return h.provider.last().requests(ProviderRequestFactory.InstructionsAppend.class).stream()
                .map(ProviderRequestFactory.InstructionsAppend::content).toList();
    }

    /** 출력을 쥐고 받은 사건을 기록하는 최소 연습 확장점. */
    private static final class Recording implements PracticeSessionExtension {
        PracticeOutput out;
        boolean introduction;
        boolean recovery;
        /** 참이면 장면 속 역할이 없는 연습(C안)처럼 군다. */
        boolean mujoong;
        UUID replySlot;
        final List<PlaybackFact> facts = new ArrayList<>();
        final List<GuardrailBlockedEvent> blocked = new ArrayList<>();
        final List<ExtensionEvent> mismatches = new ArrayList<>();
        final List<ExtensionEvent> events = new ArrayList<>();

        List<PracticePorts.Playback> endFacts(UUID correlationId) {
            return facts.stream().filter(fact -> !fact.started())
                    .filter(fact -> fact.speech().correlationId().equals(correlationId)).map(PlaybackFact::status).toList();
        }

        @Override
        public boolean controlsOutput() {
            return true;
        }

        @Override public boolean collectingIntroduction() { return introduction; }
        @Override public boolean handlesAudioRecovery() { return recovery; }
        @Override public boolean speaksAsMujoong() { return mujoong; }
        @Override public boolean canYieldUnplayedSpeech() { return !introduction; }

        @Override
        public Signal onActive(PracticeContext context, PracticeOutput output) {
            out = output;
            return Signal.NONE;
        }

        @Override
        public Signal onChildUtterance(Utterance utterance, PracticeOutput output) {
            if(replySlot!=null){UUID slot=replySlot;replySlot=null;output.speakGenerated(slot,"PEER",INSTRUCTION);}
            return Signal.NONE;
        }

        @Override
        public Signal onExtensionEvent(ExtensionEvent event, PracticeOutput output) {
            events.add(event);
            if ("approved-text-mismatch".equals(event.name())) mismatches.add(event);
            if (event.value() instanceof PlaybackFact fact) {
                facts.add(fact);
            }
            if (event.value() instanceof GuardrailBlockedEvent block) {
                blocked.add(block);
            }
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
