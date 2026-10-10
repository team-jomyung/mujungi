package kr.mujoong.realtime.startup;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.gateway.ConnectionStateWriter;
import kr.mujoong.realtime.gateway.DisconnectChecker;
import kr.mujoong.realtime.gateway.StatusReporter;
import kr.mujoong.realtime.health.DbHealthChecker;
import kr.mujoong.realtime.health.ReadinessState;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.LatencyRecorder;
import kr.mujoong.realtime.messaging.ControlEventRearm;
import kr.mujoong.realtime.messaging.MessagingCleanupJob;
import kr.mujoong.realtime.messaging.RealtimeInboxDispatcher;
import kr.mujoong.realtime.messaging.RealtimeInboxRepoller;
import kr.mujoong.realtime.messaging.RealtimeInboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxSender;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.runtime.PeriodicJobs;
import kr.mujoong.realtime.session.OrphanSessionChecker;
import kr.mujoong.realtime.session.TombstoneMap;
import kr.mujoong.realtime.session.TransitionService;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Realtime 기동 순서 S2 ~ S6 (U1 3.5 Step 26 · RD-12 · BR6.13 · A1 · J124 · R3-18).
 *
 * <p>S1(프로필 가드, EnvironmentPostProcessor) · S1b({@link RealtimeStartupChecks}, 빈 생성 때)는 이미 끝났다. 웹 서버 시작(MAX − 2048)보다
 * 앞 phase에서: S2 스키마 판 확인 → S2b 데이터 등급 확인(거부면 종료 코드 1) → S3a 비종료 세션 FAILED(SERVER_RESTART) → S3b CONNECTED ·
 * UNSTABLE 연결 행 DISCONNECTED(connection_id 비움) → S3c Inbox RECEIVED 앞당김 → S3d 제어 이벤트 재무장(A1) → S3f Outbox 선점 되돌림 →
 * S3e STARTUP_RECOVERY 한 줄 → S4 주기 작업(outbox-sender · inbox-repoll · recorder-tick · maintenance) → S6 readiness READY. 사전 로딩
 * 전에는 readiness가 UP이 아니다(S1b가 끝나야 이 빈이 만들어진다). S3 각 문장은 멱등이다.
 */
@Component
public class RealtimeStartupSequence implements SmartLifecycle {

    /** 웹 서버 시작(MAX − 2048)보다 앞. */
    public static final int PHASE = Integer.MAX_VALUE - 4096;

    /** 시작 복구 결과(S3e 줄의 값). */
    public record Recovery(int recoveredSessions, int resetConnections, int reopenedInboxRows, int rearmedControlEvents,
            int releasedOutboxClaims) {
    }

    private final RealtimeStartupChecks startupChecks;
    private final SchemaVersionCheck schemaVersionCheck;
    private final DataClassCheck dataClassCheck;
    private final TransitionService transitions;
    private final ConnectionStateWriter connectionWriter;
    private final RealtimeInboxRepository inbox;
    private final RealtimeOutboxRepository outbox;
    private final ControlEventRearm rearm;
    private final RealtimeOutboxSender sender;
    private final RealtimeInboxRepoller repoller;
    private final RealtimeInboxDispatcher dispatcher;
    private final AsyncRecorder recorder;
    private final LatencyRecorder latency;
    private final DbHealthChecker dbHealthChecker;
    private final DisconnectChecker disconnectChecker;
    private final StatusReporter statusReporter;
    private final OrphanSessionChecker orphanChecker;
    private final TombstoneMap tombstones;
    private final MessagingCleanupJob messagingCleanup;
    private final EventLogger events;
    private final ReadinessState readiness;
    private final MujoongClock clock;
    private final RealtimeProperties properties;

    private PeriodicJobs jobs;
    private volatile boolean running;

    public RealtimeStartupSequence(
            RealtimeStartupChecks startupChecks,
            SchemaVersionCheck schemaVersionCheck,
            DataClassCheck dataClassCheck,
            TransitionService transitions,
            ConnectionStateWriter connectionWriter,
            RealtimeInboxRepository inbox,
            RealtimeOutboxRepository outbox,
            ControlEventRearm rearm,
            RealtimeOutboxSender sender,
            RealtimeInboxRepoller repoller,
            RealtimeInboxDispatcher dispatcher,
            AsyncRecorder recorder,
            LatencyRecorder latency,
            DbHealthChecker dbHealthChecker,
            DisconnectChecker disconnectChecker,
            StatusReporter statusReporter,
            OrphanSessionChecker orphanChecker,
            TombstoneMap tombstones,
            MessagingCleanupJob messagingCleanup,
            EventLogger events,
            ReadinessState readiness,
            MujoongClock clock,
            RealtimeProperties properties) {
        this.startupChecks = Objects.requireNonNull(startupChecks, "startupChecks");
        this.schemaVersionCheck = Objects.requireNonNull(schemaVersionCheck, "schemaVersionCheck");
        this.dataClassCheck = Objects.requireNonNull(dataClassCheck, "dataClassCheck");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.connectionWriter = Objects.requireNonNull(connectionWriter, "connectionWriter");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.rearm = Objects.requireNonNull(rearm, "rearm");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.repoller = Objects.requireNonNull(repoller, "repoller");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.latency = Objects.requireNonNull(latency, "latency");
        this.dbHealthChecker = Objects.requireNonNull(dbHealthChecker, "dbHealthChecker");
        this.disconnectChecker = Objects.requireNonNull(disconnectChecker, "disconnectChecker");
        this.statusReporter = Objects.requireNonNull(statusReporter, "statusReporter");
        this.orphanChecker = Objects.requireNonNull(orphanChecker, "orphanChecker");
        this.tombstones = Objects.requireNonNull(tombstones, "tombstones");
        this.messagingCleanup = Objects.requireNonNull(messagingCleanup, "messagingCleanup");
        this.events = Objects.requireNonNull(events, "events");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void start() {
        // S1b는 빈 생성 때 끝났다(사전 로딩 포함). 여기서 다시 확인해 사전 없이 READY가 되지 않게 한다
        Objects.requireNonNull(startupChecks.dictionary(), "seed dictionary");
        schemaVersionCheck.verify();
        dataClassCheck.verify();
        Recovery recovery = recover();
        logRecovery(recovery);
        startJobs();
        running = true;
        readiness.markReady();
    }

    /** S3a · S3b · S3c · S3d · S3f(각 문장 멱등). */
    public Recovery recover() {
        int recoveredSessions = transitions.failInProgressAfterRestart();
        int resetConnections = connectionWriter.resetAfterRestart();
        Instant now = clock.now();
        int reopened = inbox.pullForwardReceived(now);
        int rearmed = rearm.rearmOnStart(now);
        int released = outbox.releaseClaims(now);
        return new Recovery(recoveredSessions, resetConnections, reopened, rearmed, released);
    }

    private void logRecovery(Recovery recovery) {
        try {
            events.log(EventCode.STARTUP_RECOVERY, EventLog.Component.REALTIME_MAINTENANCE, null, entry -> entry
                    .attr(AttrKey.RECOVERED_SESSIONS, (long) recovery.recoveredSessions())
                    .attr(AttrKey.REOPENED_INBOX_ROWS, (long) recovery.reopenedInboxRows())
                    .attr(AttrKey.RESET_CONNECTIONS, (long) recovery.resetConnections())
                    .attr(AttrKey.REARMED_CONTROL_EVENTS, (long) recovery.rearmedControlEvents()));
        } catch (RuntimeException e) {
            // S3e 실패는 기동을 막지 않는다(RD-12 표)
            events.appError(EventLog.Component.REALTIME_MAINTENANCE, e);
        }
    }

    private synchronized void startJobs() {
        PeriodicJobs created = new PeriodicJobs(
                failure -> events.appError(EventLog.Component.REALTIME_MAINTENANCE, failure));
        created.fixedDelay(PeriodicJobs.OUTBOX_SENDER, properties.outbox().pollInterval(), sender::runCycle);
        created.fixedDelay(PeriodicJobs.INBOX_REPOLL, properties.inbox().pollInterval(), repoller::runOnce);
        created.fixedDelay(PeriodicJobs.RECORDER_TICK, properties.timing().recorder().flushInterval(), recorder::tick);
        created.fixedDelay(PeriodicJobs.MAINTENANCE, properties.health().dbCheckInterval(), dbHealthChecker::runOnce);
        created.fixedDelay(PeriodicJobs.MAINTENANCE, properties.device().disconnectCheckInterval(),
                disconnectChecker::runOnce);
        created.fixedDelay(PeriodicJobs.MAINTENANCE, properties.timing().device().statusReportInterval(),
                statusReporter::runOnce);
        created.fixedDelay(PeriodicJobs.MAINTENANCE, properties.session().orphanCheckInterval(), () -> {
            orphanChecker.runOnce();
            tombstones.purgeExpired();
            latency.purgeEnded(clock.now());
        });
        created.fixedDelay(PeriodicJobs.MAINTENANCE, properties.cleanup().messagingInterval(), messagingCleanup::runOnce);
        created.fixedDelay(PeriodicJobs.MAINTENANCE, properties.observability().logMergeWindow(), events::flushMerged);
        jobs = created;
    }

    /**
     * T4: 주기 작업을 멈춘다. 발송기는 진행 중 한 건을 마치고 남은 선점을 되돌린다. 진행 중 작업은 발행 제한 + 문장 제한까지 기다린다. 멱등이다.
     */
    public void stopJobs() {
        sender.requestStop();
        PeriodicJobs toStop;
        synchronized (this) {
            toStop = jobs;
            jobs = null;
        }
        Duration grace = properties.internalHttp().eventPublishTimeout().plus(properties.limits().dbStatementTimeout());
        if (toStop != null) {
            toStop.stopAll(grace);
        }
        dispatcher.shutdown();
        try {
            dispatcher.awaitTermination(grace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public void stop() {
        stopJobs();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
