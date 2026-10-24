package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
class HomeEventsIT {
    @Test
    void authenticatedSseRoutesOnlyToItsGuardianAndHasNoPrivatePayload() throws Exception {
        try (DbTestSupport db = DbTestSupport.coreApp()) {
            BusinessFixtures.applyReferenceSeed(db.postgres());
            String token = TestSecrets.internalServiceToken();
            try (ContractStubServer realtime = WebFixtures.realtime(token);
                    CoreWebApp app = CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).start()) {
                UUID owner = db.insertGuardian("ACTIVE"), other = db.insertGuardian("ACTIVE");
                String ownerSession = app.sessionCookieFor(owner), otherSession = app.sessionCookieFor(other);
                try {
                    assertThat(app.get("/api/home/events").send().status()).isEqualTo(401);
                    try (Stream a = stream(app, ownerSession); Stream b = stream(app, otherSession)) {
                        assertThat(a.lines.poll(5, TimeUnit.SECONDS)).isEqualTo("event:ready");
                        assertThat(b.lines.poll(5, TimeUnit.SECONDS)).isEqualTo("event:ready");
                        app.bean(HomeEvents.class).changed(owner);
                        assertThat(a.lines.poll(5, TimeUnit.SECONDS)).isEqualTo("event:home-changed");
                        assertThat(b.lines.poll(200, TimeUnit.MILLISECONDS)).isNull();
                        assertThat(a.data.poll(5, TimeUnit.SECONDS)).isEqualTo("data:{}");
                        // Heartbeats follow the injected monotonic clock, not wall-clock sleep.
                        app.clock().advance(Duration.ofSeconds(10));
                        assertThat(a.lines.poll(5, TimeUnit.SECONDS)).isEqualTo("event:heartbeat");
                        assertThat(b.lines.poll(5, TimeUnit.SECONDS)).isEqualTo("event:heartbeat");
                        try (Stream c = stream(app, ownerSession); Stream d = stream(app, ownerSession);
                                Stream e = stream(app, ownerSession)) {
                            assertThat(app.get("/api/home/events").session(ownerSession).send().status()).isEqualTo(429);
                            app.bean(HomeEvents.class).changed(owner);
                            assertThat(a.lines.poll(5, TimeUnit.SECONDS)).isEqualTo("event:home-changed");
                        }
                    }
                } finally { WebFixtures.deleteSessions(db, owner, other); }
            }
        }
    }

    private static Stream stream(CoreWebApp app, String session) throws Exception {
        var request = HttpRequest.newBuilder(app.baseUrl().resolve("/api/home/events"))
                .timeout(Duration.ofSeconds(10)).header("Cookie", "MUJOONG_SESSION=" + session)
                .header("Accept", "text/event-stream").build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        return new Stream(response.body());
    }

    private static final class Stream implements AutoCloseable {
        final java.io.InputStream input;
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> data = new LinkedBlockingQueue<>();
        Stream(java.io.InputStream input) {
            this.input = input;
            Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event:")) lines.add(line);
                        if (line.startsWith("data:")) data.add(line);
                    }
                } catch (java.io.IOException ignored) { }
            });
        }
        @Override public void close() throws java.io.IOException { input.close(); }
    }
}
