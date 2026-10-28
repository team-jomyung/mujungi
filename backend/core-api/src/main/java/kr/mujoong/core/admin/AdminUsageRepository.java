package kr.mujoong.core.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Core AI 사용량 {@code core.core_ai_usage}(JIRA-277) 집계 읽기 (JIRA-280). 쓰기 · 삭제는 usage 패키지 몫이다.
 *
 * <p>표시용 토큰은 필드마다 아는 값을 더하고, 금액 계산용 합은 입력 · 출력 토큰이 둘 다 있는 행만 더한다(그 행의 캐시 토큰이 없으면 0으로 보고, 캐시 읽기를
 * 모르는 호출 수를 따로 센다). 계정 삭제가 접수된 보호자의
 * 행은 빠진다. 보호자가 없는 행은 활동을 연결하지 못한 호출이라 호출(call_id)마다 따로 모은다.
 */
@Repository
public class AdminUsageRepository {

    private static final String MEASURED = "u.input_tokens is not null and u.output_tokens is not null";
    private static final String SUMS = """
            count(*) as calls,
            count(*) filter (where %1$s) as measured,
            count(*) filter (where u.input_tokens is not null or u.output_tokens is not null) as known,
            count(*) filter (where u.capture_status = 'COMPLETE') as complete,
            count(*) filter (where %1$s and u.cached_input_tokens is null) as cache_unknown,
            sum(u.input_tokens) as input_tokens,
            sum(u.output_tokens) as output_tokens,
            sum(u.cached_input_tokens) as cached_tokens,
            sum(u.cache_write_tokens) as cache_write_tokens,
            sum(u.reasoning_tokens) as reasoning_tokens,
            sum(u.input_tokens) filter (where %1$s) as measured_input,
            sum(u.output_tokens) filter (where %1$s) as measured_output,
            sum(coalesce(u.cached_input_tokens, 0)) filter (where %1$s) as measured_cached,
            sum(coalesce(u.cache_write_tokens, 0)) filter (where %1$s) as measured_cache_write,
            max(u.observed_at) as last_observed_at
            """.formatted(MEASURED);

    private final NamedParameterJdbcTemplate jdbc;

    public AdminUsageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * 묶음 하나(세션 또는 연결 미확인 호출 · 목적 · 모델)의 합.
     *
     * <p>표시용 토큰 합은 필드마다 아는 값만 더한다(입력만 아는 호출의 입력도 들어간다, 하나도 모르면 null). 금액 계산용 합
     * ({@code measured*})은 입력 · 출력이 모두 있는 호출만 더한다.
     *
     * @param key 활동 · 준비 id(연결된 호출) 또는 call_id(연결 미확인)
     * @param measuredCalls 입력 · 출력이 모두 있어 금액을 계산할 수 있는 호출 수
     * @param knownCalls 입력 · 출력 가운데 하나라도 아는 호출 수
     * @param cacheUnknownCalls 금액을 계산한 호출 가운데 캐시 읽기 토큰을 모르는 호출 수(캐시 0으로 계산돼 금액이 정확하지 않다)
     */
    public record Aggregate(UUID key, String purpose, String model, int calls, int measuredCalls, int knownCalls,
            int completeCalls, int cacheUnknownCalls, Long inputTokens, Long outputTokens, Long cachedInputTokens, Long cacheWriteTokens,
            Long reasoningTokens, Long measuredInputTokens, Long measuredOutputTokens, Long measuredCachedInputTokens,
            Long measuredCacheWriteTokens, Instant lastObservedAt) {
    }

    /** 관측 시각 [from, to)의 연결된 호출(세션별). */
    public List<Aggregate> linkedBetween(Instant from, Instant to) {
        return jdbc.query("select u.session_id as group_key, u.purpose, u.model, " + SUMS + """
                from core.core_ai_usage u join core.core_guardian g on g.id = u.guardian_id and g.status = 'ACTIVE'
                where u.session_id is not null and u.observed_at >= :from and u.observed_at < :to
                group by u.session_id, u.purpose, u.model
                """, range(from, to), AdminUsageRepository::aggregate);
    }

    /** 관측 시각 [from, to)의 연결 미확인 호출(호출별). */
    public List<Aggregate> unlinkedBetween(Instant from, Instant to) {
        return jdbc.query("select u.call_id as group_key, u.purpose, u.model, " + SUMS + """
                from core.core_ai_usage u
                where u.guardian_id is null and u.observed_at >= :from and u.observed_at < :to
                group by u.call_id, u.purpose, u.model
                """, range(from, to), AdminUsageRepository::aggregate);
    }

    /** 세션의 연결된 호출 전체(관측 시각 제한 없음). */
    public List<Aggregate> linkedOf(UUID sessionId) {
        return jdbc.query("select u.session_id as group_key, u.purpose, u.model, " + SUMS + """
                from core.core_ai_usage u join core.core_guardian g on g.id = u.guardian_id and g.status = 'ACTIVE'
                where u.session_id = :session
                group by u.session_id, u.purpose, u.model
                """, ClockParams.uuid(new MapSqlParameterSource(), "session", sessionId), AdminUsageRepository::aggregate);
    }

    /** 연결 미확인 호출 하나(시도 전체). */
    public List<Aggregate> unlinkedCall(UUID callId) {
        return jdbc.query("select u.call_id as group_key, u.purpose, u.model, " + SUMS + """
                from core.core_ai_usage u
                where u.guardian_id is null and u.call_id = :call
                group by u.call_id, u.purpose, u.model
                """, ClockParams.uuid(new MapSqlParameterSource(), "call", callId), AdminUsageRepository::aggregate);
    }

    /** 세션 id들 가운데 연결된 Core 사용량이 하나라도 있는 것(관측 시각 제한 없음). */
    public Set<UUID> sessionsWithUsage(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query("select distinct session_id from core.core_ai_usage where session_id = any(:sessions)",
                ClockParams.uuidArray(new MapSqlParameterSource(), "sessions", sessionIds),
                (rs, n) -> ClockParams.uuid(rs, "session_id")));
    }

    /** 세션 id들 가운데 활동 행이 있는 것(상태 · 보호자 무관). 없는 id는 시작 전에 준비가 실패한 연습이다. */
    public Set<UUID> activitySessions(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query("select session_id from core.core_activity where session_id = any(:sessions)",
                ClockParams.uuidArray(new MapSqlParameterSource(), "sessions", sessionIds),
                (rs, n) -> ClockParams.uuid(rs, "session_id")));
    }

    private static MapSqlParameterSource range(Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.instant(params, "from", from);
        ClockParams.instant(params, "to", to);
        return params;
    }

    private static Aggregate aggregate(ResultSet rs, int n) throws SQLException {
        return new Aggregate(ClockParams.uuid(rs, "group_key"), rs.getString("purpose"), rs.getString("model"), rs.getInt("calls"),
                rs.getInt("measured"), rs.getInt("known"), rs.getInt("complete"), rs.getInt("cache_unknown"),
                nullableLong(rs, "input_tokens"),
                nullableLong(rs, "output_tokens"), nullableLong(rs, "cached_tokens"), nullableLong(rs, "cache_write_tokens"),
                nullableLong(rs, "reasoning_tokens"), nullableLong(rs, "measured_input"), nullableLong(rs, "measured_output"),
                nullableLong(rs, "measured_cached"), nullableLong(rs, "measured_cache_write"),
                ClockParams.instant(rs, "last_observed_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
