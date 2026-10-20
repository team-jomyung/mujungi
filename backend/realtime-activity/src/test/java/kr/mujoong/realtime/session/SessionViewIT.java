package kr.mujoong.realtime.session;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * U4b 진행 조회 통합 시험 (L4 · FR6.1 · BR7.1 · NFR8).
 *
 * <p>{@code GET /internal/sessions/{id}/view}가 실행기의 살아 있는 값(technicalPause · silenceWarning)을 채우고, 응답이 계약 스키마에 맞으며,
 * 종료 세션은 둘 다 null인지 본다. 공백 시계만 이 클래스에서 짧게 덮어쓴다(U4b-J26).
 */
@Tag("session-runtime")
@Tag("db")
class SessionViewIT {

    private static final Map<String, String> ARGS = Map.of(
            "mujoong.timing.session.silence-warning", "1500ms",
            "mujoong.timing.session.silence-end", "60s",
            // 점선(3초) 뒤 일시정지를 볼 시간을 두려고 끊김 시한만 늘린다
            "mujoong.timing.device.disconnect", "20s");

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
    void aSilentDeviceShowsTheTechnicalPauseAndAHeartbeatClearsIt() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);

        // test 프로필 점선 3초 동안 기기 메시지가 없다
        awaitTrue(() -> view(sessionId).json().get("technicalPause").isObject(), "technicalPause");
        InternalApi.Reply paused = view(sessionId);
        assertThat(paused.json().get("technicalPause").get("kind").asString()).isEqualTo("DEVICE");
        assertThat(InternalApi.schemaErrors("internal-session-view.schema.json", paused.body())).isEmpty();
        assertThat(paused.json().get("gaps")).isNotEmpty();

        device.heartbeat(20L);
        awaitTrue(() -> view(sessionId).json().get("technicalPause").isNull(), "일시정지 해제");
    }

    @Test
    void theSilenceWarningAppearsWhileTheDeviceStaysAliveAndAnEndedSessionShowsNeither() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = activate(device);

        awaitTrue(() -> {
            device.heartbeat(20L);
            return view(sessionId).json().get("silenceWarning").isObject();
        }, "silenceWarning");
        InternalApi.Reply warned = view(sessionId);
        assertThat(warned.json().get("silenceWarning").get("silentSince").asString()).isNotBlank();
        assertThat(warned.json().get("silenceWarning").get("endsAt").asString()).isNotBlank();
        assertThat(InternalApi.schemaErrors("internal-session-view.schema.json", warned.body())).isEmpty();

        env.api().post("/internal/sessions/" + sessionId + "/end",
                InternalApi.endBody(UUID.randomUUID(), "CORE_ABANDONED"));
        awaitTrue(() -> env.sessionStatus(sessionId) == SessionStatus.FAILED, "FAILED");

        InternalApi.Reply ended = view(sessionId);
        assertThat(ended.json().get("technicalPause").isNull()).isTrue();
        assertThat(ended.json().get("silenceWarning").isNull()).isTrue();
        assertThat(InternalApi.schemaErrors("internal-session-view.schema.json", ended.body())).isEmpty();
    }

    private UUID activate(DeviceSimulatorClient device) {
        UUID sessionId = UUID.randomUUID();
        env.start(sessionId);
        WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(start.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        return sessionId;
    }

    private InternalApi.Reply view(UUID sessionId) {
        return env.api().get("/internal/sessions/" + sessionId + "/view");
    }
}
