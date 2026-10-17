package kr.mujoong.realtime.session;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.log.TranscriptRow;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.log.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 세션 생명주기 통합 시험 (U1 3.5 Step 30 · BR6.6 ~ 6.12 · R3-1 · AC3.3.2 ~ 3.3.7 · AC6.2.1 ~ 6.2.3 · SD-17 · NFR1.1 · OD-8 · J143).
 *
 * <p>실제 DB · test 프로필 앱 · 기기 시뮬레이터(JDK WebSocket) · 가짜 공급자 WS 서버로 시작부터 끝까지 돈다. 시한은 test 프로필의 줄인
 * 값(ACK 2초 · 종료 확인 3초 · 끊김 4초)이다.
 */
@Tag("u1")
@Tag("db")
class SessionLifecycleIT {

    private static final byte[] PCM = new byte[4800];

    private RealtimeItEnvironment env;
    private LogCapture logs;

    @BeforeEach
    void start() {
        env = RealtimeItEnvironment.start();
        // 앱 기동이 Logback 설정을 다시 읽으므로 수집기는 기동 뒤에 붙인다
        logs = LogCapture.attach();
    }

    @AfterEach
    void stop() {
        try {
            env.close();
        } finally {
            logs.close();
        }
    }

    @Test
    void aFullTurnThenGuardianStopEndsConfirmedWithTheClosingSequenceOnce() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();

        assertThat(env.start(sessionId)).isEqualTo(new StartSessionHandler.Accepted(SessionStatus.PREPARING));
        WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
        assertThat(start.sessionId()).isEqualTo(sessionId);
        // R3-1: START_SESSION이 공급자 연결보다 먼저다(공급자 서버는 아직 session.start를 받기 전일 수 있다)
        device.ack(start.opId(), sessionId);
        awaitStatus(sessionId, SessionStatus.DEVICE_ACK);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitStatus(sessionId, SessionStatus.ACTIVE);
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.LISTENING,
                WAIT);

        // 한 턴: 아이 전사 → AI 출력(게이트 통과) → OUTPUT_BEGIN · 0x02 · OUTPUT_END
        device.heartbeat(30L);
        env.provider.emitTurn("item-1", "공룡이 좋아", "나도 공룡 좋아해", List.of(PCM, PCM));
        WsEnvelope begin = device.await(WsMessageType.OUTPUT_BEGIN, WAIT);
        device.await(WsMessageType.OUTPUT_END, WAIT);
        assertThat(((OutputBegin) begin.payload()).source()).isEqualTo(OutputBegin.Source.GPT_LIVE);
        assertThat(device.outputPcm()).hasSize(PCM.length * 2);
        UUID playbackId = ((OutputBegin) begin.payload()).playbackId();
        device.playbackMark(sessionId, playbackId, PlaybackMark.Status.STARTED);
        awaitTrue(() -> logs.last(EventCode.LATENCY_TURN).isPresent(), "LATENCY_TURN");
        awaitTrue(() -> transcripts(sessionId).size() == 2 && transcripts(sessionId).stream()
                .allMatch(TranscriptRow::isFinal), "전사 두 행 FINAL");
        List<TranscriptRow> rows = transcripts(sessionId);
        assertThat(rows.get(0).speaker()).isEqualTo(kr.mujoong.realtime.log.TranscriptEntry.Speaker.CHILD);
        assertThat(rows.get(1).approvedForDisplay()).isTrue();

        Map<String, Object> turn = LogCapture.attrs(logs.last(EventCode.LATENCY_TURN).orElseThrow());
        assertThat(turn).containsKeys("tSpeechEnd", "tProviderFirstOut", "tSegmentConfirmed", "tGatePass",
                "tFirstFrameSent", "tDevicePlayStart", "tDevicePlayStartRecv", "tTranscriptFinal", "tSpeechEndSource",
                "clockOffsetMs", "rttMs", "providerResponseMs", "quietWaitMs");
        env.app.bean(LatencyRecorder.class).itemsServed(sessionId, List.of(
                new LatencyRecorder.ServedItem(rows.get(0).sequenceNo(), rows.get(0).speaker())),
                env.app.bean(kr.mujoong.contract.time.MujoongClock.class).now());
        assertThat(LogCapture.attrs(logs.last(EventCode.SESSION_ITEMS_SERVED).orElseThrow()))
                .containsKeys("tViewItemReady", "tViewItemFirstServed");

        // 끝내기: STOP_OUTPUT + SESSION_STATE{CLOSING} → 마무리 문구 1회 → 같은 playbackId 마크 → CLOSE_TRANSPORT
        EndOutcome first = end(sessionId, EndRequestReason.GUARDIAN_STOP);
        EndOutcome again = end(sessionId, EndRequestReason.GUARDIAN_STOP);
        assertThat(first).isEqualTo(EndOutcome.CLOSING);
        assertThat(again).isEqualTo(EndOutcome.CLOSING);
        WsEnvelope stopOutput = device.await(WsMessageType.STOP_OUTPUT, WAIT);
        assertThat(((StopOutput) stopOutput.payload()).reason()).isEqualTo(StopOutput.Reason.GUARDIAN_STOP);
        WsEnvelope closing = device.await(WsMessageType.PLAY_APPROVED_CONTENT, WAIT);
        PlayApprovedContent play = (PlayApprovedContent) closing.payload();
        assertThat(play.contentCode()).isEqualTo(PlayApprovedContent.CLOSING);
        // CLOSING 뒤 도착한 공급자 출력은 기기로 한 바이트도 가지 않는다(NFR1.1 · SD-17)
        int framesBefore = device.outputFrames();
        env.provider.emitTurn("item-2", "응", "늦은 말", List.of(PCM));
        device.playbackMark(sessionId, play.playbackId(), PlaybackMark.Status.COMPLETED);
        WsEnvelope close = device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        assertThat(((CloseTransport) close.payload()).reason()).isEqualTo(CloseTransport.Reason.GUARDIAN_STOP);
        device.voiceSessionClosed(sessionId);
        awaitStatus(sessionId, SessionStatus.ENDED);

        ActivitySessionRow row = env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow();
        assertThat(row.confirmed()).isTrue();
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.STANDBY,
                WAIT);
        assertThat(device.received(WsMessageType.PLAY_APPROVED_CONTENT)).hasSize(1);
        assertThat(device.received(WsMessageType.CLOSE_TRANSPORT)).hasSize(1);
        assertThat(device.outputFrames()).isEqualTo(framesBefore);
        // U4b(FR11.3 · BR3.1): 마무리 인사 말풍선 한 행이 더해져 AI 행은 셋(턴 · 마무리 인사 · 늦은 말)
        awaitTrue(() -> transcripts(sessionId).stream().filter(t -> t.speaker()
                == kr.mujoong.realtime.log.TranscriptEntry.Speaker.AI).count() == 3, "늦은 AI 행");
        assertThat(transcripts(sessionId).get(transcripts(sessionId).size() - 1).approvedForDisplay()).isFalse();
        assertThat(logs.lines()).noneMatch(line -> line.contains("나도 공룡 좋아해") || line.contains(env.deviceToken));
    }

    @Test
    void aForbiddenPatternTurnSendsTheFillerInsteadAndKeepsTheRowUnapproved() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);

        env.provider.emitTurn("item-1", "나 어때?", "검사해 보니 비정상이에요", List.of(PCM));

        WsEnvelope filler = device.await(WsMessageType.PLAY_APPROVED_CONTENT, WAIT);
        assertThat(((PlayApprovedContent) filler.payload()).contentCode()).isEqualTo(PlayApprovedContent.WAITING_FILLER);
        assertThat(device.received(WsMessageType.OUTPUT_BEGIN)).isEmpty();
        assertThat(device.outputFrames()).isZero();
        awaitTrue(() -> transcripts(sessionId).stream().anyMatch(row -> row.speaker()
                == kr.mujoong.realtime.log.TranscriptEntry.Speaker.AI && row.isFinal()), "AI 행 FINAL");
        assertThat(transcripts(sessionId)).filteredOn(row -> row.speaker()
                == kr.mujoong.realtime.log.TranscriptEntry.Speaker.AI)
                .allSatisfy(row -> assertThat(row.approvedForDisplay()).isFalse());
        assertThat(logs.lines()).noneMatch(line -> line.contains("비정상이에요"));
    }

    @Test
    void withoutVoiceSessionClosedTheClosingEndsUnconfirmedAndMarksUnresponsive() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);

        assertThat(end(sessionId, EndRequestReason.GUARDIAN_STOP)).isEqualTo(EndOutcome.CLOSING);
        // 종료 확인 3초 동안 기기는 HEARTBEAT만 보낸다(원장 40초 경로와 섞이지 않게 2.5초까지만)
        keepAlive(device, Duration.ofMillis(2_500));

        awaitStatus(sessionId, SessionStatus.ENDED_UNCONFIRMED);
        awaitTrue(() -> env.ledger().map(row -> row.state() == kr.mujoong.contract.status.ConnectionState.UNRESPONSIVE)
                .orElse(false), "UNRESPONSIVE");
        // 같은 연결의 다음 HEARTBEAT로 복귀한다(J55)
        device.heartbeat(20L);
        awaitTrue(() -> env.ledger().map(row -> row.state() == kr.mujoong.contract.status.ConnectionState.CONNECTED)
                .orElse(false), "CONNECTED 복귀");
    }

    @Test
    void aSilentDeviceAbortsTheActiveSessionAfterTheDisconnectWindow() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);

        awaitStatus(sessionId, SessionStatus.ABORTED);
        // TIMEOUT_FIRED는 ABORTED 전이 커밋 뒤에 실행기 스레드가 남긴다. 상태를 본 직후에는 아직 없을 수 있다(Step 36 재실행에서 드러난 경주)
        awaitTrue(() -> logs.last(EventCode.TIMEOUT_FIRED).isPresent(), "TIMEOUT_FIRED");

        ActivitySessionRow row = env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow();
        assertThat(row.endReason()).isEqualTo(kr.mujoong.contract.status.EndReason.DISCONNECT);
        assertThat(LogCapture.attrs(logs.last(EventCode.TIMEOUT_FIRED).orElseThrow())).containsEntry("type", "DISCONNECT");
        assertThat(device.received(WsMessageType.CLOSE_TRANSPORT)).isEmpty();
    }

    @Test
    void coreAbandonedFailsTheSessionAndClosesTheDeviceTransport() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);

        assertThat(end(sessionId, EndRequestReason.CORE_ABANDONED)).isEqualTo(EndOutcome.ACCEPTED_EMPTY);

        awaitStatus(sessionId, SessionStatus.FAILED);
        ActivitySessionRow row = env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow();
        assertThat(row.failureCode()).isNull();
        WsEnvelope close = device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        assertThat(((CloseTransport) close.payload()).reason()).isEqualTo(CloseTransport.Reason.FAILED);
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.STANDBY,
                WAIT);
        // 종료 뒤 늦은 메시지는 세션을 되살리지 못한다(BR6.11)
        device.voiceSessionClosed(sessionId);
        RealtimeItEnvironment.sleep(200);
        assertThat(env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow().status())
                .isEqualTo(SessionStatus.FAILED);
        assertThat(end(sessionId, EndRequestReason.GUARDIAN_STOP)).isEqualTo(EndOutcome.NOT_ACTIVE);
    }

    @Test
    void aMissingAckFailsWithDeviceNoAckAfterTheDeviceAckWindow() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        env.start(sessionId);
        device.await(WsMessageType.START_SESSION, WAIT);
        keepAlive(device, Duration.ofMillis(2_300));

        awaitStatus(sessionId, SessionStatus.FAILED);
        assertThat(env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow().failureCode())
                .isEqualTo(FailureCode.DEVICE_NO_ACK);
        device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
    }

    @Test
    void lateStartsForCancelledIdsAndStartsWithoutALiveDeviceAreRejected() {
        UUID cancelled = UUID.randomUUID();
        assertThat(end(cancelled, EndRequestReason.CORE_ABANDONED)).isEqualTo(EndOutcome.ACCEPTED_EMPTY);
        assertThat(env.start(cancelled)).isEqualTo(new StartSessionHandler.Rejected(
                StartSessionHandler.Reason.SESSION_NOT_ACTIVE));
        assertThat(env.start(UUID.randomUUID())).isEqualTo(new StartSessionHandler.Rejected(
                StartSessionHandler.Reason.DEVICE_NOT_CONNECTED));
        assertThat(end(UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP)).isEqualTo(EndOutcome.NOT_ACTIVE);
    }

    @Test
    void theSameSessionIdStartedTwiceReturnsTheCurrentStateAndASecondSessionIsInProgress() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);

        assertThat(env.start(sessionId)).isEqualTo(new StartSessionHandler.Accepted(SessionStatus.ACTIVE));
        assertThat(env.start(UUID.randomUUID())).isEqualTo(new StartSessionHandler.Rejected(
                StartSessionHandler.Reason.SESSION_IN_PROGRESS));
    }

    private UUID activate(DeviceSimulatorClient device) {
        UUID sessionId = UUID.randomUUID();
        env.start(sessionId);
        WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(start.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitStatus(sessionId, SessionStatus.ACTIVE);
        return sessionId;
    }

    private EndOutcome end(UUID sessionId, EndRequestReason reason) {
        return env.app.bean(EndRequestHandler.class).end(sessionId, UUID.randomUUID(), reason, null);
    }

    private void awaitStatus(UUID sessionId, SessionStatus status) {
        awaitTrue(() -> env.app.bean(ActivitySessionRepository.class).find(sessionId)
                .map(row -> row.status() == status).orElse(false), "세션 " + status);
    }

    private List<TranscriptRow> transcripts(UUID sessionId) {
        return env.app.bean(TranscriptRepository.class).findAll(sessionId);
    }

    /** 기기가 살아 있게 HEARTBEAT를 보내며 기다린다. */
    private static void keepAlive(DeviceSimulatorClient device, Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            device.heartbeat(20L);
            RealtimeItEnvironment.sleep(200);
        }
    }
}
