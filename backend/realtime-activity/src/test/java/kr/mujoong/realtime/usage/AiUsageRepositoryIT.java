package kr.mujoong.realtime.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

@Tag("ai-usage")
@Tag("db")
class AiUsageRepositoryIT {
    private static RealtimeDb db;
    private AiUsageRepository repository;
    private UUID guardian, session;
    private static final Instant AT = Instant.parse("2026-10-23T00:00:00Z");

    @BeforeAll static void open() { db = RealtimeDb.open(); }
    @AfterAll static void close() { if (db != null) { db.wipe(); db.close(); } }
    @BeforeEach void prepare() {
        db.wipe();
        repository = new AiUsageRepository(db.jdbc());
        guardian = UUID.randomUUID();
        var row = new NewActivitySession(UUID.randomUUID(), guardian, UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("합성", 8, List.of()), null, null);
        db.inTransaction(() -> new ActivitySessionRepository(db.jdbc()).insert(row, AT));
        session = row.id();
    }

    private AiUsageRecord voice(UUID id, long revision, Double seconds, AiUsageRecord.Status status) {
        return new AiUsageRecord(id, AiUsageRecord.Source.VOICE, session, null, "synthetic-session", null,
                "synthetic-model", null, null, null, null, seconds, status, revision, AT);
    }

    private Map<String, Object> saved(UUID id) {
        return db.jdbc().queryForMap("select * from realtime.realtime_ai_usage where id=:id", Map.of("id", id));
    }

    @Test void writesRequireATransactionAndUnknownIsStoredAsNullInsteadOfZero() {
        var r = voice(UUID.randomUUID(), 1, null, AiUsageRecord.Status.UNKNOWN);
        assertThatThrownBy(() -> repository.write(r, id -> true)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(db.inTransaction(() -> repository.write(r, id -> true))).isEqualTo(AiUsageRepository.Result.STORED);
        assertThat(saved(r.id()).get("voice_seconds")).isNull();
        assertThat(saved(r.id()).get("capture_status")).isEqualTo("UNKNOWN");
    }

    @Test void snapshotsAreIdempotentAndAStalePartialCannotOverwriteFinalUsage() {
        UUID id = UUID.randomUUID();
        db.inTransaction(() -> repository.write(voice(id, 1, 5.0, AiUsageRecord.Status.PARTIAL), s -> true));
        db.inTransaction(() -> repository.write(voice(id, 3, 12.5, AiUsageRecord.Status.COMPLETE), s -> true));
        db.inTransaction(() -> repository.write(voice(id, 2, 9.0, AiUsageRecord.Status.PARTIAL), s -> true));
        assertThat(saved(id).get("voice_seconds")).isEqualTo(12.5);
        assertThat(saved(id).get("capture_status")).isEqualTo("COMPLETE");
        assertThat(db.jdbc().queryForObject("select count(*) from realtime.realtime_ai_usage", Map.of(), Long.class)).isEqualTo(1);
    }

    @Test void lunaWaitsForTheExistingJudgmentRecordThenLinksWithoutChangingTheBusinessTables() {
        UUID turn = UUID.randomUUID();
        var r = new AiUsageRecord(UUID.randomUUID(), AiUsageRecord.Source.LUNA, null, turn, null, "synthetic-response",
                "synthetic-model", 100L, 20L, null, null, null, AiUsageRecord.Status.COMPLETE, 1, AT);
        assertThat(db.inTransaction(() -> repository.write(r, s -> true))).isEqualTo(AiUsageRepository.Result.UNLINKED);
        db.jdbc().update("""
                insert into realtime.realtime_judgment_live
                  (id,guardian_id,session_id,judgment_point,trigger_kind,input_transcript_seq,
                   option_set_version,prompt_version,outcome,abstain_reason,turn_opened,latency_millis,next_action,created_at)
                values (:id,:guardian,:session,'J1','JOIN_UNCLEAR',0,'synthetic','synthetic',
                        'ABSTAINED','TIMEOUT',true,1,'NONE',:at)
                """, Map.of("id", turn, "guardian", guardian, "session", session, "at", java.time.OffsetDateTime.ofInstant(AT, java.time.ZoneOffset.UTC)));
        assertThat(db.inTransaction(() -> repository.write(r, s -> true))).isEqualTo(AiUsageRepository.Result.STORED);
        assertThat(saved(r.id()).get("session_id")).isEqualTo(session);
        var duplicate = new AiUsageRecord(UUID.randomUUID(), r.source(), null, turn, null, r.responseId(), r.model(),
                100L, 20L, null, null, null, r.status(), 1, AT);
        db.inTransaction(() -> repository.write(duplicate, s -> true));
        assertThat(db.jdbc().queryForObject("select count(*) from realtime.realtime_ai_usage", Map.of(), Long.class)).isEqualTo(1);
    }

    @Test void deletedOrBlockedSessionsCannotBeRecreatedByLateUsage() {
        var r = voice(UUID.randomUUID(), 1, 3.0, AiUsageRecord.Status.PARTIAL);
        assertThat(db.inTransaction(() -> repository.write(r, id -> false))).isEqualTo(AiUsageRepository.Result.DISCARDED);
        db.inTransaction(() -> repository.write(r, id -> true));
        assertThat(db.inTransaction(() -> repository.deleteByGuardian(guardian))).isEqualTo(1);
        db.jdbc().update("delete from realtime.realtime_activity_session where id=:id", Map.of("id", session));
        assertThat(db.inTransaction(() -> repository.write(r, id -> true))).isEqualTo(AiUsageRepository.Result.DISCARDED);
    }
}
