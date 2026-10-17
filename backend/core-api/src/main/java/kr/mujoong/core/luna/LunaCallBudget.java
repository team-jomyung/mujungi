package kr.mujoong.core.luna;

import java.time.Instant;

/** U15 BR4: 여러 call 사이에서도 최초 마감과 기술/내용 수정 슬롯을 공유한다. */
public final class LunaCallBudget {
    private Instant deadline;
    private int attempts;
    private boolean technicalUsed;
    private boolean correctionUsed;

    public synchronized void bindDeadline(Instant value) {
        if (deadline == null) deadline = value;
        if (!deadline.equals(value)) throw new IllegalArgumentException("Retry deadline cannot change");
    }
    public synchronized boolean takeAttempt() {
        if (attempts >= 3) return false;
        attempts++;
        return true;
    }
    public synchronized boolean takeTechnicalRetry() {
        if (technicalUsed || attempts >= 3) return false;
        technicalUsed = true;
        return true;
    }
    public synchronized boolean takeCorrection() {
        if (correctionUsed || attempts >= 3) return false;
        correctionUsed = true;
        return true;
    }
    public synchronized int attempts() { return attempts; }
    public synchronized boolean correctionUsed() { return correctionUsed; }
}
