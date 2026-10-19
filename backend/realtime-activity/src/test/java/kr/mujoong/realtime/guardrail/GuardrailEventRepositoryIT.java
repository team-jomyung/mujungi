package kr.mujoong.realtime.guardrail;

import static kr.mujoong.realtime.guardrail.GuardrailFixtures.T0;
import static kr.mujoong.realtime.guardrail.GuardrailFixtures.allow;
import static kr.mujoong.realtime.guardrail.GuardrailFixtures.blocked;
import static kr.mujoong.realtime.guardrail.GuardrailFixtures.skipped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.testing.RealtimeDb;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 제브 검사 기록 저장소 · 기록기 · 삭제 참가자 통합 시험 (U13 FR6 · BR8.1 ~ BR8.3, realtime V6). 쓰기와 같은 id 덮어쓰기, 트랜잭션 강제, 외래 키
 * 위반 폐기, 보호자 기준 삭제(세션 삭제보다 먼저, 삭제 뒤 0건, 늦은 기록 차단)를 실제 DB로 본다. 제브는 부르지 않는다(테스트 대역 증거).
 */
@Tag("jev-guardrail")
@Tag("db")
class GuardrailEventRepositoryIT {

    private static RealtimeDb db;
    private GuardrailEventRepository repository;
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
        repository = new GuardrailEventRepository(db.jdbc());
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

    private EventLogger quietEvents() {
        return TestEventLoggers.strict(new MutableClock(), LogCapture.detached(), RealtimeMetrics.inMemory());
    }

    private GuardrailEventDeletionParticipant participant(GuardrailEventRecorder recorder) {
        return new GuardrailEventDeletionParticipant(repository, recorder, new DbRetry(0, new Random(1), ignored -> { }),
                db.transactions(), Duration.ofSeconds(1), quietEvents());
    }

    @Test
    void aRecordRoundTripsAndTheSameIdOverwritesWithoutAddingARow() {
        UUID id = UUID.randomUUID();
        GuardrailEventRecord first = blocked(id, guardian, session, GuardrailEventRecord.Decision.TIMEOUT, "GUARDRAIL_TIMEOUT");

        db.inTransaction(() -> repository.upsert(first));
        assertThat(repository.find(id)).contains(first);

        GuardrailEventRecord second = allow(id, guardian, session);
        db.inTransaction(() -> repository.upsert(second));

        assertThat(repository.find(id)).contains(second);
        assertThat(repository.countByGuardian(guardian)).isEqualTo(1L);
    }

    @Test
    void theSkippedVerbatimRowKeepsItsSourceKindAndZeroLatency() {
        UUID id = UUID.randomUUID();

        db.inTransaction(() -> repository.upsert(skipped(id, guardian, session)));

        GuardrailEventRecord stored = repository.find(id).orElseThrow();
        assertThat(stored.sourceKind()).isEqualTo(GuardrailEventRecord.SourceKind.APPROVED_VERBATIM_SKIPPED);
        assertThat(stored.latencyMs()).isZero();
    }

    @Test
    void theWriteRequiresAnActiveTransactionAndAnUnknownSessionIsRefusedByTheForeignKey() {
        GuardrailEventRecord record = allow(UUID.randomUUID(), guardian, session);
        assertThatThrownBy(() -> repository.upsert(record)).isInstanceOf(IllegalTransactionStateException.class);

        GuardrailEventRecord orphan = allow(UUID.randomUUID(), guardian, UUID.randomUUID());
        assertThatThrownBy(() -> db.inTransaction(() -> repository.upsert(orphan))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(GuardrailEventRecorder.isPermanent(catchWrite(orphan))).isTrue();
    }

    private Throwable catchWrite(GuardrailEventRecord record) {
        try {
            db.inTransaction(() -> repository.upsert(record));
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    @Test
    void theRecorderWritesThroughTheJdbcStoreAndDropsARecordWhoseSessionIsGone() {
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(
                new GuardrailEventRecorder.JdbcStore(repository, db.transactions()), TaskLauncher.inline(), quietEvents());
        UUID good = UUID.randomUUID();

        recorder.put(allow(good, guardian, session));
        recorder.put(allow(UUID.randomUUID(), guardian, UUID.randomUUID()));

        assertThat(repository.find(good)).isPresent();
        assertThat(repository.countByGuardian(guardian)).isEqualTo(1L);
        assertThat(recorder.held()).as("외래 키 위반은 버린다").isZero();
    }

    @Test
    void theDeletionParticipantRunsBetweenJudgmentAndSessionAndLetsTheSessionRowGo() {
        UUID otherGuardian = UUID.randomUUID();
        UUID otherSession = insertSession(otherGuardian);
        db.inTransaction(() -> {
            repository.upsert(allow(UUID.randomUUID(), guardian, session));
            repository.upsert(blocked(UUID.randomUUID(), guardian, session, GuardrailEventRecord.Decision.BLOCK, "GUARDRAIL_BLOCK"));
            repository.upsert(allow(UUID.randomUUID(), otherGuardian, otherSession));
        });
        assertThatThrownBy(() -> db.inTransaction(() -> db.jdbc().update("delete from realtime.realtime_activity_session where id = :id",
                new MapSqlParameterSource("id", session))))
                .as("검사 기록이 세션 행을 붙잡는다").isInstanceOf(DataIntegrityViolationException.class);
        GuardrailEventDeletionParticipant participant = participant(
                new GuardrailEventRecorder(record -> { }, TaskLauncher.inline(), quietEvents()));

        assertThat(GuardrailEventDeletionParticipant.ORDER_GUARDRAIL)
                .isGreaterThan(RealtimeDeletionParticipant.ORDER_PRACTICE)
                .isLessThan(RealtimeDeletionParticipant.ORDER_SESSION);
        assertThat(participant.order()).isEqualTo(185);
        Map<String, Integer> counts = participant.deleteByGuardian(guardian, UUID.randomUUID());

        assertThat(counts).containsExactlyEntriesOf(Map.of(GuardrailEventDeletionParticipant.GUARDRAIL_EVENT, 2));
        assertThat(repository.countByGuardian(guardian)).as("삭제 뒤 0건").isZero();
        assertThat(repository.countByGuardian(otherGuardian)).as("다른 보호자는 그대로").isEqualTo(1L);
        assertThat(db.inTransaction(() -> db.jdbc().update("delete from realtime.realtime_activity_session where id = :id",
                new MapSqlParameterSource("id", session)))).isEqualTo(1);
        assertThat(participant.deleteByGuardian(guardian, UUID.randomUUID()).values()).as("다시 불러도 같다").containsOnly(0);
    }

    @Test
    void aRecordArrivingAfterTheGuardianIsBlockedIsNeverWritten() {
        GuardrailEventRecorder recorder = new GuardrailEventRecorder(
                new GuardrailEventRecorder.JdbcStore(repository, db.transactions()), TaskLauncher.inline(), quietEvents());
        GuardrailEventDeletionParticipant participant = participant(recorder);

        participant.deleteByGuardian(guardian, UUID.randomUUID());
        boolean accepted = recorder.put(allow(UUID.randomUUID(), guardian, session));

        assertThat(accepted).isFalse();
        assertThat(repository.countByGuardian(guardian)).isZero();
    }
}
