-- 무중 Core 스키마 V2 — U2 보호자 계정 (docs/units/u2/design.md 3절)
-- 근거: components.md GuardianIdentity · ConsentPolicy · ChildProfile · AccountDeletion · FR1.2 · FR1.4 · FR1.6 · FR2.4 ·
--       AC1.3.8 · AC1.6.1 · AC1.6.7 · AC2.3.6 · U1 J115('core_consent.child_id 완화는 U2가 V2로') · BR8.2(더하기만)
--
-- 적용 규칙(V1과 같다)
--   - mujoong_owner가 Flyway로 적용하고 앱은 validate만 한다. 표 소유는 mujoong_owner, core_app은 기본 권한으로 DML만(편차 (x)).
--   - DB 시각 기본값을 두지 않는다. 모든 시각은 앱 Clock 값을 바인딩한다(R2 · NFR6.4).
--   - 같은 스키마 안 참조만 외래 키(ON DELETE RESTRICT). 계정 삭제 지휘가 참가자 순서로 지운다(BR2.10 · J58).
--   - 열 삭제 · 이름 변경 · 필수화 없음. child_id NOT NULL 해제와 CHECK · 유일 제약 추가만 한다.

-- ---------------------------------------------------------------------------
-- core_consent — 동의는 아이 등록(03)보다 먼저라 child_id를 비울 수 있다(U2-J10). 동의 당시 약관 본문 버전을 가리킨다.
-- ---------------------------------------------------------------------------
ALTER TABLE core.core_consent ALTER COLUMN child_id DROP NOT NULL;
ALTER TABLE core.core_consent ADD COLUMN terms_version_id uuid;
ALTER TABLE core.core_consent ADD CONSTRAINT core_consent_terms_version_fk FOREIGN KEY (terms_version_id)
    REFERENCES core.core_approved_content_version (id) ON DELETE RESTRICT;
ALTER TABLE core.core_consent ADD CONSTRAINT core_consent_consent_version_ck CHECK (consent_version >= 1);
-- 보호자 기준 동의 판정 · 계정 삭제 참가자(R6 · R11)
CREATE INDEX core_consent_actor_type_idx ON core.core_consent (actor_guardian_id, consent_type);

-- core_child는 바꾸지 않는다: 이름 1 ~ 10자 · 나이 6 ~ 12 · 관심사 규칙(AC1.3.8)은 앱(ChildInput)이 검증한다. U1 세션 패키지는 DB에
-- 규칙 밖 값이 있어도 걸러 내는 것을 시험하므로(SessionPackageIT) DB CHECK로 막지 않는다.

-- ---------------------------------------------------------------------------
-- core_stop_expression — 아이별 추가 종료 표현(최대 5개 · 2 ~ 20자 · 정규화 기준 중복 금지, FR2.4 · AC2.3.6)
-- ---------------------------------------------------------------------------
ALTER TABLE core.core_stop_expression ADD CONSTRAINT core_stop_expression_text_ck CHECK (
    char_length(expression_text) BETWEEN 2 AND 20);
ALTER TABLE core.core_stop_expression ADD CONSTRAINT core_stop_expression_sort_order_ck CHECK (sort_order BETWEEN 1 AND 5);
ALTER TABLE core.core_stop_expression ADD CONSTRAINT core_stop_expression_normalized_uk UNIQUE (child_id, normalized_text);
ALTER TABLE core.core_stop_expression ADD CONSTRAINT core_stop_expression_sort_order_uk UNIQUE (child_id, sort_order)
    DEFERRABLE INITIALLY DEFERRED;

-- ---------------------------------------------------------------------------
-- core_social_account — 구글 계정 ↔ 보호자(GuardianSocialAccount). 식별은 구글 sub, 이메일은 저장하지 않는다(U2-J3).
-- 삭제 처리 중에도 남아 같은 구글 계정을 '삭제 처리 중'으로 식별하고 완료 때 지워진다(AC1.6.7).
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_social_account (
    id               uuid         NOT NULL,
    guardian_id      uuid         NOT NULL,
    provider         varchar(20)  NOT NULL,
    provider_user_id varchar(255) NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT core_social_account_pk PRIMARY KEY (id),
    CONSTRAINT core_social_account_provider_user_uk UNIQUE (provider, provider_user_id),
    CONSTRAINT core_social_account_guardian_fk FOREIGN KEY (guardian_id)
        REFERENCES core.core_guardian (id) ON DELETE RESTRICT,
    CONSTRAINT core_social_account_provider_ck CHECK (provider IN ('GOOGLE')),
    CONSTRAINT core_social_account_provider_user_ck CHECK (char_length(provider_user_id) BETWEEN 1 AND 255)
);
CREATE INDEX core_social_account_guardian_idx ON core.core_social_account (guardian_id);

-- ---------------------------------------------------------------------------
-- core_data_deletion_request — 접수형 계정 삭제 요청(DataDeletionRequest, AC1.6.1 · AC1.6.7 · AC1.6.8).
-- guardian_id는 논리 참조다(FK 없음): 보호자 행이 마지막에 지워진 뒤에도 DONE 기록이 남는다. 보호자당 REQUESTED 하나.
-- 진행 표시 열: 세션 끝 요청 → Core 참가자 끝 → DeletionRequested 기록 → DeletionCompleted 수신 → 완료(R11).
-- ---------------------------------------------------------------------------
CREATE TABLE core.core_data_deletion_request (
    id                       uuid         NOT NULL,
    guardian_id              uuid         NOT NULL,
    child_id                 uuid,
    status                   varchar(20)  NOT NULL,
    requested_at             timestamptz  NOT NULL,
    blocked_at               timestamptz  NOT NULL,
    session_end_requested_at timestamptz,
    core_cleared_at          timestamptz,
    realtime_requested_at    timestamptz,
    realtime_completed_at    timestamptz,
    completed_at             timestamptz,
    attempt_count            integer      NOT NULL DEFAULT 0,
    next_attempt_at          timestamptz  NOT NULL,
    last_error               varchar(100),
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT core_data_deletion_request_pk PRIMARY KEY (id),
    CONSTRAINT core_data_deletion_request_status_ck CHECK (status IN ('REQUESTED', 'DONE')),
    CONSTRAINT core_data_deletion_request_done_ck CHECK (status <> 'DONE' OR completed_at IS NOT NULL),
    CONSTRAINT core_data_deletion_request_attempt_ck CHECK (attempt_count >= 0),
    -- Realtime 완료는 Realtime 요청 뒤에만 있다
    CONSTRAINT core_data_deletion_request_realtime_ck CHECK (
        realtime_completed_at IS NULL OR realtime_requested_at IS NOT NULL)
);
CREATE UNIQUE INDEX core_data_deletion_request_open_guardian_uk
    ON core.core_data_deletion_request (guardian_id)
    WHERE status = 'REQUESTED';
-- 지휘 폴러(REQUESTED · 도래 순)
CREATE INDEX core_data_deletion_request_poll_idx ON core.core_data_deletion_request (status, next_attempt_at);
