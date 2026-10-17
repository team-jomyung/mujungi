package kr.mujoong.realtime.judgment;

import static kr.mujoong.realtime.judgment.JudgmentFixtures.T0;
import static kr.mujoong.realtime.judgment.JudgmentFixtures.abstained;
import static kr.mujoong.realtime.judgment.JudgmentFixtures.confirmed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 판정 기록 저장소 · 기록기 · 삭제 참가자 통합 시험 (U11 FR2 · BR2.1 · BR2.4 · BR2.5, realtime V5). 쓰기와 같은 id 덮어쓰기, 트랜잭션 강제, 외래 키
 * 위반 폐기, 보호자 기준 삭제(세션 삭제보다 먼저, 삭제 뒤 0건, 늦은 기록 차단)를 실제 DB로 본다.
 */
@Tag("u11")
@Tag("db")
class JudgmentLiveRepositoryIT {

    private static RealtimeDb db;
    private JudgmentLiveRepository repository;
    private ActivitySessionRepository sessions;
    private UUID guardian;
    private UUID session;

    @BeforeAll
    static void openDatabase() {
        db = RealtimeDb.open();
    }

    @AfterAll
    static void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        db.wipe();
        repository = new JudgmentLiveRepository(db.jdbc());
        sessions = new ActivitySessionRepository(db.jdbc());
        guardian = UUID.randomUUID();
        session = insertSession(guardian);
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    private UUID insertSession(UUID guardianId) {
        NewActivitySession row = new NewActivitySession(UUID.randomUUID(), guardianId, UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("하늘", 8, List.of("공룡")), null, null);
        assertThat(db.inTransaction(() -> sessions.insert(row, T0))).isTrue();
        return row.id();
    }

    @Test
    void aRecordRoundTripsAndTheSameIdOverwritesWithoutAddingARow() {
        UUID id = UUID.randomUUID();
        JudgmentLiveRecord first = abstained(id, guardian, session, AbstainReason.TIMEOUT);

        db.inTransaction(() -> repository.upsert(first));
        assertThat(repository.find(id)).contains(first);

        JudgmentLiveRecord second = confirmed(id, guardian, session);
        db.inTransaction(() -> repository.upsert(second));

        assertThat(repository.find(id)).contains(second);
        assertThat(repository.countByGuardian(guardian)).isEqualTo(1L);
    }

    @Test
    void theWriteRequiresAnActiveTransactionAndAnUnknownSessionIsRefusedByTheForeignKey() {
        JudgmentLiveRecord record = confirmed(UUID.randomUUID(), guardian, session);
        assertThatThrownBy(() -> repository.upsert(record)).isInstanceOf(IllegalTransactionStateException.class);

        JudgmentLiveRecord orphan = confirmed(UUID.randomUUID(), guardian, UUID.randomUUID());
        assertThatThrownBy(() -> db.inTransaction(() -> repository.upsert(orphan))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(JudgmentLiveRecorder.isPermanent(catchWrite(orphan))).isTrue();
    }

    private Throwable catchWrite(JudgmentLiveRecord record) {
        try {
            db.inTransaction(() -> repository.upsert(record));
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    @Test
    void theRecorderWritesThroughTheJdbcStoreAndDropsARecordWhoseSessionIsGone() {
        org.springframework.transaction.support.TransactionTemplate transactions = db.transactions();
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(new JudgmentLiveRecorder.JdbcStore(repository, transactions),
                kr.mujoong.realtime.runtime.TaskLauncher.inline(), kr.mujoong.realtime.testing.TestEventLoggers.strict(
                        new kr.mujoong.testsupport.time.MutableClock(), kr.mujoong.testsupport.log.LogCapture.detached(),
                        kr.mujoong.realtime.observability.RealtimeMetrics.inMemory()));
        UUID good = UUID.randomUUID();

        recorder.put(confirmed(good, guardian, session));
        recorder.put(confirmed(UUID.randomUUID(), guardian, UUID.randomUUID()));

        assertThat(repository.find(good)).isPresent();
        assertThat(repository.countByGuardian(guardian)).isEqualTo(1L);
        assertThat(recorder.held()).as("외래 키 위반은 버린다").isZero();
    }

    @Test
    void theDeletionParticipantRunsBetweenPracticeAndSessionAndLetsTheSessionRowGo() {
        UUID otherGuardian = UUID.randomUUID();
        UUID otherSession = insertSession(otherGuardian);
        db.inTransaction(() -> {
            repository.upsert(confirmed(UUID.randomUUID(), guardian, session));
            repository.upsert(abstained(UUID.randomUUID(), guardian, session, AbstainReason.LOW_MARGIN));
            repository.upsert(confirmed(UUID.randomUUID(), otherGuardian, otherSession));
        });
        assertThatThrownBy(() -> db.inTransaction(() -> db.jdbc().update("delete from realtime.realtime_activity_session where id = :id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", session))))
                .as("판정 기록이 세션 행을 붙잡는다").isInstanceOf(DataIntegrityViolationException.class);
        JudgmentLiveDeletionParticipant participant = participant();

        assertThat(JudgmentLiveDeletionParticipant.ORDER_JUDGMENT)
                .isGreaterThan(kr.mujoong.realtime.messaging.RealtimeDeletionParticipant.ORDER_PRACTICE)
                .isLessThan(kr.mujoong.realtime.messaging.RealtimeDeletionParticipant.ORDER_SESSION);
        assertThat(participant.order()).isEqualTo(180);
        var counts = participant.deleteByGuardian(guardian, UUID.randomUUID());

        assertThat(counts).containsExactlyEntriesOf(java.util.Map.of(JudgmentLiveDeletionParticipant.JUDGMENT_LIVE, 2));
        assertThat(repository.countByGuardian(guardian)).as("삭제 뒤 0건").isZero();
        assertThat(repository.countByGuardian(otherGuardian)).as("다른 보호자는 그대로").isEqualTo(1L);
        assertThat(db.inTransaction(() -> db.jdbc().update("delete from realtime.realtime_activity_session where id = :id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", session)))).isEqualTo(1);
        assertThat(participant.deleteByGuardian(guardian, UUID.randomUUID()).values()).as("다시 불러도 같다").containsOnly(0);
    }

    @Test
    void aRecordArrivingAfterTheGuardianIsBlockedIsNeverWritten() {
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(new JudgmentLiveRecorder.JdbcStore(repository, db.transactions()),
                kr.mujoong.realtime.runtime.TaskLauncher.inline(), kr.mujoong.realtime.testing.TestEventLoggers.strict(
                        new kr.mujoong.testsupport.time.MutableClock(), kr.mujoong.testsupport.log.LogCapture.detached(),
                        kr.mujoong.realtime.observability.RealtimeMetrics.inMemory()));
        JudgmentLiveDeletionParticipant participant = new JudgmentLiveDeletionParticipant(repository, recorder,
                new kr.mujoong.realtime.common.db.DbRetry(0, new java.util.Random(1), ignored -> { }), db.transactions(),
                java.time.Duration.ofSeconds(1), kr.mujoong.realtime.testing.TestEventLoggers.strict(
                        new kr.mujoong.testsupport.time.MutableClock(), kr.mujoong.testsupport.log.LogCapture.detached(),
                        kr.mujoong.realtime.observability.RealtimeMetrics.inMemory()));

        participant.deleteByGuardian(guardian, UUID.randomUUID());
        boolean accepted = recorder.put(confirmed(UUID.randomUUID(), guardian, session));

        assertThat(accepted).isFalse();
        assertThat(repository.countByGuardian(guardian)).isZero();
    }

    private JudgmentLiveDeletionParticipant participant() {
        JudgmentLiveRecorder recorder = new JudgmentLiveRecorder(record -> { }, kr.mujoong.realtime.runtime.TaskLauncher.inline(),
                kr.mujoong.realtime.testing.TestEventLoggers.strict(new kr.mujoong.testsupport.time.MutableClock(),
                        kr.mujoong.testsupport.log.LogCapture.detached(), kr.mujoong.realtime.observability.RealtimeMetrics.inMemory()));
        return new JudgmentLiveDeletionParticipant(repository, recorder,
                new kr.mujoong.realtime.common.db.DbRetry(0, new java.util.Random(1), ignored -> { }), db.transactions(),
                java.time.Duration.ofSeconds(1), kr.mujoong.realtime.testing.TestEventLoggers.strict(
                        new kr.mujoong.testsupport.time.MutableClock(), kr.mujoong.testsupport.log.LogCapture.detached(),
                        kr.mujoong.realtime.observability.RealtimeMetrics.inMemory()));
    }
}
