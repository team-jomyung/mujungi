package kr.mujoong.realtime.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 처리 중 요청 수 (U1 3.6 수정 · RD-13 T6 · R3-13 · J304). 정상 종료 T6이 받아들인 요청이 끝날 때까지만 기다리고, 기다릴 요청이 없으면 바로
 * 돌아오며, 한도가 지나면 포기하는지 본다.
 */
@Tag("u1")
class InFlightRequestsTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final InFlightRequests inFlight = new InFlightRequests();

    @Test
    void enterAndExitCountTheAdmittedRequests() {
        inFlight.enter();
        inFlight.enter();
        assertThat(inFlight.active()).isEqualTo(2);

        inFlight.exit();
        inFlight.exit();
        inFlight.exit();

        assertThat(inFlight.active()).as("짝이 없는 exit은 0 아래로 내려가지 않는다").isZero();
    }

    @Test
    void awaitIdleReturnsAtOnceWhenNothingIsInFlight() throws InterruptedException {
        assertThat(inFlight.awaitIdle(Duration.ZERO)).isTrue();
        assertThat(inFlight.awaitIdle(WAIT)).isTrue();
    }

    @Test
    void awaitIdleWakesWhenTheLastRequestFinishes() throws Exception {
        inFlight.enter();
        inFlight.enter();
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Boolean> idle = CompletableFuture.supplyAsync(() -> {
            waiting.countDown();
            try {
                return inFlight.awaitIdle(WAIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        assertThat(waiting.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        inFlight.exit();
        assertThat(idle).as("하나가 남아 있으면 아직 기다린다").isNotDone();
        inFlight.exit();

        assertThat(idle.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void awaitIdleGivesUpWhenTheLimitRunsOut() throws InterruptedException {
        inFlight.enter();

        assertThat(inFlight.awaitIdle(Duration.ZERO)).as("한도 0은 기다리지 않는다").isFalse();
        assertThat(inFlight.awaitIdle(Duration.ofMillis(30))).isFalse();
        assertThat(inFlight.awaitIdle(Duration.ofMillis(-5))).as("음수 한도도 기다리지 않는다").isFalse();
        assertThat(inFlight.active()).isEqualTo(1);
    }

    @Test
    void awaitIdleRejectsAMissingLimitAndHonoursInterruption() {
        inFlight.enter();
        assertThatThrownBy(() -> inFlight.awaitIdle(null)).isInstanceOf(NullPointerException.class);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> inFlight.awaitIdle(WAIT)).isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }
}
