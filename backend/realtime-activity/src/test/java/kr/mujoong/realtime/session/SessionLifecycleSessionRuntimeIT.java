package kr.mujoong.realtime.session;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.CloseTransport;
import kr.mujoong.contract.ws.message.PlayApprovedContent;
import kr.mujoong.contract.ws.message.PlaybackMark;
import kr.mujoong.contract.ws.message.StopOutput;
import kr.mujoong.realtime.log.TranscriptEntry;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.log.TranscriptRow;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.voice.ProviderRequestFactory;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 세션 생애 끝-끝 통합 시험 (L3 · FR1.1 · FR1.4 · FR2.1 ~ FR2.4 · BR2.1 ~ BR2.7 · BR3.1 · BR3.2 · BR4.7).
 *
 * <p>실제 DB · test 프로필 앱 · 기기 시뮬레이터 · 가짜 공급자로 WAITING_CHILD → 참여 응답 → ACTIVE → 대화를 돌린다. 참여 창은 이 클래스만 짧게
 * 덮어쓴다(U4b-J26).
 */
@Tag("session-runtime")
@Tag("db")
@org.junit.jupiter.api.Tag("service-guardrail")
class SessionLifecycleSessionRuntimeIT {

    private static final Map<String, String> ARGS = Map.of(
            "mujoong.timing.session.join-wait", "1500ms",
            "mujoong.timing.session.join-wait-after-reprompt", "1500ms");
    private static final String INTRO = ServiceGuardrailFixtures.TEXT;

    private RealtimeItEnvironment env;

    @BeforeEach
    void start() {
        env = RealtimeItEnvironment.start(ARGS, null);
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void waitingChildThenAJoinReplyActivatesAndHandsTheFloorToTheModelOnce() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = startWithPrompts(device);

        awaitStatus(sessionId, SessionStatus.WAITING_CHILD);
        WsEnvelope intro = device.await(WsMessageType.PLAY_APPROVED_CONTENT, WAIT);
        assertThat(((PlayApprovedContent) intro.payload()).contentCode()).isEqualTo(SessionExecutor.INTRO_FREE_TALK);
        assertThat(env.provider.received(ProviderRequestFactory.TYPE_INSTRUCTIONS_APPEND)).isEmpty();
        device.playbackMark(sessionId, ((PlayApprovedContent) intro.payload()).playbackId(),
                PlaybackMark.Status.COMPLETED);
        awaitTrue(() -> env.app.bean(SessionGuardrailFactsRepository.class).latest(sessionId)
                .map(value -> value.notice() == SessionGuardrailFacts.Notice.COMPLETED).orElse(false), "현재 안내 완료");
        env.childSays("join-1", "응");

        awaitStatus(sessionId, SessionStatus.ACTIVE);
        assertThat(env.provider.await(ProviderRequestFactory.TYPE_INSTRUCTIONS_APPEND, 1, WAIT)).hasSize(1);
        ActivitySessionRow row = sessions().find(sessionId).orElseThrow();
        assertThat(row.startedAt()).isNotNull();
        assertThat(row.statusVersion()).isEqualTo(4);
        // 시작 안내는 말풍선 한 행이고, 참여를 수락한 대답은 수락 때 아이 행으로 이어서 남는다
        awaitTrue(() -> transcripts(sessionId).size() == 2, "말풍선 두 행");
        assertThat(transcripts(sessionId)).satisfiesExactly(bubble -> {
            assertThat(bubble.speaker()).isEqualTo(TranscriptEntry.Speaker.AI);
            assertThat(bubble.text()).isEqualTo(INTRO);
            assertThat(bubble.approvedForDisplay()).isTrue();
        }, reply -> {
            assertThat(reply.speaker()).isEqualTo(TranscriptEntry.Speaker.CHILD);
            assertThat(reply.text()).isEqualTo("응");
            assertThat(reply.approvedForDisplay()).isTrue();
        });

        env.provider.emitTurn("item-1", "공룡이 좋아", "나도 공룡 좋아해", List.of(kr.mujoong.realtime.testing.RealtimeItEnvironment.audiblePcm(4800)));
        device.await(WsMessageType.OUTPUT_END, WAIT);
        // 시작 안내 · 수락한 참여 대답 · 이어진 아이 말 · AI 응답
        awaitTrue(() -> transcripts(sessionId).size() == 4, "아이 · AI 행");
    }

    @Test
    void consentWithdrawnOverHttpWhileActiveClosesWithItsReasonAndBeforeClosing() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = startPlain(device);
        env.join(sessionId);

        InternalApi.Reply reply = env.api().post("/internal/sessions/" + sessionId + "/end",
                InternalApi.endBody(UUID.randomUUID(), "CONSENT_WITHDRAWN"));

        assertThat(reply.status()).isEqualTo(202);
        assertThat(reply.json().get("status").asString()).isEqualTo("CLOSING");
        assertThat(InternalApi.schemaErrors("internal-session-end-response.schema.json", reply.body())).isEmpty();
        WsEnvelope stop = device.await(WsMessageType.STOP_OUTPUT, WAIT);
        assertThat(((StopOutput) stop.payload()).reason()).isEqualTo(StopOutput.Reason.BEFORE_CLOSING);
        ActivitySessionRow row = sessions().find(sessionId).orElseThrow();
        assertThat(row.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(row.endReason()).isEqualTo(EndReason.CONSENT_WITHDRAWN);
        // 반복 요청은 같은 답, 전이 · 기기 명령 없음
        InternalApi.Reply again = env.api().post("/internal/sessions/" + sessionId + "/end",
                InternalApi.endBody(UUID.randomUUID(), "CONSENT_WITHDRAWN"));
        assertThat(again.status()).isEqualTo(202);
        assertThat(sessions().find(sessionId).orElseThrow().statusVersion()).isEqualTo(row.statusVersion());
        assertThat(device.received(WsMessageType.STOP_OUTPUT)).hasSize(1);
    }

    @Test
    void consentWithdrawnInTheStartPhaseFailsWithAnEmptyAccepted() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        env.start(sessionId);
        device.await(WsMessageType.START_SESSION, WAIT);

        InternalApi.Reply reply = env.api().post("/internal/sessions/" + sessionId + "/end",
                InternalApi.endBody(UUID.randomUUID(), "CONSENT_WITHDRAWN"));

        assertThat(reply.status()).isEqualTo(202);
        assertThat(reply.json().isEmpty()).isTrue();
        awaitStatus(sessionId, SessionStatus.FAILED);
        assertThat(sessions().find(sessionId).orElseThrow().endReason()).isEqualTo(EndReason.CONSENT_WITHDRAWN);
        WsEnvelope close = device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        assertThat(((CloseTransport) close.payload()).reason()).isEqualTo(CloseTransport.Reason.CONSENT_WITHDRAWN);
    }

    @Test
    void oneCurrentRefusalDeclinesWithoutAlternativeErasesTheBubblesAndEndsWithTheFarewell() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = startWithPrompts(device);
        awaitStatus(sessionId, SessionStatus.WAITING_CHILD);
        awaitTrue(() -> transcripts(sessionId).size() == 1, "시작 안내 말풍선");
        env.completeNotice(sessionId);

        env.childSays("r-1", "싫어");

        awaitStatus(sessionId, SessionStatus.DECLINED);
        assertThat(sessions().find(sessionId).orElseThrow().endReason()).isEqualTo(EndReason.DECLINED);
        awaitTrue(() -> device.received(WsMessageType.PLAY_APPROVED_CONTENT).size() == 2, "대안 없이 마무리 인사");
        assertThat(device.received(WsMessageType.PLAY_APPROVED_CONTENT)).noneMatch(envelope ->
                ((PlayApprovedContent) envelope.payload()).contentCode().equals(SessionExecutor.DECLINE_ALTERNATIVE));
        PlayApprovedContent farewell = (PlayApprovedContent) device.received(WsMessageType.PLAY_APPROVED_CONTENT)
                .getLast().payload();
        assertThat(farewell.contentCode()).isEqualTo(PlayApprovedContent.CLOSING);
        device.playbackMark(sessionId, farewell.playbackId(), PlaybackMark.Status.COMPLETED);
        WsEnvelope close = device.await(WsMessageType.CLOSE_TRANSPORT, WAIT);
        assertThat(((CloseTransport) close.payload()).reason()).isEqualTo(CloseTransport.Reason.DECLINED);
        // BR3.2: 그 세션의 전사 행은 0건(인사 말풍선도 남기지 않는다)
        assertThat(env.db.single("select count(*) from realtime.realtime_transcript where session_id = cast(:id as uuid)",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", sessionId.toString()),
                Long.class)).isZero();
        assertThat(device.received(WsMessageType.STOP_OUTPUT)).isEmpty();
    }

    private UUID startWithPrompts(DeviceSimulatorClient device) {
        UUID sessionId = UUID.randomUUID();
        StartSessionHandler.StartCommand base = env.startCommand(sessionId,
                new ChildContext("하늘", 8, List.of("공룡")));
        Map<String, StartSessionHandler.ApprovedContentItem> approved = new LinkedHashMap<>(base.approvedContent());
        approved.put(SessionExecutor.INTRO_FREE_TALK, new StartSessionHandler.ApprovedContentItem(ServiceGuardrailFixtures.INTRO,
                INTRO, ServiceGuardrailFixtures.URL));
        approved.put(SessionExecutor.RE_PROMPT, new StartSessionHandler.ApprovedContentItem(UUID.fromString("11111111-1111-4111-8111-111111111117"),
                INTRO, "/media/approved/re_prompt.wav"));
        approved.put(SessionExecutor.DECLINE_ALTERNATIVE, new StartSessionHandler.ApprovedContentItem(UUID.randomUUID(),
                "그럼 다른 이야기를 해 볼까?", "/media/approved/alternative.wav"));
        StartSessionHandler.StartCommand command = new StartSessionHandler.StartCommand(sessionId,
                base.idempotencyKey(), base.guardianId(), base.childId(), base.deviceId(), base.activityType(),
                base.consentSnapshot(), base.childContext(), base.stopExpressions(), approved);
        env.app.bean(StartSessionHandler.class).start(command, "0123456789abcdef0123456789abcdef");
        WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(start.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        return sessionId;
    }

    private UUID startPlain(DeviceSimulatorClient device) {
        UUID sessionId = UUID.randomUUID();
        env.start(sessionId);
        WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(start.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        return sessionId;
    }

    private ActivitySessionRepository sessions() {
        return env.app.bean(ActivitySessionRepository.class);
    }

    private List<TranscriptRow> transcripts(UUID sessionId) {
        return env.app.bean(TranscriptRepository.class).findAll(sessionId);
    }

    private void awaitStatus(UUID sessionId, SessionStatus status) {
        awaitTrue(() -> env.sessionStatus(sessionId) == status, "세션 " + status);
    }
}
