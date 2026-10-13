-- 무중 Realtime 스키마 V2 — U3 음성 대화 (docs/units/u3/design.md 3절)
-- 근거: components.md AudioRecording · ConversationLog(끊김 구간) · DeviceGateway(끊김 횟수) · AC2.1.4 · AC2.1.10 · AC3.2.10(b) ·
--       AC6.4.7 · AC6.6.4 · K6 · BR8.2(더하기만) · J115(형식) · R2(DB 시각 기본값 없음) · 준하 제안(가정) U3-J2 · U3-J14 · U3-J18
--
-- 적용 규칙
--   - V1은 손대지 않는다. 이 파일은 표 3개와 색인만 더한다(열 삭제 · 이름 변경 · 필수화 없음).
--   - mujoong_owner가 Flyway로 적용하고 realtime_app은 01-roles.sql의 기본 권한으로 DML만 받는다(편차 (x)).
--   - 모든 시각은 앱 Clock 값을 바인딩한다. core 스키마를 가리키는 device_id는 외래 키 없는 논리 참조다(BR8.1).

-- ---------------------------------------------------------------------------
-- realtime_device_disconnect_event — 기기 끊김 사건(7열). 5분 창 끊김 횟수(불안정 판정, U3-R1)의 원천이다.
-- kind: CLOSED = HELLO_OK를 받은 연결이 서버 아닌 쪽 사유로 닫힘(1001 · 1006 · 1011 · 4400 · 4408 등, close_code 기록),
--       SILENT = 40초 무음으로 DISCONNECTED가 됨(close_code 없음). 「끄기」 1000과 4001 대체는 적지 않는다(U3-J2).
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_device_disconnect_event (
    id            uuid        NOT NULL,
    device_id     uuid        NOT NULL,
    connection_id uuid        NOT NULL,
    kind          varchar(20) NOT NULL,
    close_code    integer,
    occurred_at   timestamptz NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT realtime_device_disconnect_event_pk PRIMARY KEY (id),
    CONSTRAINT realtime_device_disconnect_event_kind_ck CHECK (kind IN ('CLOSED', 'SILENT')),
    CONSTRAINT realtime_device_disconnect_event_code_ck CHECK (close_code IS NULL OR close_code BETWEEN 1000 AND 4999)
);
-- 창 안 횟수 질의(기기 · 시각 범위)와 삭제 참가자
CREATE INDEX realtime_device_disconnect_event_device_idx
    ON realtime.realtime_device_disconnect_event (device_id, occurred_at);

-- ---------------------------------------------------------------------------
-- realtime_audio_recording — 동의된 세션의 원음 파일 메타(10열, components AudioRecording). 파일 자체는
-- mujoong.recording.directory 아래 <session_id>/<speaker 소문자>.pcm 이다(U3-J14). 세션 · 화자당 파일 하나.
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_audio_recording (
    id           uuid        NOT NULL,
    session_id   uuid        NOT NULL,
    speaker      varchar(30) NOT NULL,
    storage_path text        NOT NULL,
    format       varchar(30) NOT NULL,
    byte_count   bigint      NOT NULL DEFAULT 0,
    started_at   timestamptz NOT NULL,
    ended_at     timestamptz,
    deleted_at   timestamptz,
    created_at   timestamptz NOT NULL,
    CONSTRAINT realtime_audio_recording_pk PRIMARY KEY (id),
    CONSTRAINT realtime_audio_recording_session_fk FOREIGN KEY (session_id)
        REFERENCES realtime.realtime_activity_session (id) ON DELETE RESTRICT,
    CONSTRAINT realtime_audio_recording_session_speaker_uk UNIQUE (session_id, speaker),
    CONSTRAINT realtime_audio_recording_speaker_ck CHECK (speaker IN ('CHILD', 'AI')),
    CONSTRAINT realtime_audio_recording_bytes_ck CHECK (byte_count >= 0)
);

-- ---------------------------------------------------------------------------
-- realtime_session_gap — 세션 안 끊김 구간(6열, 계약 LiveView.gaps). DISCONNECT = 묶인 기기 연결이 끊긴 때부터 다시 붙은 때까지
-- (ended_at이 없으면 복구하지 못함), UNCONFIRMED = 자리만 둔다(ENDED_UNCONFIRMED, U4 · U3-J18).
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_session_gap (
    id         uuid        NOT NULL,
    session_id uuid        NOT NULL,
    kind       varchar(20) NOT NULL,
    started_at timestamptz NOT NULL,
    ended_at   timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT realtime_session_gap_pk PRIMARY KEY (id),
    CONSTRAINT realtime_session_gap_session_fk FOREIGN KEY (session_id)
        REFERENCES realtime.realtime_activity_session (id) ON DELETE RESTRICT,
    CONSTRAINT realtime_session_gap_kind_ck CHECK (kind IN ('DISCONNECT', 'UNCONFIRMED')),
    CONSTRAINT realtime_session_gap_order_ck CHECK (ended_at IS NULL OR ended_at >= started_at)
);
-- 12 조회(세션 · 시작 순)
CREATE INDEX realtime_session_gap_session_idx ON realtime.realtime_session_gap (session_id, started_at);
