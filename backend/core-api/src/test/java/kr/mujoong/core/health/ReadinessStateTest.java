package kr.mujoong.core.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.core.health.ReadinessState.State;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 준비 상태와 DB 점검 (RD-16 · OD-11 · 편차 (n) · J124): NOT_READY 3회 · 성공 1회 복귀 · DRAINING 고정 · Retry-After. */
@Tag("u1")
class ReadinessStateTest {

    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;
    private final CoreMetrics metrics = CoreMetrics.inMemory();

    @Test
    void startsNotReadyAndBecomesReadyAfterStartupSequence() {
        ReadinessState readiness = new ReadinessState(publisher, metrics, 3, Duration.ofSeconds(5));
        assertThat(readiness.state()).isEqualTo(State.STARTING);
        assertThat(readiness.isReady()).isFalse();

        readiness.markReady();

        assertThat(readiness.isReady()).isTrue();
        assertThat(published).singleElement().isInstanceOf(AvailabilityChangeEvent.class);
        assertThat(((AvailabilityChangeEvent<?>) published.get(0)).getState())
                .isEqualTo(org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void threeConsecutiveDbFailuresMakeNotReadyAndOneSuccessRecovers() {
        ReadinessState readiness = new ReadinessState(publisher, metrics, 3, Duration.ofSeconds(5));
        readiness.markReady();

        assertThat(readiness.recordDbCheck(false)).isEqualTo(State.READY);
        assertThat(readiness.recordDbCheck(false)).isEqualTo(State.READY);
        readiness.recordDbCheck(true);
        assertThat(readiness.consecutiveFailures()).isZero();
        readiness.recordDbCheck(false);
        readiness.recordDbCheck(false);
        assertThat(readiness.recordDbCheck(false)).isEqualTo(State.NOT_READY);

        assertThat(readiness.recordDbCheck(true)).isEqualTo(State.READY);
        assertThat(published).hasSize(3);
    }

    @Test
    void drainingIsFinalAndChecksDoNotChangeStartingOrDraining() {
        ReadinessState readiness = new ReadinessState(publisher, metrics, 1, Duration.ofSeconds(5));
        assertThat(readiness.recordDbCheck(false)).isEqualTo(State.STARTING);

        readiness.markDraining();
        readiness.markDraining();
        readiness.markReady();

        assertThat(readiness.recordDbCheck(true)).isEqualTo(State.DRAINING);
        assertThat(published).hasSize(1);
    }

    @Test
    void retryAfterIsTheCheckIntervalRoundedUpToWholeSeconds() {
        assertThat(new ReadinessState(publisher, metrics, 3, Duration.ofSeconds(5)).retryAfterSeconds()).isEqualTo(5);
        assertThat(new ReadinessState(publisher, metrics, 3, Duration.ofMillis(200)).retryAfterSeconds()).isEqualTo(1);
        assertThat(new ReadinessState(publisher, metrics, 3, Duration.ofMillis(5_001)).retryAfterSeconds()).isEqualTo(6);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new ReadinessState(publisher, metrics, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dbCheckerFeedsSelectOneResultsAndLogsFailureKindOnly() {
        TestEvents events = new TestEvents(new MutableClock(Instant.parse("2001-09-02T00:00:00Z")));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReadinessState readiness = new ReadinessState(publisher, metrics, 2, Duration.ofSeconds(5));
        readiness.markReady();
        DbHealthChecker checker = new DbHealthChecker(jdbc, readiness, events.logger());
        when(jdbc.queryForObject(eq("select 1"), eq(Integer.class))).thenReturn(1)
                .thenThrow(new DataAccessResourceFailureException("connection refused to secret-host"))
                .thenThrow(new DataAccessResourceFailureException("again"))
                .thenReturn(1);

        assertThat(checker.runOnce()).isEqualTo(State.READY);
        assertThat(checker.runOnce()).isEqualTo(State.READY);
        assertThat(checker.runOnce()).isEqualTo(State.NOT_READY);
        assertThat(checker.runOnce()).isEqualTo(State.READY);
        assertThat(events.capture().events(EventCode.APP_ERROR)).hasSize(1);
        assertThat(events.capture().anyLineContains("secret-host")).isFalse();
    }
}
