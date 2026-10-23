package kr.mujoong.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.log.LogCapture;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ① PREPARING 10초 점검 (BR6.12 ① · BR6.7 · AC3.1.9 · OD-9 · OD-10): 9.999초 무동작 · 10.001초 로컬 FAILED + 취소 · 0행 무동작. */
@Tag("skeleton")
@org.junit.jupiter.api.Tag("service-guardrail")
class PreparingTimeoutCheckerTest {

    private static final Instant REQUESTED_AT = Instant.parse("2001-03-01T06:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock(REQUESTED_AT);
    private final TestEvents events = new TestEvents(clock);
    private final ActivityCopyRepository activities = mock(ActivityCopyRepository.class);
    private final RealtimeSessionClient realtime = mock(RealtimeSessionClient.class);
    private final List<ActivityCopy> requested = new ArrayList<>();
    private PreparingTimeoutChecker checker;

    @BeforeEach
    void setUp() {
        checker = new PreparingTimeoutChecker(activities, realtime, events.logger(), events.metrics(), clock, TIMEOUT);
        // 저장소의 SQL 조건(requested_at <= cutoff)을 그대로 흉내 낸다
        when(activities.findRequestedDueBefore(any(), anyInt())).thenAnswer(call -> {
            Instant cutoff = call.getArgument(0);
            return requested.stream().filter(copy -> !copy.requestedAt().isAfter(cutoff)).toList();
        });
    }

    @Test
    void nothingHappensJustBeforeTenSeconds() {
        requested.add(ActivityCopies.copy(UUID.randomUUID(), UUID.randomUUID(), SessionStatus.REQUESTED, 0, REQUESTED_AT));
        clock.advance(Duration.ofMillis(9_999));

        assertThat(checker.runOnce()).isZero();
        verify(activities, never()).closeLocalFailed(any(), any(), anyLong(), anyInt(), any());
        verify(realtime, never()).cancel(any());
    }

    @Test
    void justAfterTenSecondsClosesLocallyWith202AndCancels() {
        UUID sessionId = UUID.randomUUID();
        requested.add(ActivityCopies.copy(sessionId, UUID.randomUUID(), SessionStatus.REQUESTED, 0, REQUESTED_AT));
        clock.advance(Duration.ofMillis(10_001));
        when(activities.closeLocalFailed(sessionId, SessionStatus.REQUESTED, 0, 202, clock.now())).thenReturn(1);

        assertThat(checker.runOnce()).isEqualTo(1);

        verify(realtime).cancel(sessionId);
        var fired = events.capture().last(EventCode.TIMEOUT_FIRED).orElseThrow();
        assertThat(LogCapture.attrs(fired)).containsEntry("type", "PREPARING");
        assertThat(((Number) LogCapture.attrs(fired).get("elapsedMs")).longValue()).isEqualTo(10_001L);
        assertThat(((Number) LogCapture.attrs(fired).get("thresholdMs")).longValue()).isEqualTo(10_000L);
        assertThat(events.metrics().count("mujoong.timeout.fired", "type", "preparing")).isEqualTo(1.0);
        assertThat(events.metrics().count("mujoong.session.ended", "origin", "core_local", "phase", "start")).isEqualTo(1.0);
        var transition = events.capture().last(EventCode.SESSION_TRANSITION).orElseThrow();
        assertThat(LogCapture.attrs(transition)).containsEntry("from", "REQUESTED").containsEntry("to", "FAILED");
    }

    @Test
    void lostConditionalUpdateDoesNothingElse() {
        UUID sessionId = UUID.randomUUID();
        requested.add(ActivityCopies.copy(sessionId, UUID.randomUUID(), SessionStatus.REQUESTED, 0, REQUESTED_AT));
        clock.advance(Duration.ofSeconds(11));
        when(activities.closeLocalFailed(any(), any(), anyLong(), anyInt(), any())).thenReturn(0);

        assertThat(checker.runOnce()).isZero();
        verify(realtime, never()).cancel(any());
        assertThat(events.capture().events(EventCode.TIMEOUT_FIRED)).isEmpty();
    }

    @Test
    void cancelFailureDoesNotStopOtherClosures() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        requested.add(ActivityCopies.copy(first, UUID.randomUUID(), SessionStatus.REQUESTED, 0, REQUESTED_AT));
        requested.add(ActivityCopies.copy(second, UUID.randomUUID(), SessionStatus.REQUESTED, 0, REQUESTED_AT.plusSeconds(1)));
        clock.advance(Duration.ofSeconds(12));
        when(activities.closeLocalFailed(any(), any(), anyLong(), anyInt(), any())).thenReturn(1);
        when(realtime.cancel(first)).thenReturn(false);
        when(realtime.cancel(second)).thenReturn(true);

        assertThat(checker.runOnce()).isEqualTo(2);
        verify(realtime).cancel(first);
        verify(realtime).cancel(second);
    }

    @Test
    void emptyRoundIsNoOp() {
        clock.advance(Duration.ofHours(1));

        assertThat(checker.runOnce()).isZero();
        assertThat(events.capture().lines()).isEmpty();
    }
}
