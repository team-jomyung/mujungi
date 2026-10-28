package kr.mujoong.core.practicegoal;

import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** One account-wide limit across legacy and generated goals. Caller holds the guardian write lock. */
public final class GoalFavoriteLimit {
    public static final int MAX_FAVORITES = 5;
    private GoalFavoriteLimit() { }

    static void requireCapacity(NamedParameterJdbcTemplate jdbc, UUID guardian) {
        Long count = jdbc.queryForObject("""
            select (select count(*) from core.core_practice_goal_selection
                     where guardian_id=:g and status='ACTIVE' and is_favorite)
                 + (select count(*) from core.core_saved_goal
                     where guardian_id=:g and status='ACTIVE' and is_favorite)
            """, Map.of("g", guardian), Long.class);
        if (count == null || count >= MAX_FAVORITES) throw CoreApiException.of(ErrorCode.FAVORITE_LIMIT_REACHED);
    }
}
