package kr.mujoong.realtime.session;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.mujoong.contract.status.EndRequestReason;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.session.SessionMailbox.EndOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 끝내기 · 정리 명령 처리 (U1 3.5 Step 23 · BR6.7 · BR6.9 · J54 응답표 · J111 (v)(w) · 편차 (f) · PD-1 표 2.1-D).
 *
 * <p>실행기가 있으면(INIT 포함) 안전 편지함에 넣고 약속을 {@code mujoong.limits.db-lock-timeout}까지 기다린다 — 결과는 실행기가 J54
 * 응답표대로 채운다. 늦게 끝난 결과는 버려지고 같은 명령은 멱등이다. 실행기가 없으면 DB 행으로 답한다:
 *
 * <ul>
 *   <li>GUARDIAN_STOP: 모르는 id → 409 (w), CLOSING → 202 {CLOSING}, 그 밖 → 409.
 *   <li>CORE_ABANDONED: 모르는 id → 10분 묘비 + 202 {} ((가) · (v)), 실행기 없는 비종료(PREPARING · DEVICE_ACK · ACTIVE) → FAILED(실패 코드
 *       없음) + 202 {}, CLOSING · 종료 → 202 {} ((다) · (라)).
 *   <li>CONSENT_WITHDRAWN: U1에 없는 전이다(U4가 더한다). 400.
 * </ul>
 */
@Component
public class EndRequestHandler {

    /** 실행기가 제한 시간 안에 답하지 않았다(내부 HTTP 503). */
    public static final class EndTimeoutException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public EndTimeoutException() {
            super("끝내기 명령이 제한 시간 안에 처리되지 않았다");
        }
    }

    private final SessionRegistry registry;
    private final TombstoneMap tombstones;
    private final SessionTransitions transitions;
    private final MujoongClock clock;
    private final Duration wait;

    @Autowired
    public EndRequestHandler(SessionRegistry registry, TombstoneMap tombstones, SessionTransitions transitions,
            MujoongClock clock, RealtimeProperties properties) {
        this(registry, tombstones, transitions, clock, properties.limits().dbLockTimeout());
    }

    public EndRequestHandler(SessionRegistry registry, TombstoneMap tombstones, SessionTransitions transitions,
            MujoongClock clock, Duration wait) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tombstones = Objects.requireNonNull(tombstones, "tombstones");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.wait = Objects.requireNonNull(wait, "wait");
    }

    /** 명령을 처리한다. */
    public EndOutcome end(UUID sessionId, UUID requestId, EndRequestReason reason, String traceId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (reason != EndRequestReason.GUARDIAN_STOP && reason != EndRequestReason.CORE_ABANDONED) {
            return EndOutcome.UNSUPPORTED;
        }
        Optional<SessionMailbox> mailbox = registry.mailbox(sessionId);
        if (mailbox.isPresent()) {
            CompletableFuture<EndOutcome> result = new CompletableFuture<>();
            if (mailbox.get().putSafety(new SessionMailbox.EndRequested(requestId, reason, result))) {
                Optional<EndOutcome> answered = await(result);
                if (answered.isPresent()) {
                    return answered.get();
                }
            }
        }
        return withoutExecutor(sessionId, reason, traceId);
    }

    /** 실행기의 답. 실행기가 처리하지 못하고 끝났으면 빈 값(DB로 답한다). */
    private Optional<EndOutcome> await(CompletableFuture<EndOutcome> result) {
        try {
            return Optional.of(result.get(wait.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EndTimeoutException();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SessionMailbox.SessionGone) {
                return Optional.empty();
            }
            throw new EndTimeoutException();
        } catch (TimeoutException e) {
            throw new EndTimeoutException();
        }
    }

    private EndOutcome withoutExecutor(UUID sessionId, EndRequestReason reason, String traceId) {
        Optional<ActivitySessionRow> row = transitions.find(sessionId);
        if (reason == EndRequestReason.GUARDIAN_STOP) {
            return row.filter(value -> value.status() == SessionStatus.CLOSING).map(value -> EndOutcome.CLOSING)
                    .orElse(EndOutcome.NOT_ACTIVE);
        }
        if (row.isEmpty()) {
            tombstones.add(sessionId);
            return EndOutcome.ACCEPTED_EMPTY;
        }
        SessionStatus status = row.get().status();
        if (TransitionTable.Trigger.CORE_ABANDONED.origins().contains(status)) {
            transitions.transitionFromAny(sessionId, TransitionTable.Trigger.CORE_ABANDONED.origins(),
                    SessionStatus.FAILED,
                    read -> TransitionTable.changeFor(TransitionTable.Trigger.CORE_ABANDONED, null, clock.now()),
                    SessionTransitions.Origin.CORE_ABANDONED, traceId);
        }
        return EndOutcome.ACCEPTED_EMPTY;
    }
}
