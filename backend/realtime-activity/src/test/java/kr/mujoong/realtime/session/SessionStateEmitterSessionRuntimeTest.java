package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import kr.mujoong.realtime.testing.TestEventLoggers;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * U4b SessionStateChanged 내용 (L2 · BR7.2 · FR1.2 · 설계 7절 · 편차 (U4b-c)).
 *
 * <p>CLOSING에도 행의 end_reason(마무리 사유)을 싣고, 전이 시점에 기술 일시정지 중이면 technicalPause를 싣는다. 일시정지가 없으면 null이다.
 */
@Tag("session-runtime")
class SessionStateEmitterSessionRuntimeTest {

    private static final Instant T0 = Instant.parse("2026-10-13T01:00:00Z");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    private final MutableClock clock = new MutableClock(T0);
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final RealtimeOutboxWriter outbox = mock(RealtimeOutboxWriter.class);
    private final SessionStateEmitter emitter =
            new SessionStateEmitter(outbox, TestEventLoggers.strict(clock, LogCapture.detached(), metrics), metrics);

    @Test
    void theClosingStateCarriesTheClosingReason() {
        ActivitySessionRow closing = row(SessionStatus.CLOSING, EndReason.CHILD_STOP, "PRACTICE_COMPLETED");

        SessionStateChanged payload = written(closing, null);

        assertThat(payload.status()).isEqualTo(SessionStatus.CLOSING);
        assertThat(payload.endReason()).isEqualTo(EndReason.CHILD_STOP);
        assertThat(payload.failureCode()).as("CLOSING의 세부는 실패 코드가 아니다").isNull();
        assertThat(payload.confirmed()).isNull();
        assertThat(payload.technicalPause()).isNull();
        assertThat(payload.problems()).isEmpty();
    }

    @Test
    void aPauseAtTheMomentOfTheTransitionIsCarried() {
        SessionStateChanged.TechnicalPause pause =
                new SessionStateChanged.TechnicalPause(SessionStateChanged.TechnicalPause.Kind.DEVICE, T0);

        SessionStateChanged payload = written(row(SessionStatus.ABORTED, EndReason.DISCONNECT, null), pause);

        assertThat(payload.technicalPause()).isEqualTo(pause);
        assertThat(payload.endReason()).isEqualTo(EndReason.DISCONNECT);
    }

    @Test
    void theUnconfirmedEndCarriesUnconfirmedAndConfirmedFalse() {
        SessionStateChanged payload = written(row(SessionStatus.ENDED_UNCONFIRMED, EndReason.UNCONFIRMED,
                "GUARDIAN_STOP"), null);

        assertThat(payload.endReason()).isEqualTo(EndReason.UNCONFIRMED);
        assertThat(payload.confirmed()).isFalse();
    }

    @Test
    void theDeclinedEndCarriesDeclinedWithoutAConfirmFlag() {
        SessionStateChanged payload = written(row(SessionStatus.DECLINED, EndReason.DECLINED, null), null);

        assertThat(payload.status()).isEqualTo(SessionStatus.DECLINED);
        assertThat(payload.endReason()).isEqualTo(EndReason.DECLINED);
        assertThat(payload.confirmed()).isNull();
    }

    private SessionStateChanged written(ActivitySessionRow row, SessionStateChanged.TechnicalPause pause) {
        emitter.write(row, T0, TRACE, pause);
        ArgumentCaptor<SessionStateChanged> captor = ArgumentCaptor.forClass(SessionStateChanged.class);
        verify(outbox).write(captor.capture(), eq(row.guardianId()), eq(T0), eq(TRACE));
        return captor.getValue();
    }

    private static ActivitySessionRow row(SessionStatus status, EndReason endReason, String endDetail) {
        return new ActivitySessionRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, status, 5, "key", null, null, T0, "{}", "1.0", true, true, null, null, null,
                endReason, endDetail, T0, null, null, null, T0, null, 1L, T0, T0);
    }
}
