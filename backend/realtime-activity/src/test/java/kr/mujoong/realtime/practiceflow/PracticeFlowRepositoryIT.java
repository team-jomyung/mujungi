package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.Fact;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FlowRole;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.testing.InternalApi;
import kr.mujoong.realtime.testing.RealtimeDb;
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
 * 새 연습 흐름 저장소 · 삭제 참가자 · 정산 복구 통합 시험 (U12 FR4.1 · BR7.1 · BR7.3 · BR7.4, realtime V7). 장면 실행 쓰기 · 사실 쌓기 · 결과 덮어쓰기 ·
 * 도움 기록, 트랜잭션 강제, 보호자 기준 삭제(세션 삭제보다 먼저 · 다시 불러도 같다 · 다른 보호자 유지), 복구 조립이 {@code practice-record-v2}
 * 스키마를 지키는지를 실제 DB로 본다. 원문 칸이 없다는 것은 {@code PracticeFlowSchemaIT}가 본다. 증거: 실제 DB(Testcontainers), AI는 부르지 않는다.
 */
@Tag("practice-flow")
@Tag("db")
class PracticeFlowRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-18T09:00:00Z");

    private static RealtimeDb db;
    private PracticeFlowRepository repository;
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
        repository = new PracticeFlowRepository(db.jdbc());
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
                ActivityType.PRACTICE, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("하늘", 8, List.of("공룡")), null, null);
        assertThat(db.inTransaction(() -> sessions.insert(row, T0))).isTrue();
        return row.id();
    }

    private SceneRunRecord run(UUID sessionId, UUID guardianId, SceneKind kind, int offsetSeconds) {
        return SceneRunRecord.started(UUID.randomUUID(), sessionId, guardianId, UUID.randomUUID(), 0, UUID.randomUUID(), kind,
                T0.plusSeconds(offsetSeconds));
    }

    private HelpRecord help(UUID runId, UUID guardianId, HelpKind kind, FlowRole role, int chars, int offsetSeconds) {
        return new HelpRecord(UUID.randomUUID(), runId, guardianId, kind, role, kind == HelpKind.EXPRESSION_EXAMPLE,
                ConsentAnswer.ACCEPT, chars, T0.plusSeconds(offsetSeconds));
    }

    private PracticeFlowDeletionParticipant participant() {
        return new PracticeFlowDeletionParticipant(repository, new DbRetry(0, new Random(1), ignored -> { }), db.transactions());
    }

    @Test
    void aSceneRunRoundTripsWithFactsInTheOrderTheyHappenedAndTheOutcomeOverwrites() {
        SceneRunRecord started = run(session, guardian, SceneKind.BASE, 0);
        db.inTransaction(() -> {
            repository.insertSceneRun(started);
            repository.appendFact(started.id(), FactKind.SITUATION_EXPLAINED, T0.plusSeconds(3));
            repository.appendFact(started.id(), FactKind.REASK_RESOLVED, T0.plusSeconds(9));
            repository.appendFact(started.id(), FactKind.VARIANT_SKIPPED, T0.plusSeconds(12));
            repository.updateOutcome(started.id(), T0.plusSeconds(12), LiveTiming.AFTER_REASK, VariantSkipReason.DECLINED, 0, 1);
        });

        SceneRunRecord read = repository.findSceneRuns(session).getFirst();

        assertThat(read.id()).isEqualTo(started.id());
        assertThat(read.goalRef()).isEqualTo(started.goalRef());
        assertThat(read.scenarioRef()).isEqualTo(started.scenarioRef());
        assertThat(read.sceneKind()).isEqualTo(SceneKind.BASE);
        assertThat(read.startedAt()).isEqualTo(T0);
        assertThat(read.endedAt()).isEqualTo(T0.plusSeconds(12));
        assertThat(read.liveTiming()).isEqualTo(LiveTiming.AFTER_REASK);
        assertThat(read.variantSkipReason()).isEqualTo(VariantSkipReason.DECLINED);
        assertThat(read.retryCount()).isZero();
        assertThat(read.reexplainCount()).isEqualTo(1);
        assertThat(read.facts()).containsExactly(new Fact(FactKind.SITUATION_EXPLAINED, T0.plusSeconds(3)),
                new Fact(FactKind.REASK_RESOLVED, T0.plusSeconds(9)), new Fact(FactKind.VARIANT_SKIPPED, T0.plusSeconds(12)));
        // 같은 값으로 다시 덮어써도 같다(멱등)
        db.inTransaction(() -> repository.updateOutcome(started.id(), T0.plusSeconds(12), LiveTiming.AFTER_REASK,
                VariantSkipReason.DECLINED, 0, 1));
        assertThat(repository.findSceneRuns(session)).containsExactly(read);
    }

    @Test
    void runsComeBackInStartOrderWithTheirOwnFactsAndInsertCarriesInitialFacts() {
        SceneRunRecord later = run(session, guardian, SceneKind.VARIANT, 60);
        SceneRunRecord earlier = new SceneRunRecord(UUID.randomUUID(), session, guardian, UUID.randomUUID(), 1, UUID.randomUUID(),
                SceneKind.BASE, T0, null, null, null, 0, 0, List.of(new Fact(FactKind.TIMEOUT, T0.plusSeconds(10))));
        db.inTransaction(() -> {
            repository.insertSceneRun(later);
            repository.insertSceneRun(earlier);
            repository.appendFact(later.id(), FactKind.VARIANT_PLAYED, T0.plusSeconds(70));
        });

        List<SceneRunRecord> runs = repository.findSceneRuns(session);

        assertThat(runs).extracting(SceneRunRecord::id).containsExactly(earlier.id(), later.id());
        assertThat(runs.get(0).facts()).extracting(Fact::kind).containsExactly(FactKind.TIMEOUT);
        assertThat(runs.get(0).goalIndex()).isEqualTo(1);
        assertThat(runs.get(1).facts()).extracting(Fact::kind).containsExactly(FactKind.VARIANT_PLAYED);
        assertThat(repository.hasSceneRuns(session)).isTrue();
        assertThat(repository.hasSceneRuns(UUID.randomUUID())).isFalse();
        assertThat(repository.findSceneRuns(UUID.randomUUID())).isEmpty();
    }

    @Test
    void everyWriteRequiresAnActiveTransactionAndAMissingRowReportsFalse() {
        SceneRunRecord started = run(session, guardian, SceneKind.BASE, 0);
        assertThatThrownBy(() -> repository.insertSceneRun(started)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.appendFact(started.id(), FactKind.TIMEOUT, T0))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.updateOutcome(started.id(), null, null, null, 0, 0))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.insertHelp(help(started.id(), guardian, HelpKind.ROLE_SWITCH, FlowRole.COACH, 0, 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.deleteByGuardian(guardian)).isInstanceOf(IllegalTransactionStateException.class);

        UUID missing = UUID.randomUUID();
        assertThat(db.inTransaction(() -> repository.appendFact(missing, FactKind.TIMEOUT, T0))).isFalse();
        assertThat(db.inTransaction(() -> repository.updateOutcome(missing, null, null, null, 0, 0))).isFalse();
    }

    @Test
    void anUnknownSessionOrRunIsRefusedByTheForeignKeys() {
        SceneRunRecord orphan = run(UUID.randomUUID(), guardian, SceneKind.BASE, 0);
        assertThatThrownBy(() -> db.inTransaction(() -> repository.insertSceneRun(orphan)))
                .isInstanceOf(DataIntegrityViolationException.class);
        HelpRecord orphanHelp = help(UUID.randomUUID(), guardian, HelpKind.FOCUS_HELP, FlowRole.COACH, 4, 1);
        assertThatThrownBy(() -> db.inTransaction(() -> repository.insertHelp(orphanHelp)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void helpRecordsStoreCodesAndLengthOnlyAndComeBackInOrder() {
        SceneRunRecord started = run(session, guardian, SceneKind.BASE, 0);
        HelpRecord example = help(started.id(), guardian, HelpKind.EXPRESSION_EXAMPLE, FlowRole.COACH, 21, 20);
        HelpRecord switched = new HelpRecord(UUID.randomUUID(), started.id(), guardian, HelpKind.ROLE_SWITCH, FlowRole.COACH, false,
                null, 0, T0.plusSeconds(10));
        db.inTransaction(() -> {
            repository.insertSceneRun(started);
            repository.insertHelp(example);
            repository.insertHelp(switched);
        });

        List<HelpRecord> helps = repository.findHelps(session);

        assertThat(helps).containsExactly(switched, example);
        assertThat(helps.get(1).contentChars()).isEqualTo(21);
        assertThat(helps.get(1).exampleGiven()).isTrue();
        assertThat(helps.get(1).consentAnswer()).isEqualTo(ConsentAnswer.ACCEPT);
        assertThat(helps.get(0).consentAnswer()).isNull();
    }

    @Test
    void theDeletionParticipantRunsBeforeTheSessionAndLetsTheSessionRowGo() {
        UUID otherGuardian = UUID.randomUUID();
        UUID otherSession = insertSession(otherGuardian);
        SceneRunRecord mine = run(session, guardian, SceneKind.BASE, 0);
        SceneRunRecord mineVariant = run(session, guardian, SceneKind.VARIANT, 30);
        SceneRunRecord theirs = run(otherSession, otherGuardian, SceneKind.BASE, 0);
        db.inTransaction(() -> {
            repository.insertSceneRun(mine);
            repository.insertSceneRun(mineVariant);
            repository.insertSceneRun(theirs);
            repository.insertHelp(help(mine.id(), guardian, HelpKind.FOCUS_HELP, FlowRole.COACH, 8, 5));
            repository.insertHelp(help(theirs.id(), otherGuardian, HelpKind.FOCUS_HELP, FlowRole.COACH, 8, 5));
        });
        assertThatThrownBy(() -> db.inTransaction(() -> db.jdbc().update("delete from realtime.realtime_activity_session"
                + " where id = :id", new MapSqlParameterSource("id", session))))
                .as("장면 실행이 세션 행을 붙잡는다").isInstanceOf(DataIntegrityViolationException.class);
        PracticeFlowDeletionParticipant participant = participant();

        assertThat(PracticeFlowDeletionParticipant.ORDER_PRACTICE_FLOW).isEqualTo(190)
                .isGreaterThan(RealtimeDeletionParticipant.ORDER_PRACTICE)
                .isLessThan(RealtimeDeletionParticipant.ORDER_SESSION);
        assertThat(participant.order()).isEqualTo(190);
        Map<String, Integer> counts = participant.deleteByGuardian(guardian, UUID.randomUUID());

        assertThat(counts).containsEntry(PracticeFlowDeletionParticipant.PRACTICE_HELP, 1)
                .containsEntry(PracticeFlowDeletionParticipant.PRACTICE_SCENE_RUN, 2).hasSize(2);
        assertThat(repository.findSceneRuns(session)).as("삭제 뒤 0건").isEmpty();
        assertThat(repository.findHelps(session)).isEmpty();
        assertThat(repository.findSceneRuns(otherSession)).as("다른 보호자는 그대로").hasSize(1);
        assertThat(repository.findHelps(otherSession)).hasSize(1);
        assertThat(db.inTransaction(() -> db.jdbc().update("delete from realtime.realtime_activity_session where id = :id",
                new MapSqlParameterSource("id", session)))).isEqualTo(1);
        assertThat(participant.deleteByGuardian(guardian, UUID.randomUUID()).values()).as("다시 불러도 같다").containsOnly(0);
    }

    @Test
    void theRecoveryRebuildsPracticeFromTheTablesAndKeepsTheContractShape() {
        SceneRunRecord started = run(session, guardian, SceneKind.BASE, 0);
        db.inTransaction(() -> {
            repository.insertSceneRun(started);
            repository.appendFact(started.id(), FactKind.HELP_DECLINED, T0.plusSeconds(8));
            repository.appendFact(started.id(), FactKind.VARIANT_SKIPPED, T0.plusSeconds(9));
            repository.updateOutcome(started.id(), T0.plusSeconds(9), LiveTiming.NONE, VariantSkipReason.DECLINED, 0, 0);
        });
        PracticeFlowRecordRecovery recovery = new PracticeFlowRecordRecovery(repository);

        Object practice = recovery.recover(session).orElseThrow();
        String json = JsonCodec.shared().write(practice);

        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", json)).isEmpty();
        assertThat(json).contains("\"flowVersion\":\"practice-conversation-v1\"", "\"HELP_DECLINED\"", "\"timing\":\"NONE\"",
                "\"variantSkipReason\":\"DECLINED\"", started.id().toString());
        assertThat(recovery.recover(UUID.randomUUID())).as("새 흐름 장면이 없으면 레거시로 넘긴다").isEmpty();
    }

    @Test
    void anOutcomeNeverFilledStaysNullAndTheEndedAtMustNotPrecedeTheStart() {
        SceneRunRecord started = run(session, guardian, SceneKind.BASE, 0);
        db.inTransaction(() -> repository.insertSceneRun(started));
        SceneRunRecord read = repository.findSceneRuns(session).getFirst();

        assertThat(read.endedAt()).isNull();
        assertThat(read.liveTiming()).isNull();
        assertThat(read.variantSkipReason()).isNull();
        assertThatThrownBy(() -> db.inTransaction(() -> repository.updateOutcome(started.id(), T0.minus(Duration.ofSeconds(1)),
                null, null, 0, 0))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
