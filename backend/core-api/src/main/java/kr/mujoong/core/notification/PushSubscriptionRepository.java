package kr.mujoong.core.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Notification 소유 구독 저장소. 쓰기 트랜잭션과 보호자 첫 잠금은 호출자가 연다. */
@Repository
public class PushSubscriptionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PushSubscriptionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 전역 endpoint 유일성으로 동시 등록을 직렬화한다. 타인 소유이면 갱신 0건이며 소유자는 바꾸지 않는다. */
    public int upsert(UUID guardianId, PushSubscriptionRequest request, Instant now) {
        var params = ClockParams.at(now).addValue("id", UUID.randomUUID()).addValue("guardianId", guardianId)
                .addValue("endpoint", request.endpoint()).addValue("p256dh", request.keys().p256dh())
                .addValue("auth", request.keys().auth()).addValue("platform", request.platform());
        return jdbc.update("""
                insert into core.core_push_subscription as subscription
                    (id,guardian_id,endpoint,p256dh,auth,platform,created_at)
                values(:id,:guardianId,:endpoint,:p256dh,:auth,:platform,:now)
                on conflict(endpoint) do update
                    set p256dh=excluded.p256dh,auth=excluded.auth,platform=excluded.platform,revoked_at=null
                    where subscription.guardian_id=excluded.guardian_id
                """, params);
    }

    public int revoke(UUID guardianId, String endpoint, Instant now) {
        return jdbc.update("""
                update core.core_push_subscription set revoked_at=:now
                 where guardian_id=:guardianId and endpoint=:endpoint and revoked_at is null
                """, ClockParams.at(now).addValue("guardianId", guardianId).addValue("endpoint", endpoint));
    }

    public List<PushSubscription> findActiveByGuardian(UUID guardianId) {
        return jdbc.query("""
                select * from core.core_push_subscription
                 where guardian_id=:guardianId and revoked_at is null order by created_at,id
                """, owner(guardianId), PushSubscriptionRepository::map);
    }

    public Optional<PushSubscription> findActive(UUID guardianId, UUID subscriptionId) {
        return jdbc.query("""
                select * from core.core_push_subscription
                 where guardian_id=:guardianId and id=:id and revoked_at is null
                """, owner(guardianId).addValue("id", subscriptionId), PushSubscriptionRepository::map).stream().findFirst();
    }

    public int markUsed(UUID guardianId, UUID subscriptionId, Instant now) {
        return jdbc.update("""
                update core.core_push_subscription set last_used_at=:now
                 where guardian_id=:guardianId and id=:id and revoked_at is null
                """, ClockParams.at(now).addValue("guardianId", guardianId).addValue("id", subscriptionId));
    }

    /** 공급자의 404/410 응답으로 만료된 현재 보호자 구독을 지운다. */
    public int expire(UUID guardianId, UUID subscriptionId) {
        return jdbc.update("delete from core.core_push_subscription where guardian_id=:guardianId and id=:id",
                owner(guardianId).addValue("id", subscriptionId));
    }

    public int deleteDispatches(UUID guardianId) {
        return jdbc.update("delete from core.core_push_dispatch where guardian_id=:guardianId", owner(guardianId));
    }

    public int deleteSubscriptions(UUID guardianId) {
        return jdbc.update("delete from core.core_push_subscription where guardian_id=:guardianId", owner(guardianId));
    }

    private static MapSqlParameterSource owner(UUID guardianId) {
        return ClockParams.uuid(new MapSqlParameterSource(), "guardianId", Objects.requireNonNull(guardianId, "guardianId"));
    }

    private static PushSubscription map(ResultSet rs, int row) throws SQLException {
        return new PushSubscription(ClockParams.uuid(rs, "id"), ClockParams.uuid(rs, "guardian_id"),
                rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth"), rs.getString("platform"),
                ClockParams.instant(rs, "created_at"), ClockParams.instant(rs, "last_used_at"), ClockParams.instant(rs, "revoked_at"));
    }
}
