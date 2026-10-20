package kr.mujoong.contract.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.mujoong.contract.time.TimerDriver.TimerHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 운영 타이머 구동기(RD-2 · RD-1 · NFR7.3 · J138). 수십 ms 시한을 실제 시계로 걸고 상한 있는 래치 대기로만 확인한다(Thread.sleep 없음).
 * 상한 5초는 실패를 빨리 드러내기 위한 것이고 정상 경로는 수십 ms 안에 끝난다.
 */
@Tag("skeleton")
class DelayQueueTimerDriverTest {

    private static final long LATCH_LIMIT_SECONDS = 5;

    private final SystemMujoongClock clock = new SystemMujoongClock();
    private final List<String> fired = new CopyOnWriteArrayList<>();
    private DelayQueueTimerDriver driver;

    @BeforeEach
    void setUp() {
        driver = new DelayQueueTimerDriver(clock, "test-timer");
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    void dueTasksRunInDeadlineOrder() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(3);
        CountDownLatch release = holdWorker();
        Instant base = clock.now();
        driver.scheduleAt(base.plusMillis(60), () -> record("60ms", done));
        driver.scheduleAt(base.plusMillis(20), () -> record("20ms", done));
        driver.scheduleAt(base.plusMillis(40), () -> record("40ms", done));
        release.countDown();

        assertThat(done.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(fired).containsExactly("20ms", "40ms", "60ms");
    }

    @Test
    void twoTasksWithTheSameDeadlineRunInRegistrationOrder() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(2);
        CountDownLatch release = holdWorker();
        Instant deadline = clock.now().plusMillis(30);
        driver.scheduleAt(deadline, () -> record("first", done));
        TimerHandle second = driver.scheduleAt(deadline, () -> record("second", done));
        release.countDown();

        assertThat(done.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(fired).containsExactly("first", "second");
        assertThat(second.deadline()).isEqualTo(deadline);
    }

    @Test
    void cancelledTaskNeverRunsEvenWhenAnEarlierDeadline() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        TimerHandle cancelled = driver.schedule(Duration.ofMillis(20), () -> fired.add("cancelled"));
        driver.schedule(Duration.ofMillis(50), () -> record("kept", done));

        assertThat(cancelled.cancel()).isTrue();
        assertThat(cancelled.cancel()).isFalse();
        assertThat(cancelled.isCancelled()).isTrue();
        assertThat(done.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(fired).containsExactly("kept");
    }

    @Test
    void pastOrZeroDeadlineRunsImmediately() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(3);
        CountDownLatch release = holdWorker();
        driver.scheduleAt(clock.now().minusSeconds(10), () -> record("past", done));
        driver.schedule(Duration.ZERO, () -> record("zero", done));
        driver.schedule(Duration.ofSeconds(-5), () -> record("negative", done));
        release.countDown();

        assertThat(done.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(fired).containsExactly("past", "negative", "zero");
    }

    @Test
    void firedTaskCannotBeCancelledAfterwards() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        TimerHandle handle = driver.schedule(Duration.ofMillis(10), () -> record("ran", done));

        assertThat(done.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(handle.cancel()).isFalse();
        assertThat(handle.isCancelled()).isFalse();
    }

    @Test
    void failingTaskGoesToTheErrorHandlerAndTheWorkerKeepsRunning() throws InterruptedException {
        List<RuntimeException> failures = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        try (DelayQueueTimerDriver withHandler = new DelayQueueTimerDriver(clock, "test-timer-errors", failures::add)) {
            withHandler.schedule(Duration.ofMillis(10), () -> {
                throw new IllegalStateException("boom");
            });
            withHandler.schedule(Duration.ofMillis(30), () -> record("after", done));

            assertThat(done.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failures).singleElement().isInstanceOf(IllegalStateException.class);
        assertThat(fired).containsExactly("after");
    }

    @Test
    void closedDriverRejectsNewTasksAndDropsPendingOnes() throws InterruptedException {
        CountDownLatch never = new CountDownLatch(1);
        driver.schedule(Duration.ofMillis(30), () -> record("dropped", never));

        driver.close();
        driver.close();

        assertThat(driver.isClosed()).isTrue();
        assertThatThrownBy(() -> driver.schedule(Duration.ofMillis(1), () -> fired.add("late")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(never.await(200, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(fired).isEmpty();
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> driver.schedule(null, () -> { })).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> driver.scheduleAt(clock.now(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DelayQueueTimerDriver(null, "x")).isInstanceOf(NullPointerException.class);
    }

    /**
     * 워커 스레드를 붙잡아 두는 지난 마감 작업을 건다. 돌려준 래치를 풀기 전까지 다른 작업은 실행되지 않으므로, 등록하는 동안 앞선 작업이
     * 먼저 실행되는 경쟁 없이 큐 순서만 확인한다.
     */
    private CountDownLatch holdWorker() throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        driver.scheduleAt(clock.now().minusSeconds(60), () -> {
            holding.countDown();
            try {
                release.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(holding.await(LATCH_LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return release;
    }

    private void record(String name, CountDownLatch latch) {
        fired.add(name);
        latch.countDown();
    }
}
