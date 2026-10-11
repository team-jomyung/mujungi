package kr.mujoong.realtime.session;

import java.util.Objects;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.common.scaling.SingleInstanceAssumption;
import kr.mujoong.realtime.observability.EventLogger;
import org.springframework.stereotype.Component;

/**
 * 고아 세션 점검 (U1 3.5 Step 23 · RD-3 · R3-6 · J126 · NFR6.3 · reliability-design 2.1 시한 표).
 *
 * <p>maintenance 스레드가 {@code mujoong.session.orphan-check-interval}(30초)마다 부른다. 비종료 세션 행 중 살아 있는 실행기(INIT 포함)가
 * 없는 것은 시한도 함께 사라졌으므로 FAILED(SERVER_RESTART)로 닫는다(실행기 최상위 예외 뒤 전이가 DB 장애로 실패한 경우). 세션마다 APP_ERROR
 * {@code OrphanSessionDetected} 한 줄을 남긴다. INIT 등록은 세션 행 INSERT보다 먼저라 시작 중인 세션을 고아로 보지 않는다(SCD-1).
 */
@Component
@SingleInstanceAssumption(si = {"SI-2", "SI-7"},
        reason = "이 인스턴스 레지스트리에 실행기가 없는 비종료 세션을 고아로 닫으므로 다른 인스턴스 실행기가 도는 세션도 FAILED(SERVER_RESTART)가 된다")
public class OrphanSessionChecker {

    private final ActivitySessionRepository sessions;
    private final SessionRegistry registry;
    private final SessionTransitions transitions;
    private final EventLogger events;
    private final MujoongClock clock;

    public OrphanSessionChecker(ActivitySessionRepository sessions, SessionRegistry registry,
            SessionTransitions transitions, EventLogger events, MujoongClock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 한 번 점검한다. 닫은 세션 수. */
    public int runOnce() {
        int closed = 0;
        for (ActivitySessionRow row : sessions.findInProgress()) {
            if (registry.isAlive(row.id())) {
                continue;
            }
            events.appError(EventLog.Component.SESSION_LIFECYCLE, "OrphanSessionDetected");
            ActivitySessionRepository.MultiOriginTransition result = transitions.transitionFromAny(row.id(),
                    TransitionTable.Trigger.SERVER_RESTART.origins(), SessionStatus.FAILED,
                    read -> TransitionTable.changeFor(TransitionTable.Trigger.SERVER_RESTART, null, clock.now()),
                    SessionTransitions.Origin.REALTIME, null);
            if (result.transitioned()) {
                closed++;
            }
        }
        return closed;
    }
}
