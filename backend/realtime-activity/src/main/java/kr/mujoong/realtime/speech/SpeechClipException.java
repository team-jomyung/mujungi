package kr.mujoong.realtime.speech;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 대본 줄 음성 합성 실패(사유 코드만, 원인 메시지 · 응답 본문은 담지 않는다 R-F14).
 */
public final class SpeechClipException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final SpeechClipFailure failure;

    public SpeechClipException(SpeechClipFailure failure) {
        super(Objects.requireNonNull(failure, "failure").name(), null, false, false);
        this.failure = failure;
    }

    public SpeechClipFailure failure() {
        return failure;
    }

    /** 감싼 예외를 벗겨 사유 코드를 찾는다. 모르는 원인은 {@link SpeechClipFailure#TRANSPORT_ERROR}다. */
    public static SpeechClipFailure failureOf(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof SpeechClipException clip ? clip.failure() : SpeechClipFailure.TRANSPORT_ERROR;
    }
}
