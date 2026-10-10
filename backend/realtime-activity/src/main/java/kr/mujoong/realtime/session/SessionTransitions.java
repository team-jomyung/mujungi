package kr.mujoong.realtime.session;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import kr.mujoong.contract.status.SessionStatus;

/**
 * 세션 실행기가 세션 행을 바꾸는 입구 (U1 3.5 Step 23 · BR6.8 · RD-3 · RD-22).
 *
 * <p>운영 구현은 {@link TransitionService}(한 전이 = 한 짧은 트랜잭션 + SessionStateChanged)이고, 실행기 단위 시험은 메모리 대역을
 * 넣는다. DB 장애는 {@code DataAccessException} 계열로 던진다(실행기가 timer-tick 뒤 다시 전달한다).
 */
public interface SessionTransitions {

    /** 전이를 일으킨 쪽(SESSION_TRANSITION origin). */
    enum Origin {
        REALTIME,
        CORE_ABANDONED
    }

    /** 한 출발 상태 전이. 이기면 바뀐 행, 지면(0행) 빈 값(무시하고 SessionEvent에 기록). */
    Optional<ActivitySessionRow> transition(UUID sessionId, SessionStatus from, SessionStatus to, SessionChange change,
            Origin origin, String traceId);

    /** 출발 상태가 둘 이상인 전이(FOR UPDATE로 읽은 상태를 조건으로). */
    ActivitySessionRepository.MultiOriginTransition transitionFromAny(UUID sessionId, Set<SessionStatus> origins,
            SessionStatus to, Function<SessionStatus, SessionChange> changeFor, Origin origin, String traceId);

    /** 상태는 그대로 두고 열 값만 쓴다(그 상태일 때만). */
    Optional<ActivitySessionRow> annotate(UUID sessionId, SessionStatus expected, SessionChange change);

    /** 지금 행. */
    Optional<ActivitySessionRow> find(UUID sessionId);
}
