package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.ws.message.PlaybackMark;

/** 현재 연결과 서버 시한에 귀속된 안내 시도다. ACK/STARTED는 완료 권한이 없다. */
final class NoticeDelivery {
    enum State { PENDING, COMPLETED, FAILED, INVALIDATED }
    private final UUID sessionId;
    private final UUID connectionId;
    private final UUID playbackId;
    private final String promptVersion;
    private final String policyVersion;
    private final Instant deadline;
    private State state = State.PENDING;
    private Instant completedAt;

    NoticeDelivery(UUID sessionId, UUID connectionId, UUID playbackId, String promptVersion,
            String policyVersion, Instant deadline) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.playbackId = Objects.requireNonNull(playbackId, "playbackId");
        this.promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    boolean mark(UUID session, UUID connection, UUID playback, PlaybackMark.Status status, Instant at) {
        if (state != State.PENDING || !sessionId.equals(session) || !connectionId.equals(connection)
                || !playbackId.equals(playback)) return false;
        if (!at.isBefore(deadline)) { state = State.FAILED; return false; }
        switch (status) {
            case COMPLETED -> { state = State.COMPLETED; completedAt = at; return true; }
            case FAILED -> state = State.FAILED;
            case STOPPED -> state = State.INVALIDATED;
            default -> { }
        }
        return false;
    }

    boolean permitsInput(Instant beganAt) {
        return state == State.COMPLETED && beganAt != null && !beganAt.isBefore(completedAt);
    }

    void invalidate() { if (state != State.FAILED) state = State.INVALIDATED; }
    State state() { return state; }
    boolean boundTo(UUID session, UUID connection) { return sessionId.equals(session) && connectionId.equals(connection); }
    Instant completedAt() { return completedAt; }
    UUID playbackId() { return playbackId; }
    String promptVersion() { return promptVersion; }
    String policyVersion() { return policyVersion; }
}
