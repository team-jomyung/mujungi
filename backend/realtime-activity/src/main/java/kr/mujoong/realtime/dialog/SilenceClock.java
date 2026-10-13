package kr.mujoong.realtime.dialog;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 공백 시계 (U4b L4 · FR4.1 · BR5.1 ~ BR5.4 · U4b-R6 · U4b-J10 · U4b-J11 · FD-Q2).
 *
 * <p>순수 계산만 한다 — 시계 예약 · 재생 · 전이는 부르는 쪽(세션 실행기)이 맡는다. 시각은 인자로 받는다.
 *
 * <ul>
 *   <li>{@link #reset}: ACTIVE 진입 · 아이의 빈 아닌 확정 전사 — 누적을 0으로 하고 경고를 끈다(빈 전사 · 안내 재생은 리셋하지 않는다).
 *   <li>{@link #setPaused}: 기기 재생 중 · 서버 출력 대기 · 기술 일시정지 · 확인 질문 대기 중 하나라도 참이면 멈추고, 모두 거짓이 되면 이어서 센다.
 *       멈춤은 리셋하지 않는다.
 *   <li>{@link #tick}: 누적이 {@code silence-warning}에 닿으면 WARN(한 번), {@code silence-end}에 닿으면 END.
 * </ul>
 */
public final class SilenceClock {

    /** 부르는 쪽이 할 일. */
    public enum Decision {
        NONE,
        /** 공백 경고를 켜고 SILENCE_WARNING을 한 번 재생한다. */
        WARN,
        /** CLOSING(SILENCE). */
        END
    }

    /** 공백 경고 값(진행 조회 silenceWarning). */
    public record Warning(Instant silentSince, Instant endsAt) {
    }

    private final Duration warning;
    private final Duration end;
    private boolean started;
    private Instant silentSince;
    private Duration accumulated = Duration.ZERO;
    private Instant runningSince;
    private boolean paused;
    private boolean warned;

    /**
     * @param warning 경고 누적({@code mujoong.timing.session.silence-warning})
     * @param end 종료 누적({@code mujoong.timing.session.silence-end}), 경고보다 길어야 한다
     */
    public SilenceClock(Duration warning, Duration end) {
        this.warning = Objects.requireNonNull(warning, "warning");
        this.end = Objects.requireNonNull(end, "end");
        if (!warning.isPositive() || end.compareTo(warning) <= 0) {
            throw new IllegalArgumentException("공백 경고는 0보다 크고 공백 종료는 경고보다 길어야 한다");
        }
    }

    /** 누적을 0으로 하고 경고를 끈다(BR5.1). 멈춤 상태는 그대로 둔다. */
    public void reset(Instant at) {
        Objects.requireNonNull(at, "at");
        started = true;
        silentSince = at;
        accumulated = Duration.ZERO;
        warned = false;
        runningSince = paused ? null : at;
    }

    /** 멈춤 조건이 바뀌었다(BR5.2). 같은 값이면 아무것도 하지 않는다. */
    public void setPaused(boolean value, Instant at) {
        Objects.requireNonNull(at, "at");
        if (value == paused) {
            return;
        }
        paused = value;
        if (!started) {
            return;
        }
        if (value) {
            accumulated = accumulated(at);
            runningSince = null;
        } else {
            runningSince = at;
        }
    }

    /** 지금까지 센 공백(멈춘 시간 제외). */
    public Duration accumulated(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!started) {
            return Duration.ZERO;
        }
        if (runningSince == null) {
            return accumulated;
        }
        Duration running = Duration.between(runningSince, now);
        return accumulated.plus(running.isNegative() ? Duration.ZERO : running);
    }

    /** 경고 · 종료 판정. 경고는 리셋 전까지 한 번만 낸다. */
    public Decision tick(Instant now) {
        if (!started) {
            return Decision.NONE;
        }
        Duration silent = accumulated(now);
        if (silent.compareTo(end) >= 0) {
            return Decision.END;
        }
        if (!warned && silent.compareTo(warning) >= 0) {
            warned = true;
            return Decision.WARN;
        }
        return Decision.NONE;
    }

    /** 다음 판정 시각(경고 전이면 경고 시각, 경고 뒤면 종료 시각). 멈췄거나 시작 전이면 null. */
    public Instant nextDeadline(Instant now) {
        if (!started || runningSince == null) {
            return null;
        }
        Duration target = warned ? end : warning;
        return now.plus(target.minus(accumulated(now)));
    }

    /** 켜진 공백 경고(진행 조회 값, BR5.3). 꺼져 있으면 null. endsAt은 지금부터 남은 누적이 쉬지 않고 흐른다고 본 종료 예상 시각이다. */
    public Warning warning(Instant now) {
        if (!warned) {
            return null;
        }
        return new Warning(silentSince, now.plus(end.minus(accumulated(now))));
    }

    public boolean isWarned() {
        return warned;
    }

    public boolean isPaused() {
        return paused;
    }
}
