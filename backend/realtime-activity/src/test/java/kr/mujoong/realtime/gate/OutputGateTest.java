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
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
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
        buffer.addTranscript(1, true, "안녕", Instant.EPOCH, 0);
        buffer.addAudio(1, false, new byte[3], Instant.EPOCH, 1);

        assertThat(gate.inspect(buffer.sealQuiet(1)).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
        assertThat(gate.inspect(null).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
        // 첫 조각부터 끊김까지를 담지 않았으면(세대가 바뀌어 닫음) 전사-음성 대응 근거가 없다(리뷰 F1, SD-17 3단계)
        buffer.addTranscript(2, true, "안녕", Instant.EPOCH, 0);
        buffer.addAudio(2, false, new byte[2], Instant.EPOCH, 1);
        assertThat(gate.inspect(buffer.sealByGeneration(98)).reason()).isEqualTo(DiscardReason.GATE_EMPTY);
        buffer.addTranscript(3, true, "안녕", Instant.EPOCH, 0);
        buffer.addAudio(3, false, new byte[2], Instant.EPOCH, 1);
        assertThat(gate.inspect(buffer.sealQuiet(99)).passed()).isTrue();
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

    // ------------------------------------------------------------------ 전사 · 음성 1:1 대응(리뷰 F1 · SD-17 3단계 · BR7.4 (가))

    @Test
    void audioThatCameBeforeItsTranscriptIsDiscardedWhenTheChildResumesBeforeTheResponseEnds() {
        h.activate();
        childSpeaks();
        // 계약은 전사 · 음성 순서를 정하지 않는다: 응답 r1의 음성이 먼저 다 오고 전사는 앞부분만 왔다
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("그건 아마 "));
        h.drain();
        // 아이 입력 재개가 구간을 봉인한다(J193) — 응답 끝 신호가 아직 없다
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-2", "근데"));
        h.drain();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("치료가 필요해요"));
        // U11: 응답 끝 사건이 없다 — 봉인은 조각 사이 무음(조용 시간 경과)으로 한다(Step 8에서 다시 단언)
        h.drain();
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_empty")).isEqualTo(2.0);
        assertThat(aiRows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL);
            assertThat(row.approvedForDisplay()).isFalse();
        });
    }

    @Test
    void aResponseCutByTheQuietTimeBeforeItsDoneSignalIsDiscardedInBothParts() {
        h.activate();
        childSpeaks();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("그건 아마 "));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        // 공급자가 응답 중간에 800ms 멈추면 부분 전사로 봉인된다
        h.advance(Duration.ofMillis(800));
        output("r1", "치료가 필요해요");
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).isEmpty();
        // 나머지 구간은 응답의 첫 조각을 담지 않아 대응 근거가 없다(사전 검사 4단계 전에 3단계에서 폐기)
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_empty")).isEqualTo(2.0);
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).isZero();
        assertThat(aiRows()).allSatisfy(row -> assertThat(row.approvedForDisplay()).isFalse());
    }

    @Test
    void aQuietExpiryThatOvertakesQueuedDeltasCannotApproveTheUncheckedRest() {
        h.activate();
        childSpeaks();
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("그건 아마 "));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.drain();
        // 실행기가 밀린 사이 남은 조각 · 응답 끝은 일반 편지함에 쌓이고, 조용 만기는 안전 편지함으로 먼저 들어간다
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("치료가 필요해요"));
        // U11: 응답 끝 사건이 없다 — 봉인은 조각 사이 무음(조용 시간 경과)으로 한다(Step 8에서 다시 단언)
        h.clock.advance(Duration.ofMillis(800));
        h.quiet.runDue();
        assertThat(h.mailbox.safetySize()).isEqualTo(1);
        assertThat(h.mailbox.generalSize()).isEqualTo(3);
        h.drain();
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_empty")).isEqualTo(2.0);
        assertThat(aiRows()).allSatisfy(row -> assertThat(row.approvedForDisplay()).isFalse());
    }

    @Test
    void fragmentsOfAnotherResponseAreNeverMixedIntoTheOpenSegment() {
        h.activate();
        childSpeaks();
        // 응답 A의 전사와 응답 B의 음성이 800ms 안에 섞여 온다
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("안녕"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        // U11: 응답 끝 사건이 없다 — 봉인은 조각 사이 무음(조용 시간 경과)으로 한다(Step 8에서 다시 단언)
        // U11: 응답 끝 사건이 없다 — 봉인은 조각 사이 무음(조용 시간 경과)으로 한다(Step 8에서 다시 단언)
        h.drain();
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_empty")).isEqualTo(2.0);
        assertThat(aiRows()).hasSize(2).allSatisfy(row -> assertThat(row.approvedForDisplay()).isFalse());
    }

    @Test
    void aCompleteResponseIsGatedAloneWhenTheNextResponseStartsWithinTheQuietTime() {
        h.activate();
        childSpeaks();
        output("rA", "공룡을 좋아하는구나");
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("검사해 보니 비정상이에요"));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        // U11: 응답 끝 사건이 없다 — 봉인은 조각 사이 무음(조용 시간 경과)으로 한다(Step 8에서 다시 단언)
        h.drain();
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).singleElement()
                .satisfies(bundle -> assertThat(bundle.totalBytes()).isEqualTo(AUDIO.length * 2));
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_pattern")).isEqualTo(1.0);
        List<TranscriptEntry> rows = aiRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).text()).isEqualTo("공룡을 좋아하는구나");
        assertThat(rows.get(0).approvedForDisplay()).isTrue();
        assertThat(rows.get(1).approvedForDisplay()).isFalse();
    }

    @Test
    void aTranscriptThatNormalizesToNothingIsDiscardedAsEmpty() {
        h.activate();
        childSpeaks();
        // isBlank는 거짓이지만 정규화(제로폭 · 구두점 제거) 뒤 빈 전사다
        output("r1", "\u200B\u2026\u200D");
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).isEmpty();
        assertThat(h.metrics.count("mujoong.gate.discard", "reason", "gate_empty")).isEqualTo(1.0);
        assertThat(aiRow().approvedForDisplay()).isFalse();
    }

    @Test
    void aWholeResponseWithItsDoneSignalPassesEvenWhenItsAudioCameFirst() {
        h.activate();
        childSpeaks();
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("그건 아마 "));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta("공룡일 거야"));
        // U11: 응답 끝 사건이 없다 — 봉인은 조각 사이 무음(조용 시간 경과)으로 한다(Step 8에서 다시 단언)
        h.drain();
        h.advance(Duration.ofMillis(800));

        assertThat(h.link.outputs()).singleElement()
                .satisfies(bundle -> assertThat(bundle.totalBytes()).isEqualTo(AUDIO.length * 2));
        assertThat(aiRow().text()).isEqualTo("그건 아마 공룡일 거야");
        assertThat(aiRow().approvedForDisplay()).isTrue();
    }

    private void childSpeaks() {
        h.provider.last().emit(new ProviderEvent.InputTranscriptDelta("item-1", "공룡"));
        h.drain();
    }

    private void output(String responseId, String text) {
        h.provider.last().emit(new ProviderEvent.OutputTranscriptDelta(text));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        h.provider.last().emit(new ProviderEvent.OutputAudioDelta(AUDIO));
        // U11: 응답 끝 사건이 없다 — 봉인은 조각 사이 무음(조용 시간 경과)으로 한다(Step 8에서 다시 단언)
        h.drain();
    }

    private TranscriptEntry aiRow() {
        return h.store.transcripts.stream().filter(row -> row.speaker() == TranscriptEntry.Speaker.AI).reduce(
                (first, second) -> second).orElseThrow();
    }

    private List<TranscriptEntry> aiRows() {
        return h.store.transcripts.stream().filter(row -> row.speaker() == TranscriptEntry.Speaker.AI).toList();
    }
}
