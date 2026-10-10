package kr.mujoong.contract.time;

import java.time.Duration;
import java.time.Instant;

/**
 * 지연 작업 예약(RD-2 · RD-1). 마감 시각은 주입한 {@link MujoongClock}으로 계산한다.
 *
 * <p>규약: 마감이 이른 작업부터 실행하고 마감이 같으면 등록 순서대로 실행한다. 마감이 이미 지났으면 곧바로(다음 실행 기회에) 실행한다.
 * 작업은 가벼워야 한다 — 세션 시한 타이머는 편지함에 메시지를 넣기만 한다(발동 규약 1, RD-1). 발동한 쪽이 Clock으로 경과를 다시 확인한다(R4).
 * 운영은 {@link DelayQueueTimerDriver}, 시험은 수동 구동기(test-support {@code ManualTimerDriver})다(NFR6.2).
 */
public interface TimerDriver extends AutoCloseable {

    /** 지금부터 {@code delay} 뒤에 실행한다. 음수 · 0은 곧바로. 닫힌 뒤에는 {@link IllegalStateException}. */
    TimerHandle schedule(Duration delay, Runnable task);

    /** {@code deadline}에 실행한다. 지난 시각이면 곧바로. 닫힌 뒤에는 {@link IllegalStateException}. */
    TimerHandle scheduleAt(Instant deadline, Runnable task);

    /** 새 등록을 거절하고 아직 실행하지 않은 작업을 버린다. 두 번 불러도 된다. */
    @Override
    void close();

    /** 등록한 작업의 손잡이. */
    interface TimerHandle {

        /** 마감 시각. */
        Instant deadline();

        /** 아직 실행하지 않았으면 취소하고 참. 이미 실행 · 취소됐으면 거짓. */
        boolean cancel();

        /** 취소됐는가. */
        boolean isCancelled();
    }
}
