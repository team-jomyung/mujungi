package kr.mujoong.core.usage;

import java.sql.Types;
import java.util.UUID;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Core 스키마의 사용량 표만 쓴다. 활동 · 준비 표를 읽지 않는다. */
@Repository
public class AiUsageRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AiUsageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 한 기록을 넣는다. 같은 행 키 · 같은 공급자 응답 ID는 한 행만 남긴다(저장 재시도 · 중복 응답이 중복 집계되지 않게). 보호자가 이미 지워졌으면
     * 외래 키 위반으로 실패한다.
     */
    public void write(AiUsageRecord record) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        ClockParams.uuid(p, "id", record.id());
        ClockParams.uuid(p, "call", record.callId());
        ClockParams.uuid(p, "guardian", record.link() == null ? null : record.link().guardianId());
        ClockParams.uuid(p, "session", record.link() == null ? null : record.link().sessionId());
        ClockParams.integer(p, "attempt", record.attemptNo());
        ClockParams.text(p, "purpose", record.purpose());
        ClockParams.text(p, "prompt", record.promptVersion());
        ClockParams.text(p, "model", record.model());
        ClockParams.text(p, "response", record.responseId());
        ClockParams.text(p, "outcome", record.outcome());
        p.addValue("input", record.inputTokens(), Types.BIGINT)
                .addValue("output", record.outputTokens(), Types.BIGINT)
                .addValue("cached", record.cachedInputTokens(), Types.BIGINT)
                .addValue("cacheWrite", record.cacheWriteTokens(), Types.BIGINT)
                .addValue("reasoning", record.reasoningTokens(), Types.BIGINT);
        ClockParams.text(p, "state", record.status().name());
        ClockParams.instant(p, "at", record.observedAt());
        jdbc.update("""
                insert into core.core_ai_usage
                    (id, call_id, attempt_no, purpose, guardian_id, session_id, prompt_version, model, response_id, outcome,
                     input_tokens, output_tokens, cached_input_tokens, cache_write_tokens, reasoning_tokens, capture_status,
                     observed_at, created_at)
                values (:id, :call, :attempt, :purpose, :guardian, :session, :prompt, :model, :response, :outcome,
                        :input, :output, :cached, :cacheWrite, :reasoning, :state, :at, :at)
                on conflict do nothing
                """, p);
    }

    public int deleteByGuardian(UUID guardianId) {
        return jdbc.update("delete from core.core_ai_usage where guardian_id = :id",
                ClockParams.uuid(new MapSqlParameterSource(), "id", guardianId));
    }
}
