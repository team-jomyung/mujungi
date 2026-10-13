package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.gate.SegmentBuffer;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.testing.FakeAudioRecordings;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 세션 실행기의 음성 중계 · 게이트 · 대화 기록 U3 단위 시험(design.md 4절 U3-R5 ~ R10 · U3-J6 · J11 ~ J13 · J17).
 *
 * <p>끼어들기 때 공급자 응답 취소와 늦은 조각 버림, 생각 중 · 듣는 중 표시, 폐기 뒤 응답당 한 번 지시 교정, 공급자 끊김 → ABORTED(PROVIDER_LOST),
 * 시작 뒤 {@code session.error}는 세션 유지, 아이 전사 불확실 표시, 동의된 세션만 원음 기록을 실제 스레드 없이 확인한다.
 */
@Tag("u3")
class SessionVoiceU3Test {

    private static final byte[] FRAME = new byte[960];

    private final SessionHarness h = new SessionHarness();

    // ------------------------------------------------------------------ 끼어들기 · 응답 취소 (U3-R5 · U3-J11)

    @Test
    void aBargeInCancelsThePlayingAndTheOpenResponseAtTheProviderAndTheirLateChunksAreDropped() {
        h.activate();
        OutputBundle first = respond("r1", "공룡은 아주 오래전에 살았어", 48_000);
        mark(first.playbackId(), PlaybackMark.Status.STARTED, null);
        // 다음 응답을 만드는 중에 아이가 말을 시작한다
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r2", "그리고 말이야"));
        h.drain();

        mark(first.playbackId(), PlaybackMark.Status.STOPPED, PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);

        // 실행기는 공급자 소켓 쓰기를 기다리지 않는다 — 보조 작업으로 띄운 취소 두 건
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCancel.class)).isEmpty();
        assertThat(h.launcher.runAll("provider-cancel-")).isEqualTo(2);
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCancel.class))
                .extracting(ProviderRequestFactory.ResponseCancel::responseId).containsExactly("r1", "r2");

        // 취소한 응답의 늦은 조각 · 끝 신호는 버린다(구간도 행도 다시 열지 않는다)
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("r2", new byte[4_800]));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r2", " 티라노는"));
        h.provider.last().emit(new ProviderEvent.OutputDone("r2"));
        h.drain();
        h.advance(Duration.ofSeconds(1));
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.launcher.names()).noneMatch(name -> name.startsWith("provider-correct-"));

        // 새 응답은 막지 않는다
        respond("r3", "다시 이야기해 줄래", 4_800);
        assertThat(h.link.outputs()).hasSize(2);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aBargeInWithNothingTrackedCancelsTheCurrentResponseWithoutAnId() {
        h.activate();

        mark(UUID.randomUUID(), PlaybackMark.Status.STOPPED, PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);
        h.launcher.runAll("provider-cancel-");

        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCancel.class)).singleElement()
                .satisfies(cancel -> {
                    assertThat(cancel.type()).isEqualTo(ProviderRequestFactory.TYPE_RESPONSE_CANCEL);
                    assertThat(cancel.responseId()).isNull();
                });
    }

    @Test
    void aCancelThatCannotBeSentIsDroppedQuietlyAndLeavesTheSessionToTheReadersLossSignal() {
        h.activate();
        OutputBundle bundle = respond("r1", "같이 세어 볼까", 4_800);
        mark(bundle.playbackId(), PlaybackMark.Status.STARTED, null);
        mark(bundle.playbackId(), PlaybackMark.Status.STOPPED, PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);
        // 취소를 보내기 전에 공급자 소켓이 닫혔다(송신 예외) — 보조 작업은 조용히 끝나고 세션 판단은 리더의 끊김 알림이 한다
        h.provider.last().close();
        int appErrors = h.logs.events(EventCode.APP_ERROR).size();

        assertThat(h.launcher.runAll("provider-cancel-")).isEqualTo(1);
        h.drain();

        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCancel.class)).isEmpty();
        assertThat(h.logs.events(EventCode.APP_ERROR)).hasSize(appErrors);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);

        // 닫힌 공급자에는 보조 작업을 띄우지 않는다
        mark(bundle.playbackId(), PlaybackMark.Status.STOPPED, PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);
        assertThat(h.launcher.runAll("provider-cancel-")).isZero();
    }

    // ------------------------------------------------------------------ 생각 중 · 듣는 중 (U3-J6)

    @Test
    void theViewGoesThinkingWhenTheChildStopsAndBackToListeningWhenPlaybackEnds() {
        h.activate();
        h.provider.last().emit(new ProviderEvent.SpeechStopped());
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("i1", "공룡 좋아", 0.9));
        h.drain();
        assertThat(views()).containsExactly(View.LISTENING, View.THINKING);

        OutputBundle bundle = respond("r1", "나도 공룡이 좋아", 4_800);
        mark(bundle.playbackId(), PlaybackMark.Status.STARTED, null);
        // 재생 중에 들어온 말 끝 신호는 표시를 바꾸지 않는다
        h.provider.last().emit(new ProviderEvent.SpeechStopped());
        h.drain();
        assertThat(views()).containsExactly(View.LISTENING, View.THINKING);

        mark(bundle.playbackId(), PlaybackMark.Status.COMPLETED, null);

        assertThat(views()).containsExactly(View.LISTENING, View.THINKING, View.LISTENING);
        assertThat(h.executor.view()).isEqualTo(View.LISTENING);
    }

    @Test
    void afterADiscardTheViewReturnsToListeningOnlyWhenTheWaitingFillerEnds() {
        h.activate();
        h.provider.last().emit(new ProviderEvent.SpeechStopped());
        h.drain();
        emitComplete("r1", "진단해 보면 이래");
        h.advance(Duration.ofMillis(800));
        PlayApprovedContent filler = h.link.sent(PlayApprovedContent.class).get(0);
        assertThat(filler.contentCode()).isEqualTo(PlayApprovedContent.WAITING_FILLER);
        assertThat(views()).containsExactly(View.LISTENING, View.THINKING);

        mark(filler.playbackId(), PlaybackMark.Status.STARTED, null);
        mark(filler.playbackId(), PlaybackMark.Status.COMPLETED, null);

        assertThat(views()).containsExactly(View.LISTENING, View.THINKING, View.LISTENING);
    }

    // ------------------------------------------------------------------ 지시 교정 (U3-R7 · U3-J12)

    @Test
    void aPatternDiscardSendsItsCategoryCorrectionOncePerResponseWithoutTheDiscardedText() {
        h.activate();
        emitComplete("r1", "진단 결과는 하늘색이야");
        h.advance(Duration.ofMillis(800));
        // 같은 응답의 늦은 조각이 새 구간이 되어 다시 폐기돼도 그 응답의 교정은 한 번뿐이다
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r1", "늦은 조각"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("r1", new byte[4_800]));
        h.drain();
        h.advance(Duration.ofMillis(800));
        emitComplete("r2", "화가났군요 그렇지");
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.launcher.runAll("provider-correct-")).isEqualTo(2);
        List<ProviderRequestFactory.InstructionsCorrect> corrections =
                h.provider.last().requests(ProviderRequestFactory.InstructionsCorrect.class);
        assertThat(corrections).extracting(ProviderRequestFactory.InstructionsCorrect::text).containsExactly(
                DialogInstructions.Correction.DIAGNOSIS.text(), DialogInstructions.Correction.EMOTION_CLAIM.text());
        assertThat(corrections).allSatisfy(correction -> {
            assertThat(correction.type()).isEqualTo(ProviderRequestFactory.TYPE_INSTRUCTIONS_CORRECT);
            assertThat(correction.text()).doesNotContain("하늘색", "화가났군요", "늦은 조각");
        });
    }

    @Test
    void anIncompleteResponseLeftByTheProviderAsksForAShortAnswerButOneCutByTheConversationDoesNot() {
        h.activate();
        // 공급자가 끝 신호 없이 멈췄다(800ms 조용) → 짧은 완결 답 요청
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r1", "공룡은"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("r1", new byte[4_800]));
        h.drain();
        h.advance(Duration.ofMillis(800));
        // 아이가 다시 말해 확정한 미완 구간 · 다른 응답이 와서 확정한 미완 구간은 대화가 넘어갔으니 교정하지 않는다
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r2", "그러니까"));
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("i1", "잠깐만"));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r3", "다음은"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("r3", new byte[4_800]));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r4", "새 이야기"));
        h.drain();

        assertThat(h.launcher.runAll("provider-correct-")).isEqualTo(1);
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsCorrect.class))
                .extracting(ProviderRequestFactory.InstructionsCorrect::text)
                .containsExactly(DialogInstructions.Correction.INCOMPLETE.text());
    }

    @Test
    void anOversizedResponseAsksForAShorterAnswerAndNoCorrectionIsSentOutsideActive() {
        h.activate();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r1", "아주 긴 이야기"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("r1", new byte[SegmentBuffer.MAX_BYTES + 2]));
        h.drain();
        assertThat(h.launcher.runAll("provider-correct-")).isEqualTo(1);

        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        assertThat(stop).isCompletedWithValue(EndOutcome.CLOSING);
        emitComplete("r2", "치료가 필요해");
        h.advance(Duration.ofMillis(800));

        assertThat(h.launcher.runAll("provider-correct-")).as("CLOSING에는 교정하지 않는다").isZero();
        assertThat(h.provider.last().requests(ProviderRequestFactory.InstructionsCorrect.class))
                .extracting(ProviderRequestFactory.InstructionsCorrect::text)
                .containsExactly(DialogInstructions.Correction.INCOMPLETE.text());
    }

    @Test
    void everySeedCategoryHasTheCorrectionOfTheSameName() {
        for (kr.mujoong.realtime.gate.SeedDictionary.Category category
                : kr.mujoong.realtime.gate.SeedDictionary.Category.values()) {
            assertThat(SessionExecutor.correctionFor(category).name()).isEqualTo(category.name());
        }
    }

    // ------------------------------------------------------------------ 공급자 끊김 (U3-R8 · U3-J13)

    @Test
    void aProviderCloseWhileActiveAbortsAsProviderLostAndReturnsTheDeviceToStandby() {
        h.rawAudioConsent = true;
        h.activate();
        h.clock.advance(Duration.ofSeconds(5));
        Instant lostAt = h.clock.now();

        h.provider.last().emit(new ProviderEvent.Closed());
        h.drain();

        ActivitySessionRow row = h.transitions.find(h.sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.ABORTED);
        assertThat(row.endReason()).isEqualTo(EndReason.DISCONNECT);
        assertThat(row.endDetail()).isEqualTo(SessionExecutor.END_DETAIL_PROVIDER_LOST);
        assertThat(row.endedAt()).isEqualTo(lostAt);
        assertThat(row.providerClosedAt()).isEqualTo(lostAt);
        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .containsExactly(CloseTransport.Reason.DISCONNECT);
        assertThat(views()).containsExactly(View.LISTENING, View.STANDBY);
        assertThat(h.executor.isTerminated()).isTrue();
        assertThat(h.executor.relay().isAccepting()).isFalse();
        assertThat(h.recordings.opened()).singleElement()
                .satisfies(recorded -> assertThat(recorded.closedAt()).isEqualTo(lostAt));
        assertThat(LogCapture.attrs(h.logs.last(EventCode.APP_ERROR).orElseThrow()))
                .containsEntry("errorKind", "ProviderDisconnected");
    }

    @Test
    void aProviderErrorAfterStartKeepsTheSessionAndOnlyLogsAWarning() {
        h.activate();

        h.provider.last().emit(new ProviderEvent.Error("rate_limited"));
        h.drain();

        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(h.link.sent(CloseTransport.class)).isEmpty();
        assertThat(LogCapture.attrs(h.logs.last(EventCode.APP_ERROR).orElseThrow()))
                .containsEntry("errorKind", "ProviderDisconnected");
    }

    @Test
    void aProviderCloseWhileClosingWaitsForTheConfirmTimerAndASelfCloseIsNotADisconnect() {
        h.provider.signalClosedOnClose(true);
        h.activate();
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        assertThat(stop).isCompletedWithValue(EndOutcome.CLOSING);

        h.provider.last().emit(new ProviderEvent.Closed());
        h.drain();
        assertThat(h.executor.status()).as("CLOSING은 30초 확인 시한이 우선한다").isEqualTo(SessionStatus.CLOSING);
        long disconnects = disconnectLines();
        assertThat(disconnects).isEqualTo(1);

        h.advance(Duration.ofSeconds(30));

        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ENDED_UNCONFIRMED);
        // 실행기가 스스로 닫은 공급자의 끊김 알림(signalClosedOnClose)은 끊김 줄을 더하지 않는다
        assertThat(disconnectLines()).isEqualTo(disconnects);
        assertThat(h.link.sent(CloseTransport.class)).extracting(CloseTransport::reason)
                .doesNotContain(CloseTransport.Reason.DISCONNECT);
    }

    // ------------------------------------------------------------------ 불확실 전사 (U3-R10 · U3-J17)

    @Test
    void childTranscriptsBelowTheConfidenceThresholdOrBlankAreMarkedForReview() {
        h.activate();
        done("i1", "공룡 좋아", 0.9);
        done("i2", "응", 0.42);
        done("i3", "", null);
        done("i4", "몰라", null);
        done("i5", "글쎄", 0.6);
        done("i6", "음", 0.59);
        h.recorder.tick();

        Map<String, Boolean> review = new java.util.LinkedHashMap<>();
        h.store.transcripts.stream()
                .filter(entry -> entry.speaker() == TranscriptEntry.Speaker.CHILD
                        && entry.assemblyStatus() == TranscriptEntry.AssemblyStatus.FINAL)
                .forEach(entry -> review.put(entry.text(), entry.reviewNeeded()));
        assertThat(review).containsExactly(Map.entry("공룡 좋아", false), Map.entry("응", true), Map.entry("", true),
                Map.entry("몰라", false), Map.entry("글쎄", false), Map.entry("음", true));
        assertThat(h.store.transcripts).filteredOn(entry -> entry.speaker() == TranscriptEntry.Speaker.AI)
                .allSatisfy(entry -> assertThat(entry.reviewNeeded()).isFalse());
    }

    // ------------------------------------------------------------------ 원음 기록 (U3-R9 · U3-J14)

    @Test
    void withoutRawAudioConsentNothingIsRecorded() {
        h.activate();
        offerFrames(2);
        respond("r1", "안녕 반가워", 4_800);
        end();

        assertThat(h.recordings.opened()).isEmpty();
    }

    @Test
    void withConsentChildFramesAndOnlyApprovedAiAudioAreRecordedUntilTheSessionEnds() {
        h.rawAudioConsent = true;
        h.activate();
        offerFrames(3);
        // 리뷰 반영 6: 송신기가 기기로 실제 보낸 프레임만 기록된다
        h.link.deliver(respond("r1", "안녕 반가워", 4_800), h.clock.now());
        emitComplete("r2", "진단 결과야");
        h.advance(Duration.ofMillis(800));
        Instant endedAt = h.clock.now();
        end();

        FakeAudioRecordings.Recorded recorded = h.recordings.opened().get(0);
        assertThat(h.recordings.opened()).hasSize(1);
        assertThat(recorded.sessionId()).isEqualTo(h.sessionId);
        assertThat(recorded.childBytes()).hasSize(3 * FRAME.length);
        assertThat(recorded.aiBytes()).as("게이트를 통과해 기기로 보낸 AI 음성만").hasSize(4_800);
        assertThat(recorded.closedAt()).isEqualTo(endedAt);
    }

    // ------------------------------------------------------------------ 종료 의도 (U3-R12 · U3-J24)

    @Test
    void aClearStopIntentCancelsTheResponseInProgressRecordsTheEventAndRefusesLaterResponses() {
        h.activate();
        respond("r1", "공룡은 멋져", 4_800);
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("r2", "그래 그럼"));
        h.provider.last().emit(new ProviderEvent.SpeechStopped());
        h.drain();
        long generation = h.executor.outputGeneration();

        done("i1", "무중아 그만할래", 0.95);

        assertThat(sessionEvents()).filteredOn(event -> event.getEventType().equals(SessionExecutor.EVENT_CHILD_STOP_INTENT))
                .singleElement().satisfies(event -> assertThat(event.getPayloadJson()).isEqualTo("{\"basis\":\"SNAPSHOT\"}"));
        assertThat(h.executor.outputGeneration()).isGreaterThan(generation);
        assertThat(h.launcher.runAll("provider-cancel-")).isEqualTo(1);
        assertThat(h.provider.last().requests(ProviderRequestFactory.ResponseCancel.class))
                .extracting(ProviderRequestFactory.ResponseCancel::responseId).containsExactly("r2");
        // 말 끝의 '생각 중'은 종료 의도 뒤 '듣는 중'으로 돌아간다(답이 오지 않는다)
        assertThat(views()).containsExactly(View.LISTENING, View.THINKING, View.LISTENING);

        // 이후 새 응답은 첫 조각에서 취소하고 기기로 보내지 않는다(전이 · 마무리 인사는 U4)
        emitComplete("r3", "알겠어 안녕");
        h.advance(Duration.ofSeconds(1));
        assertThat(h.launcher.runAll("provider-cancel-")).isEqualTo(1);
        assertThat(h.link.outputs()).hasSize(1);
        assertThat(h.executor.status()).isEqualTo(SessionStatus.ACTIVE);
        done("i2", "무중아 그만할래", 0.95);
        assertThat(sessionEvents()).filteredOn(event -> event.getEventType().startsWith("CHILD_STOP")).hasSize(1);
        assertThat(sessionEvents()).noneMatch(event -> event.getPayloadJson() != null
                && event.getPayloadJson().contains("그만"));
    }

    @Test
    void aStopPhraseHeardWhileTheDevicePlaysIsNotAStopAndAnUnclearOneIsOnlyRecorded() {
        h.activate();
        OutputBundle bundle = respond("r1", "같이 노래 부를까", 4_800);
        mark(bundle.playbackId(), PlaybackMark.Status.STARTED, null);
        done("i1", "무중아 그만할래", 0.9);
        assertThat(sessionEvents()).noneMatch(event -> event.getEventType().startsWith("CHILD_STOP"));
        assertThat(h.launcher.runAll("provider-cancel-")).isZero();
        mark(bundle.playbackId(), PlaybackMark.Status.COMPLETED, null);

        done("i2", "쉬고 싶어", 0.9);

        assertThat(sessionEvents()).filteredOn(event -> event.getEventType().startsWith("CHILD_STOP"))
                .extracting(SessionEvent::getEventType).containsExactly(SessionExecutor.EVENT_CHILD_STOP_UNCLEAR);
        assertThat(h.launcher.runAll("provider-cancel-")).isZero();
        // 확인만 남기고 대화는 이어진다
        respond("r2", "그래 조금 쉬었다 할까", 4_800);
        assertThat(h.link.outputs()).hasSize(2);
    }

    @Test
    void stopIntentIsJudgedOnlyWhileActive() {
        h.activate();
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();

        done("i1", "무중아 그만할래", 0.9);

        assertThat(sessionEvents()).noneMatch(event -> event.getEventType().startsWith("CHILD_STOP"));
    }

    // ------------------------------------------------------------------ 도움

    /** 응답 한 개를 끝까지 흘리고 조용 시간을 넘겨 게이트를 통과시킨다. 기기로 나간 묶음을 돌려준다. */
    private OutputBundle respond(String responseId, String text, int bytes) {
        int before = h.link.outputs().size();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(responseId, text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(responseId, new byte[bytes]));
        h.provider.last().emit(new ProviderEvent.OutputDone(responseId));
        h.drain();
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).hasSize(before + 1);
        return h.link.outputs().get(before);
    }

    private void emitComplete(String responseId, String text) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(responseId, text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(responseId, new byte[4_800]));
        h.provider.last().emit(new ProviderEvent.OutputDone(responseId));
        h.drain();
    }

    private void done(String itemId, String transcript, Double confidence) {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone(itemId, transcript, confidence));
        h.drain();
    }

    private void mark(UUID playbackId, PlaybackMark.Status status, PlaybackMark.StopCause cause) {
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(playbackId, status, null, h.clock.now(), cause, null), h.clock.now()));
        h.drain();
    }

    private void offerFrames(int count) {
        for (int i = 1; i <= count; i++) {
            assertThat(h.executor.offerAudio(h.link.connectionId(), i, FRAME, h.clock.monotonicNanos())).isTrue();
        }
        assertThat(h.executor.relay().relayAvailable()).isEqualTo(count);
    }

    /** 보호자 끝내기 → 마무리 문구 끝 표식 → 기기 닫힘 확인으로 ENDED까지 간다. */
    private void end() {
        CompletableFuture<EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        PlayApprovedContent closing = h.link.sent(PlayApprovedContent.class).stream()
                .filter(play -> PlayApprovedContent.CLOSING.equals(play.contentCode())).findFirst().orElseThrow();
        mark(closing.playbackId(), PlaybackMark.Status.COMPLETED, null);
        h.mailbox.offerGeneral(new SessionMailbox.VoiceClosed(h.link.connectionId(),
                new kr.mujoong.contract.ws.message.VoiceSessionClosed(h.clock.now(), h.clock.now()), h.clock.now()));
        h.drain();
        assertThat(h.transitions.status(h.sessionId)).isEqualTo(SessionStatus.ENDED);
    }

    private List<SessionEvent> sessionEvents() {
        h.recorder.tick();
        return h.store.sessionEvents;
    }

    private List<View> views() {
        return h.link.sent(SessionState.class).stream().map(SessionState::view).toList();
    }

    private long disconnectLines() {
        return h.logs.events(EventCode.APP_ERROR).stream()
                .filter(event -> "ProviderDisconnected".equals(LogCapture.attrs(event).get("errorKind"))).count();
    }
}
