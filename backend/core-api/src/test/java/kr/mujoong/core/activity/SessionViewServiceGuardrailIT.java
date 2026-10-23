package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.guardrail.GuardrailPresentation;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.internalhttp.*;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("service-guardrail")
class SessionViewServiceGuardrailIT {
    @Test void realInternalHttpParsingAndCoreFallbackDoNotReusePlaying() throws Exception {
        var clock = new MutableClock(); var events = new TestEvents(clock);
        UUID session = UUID.randomUUID(), guardian = UUID.randomUUID();
        var failed = new AtomicBoolean(); var requests = new AtomicInteger();
        String token = "test-internal-token-service-guardrail-000000000000";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/sessions/" + session + "/view", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Token")).isEqualTo(token);
            var body = new LinkedHashMap<String,Object>();
            body.put("sessionId", session); body.put("activityType", "FREE_TALK"); body.put("status", "CLOSING");
            body.put("confirmed", false); body.put("requestedAt", clock.now()); body.put("statusVersion", 5);
            body.put("serverTime", clock.now()); body.put("items", List.of()); body.put("nextSeq", 0); body.put("gaps", List.of());
            var facts = new LinkedHashMap<String,Object>(); facts.put("schemaVersion", 1); facts.put("notice", "COMPLETED");
            facts.put("participation", "ACCEPTED"); facts.put("goodbye", "PLAYING"); facts.put("confirmedStopBubbleSeq", null);
            body.put("guardrail", GuardrailPresentation.parse(facts));
            byte[] bytes = JsonCodec.shared().write(body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(failed.get() ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var http = new InternalHttpClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofMillis(500),
                new InternalAuthInterceptor(token), events.logger(), events.metrics(), clock);
        try {
            var response = new java.util.concurrent.atomic.AtomicReference<RealtimeSessionClient.ViewResult>();
            var realtime = new RealtimeSessionClient(http, new SessionStartBreaker(clock, events.metrics(), 3, Duration.ofSeconds(5)),
                    JsonCodec.shared(), Duration.ofSeconds(3), 1, Duration.ofSeconds(2), Duration.ofSeconds(1)) {
                @Override public ViewResult view(UUID id, long since) {
                    var result = super.view(id, since); response.set(result); return result;
                }
            };
            var copies = mock(ActivityCopyRepository.class);
            when(copies.findOwned(session, guardian)).thenReturn(Optional.of(ActivityCopies.copy(session, guardian, SessionStatus.CLOSING, 5, clock.now())));
            var service = new SessionViewService(copies, realtime, new StaleEvaluator(Duration.ofSeconds(30), events.metrics(), events.logger()), events.metrics(), clock);
            var first = service.view(guardian, session, 0);
            assertThat(response.get().kind()).isEqualTo(RealtimeSessionClient.ViewKind.OK);
            assertThat(response.get().view().guardrail()).containsKeys("schemaVersion", "notice", "participation", "goodbye", "confirmedStopBubbleSeq");
            assertThat(first.guardrail()).isNotNull();
            assertThat(first.guardrail().goodbye()).isEqualTo(GuardrailPresentation.Goodbye.PLAYING);
            failed.set(true);
            var fallback = service.view(guardian, session, 0);
            assertThat(fallback.stale()).isFalse(); assertThat(fallback.guardrail()).isNull();
            assertThat(requests.get()).isEqualTo(2);
        } finally { http.destroy(); server.stop(0); }
    }
}
