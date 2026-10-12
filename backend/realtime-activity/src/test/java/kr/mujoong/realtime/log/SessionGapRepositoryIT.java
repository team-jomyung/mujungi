package kr.mujoong.realtime.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.realtime.log.SessionGapRepository.Kind;
import kr.mujoong.realtime.log.SessionGapRepository.SessionGapRow;
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

/**
 * 세션 끊김 구간 데이터 접근 통합 시험(U3 L1 · ConversationLog · U3-R11 · U3-J18).
 *
 * <p>증명: DISCONNECT 구간은 끊긴 시각에 열리고 다시 붙은 시각에 한 번만 닫힌다(멱등) · 시작보다 이른 끝은 거부 · 세션 외래 키 ·
 * 12 조회(gaps)는 시작 순 · 삭제 참가자의 세션 단위 삭제(U3-J16).
 */
@Tag("u3")
@Tag("db")
class SessionGapRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    private static RealtimeDb db;
    private SessionGapRepository gaps;
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
        gaps = new SessionGapRepository(db.jdbc());
        sessionId = insertSession();
    }

    @AfterEach
    void tearDown() {
        db.wipe();
    }

    @Test
    void aDisconnectGapOpensAtTheDropAndClosesOnceWhenTheDeviceReturns() {
        UUID id = UUID.randomUUID();
        gaps.open(id, sessionId, Kind.DISCONNECT, T0, T0);

        SessionGapRow open = gaps.findBySession(sessionId).get(0);
        assertThat(open).isEqualTo(new SessionGapRow(id, sessionId, Kind.DISCONNECT, T0, null));
        assertThat(open.isOpen()).isTrue();

        assertThat(gaps.close(id, T0.plusSeconds(7))).isTrue();
        SessionGapRow closed = gaps.findBySession(sessionId).get(0);
        assertThat(closed.endedAt()).isEqualTo(T0.plusSeconds(7));
        assertThat(closed.isOpen()).isFalse();

        // 두 번째 닫기와 모르는 구간은 바꾸지 않는다
        assertThat(gaps.close(id, T0.plusSeconds(9))).isFalse();
        assertThat(gaps.findBySession(sessionId).get(0).endedAt()).isEqualTo(T0.plusSeconds(7));
        assertThat(gaps.close(UUID.randomUUID(), T0.plusSeconds(9))).isFalse();
    }

    @Test
    void aGapCannotEndBeforeItStartsAndNeedsItsSession() {
        UUID id = UUID.randomUUID();
        gaps.open(id, sessionId, Kind.UNCONFIRMED, T0.plusSeconds(10), T0.plusSeconds(10));

        assertThat(gaps.close(id, T0.plusSeconds(5))).isFalse();
        assertThat(gaps.findBySession(sessionId)).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(Kind.UNCONFIRMED);
            assertThat(row.isOpen()).isTrue();
        });
        // 시작과 같은 시각에 닫는 것은 된다(길이 0 구간)
        assertThat(gaps.close(id, T0.plusSeconds(10))).isTrue();

        UUID unknownSession = UUID.randomUUID();
        assertThatThrownBy(() -> gaps.open(UUID.randomUUID(), unknownSession, Kind.DISCONNECT, T0, T0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(gaps.findBySession(unknownSession)).isEmpty();
    }

    @Test
    void gapsAreListedInStartOrderAndDeletedPerSession() {
        UUID late = UUID.randomUUID();
        UUID early = UUID.randomUUID();
        gaps.open(late, sessionId, Kind.DISCONNECT, T0.plusSeconds(20), T0.plusSeconds(20));
        gaps.open(early, sessionId, Kind.DISCONNECT, T0, T0);
        UUID otherSession = insertSession();
        gaps.open(UUID.randomUUID(), otherSession, Kind.DISCONNECT, T0, T0);

        assertThat(gaps.findBySession(sessionId)).extracting(SessionGapRow::id).containsExactly(early, late);

        assertThat(gaps.deleteBySessions(List.of())).isZero();
        assertThat(gaps.deleteBySessions(List.of(sessionId))).isEqualTo(2);
        assertThat(gaps.deleteBySessions(List.of(sessionId))).isZero();
        assertThat(gaps.findBySession(sessionId)).isEmpty();
        assertThat(gaps.findBySession(otherSession)).hasSize(1);
    }

    private static UUID insertSession() {
        ActivitySessionRepository sessions = new ActivitySessionRepository(db.jdbc());
        NewActivitySession session = new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ActivityType.FREE_TALK, UUID.randomUUID().toString(),
                new ConsentSnapshot("1.0", true, true, false, false), new ChildContext("하늘", 8, List.of("공룡")), null,
                null);
        assertThat(db.inTransaction(() -> sessions.insert(session, T0))).isTrue();
        return session.id();
    }
}
