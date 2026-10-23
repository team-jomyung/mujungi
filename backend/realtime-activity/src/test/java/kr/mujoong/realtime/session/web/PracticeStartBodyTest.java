package kr.mujoong.realtime.session.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.session.StartSessionHandler;
import kr.mujoong.realtime.web.InternalBody;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 시작 본문의 연습 조각 처리 시험 (U12 FR1.1 · FR1.2 · BR1.1 · BR1.2). 본문 → 명령 변환이 PRACTICE에서만 practice 원문을 JSON으로 싣고, 자유대화는
 * 조각을 버리며, 계약 모양이 아니면 400 예외를 던지고, 명령의 문자열 표현에 조각이 없는지 본다. 서버 · DB 없음(테스트 대역 증거).
 */
@Tag("practice-flow")
@org.junit.jupiter.api.Tag("service-guardrail")
class PracticeStartBodyTest {

    private static final UUID GOAL = UUID.randomUUID();

    private static SessionInternalController.StartBody body(ActivityType type, Map<String, Object> practice) {
        Map<String, SessionInternalController.ApprovedBody> approved = new LinkedHashMap<>();
        approved.put("CLOSING", new SessionInternalController.ApprovedBody(UUID.randomUUID(), "마무리", "/media/approved/closing.wav"));
        approved.put("WAITING_FILLER",
                new SessionInternalController.ApprovedBody(UUID.randomUUID(), "잠깐만", "/media/approved/filler.wav"));
        Map<String, SessionInternalController.SpeechBody> speech = new LinkedHashMap<>();
        speech.put("COACH_SWITCH", new SessionInternalController.SpeechBody(UUID.randomUUID(), "코치가 도와줄게"));
        return new SessionInternalController.StartBody(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), type,
                new SessionInternalController.ConsentBody("1.0", true, true, false, false),
                new SessionInternalController.ChildBody("하늘", 8, List.of("공룡")),
                List.of(new SessionInternalController.StopBody("무중아 그만할래", true)), approved, speech, practice);
    }

    private static Map<String, Object> practice() {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("goalSelectionId", GOAL.toString());
        goal.put("order", 1);
        goal.put("displayName", "부탁하기");
        goal.put("judgmentCriteria", "원하는 것을 말로 요청한다");
        goal.put("firstScenario", Map.of("scenarioId", UUID.randomUUID().toString(), "scriptVersion", "scenario-script.v1"));
        return Map.of("goals", List.of(goal));
    }

    @Test
    void practiceCarriesThePracticeFragmentAsJsonAndTheApprovedSpeechIntoTheCommand() {
        StartSessionHandler.StartCommand command = SessionInternalController.toCommand(body(ActivityType.PRACTICE, practice()));

        assertThat(command.activityType()).isEqualTo(ActivityType.PRACTICE);
        Map<?, ?> read = JsonCodec.shared().read(command.practiceJson(), Map.class);
        assertThat(((List<?>) read.get("goals"))).hasSize(1);
        assertThat(command.practiceJson()).contains(GOAL.toString(), "원하는 것을 말로 요청한다", "scenario-script.v1");
        assertThat(command.approvedSpeech()).containsKey("COACH_SWITCH");
        assertThat(command.toString()).as("조각 원문은 문자열 표현에 없다").doesNotContain("원하는 것을").doesNotContain(GOAL.toString());
    }

    @Test
    void freeTalkDropsAnyPracticeFragmentAndPracticeWithoutOneKeepsNull() {
        assertThat(SessionInternalController.toCommand(body(ActivityType.FREE_TALK, practice())).practiceJson()).isNull();
        assertThat(SessionInternalController.toCommand(body(ActivityType.PRACTICE, null)).practiceJson()).isNull();
        assertThat(SessionInternalController.toCommand(body(ActivityType.FREE_TALK, null)).activityType())
                .isEqualTo(ActivityType.FREE_TALK);
    }

    @Test
    void anUnrecognizedActivityTypeIsStillRejected() {
        assertBadRequest(body(ActivityType.UNRECOGNIZED, null));
    }

    @Test
    void aPracticeFragmentOutsideTheContractShapeIsRejected() {
        assertBadRequest(body(ActivityType.PRACTICE, Map.of("goals", "text")));
        assertBadRequest(body(ActivityType.PRACTICE, Map.of("goals", List.of("text"))));
        assertBadRequest(body(ActivityType.PRACTICE, Map.of("goals", List.of(Map.of("goalSelectionId", "x")))));
        assertBadRequest(body(ActivityType.PRACTICE, Map.of("goals", List.of(Map.of("goalSelectionId", 7)))));
        assertBadRequest(body(ActivityType.PRACTICE, Map.of("goals", List.of(Map.of("firstScenario", "text")))));
    }

    private static void assertBadRequest(SessionInternalController.StartBody body) {
        assertThatThrownBy(() -> SessionInternalController.toCommand(body)).isInstanceOf(InternalBody.BadRequest.class);
    }
}
