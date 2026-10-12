-- 무중 Core 스키마 V3 — U4a 활동 세션 Core 쪽 (docs/units/u4a/design.md 3절)
-- 근거: components.md ActivityCoordinator(ActivityContext) · ActivityRecord · FR3.1 · NFR3 · AC3.1.2 · AC3.1.7 · AC5.1.8 ·
--       계약 6절 SessionRecordCaptured · BR8.2(더하기만)
--
-- 적용 규칙(V1 · V2와 같다)
--   - mujoong_owner가 Flyway로 적용하고 앱은 validate만 한다. 표 소유는 mujoong_owner, core_app은 기본 권한으로 DML만(편차 (x)).
--   - DB 시각 기본값을 두지 않는다. 모든 시각은 앱 Clock 값을 바인딩한다(R2 · NFR6.4).
--   - 같은 스키마 안 참조만 외래 키(ON DELETE RESTRICT). 계정 삭제 참가자가 08 배경 → 활동 기록 → 세션 사본 순서로 지운다(J58 · R10).
--   - 기존 표 · 열은 바꾸지 않는다.

-- ---------------------------------------------------------------------------
-- core_activity_context — 08 자유대화 배경 네 칸(ActivityContext). 세션당 하나, 네 칸 중 하나라도 있을 때만 쓴다(U4a-J1).
-- 결과 구조화 입력 전용이다: 세션 패키지 · Realtime · 로그 · 보호자 응답에 내보내지 않는다(NFR3 · AC3.1.7).
-- 서술 칸 길이는 코드 포인트 200자(앱 검증과 같다, BR6.4 · 편차 (d)). varchar(n)은 글자 수로 센다.
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_activity_context (
    id             uuid         NOT NULL,
    session_id     uuid         NOT NULL,
    guardian_id    uuid         NOT NULL,
    child_id       uuid         NOT NULL,
    known_content  varchar(200),
    situation_type varchar(20),
    source_type    varchar(30),
    question       varchar(200),
    created_at     timestamptz  NOT NULL,
    CONSTRAINT core_activity_context_pk PRIMARY KEY (id),
    CONSTRAINT core_activity_context_session_uk UNIQUE (session_id),
    CONSTRAINT core_activity_context_session_fk FOREIGN KEY (session_id)
        REFERENCES core.core_activity (session_id) ON DELETE RESTRICT,
    CONSTRAINT core_activity_context_guardian_fk FOREIGN KEY (guardian_id)
        REFERENCES core.core_guardian (id) ON DELETE RESTRICT,
    CONSTRAINT core_activity_context_child_fk FOREIGN KEY (child_id)
        REFERENCES core.core_child (id) ON DELETE RESTRICT,
    CONSTRAINT core_activity_context_situation_ck CHECK (
        situation_type IS NULL OR situation_type IN ('FRIEND', 'SCHOOL', 'FAMILY', 'ACADEMY', 'OTHER')),
    CONSTRAINT core_activity_context_source_ck CHECK (
        source_type IS NULL OR source_type IN ('SEEN', 'HEARD_FROM_CHILD')),
    -- 네 칸이 모두 비면 행을 쓰지 않는다(U4a-J1)
    CONSTRAINT core_activity_context_not_empty_ck CHECK (
        known_content IS NOT NULL OR situation_type IS NOT NULL OR source_type IS NOT NULL OR question IS NOT NULL)
);
-- 계정 삭제 참가자(R10)
CREATE INDEX core_activity_context_guardian_idx ON core.core_activity_context (guardian_id);

-- ---------------------------------------------------------------------------
-- core_activity_record — 끝난 활동의 기록 원장(ActivityRecord). Realtime이 종료 때 보낸 SessionRecordCaptured 스냅숏을 그대로 옮겨
-- 담는다(J6). 판정값(practice_json 안)은 저장하지만 보호자 응답에서는 뺀다(FR4.3 · A10). DECLINED는 머리 정보만(FR3.5).
-- listed_at = 26 줄 시각(started_at, 없으면 사본 요청 시각, U4a-J8).
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_activity_record (
    session_id         uuid         NOT NULL,
    guardian_id        uuid         NOT NULL,
    child_id           uuid         NOT NULL,
    activity_type      varchar(30)  NOT NULL,
    final_status       varchar(30)  NOT NULL,
    end_reason         varchar(50),
    confirmed          boolean      NOT NULL,
    started_at         timestamptz,
    ended_at           timestamptz,
    listed_at          timestamptz  NOT NULL,
    record_from        timestamptz,
    record_to          timestamptz,
    transcript_json    jsonb        NOT NULL,
    gaps_json          jsonb        NOT NULL,
    practice_json      jsonb,
    audio_refs_json    jsonb        NOT NULL,
    termination_json   jsonb,
    source_snapshot_id uuid         NOT NULL,
    snapshot_version   integer,
    snapshot_hash      varchar(71)  NOT NULL,
    recorded_at        timestamptz  NOT NULL,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    CONSTRAINT core_activity_record_pk PRIMARY KEY (session_id),
    CONSTRAINT core_activity_record_session_fk FOREIGN KEY (session_id)
        REFERENCES core.core_activity (session_id) ON DELETE RESTRICT,
    CONSTRAINT core_activity_record_guardian_fk FOREIGN KEY (guardian_id)
        REFERENCES core.core_guardian (id) ON DELETE RESTRICT,
    CONSTRAINT core_activity_record_child_fk FOREIGN KEY (child_id)
        REFERENCES core.core_child (id) ON DELETE RESTRICT,
    CONSTRAINT core_activity_record_type_ck CHECK (activity_type IN ('FREE_TALK', 'PRACTICE')),
    CONSTRAINT core_activity_record_final_status_ck CHECK (
        final_status IN ('ENDED', 'ENDED_UNCONFIRMED', 'ABORTED', 'DECLINED', 'FAILED')),
    CONSTRAINT core_activity_record_hash_ck CHECK (snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT core_activity_record_json_ck CHECK (
        jsonb_typeof(transcript_json) = 'array' AND jsonb_typeof(gaps_json) = 'array'
        AND jsonb_typeof(audio_refs_json) = 'array')
);
-- 26 달력 · 그날 목록(보호자 · 시각 범위) · 계정 삭제 참가자
CREATE INDEX core_activity_record_guardian_listed_idx ON core.core_activity_record (guardian_id, listed_at);
