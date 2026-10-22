package kr.mujoong.realtime.session;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import kr.mujoong.contract.time.TimerDriver;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.runtime.TimerClocks;

/**
 * 세션 하나의 시계 예약 (U1 3.5 Step 23 · BR6.12 ② ③ ④ · RD-1 · RD-3 발동 규약 · PD-1 · NFR6.2 · NFR6.3).
 *
 * <p>종류마다 살아 있는 예약은 하나이고 무장할 때마다 세대 번호가 오른다. 만기 때 타이머 스레드는
 * {@link SessionMailbox.TimerFired}(종류, 세대)를 안전 편지함에 넣기만 한다(규약 1). 실행기는 {@link #isCurrent}로 세대를 확인해 낡은
 * 만기를 버리고(규약 2), 경과를 다시 재 남은 시간이 있으면 다시 무장한다(규약 3, 지연 재무장). 기기 응답 · 종료 확인 · 끊김 · 재생
 * 만료 · DB 재시도는 안전 시계, 구간 조용 시간은 구간 조용 시간 시계에서 돈다(RD-2 스레드 표). 시험은 두 시계에 수동 구동기를 넣는다.
 */
@SingleInstanceAssumption(si = "SI-3",
        reason = "세션 시한(40초 끊김 · 기기 응답 · 종료 확인 등) 예약이 이 프로세스 메모리에만 있어 다른 인스턴스가 받은 HEARTBEAT를 못 보고 ABORTED(DISCONNECT)가 오발동한다")
public final class SessionTimers {

    /** 시계 종류. */
    public enum Kind {
        /** BR6.12 ② 20초(START_SESSION 송신 시도부터). */
        DEVICE_ACK(false),
        /** BR6.12 ③ 30초(CLOSING 진입부터). */
        CLOSING_CONFIRM(false),
        /** BR6.12 ④ 40초(묶인 연결의 마지막 기기 메시지부터). */
        DISCONNECT(false),
        /** BR7.3 구간 조용 시간 800ms. */
        SEGMENT_QUIET(true),
        /** 재생 중 승인 문구 표시 만료(R3-19, output-queue-max 20초). */
        PLAYBACK_EXPIRY(false),
        /** 전이 트랜잭션이 DB 장애로 실패한 시한의 재전달(session.timer-tick). */
        REDELIVER(false),
        /** U4b 참여 창 끝(join-wait · join-wait-after-reprompt, BR2.2). */
        JOIN_WINDOW(false),
        /** U4b 종료 확인 질문 창 끝(stop-confirm-wait, BR4.3). */
        STOP_CONFIRM(false),
        /** U4b 공백 시계의 다음 경고 · 종료 시각(silence-warning · silence-end, BR5.3 · BR5.4). */
        SILENCE(false),
        /** U4b 마지막 기기 메시지 뒤 기술 일시정지(gap-display, BR6.1). */
        GAP_DISPLAY(false),
        /** U4b 종료 뒤 정산 창 상한(record-settle, BR8.1). */
        RECORD_SETTLE(false),
        /** U11 A3: 지시를 보낸 뒤 새 말이 시작하기를 기다리는 시한(response-ready, 지시 송신 시각부터). */
        INSTRUCTION_WAIT(false),
        /** 참여 수락 때 보관한 응답이 비어 있으면 모델이 스스로 첫 응답을 시작하기를 기다리는 시간(provider.join-self-start-wait). */
        JOIN_SELF_START(false),
        /** U11 A7: 입력 전사 조각이 이만큼 오지 않으면 그 묶음을 확정 전사로 본다(input-transcript-quiet). */
        INPUT_TRANSCRIPT_QUIET(true),
        /** U11 BR1.4: 판정 턴 열기 + live-budget(4s). 정확히 4.000초에 닿으면 보류(TIMEOUT). */
        JUDGMENT_LIVE(false),
        /** U11 BR4.1: 판정 대상 확정 전사 시각 + response-ready(10s). 판정 단계에만 걸고 지시 단계 진입 때 푼다. */
        JUDGMENT_READY(false),
        /** U11 BR4.5: 지시 단계 진입 + response-ready(10s). 지시 응답이 시작되면 끈다. */
        JUDGMENT_INSTRUCTION(false),
        /** U13 안전망: 제브 요청을 보낸 뒤 검사 상한(jev-budget)의 두 배. 포트 규약대로 결과가 오면 끄고, 오지 않으면 시간 초과 막힘으로 닫는다. */
        GUARDRAIL_CHECK(false);

        private final boolean quietClock;

        Kind(boolean quietClock) {
            this.quietClock = quietClock;
        }

        /** 구간 조용 시간 시계에서 도는가. */
        public boolean quietClock() {
            return quietClock;
        }
    }

    private final TimerClocks clocks;
    private final Consumer<SessionMailbox.TimerFired> sink;
    private final Map<Kind, Armed> armed = new EnumMap<>(Kind.class);
    private final Map<Kind, Long> generations = new EnumMap<>(Kind.class);

    /**
     * @param sink 만기 메시지를 안전 편지함에 넣는 함수(타이머 스레드에서 불린다)
     */
    public SessionTimers(TimerClocks clocks, Consumer<SessionMailbox.TimerFired> sink) {
        this.clocks = Objects.requireNonNull(clocks, "clocks");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** 무장한다(같은 종류의 이전 예약은 취소). 새 세대 번호를 돌려준다. */
    public synchronized long arm(Kind kind, Duration delay) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(delay, "delay");
        cancelQuietly(kind);
        long generation = generations.merge(kind, 1L, Long::sum);
        Duration nonNegative = delay.isNegative() ? Duration.ZERO : delay;
        TimerDriver driver = kind.quietClock() ? clocks.quiet() : clocks.safety();
        SessionMailbox.TimerFired fired = new SessionMailbox.TimerFired(kind, generation);
        TimerDriver.TimerHandle handle = driver.schedule(nonNegative, () -> sink.accept(fired));
        armed.put(kind, new Armed(generation, handle));
        return generation;
    }

    /** 취소한다(세대를 올려 이미 편지함에 들어간 만기도 무효로 만든다). */
    public synchronized void cancel(Kind kind) {
        cancelQuietly(kind);
        generations.merge(kind, 1L, Long::sum);
    }

    /** 이 만기가 지금 살아 있는 예약의 것인가. 맞으면 예약을 소비한다. */
    public synchronized boolean consume(SessionMailbox.TimerFired fired) {
        Armed current = armed.get(fired.kind());
        if (current == null || current.generation() != fired.generation()) {
            return false;
        }
        armed.remove(fired.kind());
        return true;
    }

    /** 이 세대가 지금 세대인가(소비하지 않는다). */
    public synchronized boolean isCurrent(Kind kind, long generation) {
        Armed current = armed.get(kind);
        return current != null && current.generation() == generation;
    }

    /** 무장돼 있는가. */
    public synchronized boolean isArmed(Kind kind) {
        return armed.containsKey(kind);
    }

    /** 예약된 만기 시각(무장돼 있지 않으면 null). */
    public synchronized Instant deadline(Kind kind) {
        Armed current = armed.get(kind);
        return current == null ? null : current.handle().deadline();
    }

    /** 모두 취소한다(종료 정리). */
    public synchronized void cancelAll() {
        for (Kind kind : Kind.values()) {
            cancel(kind);
        }
    }

    private void cancelQuietly(Kind kind) {
        Armed previous = armed.remove(kind);
        if (previous != null) {
            previous.handle().cancel();
        }
    }

    private record Armed(long generation, TimerDriver.TimerHandle handle) {
    }
}
