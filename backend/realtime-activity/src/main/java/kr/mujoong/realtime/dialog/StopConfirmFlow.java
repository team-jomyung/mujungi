package kr.mujoong.realtime.dialog;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 종료 의도 확인 질문 흐름 (U4b L4 · FR3.1 · BR4.2 ~ BR4.4 · U4b-R4 · U4b-J6 · FD-Q2 · FD-Q3).
 *
 * <p>순수 판단만 한다 — 재생 · 시계 · 공급자 명령은 부르는 쪽(세션 실행기)이 맡는다. 시각은 인자로 받는다.
 *
 * <ul>
 *   <li>판정 STOP: 곧바로 {@link Decision#CLOSE}(CLOSING(CHILD_STOP)). 출력만 막고 ACTIVE를 유지하는 U3 경로는 없다(FD-Q3).
 *   <li>판정 CONFIRM: {@link Decision#ASK} — 확인 질문(STOP_CONFIRM)을 재생하고 공급자 출력을 막는다. 재생 완료를 서버가 받은 시각부터
 *       {@code stop-confirm-wait} 창이 열린다.
 *   <li>확인 중 첫 확정 전사: STOP이거나 참여 분류 ACCEPT면 {@link Decision#CLOSE}, 그 밖이면 {@link Decision#RESUME}(차단 해제 + 응답 생성
 *       요청 1회). 창이 끝나도(창 끝 시각 포함) RESUME이다(무응답만으로 종료를 확정하지 않는다, AC6.3.7).
 * </ul>
 */
public final class StopConfirmFlow {

    /** 부르는 쪽이 할 일. */
    public enum Decision {
        /** 아무것도 하지 않는다. */
        NONE,
        /** 확인 질문을 재생하고 출력을 막는다. */
        ASK,
        /** 종료를 확정한다(CLOSING(CHILD_STOP)). */
        CLOSE,
        /** 차단을 풀고 대화를 잇는다(응답 생성 요청 1회). */
        RESUME
    }

    /** 단계. */
    public enum Phase {
        /** 확인 중이 아니다. */
        IDLE,
        /** 확인 질문을 재생하는 중(창이 아직 없다). */
        ASKING,
        /** 재생이 끝나 대답을 기다린다(창이 열려 있다). */
        WAITING
    }

    private final Duration wait;
    private final boolean serviceGuardrail;
    private Phase phase = Phase.IDLE;
    private Instant windowStart;

    /**
     * @param wait 확인 질문 창({@code mujoong.timing.session.stop-confirm-wait})
     */
    public StopConfirmFlow(Duration wait) {
        this(wait, false);
    }

    public StopConfirmFlow(Duration wait, boolean serviceGuardrail) {
        Objects.requireNonNull(wait, "wait");
        if (wait.isNegative() || wait.isZero()) {
            throw new IllegalArgumentException("확인 질문 창은 0보다 커야 한다");
        }
        this.wait = wait;
        this.serviceGuardrail = serviceGuardrail;
    }

    /** 확인 중이 아닐 때 종료 의도 판정 결과를 받는다. 확인 중이면 {@link Decision#NONE}(대답은 {@link #onReply}가 본다). */
    public Decision onJudgment(StopIntentJudge.Verdict verdict) {
        Objects.requireNonNull(verdict, "verdict");
        if (phase != Phase.IDLE) {
            return Decision.NONE;
        }
        return switch (verdict) {
            case STOP -> Decision.CLOSE;
            case CONFIRM -> {
                phase = Phase.ASKING;
                windowStart = null;
                yield Decision.ASK;
            }
            default -> Decision.NONE;
        };
    }

    /** 확인 질문 재생이 끝났다(서버가 끝 표식을 받은 시각): 창을 연다. */
    public void promptPlayed(Instant at) {
        Objects.requireNonNull(at, "at");
        if (phase == Phase.ASKING) {
            phase = Phase.WAITING;
            windowStart = at;
        }
    }

    /**
     * 확인 중 첫 확정 전사(BR4.4). 창 끝 뒤에 온 대답은 만료가 먼저다.
     *
     * @param stopVerdict 그 전사의 종료 의도 판정이 STOP인가
     * @param at 전사 확정 시각
     */
    public Decision onReply(String transcript, boolean stopVerdict, Instant at) {
        Objects.requireNonNull(at, "at");
        if (phase == Phase.IDLE) {
            return Decision.NONE;
        }
        if (serviceGuardrail && stopVerdict) return finish(Decision.CLOSE);
        if (serviceGuardrail && phase == Phase.ASKING) return Decision.NONE;
        if (expired(at)) {
            return finish(Decision.RESUME);
        }
        JoinTracker.Reply reply = JoinTracker.classify(transcript);
        if (!serviceGuardrail) return finish(stopVerdict || reply == JoinTracker.Reply.ACCEPT ? Decision.CLOSE : Decision.RESUME);
        if (reply == JoinTracker.Reply.ACCEPT) return finish(Decision.CLOSE);
        if (reply == JoinTracker.Reply.REFUSE) return finish(Decision.RESUME);
        return Decision.NONE;
    }

    /** 시간 경과 확인: 창이 끝났으면(창 끝 시각 포함) RESUME. */
    public Decision tick(Instant now) {
        Objects.requireNonNull(now, "now");
        return expired(now) ? finish(Decision.RESUME) : Decision.NONE;
    }

    /** 기술 일시정지 동안 창을 멈춘다(열린 창의 시작을 멈춘 시간만큼 민다, U4b-J5). */
    public void shift(Duration paused) {
        Objects.requireNonNull(paused, "paused");
        if (phase == Phase.WAITING && windowStart != null && paused.isPositive()) {
            windowStart = windowStart.plus(paused);
        }
    }

    /** 열린 창의 끝(없으면 null). */
    public Instant windowEnd() {
        return phase == Phase.WAITING && windowStart != null ? windowStart.plus(wait) : null;
    }

    /** 확인 중인가(질문 재생 중이거나 창이 열려 있다 — 공급자 출력을 막고 공백 시계를 멈춘다). */
    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    public Phase phase() {
        return phase;
    }

    /** 흐름을 버린다(CLOSING 진입 등). */
    public void cancel() {
        phase = Phase.IDLE;
        windowStart = null;
    }

    private boolean expired(Instant at) {
        return phase == Phase.WAITING && windowStart != null && !at.isBefore(windowStart.plus(wait));
    }

    private Decision finish(Decision decision) {
        phase = Phase.IDLE;
        windowStart = null;
        return decision;
    }
}
