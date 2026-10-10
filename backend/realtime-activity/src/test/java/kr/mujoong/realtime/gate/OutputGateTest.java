package kr.mujoong.realtime.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.realtime.gate.GateDecision.DiscardReason;
import kr.mujoong.realtime.gateway.OutputBundle;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.session.SessionMailbox;
import kr.mujoong.realtime.testing.SessionHarness;
import kr.mujoong.realtime.voice.ProviderPort.ProviderEvent;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 출력 구간 · 게이트 시험 (U1 3.5 Step 29 · BR7.3 · BR7.4 · BR7.5 · PD-3 표 2.3-C · R3-19 · SD-17 · NFR1.1).
 *
 * <p>800ms 조용 경계(799 · 801ms) · 아이 입력 재개 확정 · GATE_EMPTY · GATE_PATTERN · 송신 직전 재확인 순서 · 대체 문구 겹침 규칙 ·
 * 폐기 행 FINAL · false · 출력 세대(취소 뒤 늦은 송신 0, 프레임 중간 세대 변경) · fail-closed · tGatePass 유무를 본다.
 */
@Tag("u1")
class OutputGateTest {

    private static final byte[] AUDIO = new byte[4800];

    private final SessionHarness h = new SessionHarness();

    @Test
    void aSegmentIsConfirmedOnlyAfterEightHundredMillisecondsOfQuiet() {
        h.activate();
        childSpeaks();
        output("r1", "공룡을 좋아하는구나");

        h.advance(Duration.ofMillis(799));
        assertThat(h.link.outputs()).isEmpty();
        h.advance(Duration.ofMillis(2));

        assertThat(h.link.outputs()).hasSize(1);
        OutputBundle bundle = h.link.outputs().get(0);
        assertThat(bundle.totalBytes()).isEqualTo(AUDIO.length * 2);
        assertThat(bundle.frames()).allSatisfy(frame -> assertThat(frame).hasSize(OutputBundle.FRAME_PAYLOAD_BYTES));
        TranscriptEntry ai = aiRow();
        assertThat(ai.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        assertThat(ai.approvedForDisplay()).isTrue();
        assertThat(ai.text()).isEqualTo("공룡을 좋아하는구나");
    }

    @Test
    void childInputResumingConfirmsTheOpenSegmentAtOnce() {
        h.activate();
        childSpeaks();
        output("r1", "같이 놀자");

        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-2", "근데"));
        h.drain();

        assertThat(h.link.outputs()).hasSize(1);
    }

    @Test
    void anEmptyTranscriptOrAForbiddenPatternIsDiscardedWithTheFillerAndAnUnapprovedRow() {
        h.activate();
        childSpeaks();
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta("r1", AUDIO));
        h.drain();
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_empty")).isEqualTo(1.0);
        assertThat(aiRow().approvedForDisplay()).isFalse();
        assertThat(aiRow().assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
        List<PlayApprovedContent> fillers = h.link.sent(PlayApprovedContent.class);
        assertThat(fillers).singleElement().satisfies(play -> {
            assertThat(play.contentCode()).isEqualTo(PlayApprovedContent.WAITING_FILLER);
            assertThat(play.contentVersionId()).isEqualTo(h.fillerVersion);
        });

        // 금지 패턴: 앞 대체 문구가 아직 재생 중이면 이번 대체 문구는 건너뛴다(R3-19)
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("item-1", "응"));
        output("r2", "검사해 보니 비정상이에요");
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).isEqualTo(1.0);
        assertThat(h.link.sent(PlayApprovedContent.class)).hasSize(1);

        // 대체 문구가 끝나면 다음 폐기는 다시 대체 문구를 보낸다
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(fillers.get(0).playbackId(), PlaybackMark.Status.COMPLETED, null, h.clock.now(), null,
                        null),
                h.clock.now()));
        h.drain();
        output("r3", "진단 결과는");
        h.advance(Duration.ofMillis(800));
        assertThat(h.link.sent(PlayApprovedContent.class)).hasSize(2);
    }

    @Test
    void theRecheckOrderIsNotActiveThenRecorderDegradedThenOutboundFull() {
        OutputGate gate = new OutputGate(SeedDictionary.loadDefault());

        assertThat(gate.recheck(false, true, Optional::empty).decision().reason()).isEqualTo(DiscardReason.NOT_ACTIVE);
        assertThat(gate.recheck(true, true, Optional::empty).decision().reason())
                .isEqualTo(DiscardReason.RECORDER_DEGRADED);
        assertThat(gate.recheck(true, false, Optional::empty).decision().reason()).isEqualTo(DiscardReason.OUTBOUND_FULL);
        assertThat(gate.recheck(true, false, () -> {
            throw new IllegalStateException("예약 실패");
        }).decision().reason()).isEqualTo(DiscardReason.OUTBOUND_FULL);
        OutputGate.Rechecked<String> passed = gate.recheck(true, false, () -> Optional.of("자리"));
        assertThat(passed.decision().passed()).isTrue();
        assertThat(passed.reservation()).isEqualTo("자리");
    }

    @Test
    void inspectionNeverLooksAtTheSessionStateAndFailsClosedOnBrokenSegments() {
        OutputGate gate = new OutputGate(SeedDictionary.loadDefault());
        SegmentBuffer buffer = new SegmentBuffer();
        buffer.addTranscript("r", "안녕", Instant.EPOCH, 0);
        buffer.addAudio("r", new byte[3], Instant.EPOCH, 1);

        assertThat(gate.inspect(buffer.seal(1)).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
        assertThat(gate.inspect(null).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
        buffer.addTranscript("s", "안녕", Instant.EPOCH, 0);
        buffer.addAudio("s", new byte[2], Instant.EPOCH, 1);
        assertThat(gate.inspect(buffer.seal(99)).passed()).isTrue();
    }

    @Test
    void anOutboundFullOrDegradedDiscardHasNoGatePassInTheTurnLog() {
        h.activate();
        h.link.setAudioBudget(0);
        childSpeaks();
        output("r1", "그랬구나");
        h.advance(Duration.ofMillis(800));

        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "outbound_full")).isEqualTo(1.0);
        Map<String, Object> turn = LogCapture.attrs(h.logs.last(EventCode.LATENCY_TURN).orElseThrow());
        assertThat(turn).doesNotContainKey("tGatePass").containsEntry("unobservedReason", "GATE_DISCARD");
        assertThat(aiRow().approvedForDisplay()).isFalse();
    }

    @Test
    void aLateInspectionAfterCancellationSendsNothingAndKeepsTheRowUnapproved() {
        h.activate();
        childSpeaks();
        output("r1", "이야기를 계속해 볼까");
        CompletableFuture<SessionMailbox.EndOutcome> stop = new CompletableFuture<>();
        h.mailbox.putSafety(new SessionMailbox.EndRequested(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, stop));
        h.drain();
        // CLOSING 뒤 도착한 공급자 출력도 게이트의 송신 직전 재확인에서 NOT_ACTIVE로 버려진다(NFR1.1)
        output("r2", "늦게 온 말");
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "not_active")).isEqualTo(1.0);
        assertThat(h.store.transcripts).filteredOn(row -> row.speaker() == TranscriptEntry.Speaker.AI)
                .allSatisfy(row -> assertThat(row.approvedForDisplay()).isFalse());
        // 마무리 문구 말고 대체 문구는 보내지 않는다(세션이 ACTIVE가 아니다)
        assertThat(h.link.sent(PlayApprovedContent.class)).extracting(PlayApprovedContent::contentCode)
                .containsExactly(PlayApprovedContent.CLOSING);
    }

    @Test
    void aGenerationChangeMidBundleStopsTheRemainingFrames() {
        h.activate();
        childSpeaks();
        output("r1", "하나 둘 셋");
        h.advance(Duration.ofMillis(800));
        OutputBundle bundle = h.link.outputs().get(0);
        assertThat(bundle.stillCurrent().getAsBoolean()).isTrue();

        h.mailbox.putSafety(new SessionMailbox.ConnectionLost(h.link.connectionId()));
        h.drain();

        assertThat(bundle.stillCurrent().getAsBoolean()).isFalse();
        assertThat(h.link.deliver(bundle, h.clock.now())).isZero();
        assertThat(h.link.reservedBytes()).isZero();
    }

    @Test
    void aPassedTurnLogsAllEightTimeKeysOnceThePlaybackStarts() {
        h.activate();
        h.link.timing(-120L, 40L);
        childSpeaks();
        h.provider.last().emit(new ProviderEvent.SpeechStopped());
        h.provider.last().emit(new ProviderEvent.InputTranscriptDone("item-1", "공룡 좋아"));
        output("r1", "나도 공룡 좋아");
        h.advance(Duration.ofMillis(800));
        OutputBundle bundle = h.link.outputs().get(0);
        assertThat(h.link.deliver(bundle, h.clock.now())).isEqualTo(bundle.frames().size());
        h.mailbox.offerGeneral(new SessionMailbox.PlaybackMarkReceived(h.link.connectionId(),
                new PlaybackMark(bundle.playbackId(), PlaybackMark.Status.STARTED, null, h.clock.now().plusMillis(30),
                        null, null),
                h.clock.now()));
        h.drain();

        Map<String, Object> turn = LogCapture.attrs(h.logs.last(EventCode.LATENCY_TURN).orElseThrow());
        assertThat(turn).containsKeys("tSpeechEnd", "tProviderFirstOut", "tSegmentConfirmed", "tGatePass",
                "tFirstFrameSent", "tDevicePlayStart", "tDevicePlayStartRecv", "tTranscriptFinal", "clockOffsetMs",
                "rttMs", "providerResponseMs", "quietWaitMs");
        assertThat(turn).containsEntry("tSpeechEndSource", "PROVIDER_EVENT").doesNotContainKey("unobservedReason");
        assertThat(h.logs.lines()).noneMatch(line -> line.contains("나도 공룡 좋아"));
    }

    private void childSpeaks() {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-1", "공룡"));
        h.drain();
    }

    private void output(String responseId, String text) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(responseId, text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(responseId, AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(responseId, AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputDone(responseId));
        h.drain();
    }

    private TranscriptEntry aiRow() {
        return h.store.transcripts.stream().filter(row -> row.speaker() == TranscriptEntry.Speaker.AI).reduce(
                (first, second) -> second).orElseThrow();
    }
}
