-- JIRA-271: 기존 표는 변경하지 않는다. 원문·프롬프트·음성은 저장하지 않는다.
CREATE TABLE realtime.realtime_ai_usage (
    id uuid PRIMARY KEY,
    source varchar(10) NOT NULL CHECK (source IN ('VOICE', 'LUNA')),
    session_id uuid NOT NULL REFERENCES realtime.realtime_activity_session(id) ON DELETE RESTRICT,
    guardian_id uuid NOT NULL,
    turn_id uuid,
    provider_session_id varchar(200),
    response_id varchar(200),
    model varchar(200),
    input_tokens bigint CHECK (input_tokens >= 0),
    output_tokens bigint CHECK (output_tokens >= 0),
    cached_input_tokens bigint CHECK (cached_input_tokens >= 0),
    reasoning_tokens bigint CHECK (reasoning_tokens >= 0),
    voice_seconds double precision CHECK (voice_seconds >= 0 AND voice_seconds < 'Infinity'::double precision),
    capture_status varchar(12) NOT NULL CHECK (capture_status IN ('UNKNOWN', 'PARTIAL', 'COMPLETE')),
    revision bigint NOT NULL CHECK (revision >= 0),
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT realtime_ai_usage_source_ck CHECK (
        (source = 'VOICE' AND turn_id IS NULL AND input_tokens IS NULL AND output_tokens IS NULL)
        OR (source = 'LUNA' AND turn_id IS NOT NULL AND voice_seconds IS NULL)),
    CONSTRAINT realtime_ai_usage_complete_ck CHECK (capture_status <> 'COMPLETE' OR
        (source = 'VOICE' AND voice_seconds IS NOT NULL) OR
        (source = 'LUNA' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL)),
    CONSTRAINT realtime_ai_usage_detail_ck CHECK (
        (cached_input_tokens IS NULL OR input_tokens IS NULL OR cached_input_tokens <= input_tokens)
        AND (reasoning_tokens IS NULL OR output_tokens IS NULL OR reasoning_tokens <= output_tokens))
);
CREATE INDEX realtime_ai_usage_session_idx ON realtime.realtime_ai_usage(session_id, observed_at);
CREATE INDEX realtime_ai_usage_guardian_idx ON realtime.realtime_ai_usage(guardian_id);
CREATE UNIQUE INDEX realtime_ai_usage_response_idx ON realtime.realtime_ai_usage(response_id)
    WHERE source = 'LUNA' AND response_id IS NOT NULL;
