package kr.mujoong.core.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.trace.TraceIds;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.ActivityCopyRepository.NewActivity;
import kr.mujoong.core.activity.RealtimeSessionClient;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.config.CoreInboxProperties;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.messaging.CoreInboxDispatcher;
import kr.mujoong.core.messaging.CoreInboxRepository;
import kr.mujoong.core.messaging.CoreInboxRepository.NewInboxEvent;
import kr.mujoong.core.messaging.handler.SessionStateChangedHandler;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.NoTransactions;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 실제 Inbox 상태·사본·발송 요청을 한 트랜잭션으로 검증한다. 외부 호출과 스케줄러는 실행하지 않는다. */
@Tag("u7")
@Tag("db")
class PushInboxIT {
    private final JsonCodec json = JsonCodec.shared();
    private final List<UUID> owners = new ArrayList<>();
    private final List<CoreInboxDispatcher> dispatchers = new ArrayList<>();
    private DbTestSupport db;
    private MutableClock clock;
    private ActivityCopyRepository activities;
    private GuardianWriteLock guardians;
    private PushEventRepository events;
    private CoreInboxRepository inbox;
    private RealtimeSessionClient realtime;
    private PushDeletionParticipant deletion;
    private PushSubscriptionService subscriptions;
    private DataSourceTransactionManager transactions;
    private CoreInboxDispatcher dispatcher;

    @BeforeEach
    void setup() {
        db = DbTestSupport.coreApp();
        clock = new MutableClock(DbTestSupport.EPOCH.plusSeconds(100));
        activities = new ActivityCopyRepository(db.jdbc());
        guardians = new GuardianWriteLock(db.jdbc());
        events = new PushEventRepository(db.jdbc(), json);
        inbox = new CoreInboxRepository(db.jdbc());
        realtime = mock(RealtimeSessionClient.class);
        transactions = new DataSourceTransactionManager(db.dataSource());
        var repository = new PushSubscriptionRepository(db.jdbc());
        subscriptions = new PushSubscriptionService(guardians, repository, clock, NoTransactions.noRetry(), transactions);
        deletion = new PushDeletionParticipant(guardians, repository, NoTransactions.noRetry(), transactions);
        dispatcher = dispatcher(events);
    }

    @AfterEach
    void cleanup() {
        try {
            for (var current : dispatchers) current.destroy();
        } finally {
            if (db != null) {
                try {
                    for (UUID owner : owners) deletion.deleteByGuardian(owner, UUID.randomUUID());
                } finally {
                    db.close();
                }
            }
        }
    }

    @Test
    void endedBeforeActiveCreatesExactlyOneCompletionAfterStartedAtIsFilled() {
        var copy = requested();
        subscriptions.put(copy.guardianId(), PushSubscriptionFixtures.request());
        var ended = state(copy, SessionStatus.ENDED, 5, EndReason.GUARDIAN_STOP, true, null);
        var active = state(copy, SessionStatus.ACTIVE, 3, null, false, null);
        UUID endedId = receive(ended);
        UUID activeId = receive(active);
        assertThat(dispatcher.process(endedId)).isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
        assertThat(dispatchCount(copy.sessionId())).isZero();
        assertThat(dispatcher.process(activeId)).isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
        assertThat(dispatcher.process(activeId)).isEqualTo(CoreInboxDispatcher.Outcome.SKIPPED);
        assertThat(dispatcher.process(receive(active))).isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
        assertThat(dispatcher.process(receive(ended))).isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
        var current = activities.findById(copy.sessionId()).orElseThrow();
        assertThat(current.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(current.statusVersion()).isEqualTo(5);
        assertThat(current.startedAt()).isEqualTo(active.at());
        assertThat(current.endedAt()).isEqualTo(ended.at());
        assertThat(dispatchCount(copy.sessionId())).isEqualTo(1);
        assertThat(dispatchTypes(copy.sessionId())).containsExactly("ACTIVITY_COMPLETED");
        assertThat(inbox.findById(activeId).orElseThrow().status()).isEqualTo("PROCESSED");
        assertThat(inbox.findById(activeId).orElseThrow().payloadJson()).isEqualTo("{}");
        verifyNoInteractions(realtime);
    }

    @Test
    void accountDeletionStillAcceptsTheTerminalCopyButNeverCreatesAPush() {
        var copy = requested();
        subscriptions.put(copy.guardianId(), PushSubscriptionFixtures.request());
        assertThat(dispatcher.process(receive(state(copy, SessionStatus.ACTIVE, 3, null, false, null))))
                .isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
        db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id",
                Map.of("id", copy.guardianId()));
        assertThat(dispatcher.process(receive(state(copy, SessionStatus.ENDED, 5, EndReason.CHILD_STOP, true, null))))
                .isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
        assertThat(activities.findById(copy.sessionId()).orElseThrow().status()).isEqualTo(SessionStatus.ENDED);
        assertThat(dispatchCount(copy.sessionId())).isZero();
    }

    @Test
    void failureAfterDispatchInsertRollsBackBothTheCopyAndDispatchAndRetriesTheInbox() {
        var copy = requested();
        subscriptions.put(copy.guardianId(), PushSubscriptionFixtures.request());
        dispatcher.process(receive(state(copy, SessionStatus.ACTIVE, 3, null, false, null)));
        var failing = new PushEventRepository(db.jdbc(), json) {
            @Override public int enqueue(ActivityCopy current, PushEventType type, String key, Instant now) {
                super.enqueue(current, type, key, now);
                throw new IllegalStateException("발송 기록 저장 뒤 실패를 재현한다");
            }
        };
        UUID eventId = receive(state(copy, SessionStatus.ENDED, 5, EndReason.SILENCE, true, null));
        assertThat(dispatcher(failing).process(eventId)).isEqualTo(CoreInboxDispatcher.Outcome.HANDLER_RETRY);
        assertThat(activities.findById(copy.sessionId()).orElseThrow().status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(dispatchCount(copy.sessionId())).isZero();
        assertThat(inbox.findById(eventId).orElseThrow().status()).isEqualTo("RECEIVED");
        assertThat(inbox.findById(eventId).orElseThrow().retryCount()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(dispatcher.process(eventId)).isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
        assertThat(dispatchCount(copy.sessionId())).isEqualTo(1);
        assertThat(activities.findById(copy.sessionId()).orElseThrow().status()).isEqualTo(SessionStatus.ENDED);
    }

    @Test
    void recoveryAndTerminationInvalidateWaitingFaultsAndAnOldPauseCannotRecreateThem() {
        var copy = requested();
        subscriptions.put(copy.guardianId(), PushSubscriptionFixtures.request());
        var pause = new SessionStateChanged.TechnicalPause(SessionStateChanged.TechnicalPause.Kind.DEVICE,
                DbTestSupport.EPOCH.plusSeconds(10));
        var activePause = state(copy, SessionStatus.ACTIVE, 3, null, false, pause);
        dispatcher.process(receive(activePause));
        assertThat(dispatchTypes(copy.sessionId())).containsExactly("VOICE_PROBLEM");
        dispatcher.process(receive(state(copy, SessionStatus.ACTIVE, 4, null, false, null)));
        assertThat(statuses(copy.sessionId())).containsExactly("SKIPPED");
        dispatcher.process(receive(activePause));
        assertThat(dispatchCount(copy.sessionId())).isEqualTo(1);
        var newerPause = new SessionStateChanged.TechnicalPause(SessionStateChanged.TechnicalPause.Kind.PROVIDER,
                DbTestSupport.EPOCH.plusSeconds(20));
        dispatcher.process(receive(state(copy, SessionStatus.ACTIVE, 5, null, false, newerPause)));
        dispatcher.process(receive(state(copy, SessionStatus.ABORTED, 6, EndReason.DISCONNECT, true, null)));
        assertThat(dispatchTypes(copy.sessionId())).containsExactlyInAnyOrder("VOICE_PROBLEM", "VOICE_PROBLEM", "ENDED_BY_CONNECTION");
        assertThat(db.jdbc().queryForList("select status from core.core_push_dispatch where session_id=:id and event_type='VOICE_PROBLEM'",
                Map.of("id", copy.sessionId()), String.class)).containsOnly("SKIPPED");
    }

    @Test
    void aPayloadWithAnotherGuardianCannotModifyTheSessionOrCreateAnEvent() {
        var copy = requested();
        UUID other = db.insertGuardian("ACTIVE");
        owners.add(other);
        var forged = new SessionStateChanged(copy.sessionId(), other, copy.childId(), ActivityType.FREE_TALK,
                SessionStatus.ENDED, 5L, EndReason.CHILD_STOP, null, true, null, clock.now());
        assertThat(dispatcher.process(receive(forged))).isEqualTo(CoreInboxDispatcher.Outcome.PROCESSED);
        assertThat(activities.findById(copy.sessionId()).orElseThrow().status()).isEqualTo(SessionStatus.REQUESTED);
        assertThat(dispatchCount(copy.sessionId())).isZero();
    }

    private CoreInboxDispatcher dispatcher(PushEventRepository repository) {
        var handler = new SessionStateChangedHandler(activities, realtime, json, new PushEventRecorder(guardians, repository));
        var config = new CoreInboxProperties(Duration.ofSeconds(1), List.of(Duration.ofSeconds(1)), 1,
                Duration.ofSeconds(5), 1, 10);
        var current = new CoreInboxDispatcher(inbox, List.of(handler), transactions, mock(EventLogger.class),
                mock(CoreMetrics.class), clock, config);
        dispatchers.add(current);
        return current;
    }

    private ActivityCopy requested() {
        var fixtures = new BusinessFixtures(db);
        var family = fixtures.family(DbTestSupport.EPOCH);
        owners.add(family.guardianId());
        UUID device = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED,
                DbTestSupport.EPOCH, DbTestSupport.EPOCH);
        UUID id = UUID.randomUUID();
        activities.insertRequested(new NewActivity(id, family.guardianId(), family.childId(), device,
                ActivityType.FREE_TALK, UUID.randomUUID().toString(), "hash", DbTestSupport.EPOCH));
        return activities.findById(id).orElseThrow();
    }

    private SessionStateChanged state(ActivityCopy copy, SessionStatus status, long version, EndReason reason,
            boolean confirmed, SessionStateChanged.TechnicalPause pause) {
        return new SessionStateChanged(copy.sessionId(), copy.guardianId(), copy.childId(), copy.activityType(),
                status, version, reason, null, confirmed, pause, DbTestSupport.EPOCH.plusSeconds(version * 10));
    }

    private UUID receive(SessionStateChanged payload) {
        var envelope = json.envelopeFor(UUID.randomUUID(), payload, payload.at(), TraceIds.newTraceId());
        inbox.insertReceived(new NewInboxEvent(envelope.eventId(), envelope.eventType(), 1, payload.at(),
                payload.sessionId(), payload.guardianId(), envelope.payloadJson(), json.sha256OfCanonical(envelope.payloadJson()),
                envelope.traceId(), clock.now()));
        return db.trackInbox(envelope.eventId());
    }

    private int dispatchCount(UUID session) {
        return db.jdbc().queryForObject("select count(*) from core.core_push_dispatch where session_id=:id",
                Map.of("id", session), Integer.class);
    }

    private List<String> dispatchTypes(UUID session) {
        return db.jdbc().queryForList("select event_type from core.core_push_dispatch where session_id=:id order by event_key",
                Map.of("id", session), String.class);
    }

    private List<String> statuses(UUID session) {
        return db.jdbc().queryForList("select status from core.core_push_dispatch where session_id=:id order by event_key",
                Map.of("id", session), String.class);
    }
}
