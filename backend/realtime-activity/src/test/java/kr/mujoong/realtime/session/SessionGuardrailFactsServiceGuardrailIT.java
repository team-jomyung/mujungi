package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.log.AsyncRecorder;
import kr.mujoong.realtime.log.SessionGapRepository;
import kr.mujoong.realtime.log.TranscriptRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxRepository;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.session.SessionGuardrailFacts.Goodbye;
import kr.mujoong.realtime.session.SessionGuardrailFacts.Notice;
import kr.mujoong.realtime.session.SessionGuardrailFacts.Participation;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Tag("service-guardrail")
@Tag("db")
class SessionGuardrailFactsServiceGuardrailIT {

    private static final Instant AT = Instant.parse("2026-10-21T00:00:00Z");
    private static RealtimeDb db;
    private ActivitySessionRepository sessions;
    private SessionGuardrailFactsRepository facts;
    private TransitionService transitions;
    private NewActivitySession session;
    private DbRetry retry;

    @BeforeAll static void open() { db = RealtimeDb.open(); }
    @AfterAll static void close() { if (db != null) db.close(); }

    @BeforeEach
    void prepare() {
        db.wipe();
        sessions = new ActivitySessionRepository(db.jdbc());
        facts = new SessionGuardrailFactsRepository(db.jdbc(), new SessionEventRepository(db.jdbc()));
        retry = new DbRetry(0, new java.util.Random(1), duration -> { });
        MujoongClock clock = mock(MujoongClock.class);
        when(clock.now()).thenReturn(AT);
        SessionStateEmitter emitter = new SessionStateEmitter(new RealtimeOutboxWriter(
                new RealtimeOutboxRepository(db.jdbc()), JsonCodec.shared()), mock(EventLogger.class), mock(RealtimeMetrics.class));
        transitions = new TransitionService(sessions, new TranscriptRepository(db.jdbc()), emitter,
                new DataSourceTransactionManager(db.dataSource()), retry, mock(AsyncRecorder.class), JsonCodec.shared(), clock, facts);
        session = new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("별", 8, List.of()), null, null);
        db.inTransaction(() -> sessions.insert(session, AT));
    }

    @Test
    void stateAndFactsAndOutboxCommitTogether() {
        assertThat(transition(value(1, 2, Participation.PENDING))).isPresent();
        assertThat(sessions.find(session.id()).orElseThrow().status()).isEqualTo(SessionStatus.DEVICE_ACK);
        assertThat(facts.latest(session.id())).contains(value(1, 2, Participation.PENDING));
        assertThat(count("realtime_outbox_event")).isEqualTo(1L);
    }

    @Test
    void invalidRevisionRollsBackStateOutboxAndTranscriptDeletion() {
        db.jdbc().update("insert into realtime.realtime_transcript (id,session_id,sequence_no,speaker,text,assembly_status,created_at)"
                + " values (cast(:id as uuid),cast(:session as uuid),1,'CHILD','synthetic','FINAL',:at)",
                new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("session", session.id().toString())
                        .addValue("at", java.sql.Timestamp.from(AT)));
        assertThatThrownBy(() -> transitions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK,
                SessionChange.none(), SessionTransitions.Origin.REALTIME, null,
                new SessionTransitions.Extras(null, true, value(2, 2, Participation.PENDING))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sessions.find(session.id()).orElseThrow().status()).isEqualTo(SessionStatus.PREPARING);
        assertThat(count("realtime_outbox_event")).isZero();
        assertThat(count("realtime_transcript")).isEqualTo(1L);
        assertThat(facts.latest(session.id())).isEmpty();
    }

    @Test
    void lostConditionalTransitionDoesNotWriteFactsOrAnotherOutbox() {
        assertThat(transition(value(1, 2, Participation.PENDING))).isPresent();
        assertThat(transition(value(2, 2, Participation.REFUSED))).isEmpty();
        assertThat(facts.latest(session.id()).orElseThrow().revision()).isEqualTo(1);
        assertThat(count("realtime_outbox_event")).isEqualTo(1L);
    }

    @Test
    void staleStatusAndConflictingRevisionCannotOverwriteFacts() {
        SessionGuardrailFacts original = value(1, 1, Participation.PENDING);
        assertThat(transitions.recordGuardrailFacts(original, 1)).contains(original);
        assertThat(transitions.recordGuardrailFacts(original, 1)).contains(original);
        assertThat(count("realtime_session_event")).isEqualTo(1L);
        assertThat(transitions.recordGuardrailFacts(value(2, 1, Participation.REFUSED), 2)).isEmpty();
        assertThatThrownBy(() -> transitions.recordGuardrailFacts(value(1, 1, Participation.REFUSED), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentWritersSerializeAndConflictingPayloadHasOneWinner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> writeContender(Participation.PENDING));
            var second = pool.submit(() -> writeContender(Participation.REFUSED));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("realtime_session_event")).isEqualTo(1L);
    }

    @Test
    void newRepositoryReadsHighestRevisionRatherThanTimestampOrUuid() {
        transitions.recordGuardrailFacts(value(1, 1, Participation.PENDING), 1);
        SessionGuardrailFacts second = new SessionGuardrailFacts(1, session.id(), 2, 1, Notice.COMPLETED,
                Participation.REFUSED, Goodbye.NONE, null, AT.minusSeconds(10));
        transitions.recordGuardrailFacts(second, 1);
        SessionGuardrailFactsRepository restarted = new SessionGuardrailFactsRepository(db.jdbc(), new SessionEventRepository(db.jdbc()));
        assertThat(restarted.latest(session.id())).contains(second);
        assertThat(restarted.latest(UUID.randomUUID())).isEmpty();
    }

    @Test
    void existingAccountDeletionRemovesNewFactsInTheSameSessionLifetime() {
        transitions.recordGuardrailFacts(value(1, 1, Participation.PENDING), 1);
        var deletion = new RealtimeSessionDeletionParticipant(db.jdbc(), mock(ResultInputSnapshotRepository.class),
                new TranscriptRepository(db.jdbc()), new SessionGapRepository(db.jdbc()), retry,
                new DataSourceTransactionManager(db.dataSource()));
        assertThat(deletion.deleteByGuardian(session.guardianId(), UUID.randomUUID()))
                .containsEntry(RealtimeSessionDeletionParticipant.SESSION_EVENT, 1);
        assertThat(facts.latest(session.id())).isEmpty();
        assertThat(sessions.find(session.id())).isEmpty();
    }

    @Test
    void queryPlanCanUseBoundedSessionRevisionIndex() {
        transitions.recordGuardrailFacts(value(1, 1, Participation.PENDING), 1);
        String plan = db.inTransaction(() -> {
            db.jdbc().getJdbcTemplate().execute("set local enable_seqscan = off");
            return String.join("\n", db.jdbc().query("explain " + SessionGuardrailFactsRepository.LATEST,
                    new MapSqlParameterSource("sessionId", session.id()), (rs, index) -> rs.getString(1)));
        });
        assertThat(plan).contains("Limit", "realtime_session_guardrail_revision_idx").doesNotContain("Sort");
    }

    private boolean writeContender(Participation participation) {
        try {
            return transitions.recordGuardrailFacts(value(1, 1, participation), 1).isPresent();
        } catch (IllegalStateException conflict) {
            return false;
        }
    }

    private java.util.Optional<ActivitySessionRow> transition(SessionGuardrailFacts proposed) {
        return transitions.transition(session.id(), SessionStatus.PREPARING, SessionStatus.DEVICE_ACK,
                SessionChange.none(), SessionTransitions.Origin.REALTIME, null,
                new SessionTransitions.Extras(null, false, proposed));
    }

    private SessionGuardrailFacts value(long revision, int version, Participation participation) {
        return new SessionGuardrailFacts(1, session.id(), revision, version, Notice.COMPLETED,
                participation, Goodbye.NONE, null, AT);
    }

    private long count(String table) {
        return db.jdbc().getJdbcTemplate().queryForObject("select count(*) from realtime." + table, Long.class);
    }
}
