package kr.mujoong.realtime.session;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.status.SessionStatus;

/**
 * 세션 실행기가 세션 행을 바꾸는 입구 (U1 3.5 Step 23 · BR6.8 · RD-3 · RD-22 · U4b BR3.2 · BR7.2).
 *
 * <p>운영 구현은 {@link TransitionService}(한 전이 = 한 짧은 트랜잭션 + SessionStateChanged)이고, 실행기 단위 시험은 메모리 대역을
 * 넣는다. DB 장애는 {@code DataAccessException} 계열로 던진다(실행기가 timer-tick 뒤 다시 전달한다).
 *
 * <p>U4b: 전이와 같은 트랜잭션에서 할 일({@link Extras})을 함께 넘길 수 있다 — 전이 시점 기술 일시정지를 SessionStateChanged에 싣고(BR7.2),
 * DECLINED 전이는 그 세션의 전사 행을 지운다(BR3.2).
 */
public interface SessionTransitions {

    /** 전이를 일으킨 쪽(SESSION_TRANSITION origin). */
    enum Origin {
        REALTIME,
        CORE_ABANDONED
    }

    /**
     * 전이와 함께 쓰는 값(U4b).
     *
     * @param technicalPause 전이 시점에 켜진 기술 일시정지(없으면 null, SessionStateChanged.technicalPause · BR7.2)
     * @param eraseTranscripts 같은 트랜잭션에서 그 세션의 전사 행을 지운다(DECLINED, BR3.2)
     */
    record Extras(SessionStateChanged.TechnicalPause technicalPause, boolean eraseTranscripts) {

        private static final Extras NONE = new Extras(null, false);

        /** 함께 쓸 값 없음. */
        public static Extras none() {
            return NONE;
        }

        /** 기술 일시정지만 싣는다(없으면 {@link #none()}). */
        public static Extras pause(SessionStateChanged.TechnicalPause technicalPause) {
            return technicalPause == null ? NONE : new Extras(technicalPause, false);
        }
    }

    /** 한 출발 상태 전이. 이기면 바뀐 행, 지면(0행) 빈 값(무시하고 SessionEvent에 기록). */
    Optional<ActivitySessionRow> transition(UUID sessionId, SessionStatus from, SessionStatus to, SessionChange change,
            Origin origin, String traceId);

    /** U4b: 함께 쓸 값이 있는 한 출발 상태 전이. 기본 구현은 값을 버리고 {@link #transition}을 부른다(대역용). */
    default Optional<ActivitySessionRow> transition(UUID sessionId, SessionStatus from, SessionStatus to,
            SessionChange change, Origin origin, String traceId, Extras extras) {
        return transition(sessionId, from, to, change, origin, traceId);
    }

    /** 출발 상태가 둘 이상인 전이(FOR UPDATE로 읽은 상태를 조건으로). */
    ActivitySessionRepository.MultiOriginTransition transitionFromAny(UUID sessionId, Set<SessionStatus> origins,
            SessionStatus to, Function<SessionStatus, SessionChange> changeFor, Origin origin, String traceId);

    /** 상태는 그대로 두고 열 값만 쓴다(그 상태일 때만). */
    Optional<ActivitySessionRow> annotate(UUID sessionId, SessionStatus expected, SessionChange change);

    /** 지금 행. */
    Optional<ActivitySessionRow> find(UUID sessionId);
}
