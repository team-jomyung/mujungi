package kr.mujoong.realtime.session;

import java.time.Instant;
import java.util.Objects;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.realtime.messaging.RealtimeOutboxWriter;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.observability.RealtimeMetrics;
import org.springframework.stereotype.Component;

/**
 * 세션 전이의 이벤트 · 관측 출력 (U1 3.5 Step 23 · BR6.8 · BR5.2 · OD-2 SESSION_TRANSITION · OD-5 지표).
 *
 * <p>{@link #write}는 전이 트랜잭션 안에서 SessionStateChanged를 Outbox에 쓴다(전이와 같은 트랜잭션, BR6.8). {@link #committed}는
 * 커밋 뒤에만 SESSION_TRANSITION 한 줄과 세션 지표를 남긴다 — 롤백된 전이는 로그 · 지표에 나오지 않는다. technicalPause는 U1에서 늘
 * null이다.
 */
@Component
public class SessionStateEmitter {

    private final RealtimeOutboxWriter outbox;
    private final EventLogger events;
    private final RealtimeMetrics metrics;

    public SessionStateEmitter(RealtimeOutboxWriter outbox, EventLogger events, RealtimeMetrics metrics) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** 전이 트랜잭션 안에서 SessionStateChanged를 쓴다. */
    public void write(ActivitySessionRow row, Instant at, String traceId) {
        Objects.requireNonNull(row, "row");
        SessionStateChanged payload = new SessionStateChanged(row.id(), row.guardianId(), row.childId(),
                row.activityType(), row.status(), (long) row.statusVersion(), row.endReason(), row.failureCode(),
                row.confirmed(), null, at);
        outbox.write(payload, row.guardianId(), at, traceId);
    }

    /**
     * 커밋 뒤 관측 출력.
     *
     * @param from 출발 상태(새 세션이면 null)
     */
    public void committed(SessionStatus from, ActivitySessionRow row, SessionTransitions.Origin origin, String traceId) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(origin, "origin");
        String phase = from == null || TransitionTable.isStartPhase(from) ? "START" : "RUNNING";
        events.log(EventCode.SESSION_TRANSITION, EventLog.Component.SESSION_LIFECYCLE, row.id(), traceId, entry -> {
            entry.attr(AttrKey.TO, row.status()).attr(AttrKey.STATUS_VERSION, (long) row.statusVersion())
                    .attr(AttrKey.PHASE, phase).attr(AttrKey.ORIGIN, origin.name());
            if (from != null) {
                entry.attr(AttrKey.FROM, from);
            }
            entry.attr(AttrKey.END_REASON, row.endReason()).attr(AttrKey.FAILURE_CODE, row.failureCode());
            if (row.confirmed() != null) {
                entry.attr(AttrKey.CONFIRMED, row.confirmed().booleanValue());
            }
        });
        if (from == null && row.status() == SessionStatus.PREPARING) {
            metrics.sessionStarted();
        }
        if (row.status().isTerminal()) {
            metrics.sessionEnded(row.status().name(), row.endReason() == null ? null : row.endReason().name(),
                    row.failureCode() == null ? null : row.failureCode().name(), phase, origin.name());
            metrics.sessionFinished();
        }
    }
}
