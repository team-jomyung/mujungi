package kr.mujoong.core.textai;
/** Sanitized failure; never preserve provider request/response or its exception message. */
public final class TextAiException extends RuntimeException {
    public enum Reason { UNAVAILABLE, TIMEOUT, INVALID_OUTPUT, FORBIDDEN_OUTPUT }
    private final Reason reason;
    private final boolean retryable;
    public TextAiException(Reason reason) { this(reason, false); }
    public TextAiException(Reason reason, boolean retryable) {
        super("Text AI failure: " + reason.name());
        this.reason = reason;
        this.retryable = retryable;
    }
    public Reason reason() { return reason; }
    public boolean retryable() { return retryable; }
}
