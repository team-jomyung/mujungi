-- 시험 전용 마이그레이션 (U1 3.5 Step 14 · SD-13 기본 권한 시험, SchemaPrivilegeIT)
-- 운영 위치(db/migration/core)에는 두지 않는다. 시험이 별도 인스턴스에 mujoong_owner로 core 위치 다음에 적용한다.
-- 뒤 유닛의 V2+처럼 소유자가 새 표를 만들면 01-roles.sql의 기본 권한이 core_app에 DML만 붙는지 본다.
CREATE TABLE core.core_test_default_privilege_probe (
    id   uuid NOT NULL,
    note varchar(50),
    CONSTRAINT core_test_default_privilege_probe_pk PRIMARY KEY (id)
);
