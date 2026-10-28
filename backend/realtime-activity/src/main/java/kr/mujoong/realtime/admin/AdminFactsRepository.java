package kr.mujoong.realtime.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 관리자 조회용 읽기 (JIRA-280). 자기 Realtime 스키마의 연습 장면 실행 · 도움 기록 · AI 사용량만 읽고 쓰지 않는다.
 *
 * <p>원문 열은 없다: 분류 코드 · 횟수 · 토큰 · 초 · 시각만 돌려준다(R-F14). 쓰기 · 삭제는 practiceflow · usage 패키지 몫이다.
 */
@Repository
public class AdminFactsRepository {

    private static final String USAGE_SUMS = """
            select session_id, source, model,
                count(*) as calls,
                count(*) filter (where %1$s) as measured,
                count(*) filter (where %3$s) as known,
                count(*) filter (where capture_status = 'COMPLETE') as complete,
                count(*) filter (where (%2$s) and cached_input_tokens is null) as cache_unknown,
                sum(voice_seconds) as voice_seconds,
                sum(input_tokens) as input_tokens,
                sum(output_tokens) as output_tokens,
                sum(cached_input_tokens) as cached_tokens,
                sum(reasoning_tokens) as reasoning_tokens,
                sum(input_tokens) filter (where %2$s) as measured_input,
                sum(output_tokens) filter (where %2$s) as measured_output,
                sum(coalesce(cached_input_tokens, 0)) filter (where %2$s) as measured_cached,
                max(observed_at) as last_observed_at
            from realtime.realtime_ai_usage
            """.formatted(
            "(source = 'VOICE' and voice_seconds is not null)"
                    + " or (source = 'LUNA' and input_tokens is not null and output_tokens is not null)",
            "source = 'LUNA' and input_tokens is not null and output_tokens is not null",
            "voice_seconds is not null or input_tokens is not null or output_tokens is not null");

    private final NamedParameterJdbcTemplate jdbc;

    public AdminFactsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 도움 · 역할 전환 기록 한 건(분류 코드만). */
    public record Help(UUID helpId, String kind, String consentAnswer, boolean exampleGiven) {
    }

    /** 장면 실행 한 건과 그 사실 · 도움 기록. */
    public record SceneRun(UUID sceneRunId, String sceneKind, String liveTiming, String variantSkipReason, int retryCount,
            int reexplainCount, List<String> facts, List<Help> helps) {
    }

    /**
     * 세션 · 출처 · 모델 하나의 사용량 합. 표시용 합(voiceSeconds · inputTokens · …)은 필드마다 아는 값만 더하고, 금액 계산용 합
     * ({@code measured*})은 측정된 행(음성: 초 있음, 판정: 입력 · 출력 토큰 모두 있음)만 더한다. {@code cacheUnknownCalls}는 측정된 판정 행
     * 가운데 캐시 읽기 토큰이 없는 행 수다(금액 계산에서 캐시 0으로 본다).
     */
    public record UsageSum(UUID sessionId, String source, String model, int calls, int measuredCalls, int knownCalls,
            int completeCalls, int cacheUnknownCalls, Double voiceSeconds, Long inputTokens, Long outputTokens, Long cachedInputTokens,
            Long reasoningTokens, Long measuredInputTokens, Long measuredOutputTokens, Long measuredCachedInputTokens,
            Instant lastObservedAt) {
    }

    /** 세션별 장면 실행(시작 순). 장면 실행이 없는 세션은 결과에 없다. */
    public Map<UUID, List<SceneRun>> sceneRuns(Collection<UUID> sessionIds) {
        Map<UUID, List<SceneRun>> result = new LinkedHashMap<>();
        if (sessionIds.isEmpty()) {
            return result;
        }
        MapSqlParameterSource params = ClockParams.uuidArray(new MapSqlParameterSource(), "sessions", sessionIds);
        Map<UUID, List<Help>> helps = new LinkedHashMap<>();
        jdbc.query("""
                select h.id, h.scene_run_id, h.kind, h.example_given, h.consent_answer
                from realtime.realtime_practice_help h
                join realtime.realtime_practice_scene_run r on r.id = h.scene_run_id
                where r.session_id = any(:sessions)
                order by h.occurred_at, h.id
                """, params, rs -> {
                    helps.computeIfAbsent(ClockParams.uuid(rs, "scene_run_id"), key -> new ArrayList<>())
                            .add(new Help(ClockParams.uuid(rs, "id"), rs.getString("kind"), rs.getString("consent_answer"),
                                    rs.getBoolean("example_given")));
                });
        jdbc.query("""
                select id, session_id, scene_kind, live_timing, variant_skip_reason, retry_count, reexplain_count,
                    cast(facts as text[]) as facts
                from realtime.realtime_practice_scene_run
                where session_id = any(:sessions)
                order by session_id, started_at, id
                """, params, rs -> {
                    UUID runId = ClockParams.uuid(rs, "id");
                    result.computeIfAbsent(ClockParams.uuid(rs, "session_id"), key -> new ArrayList<>())
                            .add(new SceneRun(runId, rs.getString("scene_kind"), rs.getString("live_timing"),
                                    rs.getString("variant_skip_reason"), rs.getInt("retry_count"), rs.getInt("reexplain_count"),
                                    facts(rs), helps.getOrDefault(runId, List.of())));
                });
        return result;
    }

    /** 세션들의 사용량 전체(관측 시각 제한 없음). */
    public List<UsageSum> usageOf(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(USAGE_SUMS + " where session_id = any(:sessions) group by session_id, source, model",
                ClockParams.uuidArray(new MapSqlParameterSource(), "sessions", sessionIds), AdminFactsRepository::usage);
    }

    /** 관측 시각이 [from, to)인 사용량. */
    public List<UsageSum> usageBetween(Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        ClockParams.instant(params, "from", from);
        ClockParams.instant(params, "to", to);
        return jdbc.query(USAGE_SUMS + " where observed_at >= :from and observed_at < :to group by session_id, source, model",
                params, AdminFactsRepository::usage);
    }

    private static List<String> facts(ResultSet rs) throws SQLException {
        java.sql.Array array = rs.getArray("facts");
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    private static UsageSum usage(ResultSet rs, int n) throws SQLException {
        return new UsageSum(ClockParams.uuid(rs, "session_id"), rs.getString("source"), rs.getString("model"), rs.getInt("calls"),
                rs.getInt("measured"), rs.getInt("known"), rs.getInt("complete"), rs.getInt("cache_unknown"),
                nullableDouble(rs, "voice_seconds"),
                nullableLong(rs, "input_tokens"), nullableLong(rs, "output_tokens"), nullableLong(rs, "cached_tokens"),
                nullableLong(rs, "reasoning_tokens"), nullableLong(rs, "measured_input"), nullableLong(rs, "measured_output"),
                nullableLong(rs, "measured_cached"), ClockParams.instant(rs, "last_observed_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
