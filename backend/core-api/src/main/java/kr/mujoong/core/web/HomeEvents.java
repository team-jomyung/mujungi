package kr.mujoong.core.web;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** PostgreSQL NOTIFY fans out committed invalidations to every Core instance, without sticky sessions.
 * Signals are best effort and contain no device/child/conversation content; GET /api/home remains authoritative.
 * A dropped listener closes streams so clients reconcile immediately and use their polling fallback. */
@Component
public class HomeEvents implements SmartLifecycle {
    static final String CHANNEL = "mujoong_home_changed";
    private static final long STREAM_MS = Duration.ofMinutes(2).toMillis();
    private static final long HEARTBEAT_NANOS = Duration.ofSeconds(10).toNanos();
    private final DataSource dataSource;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private volatile Connection listening;
    private Thread worker;

    public HomeEvents(DataSource dataSource) { this.dataSource = dataSource; }

    public SseEmitter subscribe(UUID guardianId) {
        SseEmitter emitter = new SseEmitter(STREAM_MS);
        Client client = new Client(guardianId, emitter);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> { clients.remove(client); emitter.complete(); });
        emitter.onError(error -> clients.remove(client));
        // Bound concurrent streams per identity (e.g. many open tabs).
        synchronized (clients) {
            if (clients.stream().filter(c -> c.guardianId().equals(guardianId)).count() >= 4) {
                // Keep existing tabs connected; evicting them would cause an EventSource reconnect loop.
                throw CoreApiException.of(ErrorCode.TOO_MANY_ATTEMPTS);
            }
            clients.add(client);
        }
        send(client, "ready"); // Reconcile after every connection; no event replay is required.
        return emitter;
    }

    void changed(UUID guardianId) {
        clients.stream().filter(c -> c.guardianId().equals(guardianId)).forEach(c -> send(c, "home-changed"));
    }

    private void send(Client client, String event) {
        try {
            synchronized (client) {
                client.emitter().send(SseEmitter.event().name(event).data("{}"));
            }
        } catch (IOException | IllegalStateException error) { close(client); }
    }

    private void close(Client client) {
        clients.remove(client);
        client.emitter().complete();
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        worker = Thread.ofVirtual().name("home-events-listener").start(this::listen);
    }

    private void listen() {
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                listening = connection;
                connection.setAutoCommit(true);
                try (var statement = connection.createStatement()) { statement.execute("LISTEN " + CHANNEL); }
                try {
                PGConnection pg = connection.unwrap(PGConnection.class);
                clients.forEach(c -> send(c, "ready"));
                long heartbeat = System.nanoTime();
                while (running) {
                    PGNotification[] notices = pg.getNotifications(1000);
                    if (notices != null) for (PGNotification notice : notices) {
                        if (!CHANNEL.equals(notice.getName())) continue;
                        try { changed(UUID.fromString(notice.getParameter())); }
                        catch (IllegalArgumentException ignored) { /* Never route malformed identity hints. */ }
                    }
                    if (System.nanoTime() - heartbeat >= HEARTBEAT_NANOS) {
                        clients.forEach(c -> send(c, "heartbeat"));
                        heartbeat = System.nanoTime();
                    }
                }
                } finally {
                    // A pooled JDBC connection must not return with LISTEN subscriptions attached.
                    if (!connection.isClosed()) try (var statement = connection.createStatement()) {
                        statement.execute("UNLISTEN *");
                    }
                }
            } catch (SQLException error) {
                // NOTIFY has no durable replay. Force resync instead of keeping apparently healthy SSE streams.
                clients.forEach(this::close);
                if (running) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            } finally { listening = null; }
        }
    }

    @Override public synchronized void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        Connection connection = listening;
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
        clients.forEach(this::close);
    }

    @Override public boolean isRunning() { return running; }
    private record Client(UUID guardianId, SseEmitter emitter) { }
}
