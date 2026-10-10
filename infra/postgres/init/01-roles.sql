-- 무중 PostgreSQL 초기화 SQL — 역할 4 · DB · 스키마 3 · 권한 · 기본 권한 · 데이터 등급 표지
-- (U1 3.5 Step 9 · SD-13 · BR8.1 · NFR9.7 · SD-24 · J84 · J115 · J116 · J117 · [편차 (x)])
--
-- 01-roles.sh가 `-v mujoong_init_runner=01-roles.sh`로 실행한다. postgres 이미지 entrypoint가 이 .sql을 직접 실행하면
-- (변수 없이) 아무것도 하지 않고 끝난다. 비밀번호는 이 파일에 없고 \getenv로 환경 변수에서 읽는다.
--
-- [편차 (x)] 계약 8절 C13 `owner_role: core_app`은 '그 스키마에 쓰는 앱 역할'로 읽는다. 스키마 · 표의 소유자는 마이그레이션 역할
-- mujoong_owner이고 앱 역할은 자기 스키마의 DML과 시퀀스 사용만 가진다(DDL · TRUNCATE 없음, 상대 스키마 권한 없음).
-- 멱등이다: 다시 실행해도 같은 결과다(역할이 있으면 속성 · 비밀번호를 다시 맞춘다).

\if :{?mujoong_init_runner}
\else
  \echo '01-roles.sql: 01-roles.sh가 실행하는 파일이라 직접 실행은 건너뛴다'
  \quit
\endif

-- 실패한 문장 본문(비밀번호를 담을 수 있다)을 서버 로그에 남기지 않는다(SD-15 층 7 · BR8.7)
SET log_min_error_statement = 'panic';
SET log_statement = 'none';

\getenv owner_password DB_OWNER_PASSWORD
\getenv core_password DB_CORE_PASSWORD
\getenv realtime_password DB_REALTIME_PASSWORD
\getenv backup_password DB_BACKUP_PASSWORD

-- ---------------------------------------------------------------------------
-- 역할 4개 (모두 NOSUPERUSER · NOCREATEDB · NOCREATEROLE · NOREPLICATION · NOBYPASSRLS)
-- 연결 한도: owner 4 · core_app 12 · realtime_app 12 · backup 2 (infra-spec 2.5, 풀 10 + 기동 가드 1 + 여유 1)
-- ---------------------------------------------------------------------------
SELECT format('%s ROLE mujoong_owner WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 4 PASSWORD %L',
              CASE WHEN EXISTS (SELECT FROM pg_roles WHERE rolname = 'mujoong_owner') THEN 'ALTER' ELSE 'CREATE' END,
              :'owner_password')
\gexec
SELECT format('%s ROLE core_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 12 PASSWORD %L',
              CASE WHEN EXISTS (SELECT FROM pg_roles WHERE rolname = 'core_app') THEN 'ALTER' ELSE 'CREATE' END,
              :'core_password')
\gexec
SELECT format('%s ROLE realtime_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 12 PASSWORD %L',
              CASE WHEN EXISTS (SELECT FROM pg_roles WHERE rolname = 'realtime_app') THEN 'ALTER' ELSE 'CREATE' END,
              :'realtime_password')
\gexec
SELECT format('%s ROLE mujoong_backup WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 2 PASSWORD %L',
              CASE WHEN EXISTS (SELECT FROM pg_roles WHERE rolname = 'mujoong_backup') THEN 'ALTER' ELSE 'CREATE' END,
              :'backup_password')
\gexec

-- 백업은 두 스키마를 읽어야 하므로 전체 읽기 전용 기본 역할을 받는다(J84(b) · infra-spec 2.1 backup)
GRANT pg_read_all_data TO mujoong_backup;

\unset owner_password
\unset core_password
\unset realtime_password
\unset backup_password

-- ---------------------------------------------------------------------------
-- 데이터베이스 mujoong(J117). 소유자는 슈퍼유저 그대로 둔다 — 앱 역할은 ALTER DATABASE를 할 수 없다(SD-24)
-- ---------------------------------------------------------------------------
SELECT 'CREATE DATABASE mujoong' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mujoong')
\gexec
REVOKE ALL ON DATABASE mujoong FROM PUBLIC;
GRANT CONNECT ON DATABASE mujoong TO mujoong_owner, core_app, realtime_app, mujoong_backup;
-- 데이터 등급 표지: 시연 데이터는 합성(synthetic). 없거나 모르는 값이면 앱 · 백업이 real로 본다(fail-closed, J124 · SD-24)
ALTER DATABASE mujoong SET mujoong_db.data_class = 'synthetic';
ALTER DATABASE mujoong SET timezone = 'UTC';

\connect mujoong
SET log_min_error_statement = 'panic';
SET log_statement = 'none';

-- public 스키마는 쓰지 않는다(전원에게서 CREATE · USAGE 회수)
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- 스키마 3개, 소유 mujoong_owner. 이력 표는 flyway 스키마(J116: flyway.core_schema_history · flyway.realtime_schema_history)
CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION mujoong_owner;
CREATE SCHEMA IF NOT EXISTS realtime AUTHORIZATION mujoong_owner;
CREATE SCHEMA IF NOT EXISTS flyway AUTHORIZATION mujoong_owner;
REVOKE ALL ON SCHEMA core, realtime, flyway FROM PUBLIC;

-- 앱 역할: 자기 스키마 USAGE만(CREATE 없음 → DDL 불가), 상대 스키마는 권한 없음(BR8.1 · NFR9.7)
GRANT USAGE ON SCHEMA core TO core_app;
GRANT USAGE ON SCHEMA realtime TO realtime_app;
-- 자기 이력 표 SELECT는 각 마이그레이션 위치의 afterMigrate.sql이 준다(init 때는 표가 없다, J116)
GRANT USAGE ON SCHEMA flyway TO core_app, realtime_app;

-- 기본 권한: mujoong_owner가 이후 만드는 표 · 시퀀스에 앱 권한이 자동으로 붙는다(DML · 시퀀스만, TRUNCATE · DDL 없음)
ALTER DEFAULT PRIVILEGES FOR ROLE mujoong_owner IN SCHEMA core
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_app;
ALTER DEFAULT PRIVILEGES FOR ROLE mujoong_owner IN SCHEMA core
    GRANT USAGE, SELECT ON SEQUENCES TO core_app;
ALTER DEFAULT PRIVILEGES FOR ROLE mujoong_owner IN SCHEMA realtime
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO realtime_app;
ALTER DEFAULT PRIVILEGES FOR ROLE mujoong_owner IN SCHEMA realtime
    GRANT USAGE, SELECT ON SEQUENCES TO realtime_app;
-- owner가 만드는 함수의 기본 EXECUTE를 PUBLIC에서 회수한다
ALTER DEFAULT PRIVILEGES FOR ROLE mujoong_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- 역할별 search_path(이 DB 안에서)
ALTER ROLE core_app IN DATABASE mujoong SET search_path = core;
ALTER ROLE realtime_app IN DATABASE mujoong SET search_path = realtime;
