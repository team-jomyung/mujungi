package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.*;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;

@Tag("u10")
class U10ScenarioDeadlineTest {
    @Test void exactDeadlineAndLaterAreExpired(){var c=new MutableClock();var d=new ScenarioDeadline(c,c.now().plusSeconds(15));c.advance(Duration.ofSeconds(15));assertThatThrownBy(d::check).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);c.advance(Duration.ofNanos(1));assertThatThrownBy(d::check).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}
    @Test void remainingAndPurposeLimitUseSmallerWindow(){var c=new MutableClock();var d=new ScenarioDeadline(c,c.now().plusSeconds(15));c.advance(Duration.ofSeconds(14));assertThat(d.limit(Duration.ofSeconds(3))).isEqualTo(Duration.ofSeconds(1));}
    @Test void alreadyExpiredNeverSchedulesTask(){var c=new MutableClock();var d=new ScenarioDeadline(c,c.now());try(var e=Executors.newVirtualThreadPerTaskExecutor()){assertThatThrownBy(()->d.call(e,Duration.ofSeconds(1),()->"late")).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}}
    @Test void lateNormalResultIsDiscarded(){var c=new MutableClock();var d=new ScenarioDeadline(c,c.now().plusSeconds(15));try(var e=Executors.newVirtualThreadPerTaskExecutor()){assertThatThrownBy(()->d.call(e,Duration.ofSeconds(1),()->{c.advance(Duration.ofSeconds(15));return "late";})).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);}}
    @Test void timeoutCancelsTask(){var c=new MutableClock();var d=new ScenarioDeadline(c,c.now().plusSeconds(15));var interrupted=new CountDownLatch(1);try(var e=Executors.newVirtualThreadPerTaskExecutor()){assertThatThrownBy(()->d.call(e,Duration.ofMillis(30),()->{try{Thread.sleep(10000);return "late";}catch(InterruptedException x){interrupted.countDown();throw x;}})).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);try{assertThat(interrupted.await(1,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException x){throw new AssertionError(x);}}}
    @Test void callerInterruptionRemainsSetAndPreventsScheduling(){var c=new MutableClock();var d=new ScenarioDeadline(c,c.now().plusSeconds(15));try{Thread.currentThread().interrupt();assertThatThrownBy(d::check).isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);assertThat(Thread.currentThread().isInterrupted()).isTrue();}finally{Thread.interrupted();}}
}
