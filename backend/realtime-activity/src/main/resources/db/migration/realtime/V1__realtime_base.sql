-- 무중 Realtime 기준 스키마 V1 (U1 3.5 Step 11)
-- 근거: BR8.1 · BR8.2 · J43 · entities.md E2 · E4 · E5 · E9 · A3(trace_id) · A4(guardian_id) · BR6.3 · BR6.6 · BR6.8 · BR7.5 ·
--       BR3.7 · BR4.8 · J115 · J116 · PD-8 · SD-13
--
-- 적용 규칙
--   - mujoong_owner가 Flyway(위치 db/migration/realtime, 이력 표 flyway.realtime_schema_history)로 core 위치 다음에 적용한다.
--     앱은 validate만 한다(BR8.1).
--   - 표 7개를 realtime 스키마에 만든다. 이름은 스키마로 한정한다(J116). 표 소유자는 mujoong_owner이고 realtime_app은
--     01-roles.sql의 기본 권한으로 DML만 받는다(편차 (x)).
--   - 형식(J115): uuid → uuid, string(n) → varchar(n), timestamp → timestamptz(엔터티 필드 Instant), json → jsonb.
--   - DB 시각 기본값을 두지 않는다. 모든 시각은 앱 Clock 값을 바인딩한다(R2 · NFR6.4).
--   - core 스키마를 가리키는 열(guardian_id · child_id · device_id · *_content_version_id)은 외래 키 없는 논리 참조다(BR8.1).
--     같은 스키마 안의 세션 참조만 외래 키(ON DELETE RESTRICT)다.
--   - 세션의 40초 시계와 10분 묘비는 Realtime 메모리 값이라 열이 없다(entities ActivitySession).
--   - 뒤 유닛은 V2+로 열 · 색인을 더하기만 한다(BR8.2).

-- ---------------------------------------------------------------------------
-- realtime_activity_session — 진행 중 세션의 주인 행(E5, 29열)
-- 전이는 WHERE id AND status = :from 조건부 UPDATE 한 문장(BR6.8), INSERT는 ON CONFLICT (id) DO NOTHING(BR6.6 ④).
-- 동의 스냅샷 다섯 값과 파생 열이 서로 같아야 한다(스냅샷 CHECK). REQUESTED는 Core 사본에만 있다.
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_activity_session (
    id                         uuid         NOT NULL,
    guardian_id                uuid         NOT NULL,
    child_id                   uuid         NOT NULL,
    device_id                  uuid         NOT NULL,
    activity_type              varchar(30)  NOT NULL,
    status                     varchar(30)  NOT NULL,
    status_version             integer      NOT NULL,
    start_idempotency_key      varchar(100) NOT NULL,
    gpt_live_session_id        varchar(255),
    consent_version            integer,
    consent_checked_at         timestamptz  NOT NULL,
    consent_snapshot_json      jsonb        NOT NULL,
    policy_version             varchar(50)  NOT NULL,
    data_collection_allowed    boolean      NOT NULL,
    transcript_storage_allowed boolean      NOT NULL,
    child_context_json         jsonb,
    intro_content_version_id   uuid,
    closing_content_version_id uuid,
    end_reason                 varchar(50),
    end_detail                 text,
    termination_requested_at   timestamptz,
    output_stopped_at          timestamptz,
    transport_closed_at        timestamptz,
    provider_closed_at         timestamptz,
    started_at                 timestamptz,
    ended_at                   timestamptz,
    row_version                bigint       NOT NULL DEFAULT 0,
    created_at                 timestamptz  NOT NULL,
    updated_at                 timestamptz  NOT NULL,
    CONSTRAINT realtime_activity_session_pk PRIMARY KEY (id),
    CONSTRAINT realtime_activity_session_type_ck CHECK (activity_type IN ('FREE_TALK', 'PRACTICE')),
    CONSTRAINT realtime_activity_session_status_ck CHECK (status IN (
        'PREPARING', 'DEVICE_ACK', 'WAITING_CHILD', 'ACTIVE', 'CLOSING',
        'ENDED', 'ENDED_UNCONFIRMED', 'ABORTED', 'DECLINED', 'FAILED')),
    -- PREPARING = 1부터 전이마다 +1(BR6.8 · ERD v0.2)
    CONSTRAINT realtime_activity_session_status_version_ck CHECK (status_version >= 1),
    -- 동의 스냅샷: 다섯 값이 모두 있고 형식이 맞으며 파생 열과 같다(entities ActivitySession, 시작 뒤 바뀌지 않음).
    -- 키가 없으면 비교가 NULL이 되어 통과해 버리므로 전체를 IS TRUE로 묶는다. 형 변환 없이 jsonb끼리 비교한다
    CONSTRAINT realtime_activity_session_consent_snapshot_ck CHECK ((
        jsonb_typeof(consent_snapshot_json) = 'object'
        AND jsonb_typeof(consent_snapshot_json -> 'policyVersion') = 'string'
        AND jsonb_typeof(consent_snapshot_json -> 'voiceAndText') = 'boolean'
        AND jsonb_typeof(consent_snapshot_json -> 'guardianView') = 'boolean'
        AND jsonb_typeof(consent_snapshot_json -> 'reportSharing') = 'boolean'
        AND jsonb_typeof(consent_snapshot_json -> 'rawAudio') = 'boolean'
        AND consent_snapshot_json -> 'policyVersion' = to_jsonb(policy_version)
        AND consent_snapshot_json -> 'voiceAndText' = to_jsonb(data_collection_allowed)
        AND consent_snapshot_json -> 'voiceAndText' = to_jsonb(transcript_storage_allowed)
    ) IS TRUE)
);

-- 보호자 · 기기당 진행 중 세션 하나(BR6.3 · Realtime 2차 방어 SESSION_IN_PROGRESS)
CREATE UNIQUE INDEX realtime_activity_session_in_progress_guardian_uk
    ON realtime.realtime_activity_session (guardian_id)
    WHERE status IN ('PREPARING', 'DEVICE_ACK', 'WAITING_CHILD', 'ACTIVE', 'CLOSING');
CREATE UNIQUE INDEX realtime_activity_session_in_progress_device_uk
    ON realtime.realtime_activity_session (device_id)
    WHERE status IN ('PREPARING', 'DEVICE_ACK', 'WAITING_CHILD', 'ACTIVE', 'CLOSING');

-- ---------------------------------------------------------------------------
-- realtime_session_event — 세션 안 명령 · 전이 기록(payload에 전사 원문을 넣지 않는다, BR6.9 · NFR12.3)
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_session_event (
    id           uuid         NOT NULL,
    session_id   uuid         NOT NULL,
    event_type   varchar(100) NOT NULL,
    op_id        varchar(100),
    payload_json jsonb,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT realtime_session_event_pk PRIMARY KEY (id),
    CONSTRAINT realtime_session_event_session_fk FOREIGN KEY (session_id)
        REFERENCES realtime.realtime_activity_session (id) ON DELETE RESTRICT
);

-- ---------------------------------------------------------------------------
-- realtime_session_stop_expression — 세션이 시작 때 고정한 종료 표현(U1은 기본 표현 한 행 is_default true, BR6.5)
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_session_stop_expression (
    id              uuid         NOT NULL,
    session_id      uuid         NOT NULL,
    expression_text varchar(100) NOT NULL,
    is_default      boolean      NOT NULL DEFAULT false,
    sort_order      integer      NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT realtime_session_stop_expression_pk PRIMARY KEY (id),
    CONSTRAINT realtime_session_stop_expression_session_fk FOREIGN KEY (session_id)
        REFERENCES realtime.realtime_activity_session (id) ON DELETE RESTRICT
);

-- ---------------------------------------------------------------------------
-- realtime_transcript — 전사 조각과 확정본(12열). (session_id, sequence_no) 유일: 기록기가
-- ON CONFLICT (session_id, sequence_no) DO UPDATE로 PARTIAL을 같은 번호로 덮고 FINAL로 닫는다(BR7.5 · PD-5 · SQ-11).
-- AI 말은 게이트 통과 뒤에만 approved_for_display true(BR7.4 · J12).
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_transcript (
    id                   uuid        NOT NULL,
    session_id           uuid        NOT NULL,
    sequence_no          integer     NOT NULL,
    speaker              varchar(30) NOT NULL,
    text                 text        NOT NULL,
    assembly_status      varchar(30) NOT NULL,
    review_needed        boolean     NOT NULL DEFAULT false,
    approved_for_display boolean     NOT NULL DEFAULT false,
    started_at           timestamptz,
    ended_at             timestamptz,
    created_at           timestamptz NOT NULL,
    deleted_at           timestamptz,
    CONSTRAINT realtime_transcript_pk PRIMARY KEY (id),
    CONSTRAINT realtime_transcript_session_fk FOREIGN KEY (session_id)
        REFERENCES realtime.realtime_activity_session (id) ON DELETE RESTRICT,
    CONSTRAINT realtime_transcript_sequence_uk UNIQUE (session_id, sequence_no),
    CONSTRAINT realtime_transcript_sequence_ck CHECK (sequence_no >= 1),
    CONSTRAINT realtime_transcript_speaker_ck CHECK (speaker IN ('CHILD', 'AI')),
    CONSTRAINT realtime_transcript_assembly_ck CHECK (assembly_status IN ('PARTIAL', 'FINAL'))
);

-- ---------------------------------------------------------------------------
-- realtime_device_connection — 기기 한 대의 연결 상태 원장(E2, 10열). 첫 접속에서 만들어진다(BR3.7).
-- HELLO_OK 때 FOR UPDATE 교체, 이후 기록은 connection_id 조건부(BR3.7 · BR4.8). 갱신이 잦아 fillfactor 90(PD-8).
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_device_connection (
    device_id          uuid        NOT NULL,
    guardian_id        uuid        NOT NULL,
    child_id           uuid,
    connection_state   varchar(20) NOT NULL DEFAULT 'OFFLINE',
    connection_id      uuid,
    connection_since   timestamptz,
    last_seen_at       timestamptz,
    last_rtt_ms        integer,
    recent_disconnects integer     NOT NULL DEFAULT 0,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT realtime_device_connection_pk PRIMARY KEY (device_id),
    CONSTRAINT realtime_device_connection_state_ck CHECK (
        connection_state IN ('OFFLINE', 'CONNECTED', 'UNSTABLE', 'DISCONNECTED', 'UNRESPONSIVE')),
    CONSTRAINT realtime_device_connection_counts_ck CHECK (
        recent_disconnects >= 0 AND (last_rtt_ms IS NULL OR last_rtt_ms >= 0))
) WITH (fillfactor = 90);

-- ---------------------------------------------------------------------------
-- realtime_outbox_event — Realtime → Core 영속 이벤트(E4 16열 + A3 trace_id = 17열). Core Outbox와 같은 모양 · 규칙(LC-11).
-- SessionRecordCaptured를 계정 삭제 참가자가 닫을 때는 FAILED(last_error ACCOUNT_DELETED)다(BR5.14).
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_outbox_event (
    event_id       uuid         NOT NULL,
    event_type     varchar(100) NOT NULL,
    schema_version integer      NOT NULL DEFAULT 1,
    occurred_at    timestamptz  NOT NULL,
    aggregate_type varchar(100) NOT NULL,
    aggregate_id   uuid         NOT NULL,
    guardian_id    uuid,
    payload_json   jsonb        NOT NULL,
    payload_hash   varchar(64)  NOT NULL,
    status         varchar(30)  NOT NULL DEFAULT 'PENDING',
    retry_count    integer      NOT NULL DEFAULT 0,
    available_at   timestamptz  NOT NULL,
    published_at   timestamptz,
    last_error     text,
    purged_at      timestamptz,
    created_at     timestamptz  NOT NULL,
    trace_id       varchar(32),
    CONSTRAINT realtime_outbox_event_pk PRIMARY KEY (event_id),
    CONSTRAINT realtime_outbox_event_status_ck CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT realtime_outbox_event_schema_version_ck CHECK (schema_version >= 1),
    CONSTRAINT realtime_outbox_event_retry_count_ck CHECK (retry_count BETWEEN 0 AND 20),
    -- PUBLISHED가 되면 payload를 비운다(BR5.11)
    CONSTRAINT realtime_outbox_event_published_purged_ck CHECK (
        status <> 'PUBLISHED' OR purged_at IS NOT NULL OR payload_json = '{}'::jsonb),
    -- traceId 형식(OD-7 · A3): 32자 소문자 16진수
    CONSTRAINT realtime_outbox_event_trace_id_ck CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$')
);
-- 발송 폴러(SQ-15) · Realtime 쪽 계정 삭제 참가자(BR5.14)
CREATE INDEX realtime_outbox_event_poll_idx ON realtime.realtime_outbox_event (status, available_at, created_at);
CREATE INDEX realtime_outbox_event_guardian_idx ON realtime.realtime_outbox_event (guardian_id);

-- ---------------------------------------------------------------------------
-- realtime_inbox_event — Core → Realtime 영속 이벤트 수락 기록(E4 16열 + A3 trace_id = 17열, BR5.5 ~ BR5.9 · BR5.11)
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_inbox_event (
    event_id        uuid         NOT NULL,
    source_service  varchar(50)  NOT NULL,
    event_type      varchar(100) NOT NULL,
    schema_version  integer      NOT NULL DEFAULT 1,
    occurred_at     timestamptz  NOT NULL,
    aggregate_id    uuid         NOT NULL,
    guardian_id     uuid,
    payload_json    jsonb        NOT NULL,
    payload_hash    varchar(64)  NOT NULL,
    status          varchar(30)  NOT NULL DEFAULT 'RECEIVED',
    retry_count     integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    received_at     timestamptz  NOT NULL,
    processed_at    timestamptz,
    last_error      text,
    purged_at       timestamptz,
    trace_id        varchar(32),
    CONSTRAINT realtime_inbox_event_pk PRIMARY KEY (event_id),
    CONSTRAINT realtime_inbox_event_source_ck CHECK (source_service = 'CORE'),
    CONSTRAINT realtime_inbox_event_status_ck CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'DEAD')),
    CONSTRAINT realtime_inbox_event_schema_version_ck CHECK (schema_version >= 1),
    CONSTRAINT realtime_inbox_event_retry_count_ck CHECK (retry_count BETWEEN 0 AND 5),
    -- RECEIVED 행은 폴러가 잡도록 next_attempt_at이 반드시 있다(BR5.6 · BR5.7)
    CONSTRAINT realtime_inbox_event_received_next_attempt_ck CHECK (
        status <> 'RECEIVED' OR next_attempt_at IS NOT NULL),
    CONSTRAINT realtime_inbox_event_trace_id_ck CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$')
);
-- 처리기 재호출 폴러(SQ-16) · Realtime 쪽 계정 삭제 참가자(BR5.14)
CREATE INDEX realtime_inbox_event_poll_idx ON realtime.realtime_inbox_event (status, next_attempt_at);
CREATE INDEX realtime_inbox_event_guardian_idx ON realtime.realtime_inbox_event (guardian_id);
