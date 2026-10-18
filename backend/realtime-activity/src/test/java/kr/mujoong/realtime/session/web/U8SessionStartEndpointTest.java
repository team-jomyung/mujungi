package kr.mujoong.realtime.session.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.config.RealtimeLimitsProperties;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.session.EndRequestHandler;
import kr.mujoong.realtime.session.SessionViewQuery;
import kr.mujoong.realtime.session.StartSessionHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;

/**
 * {@code POST /internal/sessions}가 선택 속성 approvedSpeech가 있는 본문 · 없는 옛 본문을 모두 받아 시작 처리기에 넘기고, 처리 결과를 계약 응답
 * (202 · 409 · 423)으로 답하는지 본다(U8 NFR6.1 · SD7 · 편차 U8-b, 계약 internal-api.yaml 예시와 같은 본문).
 */
@Tag("u8")
class U8SessionStartEndpointTest {

    private final StartSessionHandler starts = mock(StartSessionHandler.class);
    private final SessionInternalController controller = controller();

    @Test
    void theContractExampleBodyWithApprovedSpeechIsAcceptedAndHandedOver() {
        when(starts.start(any(), any())).thenReturn(new StartSessionHandler.Accepted(SessionStatus.PREPARING));

        ResponseEntity<String> response = controller.start(request(body("""
                ,"approvedSpeech":{"D_REST_ACK":{"contentVersionId":"00000000-0000-4000-8000-0000000008b3",
                  "text":"잠깐 쉬고 싶구나. 천천히 해도 괜찮아."}}""")));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"PREPARING\"}");
        ArgumentCaptor<StartSessionHandler.StartCommand> command = ArgumentCaptor.forClass(StartSessionHandler.StartCommand.class);
        verify(starts).start(command.capture(), any());
        assertThat(command.getValue().approvedSpeech()).containsOnlyKeys("D_REST_ACK");
    }

    @Test
    void anOldBodyIsStillAcceptedAndRejectionsKeepTheirContractCodes() {
        when(starts.start(any(), any()))
                .thenReturn(new StartSessionHandler.Rejected(StartSessionHandler.Reason.DEVICE_NOT_CONNECTED))
                .thenReturn(new StartSessionHandler.Rejected(StartSessionHandler.Reason.SESSION_IN_PROGRESS))
                .thenReturn(new StartSessionHandler.Rejected(StartSessionHandler.Reason.SESSION_NOT_ACTIVE));

        ResponseEntity<String> notConnected = controller.start(request(body("")));
        ResponseEntity<String> inProgress = controller.start(request(body("")));
        ResponseEntity<String> notActive = controller.start(request(body("")));

        assertThat(notConnected.getStatusCode().value()).isEqualTo(423);
        assertThat(notConnected.getBody()).contains("\"code\":\"DEVICE_NOT_CONNECTED\"");
        assertThat(inProgress.getStatusCode().value()).isEqualTo(409);
        assertThat(inProgress.getBody()).contains("\"code\":\"SESSION_IN_PROGRESS\"");
        assertThat(notActive.getStatusCode().value()).isEqualTo(409);
        assertThat(notActive.getBody()).contains("\"code\":\"SESSION_NOT_ACTIVE\"");
    }

    // ------------------------------------------------------------------ 도움

    private SessionInternalController controller() {
        RealtimeProperties properties = mock(RealtimeProperties.class);
        RealtimeLimitsProperties limits = mock(RealtimeLimitsProperties.class);
        when(properties.limits()).thenReturn(limits);
        when(limits.internalEventBodyMax()).thenReturn(DataSize.ofKilobytes(64));
        return new SessionInternalController(starts, mock(EndRequestHandler.class), mock(SessionViewQuery.class), JsonCodec.shared(),
                properties);
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/sessions");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static String body(String extra) {
        return """
                {"sessionId":"00000000-0000-4000-8000-000000000020","idempotencyKey":"00000000-0000-4000-8000-000000000010",
                 "guardianId":"00000000-0000-4000-8000-000000000001","childId":"00000000-0000-4000-8000-000000000002",
                 "deviceId":"00000000-0000-4000-8000-000000000003","activityType":"FREE_TALK",
                 "consentSnapshot":{"policyVersion":"1.0","voiceAndText":true,"guardianView":true,"reportSharing":true,"rawAudio":true},
                 "childContext":{"displayName":"하늘","age":8,"interests":["공룡","그림"]},
                 "stopExpressions":[{"text":"무중아 그만할래","isDefault":true}],
                 "approvedContent":{
                   "CLOSING":{"contentVersionId":"00000000-0000-4000-8000-000000000041","text":"오늘 이야기 즐거웠어. 다음에 또 만나!",
                     "audioUrl":"/media/approved/closing-v1.wav"},
                   "WAITING_FILLER":{"contentVersionId":"00000000-0000-4000-8000-000000000042","text":"음, 잠깐만 생각해 볼게.",
                     "audioUrl":"/media/approved/waiting-filler-v1.wav"}},
                 "practice":null%s}
                """.formatted(extra);
    }
}
