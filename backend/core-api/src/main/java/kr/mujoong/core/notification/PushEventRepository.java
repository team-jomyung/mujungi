package kr.mujoong.core.notification;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.activity.ActivityCopyRepository.ActivityCopy;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PushEventRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public PushEventRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public int enqueue(ActivityCopy copy, PushEventType type, String eventKey, Instant now) {
        var params = ClockParams.at(now);
        ClockParams.uuid(params, "guardian", copy.guardianId());
        List<UUID> recipients = jdbc.queryForList("""
                select id from core.core_push_subscription
                where guardian_id = :guardian and revoked_at is null order by id
                """, params, UUID.class);
        Map<String, Object> progress = new LinkedHashMap<>();
        recipients.forEach(id -> progress.put(id.toString(), Map.of("status", "PENDING", "attempt", 0)));
        ClockParams.uuid(params, "id", UUID.randomUUID());
        ClockParams.uuid(params, "session", copy.sessionId());
        ClockParams.text(params, "type", type.name());
        ClockParams.text(params, "key", eventKey);
        ClockParams.text(params, "activityType", copy.activityType().name());
        ClockParams.text(params, "status", recipients.isEmpty() ? "SKIPPED" : "REQUESTED");
        ClockParams.text(params, "progress", json.write(progress));
        return jdbc.update("""
                insert into core.core_push_dispatch
                    (id, guardian_id, session_id, event_type, event_key, status, attempt_count,
                     requested_at, activity_type, next_attempt_at, recipient_progress)
                values (:id, :guardian, :session, :type, :key, :status, 0,
                        :now, :activityType, :now, cast(:progress as jsonb))
                on conflict (event_key) do nothing
                """, params);
    }

    public int skipTechnical(UUID sessionId) {
        return jdbc.update("""
                update core.core_push_dispatch set status = 'SKIPPED', claim_id = null, lease_until = null,
                    last_error_code = 'SESSION_ENDED'
                where session_id = :session and status = 'REQUESTED'
                  and event_type in ('VOICE_PROBLEM', 'DEVICE_STATUS_UNKNOWN')
                """, ClockParams.uuid(new MapSqlParameterSource(), "session", sessionId));
    }

    public int skipOtherVoice(UUID sessionId, String currentKey) {
        var params = ClockParams.uuid(new MapSqlParameterSource(), "session", sessionId);
        ClockParams.text(params, "currentKey", currentKey);
        return jdbc.update("""
                update core.core_push_dispatch set status = 'SKIPPED', claim_id = null, lease_until = null,
                    last_error_code = 'PAUSE_CHANGED'
                where session_id = :session and status = 'REQUESTED' and event_type = 'VOICE_PROBLEM'
                  and (:currentKey is null or event_key <> :currentKey)
                """, params);
    }

    public record Candidate(UUID sessionId, UUID guardianId) { }

    public List<Candidate> candidatesAfter(UUID cursor, int limit) {
        var params = ClockParams.uuid(new MapSqlParameterSource(), "cursor", cursor);
        params.addValue("limit", limit);
        return jdbc.query("""
                select session_id, guardian_id from core.core_activity
                where status in ('REQUESTED','PREPARING','DEVICE_ACK','WAITING_CHILD','ACTIVE','CLOSING')
                  and (cast(:cursor as uuid) is null or session_id > :cursor)
                order by session_id limit :limit
                """, params, (rs, row) -> new Candidate(ClockParams.uuid(rs, "session_id"), ClockParams.uuid(rs, "guardian_id")));
    }

    public boolean lockActivity(UUID sessionId) {
        return !jdbc.queryForList("select session_id from core.core_activity where session_id = :session for share",
                ClockParams.uuid(new MapSqlParameterSource(), "session", sessionId), UUID.class).isEmpty();
    }
}
