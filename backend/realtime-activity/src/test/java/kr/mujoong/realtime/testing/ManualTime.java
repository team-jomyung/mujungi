package kr.mujoong.realtime.testing;

import java.time.Duration;
import kr.mujoong.realtime.runtime.TimerClocks;
import kr.mujoong.testsupport.time.ManualTimerDriver;
import kr.mujoong.testsupport.time.MutableClock;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 앱 전체 시험의 수동 시계(Step 35 · NFR6.2 · RD-2, 시험 소스 전용). 빈 생성 전에 가변 {@link MutableClock}과 수동 구동기 두 개로 만든
 * {@link TimerClocks}를 등록한다 — 운영 설정은 이 빈이 있으면 자기 것을 만들지 않는다({@code @ConditionalOnMissingBean}). 시험은 실제
 * 시간을 기다리지 않고 {@link #advance}로 시한(HELLO 5초 · 무음 40초 등)을 넘긴다. 주기 작업 스레드(점검 · 보고)는 실제 시간으로 돌며 이
 * 시계의 지금을 읽는다.
 */
public final class ManualTime implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public final MutableClock clock = new MutableClock();
    public final ManualTimerDriver safety = new ManualTimerDriver(clock);
    public final ManualTimerDriver quiet = new ManualTimerDriver(clock);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getBeanFactory().registerSingleton("itMutableClock", clock);
        context.getBeanFactory().registerSingleton("itTimerClocks", new TimerClocks(safety, quiet));
    }

    /** 시계를 앞당기고 도래한 시계 1 · 시계 2 타이머를 마감 순서대로 실행한다. 실행한 타이머 수. */
    public int advance(Duration duration) {
        int fired = safety.advance(duration);
        return fired + quiet.runDue();
    }
}
