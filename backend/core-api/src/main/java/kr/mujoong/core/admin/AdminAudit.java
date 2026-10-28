package kr.mujoong.core.admin;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.log.AttrKey;
import kr.mujoong.contract.log.EventCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.stereotype.Component;

/**
 * 대화 원문 열람 기록 (JIRA-280). 활동 상세를 부를 때마다 ADMIN_RECORD_VIEWED 한 줄을 남긴다(병합 · 상한 없음).
 *
 * <p>누가(관리자 해시) · 어느 활동(ownerHash) · 언제(ts)만 남기고 원문 · 아이 이름 · id 원문은 남기지 않는다(R-F14). 해시는 두 앱이 같은 비밀로
 * 만드는 ownerHash라, 알고 있는 id를 같은 방식으로 해시해 맞춰 볼 수 있다.
 */
@Component
public class AdminAudit {

    private final EventLogger events;
    private final OwnerHasher hasher;

    public AdminAudit(EventLogger events, OwnerHasher hasher) {
        this.events = Objects.requireNonNull(events, "events");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    public void recordViewed(UUID adminId, UUID sessionId) {
        String viewer = hasher.hash(adminId.toString());
        events.log(EventCode.ADMIN_RECORD_VIEWED, EventLog.Component.ADMIN_QUERY, sessionId,
                entry -> entry.attr(AttrKey.VIEWER_HASH, viewer));
    }
}
