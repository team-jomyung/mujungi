-- 무중 Realtime 스키마 V3 — U4b 활동 세션 생애 (docs/units/u4b/design.md 6절 · Functional Design entities.md)
-- 근거: FR7.1 · BR8.1 · BR8.4 · BR8.5 · BR9.3 · U4b-R11 · U4b-R16 · U4b-J15 · U4b-J16 · J115(형식) · R2(DB 시각 기본값 없음) · BR8.2(더하기만)
--
-- 적용 규칙
--   - V1 · V2는 손대지 않는다. 이 파일은 표 하나와 색인만 더한다(열 삭제 · 이름 변경 · 필수화 · 기존 표 제약 강화 없음).
--   - mujoong_owner가 Flyway로 적용하고 realtime_app은 01-roles.sql의 기본 권한으로 DML만 받는다(V1 · V2와 같은 방식, 편차 (x)).
--   - 모든 시각은 앱 Clock 값을 바인딩한다. 세션 참조는 같은 스키마 안이라 외래 키(ON DELETE RESTRICT)다.

-- ---------------------------------------------------------------------------
-- realtime_result_input_snapshot — 종료된 세션의 결과 입력 기록(12열, entities ResultInputSnapshot · 계약 6절 SessionRecord).
-- 정산이 끝나면 SessionRecordCaptured Outbox와 같은 트랜잭션에 세션당 한 행(snapshot_version 1)을 쓴다(BR8.1 · BR8.5).
-- record_json은 전사 원문을 담으므로 로그에 쓰지 않는다(BR12.1). 계정 삭제 때 세션 참가자(200)가 세션 행보다 먼저 지운다(BR9.3).
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.realtime_result_input_snapshot (
    id               uuid        NOT NULL,
    session_id       uuid        NOT NULL,
    snapshot_version integer     NOT NULL DEFAULT 1,
    final_status     varchar(30) NOT NULL,
    end_reason       varchar(50) NOT NULL,
    confirmed        boolean     NOT NULL,
    policy_version   varchar(50) NOT NULL,
    cutoff_at        timestamptz NOT NULL,
    snapshot_hash    varchar(71) NOT NULL,
    record_json      jsonb       NOT NULL,
    captured_at      timestamptz NOT NULL,
    created_at       timestamptz NOT NULL,
    CONSTRAINT realtime_result_input_snapshot_pk PRIMARY KEY (id),
    CONSTRAINT realtime_result_input_snapshot_session_fk FOREIGN KEY (session_id)
        REFERENCES realtime.realtime_activity_session (id) ON DELETE RESTRICT,
    -- 세션당 한 판(U4b는 1만 쓴다, BR8.5)
    CONSTRAINT realtime_result_input_snapshot_version_uk UNIQUE (session_id, snapshot_version),
    CONSTRAINT realtime_result_input_snapshot_version_ck CHECK (snapshot_version >= 1),
    CONSTRAINT realtime_result_input_snapshot_status_ck CHECK (final_status IN (
        'ENDED', 'ENDED_UNCONFIRMED', 'DECLINED', 'ABORTED', 'FAILED')),
    CONSTRAINT realtime_result_input_snapshot_reason_ck CHECK (end_reason IN (
        'GUARDIAN_STOP', 'CHILD_STOP', 'SILENCE', 'DISCONNECT', 'CONSENT_WITHDRAWN', 'UNCONFIRMED', 'DECLINED', 'FAILED')),
    -- 'sha256:' + 16진 소문자 64자(BR8.4 · 계약 snapshotHash 형식)
    CONSTRAINT realtime_result_input_snapshot_hash_ck CHECK (snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT realtime_result_input_snapshot_record_ck CHECK (jsonb_typeof(record_json) = 'object')
);

-- ---------------------------------------------------------------------------
-- realtime_activity_session(guardian_id) 일반 색인: 진행 중 세션의 부분 유일 색인은 이미 있다(V1). 삭제 처리기 · 세션 참가자가
-- 종료 세션까지 보호자로 찾는다(BR9.1 · BR9.3). 제약이 아니라 이전 앱에 영향이 없다.
-- ---------------------------------------------------------------------------
CREATE INDEX realtime_activity_session_guardian_idx ON realtime.realtime_activity_session (guardian_id);
