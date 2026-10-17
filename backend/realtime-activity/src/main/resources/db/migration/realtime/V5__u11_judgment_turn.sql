-- 무중 Realtime 스키마 V5 — U11 진행용 판정 기록 (U11 기능 설계 entities.md JudgmentLiveRecord · functional-spec.md 7절)
-- 근거: FR2.1 · FR2.2 · BR2.1 ~ BR2.5 · U11-D5 · R-F14 · R2(DB 시각 기본값 없음)
--
-- 적용 규칙
--   - V1 ~ V4는 손대지 않는다. 이 파일은 표 하나와 색인 하나만 더한다(열 삭제 · 이름 변경 · 기존 표 제약 강화 없음, 이전 앱에 영향 없음).
--   - mujoong_owner가 Flyway로 적용하고 realtime_app은 01-roles.sql의 기본 권한으로 DML만 받는다(V1 ~ V4와 같은 방식).
--   - 시각은 앱 MujoongClock 값을 바인딩한다(기본값 없음). 판정 기록은 보호자 화면 · 리포트 · 기록 스냅숏에 쓰지 않고 읽는 외부 경로가 없다(BR2.2).
--   - 전사 원문은 모은 출력 전사(held_output_transcript, 2000자 이하) 하나뿐이고 로그에 쓰지 않는다(R-F14).

-- ---------------------------------------------------------------------------
-- realtime_judgment_live — 판정 턴이 닫힐 때마다 1건(BR2.1). id = turnId라 같은 id 덮어쓰기로 재시도가 멱등이다(BR2.5).
-- guardian_id는 계정 삭제 참여자(순서 180, 연습 150 뒤 · 세션 200 앞)의 기준이다(BR2.4). 세션 참조는 같은 스키마 안이라 외래 키(ON DELETE RESTRICT)다.
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_judgment_live (
    id                      uuid          NOT NULL,
    guardian_id             uuid          NOT NULL,
    session_id              uuid          NOT NULL,
    judgment_point          varchar(2)    NOT NULL,
    trigger_kind            varchar(30)   NOT NULL,
    goal_ref                uuid,
    scene_ref               uuid,
    input_transcript_seq    integer       NOT NULL,
    option_set_version      varchar(50)   NOT NULL,
    prompt_version          varchar(50)   NOT NULL,
    model                   varchar(100),
    outcome                 varchar(20)   NOT NULL,
    abstain_reason          varchar(30),
    turn_opened             boolean       NOT NULL,
    first_option            varchar(50),
    first_probability       numeric(5, 4),
    second_option           varchar(50),
    second_probability      numeric(5, 4),
    latency_millis          integer       NOT NULL,
    next_action             varchar(30)   NOT NULL,
    held_output_transcript  text,
    created_at              timestamptz   NOT NULL,
    CONSTRAINT realtime_judgment_live_pk PRIMARY KEY (id),
    CONSTRAINT realtime_judgment_live_session_fk FOREIGN KEY (session_id)
        REFERENCES realtime.realtime_activity_session (id) ON DELETE RESTRICT,
    CONSTRAINT realtime_judgment_live_point_ck CHECK (judgment_point IN ('J1', 'J2', 'J3', 'J4', 'J5', 'J6')),
    CONSTRAINT realtime_judgment_live_trigger_ck CHECK (trigger_kind IN (
        'JOIN_UNCLEAR', 'STOP_CONFIRM_CANDIDATE', 'EXTENSION_REQUEST')),
    CONSTRAINT realtime_judgment_live_seq_ck CHECK (input_transcript_seq >= 0),
    CONSTRAINT realtime_judgment_live_outcome_ck CHECK (outcome IN (
        'CONFIRMED', 'ABSTAINED', 'FAULT_CANCELLED', 'STOPPED', 'SUPERSEDED', 'SESSION_EXITED')),
    CONSTRAINT realtime_judgment_live_reason_ck CHECK (abstain_reason IS NULL OR abstain_reason IN (
        'LOW_CONFIDENCE', 'LOW_MARGIN', 'TIMEOUT', 'PROVIDER_ERROR', 'SCHEMA_INVALID', 'OPTION_OUT_OF_SET',
        'DUPLICATE_OPTION', 'PROBABILITY_OUT_OF_RANGE', 'NO_PROVIDER', 'UNREADABLE_INPUT', 'RESPONSE_NOT_READY')),
    CONSTRAINT realtime_judgment_live_probability_ck CHECK (
        (first_probability IS NULL OR first_probability BETWEEN 0 AND 1)
        AND (second_probability IS NULL OR second_probability BETWEEN 0 AND 1)),
    CONSTRAINT realtime_judgment_live_latency_ck CHECK (latency_millis >= 0),
    CONSTRAINT realtime_judgment_live_next_action_ck CHECK (next_action IN (
        'NONE', 'START_JOIN', 'DECLINE_JOIN', 'REPROMPT_JOIN', 'CLOSE', 'ASK_STOP_CONFIRM', 'RESUME', 'HOLD_REASK',
        'REST_ACK_AND_RETURN', 'REST_ACK_ONLY', 'DELEGATED_TO_EXTENSION')),
    CONSTRAINT realtime_judgment_live_held_ck CHECK (held_output_transcript IS NULL OR char_length(held_output_transcript) <= 2000),
    -- outcome = CONFIRMED ⇔ 1 · 2순위가 모두 있다(확률 포함)
    CONSTRAINT realtime_judgment_live_confirmed_ck CHECK (
        (outcome = 'CONFIRMED') = (first_option IS NOT NULL AND first_probability IS NOT NULL
            AND second_option IS NOT NULL AND second_probability IS NOT NULL)),
    -- outcome = ABSTAINED ⇔ 보류 사유가 있다
    CONSTRAINT realtime_judgment_live_abstained_ck CHECK ((outcome = 'ABSTAINED') = (abstain_reason IS NOT NULL))
);

CREATE INDEX realtime_judgment_live_guardian_idx ON realtime.realtime_judgment_live (guardian_id);
