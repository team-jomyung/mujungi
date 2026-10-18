package kr.mujoong.realtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.log.EventCode;
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

/**
 * 세션 전이의 관측 출력 (U1 3.6 수정 · OD-5 · OD-9 · OD-17 · NFR4.14 · BT-NFR4.15 · J297).
 *
 * <p>커밋 뒤 SESSION_TRANSITION 한 줄과 세션 지표: PREPARING 생성은 동시 활성 세션 + 1, ACTIVE 전이는
 * {@code mujoong_session_activated_total}과 시작(PREPARING 행을 쓴 시각) → ACTIVE 길이 히스토그램, 종료 상태는
 * {@code mujoong_session_ended_total}(라벨) 한 번과 동시 활성 − 1. 그 밖의 전이는 지표를 움직이지 않는다.
 */
@Tag("skeleton")
class SessionStateEmitterTest {

    private static final Instant T0 = Instant.parse("2026-10-11T01:00:00Z");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    private final MutableClock clock = new MutableClock(T0);
    private final LogCapture logs = LogCapture.detached();
    private final RealtimeMetrics metrics = RealtimeMetrics.inMemory();
    private final RealtimeOutboxWriter outbox = mock(RealtimeOutboxWriter.class);
    private final SessionStateEmitter emitter =
            new SessionStateEmitter(outbox, TestEventLoggers.strict(clock, logs, metrics), metrics);

    @Test
    void aNewPreparingSessionCountsAsActiveAndLogsTheTransitionWithoutFrom() {
        emitter.committed(null, row(SessionStatus.PREPARING, null, null, null), SessionTransitions.Origin.REALTIME, TRACE);

        assertThat(metrics.sessionsActive()).isEqualTo(1);
        Map<String, Object> line = LogCapture.attrs(logs.last(EventCode.SESSION_TRANSITION).orElseThrow());
        assertThat(line).containsEntry("to", "PREPARING").containsEntry("phase", "START")
                .containsEntry("origin", "REALTIME").doesNotContainKey("from");
        assertThat(metrics.count(RealtimeMetrics.SESSION_ACTIVATED)).isZero();
    }

    @Test
    void theActiveTransitionCountsTheActivationAndTheStartToActiveTime() {
        Instant activeAt = T0.plus(Duration.ofMillis(3200));

        emitter.committed(SessionStatus.DEVICE_ACK, row(SessionStatus.ACTIVE, null, null, activeAt),
                SessionTransitions.Origin.REALTIME, TRACE);

        assertThat(metrics.count(RealtimeMetrics.SESSION_ACTIVATED)).isEqualTo(1.0);
        assertThat(metrics.recorded(RealtimeMetrics.SESSION_START_TO_ACTIVE)).isEqualTo(1);
        assertThat(metrics.registry().find(RealtimeMetrics.SESSION_START_TO_ACTIVE).timer()
                .totalTime(TimeUnit.MILLISECONDS)).as("PREPARING 행을 쓴 시각 → ACTIVE").isEqualTo(3200.0);
        assertThat(LogCapture.attrs(logs.last(EventCode.SESSION_TRANSITION).orElseThrow()))
                .containsEntry("from", "DEVICE_ACK").containsEntry("to", "ACTIVE").containsEntry("phase", "START");
    }

    @Test
    void anActiveRowWithoutAStartTimeStillCountsTheActivationButRecordsNoLength() {
        emitter.committed(SessionStatus.DEVICE_ACK, row(SessionStatus.ACTIVE, null, null, null),
                SessionTransitions.Origin.REALTIME, TRACE);

        assertThat(metrics.count(RealtimeMetrics.SESSION_ACTIVATED)).isEqualTo(1.0);
        assertThat(metrics.recorded(RealtimeMetrics.SESSION_START_TO_ACTIVE)).as("시각을 만들지 않는다").isZero();
    }

    @Test
    void aTerminalTransitionCountsTheEndOnceWithItsLabelsAndFreesTheActiveSlot() {
        metrics.sessionStarted();

        emitter.committed(SessionStatus.CLOSING, row(SessionStatus.ENDED, EndReason.GUARDIAN_STOP, null, T0),
                SessionTransitions.Origin.REALTIME, TRACE);

        assertThat(metrics.count("mujoong.session.ended", "status", "ended", "end_reason", "guardian_stop",
                "failure_code", "none", "phase", "running", "origin", "realtime")).isEqualTo(1.0);
        assertThat(metrics.sessionsActive()).isZero();
        assertThat(metrics.count(RealtimeMetrics.SESSION_ACTIVATED)).as("종료는 활성이 아니다").isZero();
        assertThat(LogCapture.attrs(logs.last(EventCode.SESSION_TRANSITION).orElseThrow()))
                .containsEntry("confirmed", true).containsEntry("endReason", "GUARDIAN_STOP");
    }

    @Test
    void aMiddleTransitionLogsALineButLeavesTheSessionCountersAlone() {
        emitter.committed(SessionStatus.PREPARING, row(SessionStatus.DEVICE_ACK, null, null, null),
                SessionTransitions.Origin.REALTIME, TRACE);
        emitter.committed(SessionStatus.ACTIVE, row(SessionStatus.CLOSING, EndReason.GUARDIAN_STOP, null, T0),
                SessionTransitions.Origin.REALTIME, TRACE);

        assertThat(logs.events(EventCode.SESSION_TRANSITION)).hasSize(2);
        assertThat(metrics.count(RealtimeMetrics.SESSION_ACTIVATED)).isZero();
        assertThat(metrics.recorded(RealtimeMetrics.SESSION_START_TO_ACTIVE)).isZero();
        assertThat(metrics.registry().find("mujoong.session.ended").counters()).isEmpty();
    }

    @Test
    void writeQueuesSessionStateChangedOnTheOutboxWithTheSessionTraceId() {
        ActivitySessionRow active = row(SessionStatus.ACTIVE, null, null, T0);
        Instant at = T0.plusSeconds(1);

        emitter.write(active, at, TRACE);

        verify(outbox).write(any(SessionStateChanged.class), eq(active.guardianId()), eq(at), eq(TRACE));
    }

    private static ActivitySessionRow row(SessionStatus status, EndReason endReason, String endDetail, Instant startedAt) {
        return new ActivitySessionRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, status, 2, "key", null, null, T0, "{}", "1.0", true, true, null, null, null,
                endReason, endDetail, null, null, null, null, startedAt, null, 1L, T0, T0);
    }
}
