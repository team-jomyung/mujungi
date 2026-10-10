package kr.mujoong.core.activity;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.RealtimeSessionClient.EndResult;
import kr.mujoong.core.common.error.CoreApiException;
import org.springframework.stereotype.Service;

/**
 * 보호자 끝내기 (U1 3.5 Step 22 · BR6.9 · J54 · J111 · 편차 (w) · AC3.3.1 · AC3.3.8 · RD-14).
 *
 * <p>남의 세션이면 404. 사본이 종료 상태(ENDED · ENDED_UNCONFIRMED · ABORTED · DECLINED · FAILED)면 Realtime을 부르지 않고 409
 * SESSION_NOT_ACTIVE. 그 밖(사본이 늦어 PREPARING · DEVICE_ACK여도)은 Realtime에 end(GUARDIAN_STOP, 제한 2초, 재시도 · 차단기 없음)를
 * 보내고 그 응답을 최종으로 삼는다: 202 → 202 {status: CLOSING}, 409 → 409 SESSION_NOT_ACTIVE, 닿지 않음(시간 초과 · 연결 실패 · 401 ·
 * 5xx) → 503 REALTIME_UNAVAILABLE(세션은 그대로). 같은 requestId · 다른 requestId 반복은 Realtime이 멱등으로 처리한다(CLOSING이면 재처리
 * 없이 202). 사본은 SessionStateChanged로만 바뀐다(BR6.17).
 */
@Service
public class EndSessionService {

    private final ActivityCopyRepository activities;
    private final RealtimeSessionClient realtime;

    public EndSessionService(ActivityCopyRepository activities, RealtimeSessionClient realtime) {
        this.activities = Objects.requireNonNull(activities, "activities");
        this.realtime = Objects.requireNonNull(realtime, "realtime");
    }

    /**
     * 끝내기를 요청한다.
     *
     * @return 202 본문(status CLOSING)
     * @throws CoreApiException 404 · 409 · 503
     */
    public Ended end(UUID guardianId, UUID sessionId, UUID requestId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(sessionId, "sessionId");
        if (requestId == null) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        ActivityCopy copy = activities.findOwned(sessionId, guardianId)
                .orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
        if (copy.status().isTerminal()) {
            throw CoreApiException.of(ErrorCode.SESSION_NOT_ACTIVE);
        }
        EndResult result = realtime.end(sessionId, requestId);
        return switch (result.kind()) {
            case ACCEPTED -> new Ended(SessionStatus.CLOSING);
            case NOT_ACTIVE -> throw CoreApiException.of(ErrorCode.SESSION_NOT_ACTIVE);
            case UNAVAILABLE -> throw CoreApiException.of(ErrorCode.REALTIME_UNAVAILABLE);
        };
    }

    /** 202 본문(session-end-response.schema.json). */
    public record Ended(SessionStatus status) {
    }
}
