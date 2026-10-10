package kr.mujoong.contract.event.payload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.event.EventPayload;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;

/**
 * SessionStateChanged payload(계약 6절). Realtime이 전이를 이긴 트랜잭션에서 쓰고(BR6.8) Core 사본은 statusVersion이 더 클 때만
 * 반영한다(BR5.10 · BR6.14). 필수: sessionId · guardianId · status · statusVersion(1 이상) · at.
 */
public record SessionStateChanged(
        UUID sessionId,
        UUID guardianId,
        UUID childId,
        ActivityType activityType,
        SessionStatus status,
        Long statusVersion,
        EndReason endReason,
        FailureCode failureCode,
        Boolean confirmed,
        TechnicalPause technicalPause,
        Instant at)
        implements EventPayload {

    /** 기술적 일시 정지(U1은 늘 null, U3 · U4가 채운다). */
    public record TechnicalPause(Kind kind, Instant since) {

        /** 정지 원인. */
        public enum Kind {
            DEVICE,
            PROVIDER,
            /** 모르는 값('알 수 없음'). */
            UNRECOGNIZED
        }
    }

    @Override
    public EventType eventType() {
        return EventType.SESSION_STATE_CHANGED;
    }

    @Override
    public UUID aggregateId() {
        return sessionId;
    }

    @Override
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (sessionId == null) {
            problems.add("sessionId:missing");
        }
        if (guardianId == null) {
            problems.add("guardianId:missing");
        }
        if (status == null || status == SessionStatus.UNRECOGNIZED || status == SessionStatus.REQUESTED) {
            problems.add("status:invalid");
        }
        if (statusVersion == null || statusVersion < 1) {
            problems.add("statusVersion:invalid");
        }
        if (at == null) {
            problems.add("at:missing");
        }
        return problems;
    }
}
