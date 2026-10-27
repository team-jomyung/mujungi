-- JIRA-277: Core Luna 호출 사용량. 기존 표는 변경하지 않는다. 프롬프트·입력·출력 원문은 저장하지 않는다.
-- session_id는 활동 또는 연습 준비 ID다. 준비에 실패한 연습은 활동 행이 없으므로 활동 표를 참조하지 않는다.
-- guardian_id가 없는 행은 호출 경로에서 활동을 연결하지 못한 호출이며 개인 식별 정보가 없다.
CREATE TABLE core.core_ai_usage (
    id uuid PRIMARY KEY,
    call_id uuid NOT NULL,
    attempt_no smallint NOT NULL CHECK (attempt_no BETWEEN 1 AND 3),
    purpose varchar(40) NOT NULL CHECK (purpose IN ('SCENARIO_GENERATION', 'SCENARIO_REVIEW', 'SEED_GENERATION',
        'RESULT_TEXT', 'GOAL_CANDIDATES', 'REJUDGMENT')),
    guardian_id uuid REFERENCES core.core_guardian (id) ON DELETE RESTRICT,
    session_id uuid,
    prompt_version varchar(64) NOT NULL,
    model varchar(200),
    response_id varchar(200),
    outcome varchar(40) NOT NULL,
    input_tokens bigint CHECK (input_tokens >= 0),
    output_tokens bigint CHECK (output_tokens >= 0),
    cached_input_tokens bigint CHECK (cached_input_tokens >= 0),
    -- 캐시 쓰기는 캐시 읽기와 따로 입력 토큰의 일부이며 별도 단가가 적용된다
    cache_write_tokens bigint CHECK (cache_write_tokens >= 0),
    reasoning_tokens bigint CHECK (reasoning_tokens >= 0),
    capture_status varchar(12) NOT NULL CHECK (capture_status IN ('UNKNOWN', 'PARTIAL', 'COMPLETE')),
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT core_ai_usage_link_ck CHECK (session_id IS NULL OR guardian_id IS NOT NULL),
    CONSTRAINT core_ai_usage_complete_ck CHECK (capture_status <> 'COMPLETE'
        OR (input_tokens IS NOT NULL AND output_tokens IS NOT NULL)),
    CONSTRAINT core_ai_usage_detail_ck CHECK (
        (cached_input_tokens IS NULL OR input_tokens IS NULL OR cached_input_tokens <= input_tokens)
        AND (cache_write_tokens IS NULL OR input_tokens IS NULL
            OR coalesce(cached_input_tokens, 0) + cache_write_tokens <= input_tokens)
        AND (reasoning_tokens IS NULL OR output_tokens IS NULL OR reasoning_tokens <= output_tokens))
);
CREATE INDEX core_ai_usage_session_idx ON core.core_ai_usage (session_id, observed_at);
CREATE INDEX core_ai_usage_observed_idx ON core.core_ai_usage (observed_at);
CREATE INDEX core_ai_usage_guardian_idx ON core.core_ai_usage (guardian_id);
CREATE UNIQUE INDEX core_ai_usage_response_idx ON core.core_ai_usage (response_id) WHERE response_id IS NOT NULL;
