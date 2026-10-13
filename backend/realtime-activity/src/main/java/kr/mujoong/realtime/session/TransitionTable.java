package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;

/**
 * 세션 전이 표 (U1 3.5 Step 23 · BR6.8 · BR6.12 · BR6.13 · J126 · U4b L2 · BR1.1 ~ BR1.4 · BR1.8 · BR1.9 · U4b-R1 · U4b-J7 · FD-Q6).
 *
 * <p>세션 실행기 · 전이 장치 · 재시작 정리가 같은 표를 쓴다. 사건(Trigger)마다 출발 상태 집합 · 도착 상태가 하나이고, U4b부터는
 * <b>사건</b>이 end_reason · end_detail을 정한다(BR1.3): 보호자 끝내기 GUARDIAN_STOP, 동의 철회 CONSENT_WITHDRAWN, 종료 의도 확정 CHILD_STOP,
 * 연습 마지막 목표 끝 CHILD_STOP + end_detail PRACTICE_COMPLETED, 공백 6분 SILENCE, 참여 거절 DECLINED, 끊김 · 공급자 끊김 DISCONNECT, 시작
 * 실패 · Core 취소 · 재시작 FAILED(시작 단계 동의 철회만 CONSENT_WITHDRAWN). CLOSING에 들어갈 때 마무리 사유를 쓰고 ENDED는 그대로
 * 두며(음성 세션 닫힘), ENDED_UNCONFIRMED는 UNCONFIRMED로 덮고 처음 사유를 end_detail에 둔다(BR1.4 · {@link #closingTimeoutChange}). 표에
 * 없는 (사건, 상태)는 성립하지 않는다 — 전이 장치가 SessionEvent TRANSITION_IGNORED로 남긴다(BR1.1).
 */
public final class TransitionTable {

    /** 연습 마지막 목표 끝의 end_detail(FD-Q6). */
    public static final String END_DETAIL_PRACTICE_COMPLETED = "PRACTICE_COMPLETED";

    /** 전이를 일으키는 사건. */
    public enum Trigger {
        /** START_SESSION에 대한 ACK. */
        DEVICE_ACK(EnumSet.of(SessionStatus.PREPARING), SessionStatus.DEVICE_ACK, null, null, null),
        /** U4b: DEVICE_READY(OK)와 공급자 session.started가 모두 확인됨 → 참여 확인(WAITING_CHILD, BR2.1). */
        READY(EnumSet.of(SessionStatus.DEVICE_ACK), SessionStatus.WAITING_CHILD, null, null, null),
        /** U4b: 참여 수락(BR2.4) → ACTIVE. */
        JOIN_ACCEPTED(EnumSet.of(SessionStatus.WAITING_CHILD), SessionStatus.ACTIVE, null, null, null),
        /** U4b: 참여 거절(대안 뒤 재거부 · 재안내 뒤 재무응답, BR2.4) → DECLINED. */
        JOIN_DECLINED(EnumSet.of(SessionStatus.WAITING_CHILD), SessionStatus.DECLINED, EndReason.DECLINED, null, null),
        /** DEVICE_READY가 mic · player 사용 불가를 알림(즉시). */
        DEVICE_UNAVAILABLE(EnumSet.of(SessionStatus.DEVICE_ACK), SessionStatus.FAILED, EndReason.FAILED,
                FailureCode.DEVICE_NO_ACK, null),
        /** START_SESSION 전송 실패(소켓 없음 · 전송 예외, BR6.6 ⑤). */
        START_SEND_FAILED(EnumSet.of(SessionStatus.PREPARING), SessionStatus.FAILED, EndReason.FAILED,
                FailureCode.DEVICE_NO_ACK, null),
        /** BR6.12 ② 20초 시한. 실패 코드는 무엇이 빠졌는지로 실행기가 정한다(J59 (h)). */
        START_TIMEOUT(EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK), SessionStatus.FAILED,
                EndReason.FAILED, null, null),
        /** 공급자 연결 · 시작 실패(20초를 기다리지 않음). */
        PROVIDER_FAILED(EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK), SessionStatus.FAILED,
                EndReason.FAILED, FailureCode.PROVIDER_UNAVAILABLE, null),
        /** U4b: 시작 단계의 동의 철회 끝내기 → FAILED(CONSENT_WITHDRAWN), 실패 코드 없음(BR1.5 · U4b-J9). */
        CONSENT_WITHDRAWN_START(EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK), SessionStatus.FAILED,
                EndReason.CONSENT_WITHDRAWN, null, null),
        /** Core 취소(BR6.7 · BR1.9): failure_code 비움. U4b가 WAITING_CHILD를 더했다. */
        CORE_ABANDONED(EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, SessionStatus.WAITING_CHILD,
                SessionStatus.ACTIVE), SessionStatus.FAILED, EndReason.FAILED, null, null),
        /** Realtime 재시작 · 실행기 최상위 예외 · 고아 세션(BR6.13 · J126 · RD-3 · BR1.8). U4b가 WAITING_CHILD를 더했다. */
        SERVER_RESTART(EnumSet.of(SessionStatus.PREPARING, SessionStatus.DEVICE_ACK, SessionStatus.WAITING_CHILD,
                SessionStatus.ACTIVE, SessionStatus.CLOSING), SessionStatus.FAILED, EndReason.FAILED,
                FailureCode.SERVER_RESTART, null),
        /** 보호자 끝내기(BR6.9 · BR1.5). U4b: WAITING_CHILD에서도 끝낼 수 있다(AC3.3.8). */
        GUARDIAN_STOP(EnumSet.of(SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE), SessionStatus.CLOSING,
                EndReason.GUARDIAN_STOP, null, null),
        /** U4b: 진행 세션의 동의 철회 끝내기(BR1.5 · BR1.7). */
        CONSENT_WITHDRAWN(EnumSet.of(SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE), SessionStatus.CLOSING,
                EndReason.CONSENT_WITHDRAWN, null, null),
        /** U4b: 아이 종료 의도 확정(BR4.2 · BR4.4). */
        CHILD_STOP_CONFIRMED(EnumSet.of(SessionStatus.ACTIVE), SessionStatus.CLOSING, EndReason.CHILD_STOP, null, null),
        /** U4b: 연습 마지막 목표 끝(BR10.3 · FD-Q6) — 사유 CHILD_STOP, end_detail PRACTICE_COMPLETED. */
        PRACTICE_COMPLETED(EnumSet.of(SessionStatus.ACTIVE), SessionStatus.CLOSING, EndReason.CHILD_STOP, null,
                END_DETAIL_PRACTICE_COMPLETED),
        /** U4b: 공백 6분(BR5.4). */
        SILENCE_TIMEOUT(EnumSet.of(SessionStatus.ACTIVE), SessionStatus.CLOSING, EndReason.SILENCE, null, null),
        /** VOICE_SESSION_CLOSED 확인(BR6.10 ④). 사유는 CLOSING의 것을 그대로 둔다(BR1.4). */
        VOICE_CLOSED(EnumSet.of(SessionStatus.CLOSING), SessionStatus.ENDED, null, null, null),
        /** BR6.12 ③ 30초 시한. UNCONFIRMED로 덮고 처음 사유를 end_detail에 둔다({@link #closingTimeoutChange}). */
        CLOSING_TIMEOUT(EnumSet.of(SessionStatus.CLOSING), SessionStatus.ENDED_UNCONFIRMED, EndReason.UNCONFIRMED, null,
                null),
        /** BR6.12 ④ 40초 끊김 · 공급자 끊김(U3-R8). U4b가 WAITING_CHILD를 더했다(BR6.3). */
        DISCONNECT(EnumSet.of(SessionStatus.WAITING_CHILD, SessionStatus.ACTIVE), SessionStatus.ABORTED,
                EndReason.DISCONNECT, null, null);

        private final Set<SessionStatus> origins;
        private final SessionStatus target;
        private final EndReason endReason;
        private final FailureCode failureCode;
        private final String endDetail;

        Trigger(Set<SessionStatus> origins, SessionStatus target, EndReason endReason, FailureCode failureCode,
                String endDetail) {
            this.origins = Set.copyOf(origins);
            this.target = target;
            this.endReason = endReason;
            this.failureCode = failureCode;
            this.endDetail = endDetail;
        }

        /** 출발 상태 집합. */
        public Set<SessionStatus> origins() {
            return origins;
        }

        /** 도착 상태. */
        public SessionStatus target() {
            return target;
        }

        /** 이 사건이 정하는 end_reason(사유를 바꾸지 않으면 null, BR1.3). */
        public EndReason endReason() {
            return endReason;
        }

        /** 정해진 실패 코드(없거나 실행기가 정하면 null). */
        public FailureCode failureCode() {
            return failureCode;
        }

        /** 정해진 end_detail(PRACTICE_COMPLETED, 없으면 null). */
        public String endDetail() {
            return endDetail;
        }

        /** 출발 상태가 둘 이상인가(FOR UPDATE로 읽은 상태를 조건으로 쓸 수 있다, BR6.8). */
        public boolean multiOrigin() {
            return origins.size() > 1;
        }

        /** 이 상태에서 성립하는가(BR1.1). */
        public boolean accepts(SessionStatus from) {
            return from != null && origins.contains(from);
        }
    }

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

    /**
     * BR1.1: (사건, 현재 상태)가 표에 있으면 도착 상태, 없으면 빈 값(호출자는 상태를 바꾸지 않고 TRANSITION_IGNORED를 남긴다).
     */
    public static java.util.Optional<SessionStatus> decide(Trigger trigger, SessionStatus from) {
        Objects.requireNonNull(trigger, "trigger");
        return trigger.accepts(from) ? java.util.Optional.of(trigger.target()) : java.util.Optional.empty();
    }

    /**
     * 도착 상태만으로 정해지는 기본 사유(U4b BR1.3 · BR1.4): ENDED_UNCONFIRMED = UNCONFIRMED, ABORTED = DISCONNECT, DECLINED = DECLINED,
     * FAILED = FAILED. ENDED는 CLOSING에 들어갈 때 쓴 사유를 그대로 두므로 null이고(사건이 정한다), 비종료 상태도 null이다. 시작 단계 동의 철회처럼
     * 사건이 이 값을 덮을 수 있다({@link Trigger#endReason()}).
     */
    public static EndReason endReason(SessionStatus to) {
        if (to == null) {
            return null;
        }
        return switch (to) {
            case ENDED_UNCONFIRMED -> EndReason.UNCONFIRMED;
            case ABORTED -> EndReason.DISCONNECT;
            case DECLINED -> EndReason.DECLINED;
            case FAILED -> EndReason.FAILED;
            default -> null;
        };
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

    /**
     * 사건의 SessionChange(BR1.3): 사건이 정한 end_reason · end_detail · 실패 코드, 종료 상태로 가면 끝난 시각. 사유를 정하지 않는 사건
     * (ACK · 준비 · 참여 수락 · 음성 세션 닫힘)은 사유 열을 건드리지 않는다 — ENDED는 CLOSING 사유를 그대로 둔다(BR1.4).
     *
     * @param failureCode 실행기가 정한 실패 코드(없으면 사건의 것)
     */
    public static SessionChange changeFor(Trigger trigger, FailureCode failureCode, Instant at) {
        Objects.requireNonNull(trigger, "trigger");
        SessionChange change = SessionChange.none();
        if (trigger.target().isTerminal()) {
            change = change.withEndedAt(Objects.requireNonNull(at, "at"));
        }
        if (trigger.endReason() != null) {
            change = change.withEndReason(trigger.endReason());
        }
        FailureCode code = failureCode != null ? failureCode : trigger.failureCode();
        if (code != null) {
            change = change.withFailureCode(code);
        }
        if (trigger.endDetail() != null) {
            change = change.withEndDetail(trigger.endDetail());
        }
        return change;
    }

    /**
     * CLOSING 30초 시한의 SessionChange(BR1.4): end_reason UNCONFIRMED, end_detail은 CLOSING의 처음 사유(연습 완료였으면
     * {@code CHILD_STOP/PRACTICE_COMPLETED}).
     *
     * @param firstReason CLOSING에 들어갈 때 쓴 사유(없으면 end_detail을 비운다)
     * @param firstDetail CLOSING의 end_detail(없으면 null)
     */
    public static SessionChange closingTimeoutChange(EndReason firstReason, String firstDetail, Instant at) {
        SessionChange change = changeFor(Trigger.CLOSING_TIMEOUT, null, at);
        if (firstReason == null || firstReason == EndReason.UNRECOGNIZED || firstReason == EndReason.UNCONFIRMED) {
            return change;
        }
        String detail = firstDetail == null || firstDetail.isBlank() ? firstReason.name()
                : firstReason.name() + "/" + firstDetail;
        return change.withEndDetail(detail);
    }
}
