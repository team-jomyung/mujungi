package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.luna.LunaResponsesStub.Reply;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 나가는 길 하나 (U9 SD3 · NFR3.6 · NFR3.9 · NFR3.10). 공급자는 루프백 대역뿐이다(R-F1). */
@Tag("luna")
class LunaTransportTest {

    private static final String BODY = "{\"model\":\"gpt-6-luna\",\"instructions\":\"i\",\"input\":\"{}\",\"store\":false}";

    private static LunaException failure(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(thrown.getCause()).isInstanceOf(LunaException.class);
        return (LunaException) thrown.getCause();
    }

    @Test
    void sendsToResponsesWithTheKeyHeaderAndReturnsStatusAndBody() throws Exception {
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            stub.respond(Reply.ok("gpt-6-luna", "{}"));
            LunaTransport transport = LunaTestFixtures.transport(URI.create(stub.baseUrl() + "/"), LunaHosts.Mode.TEST);

            LunaTransport.RawResponse response = transport.send(BODY, Duration.ofSeconds(5)).get();

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"completed\"");
            assertThat(response.toString()).doesNotContain("completed");
            assertThat(transport.endpoint().getPath()).isEqualTo("/v1/responses");
            assertThat(stub.requests().getFirst().header("authorization")).isEqualTo("Bearer " + LunaTestFixtures.FAKE_KEY);
        }
    }

    @Test
    void redirectsAreNotFollowedAndNoRequestGoesToTheOtherHost() throws Exception {
        try (LunaResponsesStub stub = LunaResponsesStub.start(); LunaResponsesStub other = LunaResponsesStub.start()) {
            stub.respond(Reply.redirect(other.baseUrl() + "/responses"));
            LunaTransport transport = LunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST);

            LunaTransport.RawResponse response = transport.send(BODY, Duration.ofSeconds(5)).get();

            assertThat(response.status()).as("3xx는 호출부가 REQUEST_REJECTED로 분류").isEqualTo(302);
            assertThat(other.requestCount()).isZero();
            assertThat(stub.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void aBodyOverTheByteLimitIsCutAsLengthExceeded() {
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            stub.respond(Reply.oversized("gpt-6-luna", 1_100_000));
            LunaTransport transport = LunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST);

            assertThatThrownBy(() -> transport.send(BODY, Duration.ofSeconds(10)).get())
                    .satisfies(thrown -> assertThat(failure(thrown).reason()).isEqualTo(LunaFailureReason.LENGTH_EXCEEDED));
        }
    }

    @Test
    void aSlowlyDrippedBodyTimesOutAtTheAttemptLimit() {
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            stub.respond(Reply.ok("gpt-6-luna", "{}").dripping(Duration.ofMillis(50)));
            LunaTransport transport = LunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST);
            long started = System.nanoTime();

            assertThatThrownBy(() -> transport.send(BODY, Duration.ofMillis(400)).get())
                    .satisfies(thrown -> assertThat(failure(thrown).reason()).isEqualTo(LunaFailureReason.TIMEOUT));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("본문 끝까지 기다리지 않음").isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void addressesOutsideTheRuleBlankKeysAndZeroLimitsAreRefusedBeforeSending() {
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            LunaTransport strict = LunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.STRICT);
            LunaTransport noKey = new LunaTransport(stub.baseUrl(), LunaHosts.Mode.TEST, () -> " ", 1000);
            LunaTransport ok = LunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST);

            assertThatThrownBy(() -> strict.send(BODY, Duration.ofSeconds(1)).get())
                    .satisfies(thrown -> assertThat(failure(thrown).reason()).isEqualTo(LunaFailureReason.REQUEST_REJECTED));
            assertThatThrownBy(() -> noKey.send(BODY, Duration.ofSeconds(1)).get())
                    .satisfies(thrown -> assertThat(failure(thrown).reason()).isEqualTo(LunaFailureReason.AUTH_FAILED));
            assertThatThrownBy(() -> ok.send(BODY, Duration.ZERO).get())
                    .satisfies(thrown -> assertThat(failure(thrown).reason()).isEqualTo(LunaFailureReason.TIMEOUT));
            assertThat(stub.requestCount()).isZero();
            assertThatThrownBy(() -> new LunaTransport(stub.baseUrl(), LunaHosts.Mode.TEST, () -> "k", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void thePreSendCheckNamesTheRefusalWithoutSendingAndCloseShutsTheClient() {
        try (LunaResponsesStub stub = LunaResponsesStub.start()) {
            LunaTransport strict = LunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.STRICT);
            LunaTransport noKey = new LunaTransport(stub.baseUrl(), LunaHosts.Mode.TEST, () -> null, 1000);
            LunaTransport ok = LunaTestFixtures.transport(stub.baseUrl(), LunaHosts.Mode.TEST);

            assertThat(strict.rejectBeforeSend(Duration.ofSeconds(1))).contains(LunaFailureReason.REQUEST_REJECTED);
            assertThat(noKey.rejectBeforeSend(Duration.ofSeconds(1))).contains(LunaFailureReason.AUTH_FAILED);
            assertThat(ok.rejectBeforeSend(Duration.ZERO)).contains(LunaFailureReason.TIMEOUT);
            assertThat(ok.rejectBeforeSend(null)).contains(LunaFailureReason.TIMEOUT);
            assertThat(ok.rejectBeforeSend(Duration.ofSeconds(1))).isEmpty();
            assertThat(stub.requestCount()).isZero();

            assertThat(ok.isClosed()).isFalse();
            ok.close();
            assertThat(ok.isClosed()).as("검토 R-06: 공유 HttpClient가 닫힌다").isTrue();
            strict.close();
            noKey.close();
        }
    }

    @Test
    void aConnectionFailureIsATransportErrorAndNoMessageCarriesTheKey() {
        LunaTransport transport = LunaTestFixtures.transport(URI.create("http://127.0.0.1:9/v1"), LunaHosts.Mode.TEST);

        assertThatThrownBy(() -> transport.send(BODY, Duration.ofSeconds(5)).get())
                .satisfies(thrown -> {
                    LunaException failure = failure(thrown);
                    assertThat(failure.reason()).isEqualTo(LunaFailureReason.TRANSPORT_ERROR);
                    assertThat(failure.isTransient()).isTrue();
                    assertThat(failure.getMessage()).isEqualTo("Luna failure: TRANSPORT_ERROR")
                            .doesNotContain(LunaTestFixtures.FAKE_KEY);
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void retryAfterAcceptsOnlyWholeSeconds() {
        assertThat(LunaTransport.retryAfter(headers("Retry-After", "7"))).contains(Duration.ofSeconds(7));
        assertThat(LunaTransport.retryAfter(headers("Retry-After", " 12 "))).contains(Duration.ofSeconds(12));
        assertThat(LunaTransport.retryAfter(headers("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"))).isEmpty();
        assertThat(LunaTransport.retryAfter(headers("X-Other", "1"))).isEmpty();
        assertThat(LunaTransport.translate(new IllegalStateException("x")).reason()).isEqualTo(LunaFailureReason.TRANSPORT_ERROR);
        assertThat(LunaTransport.translate(new java.util.concurrent.CancellationException()).reason())
                .isEqualTo(LunaFailureReason.TIMEOUT);
    }

    private static HttpHeaders headers(String name, String value) {
        return HttpHeaders.of(Map.of(name, List.of(value)), (a, b) -> true);
    }
}
