package kr.mujoong.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 정상 종료 T6이 기다리는 진행 중 요청 수 (U1 3.6 수정 · RD-13 · R3-13 · J321). 기다림은 실제 시간이지만 짧은 한도(수백 ms)만 쓴다.
 */
@Tag("u1")
class InFlightRequestsTest {

    private static final Duration SHORT = Duration.ofMillis(150);
    private static final Duration LONG = Duration.ofSeconds(5);

    private final InFlightRequests inFlight = new InFlightRequests();

    @Test
    void enterAndExitCountTheRequestsInProgress() {
        inFlight.enter();
        inFlight.enter();
        assertThat(inFlight.active()).isEqualTo(2);

        inFlight.exit();

        assertThat(inFlight.active()).isEqualTo(1);
    }

    @Test
    void awaitIdleReturnsAtOnceWhenNothingIsInProgress() throws Exception {
        long started = System.nanoTime();

        assertThat(inFlight.awaitIdle(LONG)).isTrue();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SHORT);
    }

    @Test
    void awaitIdleGivesUpWhenTheLimitPassesWhileARequestRuns() throws Exception {
        inFlight.enter();
        long started = System.nanoTime();

        assertThat(inFlight.awaitIdle(SHORT)).isFalse();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(SHORT);
        assertThat(inFlight.awaitIdle(Duration.ZERO)).as("한도 0은 기다리지 않는다").isFalse();
        assertThat(inFlight.awaitIdle(Duration.ofMillis(-1))).isFalse();
    }

    @Test
    void awaitIdleWakesUpWhenTheLastRequestEnds() throws Exception {
        inFlight.enter();
        inFlight.enter();
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Boolean> drained = CompletableFuture.supplyAsync(() -> {
            waiting.countDown();
            try {
                return inFlight.awaitIdle(LONG);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();

        inFlight.exit();
        assertThat(drained).as("하나가 남아 있으면 아직 기다린다").isNotDone();
        inFlight.exit();

        assertThat(drained.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void exitWithoutEnterNeverGoesBelowZero() throws Exception {
        inFlight.exit();
        inFlight.enter();

        assertThat(inFlight.active()).isEqualTo(1);
        inFlight.exit();
        assertThat(inFlight.awaitIdle(SHORT)).isTrue();
        assertThatThrownBy(() -> inFlight.awaitIdle(null)).isInstanceOf(NullPointerException.class);
    }
}
