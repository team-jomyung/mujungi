package kr.mujoong.core.activity.web;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.SessionViewService;
import kr.mujoong.core.web.ApiResponses;

/**
 * 세션 요약 응답 모양(live-view.schema.json#/$defs/SessionSummary, U4a): 04 지금 활동 · {@code /api/sessions/current} · 27 머리가 같이 쓴다.
 * 사본 열의 모르는 endReason · failureCode는 null로 싣는다(계약 10절).
 */
public record SessionSummaryBody(
        UUID sessionId,
        ActivityType activityType,
        SessionStatus status,
        EndReason endReason,
        FailureCode failureCode,
        boolean confirmed,
        String requestedAt,
        String startedAt,
        String endedAt) {

    /** 세션 사본의 요약. */
    public static SessionSummaryBody of(ActivityCopy copy) {
        Objects.requireNonNull(copy, "copy");
        return new SessionSummaryBody(copy.sessionId(), copy.activityType(), copy.status(),
                SessionViewService.parse(EndReason.class, copy.endReason()),
                SessionViewService.parse(FailureCode.class, copy.failureCode()), copy.confirmed(),
                ApiResponses.time(copy.requestedAt()), ApiResponses.time(copy.startedAt()),
                ApiResponses.time(copy.endedAt()));
    }
}
