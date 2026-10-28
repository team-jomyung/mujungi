-- JIRA-280: 관리자 명단. 기존 회원 표(core_guardian)에 열을 더하지 않고 별도 표로 둔다.
-- 관리자는 기존 보호자 로그인 세션을 그대로 쓰고, /api/admin/** 요청마다 이 표에서 ACTIVE인지 확인한다.
-- 등록 · 회수는 운영 SQL로만 한다(infra/RUNBOOK.md). 등록 화면 · API는 없다.
-- 보호자 계정이 지워지면 관리자 행도 함께 지운다(계정 삭제 마지막 단계의 core_guardian 삭제를 막지 않게).
CREATE TABLE core.core_admin_account (
    guardian_id uuid PRIMARY KEY REFERENCES core.core_guardian (id) ON DELETE CASCADE,
    status varchar(10) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
