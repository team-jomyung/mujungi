package kr.mujoong.realtime.runtime;

import java.util.Objects;
import kr.mujoong.contract.time.DelayQueueTimerDriver;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.TimerDriver;

/**
 * Realtime의 시계 두 개 (U1 3.5 Step 23 · RD-1 · RD-2 · RD-3 · R3-18 · NFR7.3).
 *
 * <ul>
 *   <li>시계 1 {@link #safety()}: 안전 시한(② 기기 응답 · ③ 종료 확인 · ④ 끊김) · HELLO 5초 · 전이 실패 재시도 · 재생 중 승인 문구 만료의
 *       만기를 편지함 · 제어 레인에 넣기만 한다(발동 규약 1). DB · 공급자 호출을 하지 않는다.
 *   <li>시계 2 {@link #quiet()}: 턴마다 다시 거는 구간 조용 시간(800ms). 잦은 재무장이 안전 시한 큐를 흔들지 않게 분리한다.
 * </ul>
 *
 * <p>운영은 {@link DelayQueueTimerDriver} 두 개(데몬 플랫폼 스레드 둘)이고, 시험은 수동 구동기를 넣는다(NFR6.2). 마감은 모두 주입한
 * {@link MujoongClock}으로 계산한다.
 */
public final class TimerClocks implements AutoCloseable {

    /** 시계 1 스레드 이름. */
    public static final String SAFETY_THREAD = "realtime-safety-timer";
    /** 시계 2 스레드 이름. */
    public static final String QUIET_THREAD = "realtime-quiet-timer";

    private final TimerDriver safety;
    private final TimerDriver quiet;

    public TimerClocks(TimerDriver safety, TimerDriver quiet) {
        this.safety = Objects.requireNonNull(safety, "safety");
        this.quiet = Objects.requireNonNull(quiet, "quiet");
    }

    /** 운영 시계 두 개를 만든다. */
    public static TimerClocks system(MujoongClock clock) {
        Objects.requireNonNull(clock, "clock");
        return new TimerClocks(new DelayQueueTimerDriver(clock, SAFETY_THREAD),
                new DelayQueueTimerDriver(clock, QUIET_THREAD));
    }

    /** 시계 1(안전 시한). */
    public TimerDriver safety() {
        return safety;
    }

    /** 시계 2(구간 조용 시간). */
    public TimerDriver quiet() {
        return quiet;
    }

    @Override
    public void close() {
        safety.close();
        quiet.close();
    }
}
