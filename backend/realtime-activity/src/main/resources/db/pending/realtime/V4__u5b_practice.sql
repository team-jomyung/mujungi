-- U5b 독립 저장 구조. 공유 환경 적용/자동 탐색 위치 이동은 실제 U4b V3 뒤 별도 승인으로 한다.
-- data는 내부 불변 모델 원본이며 EvaluationWork도 response에 속한다.
-- 시각은 앱 Clock으로 전달한다. 외부 계약 상태를 추가하지 않는다.
CREATE TABLE realtime.realtime_session_goal (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES realtime.realtime_activity_session(id),
    guardian_id uuid NOT NULL,
    goal_selection_id uuid NOT NULL,
    goal_order integer NOT NULL CHECK (goal_order > 0),
    terminated boolean NOT NULL DEFAULT false,
    data jsonb NOT NULL CHECK (jsonb_typeof(data) = 'object'),
    UNIQUE(session_id, goal_selection_id), UNIQUE(session_id, goal_order)
);
CREATE INDEX realtime_session_goal_guardian_idx ON realtime.realtime_session_goal(guardian_id);
CREATE TABLE realtime.realtime_practice_attempt (
    id uuid PRIMARY KEY,
    session_goal_id uuid NOT NULL REFERENCES realtime.realtime_session_goal(id),
    attempt_type text NOT NULL CHECK (attempt_type IN ('ORIGINAL','NEW_SCENARIO')),
    scenario_id uuid,
    data jsonb NOT NULL,
    UNIQUE(session_goal_id, attempt_type), UNIQUE(id, scenario_id),
    CHECK (((data->>'state' IN ('ISSUED','EXPOSED','COMPLETED')) = (scenario_id IS NOT NULL)) IS TRUE),
    CHECK ((data->>'state' <> 'PREPARATION_FAILED' OR
        (scenario_id IS NULL AND data->>'preparationFailure' IS NOT NULL AND data->>'exposedAt' IS NULL)) IS TRUE)
);
CREATE TABLE realtime.realtime_practice_response (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL,
    scenario_id uuid NOT NULL,
    response_type text NOT NULL CHECK (response_type IN ('FIRST','AFTER_CUE','NEW_SCENARIO')),
    data jsonb NOT NULL,
    evaluation_state text GENERATED ALWAYS AS (data->>'evaluationState') STORED,
    label text GENERATED ALWAYS AS (data->>'label') STORED,
    UNIQUE(attempt_id, response_type),
    FOREIGN KEY(attempt_id, scenario_id) REFERENCES realtime.realtime_practice_attempt(id, scenario_id),
    CHECK ((evaluation_state IN ('PENDING','FINALIZED','TERMINATED_PENDING')) IS TRUE),
    CHECK ((CASE WHEN evaluation_state = 'FINALIZED' THEN label IS NOT NULL AND data->>'closedAt' IS NOT NULL
        ELSE label IS NULL AND data->>'closedAt' IS NULL END) IS TRUE),
    CHECK ((CASE WHEN label = 'NOT_PERFORMED' THEN data->>'utteranceSeq' IS NULL
        AND data->>'notPerformedReason' IS NOT NULL AND data->'work' IS NULL
        ELSE (data->>'utteranceSeq')::integer > 0 AND data->>'notPerformedReason' IS NULL
        AND (data->'work'->>'attemptsStarted')::integer BETWEEN 0 AND 2 END) IS TRUE)
);
CREATE INDEX realtime_practice_response_pending_idx ON realtime.realtime_practice_response(evaluation_state);
CREATE TABLE realtime.realtime_practice_cue (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL UNIQUE,
    scenario_id uuid NOT NULL,
    data jsonb NOT NULL,
    FOREIGN KEY(attempt_id, scenario_id) REFERENCES realtime.realtime_practice_attempt(id, scenario_id),
    CHECK ((data->>'consentResponse' IN ('ACCEPTED','DECLINED','NO_RESPONSE')) IS TRUE),
    CHECK ((data->>'givenAt' IS NULL OR (data->>'consentResponse' = 'ACCEPTED'
        AND data->>'deliveredText' IS NOT NULL)) IS TRUE)
);
CREATE TABLE realtime.realtime_practice_evaluation (
    id uuid PRIMARY KEY,
    response_id uuid NOT NULL UNIQUE REFERENCES realtime.realtime_practice_response(id),
    data jsonb NOT NULL,
    CHECK ((data->>'label' IN ('functional','partial','none','uncertain')) IS TRUE),
    CHECK (((data->>'attempts')::integer BETWEEN 0 AND 2) IS TRUE)
);
