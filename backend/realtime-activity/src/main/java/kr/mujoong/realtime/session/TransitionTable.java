package kr.mujoong.realtime.session;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;

/**
 * U1 세션 전이 표 (U1 3.5 Step 23 · BR6.8 · BR6.12 · BR6.13 · J126).
 *
 * <p>세션 실행기 · 전이 장치 · 재시작 정리가 같은 표를 쓴다. 사건(Trigger)마다 출발 상태 집합 · 도착 상태가 하나이고 도착 상태가
 * end_reason을 정한다(ENDED = GUARDIAN_STOP, ENDED_UNCONFIRMED = UNCONFIRMED, ABORTED = DISCONNECT, FAILED = FAILED). 표에 없는 전이는
 * 성립하지 않는다(무시하고 SessionEvent에 기록, BR6.8 위반 행). WAITING_CHILD · DECLINED · CHILD_STOP · SILENCE ·
 * CONSENT_WITHDRAWN 전이는 U4가 더한다.
 */
public final class TransitionTable {

    /** 전이를 일으키는 사건. */
    public enum Trigger {
        /** START_SESSION에 대한 ACK. */
        DEVICE_ACK(EnumSet.of(SessionStatus.PREPARING), SessionStatus.DEVICE_ACK, null),
        /** DEVICE_READY(OK)와 공급자 session.started가 모두 확인됨. */
        ACTIVATED(EnumSet.of(SessionStatus.DEVICE_ACK), SessionStatus.ACTIVE, null),
        /** DEVICE_READY가 mic · player 사용 불가를 알림(즉시). */
        DEVICE_UNAVAILABLE(EnumSet.of(SessionStatus.DEVICE_ACK), SessionStatus.FAILED, FailureCode.DEVICE_NO_ACK),
        /** START_SESSION 전송 실패(소켓 없음 · 전송 예외, BR6.6 ⑤). */
        START_SEND_FAILED(EnumSet.of(SessionStatus.PREPARING), SessionStatus.FAILED, FailureCode.DEVICE_NO_ACK),
        /** BR6.12 ② 20초 시한. 실패 코드는 무엇이 빠졌는지로 실행기가 정한다(J59 (h)). */
        START_TIMEOUT(EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK), SessionStatus.FAILED, null),
        /** 공급자 연결 · 시작 실패(20초를 기다리지 않음). */
        PROVIDER_FAILED(EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK), SessionStatus.FAILED,
                FailureCode.PROVIDER_UNAVAILABLE),
        /** Core 취소(BR6.7): failure_code 비움. */
        CORE_ABANDONED(EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, SessionStatus.ACTIVE),
                SessionStatus.FAILED, null),
        /** Realtime 재시작 · 실행기 최상위 예외 · 고아 세션(BR6.13 · J126 · RD-3). */
        SERVER_RESTART(EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, SessionStatus.ACTIVE,
                SessionStatus.CLOSING), SessionStatus.FAILED, FailureCode.SERVER_RESTART),
        /** 보호자 끝내기(BR6.9). */
        GUARDIAN_STOP(EnumSet.of(SessionStatus.ACTIVE), SessionStatus.CLOSING, null),
        /** VOICE_SESSION_CLOSED 확인(BR6.10 ④). */
        VOICE_CLOSED(EnumSet.of(SessionStatus.CLOSING), SessionStatus.ENDED, null),
        /** BR6.12 ③ 30초 시한. */
        CLOSING_TIMEOUT(EnumSet.of(SessionStatus.CLOSING), SessionStatus.ENDED_UNCONFIRMED, null),
        /** BR6.12 ④ 40초 끊김. */
        DISCONNECT(EnumSet.of(SessionStatus.ACTIVE), SessionStatus.ABORTED, null);

        private final Set<SessionStatus> origins;
        private final SessionStatus target;
        private final FailureCode failureCode;

        Trigger(Set<SessionStatus> origins, SessionStatus target, FailureCode failureCode) {
            this.origins = Set.copyOf(origins);
            this.target = target;
            this.failureCode = failureCode;
        }

        /** 출발 상태 집합. */
        public Set<SessionStatus> origins() {
            return origins;
        }

        /** 도착 상태. */
        public SessionStatus target() {
            return target;
        }

        /** 정해진 실패 코드(없거나 실행기가 정하면 null). */
        public FailureCode failureCode() {
            return failureCode;
        }

        /** 출발 상태가 둘 이상인가(FOR UPDATE로 읽은 상태를 조건으로 쓴다, BR6.8). */
        public boolean multiOrigin() {
            return origins.size() > 1;
        }
    }

    private static final Map<SessionStatus, EndReason> END_REASONS = new EnumMap<>(Map.of(
            SessionStatus.ENDED, EndReason.GUARDIAN_STOP,
            SessionStatus.ENDED_UNCONFIRMED, EndReason.UNCONFIRMED,
            SessionStatus.ABORTED, EndReason.DISCONNECT,
            SessionStatus.FAILED, EndReason.FAILED));

    private TransitionTable() {
    }

    /** 표에 있는 전이인가. */
    public static boolean allowed(SessionStatus from, SessionStatus to) {
        if (from == null || to == null) {
            return false;
        }
        for (Trigger trigger : Trigger.values()) {
            if (trigger.target() == to && trigger.origins().contains(from)) {
                return true;
            }
        }
        return false;
    }

    /** 도착 상태의 end_reason(종료 상태가 아니면 null). */
    public static EndReason endReason(SessionStatus to) {
        return to == null ? null : END_REASONS.get(to);
    }

    /** START 구간(PREPARING · DEVICE_ACK)에서의 전이인가(SESSION_TRANSITION phase). */
    public static boolean isStartPhase(SessionStatus from) {
        return from == SessionStatus.PREPARING || from == SessionStatus.DEVICE_ACK;
    }

    /**
     * 20초 시한의 실패 코드(J59 (h)): ACK나 DEVICE_READY(OK)가 빠졌으면 DEVICE_NO_ACK, 둘 다 왔는데 session.started가 없으면
     * PROVIDER_UNAVAILABLE.
     */
    public static FailureCode startTimeoutCode(boolean ackReceived, boolean deviceReady) {
        return ackReceived && deviceReady ? FailureCode.PROVIDER_UNAVAILABLE : FailureCode.DEVICE_NO_ACK;
    }

    /** 사건의 SessionChange(종료 상태면 end_reason · 실패 코드 · 끝난 시각). */
    public static SessionChange changeFor(Trigger trigger, FailureCode failureCode, java.time.Instant at) {
        Objects.requireNonNull(trigger, "trigger");
        EndReason reason = endReason(trigger.target());
        if (reason == null) {
            return SessionChange.none();
        }
        SessionChange change = SessionChange.ended(reason, at);
        FailureCode code = failureCode != null ? failureCode : trigger.failureCode();
        return code == null ? change : change.withFailureCode(code);
    }
}
