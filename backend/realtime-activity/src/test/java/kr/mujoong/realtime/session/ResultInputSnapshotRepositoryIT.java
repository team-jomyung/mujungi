package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.session.ResultInputSnapshotRepository.NewSnapshot;
import kr.mujoong.realtime.session.ResultInputSnapshotRepository.SnapshotRow;
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
 * 결과 입력 스냅숏 데이터 접근 통합 시험(U4b L1 · FR7.1 · BR8.1 · BR8.4 · BR8.5 · BR9.3 · realtime V3).
 *
 * <p>증명: 세션당 한 판만 쓰고 두 번째 쓰기는 false(멱등) · 쓰기는 트랜잭션 안에서만 · 세션 외래 키(RESTRICT, 스냅숏을 먼저 지워야 세션을
 * 지운다) · 해시 형식 · 판 · 종료 상태 CHECK · 세션 단위 삭제.
 */
@Tag("session-runtime")
@Tag("db")
class ResultInputSnapshotRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final String HASH = "sha256:" + "a".repeat(64);
    private static final String RECORD = "{\"utterances\":[],\"gaps\":[],\"recordScope\":{\"from\":\"2026-10-15T09:00:00Z\","
            + "\"to\":\"2026-10-15T09:05:00Z\"},\"practice\":null,\"audio\":[]}";

    private static RealtimeDb db;
    private ResultInputSnapshotRepository snapshots;
    private ActivitySessionRepository sessions;
    private UUID sessionId;

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
        snapshots = new ResultInputSnapshotRepository(db.jdbc());
        sessions = new ActivitySessionRepository(db.jdbc());
        sessionId = insertSession();
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void aSnapshotIsWrittenOncePerSessionAndReadBack() {
        NewSnapshot snapshot = snapshot(sessionId, HASH);

        assertThat(db.inTransaction(() -> snapshots.insert(snapshot, T0.plusSeconds(301)))).isTrue();

        SnapshotRow row = snapshots.find(sessionId).orElseThrow();
        assertThat(row.id()).isEqualTo(snapshot.id());
        assertThat(row.snapshotVersion()).isEqualTo(ResultInputSnapshotRepository.SNAPSHOT_VERSION);
        assertThat(row.finalStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(row.endReason()).isEqualTo(EndReason.GUARDIAN_STOP);
        assertThat(row.confirmed()).isTrue();
        assertThat(row.policyVersion()).isEqualTo("1.0");
        assertThat(row.cutoffAt()).isEqualTo(T0.plusSeconds(300));
        assertThat(row.capturedAt()).isEqualTo(T0.plusSeconds(301));
        assertThat(row.snapshotHash()).isEqualTo(HASH);
        assertThat(row.recordJson()).contains("\"utterances\"").contains("\"recordScope\"");
        assertThat(row.toString()).as("기록 본문은 문자열 표현에 없다").doesNotContain("utterances");
        assertThat(snapshots.exists(sessionId)).isTrue();
    }

    @Test
    void aSecondWriteForTheSameSessionAndVersionIsIgnored() {
        assertThat(db.inTransaction(() -> snapshots.insert(snapshot(sessionId, HASH), T0))).isTrue();
        NewSnapshot again = snapshot(sessionId, "sha256:" + "b".repeat(64));

        assertThat(db.inTransaction(() -> snapshots.insert(again, T0.plusSeconds(1)))).isFalse();

        assertThat(snapshots.find(sessionId).orElseThrow().snapshotHash()).as("처음 판이 남는다").isEqualTo(HASH);
        assertThat(db.single("select count(*) from realtime.realtime_result_input_snapshot where session_id = :id",
                new MapSqlParameterSource("id", sessionId), Long.class)).isEqualTo(1L);
    }

    @Test
    void writesNeedATransactionAndAnExistingSession() {
        assertThatThrownBy(() -> snapshots.insert(snapshot(sessionId, HASH), T0))
                .isInstanceOf(IllegalTransactionStateException.class);
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> db.inTransaction(() -> snapshots.insert(snapshot(unknown, HASH), T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshots.exists(unknown)).isFalse();
        assertThat(snapshots.find(unknown)).isEmpty();
    }

    @Test
    void theHashFormatAndVersionAreCheckedByTheDatabase() {
        assertThatThrownBy(() -> db.inTransaction(() -> snapshots.insert(snapshot(sessionId, "sha256:XYZ"), T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.inTransaction(() -> db.jdbc().update(
                "insert into realtime.realtime_result_input_snapshot (id, session_id, snapshot_version, final_status,"
                        + " end_reason, confirmed, policy_version, cutoff_at, snapshot_hash, record_json, captured_at,"
                        + " created_at) values (:id, :session, 0, 'ENDED', 'GUARDIAN_STOP', true, '1.0', :t, :hash,"
                        + " '{}'::jsonb, :t, :t)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("session", sessionId).addValue("hash", HASH)
                        .addValue("t", java.time.OffsetDateTime.ofInstant(T0, java.time.ZoneOffset.UTC)))))
                .as("판 0은 거부").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new NewSnapshot(UUID.randomUUID(), sessionId, 1, SessionStatus.ACTIVE,
                EndReason.FAILED, false, "1.0", T0, HASH, RECORD))
                .as("종료 상태가 아니면 만들지 않는다").isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshots.exists(sessionId)).isFalse();
    }

    @Test
    void theSessionRowCannotBeDeletedBeforeItsSnapshotAndSnapshotsAreDeletedPerSession() {
        UUID otherSession = insertSession();
        db.inTransaction(() -> snapshots.insert(snapshot(sessionId, HASH), T0));
        db.inTransaction(() -> snapshots.insert(snapshot(otherSession, HASH), T0));

        assertThatThrownBy(() -> db.inTransaction(() -> db.jdbc().update(
                "delete from realtime.realtime_activity_session where id = :id",
                new MapSqlParameterSource("id", sessionId))))
                .as("FK RESTRICT").isInstanceOf(DataIntegrityViolationException.class);

        assertThat(snapshots.deleteBySessions(List.of())).isZero();
        assertThat(snapshots.deleteBySessions(List.of(sessionId))).isEqualTo(1);
        assertThat(snapshots.deleteBySessions(List.of(sessionId))).isZero();
        assertThat(snapshots.exists(sessionId)).isFalse();
        assertThat(snapshots.exists(otherSession)).isTrue();
        assertThat(db.inTransaction(() -> db.jdbc().update("delete from realtime.realtime_activity_session where id = :id",
                new MapSqlParameterSource("id", sessionId)))).isEqualTo(1);
    }

    private static NewSnapshot snapshot(UUID session, String hash) {
        return new NewSnapshot(UUID.randomUUID(), session, ResultInputSnapshotRepository.SNAPSHOT_VERSION,
                SessionStatus.ENDED, EndReason.GUARDIAN_STOP, true, "1.0", T0.plusSeconds(300), hash, RECORD);
    }

    private UUID insertSession() {
        NewActivitySession session = new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                new ConsentSnapshot("1.0", true, true, false, false), new ChildContext("하늘", 8, List.of("공룡")), null,
                null);
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();
        return session.id();
    }
}
