package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 원문을 담지 않는 세션 확정 사실의 개정이다. 기존 세션 이벤트와 같은 수명이다. */
public record SessionGuardrailFacts(int schemaVersion, UUID sessionId, long revision, int statusVersion,
        Notice notice, Participation participation, Goodbye goodbye, Integer confirmedStopBubbleSeq, Instant recordedAt) {

    public enum Notice { UNKNOWN, PENDING, COMPLETED, FAILED }
    public enum Participation { UNKNOWN, PENDING, ACCEPTED, REFUSED, NO_RESPONSE }
    public enum Goodbye { UNKNOWN, NONE, PENDING, PLAYING, COMPLETED, STOPPED, FAILED }

    public SessionGuardrailFacts {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(notice, "notice");
        Objects.requireNonNull(participation, "participation");
        Objects.requireNonNull(goodbye, "goodbye");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (schemaVersion != 1 || revision < 1 || statusVersion < 1
                || confirmedStopBubbleSeq != null && confirmedStopBubbleSeq < 1) {
            throw new IllegalArgumentException("세션 사실의 형식/개정이 올바르지 않다");
        }
        if (participation == Participation.ACCEPTED && notice != Notice.COMPLETED) {
            throw new IllegalArgumentException("안내 완료 없이 참여 수락을 확정할 수 없다");
        }
    }

    /** 다음 상태 전이/같은 상태의 예상 버전에 귀속시킨다. */
    public SessionGuardrailFacts atVersion(int version) {
        return new SessionGuardrailFacts(schemaVersion, sessionId, revision, version, notice, participation, goodbye,
                confirmedStopBubbleSeq, recordedAt);
    }
}
