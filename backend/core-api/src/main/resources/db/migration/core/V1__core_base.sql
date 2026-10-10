-- 무중 Core 기준 스키마 V1 (U1 3.5 Step 10)
-- 근거: BR8.1 · BR8.2 · J43 · entities.md E1 ~ E8 · A3(trace_id) · A4(guardian_id) · J115 · J116 · PD-8 · SD-13
--
-- 적용 규칙
--   - mujoong_owner가 Flyway(위치 db/migration/core, 이력 표 flyway.core_schema_history)로 적용한다. 앱은 validate만 한다(BR8.1).
--   - 표 12개를 core 스키마에 만든다. 이름은 스키마로 한정한다(J116). 표 소유자는 mujoong_owner이고 core_app은
--     01-roles.sql의 기본 권한으로 DML만 받는다(편차 (x)).
--   - 형식(J115): uuid → uuid, string(n) → varchar(n), timestamp → timestamptz(엔터티 필드 Instant), json → jsonb.
--   - DB 시각 기본값을 두지 않는다. 모든 시각은 앱 Clock 값을 바인딩한다(R2 · NFR6.4).
--   - 같은 스키마 안의 참조만 외래 키이고 ON DELETE RESTRICT다. 계정 삭제 순서 core_activity → core_device → core_guardian을
--     DB가 강제한다(BR2.10 · J58). 다른 스키마를 가리키는 열은 논리 참조(UUID)다(BR8.1).
--   - 뒤 유닛은 V2+로 열 · 색인을 더하기만 한다(BR8.2).

-- ---------------------------------------------------------------------------
-- core_guardian — 보호자 계정(U1은 dev 시드 한 명, 완성은 U2)
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_guardian (
    id         uuid        NOT NULL,
    status     varchar(30) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT core_guardian_pk PRIMARY KEY (id),
    CONSTRAINT core_guardian_status_ck CHECK (status IN ('ACTIVE', 'DELETION_REQUESTED'))
);

-- ---------------------------------------------------------------------------
-- core_spring_session · core_spring_session_attributes — Spring Session JDBC 표준 정의(PK · CASCADE 그대로),
-- 이름만 core_spring_session*(J115 · spring.session.jdbc.table-name). 시각은 Spring Session이 쓰는 epoch 밀리초 bigint다.
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_spring_session (
    primary_id            char(36)     NOT NULL,
    session_id            char(36)     NOT NULL,
    creation_time         bigint       NOT NULL,
    last_access_time      bigint       NOT NULL,
    max_inactive_interval integer      NOT NULL,
    expiry_time           bigint       NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT core_spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX core_spring_session_ix1 ON core.core_spring_session (session_id);
CREATE INDEX core_spring_session_ix2 ON core.core_spring_session (expiry_time);
CREATE INDEX core_spring_session_ix3 ON core.core_spring_session (principal_name);

CREATE TABLE core.core_spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT core_spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT core_spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES core.core_spring_session (primary_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- core_child — 보호자당 아이 한 명(U1은 dev 시드, 검증 규칙은 U2)
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_child (
    id             uuid         NOT NULL,
    guardian_id    uuid         NOT NULL,
    display_name   varchar(100) NOT NULL,
    age            integer,
    interests_json jsonb,
    status         varchar(30)  NOT NULL,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    deleted_at     timestamptz,
    CONSTRAINT core_child_pk PRIMARY KEY (id),
    CONSTRAINT core_child_guardian_uk UNIQUE (guardian_id),
    CONSTRAINT core_child_guardian_fk FOREIGN KEY (guardian_id)
        REFERENCES core.core_guardian (id) ON DELETE RESTRICT
);

-- ---------------------------------------------------------------------------
-- core_consent — 4항목 동의 기록(U1은 dev 시드 GIVEN 4행, 로직은 U2)
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_consent (
    id                uuid        NOT NULL,
    actor_guardian_id uuid        NOT NULL,
    child_id          uuid        NOT NULL,
    consent_type      varchar(50) NOT NULL,
    consent_status    varchar(30) NOT NULL,
    consent_version   integer     NOT NULL,
    policy_version    varchar(50),
    effective_from    timestamptz NOT NULL,
    effective_to      timestamptz,
    created_at        timestamptz NOT NULL,
    CONSTRAINT core_consent_pk PRIMARY KEY (id),
    CONSTRAINT core_consent_actor_guardian_fk FOREIGN KEY (actor_guardian_id)
        REFERENCES core.core_guardian (id) ON DELETE RESTRICT,
    CONSTRAINT core_consent_child_fk FOREIGN KEY (child_id)
        REFERENCES core.core_child (id) ON DELETE RESTRICT,
    CONSTRAINT core_consent_type_ck CHECK (consent_type IN ('VOICE_AND_TEXT', 'GUARDIAN_VIEW', 'REPORT_SHARING', 'RAW_AUDIO')),
    CONSTRAINT core_consent_status_ck CHECK (consent_status IN ('GIVEN', 'WITHDRAWN'))
);

-- ---------------------------------------------------------------------------
-- core_stop_expression — 아이별 추가 종료 표현(U1은 표만 만든다. 기본 표현은 코드 상수, BR6.5 · J12 · E6)
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_stop_expression (
    id              uuid        NOT NULL,
    child_id        uuid        NOT NULL,
    expression_text varchar(20) NOT NULL,
    normalized_text varchar(40) NOT NULL,
    sort_order      integer     NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT core_stop_expression_pk PRIMARY KEY (id),
    CONSTRAINT core_stop_expression_child_fk FOREIGN KEY (child_id)
        REFERENCES core.core_child (id) ON DELETE RESTRICT
);

-- ---------------------------------------------------------------------------
-- core_approved_content · core_approved_content_version — 승인 문구와 버전(U1 시드: CLOSING · WAITING_FILLER · CONSENT_POLICY,
-- J52 · J60 · J114). audio_path는 승인 음성 정적 경로(/media/approved/) 아래 파일 이름이다(E7 · 계약 ApprovedAudioUrl).
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_approved_content (
    id           uuid         NOT NULL,
    content_code varchar(100) NOT NULL,
    content_type varchar(50)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT core_approved_content_pk PRIMARY KEY (id),
    CONSTRAINT core_approved_content_code_uk UNIQUE (content_code)
);

CREATE TABLE core.core_approved_content_version (
    id           uuid         NOT NULL,
    content_id   uuid         NOT NULL,
    version      integer      NOT NULL,
    content_text text         NOT NULL,
    audio_path   varchar(500),
    status       varchar(30)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT core_approved_content_version_pk PRIMARY KEY (id),
    CONSTRAINT core_approved_content_version_content_fk FOREIGN KEY (content_id)
        REFERENCES core.core_approved_content (id) ON DELETE RESTRICT,
    CONSTRAINT core_approved_content_version_uk UNIQUE (content_id, version),
    CONSTRAINT core_approved_content_version_version_ck CHECK (version >= 1),
    CONSTRAINT core_approved_content_version_status_ck CHECK (status IN ('DRAFT', 'APPROVED', 'RETIRED')),
    CONSTRAINT core_approved_content_version_audio_path_ck CHECK (
        audio_path IS NULL OR audio_path ~ '^[A-Za-z0-9._-]+\.wav$')
);

-- ---------------------------------------------------------------------------
-- core_device — 아이 기기 한 대의 등록 수명과 Realtime 보고의 연결 상태 사본(U1 소유, E1, 22열)
-- 갱신이 잦아 fillfactor 90(PD-8). 상태 검사 4개, 부분 유일 색인 2개(계정당 활성 기기 하나 · 발급 중 코드 유일).
-- 교체 등록은 옛 행을 RELEASED로 바꾼 뒤 새 행을 REGISTERED로 바꾼다(부분 유일 색인은 지연할 수 없다, BR2.5).
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_device (
    id                        uuid        NOT NULL,
    guardian_id               uuid,
    registration_code         varchar(8)  NOT NULL,
    code_issued_at            timestamptz NOT NULL,
    code_expires_at           timestamptz NOT NULL,
    bootstrap_secret_hash     varchar(64) NOT NULL,
    device_token_hash         varchar(64),
    token_issued_at           timestamptz,
    first_connected_at        timestamptz,
    registration_status       varchar(20) NOT NULL DEFAULT 'UNREGISTERED',
    registered_at             timestamptz,
    released_at               timestamptz,
    release_reason            varchar(30),
    reported_connection_state varchar(20),
    reported_at               timestamptz,
    last_seen_at              timestamptz,
    connection_since          timestamptz,
    rtt_ms                    integer,
    recent_disconnect_count   integer     NOT NULL DEFAULT 0,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    row_version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT core_device_pk PRIMARY KEY (id),
    CONSTRAINT core_device_bootstrap_secret_hash_uk UNIQUE (bootstrap_secret_hash),
    CONSTRAINT core_device_device_token_hash_uk UNIQUE (device_token_hash),
    CONSTRAINT core_device_guardian_fk FOREIGN KEY (guardian_id)
        REFERENCES core.core_guardian (id) ON DELETE RESTRICT,
    -- 등록 축 상태 4개(SM1)
    CONSTRAINT core_device_status_ck CHECK (
        registration_status IN ('UNREGISTERED', 'REGISTERED', 'RELEASED', 'EXPIRED')),
    -- REGISTERED이면 보호자와 등록 시각이 있다(BR2.3 · AC2.2.7)
    CONSTRAINT core_device_registered_ck CHECK (
        registration_status <> 'REGISTERED' OR (guardian_id IS NOT NULL AND registered_at IS NOT NULL)),
    -- RELEASED이면 해제 시각 · 사유가 있고 토큰 해시를 비운다(BR2.5 · BR3.3 · BR2.10)
    CONSTRAINT core_device_released_ck CHECK (
        registration_status <> 'RELEASED'
        OR (released_at IS NOT NULL
            AND release_reason IS NOT NULL
            AND release_reason IN ('REREGISTERED', 'ACCOUNT_DELETED')
            AND device_token_hash IS NULL)),
    -- 등록 전 · 만료 행에는 보호자가 없다(BR1.4 · BR2.10)
    CONSTRAINT core_device_unbound_ck CHECK (
        registration_status NOT IN ('UNREGISTERED', 'EXPIRED') OR guardian_id IS NULL)
) WITH (fillfactor = 90);

CREATE UNIQUE INDEX core_device_active_guardian_uk
    ON core.core_device (guardian_id)
    WHERE registration_status = 'REGISTERED';
CREATE UNIQUE INDEX core_device_pending_code_uk
    ON core.core_device (registration_code)
    WHERE registration_status = 'UNREGISTERED';

-- ---------------------------------------------------------------------------
-- core_activity — Core의 세션 사본(E3, 20열). 사본 갱신은 status_version 조건부 UPDATE 한 문장이다(BR5.10 · BR6.14).
-- 로컬 FAILED(local_failed)는 status FAILED · failure_code 비움 · start_result_code 필수(BR6.2 · BR6.6 · BR6.7 · BR6.12 ①).
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_activity (
    session_id            uuid         NOT NULL,
    guardian_id           uuid         NOT NULL,
    child_id              uuid         NOT NULL,
    device_id             uuid         NOT NULL,
    activity_type         varchar(30)  NOT NULL,
    status                varchar(30)  NOT NULL DEFAULT 'REQUESTED',
    status_version        integer      NOT NULL DEFAULT 0,
    end_reason            varchar(50),
    failure_code          varchar(50),
    confirmed             boolean      NOT NULL DEFAULT false,
    local_failed          boolean      NOT NULL DEFAULT false,
    start_result_code     integer,
    start_idempotency_key varchar(100) NOT NULL,
    request_hash          varchar(64)  NOT NULL,
    requested_at          timestamptz  NOT NULL,
    started_at            timestamptz,
    ended_at              timestamptz,
    observed_at           timestamptz,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    CONSTRAINT core_activity_pk PRIMARY KEY (session_id),
    -- 같은 보호자의 같은 키는 같은 세션이다(BR6.2 · AC3.1.8)
    CONSTRAINT core_activity_idempotency_uk UNIQUE (guardian_id, start_idempotency_key),
    CONSTRAINT core_activity_guardian_fk FOREIGN KEY (guardian_id)
        REFERENCES core.core_guardian (id) ON DELETE RESTRICT,
    CONSTRAINT core_activity_child_fk FOREIGN KEY (child_id)
        REFERENCES core.core_child (id) ON DELETE RESTRICT,
    CONSTRAINT core_activity_device_fk FOREIGN KEY (device_id)
        REFERENCES core.core_device (id) ON DELETE RESTRICT,
    CONSTRAINT core_activity_type_ck CHECK (activity_type IN ('FREE_TALK', 'PRACTICE')),
    CONSTRAINT core_activity_status_ck CHECK (status IN (
        'REQUESTED', 'PREPARING', 'DEVICE_ACK', 'WAITING_CHILD', 'ACTIVE', 'CLOSING',
        'ENDED', 'ENDED_UNCONFIRMED', 'ABORTED', 'DECLINED', 'FAILED')),
    CONSTRAINT core_activity_status_version_ck CHECK (status_version >= 0),
    CONSTRAINT core_activity_local_failed_ck CHECK ((
        (local_failed
            AND status = 'FAILED'
            AND failure_code IS NULL
            AND start_result_code IS NOT NULL
            AND start_result_code IN (202, 409, 423, 503))
        OR (NOT local_failed AND start_result_code IS NULL)
    ) IS TRUE)
);

-- 보호자당 진행 중 세션 하나(BR6.3 · AC3.1.8). 시작 검사 질의는 이 상태 목록을 그대로 쓴다(PD-8 SQ-7)
CREATE UNIQUE INDEX core_activity_in_progress_guardian_uk
    ON core.core_activity (guardian_id)
    WHERE status IN ('REQUESTED', 'PREPARING', 'DEVICE_ACK', 'WAITING_CHILD', 'ACTIVE', 'CLOSING');

-- ---------------------------------------------------------------------------
-- core_outbox_event — Core → Realtime 영속 이벤트(E4 16열 + A3 trace_id = 17열, BR5.1 ~ BR5.3 · BR5.11)
-- 선점은 available_at을 claim-lease만큼 미는 방식이라 선점 열이 없다(RD-8 · SQ-15).
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_outbox_event (
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
    CONSTRAINT core_outbox_event_pk PRIMARY KEY (event_id),
    CONSTRAINT core_outbox_event_status_ck CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT core_outbox_event_schema_version_ck CHECK (schema_version >= 1),
    CONSTRAINT core_outbox_event_retry_count_ck CHECK (retry_count BETWEEN 0 AND 20),
    -- PUBLISHED가 되면 payload를 비운다(BR5.11)
    CONSTRAINT core_outbox_event_published_purged_ck CHECK (
        status <> 'PUBLISHED' OR purged_at IS NOT NULL OR payload_json = '{}'::jsonb),
    -- traceId 형식(OD-7 · A3): 32자 소문자 16진수
    CONSTRAINT core_outbox_event_trace_id_ck CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$')
);
-- 발송 폴러(SQ-15) · Core 쪽 계정 삭제 참가자(BR5.14)
CREATE INDEX core_outbox_event_poll_idx ON core.core_outbox_event (status, available_at, created_at);
CREATE INDEX core_outbox_event_guardian_idx ON core.core_outbox_event (guardian_id);

-- ---------------------------------------------------------------------------
-- core_inbox_event — Realtime → Core 영속 이벤트 수락 기록(E4 16열 + A3 trace_id = 17열, BR5.5 ~ BR5.9 · BR5.11)
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_inbox_event (
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
    CONSTRAINT core_inbox_event_pk PRIMARY KEY (event_id),
    CONSTRAINT core_inbox_event_source_ck CHECK (source_service = 'REALTIME'),
    CONSTRAINT core_inbox_event_status_ck CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'DEAD')),
    CONSTRAINT core_inbox_event_schema_version_ck CHECK (schema_version >= 1),
    CONSTRAINT core_inbox_event_retry_count_ck CHECK (retry_count BETWEEN 0 AND 5),
    -- RECEIVED 행은 폴러가 잡도록 next_attempt_at이 반드시 있다(BR5.6 · BR5.7)
    CONSTRAINT core_inbox_event_received_next_attempt_ck CHECK (status <> 'RECEIVED' OR next_attempt_at IS NOT NULL),
    CONSTRAINT core_inbox_event_trace_id_ck CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$')
);
-- 처리기 재호출 폴러(SQ-16) · Core 쪽 계정 삭제 참가자(BR5.14)
CREATE INDEX core_inbox_event_poll_idx ON core.core_inbox_event (status, next_attempt_at);
CREATE INDEX core_inbox_event_guardian_idx ON core.core_inbox_event (guardian_id);
