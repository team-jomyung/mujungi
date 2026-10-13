package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ApprovedContentCatalog.ApprovedItem;
import kr.mujoong.core.activity.RealtimeSessionClient.EndKind;
import kr.mujoong.core.activity.RealtimeSessionClient.StartKind;
import kr.mujoong.core.activity.RealtimeSessionClient.StartResult;
import kr.mujoong.core.activity.RealtimeSessionClient.ViewKind;
import kr.mujoong.core.activity.SessionPackageBuilder.SessionPackage;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.internalhttp.InternalHttpClient;
import kr.mujoong.core.internalhttp.InternalHttpClient.CallException;
import kr.mujoong.core.internalhttp.InternalHttpClient.Outcome;
import kr.mujoong.core.internalhttp.InternalHttpClient.Response;
import kr.mujoong.core.internalhttp.InternalHttpClient.Target;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Core → Realtime 호출 규칙 (RD-14 · NFR4.12 · R3-2 · J74 · BR6.6 · BR6.7 · BR6.9 · BR6.15). 호출별 제한 · 시간 초과만 1회 재시도 · 차단기
 * 열림 · 반열림(가변 Clock) · 409/423 성공 계수 · 열림 중에도 끝내기 · 조회 시도.
 */
@Tag("u1")
class RealtimeSessionClientTest {

    private static final Instant NOW = Instant.parse("2001-03-01T04:00:00Z");
    private static final Duration START = Duration.ofSeconds(3);
    private static final Duration END = Duration.ofSeconds(2);
    private static final Duration VIEW = Duration.ofSeconds(1);

    private final MutableClock clock = new MutableClock(NOW);
    private final CoreMetrics metrics = CoreMetrics.inMemory();
    private final InternalHttpClient http = mock(InternalHttpClient.class);
    private final SessionStartBreaker breaker = new SessionStartBreaker(clock, metrics, 3, Duration.ofSeconds(5));
    private final JsonCodec json = JsonCodec.shared();
    private final RealtimeSessionClient client = new RealtimeSessionClient(http, breaker, json, START, 1, END, VIEW);

    @Test
    void acceptedStartSendsPackageWithStartTimeout() throws Exception {
        SessionPackage sessionPackage = sessionPackage();
        when(http.send(eq(Target.SESSION_START), any())).thenReturn(new Response(202, "{\"status\":\"PREPARING\"}"));

        StartResult result = client.start(sessionPackage);

        assertThat(result.kind()).isEqualTo(StartKind.ACCEPTED);
        assertThat(result.status()).isEqualTo(SessionStatus.PREPARING);
        verify(http).send(eq(Target.SESSION_START), argThat(call -> call.path().equals("/internal/sessions")
                && call.timeout().equals(START) && call.attempt() == 1 && call.body().contains("\"isDefault\":true")
                && call.body().contains("\"sessionId\":\"" + sessionPackage.sessionId() + "\"")));
    }

    @Test
    void timeoutIsRetriedOnceWithSameSessionId() throws Exception {
        when(http.send(eq(Target.SESSION_START), any()))
                .thenThrow(new CallException(Target.SESSION_START, Outcome.TIMEOUT, "HttpTimeoutException"))
                .thenReturn(new Response(202, "{\"status\":\"PREPARING\"}"));

        StartResult result = client.start(sessionPackage());

        assertThat(result.kind()).isEqualTo(StartKind.ACCEPTED);
        verify(http).send(eq(Target.SESSION_START), argThat(call -> call.attempt() == 1));
        verify(http).send(eq(Target.SESSION_START), argThat(call -> call.attempt() == 2));
    }

    /** F3(J320): 시간 초과 뒤 재시도가 받은 409는 '재시도 뒤'로 표시되고, 첫 시도의 409 · 423은 표시되지 않는다(BR6.6 끝 문장). */
    @Test
    void conflictAnsweredToTheTimeoutRetryIsMarkedAsAfterTheRetry() throws Exception {
        when(http.send(eq(Target.SESSION_START), any()))
                .thenThrow(new CallException(Target.SESSION_START, Outcome.TIMEOUT, "HttpTimeoutException"))
                .thenReturn(new Response(409, "{\"code\":\"SESSION_IN_PROGRESS\",\"message\":\"x\"}"))
                .thenReturn(new Response(409, "{\"code\":\"SESSION_IN_PROGRESS\",\"message\":\"x\"}"))
                .thenReturn(new Response(423, "{\"code\":\"DEVICE_NOT_CONNECTED\",\"message\":\"x\"}"));

        StartResult retried = client.start(sessionPackage());
        StartResult direct = client.start(sessionPackage());
        StartResult deviceGone = client.start(sessionPackage());

        assertThat(retried.kind()).isEqualTo(StartKind.CONFLICT);
        assertThat(retried.afterTimeoutRetry()).isTrue();
        assertThat(direct.kind()).isEqualTo(StartKind.CONFLICT);
        assertThat(direct.afterTimeoutRetry()).isFalse();
        assertThat(deviceGone).isEqualTo(new StartResult(StartKind.DEVICE_NOT_CONNECTED, null, false));
        assertThat(breaker.state()).as("409 · 423은 업무 응답이라 차단기 실패가 아니다").isEqualTo(SessionStartBreaker.State.CLOSED);
    }

    @Test
    void secondTimeoutAndConnectionErrorAreUnavailableWithoutFurtherRetry() throws Exception {
        when(http.send(eq(Target.SESSION_START), any()))
                .thenThrow(new CallException(Target.SESSION_START, Outcome.TIMEOUT, "HttpTimeoutException"));
        assertThat(client.start(sessionPackage()).kind()).isEqualTo(StartKind.UNAVAILABLE);
        verify(http, times(2)).send(eq(Target.SESSION_START), any());

        InternalHttpClient refusing = mock(InternalHttpClient.class);
        when(refusing.send(eq(Target.SESSION_START), any()))
                .thenThrow(new CallException(Target.SESSION_START, Outcome.CONN_ERROR, "ConnectException"));
        RealtimeSessionClient other = new RealtimeSessionClient(refusing,
                new SessionStartBreaker(clock, metrics, 3, Duration.ofSeconds(5)), json, START, 1, END, VIEW);
        assertThat(other.start(sessionPackage()).kind()).isEqualTo(StartKind.UNAVAILABLE);
        verify(refusing, times(1)).send(eq(Target.SESSION_START), any());
    }

    @Test
    void businessRefusalsAreTranslatedAndCountAsBreakerSuccess() throws Exception {
        when(http.send(eq(Target.SESSION_START), any()))
                .thenReturn(new Response(503, "{}"), new Response(503, "{}"))
                .thenReturn(new Response(423, "{\"code\":\"DEVICE_NOT_CONNECTED\",\"message\":\"x\"}"))
                .thenReturn(new Response(503, "{}"), new Response(503, "{}"))
                .thenReturn(new Response(409, "{\"code\":\"SESSION_IN_PROGRESS\",\"message\":\"x\"}"));

        client.start(sessionPackage());
        client.start(sessionPackage());
        assertThat(client.start(sessionPackage()).kind()).isEqualTo(StartKind.DEVICE_NOT_CONNECTED);
        client.start(sessionPackage());
        client.start(sessionPackage());
        assertThat(client.start(sessionPackage()).kind()).isEqualTo(StartKind.CONFLICT);

        assertThat(breaker.state()).isEqualTo(SessionStartBreaker.State.CLOSED);
    }

    @Test
    void breakerOpensAfterThreeFailuresThenHalfOpensForOneTrial() throws Exception {
        when(http.send(eq(Target.SESSION_START), any())).thenReturn(new Response(500, "{}"));
        for (int i = 0; i < 3; i++) {
            assertThat(client.start(sessionPackage()).breakerOpen()).isFalse();
        }
        assertThat(breaker.state()).isEqualTo(SessionStartBreaker.State.OPEN);
        assertThat(metrics.breakerStateValue()).isEqualTo(2);

        StartResult rejected = client.start(sessionPackage());

        assertThat(rejected.kind()).isEqualTo(StartKind.UNAVAILABLE);
        assertThat(rejected.breakerOpen()).isTrue();
        verify(http, times(3)).send(eq(Target.SESSION_START), any());
        verify(http).recordBreakerOpen(any());

        clock.advance(Duration.ofMillis(4_999));
        assertThat(client.start(sessionPackage()).breakerOpen()).isTrue();
        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.acquire()).isEqualTo(SessionStartBreaker.Permit.TRIAL);
        assertThat(breaker.acquire()).isEqualTo(SessionStartBreaker.Permit.REJECTED);
        assertThat(metrics.breakerStateValue()).isEqualTo(1);
        breaker.onSuccess(SessionStartBreaker.Permit.TRIAL);
        assertThat(breaker.state()).isEqualTo(SessionStartBreaker.State.CLOSED);
    }

    /** METRIC_SNAPSHOT breakerOpenSec의 원천(J325): 열림마다 최대 열림 시간(5초)만 쌓고, 그 뒤 반열림 · 닫힘 시간은 세지 않는다. */
    @Test
    void breakerOpenTimeAddsUpOnlyTheOpenPeriods() throws Exception {
        assertThat(breaker.openTimeTotal()).isZero();
        when(http.send(eq(Target.SESSION_START), any())).thenReturn(new Response(500, "{}"));
        for (int i = 0; i < 3; i++) {
            client.start(sessionPackage());
        }

        clock.advance(Duration.ofSeconds(2));
        assertThat(breaker.openTimeTotal()).isEqualTo(Duration.ofSeconds(2));
        clock.advance(Duration.ofSeconds(10));
        assertThat(breaker.openTimeTotal()).isEqualTo(Duration.ofSeconds(5));

        client.start(sessionPackage());
        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.openTimeTotal()).as("시험 호출 실패로 다시 열린 1초").isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    void failedTrialReopensForAnotherFullPeriod() throws Exception {
        when(http.send(eq(Target.SESSION_START), any())).thenReturn(new Response(502, "{}"));
        for (int i = 0; i < 3; i++) {
            client.start(sessionPackage());
        }
        clock.advance(Duration.ofSeconds(5));

        assertThat(client.start(sessionPackage()).breakerOpen()).isFalse();
        assertThat(breaker.state()).isEqualTo(SessionStartBreaker.State.OPEN);
        clock.advance(Duration.ofMillis(4_999));
        assertThat(breaker.state()).isEqualTo(SessionStartBreaker.State.OPEN);
        verify(http, times(4)).send(eq(Target.SESSION_START), any());
    }

    @Test
    void endCancelAndViewStillCallWhileBreakerIsOpen() throws Exception {
        when(http.send(eq(Target.SESSION_START), any())).thenReturn(new Response(500, "{}"));
        for (int i = 0; i < 3; i++) {
            client.start(sessionPackage());
        }
        UUID sessionId = UUID.randomUUID();
        when(http.send(eq(Target.SESSION_END), any())).thenReturn(new Response(202, "{\"status\":\"CLOSING\"}"),
                new Response(202, "{}"), new Response(409, "{}"));
        when(http.send(eq(Target.SESSION_VIEW), any())).thenReturn(new Response(404, "{}"));

        assertThat(client.end(sessionId, UUID.randomUUID()).status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(client.cancel(sessionId)).isTrue();
        assertThat(client.end(sessionId, UUID.randomUUID()).kind()).isEqualTo(EndKind.NOT_ACTIVE);
        assertThat(client.view(sessionId, 0).kind()).isEqualTo(ViewKind.NOT_FOUND);
        verify(http, times(3)).send(eq(Target.SESSION_END), argThat(call -> call.timeout().equals(END) && call.attempt() == 1));
        verify(http).send(eq(Target.SESSION_VIEW), argThat(call -> call.timeout().equals(VIEW)
                && call.path().equals("/internal/sessions/" + sessionId + "/view?sinceSeq=0")));
        verify(http).send(eq(Target.SESSION_END), argThat(call -> call.body().contains("\"reason\":\"CORE_ABANDONED\"")
                && call.body().contains("\"requestId\":\"" + sessionId + "\"")));
    }

    @Test
    void cancelFailureThrowsOnlyInHandlerVariantAndViewParsesOrFallsBack() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(http.send(eq(Target.SESSION_END), any()))
                .thenThrow(new CallException(Target.SESSION_END, Outcome.TIMEOUT, "HttpTimeoutException"));
        when(http.send(eq(Target.SESSION_VIEW), any()))
                .thenReturn(new Response(200, """
                        {"sessionId":"%s","activityType":"FREE_TALK","status":"ACTIVE","confirmed":false,
                         "requestedAt":"2001-03-01T04:00:00Z","statusVersion":3,"serverTime":"2001-03-01T04:00:05Z",
                         "gaps":[],"items":[{"seq":1,"speaker":"CHILD","text":"t","final":true,"uncertain":false,
                         "startedAt":"2001-03-01T04:00:04Z"}],"nextSeq":1,"futureField":true}
                        """.formatted(sessionId)))
                .thenReturn(new Response(200, "not json"))
                .thenThrow(new CallException(Target.SESSION_VIEW, Outcome.TIMEOUT, "HttpTimeoutException"));

        assertThat(client.cancel(sessionId)).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.cancelOrThrow(sessionId))
                .isInstanceOf(RealtimeSessionClient.RealtimeCallFailedException.class);
        RealtimeSessionClient.ViewResult ok = client.view(sessionId, 0);
        assertThat(ok.kind()).isEqualTo(ViewKind.OK);
        assertThat(ok.view().statusVersion()).isEqualTo(3L);
        assertThat(ok.view().items()).hasSize(1);
        assertThat(ok.view().toString()).doesNotContain("\"t\"");
        assertThat(client.view(sessionId, 0).kind()).isEqualTo(ViewKind.UNAVAILABLE);
        assertThat(client.view(sessionId, 0).kind()).isEqualTo(ViewKind.UNAVAILABLE);
        verify(http, never()).recordBreakerOpen(any());
    }

    private SessionPackage sessionPackage() {
        // U4a: 빌더는 계약 8절 포트 값으로 조립한다(부르는 모양만 바뀜)
        ChildProfileService.ChildContext child = new ChildProfileService.ChildContext(UUID.randomUUID(), "하늘", 8,
                List.of("공룡", "그림"),
                List.of(new ChildProfileService.StopExpression(SessionPackageBuilder.DEFAULT_STOP_EXPRESSION, true)));
        Map<String, ApprovedItem> approved = new LinkedHashMap<>();
        approved.put("CLOSING",
                new ApprovedItem(UUID.randomUUID(), "오늘 이야기 즐거웠어. 다음에 또 만나!", "/media/approved/closing-v1.wav"));
        approved.put("WAITING_FILLER",
                new ApprovedItem(UUID.randomUUID(), "음, 잠깐만 생각해 볼게.", "/media/approved/waiting-filler-v1.wav"));
        return new SessionPackageBuilder().build(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                child, new ConsentPolicyService.Snapshot("1.0", true, true, true, true), approved);
    }
}
