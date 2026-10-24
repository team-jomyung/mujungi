package kr.mujoong.realtime.usage;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import kr.mujoong.realtime.common.db.ClockParams;
import kr.mujoong.realtime.common.db.Transactions;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 자기 Realtime 스키마만 읽는다. 판정 턴 연결은 음성 경로가 아닌 저장 작업에서 한다. */
@Repository
public class AiUsageRepository {
    public enum Result { STORED, UNLINKED, DISCARDED }

    private final NamedParameterJdbcTemplate jdbc;

    public AiUsageRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Result write(AiUsageRecord record, Predicate<UUID> allowed) {
        Transactions.requireActive("AiUsageRepository.write");
        UUID sessionId = record.sessionId();
        if (sessionId == null) {
            List<UUID> ids = jdbc.query("select session_id from realtime.realtime_judgment_live where id = :id",
                    ClockParams.uuid(new MapSqlParameterSource(), "id", record.turnId()),
                    (rs, n) -> ClockParams.uuid(rs, "session_id"));
            if (ids.isEmpty()) return Result.UNLINKED;
            sessionId = ids.getFirst();
        }
        if (!allowed.test(sessionId)) return Result.DISCARDED;
        List<UUID> owners = jdbc.query("select guardian_id from realtime.realtime_activity_session where id = :id for key share",
                ClockParams.uuid(new MapSqlParameterSource(), "id", sessionId),
                (rs, n) -> ClockParams.uuid(rs, "guardian_id"));
        if (owners.isEmpty() || !allowed.test(sessionId)) return Result.DISCARDED;

        MapSqlParameterSource p = new MapSqlParameterSource();
        ClockParams.uuid(p, "id", record.id());
        ClockParams.uuid(p, "session", sessionId);
        ClockParams.uuid(p, "guardian", owners.getFirst());
        ClockParams.uuid(p, "turn", record.turnId());
        p.addValue("source", record.source().name()).addValue("providerSession", record.providerSessionId())
                .addValue("response", record.responseId()).addValue("model", record.model())
                .addValue("input", record.inputTokens(), java.sql.Types.BIGINT)
                .addValue("output", record.outputTokens(), java.sql.Types.BIGINT)
                .addValue("cached", record.cachedInputTokens(), java.sql.Types.BIGINT)
                .addValue("reasoning", record.reasoningTokens(), java.sql.Types.BIGINT)
                .addValue("seconds", record.voiceSeconds(), java.sql.Types.DOUBLE)
                .addValue("state", record.status().name()).addValue("revision", record.revision());
        ClockParams.instant(p, "at", record.capturedAt());
        // 동일 공급자 응답이 다른 호출 ID로 재전달돼도 중복 청구량을 만들지 않는다.
        jdbc.update("""
                insert into realtime.realtime_ai_usage
                    (id, source, session_id, guardian_id, turn_id, provider_session_id, response_id, model,
                     input_tokens, output_tokens, cached_input_tokens, reasoning_tokens, voice_seconds,
                     capture_status, revision, observed_at, created_at, updated_at)
                values (:id, :source, :session, :guardian, :turn, :providerSession, :response, :model,
                        :input, :output, :cached, :reasoning, :seconds, :state, :revision, :at, :at, :at)
                on conflict do nothing
                """, p);
        jdbc.update("""
                update realtime.realtime_ai_usage set
                    provider_session_id = coalesce(:providerSession, provider_session_id),
                    response_id = coalesce(:response, response_id), model = coalesce(:model, model),
                    input_tokens = coalesce(:input, input_tokens), output_tokens = coalesce(:output, output_tokens),
                    cached_input_tokens = coalesce(:cached, cached_input_tokens),
                    reasoning_tokens = coalesce(:reasoning, reasoning_tokens),
                    voice_seconds = greatest(voice_seconds, :seconds),
                    capture_status = case when capture_status = 'COMPLETE' then capture_status else :state end,
                    revision = :revision, observed_at = :at, updated_at = :at
                where id = :id and session_id = :session and revision < :revision
                """, p);
        return Result.STORED;
    }

    public List<UUID> sessions(UUID guardianId) {
        return jdbc.query("select id from realtime.realtime_activity_session where guardian_id = :id",
                ClockParams.uuid(new MapSqlParameterSource(), "id", guardianId),
                (rs, n) -> ClockParams.uuid(rs, "id"));
    }

    public int deleteByGuardian(UUID guardianId) {
        Transactions.requireActive("AiUsageRepository.deleteByGuardian");
        return jdbc.update("delete from realtime.realtime_ai_usage where guardian_id = :id",
                ClockParams.uuid(new MapSqlParameterSource(), "id", guardianId));
    }
}
