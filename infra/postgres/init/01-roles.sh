#!/usr/bin/env bash
# 무중 PostgreSQL 초기화 1 — 역할 · DB · 스키마 · 권한 (U1 3.5 Step 9 · SD-13 · BR8.1 · NFR9.7 · J84 · J116)
#
# 실행 주체
#   - Compose: postgres 공식 이미지 entrypoint가 빈 데이터 디렉터리에서 /docker-entrypoint-initdb.d/의 이 파일을 한 번만 실행한다.
#   - 시험: test-support의 MujoongPostgres(docker · local 백엔드, J132)가 같은 환경 변수로 실행한다.
# 입력(환경 변수 이름만, 값은 출력하지 않는다)
#   - 필수: DB_OWNER_PASSWORD · DB_CORE_PASSWORD · DB_REALTIME_PASSWORD · DB_BACKUP_PASSWORD (SD-12 등록부)
#   - 선택: POSTGRES_USER(슈퍼유저 이름, 기본 postgres), PGHOST · PGPORT · PGPASSWORD(psql이 그대로 쓴다)
# 비밀번호는 SQL 파일에도 명령줄 인자에도 쓰지 않는다. 01-roles.sql이 psql \getenv로 환경 변수를 psql 변수에 담는다.
# entrypoint가 이 파일을 source해도 셸 설정이 새지 않도록 본문은 서브셸 함수 안에서 돈다.

mujoong_init_roles() (
  set -Eeuo pipefail

  local script_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

  local missing=()
  local name
  for name in DB_OWNER_PASSWORD DB_CORE_PASSWORD DB_REALTIME_PASSWORD DB_BACKUP_PASSWORD; do
    if [ -z "${!name:-}" ]; then
      missing+=("$name")
    fi
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "01-roles.sh: 필수 환경 변수가 비어 있다: ${missing[*]}" >&2
    exit 1
  fi

  # -X: psqlrc 무시, -q: 조용히(실행 문장을 출력하지 않음), ON_ERROR_STOP: 첫 오류에서 멈춤
  psql -X -q -v ON_ERROR_STOP=1 -v mujoong_init_runner=01-roles.sh \
    --username "${POSTGRES_USER:-postgres}" --dbname postgres \
    -f "$script_dir/01-roles.sql"

  echo "01-roles.sh: 역할 · 데이터베이스 · 스키마 · 권한 초기화를 마쳤다"
)

mujoong_init_roles
