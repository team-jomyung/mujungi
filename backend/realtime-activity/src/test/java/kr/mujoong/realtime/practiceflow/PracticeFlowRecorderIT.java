package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.ConsentAnswer;
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

/**
 * 운영 기록기 통합 시험 (U12 FR4.1 · FR4.2 · BR7.2 · BR7.3, realtime V7). 상태 기계가 남기는 장면 · 사실 · 도움이 V7 표에 쓰이고, 정산 값이 같은
 * 값에서 조립되어 {@code practice-record-v2} 스키마를 지키며, 표 쓰기가 실패해도 정산 값은 메모리에서 비지 않는지를 실제 DB로 본다. 증거: 실제
 * DB(Testcontainers), AI는 부르지 않는다.
 */
@Tag("practice-flow")
@Tag("db")
class PracticeFlowRecorderIT {

    private static final Instant T0 = Instant.parse("2026-10-18T09:00:00Z");

    private static RealtimeDb db;
    private PracticeFlowRepository repository;
    private PracticeFlowRecorder recorder;
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
        recorder = new PracticeFlowRecorder(repository, db.transactions());
        guardian = UUID.randomUUID();
        session = insertSession(guardian);
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    private UUID insertSession(UUID guardianId) {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession row = new NewActivitySession(UUID.randomUUID(), guardianId, UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.PRACTICE, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("하늘", 8, List.of("공룡")), null, null);
        assertThat(db.inTransaction(() -> sessions.insert(row, T0))).isTrue();
        return row.id();
    }

    private SceneRunRecord started(UUID sessionId, SceneKind kind, int offsetSeconds) {
        return SceneRunRecord.started(UUID.randomUUID(), sessionId, guardian, UUID.randomUUID(), 0, UUID.randomUUID(), kind,
                T0.plusSeconds(offsetSeconds));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWholeSceneIsWrittenToTheTablesAndTheSettlementValueIsBuiltFromTheSameRowsAndFollowsTheContract() {
        SceneRunRecord base = started(session, SceneKind.BASE, 0);
        recorder.sceneStarted(base);
        recorder.fact(base.id(), FactKind.REASK_RESOLVED, T0.plusSeconds(4));
        recorder.help(new HelpRecord(UUID.randomUUID(), base.id(), guardian, HelpKind.ROLE_SWITCH, FlowRole.COACH, false,
                ConsentAnswer.ACCEPT, 18, T0.plusSeconds(5)));
        recorder.fact(base.id(), FactKind.VARIANT_SKIPPED, T0.plusSeconds(6));
        recorder.sceneOutcome(base.id(), T0.plusSeconds(6), LiveTiming.AFTER_REASK, VariantSkipReason.NO_VARIANT, 1, 0);

        List<SceneRunRecord> stored = repository.findSceneRuns(session);
        assertThat(stored).singleElement().satisfies(row -> {
            assertThat(row.liveTiming()).isEqualTo(LiveTiming.AFTER_REASK);
            assertThat(row.variantSkipReason()).isEqualTo(VariantSkipReason.NO_VARIANT);
            assertThat(row.retryCount()).isEqualTo(1);
            assertThat(row.facts()).extracting(PracticeFlowTypes.Fact::kind)
                    .containsExactly(FactKind.REASK_RESOLVED, FactKind.VARIANT_SKIPPED);
        });
        assertThat(repository.findHelps(session)).singleElement().satisfies(help -> {
            assertThat(help.contentChars()).isEqualTo(18);
            assertThat(help.kind()).isEqualTo(HelpKind.ROLE_SWITCH);
        });

        Object settled = recorder.practiceRecord();
        assertThat(settled).isEqualTo(PracticeFlowRecordAssembler.assemble(stored));
        Map<String, Object> practice = (Map<String, Object>) settled;
        assertThat(practice).containsEntry("flowVersion", "practice-conversation-v1");
        assertThat(InternalApi.schemaErrors("practice-record-v2.schema.json", JsonCodec.shared().write(practice))).isEmpty();
    }

    @Test
    void aFactOrHelpForASceneNeverStartedIsDroppedWithoutWritingAnything() {
        UUID unknown = UUID.randomUUID();

        recorder.fact(unknown, FactKind.TIMEOUT, T0);
        recorder.help(new HelpRecord(UUID.randomUUID(), unknown, guardian, HelpKind.FOCUS_HELP, FlowRole.COACH, false, null, 3, T0));
        recorder.sceneOutcome(unknown, T0, LiveTiming.NONE, null, 0, 0);

        assertThat(repository.findSceneRuns(session)).isEmpty();
        assertThat(repository.findHelps(session)).isEmpty();
        assertThat(recorder.practiceRecord()).isEqualTo(PracticeFlowRecordAssembler.assemble(List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void whenTheTableWriteFailsTheErrorSurfacesButTheSettlementValueStillHoldsTheScene() {
        SceneRunRecord orphan = started(UUID.randomUUID(), SceneKind.BASE, 0);

        assertThatThrownBy(() -> recorder.sceneStarted(orphan)).isInstanceOf(DataIntegrityViolationException.class);

        List<Map<String, Object>> scenes = (List<Map<String, Object>>) ((Map<String, Object>) recorder.practiceRecord()).get("scenes");
        assertThat(scenes).singleElement().satisfies(scene -> assertThat(scene).containsEntry("sceneRunId", orphan.id()));
        assertThat(repository.findSceneRuns(orphan.sessionId())).isEmpty();
    }

    @Test
    void theRecoveryReadsTheSameValueTheRecorderSettledWithAndSkipsASessionWithoutNewFlowRows() {
        SceneRunRecord base = started(session, SceneKind.BASE, 0);
        recorder.sceneStarted(base);
        recorder.fact(base.id(), FactKind.TIMEOUT, T0.plusSeconds(10));
        recorder.sceneOutcome(base.id(), T0.plusSeconds(10), LiveTiming.NONE, VariantSkipReason.END_OR_REST, 0, 0);
        PracticeFlowRecordRecovery recovery = new PracticeFlowRecordRecovery(repository);

        assertThat(recovery.recover(session)).contains(recorder.practiceRecord());
        assertThat(recovery.recover(insertSession(UUID.randomUUID()))).isEmpty();
    }
}
