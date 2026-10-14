package kr.mujoong.core.messaging.handler;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.event.payload.SessionStateChanged;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.EndReason;
import kr.mujoong.contract.status.FailureCode;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.core.activity.ActivityCopyRepository;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.activity.ActivityCopyRepository.StateChange;
import kr.mujoong.core.activity.RealtimeSessionClient;
import kr.mujoong.core.messaging.CoreInboxRepository.InboxEvent;
import kr.mujoong.core.notification.PushEventRecorder;
import org.springframework.stereotype.Component;

/**
 * SessionStateChanged 처리기 (U1 3.5 Step 20 · BR5.10 · BR6.14 · BR6.17 · NFR4.5 · NFR4.6).
 *
 * <p>한 문장의 조건부 UPDATE({@code status_version < :v AND NOT local_failed})로 사본에 반영한다. 0행이면:
 *
 * <ul>
 *   <li>status가 ACTIVE이고 사본 started_at이 비었으면 그 열만 채운다(낡은 이벤트라도, BR6.14).
 *   <li>사본이 Core 로컬 FAILED(BR6.7 · BR6.12 ①)이고 이벤트 status가 종료 상태가 아니면 같은 취소(CORE_ABANDONED)를 다시 보낸다.
 *       취소는 커밋 뒤 트랜잭션 밖에서 하고 실패하면 예외 → BR5.8 재호출(BR6.14 · RD-9 6번).
 *   <li>그 밖(같거나 낮은 statusVersion · 사본 없음)은 버린다(PROCESSED). 역순 · 같은 eventId 재처리에도 결과가 같다.
 * </ul>
 *
 * <p>모르는 status · REQUESTED 같은 반영할 수 없는 값은 버린다. 모르는 endReason · failureCode는 비워 쓴다(계약 10절).
 */
@Component
public class SessionStateChangedHandler implements InboxHandler {

    private final ActivityCopyRepository activities;
    private final RealtimeSessionClient realtime;
    private final JsonCodec json;
    private final PushEventRecorder push;

    public SessionStateChangedHandler(ActivityCopyRepository activities, RealtimeSessionClient realtime, JsonCodec json,
            PushEventRecorder push) {
        this.activities = Objects.requireNonNull(activities, "activities");
        this.realtime = Objects.requireNonNull(realtime, "realtime");
        this.json = Objects.requireNonNull(json, "json");
        this.push = Objects.requireNonNull(push, "push");
    }

    @Override
    public EventType eventType() {
        return EventType.SESSION_STATE_CHANGED;
    }

    @Override
    public Result handle(InboxEvent event, Instant now) {
        SessionStateChanged payload = json.read(event.payloadJson(), SessionStateChanged.class);
        SessionStatus status = payload.status();
        if (payload.sessionId() == null || payload.at() == null || payload.statusVersion() == null
                || payload.statusVersion() < 1 || status == null || status == SessionStatus.UNRECOGNIZED
                || status == SessionStatus.REQUESTED) {
            return Result.done();
        }
        UUID sessionId = payload.sessionId();
        var before = activities.findById(sessionId);
        if (before.isEmpty() || !before.get().guardianId().equals(payload.guardianId())) {
            return Result.done();
        }
        boolean pushAllowed = push.lockGuardian(before.get().guardianId());
        StateChange change = new StateChange(sessionId, status, payload.statusVersion(), known(payload.endReason()),
                known(payload.failureCode()), Boolean.TRUE.equals(payload.confirmed()), payload.at());
        if (activities.applyStateChange(change, now) == 1) {
            if (pushAllowed) {
                activities.findById(sessionId).ifPresent(copy -> push.record(copy, payload, true, now));
            }
            return Result.done();
        }
        if (status == SessionStatus.ACTIVE) {
            activities.fillStartedAtIfMissing(sessionId, payload.at(), now);
        }
        Optional<ActivityCopy> copy = activities.findById(sessionId);
        if (pushAllowed) {
            copy.ifPresent(current -> push.record(current, payload, false, now));
        }
        if (copy.isPresent() && copy.get().localFailed() && status.isNonTerminal()) {
            return Result.then(() -> realtime.cancelOrThrow(sessionId));
        }
        return Result.done();
    }

    private static EndReason known(EndReason value) {
        return value == EndReason.UNRECOGNIZED ? null : value;
    }

    private static FailureCode known(FailureCode value) {
        return value == FailureCode.UNRECOGNIZED ? null : value;
    }
}
