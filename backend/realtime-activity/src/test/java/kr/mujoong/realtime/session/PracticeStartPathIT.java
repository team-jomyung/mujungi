package kr.mujoong.realtime.session;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.InternalApi.Reply;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.testing.RecordingPracticeExtension;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 연습 시작 경로 통합 시험 (U12 FR1.1 · FR1.2 · FR3.2 · BR1.1 · BR1.2 · D7). 실제 앱 · 실제 DB · 가짜 공급자 · 기기 시뮬레이터로
 * {@code POST /internal/sessions}에 PRACTICE + 선택 본문 {@code practice}를 보내 202로 받고, practice 원문이 확장의 {@code onActive} 맥락의
 * {@code practiceJson}에, 승인 발화 문장 지도가 {@code approvedSpeechTexts}에 닿는지 본다. 확장은 호출을 기록하는 대역이다. 이 시험의 합격은 테스트
 * 대역 증거이고 실제 AI · 실제 기기 성공이 아니다(R-F1 · R-M11).
 */
@Tag("practice-flow")
@Tag("db")
@org.junit.jupiter.api.Tag("service-guardrail")
class PracticeStartPathIT {

    private static final String SESSIONS = "/internal/sessions";
    private static final String COACH_TEXT = "이제부터 코치 목소리로 도와줄게";

    private final RecordingPracticeExtension extension = new RecordingPracticeExtension();
    private RealtimeItEnvironment env;

    @BeforeEach
    void start() {
        env = RealtimeItEnvironment.startWith(Map.of(), withExtension(extension));
    }

    @AfterEach
    void stop() {
        env.close();
    }

    private static ApplicationContextInitializer<ConfigurableApplicationContext> withExtension(RecordingPracticeExtension fake) {
        return context -> ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition("recordingPracticeFactory",
                BeanDefinitionBuilder.genericBeanDefinition(PracticeSessionExtension.Factory.class, () -> fake.factory)
                        .setPrimary(true).getBeanDefinition());
    }

    private String practiceBody(UUID sessionId, UUID goal, String practiceJson) {
        String base = InternalApi.startBody(sessionId, env.guardianId, env.childId, env.deviceId)
                .replace("\"FREE_TALK\"", "\"PRACTICE\"");
        String speech = "\"approvedSpeech\":{\"COACH_SWITCH\":{\"contentVersionId\":\"" + UUID.randomUUID() + "\",\"text\":\""
                + COACH_TEXT + "\"}},";
        return base.replace("\"practice\":null", speech + "\"practice\":" + practiceJson.replace("GOAL", goal.toString()));
    }

    private static final String PRACTICE = "{\"goals\":[{\"goalSelectionId\":\"GOAL\",\"order\":1,\"displayName\":\"부탁하기\","
            + "\"judgmentCriteria\":\"원하는 것을 말로 요청한다\",\"firstScenario\":{\"scenarioId\":\"" + UUID.randomUUID()
            + "\",\"scriptVersion\":\"scenario-script.v1\"}}]}";

    @Test
    void aPracticeStartWithThePracticeFragmentIs202AndTheFragmentAndApprovedSpeechReachOnActive() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        UUID goal = UUID.randomUUID();

        Reply reply = env.api().post(SESSIONS, practiceBody(sessionId, goal, PRACTICE));

        assertThat(reply.status()).isEqualTo(202);
        assertThat(reply.json().get("status").asString()).isEqualTo("PREPARING");
        assertThat(InternalApi.schemaErrors("internal-session-start-response.schema.json", reply.body())).isEmpty();
        assertThat(env.app.bean(ActivitySessionRepository.class).find(sessionId).orElseThrow().activityType())
                .isEqualTo(ActivityType.PRACTICE);
        WsEnvelope command = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(command.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitTrue(() -> env.sessionStatus(sessionId) == SessionStatus.ACTIVE, "ACTIVE");
        awaitTrue(() -> extension.context != null, "onActive 맥락");

        PracticeSessionExtension.PracticeContext context = extension.context;
        assertThat(context.sessionId()).isEqualTo(sessionId);
        assertThat(context.practiceJson()).contains(goal.toString(), "\"judgmentCriteria\":\"원하는 것을 말로 요청한다\"",
                "\"scriptVersion\":\"scenario-script.v1\"");
        assertThat(context.approvedSpeechTexts()).containsExactly(Map.entry("COACH_SWITCH", COACH_TEXT));
        assertThat(context.approvedContentCodes()).contains("CLOSING", "WAITING_FILLER").doesNotContain("COACH_SWITCH");
        assertThat(context.toString()).as("연습 조각 · 승인 발화 원문은 문자열 표현에 없다")
                .doesNotContain(COACH_TEXT).doesNotContain("judgmentCriteria");
    }

    @Test
    void aPracticeStartWithoutAFragmentIsStill202AndTheExtensionSeesNoPracticeJson() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        String body = InternalApi.startBody(sessionId, env.guardianId, env.childId, env.deviceId)
                .replace("\"FREE_TALK\"", "\"PRACTICE\"");

        assertThat(env.api().post(SESSIONS, body).status()).isEqualTo(202);
        WsEnvelope command = device.await(WsMessageType.START_SESSION, WAIT);
        device.ack(command.opId(), sessionId);
        device.deviceReady(sessionId, true, true);
        env.join(sessionId);
        awaitTrue(() -> extension.context != null, "onActive 맥락");

        assertThat(extension.context.practiceJson()).isNull();
        assertThat(extension.context.approvedSpeechTexts()).isEmpty();
    }

    @Test
    void aMalformedPracticeFragmentIs400AndCreatesNothing() {
        DeviceSimulatorClient device = env.connectDevice();
        UUID sessionId = UUID.randomUUID();
        UUID goal = UUID.randomUUID();

        for (String bad : new String[] {"{\"goals\":\"not-a-list\"}", "{\"goals\":[\"not-an-object\"]}",
                "{\"goals\":[{\"goalSelectionId\":\"not-a-uuid\"}]}", "{\"goals\":[{\"goalSelectionId\":42}]}",
                "{\"goals\":[{\"firstScenario\":\"text\"}]}", "\"just-a-string\""}) {
            Reply reply = env.api().post(SESSIONS, practiceBody(sessionId, goal, bad));
            assertThat(reply.status()).as(bad).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_FAILED");
        }

        assertThat(env.app.bean(ActivitySessionRepository.class).find(sessionId)).isEmpty();
        assertThat(device.received(WsMessageType.START_SESSION)).isEmpty();
        assertThat(extension.created).isZero();
    }
}
