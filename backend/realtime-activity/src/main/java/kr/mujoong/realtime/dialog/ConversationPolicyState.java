package kr.mujoong.realtime.dialog;

import java.util.Objects;
import java.util.UUID;

/** 현재 정보 요구의 서버 참조만 유지한다. 발화 순번/재표현으로 도움 예산을 새로 만들지 않는다. */
public final class ConversationPolicyState {
    private UUID currentRequest;
    private UUID refusedRequest;
    private boolean helpUsed;
    private volatile boolean resting;
    private boolean restAckAvailable;

    public void selectRequest(UUID reference, boolean explicitlyDifferentRequest) {
        Objects.requireNonNull(reference, "reference");
        if (currentRequest == null || explicitlyDifferentRequest && !reference.equals(currentRequest)) {
            currentRequest = reference;
            refusedRequest = null;
            helpUsed = false;
        }
    }

    public void refuseCurrent() {
        refusedRequest = currentRequest;
        helpUsed = true;
    }

    public boolean allowHelp(boolean actualUnderstandingFailure) {
        if (!actualUnderstandingFailure || currentRequest == null || helpUsed || resting
                || currentRequest.equals(refusedRequest)) return false;
        helpUsed = true;
        return true;
    }

    public boolean rest() {
        if (resting) return false;
        resting = true;
        restAckAvailable = true;
        return true;
    }

    public boolean claimRestAck() {
        if (!resting || !restAckAvailable) return false;
        restAckAvailable = false;
        return true;
    }

    /** 현재 아이의 새로운 입력만 휴식을 해제한다. 시계/재시도는 이 입구를 부르지 않는다. */
    public void resumeOnNewInput() { resting = false; restAckAvailable = false; }
    public boolean resting() { return resting; }
    public boolean helpUsed() { return helpUsed; }
    public UUID currentRequest() { return currentRequest; }
    public UUID refusedRequest() { return refusedRequest; }
}
