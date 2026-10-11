package kr.mujoong.realtime.web;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * 필터 F1b를 지나 처리 중인 요청 수 (U1 3.6 수정 · RD-13 T6 · R3-13 · NFR4.10 · J304, Core J321과 같은 방식).
 *
 * <p>정상 종료 T6은 이 수가 0이 될 때까지(유예 안에서) 기다리고, 그동안 서버 커넥터는 열려 있어 새 요청은 F1b가 503 SERVICE_NOT_READY +
 * {@code Retry-After}로 답한다(접속 거절이 아니다). 요청은 준비 상태를 보기 전에 먼저 센다: T1(DRAINING) 뒤 T6이 0을 본 다음 들어온 요청은
 * 반드시 DRAINING을 보고 거절되므로 받아들인 요청을 놓치지 않는다. 기기 WSS 업그레이드는 F1b를 지나지 않아 세지 않는다(업그레이드 뒤 1013).
 */
@Component
public class InFlightRequests {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private int active;

    /** 요청 하나가 들어왔다(F1b 판정 전). */
    public void enter() {
        lock.lock();
        try {
            active++;
        } finally {
            lock.unlock();
        }
    }

    /** 요청 하나가 끝났다(거절 응답 포함). 마지막 요청이면 기다리는 쪽을 깨운다. */
    public void exit() {
        lock.lock();
        try {
            if (active > 0) {
                active--;
            }
            if (active == 0) {
                idle.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 지금 처리 중인 요청 수. */
    public int active() {
        lock.lock();
        try {
            return active;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 처리 중인 요청이 모두 끝나거나 {@code limit}이 지날 때까지 기다린다.
     *
     * @return 모두 끝났으면 true, 제한이 먼저 지났으면 false
     * @throws InterruptedException 기다리는 스레드가 끊겼다
     */
    public boolean awaitIdle(Duration limit) throws InterruptedException {
        Objects.requireNonNull(limit, "limit");
        long remaining = Math.max(0L, limit.toNanos());
        lock.lock();
        try {
            while (active > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = idle.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
