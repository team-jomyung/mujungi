package kr.mujoong.realtime.session;

import java.time.Instant;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;

/**
 * 전이(또는 같은 상태의 보조 기록)와 함께 쓰는 세션 열 값(BR6.8 · BR6.10 · entities ActivitySession). null인 값은 기존 값을
 * 그대로 둔다 — 한 번 쓴 시각 · 사유는 뒤 전이가 지우지 않는다.
 *
 * @param endReason 종료 사유(ENDED = GUARDIAN_STOP, ENDED_UNCONFIRMED = UNCONFIRMED, ABORTED = DISCONNECT, FAILED = FAILED)
 * @param failureCode FAILED의 실패 코드. {@code end_detail}에 이름으로 적는다(entities: U1은 이 열에 failure_code만 쓴다)
 * @param providerSessionId 공급자 세션 ID({@code gpt_live_session_id}, 밖으로 노출하지 않음)
 * @param startedAt 처음 ACTIVE가 된 시각
 * @param endedAt 종료 상태가 된 시각
 * @param terminationRequestedAt 끝내기 요청 수락 시각(ACTIVE → CLOSING)
 * @param outputStoppedAt 기기가 확인한 출력 멈춤 시각(VOICE_SESSION_CLOSED)
 * @param transportClosedAt 서버가 CLOSE_TRANSPORT를 보낸 시각
 * @param providerClosedAt 서버가 공급자 세션을 닫은 시각
 * @param endDetail 실패 코드가 아닌 종료 세부(U3: ABORTED의 {@code PROVIDER_LOST}, U3-J13). 있으면 {@code end_detail}에 실패 코드 대신 쓴다
 */
public record SessionChange(
        EndReason endReason,
        FailureCode failureCode,
        String providerSessionId,
        Instant startedAt,
        Instant endedAt,
        Instant terminationRequestedAt,
        Instant outputStoppedAt,
        Instant transportClosedAt,
        Instant providerClosedAt,
        String endDetail) {

    /** V1 열 길이 {@code gpt_live_session_id varchar(255)}. */
    public static final int MAX_PROVIDER_SESSION_ID_LENGTH = 255;
    /** U3 종료 세부 형식(대문자 · 밑줄, 실패 코드 이름과 같은 모양). */
    private static final java.util.regex.Pattern END_DETAIL = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private static final SessionChange NONE = new SessionChange(null, null, null, null, null, null, null, null, null,
            null);

    public SessionChange {
        if (endReason == EndReason.UNRECOGNIZED) {
            throw new IllegalArgumentException("endReason이 알려진 값이 아니다");
        }
        if (failureCode == FailureCode.UNRECOGNIZED) {
            throw new IllegalArgumentException("failureCode가 알려진 값이 아니다");
        }
        if (providerSessionId != null
                && (providerSessionId.isBlank() || providerSessionId.length() > MAX_PROVIDER_SESSION_ID_LENGTH)) {
            throw new IllegalArgumentException(
                    "providerSessionId는 1 ~ " + MAX_PROVIDER_SESSION_ID_LENGTH + "자여야 한다");
        }
        if (endDetail != null && !END_DETAIL.matcher(endDetail).matches()) {
            throw new IllegalArgumentException("endDetail은 대문자 식별자여야 한다");
        }
    }

    /** U1 모양(종료 세부 없음). */
    public SessionChange(EndReason endReason, FailureCode failureCode, String providerSessionId, Instant startedAt,
            Instant endedAt, Instant terminationRequestedAt, Instant outputStoppedAt, Instant transportClosedAt,
            Instant providerClosedAt) {
        this(endReason, failureCode, providerSessionId, startedAt, endedAt, terminationRequestedAt, outputStoppedAt,
                transportClosedAt, providerClosedAt, null);
    }

    /** {@code end_detail}에 쓸 값: 종료 세부가 있으면 그것, 없으면 실패 코드 이름(U1 그대로). */
    public String endDetailValue() {
        if (endDetail != null) {
            return endDetail;
        }
        return failureCode == null ? null : failureCode.name();
    }

    /** 바꾸는 열 없음(상태 · 버전만). */
    public static SessionChange none() {
        return NONE;
    }

    /** 종료 사유와 종료 시각. */
    public static SessionChange ended(EndReason reason, Instant at) {
        return NONE.withEndReason(reason).withEndedAt(at);
    }

    /** FAILED 종료(사유 FAILED, 실패 코드는 비울 수 있다 — Core 취소 정리, BR6.7). */
    public static SessionChange failed(FailureCode code, Instant at) {
        return ended(EndReason.FAILED, at).withFailureCode(code);
    }

    public SessionChange withEndReason(EndReason value) {
        return new SessionChange(value, failureCode, providerSessionId, startedAt, endedAt, terminationRequestedAt,
                outputStoppedAt, transportClosedAt, providerClosedAt, endDetail);
    }

    public SessionChange withFailureCode(FailureCode value) {
        return new SessionChange(endReason, value, providerSessionId, startedAt, endedAt, terminationRequestedAt,
                outputStoppedAt, transportClosedAt, providerClosedAt, endDetail);
    }

    public SessionChange withProviderSessionId(String value) {
        return new SessionChange(endReason, failureCode, value, startedAt, endedAt, terminationRequestedAt,
                outputStoppedAt, transportClosedAt, providerClosedAt, endDetail);
    }

    public SessionChange withStartedAt(Instant value) {
        return new SessionChange(endReason, failureCode, providerSessionId, value, endedAt, terminationRequestedAt,
                outputStoppedAt, transportClosedAt, providerClosedAt, endDetail);
    }

    public SessionChange withEndedAt(Instant value) {
        return new SessionChange(endReason, failureCode, providerSessionId, startedAt, value, terminationRequestedAt,
                outputStoppedAt, transportClosedAt, providerClosedAt, endDetail);
    }

    public SessionChange withTerminationRequestedAt(Instant value) {
        return new SessionChange(endReason, failureCode, providerSessionId, startedAt, endedAt, value,
                outputStoppedAt, transportClosedAt, providerClosedAt, endDetail);
    }

    public SessionChange withOutputStoppedAt(Instant value) {
        return new SessionChange(endReason, failureCode, providerSessionId, startedAt, endedAt, terminationRequestedAt,
                value, transportClosedAt, providerClosedAt, endDetail);
    }

    public SessionChange withTransportClosedAt(Instant value) {
        return new SessionChange(endReason, failureCode, providerSessionId, startedAt, endedAt, terminationRequestedAt,
                outputStoppedAt, value, providerClosedAt, endDetail);
    }

    public SessionChange withProviderClosedAt(Instant value) {
        return new SessionChange(endReason, failureCode, providerSessionId, startedAt, endedAt, terminationRequestedAt,
                outputStoppedAt, transportClosedAt, value, endDetail);
    }

    /** U3 종료 세부(예: ABORTED의 PROVIDER_LOST, U3-J13). */
    public SessionChange withEndDetail(String value) {
        return new SessionChange(endReason, failureCode, providerSessionId, startedAt, endedAt, terminationRequestedAt,
                outputStoppedAt, transportClosedAt, providerClosedAt, value);
    }

    /** 공급자 세션 ID는 문자열 표현에 넣지 않는다. */
    @Override
    public String toString() {
        return "SessionChange[endReason=" + endReason + ", failureCode=" + failureCode + "]";
    }
}
