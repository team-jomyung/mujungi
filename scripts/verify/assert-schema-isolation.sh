#!/usr/bin/env bash
# 무중 사후 단언 B1 ② — 교차 스키마 쓰기 · 앱 역할 DDL 거부 (U1 3.5 Step 49 · SD-13 · NFR9.7 · SEC-04 · J135)
#
# 실행 중인 검증 스택의 DB에서 앱 역할로 접속해 아래 문장이 모두 SQLSTATE 정확히 42501(insufficient_privilege)인지 본다.
#   core_app     : INSERT INTO realtime.realtime_transcript · CREATE TABLE core.* · CREATE TABLE public.*
#   realtime_app : INSERT INTO core.core_device · CREATE TABLE realtime.* · CREATE TABLE public.*
# 문장마다 DO 블록 안 EXECUTE를 EXCEPTION으로 받아 RETURNED_SQLSTATE를 NOTICE로 낸다(성공하면 NO_ERROR → 실패). 블록은
# BEGIN … ROLLBACK 안에서 돌아 무엇도 남기지 않는다. 먼저 SELECT current_user로 그 역할로 접속했는지 확인한다.
# 접속 실패(28P01 · 28000 · 08001 등)는 단언 실패(종료 코드 1)로 센다.
# 접속 방식(비밀번호를 명령줄 인자나 -e로 넘기지 않는다):
#   compose  backend 망에 붙인 일회성 postgres 고정 태그 컨테이너 + --env-file build/verify/env/<앱>.env
#            (컨테이너 안에서 SPRING_DATASOURCE_PASSWORD를 PGPASSWORD로)
#   host     0600 PGPASSFILE(build/verify/host/pgpass)로 127.0.0.1:<로컬 클러스터 포트>
#
# 쓰는 법: bash scripts/verify/assert-schema-isolation.sh --mode compose|host
# 종료 코드: 0 통과, 1 단언 실패, 2 전제 누락

set -Eeuo pipefail
# shellcheck source=scripts/verify/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$VERIFY_ROOT"

mode=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode) mode="${2:-}"; shift ;;
    *) echo "assert-schema-isolation: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done
case "$mode" in compose | host) ;; *) echo "assert-schema-isolation: --mode compose|host" >&2; exit 64 ;; esac

if [ "$mode" = compose ]; then
  docker_usable || die_prereq "Docker 데몬에 닿지 못한다"
  for f in core-api.env realtime.env; do
    [ -f "$VERIFY_ENV_DIR/$f" ] || die_prereq "env 파일이 없다: build/verify/env/$f"
  done
else
  [ -f "$VERIFY_HOST_DIR/pgpass" ] && [ -f "$VERIFY_HOST_DIR/pg.port" ] || die_prereq "로컬 클러스터 접속 정보가 없다(B가 먼저 띄운다)"
fi

# probe_sql <라벨=문장>... — DO 블록 묶음(문장은 상수, 값 없음)
probe_sql() {
  echo "SELECT 'MUJOONG_CURRENT_USER ' || current_user;"
  echo "BEGIN;"
  local item label stmt
  for item in "$@"; do
    label="${item%%=*}"
    stmt="${item#*=}"
    cat <<SQL
DO \$mj\$
DECLARE state text := 'NO_ERROR';
BEGIN
  BEGIN
    EXECUTE \$q\$$stmt\$q\$;
  EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS state = RETURNED_SQLSTATE;
  END;
  RAISE NOTICE 'MUJOONG_SQLSTATE $label %', state;
END
\$mj\$;
SQL
  done
  echo "ROLLBACK;"
}

# run_as <core|realtime> — 표준 입력 SQL을 그 앱 역할로 실행(출력에 NOTICE 포함)
run_as() {
  local app="$1" user envfile
  case "$app" in
    core) user=core_app envfile=core-api.env ;;
    realtime) user=realtime_app envfile=realtime.env ;;
  esac
  if [ "$mode" = compose ]; then
    docker run --rm -i --network "${VERIFY_PROJECT}_backend" --cap-drop ALL --security-opt no-new-privileges:true \
      --env-file "$VERIFY_ENV_DIR/$envfile" --entrypoint sh "$(pg_image)" -c \
      'PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" exec psql -X -h postgres -U "$1" -d mujoong -At -v ON_ERROR_STOP=1 -f -' \
      probe "$user" 2>&1
  else
    PGPASSFILE="$VERIFY_HOST_DIR/pgpass" "$VERIFY_PG_BIN/psql" -X -h 127.0.0.1 -p "$(cat "$VERIFY_HOST_DIR/pg.port")" \
      -U "$user" -d mujoong -At -v ON_ERROR_STOP=1 -f - 2>&1
  fi
}

# check_role <앱> <역할> <라벨=문장>...
check_role() {
  local app="$1" role="$2"
  shift 2
  local out rc=0
  out="$(probe_sql "$@" | run_as "$app")" || rc=$?
  if [ "$rc" -ne 0 ] || ! grep -qx "MUJOONG_CURRENT_USER $role" <<<"$out"; then
    # psql 오류 줄(비밀번호는 들어가지 않는다)에서 SQLSTATE · 원인만 짧게
    fail "P1.$role.connect" "$role 접속 · 실행 실패(rc=$rc): $(grep -m1 -E 'FATAL|ERROR|error' <<<"$out" | cut -c1-160)"
    return
  fi
  local item label state
  for item in "$@"; do
    label="${item%%=*}"
    state="$(grep -oE "MUJOONG_SQLSTATE $label [0-9A-Z_]+" <<<"$out" | awk '{print $3}' | head -n 1)"
    if [ "$state" = 42501 ]; then
      pass "P1.$role.$label" "$role: ${item#*=} → SQLSTATE 42501"
    else
      fail "P1.$role.$label" "$role: ${item#*=} → SQLSTATE ${state:-없음}(기대 42501)"
    fi
  done
}

check_role core core_app \
  "cross_insert=INSERT INTO realtime.realtime_transcript DEFAULT VALUES" \
  "create_own=CREATE TABLE core.mujoong_verify_probe (id int)" \
  "create_public=CREATE TABLE public.mujoong_verify_probe (id int)"
check_role realtime realtime_app \
  "cross_insert=INSERT INTO core.core_device DEFAULT VALUES" \
  "create_own=CREATE TABLE realtime.mujoong_verify_probe (id int)" \
  "create_public=CREATE TABLE public.mujoong_verify_probe (id int)"
finish_asserts
