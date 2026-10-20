package kr.mujoong.realtime.session.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.session.StartSessionHandler;
import kr.mujoong.realtime.web.InternalBody;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 세션 수신부의 선택 속성 approvedSpeech (U8 functional-spec 8.1 · BR1.2 · NFR6.1 · SD7 · 편차 U8-b). 실제 수신 경로와 같게 본문 JSON을
 * {@link JsonCodec}으로 {@code StartBody}에 읽은 뒤 명령으로 바꾼다.
 */
@Tag("live-content")
class SessionStartReceiveTest {

    private static final String SPEECH_VERSION = "00000000-0000-4000-8000-0000000008b3";

    @Test
    void anOldPackageWithoutApprovedSpeechStartsAsBefore() {
        StartSessionHandler.StartCommand command = command(body(""));

        assertThat(command.approvedSpeech()).isEmpty();
        assertThat(command.approvedContent()).containsOnlyKeys("CLOSING", "WAITING_FILLER");
    }

    @Test
    void approvedSpeechIsCarriedIntoTheCommandAndUnknownPropertiesAreNotRejected() {
        StartSessionHandler.StartCommand command = command(body("""
                ,"approvedSpeech":{"D_REST_ACK":{"contentVersionId":"%s","text":"잠깐 쉬고 싶구나. 천천히 해도 괜찮아."}},
                 "futureField":{"anything":true}""".formatted(SPEECH_VERSION)));

        assertThat(command.approvedSpeech()).containsOnlyKeys("D_REST_ACK");
        assertThat(command.approvedSpeech().get("D_REST_ACK").contentVersionId()).isEqualTo(UUID.fromString(SPEECH_VERSION));
        assertThat(command.approvedSpeech().get("D_REST_ACK").text()).isEqualTo("잠깐 쉬고 싶구나. 천천히 해도 괜찮아.");
        assertThat(command.approvedSpeech().get("D_REST_ACK").toString()).doesNotContain("쉬고");
        assertThat(command.toString()).doesNotContain("쉬고");
    }

    @Test
    void approvedSpeechOutsideTheSchemaRulesIsRejected() {
        assertThatThrownBy(() -> command(body("""
                ,"approvedSpeech":{"d_rest_ack":{"contentVersionId":"%s","text":"x"}}""".formatted(SPEECH_VERSION))))
                .isInstanceOf(InternalBody.BadRequest.class);
        assertThatThrownBy(() -> command(body("""
                ,"approvedSpeech":{"D_REST_ACK":{"text":"x"}}""")))
                .isInstanceOf(InternalBody.BadRequest.class);
        assertThatThrownBy(() -> command(body("""
                ,"approvedSpeech":{"D_REST_ACK":{"contentVersionId":"%s","text":"%s"}}"""
                .formatted(SPEECH_VERSION, "가".repeat(201))))).isInstanceOf(InternalBody.BadRequest.class);
    }

    @Test
    void aDuplicatedCodeIsStillAcceptedHereAndLeftToTheStartHandler() {
        StartSessionHandler.StartCommand command = command(body("""
                ,"approvedSpeech":{"CLOSING":{"contentVersionId":"%s","text":"안녕!"}}""".formatted(SPEECH_VERSION)));

        assertThat(command.approvedSpeech()).containsOnlyKeys("CLOSING");
        assertThat(command.approvedContent()).containsKey("CLOSING");
    }

    // ------------------------------------------------------------------ 도움

    private static StartSessionHandler.StartCommand command(String json) {
        return SessionInternalController.toCommand(
                InternalBody.parse(JsonCodec.shared(), json, SessionInternalController.StartBody.class, ErrorCode.VALIDATION_FAILED));
    }

    /** 계약 예시와 같은 자유대화 패키지 + 덧붙일 속성 조각. */
    private static String body(String extra) {
        return """
                {"sessionId":"00000000-0000-4000-8000-000000000020","idempotencyKey":"00000000-0000-4000-8000-000000000010",
                 "guardianId":"00000000-0000-4000-8000-000000000001","childId":"00000000-0000-4000-8000-000000000002",
                 "deviceId":"00000000-0000-4000-8000-000000000003","activityType":"FREE_TALK",
                 "consentSnapshot":{"policyVersion":"1.0","voiceAndText":true,"guardianView":true,"reportSharing":true,"rawAudio":true},
                 "childContext":{"displayName":"하늘","age":8,"interests":["공룡"]},
                 "stopExpressions":[{"text":"무중아 그만할래","isDefault":true}],
                 "approvedContent":{
                   "CLOSING":{"contentVersionId":"00000000-0000-4000-8000-000000000041","text":"안녕!","audioUrl":"/media/approved/closing-v1.wav"},
                   "WAITING_FILLER":{"contentVersionId":"00000000-0000-4000-8000-000000000042","text":"음.","audioUrl":"/media/approved/waiting-filler-v1.wav"}},
                 "practice":null%s}
                """.formatted(extra);
    }
}
