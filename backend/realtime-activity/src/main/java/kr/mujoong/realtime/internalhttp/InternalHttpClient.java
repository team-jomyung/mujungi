package kr.mujoong.realtime.internalhttp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Realtime → Core 내부 HTTP 클라이언트 (U1 3.5 Step 26 · RD-14 · NFR4.12 · NFR4.15 · OD-3 · OD-5 · OD-7 · J109).
 *
 * <ul>
 *   <li>대상 그룹(토큰 확인 · 상태 보고 · 이벤트 발행)마다 JDK {@link HttpClient}를 따로 둔다(느린 보고가 토큰 확인을 굶기지 않게).
 *       HTTP/1.1, 프록시 없음(내부망 직접), 연결 제한 {@code mujoong.internal-http.connect-timeout}(0.5초).
 *   <li>응답 제한은 호출마다 받는다(호출 쪽이 설정 키에서 읽어 넘긴다). 재시도 · 차단기는 없다(토큰 확인은 기기가 재접속, 보고는 다음
 *       라운드, 발행은 Outbox 대기표, RD-14).
 *   <li>헤더는 {@link InternalAuthInterceptor} 한 곳이 붙인다.
 *   <li>관측: 모든 호출을 {@code mujoong_internal_http_*} 지표로 센다. INTERNAL_HTTP 줄은 실패는 모두(WARN · 병합 대상), 성공은
 *       VERIFY_TOKEN만 쓴다(OD-2 기록 규칙: device_status · event_publish 성공은 지표만).
 * </ul>
 *
 * <p>본문 · 응답 본문은 로그 · 예외 메시지에 넣지 않는다(BR5.13 · SD-11 · 기기 토큰 BR8.7).
 */
@Component
public class InternalHttpClient implements DisposableBean {

    /** 호출 대상(OD-2 target 열거 중 Realtime이 부르는 것). */
    public enum Target {
        VERIFY_TOKEN(Group.VERIFY, true, EventLog.Component.DEVICE_GATEWAY),
        DEVICE_STATUS(Group.STATUS, false, EventLog.Component.DEVICE_GATEWAY),
        EVENT_PUBLISH(Group.EVENTS, false, EventLog.Component.REALTIME_MESSAGING),
        PRACTICE_SCENARIO(Group.PRACTICE, false, EventLog.Component.SESSION_LIFECYCLE);

        private final Group group;
        private final boolean logSuccess;
        private final EventLog.Component component;

        Target(Group group, boolean logSuccess, EventLog.Component component) {
            this.group = group;
            this.logSuccess = logSuccess;
            this.component = component;
        }

        /** 연결 풀 그룹. */
        public Group group() {
            return group;
        }

        /** 성공도 INTERNAL_HTTP 한 줄을 남기는가(OD-2). */
        public boolean logsSuccess() {
            return logSuccess;
        }

        /** 로그 component. */
        public EventLog.Component component() {
            return component;
        }
    }

    /** 연결 풀 그룹(RD-14 분리 규칙). */
    public enum Group {
        VERIFY,
        STATUS,
        EVENTS,
        PRACTICE
    }

    /** 호출 결과 분류(OD-2 outcome 열거). */
    public enum Outcome {
        OK,
        TIMEOUT,
        CONN_ERROR,
        HTTP_4XX,
        HTTP_5XX;

        /** 상태 코드로 분류한다. */
        public static Outcome ofStatus(int status) {
            if (status >= 200 && status < 300) {
                return OK;
            }
            return status >= 500 ? HTTP_5XX : HTTP_4XX;
        }
    }

    private final URI baseUrl;
    private final InternalAuthInterceptor auth;
    private final EventLogger events;
    private final RealtimeMetrics metrics;
    private final MujoongClock clock;
    private final ExecutorService executor;
    private final Map<Group, HttpClient> clients = new EnumMap<>(Group.class);

    @Autowired
    public InternalHttpClient(
            RealtimeProperties properties,
            InternalAuthInterceptor auth,
            EventLogger events,
            RealtimeMetrics metrics,
            MujoongClock clock) {
        this(properties.internalHttp().coreBaseUrl(), properties.internalHttp().connectTimeout(), auth, events, metrics,
                clock);
    }

    public InternalHttpClient(
            URI baseUrl,
            Duration connectTimeout,
            InternalAuthInterceptor auth,
            EventLogger events,
            RealtimeMetrics metrics,
            MujoongClock clock) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        for (Group group : Group.values()) {
            clients.put(group, HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(connectTimeout)
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .executor(executor)
                    .build());
        }
    }

    /**
     * 한 번 호출한다.
     *
     * @return 상태 코드와 본문(2xx가 아니어도 응답이면 돌려준다)
     * @throws CallException 시간 초과 · 연결 실패(응답 없음)
     */
    public Response send(Target target, Call call) throws CallException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(call, "call");
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve(call.path()))
                .timeout(call.timeout())
                .header("Accept", "application/json");
        if (call.body() == null) {
            builder.method(call.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(call.method(), HttpRequest.BodyPublishers.ofString(call.body(), StandardCharsets.UTF_8));
        }
        HttpRequest request = auth.apply(builder, call.traceId()).build();
        long start = clock.monotonicNanos();
        try {
            HttpResponse<String> response = clients.get(target.group())
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Duration duration = clock.elapsedSince(start);
            Outcome outcome = Outcome.ofStatus(response.statusCode());
            record(target, outcome, response.statusCode(), duration, call);
            return new Response(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (HttpConnectTimeoutException e) {
            throw fail(target, Outcome.CONN_ERROR, start, call, e);
        } catch (HttpTimeoutException e) {
            throw fail(target, Outcome.TIMEOUT, start, call, e);
        } catch (IOException e) {
            throw fail(target, Outcome.CONN_ERROR, start, call, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail(target, Outcome.CONN_ERROR, start, call, e);
        }
    }

    private CallException fail(Target target, Outcome outcome, long start, Call call, Exception cause) {
        record(target, outcome, null, clock.elapsedSince(start), call);
        return new CallException(target, outcome, cause.getClass().getSimpleName());
    }

    private void record(Target target, Outcome outcome, Integer statusCode, Duration duration, Call call) {
        metrics.internalHttp(target.name(), outcome.name(), duration);
        if (outcome == Outcome.OK && !target.logsSuccess()) {
            return;
        }
        events.log(EventCode.INTERNAL_HTTP, target.component(), call.owner(), call.traceId(), entry -> {
            entry.attr(AttrKey.TARGET, target).attr(AttrKey.OUTCOME, outcome)
                    .attr(AttrKey.DURATION_MS, duration.toMillis())
                    .attr(AttrKey.ATTEMPT, (long) call.attempt());
            if (statusCode != null) {
                entry.attr(AttrKey.STATUS_CODE, (long) statusCode);
            }
        });
    }

    @Override
    public void destroy() {
        for (HttpClient client : clients.values()) {
            client.shutdownNow();
        }
        executor.shutdownNow();
    }

    /**
     * 호출 하나의 입력.
     *
     * @param path 기준 주소에 붙일 경로(예: {@code /internal/devices/{id}/connection})
     * @param body JSON 본문(없으면 null)
     * @param timeout 응답 제한
     * @param attempt 1부터(Outbox 발송은 retry_count + 1)
     * @param owner ownerHash의 소유 id(기기 · 이벤트 집합체, 없으면 null)
     * @param traceId 헤더 · 로그에 실을 traceId(연결 · 행 traceId, 없으면 null → MDC 또는 새 값)
     */
    public record Call(String method, String path, String body, Duration timeout, int attempt, UUID owner, String traceId) {

        public Call {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("method가 없다");
            }
            if (path == null || !path.startsWith("/")) {
                throw new IllegalArgumentException("path는 /로 시작해야 한다");
            }
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout은 0보다 커야 한다");
            }
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt는 1 이상이어야 한다");
            }
        }

        /** 본문은 문자열 표현에 내지 않는다(기기 토큰 · 이벤트 payload). */
        @Override
        public String toString() {
            return "Call[" + method + " " + path + ", attempt=" + attempt + "]";
        }
    }

    /** 받은 응답. 본문은 문자열 표현에 내지 않는다. */
    public record Response(int status, String body) {

        /** 2xx인가. */
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        @Override
        public String toString() {
            return "Response[status=" + status + "]";
        }
    }

    /** 응답을 받지 못한 호출(시간 초과 · 연결 실패). 메시지는 대상 · 분류 · 예외 종류만 담는다. */
    public static final class CallException extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient Target target;
        private final transient Outcome outcome;
        private final String errorKind;

        public CallException(Target target, Outcome outcome, String errorKind) {
            super(target + " " + outcome + " " + errorKind, null, false, false);
            this.target = target;
            this.outcome = outcome;
            this.errorKind = errorKind;
        }

        public Target target() {
            return target;
        }

        public Outcome outcome() {
            return outcome;
        }

        /** 예외 종류(클래스 단순 이름). Outbox last_error에 쓴다(BR5.13). */
        public String errorKind() {
            return errorKind;
        }
    }
}
