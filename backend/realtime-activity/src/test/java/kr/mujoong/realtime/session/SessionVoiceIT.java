package kr.mujoong.realtime.session;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.OutputBegin;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.recording.AudioRecordingRepository;
import kr.mujoong.realtime.recording.AudioRecordingRepository.AudioRecordingRow;
import kr.mujoong.realtime.recording.FileAudioRecorder;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * 음성 대화 끝-끝 U3 시험(design.md 4절 U3-R5 ~ R10 · U3-J6 · J11 ~ J14 · J17 · AC6.2.12 · AC6.4.7 · AC6.6.3 · B18).
 *
 * <p>실제 서버 · DB · 가짜 공급자 WS · 기기 시뮬레이터로: 끼어들기가 공급자에 응답 취소로 가는지, 금지 패턴 폐기 뒤 교정 문장이 가는지, 말 끝에
 * '생각 중'이 보이는지, 공급자 연결이 끊기면 ABORTED(PROVIDER_LOST)로 끝나는지, 신뢰도 낮은 전사가 검토 표시로 저장되는지, 원음이 동의한
 * 세션에서만 파일 · 메타로 남는지 본다.
 */
@Tag("voice-session")
@Tag("db")
class SessionVoiceIT {

    /**
     * 기기 · 공급자 메시지를 기다리는 상한(2차 리뷰 9). 서버 시한을 운영값으로 둬서 느린 단계도 결국 오므로, 실패를 알리는 데만 쓰는 이 상한은
     * 넉넉히 둔다(통과하는 실행은 메시지가 오는 즉시 돌아온다).
     */
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final byte[] PCM = new byte[48_000];
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path recordings;

    private RealtimeItEnvironment env;

    @AfterEach
    void stop() {
        if (env != null) {
            env.close();
        }
    }

    @Test
    void aBargeInDropsTheResponseByGenerationAndAPatternDiscardSendsItsCorrection() {
        start();
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device, false);

        env.provider.emitTurn("item-1", "공룡 이야기", "공룡은 아주 오래전에 살았어", List.of(PCM));
        // U3-J6: 말 끝 신호에 '생각 중'
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.THINKING, WAIT);
        UUID playbackId = ((OutputBegin) device.await(WsMessageType.OUTPUT_BEGIN, WAIT).payload()).playbackId();
        device.playbackMark(sessionId, playbackId, PlaybackMark.Status.STARTED);
        device.playbackMark(sessionId, playbackId, PlaybackMark.Status.STOPPED, null,
                PlaybackMark.StopCause.LOCAL_VOICE_ACTIVITY);

        // U11 BR3.4: 응답 취소 type은 공급자 지원 목록 밖이다 — 끼어들기는 서버 출력 세대로 버리고 공급자로는 보내지 않는다
        assertThat(env.provider.received("session.response.cancel")).isEmpty();
        // 재생이 끝났으니 다시 '듣는 중'
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.LISTENING
                && device.received(WsMessageType.SESSION_STATE).size() >= 3, WAIT);

        pauseForTheGap();
        env.provider.emitTurn("item-2", "또 해 줘", "진단 결과는 이래", List.of(PCM));
        device.await(envelope -> envelope.payload() instanceof PlayApprovedContent play
                && PlayApprovedContent.WAITING_FILLER.equals(play.contentCode()), WAIT);
        // 설계 개정 A2: 첫 응답 지시(mj-1) 다음에 교정 지시 하나가 같은 큐로 나간다(교정도 발화를 시작하는 지시다)
        List<JsonNode> instructions = env.provider.await("session.instructions.append", 2, WAIT);
        assertThat(instructions).hasSize(2);
        assertThat(instructions.get(0).get("content").asString()).isEqualTo(InstructionTemplates.ResponseStart.FIRST.content());
        assertThat(instructions.get(1).get("content").asString())
                .isEqualTo(InstructionTemplates.correctionContent(DialogInstructions.Correction.DIAGNOSIS));
        assertThat(instructions).allSatisfy(append -> assertThat(append.get("delegation_id").isNull()).isTrue());
        assertThat(String.join("\n", env.provider.rawReceived())).doesNotContain("진단 결과는");
        assertThat(device.received(WsMessageType.OUTPUT_BEGIN)).hasSize(1);
        assertThat(session(sessionId).status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aProviderThatClosesMidSessionAbortsItAsProviderLostAndReturnsTheDeviceToStandby() {
        start();
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device, false);

        env.provider.closeCurrent();

        awaitStatus(sessionId, SessionStatus.ABORTED);
        ActivitySessionRow row = session(sessionId);
        assertThat(row.endReason()).isEqualTo(EndReason.DISCONNECT);
        assertThat(row.endDetail()).isEqualTo("PROVIDER_LOST");
        assertThat(row.providerClosedAt()).isNotNull();
        WsEnvelope close = device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        assertThat(((CloseTransport) close.payload()).reason()).isEqualTo(CloseTransport.Reason.DISCONNECT);
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.STANDBY, WAIT);
        assertThat(device.isClosed()).as("기기 연결은 그대로다").isFalse();
    }

    @Test
    void childTranscriptsBuiltFromGroupedDeltasAreStoredAsCertainBecauseNoConfidenceArrives() {
        start();
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device, false);

        // 공급자는 입력 전사 완료 사건도 신뢰도도 보내지 않는다(설계 개정 A7): 조각을 묶어 만든 확정 전사는 확실로 본다.
        // 신뢰도가 낮은 전사의 검토 표시는 확정 전사 입구를 직접 부르는 실행기 단위 시험이 지킨다
        env.childSays("item-1", "응");
        pauseForTheGap();
        env.childSays("item-2", "공룡 좋아");
        pauseForTheGap();
        env.childSays("item-3", "몰라");

        TranscriptRepository transcripts = env.app.bean(TranscriptRepository.class);
        awaitTrue(() -> transcripts.findAll(sessionId).stream().filter(row -> row.speaker() == TranscriptEntry.Speaker.CHILD
                && row.assemblyStatus() == TranscriptEntry.AssemblyStatus.FINAL).count() == 3, "아이 전사 3행");
        Map<String, Boolean> review = new LinkedHashMap<>();
        transcripts.findAll(sessionId).forEach(row -> review.put(row.text(), row.reviewNeeded()));
        assertThat(review).containsExactly(Map.entry("응", false), Map.entry("공룡 좋아", false), Map.entry("몰라", false));
        assertThat(transcripts.findAll(sessionId)).allSatisfy(row ->
                assertThat(row.assemblyStatus()).isEqualTo(TranscriptEntry.AssemblyStatus.FINAL));
    }

    @Test
    void rawAudioIsWrittenOnlyForAConsentedSessionAndItsMetadataIsFinishedAtTheEnd() throws Exception {
        start();
        DeviceSimulatorClient device = env.connectDevice();
        UUID consented = activate(device, true);
        byte[] frame = new byte[960];
        frame[0] = 7;
        for (int i = 0; i < 5; i++) {
            device.sendAudio(frame);
        }
        env.provider.await("session.input_audio.append", 5, WAIT);
        env.provider.emitTurn("item-1", "안녕", "반가워 하늘아", List.of(PCM));
        device.await(WsMessageType.OUTPUT_BEGIN, WAIT);
        endConfirmed(device, consented);

        AudioRecordingRepository rows = env.app.bean(AudioRecordingRepository.class);
        awaitTrue(() -> rows.findBySession(consented).size() == 2
                && rows.findBySession(consented).stream().allMatch(row -> row.endedAt() != null), "원음 메타 2행 닫힘");
        AudioRecordingRow child = rows.find(consented, AudioRecordingRepository.Speaker.CHILD).orElseThrow();
        AudioRecordingRow ai = rows.find(consented, AudioRecordingRepository.Speaker.AI).orElseThrow();
        assertThat(child.byteCount()).isEqualTo(5L * frame.length);
        assertThat(ai.byteCount()).isEqualTo(PCM.length);
        FileAudioRecorder recorder = env.app.bean(FileAudioRecorder.class);
        assertThat(recorder.directory()).isEqualTo(recordings.toAbsolutePath().normalize());
        Path childFile = recorder.resolve(child.storagePath());
        assertThat(Files.size(childFile)).isEqualTo(5L * frame.length);
        assertThat(Files.readAllBytes(childFile)[0]).isEqualTo((byte) 7);
        assertThat(Files.size(recorder.resolve(ai.storagePath()))).isEqualTo(PCM.length);

        // 동의하지 않은 세션: 같은 기기 · 같은 흐름이어도 메타 · 파일이 없다
        UUID plain = activate(device, false);
        for (int i = 0; i < 5; i++) {
            device.sendAudio(frame);
        }
        env.provider.await("session.input_audio.append", 10, WAIT);
        endConfirmed(device, plain);
        assertThat(rows.findBySession(plain)).isEmpty();
        assertThat(Files.exists(recordings.resolve(plain.toString()))).isFalse();
    }

    // ------------------------------------------------------------------ 보조

    /**
     * 기록 디렉터리를 시험 임시 폴더로 두고, 이 흐름이 지나는 서버 시한을 모두 운영값으로 둔다(2차 리뷰 9). 시험 프로필의 줄인 값(START 확인 2초 ·
     * 마무리 확인 3초 · HELLO 0.5초 · verify-token 0.1초 · 공급자 연결 1초 · 무음 4초)이면 기계가 바쁠 때 서버가 먼저 FAILED · ENDED_UNCONFIRMED ·
     * 1013으로 끝내 버려, 기기가 기다리던 LISTENING · CLOSE_TRANSPORT · HELLO_OK가 영영 오지 않고 시험은 '제한 PT10S'로만 실패한다. 이 클래스는
     * 시한 경계가 아니라 정상 흐름을 보므로 줄인 값이 필요 없다. 3차 리뷰 3: 조용 시간(segment-quiet)도 운영값 800ms로 둔다 — 가짜 공급자는 AI
     * 전사와 음성(64KB)을 따로 보내는데, 둘 사이가 시험값 50ms를 넘으면 그 턴의 두 구간이 모두 GATE_EMPTY로 버려져 OUTPUT_BEGIN이 아예 나가지
     * 않는다(조정자 실패: 152행 OUTPUT_BEGIN 30초 대기).
     */
    private void start() {
        env = RealtimeItEnvironment.start(Map.of("mujoong.recording.directory", recordings.toString(),
                "mujoong.timing.device.disconnect", "40s",
                "mujoong.timing.session.device-ack-timeout", "20s",
                "mujoong.timing.session.closing-confirm", "30s",
                "mujoong.timing.device.hello-timeout", "5s",
                "mujoong.internal-http.verify-token-timeout", "1s",
                "mujoong.provider.connect-timeout", "10s",
                "mujoong.gate.segment-quiet", "800ms"), null);
    }

    private UUID activate(DeviceSimulatorClient device, boolean rawAudio) {
        UUID sessionId = UUID.randomUUID();
        StartSessionHandler.StartCommand base = env.startCommand(sessionId, new ChildContext("하늘", 8, List.of("공룡")));
        StartSessionHandler.StartCommand command = new StartSessionHandler.StartCommand(base.sessionId(),
                base.idempotencyKey(), base.guardianId(), base.childId(), base.deviceId(), base.activityType(),
                new ConsentSnapshot("1.0", true, true, false, rawAudio), base.childContext(), base.stopExpressions(),
                base.approvedContent());
        env.app.bean(StartSessionHandler.class).start(command, TRACE);
        WsEnvelope start = device.await(envelope -> envelope.messageType() == WsMessageType.START_SESSION
                && sessionId.equals(envelope.sessionId()), WAIT);
        device.ack(start.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitStatus(sessionId, SessionStatus.ACTIVE);
        device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.LISTENING
                && sessionId.equals(envelope.sessionId()), WAIT);
        return sessionId;
    }

    /** 보호자 끝내기 → 마무리 문구 끝 표식 → 기기 닫힘 확인 → ENDED. */
    private void endConfirmed(DeviceSimulatorClient device, UUID sessionId) {
        env.app.bean(EndRequestHandler.class).end(sessionId, UUID.randomUUID(), EndRequestReason.GUARDIAN_STOP, null);
        WsEnvelope closing = device.await(envelope -> envelope.payload() instanceof PlayApprovedContent play
                && PlayApprovedContent.CLOSING.equals(play.contentCode()) && sessionId.equals(envelope.sessionId()), WAIT);
        device.ack(closing.opId(), sessionId);
        device.playbackMark(sessionId, ((PlayApprovedContent) closing.payload()).playbackId(),
                PlaybackMark.Status.COMPLETED);
        device.await(envelope -> envelope.messageType() == WsMessageType.CLOSE_TRANSPORT
                && sessionId.equals(envelope.sessionId()), WAIT);
        device.voiceSessionClosed(sessionId);
        awaitStatus(sessionId, SessionStatus.ENDED);
    }

    private ActivitySessionRow session(UUID sessionId) {
        return env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow();
    }

    private void awaitStatus(UUID sessionId, SessionStatus status) {
        awaitTrue(() -> env.app.bean(ActivitySessionRepository.class).find(sessionId)
                .map(row -> row.status() == status).orElse(false), "세션 " + status);
    }

    /** 끼어들기로 올린 출력 세대의 꼬리가 끊겨 다음 응답을 받을 수 있을 때까지(이 클래스는 운영값 800ms) 잠깐 기다린다. */
    private static void pauseForTheGap() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리는 중 중단됐다", e);
        }
    }
}
