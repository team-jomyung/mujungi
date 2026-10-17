-- 무중 Realtime 스키마 V7 — U12 새 연습 흐름 기록 (U12 기능 설계 entities.md SceneRun · PracticeFact · HelpRecord)
-- 근거: FR4.1 · BR7.1 · BR7.3 · BR7.4 · NFR2 · R-F14 · R2(DB 시각 기본값 없음) · 편차 U12-D2(도움 원문 금지)
--
-- 적용 규칙
--   - V1 ~ V6은 손대지 않는다. 이 파일은 새 표 두 개와 색인만 더한다(열 삭제 · 이름 변경 · 기존 표 제약 강화 없음, 이전 앱에 영향 없음).
--     기존 표에 제약을 더하는 문장이 없으므로 infra/migration-approvals.txt 승인 대상이 아니다(R-M3).
--   - V4 레거시 연습 표 다섯 개(realtime_session_goal · realtime_practice_*)는 건드리지 않고 새 표도 그 표를 참조하지 않는다(BR10.1).
--     V4의 UNIQUE(session_goal_id, attempt_type)는 목표당 BASE 1개 · VARIANT 여러 개 · 장면당 도움 여러 번을 담을 수 없어 새 표가 맞다.
--   - mujoong_owner가 Flyway로 적용하고 realtime_app은 01-roles.sql의 기본 권한으로 DML만 받는다(V1 ~ V6과 같은 방식).
--   - 시각은 앱 MujoongClock 값을 바인딩한다(기본값 없음).
--   - 아이 전사 · 도움 발화 · 대본 문장 · 판정 입력을 담는 칸이 없다. 분류 코드 · 길이 · 횟수 · 시각만 남긴다(R-F14 · BR7.1).
--   - guardian_id는 보호자 삭제 참여자(순서 190, 세션 200 앞)의 기준이다(BR7.4). 세션 참조는 같은 스키마 안이라 외래 키(ON DELETE RESTRICT)다.

-- ---------------------------------------------------------------------------
-- realtime_practice_scene_run — 장면을 실제로 시작한(도입 재생이 끝난) 한 번의 실행마다 1행(BR7.3).
-- 사실(PracticeFact)은 같은 행의 순서 있는 배열 facts(계약 15종)와 같은 길이의 발생 시각 fact_ats로 담는다.
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_practice_scene_run (
    id                   uuid          NOT NULL,
    session_id           uuid          NOT NULL,
    guardian_id          uuid          NOT NULL,
    goal_ref             uuid          NOT NULL,
    goal_index           integer       NOT NULL,
    scenario_ref         uuid          NOT NULL,
    scene_kind           varchar(10)   NOT NULL,
    started_at           timestamptz   NOT NULL,
    ended_at             timestamptz,
    live_timing          varchar(12),
    variant_skip_reason  varchar(12),
    retry_count          integer       NOT NULL DEFAULT 0,
    reexplain_count      integer       NOT NULL DEFAULT 0,
    facts                text[]        NOT NULL DEFAULT '{}',
    fact_ats             timestamptz[] NOT NULL DEFAULT '{}',
    CONSTRAINT realtime_practice_scene_run_pk PRIMARY KEY (id),
    CONSTRAINT realtime_practice_scene_run_session_fk FOREIGN KEY (session_id)
        REFERENCES realtime.realtime_activity_session (id) ON DELETE RESTRICT,
    CONSTRAINT realtime_practice_scene_run_goal_index_ck CHECK (goal_index >= 0),
    CONSTRAINT realtime_practice_scene_run_kind_ck CHECK (scene_kind IN ('BASE', 'VARIANT')),
    CONSTRAINT realtime_practice_scene_run_ended_ck CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT realtime_practice_scene_run_timing_ck CHECK (live_timing IS NULL OR live_timing IN (
        'IMMEDIATE', 'AFTER_REASK', 'AFTER_HELP', 'NONE', 'ABSTAINED')),
    CONSTRAINT realtime_practice_scene_run_skip_ck CHECK (variant_skip_reason IS NULL OR variant_skip_reason IN (
        'NO_VARIANT', 'DECLINED', 'TIME_LIMIT', 'FOCUS_LIMIT', 'END_OR_REST')),
    CONSTRAINT realtime_practice_scene_run_counts_ck CHECK (retry_count >= 0 AND reexplain_count >= 0),
    -- practice-record-v2.schema.json facts 15종만(원문 문장은 들어갈 수 없다)
    CONSTRAINT realtime_practice_scene_run_facts_ck CHECK (facts <@ ARRAY[
        'REASK_RESOLVED', 'SITUATION_EXPLAINED', 'FOCUS_HELP', 'EXPRESSION_EXAMPLE', 'HELP_ACCEPTED', 'HELP_DECLINED',
        'COACH_SWITCH', 'COACH_RETURN', 'ORIGINAL_SCENE_RETURN', 'VARIANT_PLAYED', 'VARIANT_SKIPPED', 'END_REQUEST',
        'TIMEOUT', 'TRANSCRIPT_FAILED', 'PLAYBACK_FAILED']::text[]),
    CONSTRAINT realtime_practice_scene_run_fact_ats_ck CHECK (cardinality(facts) = cardinality(fact_ats))
);

CREATE INDEX realtime_practice_scene_run_guardian_idx ON realtime.realtime_practice_scene_run (guardian_id);
CREATE INDEX realtime_practice_scene_run_session_idx ON realtime.realtime_practice_scene_run (session_id, started_at);

-- ---------------------------------------------------------------------------
-- realtime_practice_help — 도움 · 역할 전환 기록 한 건마다 1행(U12-D2: 분류 코드와 길이만, 원문 금지).
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_practice_help (
    id               uuid          NOT NULL,
    scene_run_id     uuid          NOT NULL,
    guardian_id      uuid          NOT NULL,
    kind             varchar(20)   NOT NULL,
    role             varchar(5)    NOT NULL,
    example_given    boolean       NOT NULL DEFAULT false,
    consent_answer   varchar(10),
    content_chars    integer       NOT NULL,
    occurred_at      timestamptz   NOT NULL,
    CONSTRAINT realtime_practice_help_pk PRIMARY KEY (id),
    CONSTRAINT realtime_practice_help_scene_run_fk FOREIGN KEY (scene_run_id)
        REFERENCES realtime.realtime_practice_scene_run (id) ON DELETE RESTRICT,
    CONSTRAINT realtime_practice_help_kind_ck CHECK (kind IN (
        'ROLE_SWITCH', 'ROLE_RETURN', 'FOCUS_HELP', 'EXPRESSION_EXAMPLE', 'SITUATION_EXPLAIN')),
    CONSTRAINT realtime_practice_help_role_ck CHECK (role IN ('PEER', 'COACH')),
    CONSTRAINT realtime_practice_help_consent_ck CHECK (consent_answer IS NULL OR consent_answer IN (
        'ACCEPT', 'DECLINE', 'UNCLEAR', 'REQUESTED')),
    CONSTRAINT realtime_practice_help_chars_ck CHECK (content_chars >= 0)
);

CREATE INDEX realtime_practice_help_guardian_idx ON realtime.realtime_practice_help (guardian_id);
CREATE INDEX realtime_practice_help_scene_run_idx ON realtime.realtime_practice_help (scene_run_id, occurred_at);
