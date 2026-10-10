package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.common.db.SqlErrors;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 세션 종료 표현 · 세션 기록 데이터 접근 통합 시험(U1 3.5 Step 18).
 *
 * <p>증명: BR6.6 ④(세션 행 · 기본 종료 표현을 한 트랜잭션에서 쓰고 함께 되돌려짐) · BR6.5(기본 표현 '무중아 그만할래' is_default 한 행) ·
 * BR6.9(끝내기 requestId를 op_id에) · PD-5(기록 행은 배치로 추가만) · 외래 키 RESTRICT(세션 없는 기록 거부).
 */
@Tag("u1")
@Tag("db")
class SessionRecordsRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final String DEFAULT_STOP = "무중아 그만할래";

    private static RealtimeDb db;
    private ActivitySessionRepository sessions;
    private SessionStopExpressionRepository stopExpressions;
    private SessionEventRepository events;

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
        sessions = new ActivitySessionRepository(db.jdbc());
        stopExpressions = new SessionStopExpressionRepository(db.jdbc());
        events = new SessionEventRepository(db.jdbc());
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void sessionStartWritesTheDefaultStopExpressionInTheSameTransaction() {
        NewActivitySession session = newSession();
        db.inTransaction(() -> {
            assertThat(sessions.insert(session, T0)).isTrue();
            stopExpressions.insertAll(List.of(
                    new SessionStopExpression(UUID.randomUUID(), session.id(), "그만할래", false, 1, T0),
                    new SessionStopExpression(UUID.randomUUID(), session.id(), DEFAULT_STOP, true, 0, T0)));
        });

        List<SessionStopExpression> stored = stopExpressions.findBySession(session.id());
        assertThat(stored).extracting(SessionStopExpression::getExpressionText).containsExactly(DEFAULT_STOP, "그만할래");
        assertThat(stored.get(0).isDefaultExpression()).isTrue();
        assertThat(stored.get(0).getSortOrder()).isZero();
        assertThat(stored.get(0).getCreatedAt()).isEqualTo(T0);
        assertThat(stored.get(1).isDefaultExpression()).isFalse();
        assertThat(stopExpressions.findBySession(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aRolledBackStartLeavesNeitherTheSessionNorItsStopExpression() {
        NewActivitySession session = newSession();
        db.rolledBack(() -> {
            sessions.insert(session, T0);
            stopExpressions.insertAll(List.of(defaultStop(session.id())));
        });

        assertThat(sessions.find(session.id())).isEmpty();
        assertThat(stopExpressions.findBySession(session.id())).isEmpty();
        // 빈 목록은 아무것도 하지 않는다
        stopExpressions.insertAll(List.of());
        events.insertAll(List.of());
        assertThat(events.findBySession(session.id())).isEmpty();
    }

    @Test
    void recordsWithoutASessionRowAreRejectedByTheForeignKey() {
        UUID unknownSession = UUID.randomUUID();

        assertThatThrownBy(() -> stopExpressions.insertAll(List.of(defaultStop(unknownSession))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(SqlErrors.isForeignKeyViolation(error)).isTrue());
        assertThatThrownBy(() -> events.insert(new SessionEvent(UUID.randomUUID(), unknownSession, "STATE", null, null, T0)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(SqlErrors.sqlState(error)).contains(SqlErrors.FOREIGN_KEY_VIOLATION));
    }

    @Test
    void sessionEventsAreAppendedSinglyOrInBatchesAndReadInTimeOrder() {
        NewActivitySession session = insertSession();
        String requestId = UUID.randomUUID().toString();
        events.insert(new SessionEvent(UUID.randomUUID(), session.id(), "END_ACCEPTED", requestId,
                "{\"reason\":\"GUARDIAN_STOP\"}", T0.plusSeconds(30)));
        events.insertAll(List.of(
                new SessionEvent(UUID.randomUUID(), session.id(), "TRANSITION_LOST", null,
                        "{\"from\":\"PREPARING\",\"to\":\"FAILED\"}", T0.plusSeconds(20)),
                new SessionEvent(UUID.randomUUID(), session.id(), "VOICE_SESSION_CLOSED_LATE", null, null,
                        T0.plusSeconds(40))));

        List<SessionEvent> stored = events.findBySession(session.id());
        assertThat(stored).extracting(SessionEvent::getEventType)
                .containsExactly("TRANSITION_LOST", "END_ACCEPTED", "VOICE_SESSION_CLOSED_LATE");
        assertThat(stored.get(1).getOpId()).isEqualTo(requestId);
        assertThat(stored.get(1).getPayloadJson()).contains("GUARDIAN_STOP");
        assertThat(stored.get(2).getPayloadJson()).isNull();
        assertThat(stored.get(2).getOpId()).isNull();
        assertThat(stored.get(0).getCreatedAt()).isEqualTo(T0.plusSeconds(20));
    }

    @Test
    void aBatchWithOneBadRecordWritesNothingInsideItsTransaction() {
        NewActivitySession session = insertSession();
        List<SessionEvent> batch = List.of(
                new SessionEvent(UUID.randomUUID(), session.id(), "OK", null, null, T0),
                new SessionEvent(UUID.randomUUID(), UUID.randomUUID(), "ORPHAN", null, null, T0));

        assertThatThrownBy(() -> db.inTransaction(() -> events.insertAll(batch)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.findBySession(session.id())).isEmpty();
    }

    private NewActivitySession insertSession() {
        NewActivitySession session = newSession();
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();
        return session;
    }

    private static SessionStopExpression defaultStop(UUID sessionId) {
        return new SessionStopExpression(UUID.randomUUID(), sessionId, DEFAULT_STOP, true, 0, T0);
    }

    private static NewActivitySession newSession() {
        return new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("하늘", 8, List.of("공룡")), UUID.randomUUID(), null);
    }
}
