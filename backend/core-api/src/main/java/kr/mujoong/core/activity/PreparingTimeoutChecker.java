package kr.mujoong.core.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.common.scaling.SingleInstanceAssumption;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.observability.CoreMetrics;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ① PREPARING 시한 점검 (U1 3.5 Step 22 · BR6.12 ① · BR6.7 · BR6.13 · AC3.1.9 · OD-9 · OD-10).
 *
 * <p>maintenance 스레드가 {@code mujoong.session.timer-tick}(1초)마다 {@link #runOnce}를 부른다. Core가 REQUESTED를 만든 때(requested_at)부터
 * {@code mujoong.timing.session.preparing-timeout}(10초) 이상 PREPARING 이벤트를 받지 못한 사본(status REQUESTED · status_version 0)을
 * 한 건씩 조건부 UPDATE로 로컬 FAILED(start_result_code 202)로 닫는다. 이긴 건만 TIMEOUT_FIRED(PREPARING) · SESSION_TRANSITION ·
 * 종료 지표를 남기고 Realtime에 취소(CORE_ABANDONED)를 보낸다(실패하면 로그만, BR6.14 재취소가 보완). 0행이면 아무것도 하지 않는다.
 * Core가 재시작해도 남은 REQUESTED는 이 점검이 닫는다(BR6.13).
 */
@Component
@SingleInstanceAssumption(si = "SI-7",
        reason = "인스턴스마다 1초 점검이 돌아 같은 사본을 두고 경합한다(조건부 UPDATE가 승자를 정해 결과 · 취소는 한 번이다)")
public class PreparingTimeoutChecker {

    /** 한 번에 닫는 최대 건수(보호자당 진행 중 하나라 평소 0 ~ 수 건). */
    static final int BATCH = 100;

    private final ActivityCopyRepository activities;
    private final RealtimeSessionClient realtime;
    private final EventLogger events;
    private final CoreMetrics metrics;
    private final MujoongClock clock;
    private final Duration preparingTimeout;

    @Autowired
    public PreparingTimeoutChecker(
            ActivityCopyRepository activities,
            RealtimeSessionClient realtime,
            EventLogger events,
            CoreMetrics metrics,
            MujoongClock clock,
            CoreProperties properties) {
        this(activities, realtime, events, metrics, clock, properties.timing().session().preparingTimeout());
    }

    public PreparingTimeoutChecker(
            ActivityCopyRepository activities,
            RealtimeSessionClient realtime,
            EventLogger events,
            CoreMetrics metrics,
            MujoongClock clock,
            Duration preparingTimeout) {
        this.activities = Objects.requireNonNull(activities, "activities");
        this.realtime = Objects.requireNonNull(realtime, "realtime");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.preparingTimeout = Objects.requireNonNull(preparingTimeout, "preparingTimeout");
    }

    /**
     * 한 번 점검한다.
     *
     * @return 이 점검이 닫은 사본 수
     */
    public int runOnce() {
        Instant now = clock.now();
        int closed = 0;
        for (ActivityCopy copy : activities.findRequestedDueBefore(now.minus(preparingTimeout), BATCH)) {
            if (activities.closeLocalFailed(copy.sessionId(), SessionStatus.REQUESTED, 0, 202, now) != 1) {
                continue;
            }
            closed++;
            long elapsedMs = Duration.between(copy.requestedAt(), now).toMillis();
            metrics.timeoutFired("PREPARING");
            events.log(EventCode.TIMEOUT_FIRED, EventLog.Component.ACTIVITY_COORDINATOR, copy.sessionId(), entry -> entry
                    .attr(AttrKey.TYPE, "PREPARING")
                    .attr(AttrKey.THRESHOLD_MS, preparingTimeout.toMillis())
                    .attr(AttrKey.ELAPSED_MS, elapsedMs));
            StartActivityService.recordLocalFailure(events, metrics, copy.sessionId(), SessionStatus.REQUESTED, 0);
            realtime.cancel(copy.sessionId());
        }
        return closed;
    }
}
