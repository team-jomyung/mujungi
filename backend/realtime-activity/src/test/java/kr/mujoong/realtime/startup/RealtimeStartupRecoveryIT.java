package kr.mujoong.realtime.startup;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.mujoong.contract.event.EventEnvelope;
import kr.mujoong.contract.event.payload.DeletionCompleted;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.gateway.DeviceConnectionRepository;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.messaging.InboxEventRow;
import kr.mujoong.realtime.messaging.NewInboxEvent;
import kr.mujoong.realtime.messaging.NewOutboxEvent;
import kr.mujoong.realtime.messaging.OutboxEventRow;
import kr.mujoong.realtime.messaging.RealtimeInboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxRepository;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ActivitySessionRow;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.session.SessionChange;
import kr.mujoong.realtime.session.StartSessionHandler;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.testing.RealtimeTestApp;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.http.ContractStubServer.StubResponse;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.provider.FakeProviderWsServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Realtime 기동 복구 통합 시험 (U1 3.5 Step 30 · BR6.13 · RD-12 S3a ~ S3f · S6 · A1 · RD-3 · J126).
 *
 * <p>앱을 띄우기 전에 DB에 비종료 세션 · CONNECTED 연결 행 · 미뤄 둔 Inbox 행 · 선점된 Outbox 행 · FAILED 제어 이벤트를 넣어 두고 기동한다.
 * 복구 뒤 상태와 STARTUP_RECOVERY 한 줄, 복구가 끝나기 전에는 readiness가 UP이 아님을 본다. 기동 점검 거부는 웹 서버가 뜨기 전에 끝난다.
 * 실행기 없는 비종료 세션은 고아 세션 점검이 FAILED(SERVER_RESTART)로 한 번 닫고 같은 보호자 · 기기의 새 시작이 성공한다.
 */
@Tag("u1")
@Tag("db")
class RealtimeStartupRecoveryIT {

    private static final JsonCodec JSON = JsonCodec.shared();

    private RealtimeDb db;
    private ContractStubServer core;
    private FakeProviderWsServer provider;
    private RealtimeTestApp app;
    private final String internalToken = TestSecrets.internalServiceToken();

    @BeforeEach
    void open() {
        db = RealtimeDb.open();
        db.wipe();
        core = ContractStubServer.start().requireInternalToken(internalToken);
        core.route("POST", "/internal/events").respond(StubResponse.json(200, "{}"));
        core.route("PUT", "/internal/devices/{deviceId}/connection").respond(StubResponse.json(200, "{}"));
        provider = FakeProviderWsServer.start();
    }

    @AfterEach
    void close() {
        try {
            if (app != null) {
                app.close();
            }
        } finally {
            provider.close();
            core.close();
            db.wipe();
            db.close();
        }
    }

    @Test
    void startupRecoversEveryLeftoverAndLogsOneRecoveryLineBeforeBecomingReady() {
        Instant now = Instant.now();
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        UUID preparing = seedSession(sessions, now, SessionStatus.PREPARING);
        UUID active = seedSession(sessions, now, SessionStatus.ACTIVE);
        UUID closing = seedSession(sessions, now, SessionStatus.CLOSING);
        UUID ended = seedSession(sessions, now, SessionStatus.ENDED);
        UUID deviceId = UUID.randomUUID();
        UUID guardianId = UUID.randomUUID();
        DeviceConnectionRepository connections = new DeviceConnectionRepository(db.jdbc());
        db.inTransaction(() -> connections.handover(deviceId, UUID.randomUUID(), guardianId, UUID.randomUUID(), now));
        RealtimeInboxRepository inbox = new RealtimeInboxRepository(db.jdbc());
        UUID inboxEvent = UUID.randomUUID();
        EventEnvelope released = JSON.envelopeFor(inboxEvent,
                new DeviceReleased(deviceId, guardianId, DeviceReleased.Reason.REREGISTERED), now, null);
        db.inTransaction(() -> inbox.insertIfAbsent(NewInboxEvent.received(released, guardianId, now)));
        db.jdbc().update("update realtime.realtime_inbox_event set next_attempt_at = :later where event_id = :id",
                new MapSqlParameterSource("later", java.sql.Timestamp.from(now.plus(Duration.ofHours(1))))
                        .addValue("id", inboxEvent));
        RealtimeOutboxRepository outbox = new RealtimeOutboxRepository(db.jdbc());
        UUID failedControl = UUID.randomUUID();
        db.inTransaction(() -> outbox.insert(NewOutboxEvent.of(JSON.envelopeFor(failedControl,
                new DeletionCompleted(UUID.randomUUID(), guardianId, now, null), now, null), guardianId), now));
        outbox.markFailed(failedControl, "503");
        UUID claimed = UUID.randomUUID();
        db.inTransaction(() -> outbox.insert(NewOutboxEvent.of(JSON.envelopeFor(claimed,
                new DeletionCompleted(UUID.randomUUID(), guardianId, now, null), now, null), guardianId), now));
        outbox.claimDue(now, 10, Duration.ofHours(1));

        List<String> order = new CopyOnWriteArrayList<>();
        List<LogCapture> captures = new ArrayList<>();
        app = RealtimeTestApp.start(args(provider.uri()), context -> {
            LogCapture capture = LogCapture.attach();
            captures.add(capture);
            ApplicationListener<AvailabilityChangeEvent<?>> listener = event -> {
                if (event.getState() == org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC) {
                    order.add("READY:" + capture.events(EventCode.STARTUP_RECOVERY).size());
                }
            };
            context.addApplicationListener(listener);
        });
        LogCapture logs = captures.get(0);
        try {
            assertThat(app.bean(ReadinessState.class).isReady()).isTrue();
            // S6는 S3e 뒤다: READY가 될 때 이미 복구 줄이 있었다
            assertThat(order).isNotEmpty().allMatch("READY:1"::equals);
            Map<String, Object> recovery = LogCapture.attrs(logs.last(EventCode.STARTUP_RECOVERY).orElseThrow());
            assertThat(((Number) recovery.get("recoveredSessions")).intValue()).isEqualTo(3);
            assertThat(((Number) recovery.get("resetConnections")).intValue()).isEqualTo(1);
            assertThat(((Number) recovery.get("reopenedInboxRows")).intValue()).isEqualTo(1);
            assertThat(((Number) recovery.get("rearmedControlEvents")).intValue()).isEqualTo(1);

            for (UUID id : List.of(preparing, active, closing)) {
                ActivitySessionRow row = sessions.find(id).orElseThrow();
                assertThat(row.status()).isEqualTo(SessionStatus.FAILED);
                assertThat(row.failureCode()).isEqualTo(FailureCode.SERVER_RESTART);
            }
            assertThat(sessions.find(ended).orElseThrow().status()).isEqualTo(SessionStatus.ENDED);
            assertThat(connections.find(deviceId).orElseThrow().state()).isIn(ConnectionState.DISCONNECTED,
                    ConnectionState.OFFLINE);
            awaitTrue(() -> inbox.find(inboxEvent).map(row -> row.status() == InboxEventRow.Status.PROCESSED)
                    .orElse(false), "S3c 앞당긴 Inbox 행 처리");
            awaitTrue(() -> outbox.find(failedControl).map(row -> row.status() == OutboxEventRow.Status.PUBLISHED)
                    .orElse(false), "S3d 재무장 → 발송");
            awaitTrue(() -> outbox.find(claimed).map(row -> row.status() == OutboxEventRow.Status.PUBLISHED)
                    .orElse(false), "S3f 선점 되돌림 → 발송");
            awaitTrue(() -> core.requests("POST", "/internal/events").stream()
                    .map(request -> request.json().get("eventType").asString()).toList()
                    .containsAll(List.of("SessionStateChanged", "DeviceStatusChanged", "DeletionCompleted")),
                    "복구 이벤트 세 종류 발송");
        } finally {
            logs.close();
        }
    }

    @Test
    void anOrphanSessionWithoutAnExecutorIsFailedOnceAndANewStartSucceeds() {
        String deviceToken = TestSecrets.deviceToken();
        UUID deviceId = UUID.randomUUID();
        UUID guardianId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        core.route("POST", "/internal/devices/verify-token").respond(StubResponse.json(200,
                "{\"deviceId\":\"" + deviceId + "\",\"guardianId\":\"" + guardianId + "\",\"childId\":\"" + childId
                        + "\",\"registrationStatus\":\"REGISTERED\"}"));
        app = RealtimeTestApp.start(args(provider.uri()));
        LogCapture logs = LogCapture.attach();
        try {
            ActivitySessionRepository sessions = app.bean(ActivitySessionRepository.class);
            UUID orphan = UUID.randomUUID();
            Instant now = Instant.now();
            insert(sessions, orphan, guardianId, deviceId, childId, now);
            move(sessions, orphan, now, SessionStatus.ACTIVE);

            awaitTrue(() -> sessions.find(orphan).map(row -> row.status() == SessionStatus.FAILED).orElse(false),
                    "고아 세션 FAILED");
            assertThat(sessions.find(orphan).orElseThrow().failureCode()).isEqualTo(FailureCode.SERVER_RESTART);
            RealtimeItEnvironment.sleep(1_500);
            assertThat(logs.events(EventCode.APP_ERROR)).filteredOn(event -> "OrphanSessionDetected"
                    .equals(LogCapture.attrs(event).get("errorKind"))).hasSize(1);

            DeviceSimulatorClient device = DeviceSimulatorClient.open(app.deviceUri(), RealtimeItEnvironment.ORIGIN,
                    deviceToken, WAIT);
            device.hello(WAIT);
            awaitTrue(() -> app.bean(DeviceConnectionRepository.class).find(deviceId)
                    .map(row -> row.state() == ConnectionState.CONNECTED).orElse(false), "CONNECTED");
            awaitTrue(() -> app.bean(kr.mujoong.realtime.gateway.SocketRegistry.class).current(deviceId).isPresent(),
                    "소켓");
            StartSessionHandler.StartResult result = app.bean(StartSessionHandler.class).start(
                    new StartSessionHandler.StartCommand(UUID.randomUUID(), UUID.randomUUID(), guardianId, childId,
                            deviceId, ActivityType.FREE_TALK, new ConsentSnapshot("1.0", true, true, false, false),
                            new ChildContext("하늘", 8, List.of()), List.of(), Map.of()), null);
            assertThat(result).isEqualTo(new StartSessionHandler.Accepted(SessionStatus.PREPARING));
            device.close();
        } finally {
            logs.close();
        }
    }

    @Test
    void aStartupCheckRejectionStopsBeforeTheWebServerAndReadinessNeverComesUp() {
        Map<String, String> args = args(provider.uri());
        args.put(RealtimeStartupChecks.PROVIDER_API_KEY, " ");

        assertThatThrownBy(() -> RealtimeTestApp.start(args)).hasRootCauseInstanceOf(
                RealtimeStartupChecks.StartupRejectedException.class);
    }

    private Map<String, String> args(java.net.URI providerUrl) {
        return new java.util.LinkedHashMap<>(RealtimeTestApp.defaults(db.postgres(), core.baseUrl(), providerUrl,
                internalToken));
    }

    private UUID seedSession(ActivitySessionRepository sessions, Instant now, SessionStatus target) {
        UUID id = UUID.randomUUID();
        insert(sessions, id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now);
        move(sessions, id, now, target);
        return id;
    }

    private static void insert(ActivitySessionRepository sessions, UUID id, UUID guardianId, UUID deviceId,
            UUID childId, Instant now) {
        sessions.insert(new NewActivitySession(id, guardianId, childId, deviceId, ActivityType.FREE_TALK,
                UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("하늘", 8, List.of()), UUID.randomUUID(), null), now);
    }

    private static void move(ActivitySessionRepository sessions, UUID id, Instant now, SessionStatus target) {
        List<SessionStatus> path = List.of(SessionStatus.DEVICE_ACK, SessionStatus.ACTIVE, SessionStatus.CLOSING,
                SessionStatus.ENDED);
        SessionStatus current = SessionStatus.PREPARING;
        for (SessionStatus next : path) {
            if (current == target) {
                return;
            }
            SessionChange change = next == SessionStatus.ENDED
                    ? SessionChange.ended(kr.mujoong.contract.status.EndReason.GUARDIAN_STOP, now) : SessionChange.none();
            assertThat(sessions.transition(id, current, next, now, change)).isPresent();
            current = next;
        }
    }
}
