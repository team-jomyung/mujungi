package kr.mujoong.core.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** core_ai_usage 저장 · 중복 방지 · 계정 삭제를 core_app 역할로 확인한다. 행은 합성 값이다. */
@Tag("ai-usage")
@Tag("db")
class AiUsageRepositoryIT {

    private static final AiUsageProperties.Timing TIMING =
            new AiUsageProperties.Timing(Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(1));

    private DbTestSupport db;
    private MutableClock clock;
    private AiUsageRepository repository;
    private DataSourceTransactionManager transactions;
    private AiUsageRecorder recorder;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        clock = new MutableClock(DbTestSupport.EPOCH);
        repository = new AiUsageRepository(db.jdbc());
        transactions = new DataSourceTransactionManager(db.dataSource());
        recorder = new AiUsageRecorder(repository, transactions, clock, new AiUsageProperties(true), TIMING,
                new AiUsageProperties.Limits(10, 10), CoreMetrics.inMemory());
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private AiUsageRecord record(AiUsageLink link, String responseId, Long input, Long output) {
        AiUsageRecord.Status status = input != null && output != null ? AiUsageRecord.Status.COMPLETE
                : input != null || output != null ? AiUsageRecord.Status.PARTIAL : AiUsageRecord.Status.UNKNOWN;
        return new AiUsageRecord(UUID.randomUUID(), UUID.randomUUID(), 1, "GOAL_CANDIDATES", link, "goal-candidates.v1",
                "gpt-6-luna", responseId, "OK", input, output, null, null, null, status, clock.now());
    }

    private Map<String, Object> row(UUID id) {
        return db.jdbc().queryForMap("select * from core.core_ai_usage where id = :id", new MapSqlParameterSource("id", id));
    }

    private int rows(UUID guardian) {
        Integer count = db.jdbc().queryForObject("select count(*) from core.core_ai_usage where guardian_id = :g",
                new MapSqlParameterSource("g", guardian), Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void aQueuedRecordIsStoredWithItsLinkAndUnknownCountsStayNull() {
        UUID guardian = db.insertGuardian("ACTIVE");
        AiUsageLink link = new AiUsageLink(guardian, UUID.randomUUID());
        AiUsageRecord complete = record(link, "resp_it_" + UUID.randomUUID(), 1000L, 50L);
        AiUsageRecord unknown = record(link, null, null, null);

        recorder.offer(complete);
        recorder.offer(unknown);
        recorder.flushOnce();

        assertThat(row(complete.id())).containsEntry("guardian_id", guardian).containsEntry("session_id", link.sessionId())
                .containsEntry("purpose", "GOAL_CANDIDATES").containsEntry("input_tokens", 1000L)
                .containsEntry("capture_status", "COMPLETE");
        assertThat(row(unknown.id())).containsEntry("input_tokens", null).containsEntry("output_tokens", null)
                .containsEntry("capture_status", "UNKNOWN");
    }

    @Test
    void cacheReadAndCacheWriteTokensAreStoredSeparately() {
        UUID guardian = db.insertGuardian("ACTIVE");
        AiUsageRecord cached = new AiUsageRecord(UUID.randomUUID(), UUID.randomUUID(), 1, "SCENARIO_GENERATION",
                new AiUsageLink(guardian, UUID.randomUUID()), "scenario-generation.v2", "gpt-6-luna", null, "OK",
                12000L, 300L, 4000L, 7000L, null, AiUsageRecord.Status.COMPLETE, clock.now());

        recorder.offer(cached);
        recorder.flushOnce();

        assertThat(row(cached.id())).containsEntry("input_tokens", 12000L).containsEntry("cached_input_tokens", 4000L)
                .containsEntry("cache_write_tokens", 7000L);
    }

    @Test
    void theTableRejectsCacheReadAndWriteTogetherLargerThanTheInput() {
        UUID guardian = db.insertGuardian("ACTIVE");
        MapSqlParameterSource bad = new MapSqlParameterSource("id", UUID.randomUUID()).addValue("call", UUID.randomUUID())
                .addValue("guardian", guardian).addValue("at", java.sql.Timestamp.from(clock.now()));

        assertThatThrownBy(() -> db.jdbc().update("""
                insert into core.core_ai_usage (id, call_id, attempt_no, purpose, guardian_id, prompt_version, outcome,
                    input_tokens, cached_input_tokens, cache_write_tokens, capture_status, observed_at, created_at)
                values (:id, :call, 1, 'RESULT_TEXT', :guardian, 'v1', 'OK', 100, 60, 50, 'PARTIAL', :at, :at)
                """, bad)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void writingTheSameRecordOrTheSameProviderResponseAgainKeepsOneRow() {
        UUID guardian = db.insertGuardian("ACTIVE");
        AiUsageLink link = new AiUsageLink(guardian, UUID.randomUUID());
        String responseId = "resp_it_" + UUID.randomUUID();
        AiUsageRecord first = record(link, responseId, 10L, 1L);

        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            repository.write(first);
            repository.write(first);
            repository.write(record(link, responseId, 10L, 1L));
        });

        assertThat(rows(guardian)).isEqualTo(1);
    }

    @Test
    void aCallWithoutALinkIsStoredWithoutGuardianOrSession() {
        AiUsageRecord unlinked = record(null, null, 3L, 1L);

        recorder.offer(unlinked);
        recorder.flushOnce();

        try {
            assertThat(row(unlinked.id())).containsEntry("guardian_id", null).containsEntry("session_id", null);
        } finally {
            db.jdbc().update("delete from core.core_ai_usage where id = :id", new MapSqlParameterSource("id", unlinked.id()));
        }
    }

    @Test
    void aRecordForAGuardianThatNoLongerExistsIsDroppedNotRetried() {
        AiUsageRecord orphan = record(new AiUsageLink(UUID.randomUUID(), UUID.randomUUID()), null, 1L, 1L);

        recorder.offer(orphan);
        recorder.flushOnce();

        assertThat(recorder.pendingCount()).isZero();
        assertThat(db.jdbc().queryForObject("select count(*) from core.core_ai_usage where id = :id",
                new MapSqlParameterSource("id", orphan.id()), Integer.class)).isZero();
    }

    @Test
    void theTableRejectsALinkedSessionWithoutAGuardianAndCountsBelowZero() {
        UUID guardian = db.insertGuardian("ACTIVE");
        MapSqlParameterSource bad = new MapSqlParameterSource("id", UUID.randomUUID()).addValue("call", UUID.randomUUID())
                .addValue("session", UUID.randomUUID()).addValue("guardian", guardian)
                .addValue("at", java.sql.Timestamp.from(clock.now()));

        assertThatThrownBy(() -> db.jdbc().update("""
                insert into core.core_ai_usage (id, call_id, attempt_no, purpose, guardian_id, session_id, prompt_version, outcome,
                    capture_status, observed_at, created_at)
                values (:id, :call, 1, 'RESULT_TEXT', null, :session, 'v1', 'OK', 'UNKNOWN', :at, :at)
                """, bad)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.jdbc().update("""
                insert into core.core_ai_usage (id, call_id, attempt_no, purpose, guardian_id, session_id, prompt_version, outcome,
                    input_tokens, capture_status, observed_at, created_at)
                values (:id, :call, 1, 'RESULT_TEXT', :guardian, :session, 'v1', 'OK', -1, 'PARTIAL', :at, :at)
                """, bad)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountDeletionRemovesOnlyThatGuardiansRowsAndQueuedRecords() {
        UUID deleted = db.insertGuardian("DELETION_REQUESTED");
        UUID kept = db.insertGuardian("ACTIVE");
        recorder.offer(record(new AiUsageLink(deleted, UUID.randomUUID()), null, 1L, 1L));
        recorder.offer(record(new AiUsageLink(kept, UUID.randomUUID()), null, 1L, 1L));
        recorder.flushOnce();
        AiUsageRecord queued = record(new AiUsageLink(deleted, UUID.randomUUID()), null, 2L, 2L);
        recorder.offer(queued);
        AiUsageDeletionParticipant participant = new AiUsageDeletionParticipant(recorder, repository, transactions, TIMING);

        Map<String, Integer> first = participant.deleteByGuardian(deleted, UUID.randomUUID());
        Map<String, Integer> again = participant.deleteByGuardian(deleted, UUID.randomUUID());
        recorder.flushOnce();

        assertThat(first).containsEntry("core_ai_usage", 1);
        assertThat(again).as("멱등").containsEntry("core_ai_usage", 0);
        assertThat(rows(deleted)).as("대기 기록도 저장되지 않는다").isZero();
        assertThat(rows(kept)).isEqualTo(1);
    }
}
