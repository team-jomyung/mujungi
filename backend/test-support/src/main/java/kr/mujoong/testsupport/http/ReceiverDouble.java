package kr.mujoong.testsupport.http;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.testsupport.http.ContractStubServer.RecordedRequest;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;

/**
 * 상대 앱 Inbox 수신기 대역 (U1 3.5 Step 28 · RD-8 · RD-23 · BR5.3 · OD-7).
 *
 * <p>{@code POST /internal/events}만 받는 {@link ContractStubServer}다. 받은 본문을 event-envelope.schema.json(아는 eventType의 payload 포함)
 * 으로 검사하고, 응답은 시험이 정한 순서(200 · 401 · 409 · 400 · 503 · 지연)대로 답한다. 기본은 200 {@code {}}다. 받은 봉투의
 * eventId · traceId · X-Trace-Id를 시험이 단언할 수 있게 기록한다.
 */
public final class ReceiverDouble implements AutoCloseable {

    /** 수신 경로. */
    public static final String PATH = "/internal/events";

    private final ContractStubServer server;
    private final ContractStubServer.Route route;

    private ReceiverDouble(ContractStubServer server) {
        this.server = server;
        this.route = server.route("POST", PATH).requestSchema("event-envelope.schema.json")
                .respond(StubResponse.json(200, "{}"));
    }

    /** 대역을 띄운다. */
    public static ReceiverDouble start() {
        return new ReceiverDouble(ContractStubServer.start());
    }

    /** 내부 토큰 확인을 켠다. */
    public ReceiverDouble requireInternalToken(String token) {
        server.requireInternalToken(token);
        return this;
    }

    /** 기준 주소. */
    public URI baseUrl() {
        return server.baseUrl();
    }

    /**
     * 응답 순서를 정한다(다 쓰면 마지막을 되풀이). 200은 {@code {}}, 400 · 401 · 409 · 503은 계약 오류 봉투다.
     */
    public ReceiverDouble respondWith(int... statuses) {
        StubResponse[] responses = new StubResponse[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            responses[i] = responseFor(statuses[i]);
        }
        route.respond(responses);
        return this;
    }

    /** 다음 응답들을 직접 정한다(지연 응답 등). */
    public ReceiverDouble respond(StubResponse... responses) {
        route.respond(responses);
        return this;
    }

    /** 상태 코드의 계약 응답. */
    public static StubResponse responseFor(int status) {
        return switch (status) {
            case 200 -> StubResponse.json(200, "{}");
            case 400 -> StubResponse.error(400, ErrorCode.INVALID_ENVELOPE);
            case 401 -> StubResponse.error(401, ErrorCode.UNAUTHENTICATED);
            case 409 -> StubResponse.error(409, ErrorCode.EVENT_CONFLICT);
            case 503 -> StubResponse.error(503, ErrorCode.SERVICE_NOT_READY);
            default -> StubResponse.error(status, ErrorCode.INTERNAL_ERROR);
        };
    }

    /** 받은 요청(도착 순서). */
    public List<RecordedRequest> deliveries() {
        return server.requests("POST", PATH);
    }

    /** 이 eventId로 받은 요청(재시도 포함). */
    public List<RecordedRequest> deliveriesOf(UUID eventId) {
        return deliveries().stream().filter(r -> eventId.toString().equals(r.json().path("eventId").asString())).toList();
    }

    /** {@code count}건 이상 받을 때까지 기다린다(상한 {@code timeout}). */
    public List<RecordedRequest> awaitDeliveries(int count, Duration timeout) {
        return server.awaitRequests("POST", PATH, count, timeout);
    }

    /** 스키마 위반 요청. */
    public List<RecordedRequest> violations() {
        return server.violations();
    }

    @Override
    public void close() {
        server.close();
    }
}
