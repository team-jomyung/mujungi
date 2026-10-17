-- 무중 Realtime 스키마 V6 — U13 제브 검사 기록 (U13 기능 설계 entities.md GuardrailEvent · functional-spec.md 7절)
-- 근거: FR6.1 · FR6.2 · BR8.1 ~ BR8.3 · NFR3 · NFR4 · R-F14 · R2(DB 시각 기본값 없음)
--
-- 적용 규칙
--   - V1 ~ V5는 손대지 않는다. 이 파일은 표 하나와 색인 하나만 더한다(열 삭제 · 이름 변경 · 기존 표 제약 강화 없음, 이전 앱에 영향 없음).
--     기존 표에 제약을 더하는 문장이 없으므로 infra/migration-approvals.txt 승인 대상이 아니다(R-M3).
--   - mujoong_owner가 Flyway로 적용하고 realtime_app은 01-roles.sql의 기본 권한으로 DML만 받는다(V1 ~ V5와 같은 방식).
--   - 시각은 앱 MujoongClock 값을 바인딩한다(기본값 없음).
--   - 전사 원문 · 후보 문장 · 맥락을 담는 칸이 없다. 이유 코드 · 정책 판 · 지연 · 판정만 남긴다(R-F14).

-- ---------------------------------------------------------------------------
-- guardrail_event — 제브 검사 결과마다 1건(OFF 제외). id = 검사 요청 id라 같은 id 덮어쓰기로 재시도가 멱등이다(BR8.2).
-- guardian_id는 계정 삭제 참여자(순서 185, 판정 기록 180 뒤 · 세션 200 앞)의 기준이다(BR8.3). 세션 참조는 같은 스키마 안이라 외래 키(ON DELETE RESTRICT)다.
-- ---------------------------------------------------------------------------
CREATE TABLE realtime.guardrail_event (
    id              uuid          NOT NULL,
    guardian_id     uuid          NOT NULL,
    session_id      uuid          NOT NULL,
    mode            varchar(10)   NOT NULL,
    source_kind     varchar(30)   NOT NULL,
    decision        varchar(20)   NOT NULL,
    reason_code     varchar(80)   NOT NULL,
    policy_version  varchar(80)   NOT NULL,
    latency_ms      integer       NOT NULL,
    created_at      timestamptz   NOT NULL,
    CONSTRAINT guardrail_event_pk PRIMARY KEY (id),
    CONSTRAINT guardrail_event_session_fk FOREIGN KEY (session_id)
        REFERENCES realtime.realtime_activity_session (id) ON DELETE RESTRICT,
    -- OFF는 제브 단계를 건너뛰므로 기록이 없다(BR1.5)
    CONSTRAINT guardrail_event_mode_ck CHECK (mode IN ('REAL', 'STUB')),
    CONSTRAINT guardrail_event_source_ck CHECK (source_kind IN ('NEW_UTTERANCE', 'APPROVED_VERBATIM_SKIPPED')),
    CONSTRAINT guardrail_event_decision_ck CHECK (decision IN (
        'ALLOW', 'BLOCK', 'REVIEW', 'TIMEOUT', 'ERROR', 'VOIDED', 'QUEUE_FULL')),
    -- guardrail-result.schema.json reasonCode와 같은 형식(사유 코드만, 문장 · 원문 불가)
    CONSTRAINT guardrail_event_reason_ck CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    CONSTRAINT guardrail_event_policy_ck CHECK (char_length(policy_version) >= 1),
    CONSTRAINT guardrail_event_latency_ck CHECK (latency_ms >= 0)
);

CREATE INDEX guardrail_event_guardian_idx ON realtime.guardrail_event (guardian_id);
