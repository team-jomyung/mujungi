package kr.mujoong.realtime.session.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.realtime.config.RealtimeLimitsProperties;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.dialog.SilenceClock;
import kr.mujoong.realtime.session.ActivitySessionRow;
import kr.mujoong.realtime.session.EndRequestHandler;
import kr.mujoong.realtime.session.IdleClock;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import kr.mujoong.realtime.session.SessionViewQuery;
import kr.mujoong.realtime.session.StartSessionHandler;
import kr.mujoong.realtime.testing.InMemoryTransitions;
import kr.mujoong.realtime.web.InternalBody;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;

/**
 * 내부 세션 API의 U4b 몫 (FR1.3 · FR1.4 · FR2.4 · FR6.1 · BR1.5 · BR3.3 · BR7.1 · 편차 (U4b-b)).
 *
 * <p>시작 본문이 참여 확인 · 확인 질문 · 공백 안내 승인 문구(INTRO_FREE_TALK · RE_PROMPT · DECLINE_ALTERNATIVE · STOP_CONFIRM ·
 * SILENCE_WARNING)를 코드 · 버전 · 원문 · 음성 주소 그대로 실행기 명령으로 넘기고 스키마 규칙 밖 값은 400으로 거절하는지, 끝내기가
 * CONSENT_WITHDRAWN을 받아 실행기 결과대로 202 {status: CLOSING} · 202 {} · 409로 답하는지, LiveView 선 형식이 실행기의 살아 있는 값
 * (technicalPause · silenceWarning)을 싣는지 본다.
 */
@Tag("session-runtime")
@org.junit.jupiter.api.Tag("service-guardrail")
class SessionInternalControllerSessionRuntimeTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final List<String> U4B_CODES = List.of("INTRO_FREE_TALK", "RE_PROMPT", "DECLINE_ALTERNATIVE",
            "STOP_CONFIRM", "SILENCE_WARNING");

    private final StartSessionHandler starts = mock(StartSessionHandler.class);
    private final EndRequestHandler ends = mock(EndRequestHandler.class);
    private final SessionViewQuery views = mock(SessionViewQuery.class);

    @Test
    void theStartBodyCarriesTheU4bApprovedPromptsIntoTheCommandUnchanged() {
        Map<String, SessionInternalController.ApprovedBody> approved = approvedWith(U4B_CODES);

        StartSessionHandler.StartCommand command = SessionInternalController.toCommand(body(ActivityType.FREE_TALK,
                approved, child("하늘", 8), List.of(new SessionInternalController.StopBody("무중아 그만할래", true))));

        assertThat(command.activityType()).isEqualTo(ActivityType.FREE_TALK);
        assertThat(command.approvedContent()).containsKeys(U4B_CODES.toArray(String[]::new))
                .containsKeys("CLOSING", "WAITING_FILLER");
        StartSessionHandler.ApprovedContentItem intro = command.approvedContent().get("INTRO_FREE_TALK");
        assertThat(intro.text()).isEqualTo("INTRO_FREE_TALK 문구");
        assertThat(intro.audioUrl()).isEqualTo("/media/approved/intro_free_talk-v2.wav");
        assertThat(intro.contentVersionId()).isEqualTo(approved.get("INTRO_FREE_TALK").contentVersionId());
        assertThat(command.stopExpressions()).singleElement()
                .isEqualTo(new StartSessionHandler.StopExpressionItem("무중아 그만할래", true));
        assertThat(command.consentSnapshot().rawAudio()).isFalse();
        assertThat(command.childContext().age()).isEqualTo(8);
    }

    @Test
    void practiceIsOpenedAtTheControllerByU12AndAnUnrecognizedActivityTypeStaysRejected() {
        // U12(BR1.1): 연습 시작을 받는다(자세한 본문 · 조각 시험은 PracticeStartBodyTest · PracticeStartPathIT)
        StartSessionHandler.StartCommand command = SessionInternalController.toCommand(
                body(ActivityType.PRACTICE, approvedWith(U4B_CODES), child("하늘", 8), stops()));
        assertThat(command.activityType()).isEqualTo(ActivityType.PRACTICE);
        assertThat(command.practiceJson()).isNull();
        assertBadRequest(body(ActivityType.UNRECOGNIZED, approvedWith(U4B_CODES), child("하늘", 8), stops()));
    }

    @Test
    void approvedContentOutsideTheSchemaRulesIsRejected() {
        Map<String, SessionInternalController.ApprovedBody> withoutClosing = approvedWith(List.of());
        withoutClosing.remove("CLOSING");
        assertBadRequest(body(ActivityType.FREE_TALK, withoutClosing, child("하늘", 8), stops()));

        Map<String, SessionInternalController.ApprovedBody> lowerCode = approvedWith(List.of());
        lowerCode.put("intro", item("intro"));
        assertBadRequest(body(ActivityType.FREE_TALK, lowerCode, child("하늘", 8), stops()));

        Map<String, SessionInternalController.ApprovedBody> badAudio = approvedWith(List.of());
        badAudio.put("RE_PROMPT", new SessionInternalController.ApprovedBody(UUID.randomUUID(), "다시 물어볼게",
                "/media/other/re.wav"));
        assertBadRequest(body(ActivityType.FREE_TALK, badAudio, child("하늘", 8), stops()));

        Map<String, SessionInternalController.ApprovedBody> longText = approvedWith(List.of());
        longText.put("SILENCE_WARNING", new SessionInternalController.ApprovedBody(UUID.randomUUID(), "가".repeat(201),
                "/media/approved/silence.wav"));
        assertBadRequest(body(ActivityType.FREE_TALK, longText, child("하늘", 8), stops()));
    }

    @Test
    void childContextConsentAndStopExpressionsFollowTheSchemaBounds() {
        assertThat(SessionInternalController.toCommand(body(ActivityType.FREE_TALK, approvedWith(U4B_CODES), child("하늘", 13), stops())).childContext().age()).isEqualTo(13);
        assertBadRequest(body(ActivityType.FREE_TALK, approvedWith(List.of()), child("하늘", 5), stops()));
        assertBadRequest(body(ActivityType.FREE_TALK, approvedWith(List.of()), child(" ", 8), stops()));
        assertBadRequest(body(ActivityType.FREE_TALK, approvedWith(List.of()), child("하늘", 8), List.of()));
        assertBadRequest(body(ActivityType.FREE_TALK, approvedWith(List.of()), child("하늘", 8),
                List.of(new SessionInternalController.StopBody("그만", null))));
        assertBadRequest(new SessionInternalController.StartBody(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ActivityType.FREE_TALK,
                new SessionInternalController.ConsentBody("2026-10", true, true, null, false), child("하늘", 8),
                stops(), approvedWith(List.of())));
    }

    @Test
    void endAcceptsConsentWithdrawnAndAnswersWhatTheExecutorDecided() {
        SessionInternalController controller = controller();
        UUID session = UUID.randomUUID();
        when(ends.end(eq(session), any(), eq(EndRequestReason.CONSENT_WITHDRAWN), any())).thenReturn(EndOutcome.CLOSING);

        ResponseEntity<String> closing = controller.end(session.toString(), endRequest("CONSENT_WITHDRAWN"));

        assertThat(closing.getStatusCode().value()).isEqualTo(202);
        assertThat(closing.getBody()).isEqualTo("{\"status\":\"CLOSING\"}");

        UUID starting = UUID.randomUUID();
        when(ends.end(eq(starting), any(), eq(EndRequestReason.CONSENT_WITHDRAWN), any()))
                .thenReturn(EndOutcome.ACCEPTED_EMPTY);
        ResponseEntity<String> empty = controller.end(starting.toString(), endRequest("CONSENT_WITHDRAWN"));
        assertThat(empty.getStatusCode().value()).isEqualTo(202);
        assertThat(empty.getBody()).isEqualTo("{}");

        UUID ended = UUID.randomUUID();
        when(ends.end(eq(ended), any(), eq(EndRequestReason.CONSENT_WITHDRAWN), any()))
                .thenReturn(EndOutcome.NOT_ACTIVE);
        ResponseEntity<String> notActive = controller.end(ended.toString(), endRequest("CONSENT_WITHDRAWN"));
        assertThat(notActive.getStatusCode().value()).isEqualTo(409);
        assertThat(notActive.getBody()).contains("SESSION_NOT_ACTIVE");
    }

    @Test
    void anUnrecognizedReasonIsRejectedBeforeTheHandler() {
        SessionInternalController controller = controller();
        UUID session = UUID.randomUUID();

        assertThatThrownBy(() -> controller.end(session.toString(), endRequest("SOMETHING_ELSE")))
                .isInstanceOf(InternalBody.BadRequest.class);
        verify(ends, never()).end(any(), any(), any(), any());
    }

    @Test
    void theLiveViewWiresThePauseAndTheSilenceWarningAndAnUnknownSessionIs404() {
        MutableClock clock = new MutableClock(T0);
        ActivitySessionRow row = new InMemoryTransitions(clock).seed(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        IdleClock.LiveState live = new IdleClock.LiveState(
                new SessionStateChanged.TechnicalPause(SessionStateChanged.TechnicalPause.Kind.DEVICE, T0),
                new SilenceClock.Warning(T0.minusSeconds(240), T0.plusSeconds(120)));
        when(views.view(eq(row.id()), anyInt()))
                .thenReturn(Optional.of(new SessionViewQuery.View(row, T0, List.of(), 0, List.of(), live)));
        SessionInternalController controller = controller();

        ResponseEntity<String> found = controller.view(row.id().toString(), "3", new MockHttpServletRequest());
        ResponseEntity<String> missing = controller.view(UUID.randomUUID().toString(), null,
                new MockHttpServletRequest());

        assertThat(found.getStatusCode().value()).isEqualTo(200);
        assertThat(found.getBody()).contains("\"technicalPause\":{\"kind\":\"DEVICE\"")
                .contains("\"silenceWarning\":{\"silentSince\"");
        verify(views).view(row.id(), 3);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThatThrownBy(() -> controller.view(row.id().toString(), "-1", new MockHttpServletRequest()))
                .isInstanceOf(InternalBody.BadRequest.class);

        Map<String, Object> quiet = SessionInternalController.wire(new SessionViewQuery.View(row, T0, List.of(), 0,
                List.of()));
        assertThat(quiet).containsEntry("technicalPause", null).containsEntry("silenceWarning", null);
    }

    // ------------------------------------------------------------------------------------------------ 도우미

    private SessionInternalController controller() {
        RealtimeProperties properties = mock(RealtimeProperties.class);
        RealtimeLimitsProperties limits = mock(RealtimeLimitsProperties.class);
        when(properties.limits()).thenReturn(limits);
        when(limits.internalEventBodyMax()).thenReturn(DataSize.ofKilobytes(64));
        return new SessionInternalController(starts, ends, views, JsonCodec.shared(), properties);
    }

    private static MockHttpServletRequest endRequest(String reason) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/sessions/x/end");
        request.setContentType("application/json");
        request.setContent(("{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"" + reason + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static void assertBadRequest(SessionInternalController.StartBody body) {
        assertThatThrownBy(() -> SessionInternalController.toCommand(body)).isInstanceOf(InternalBody.BadRequest.class);
    }

    private static SessionInternalController.StartBody body(ActivityType type,
            Map<String, SessionInternalController.ApprovedBody> approved, SessionInternalController.ChildBody child,
            List<SessionInternalController.StopBody> stops) {
        return new SessionInternalController.StartBody(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), type,
                new SessionInternalController.ConsentBody("2026-10", true, true, true, false), child, stops,
                approved);
    }

    private static SessionInternalController.ChildBody child(String name, int age) {
        return new SessionInternalController.ChildBody(name, age, List.of("공룡"));
    }

    private static List<SessionInternalController.StopBody> stops() {
        return List.of(new SessionInternalController.StopBody("무중아 그만할래", true));
    }

    private static Map<String, SessionInternalController.ApprovedBody> approvedWith(List<String> codes) {
        Map<String, SessionInternalController.ApprovedBody> approved = new LinkedHashMap<>();
        approved.put("CLOSING", item("CLOSING"));
        approved.put("WAITING_FILLER", item("WAITING_FILLER"));
        for (String code : codes) {
            approved.put(code, item(code));
        }
        return approved;
    }

    private static SessionInternalController.ApprovedBody item(String code) {
        return new SessionInternalController.ApprovedBody(UUID.randomUUID(), code + " 문구",
                "/media/approved/" + code.toLowerCase(java.util.Locale.ROOT) + "-v2.wav");
    }
}
