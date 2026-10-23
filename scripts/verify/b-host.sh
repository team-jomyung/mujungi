#!/usr/bin/env bash
# 무중 검증 명령 B — host 모드 기동과 3종 호출 (U1 3.5 Step 49 · J133 · J132 · J135 · J108 · J141 · 사람 결정 11 · SEC-04 · SEC-08 · SEC-12)
#
# Docker를 쓸 수 없을 때 compose 모드(b-stack.sh)와 같은 순서를 호스트 프로세스로 돈다:
#   준비  gen-env(값 출처, umask 077) · gen-selfsigned · 포트 비어 있음 확인
#   B2    로컬 PostgreSQL 16 클러스터: runuser -u postgres(root일 때) initdb --auth=scram-sha-256 → pg_ctl start
#         (127.0.0.1 · 빈 포트 · 유닉스 소켓 없음, 설정은 infra/postgres/postgresql.conf.tmpl 렌더) → infra/postgres/init/01-roles.sh
#         → 02-hba.sh(PG_HBA_ALLOWED_CIDR=127.0.0.1/32, J135) → reload → migrate(scripts/verify/HostMigrate.java: migrate 이미지와
#         같은 infra/migrate/flyway-*.conf, mujoong_owner, core → realtime) → 시드 두 번 → 정책 1 · 문구 2
#   B5    프로필 가드: 같은 bootJar를 (i) demo + MUJOONG_DEVLOGIN_ENABLED=true (ii) demo,dev 로 띄워 60초 안에 비0 +
#         MUJOONG_STARTUP_REJECTED 줄(이미지 기동 거부는 NOT_VERIFIED(host), CI skeleton 몫)
#   B3    호스트 프로세스(dev 프로필 + MUJOONG_DEVLOGIN_ENABLED=true, J108): 가짜 공급자 127.0.0.1:8090 · realtime 8081(관리 9091) ·
#         core-api 8080(관리 9090) · web 3000(.next/standalone) · Nginx 대역 tools/dev-edge 127.0.0.1:8443 →
#         두 앱 readiness UP · web /healthz · 입구 /api/health
#   B4    입구 단언(lib.sh entry_assertions, compose 모드와 같은 단언)
# 앱 신뢰 프록시 주소는 127.0.0.1(SEC-11 키 SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES). 공급자 주소는 가짜 공급자(J141).
# 비밀은 env 파일에서 자식 프로세스 환경으로만 넘긴다(명령줄 인자 · 출력 금지). 세션의 프록시 · JAVA_TOOL_OPTIONS는 물려주지 않는다.
# 프로세스 · 클러스터 정리는 scripts/verify.sh(host_stop_all · host_pg_stop)가 한다(KEEP=1이면 남긴다).
#
# 쓰는 법: bash scripts/verify/b-host.sh [--engine]   (--engine은 기한만 다르고 같은 일을 한다)
# 종료 코드: 0 통과, 1 단언 실패, 2 전제 누락

set -Eeuo pipefail
# shellcheck source=scripts/verify/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$VERIFY_ROOT"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --engine) ;;
    *) echo "b-host: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done

JAVA="$(find_java21 || true)"
[ -n "$JAVA" ] || die_prereq "JDK 21이 없다"
for bin in initdb pg_ctl postgres psql; do
  [ -x "$VERIFY_PG_BIN/$bin" ] || die_prereq "PostgreSQL 16 바이너리가 없다: $VERIFY_PG_BIN/$bin"
done
for tool in jq openssl curl node unzip; do
  have "$tool" || die_prereq "$tool이 없다"
done
if [ "$(id -u)" -eq 0 ]; then
  have runuser || die_prereq "runuser가 없다(root에서 initdb · pg_ctl을 postgres 사용자로 돌린다)"
  id postgres >/dev/null 2>&1 || die_prereq "postgres 사용자가 없다"
fi
readonly CORE_JAR=backend/core-api/build/libs/core-api.jar
readonly RT_JAR=backend/realtime-activity/build/libs/realtime-activity.jar
readonly WEB_SERVER=frontend/.next/standalone/server.js
for f in "$CORE_JAR" "$RT_JAR" "$WEB_SERVER" tools/node_modules/ws/package.json; do
  [ -f "$f" ] || die_prereq "산출물이 없다: $f (검증 명령 A가 먼저 만든다)"
done

# port_free <포트> — 127.0.0.1에서 아무도 듣지 않는가
port_free() {
  ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}
busy=()
for p in "${VERIFY_HOST_PORTS[@]}"; do
  port_free "$p" || busy+=("$p")
done
[ "${#busy[@]}" -eq 0 ] || die_prereq "host 모드 포트가 이미 쓰이고 있다: ${busy[*]}"

# ---- 준비 ------------------------------------------------------------------------------------------------------------
log "준비: env · 인증서"
bash infra/ci/gen-env.sh --out "$VERIFY_ENV_DIR" --service-host "$VERIFY_SERVICE_HOST" --origin "$VERIFY_ORIGIN"
bash infra/ci/gen-selfsigned.sh --out "$VERIFY_CERT_DIR" --host "$VERIFY_SERVICE_HOST"
mkdir -p "$VERIFY_HOST_DIR"

# ---- B2 로컬 클러스터 -------------------------------------------------------------------------------------------------
pg_port="$(node -e 'const s=require("net").createServer().listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')"
readonly pg_port
pg_run="$(mktemp -d /tmp/mujoong-verify-pg.XXXXXX)"
echo "$pg_run" >"$VERIFY_HOST_DIR/pg.dir"
echo "$pg_port" >"$VERIFY_HOST_DIR/pg.port"
chmod 0700 "$pg_run"
superuser_password="$(env_value "$VERIFY_ENV_DIR/postgres.env" POSTGRES_PASSWORD)"
(umask 077 && printf '%s\n' "$superuser_password" >"$pg_run/pwfile")
bash infra/bin/render-pgconf --out "$pg_run/postgresql.conf" --listen 127.0.0.1 >/dev/null
if [ "$(id -u)" -eq 0 ]; then
  chown -R postgres:postgres "$pg_run"
fi
log "B2: initdb · pg_ctl start (127.0.0.1:$pg_port)"
if ! as_postgres "$VERIFY_PG_BIN/initdb" -D "$pg_run/data" -U postgres --auth=scram-sha-256 --pwfile="$pg_run/pwfile" \
  --encoding=UTF8 --locale=C --no-sync >"$VERIFY_LOG_DIR/b-initdb.log" 2>&1; then
  die_assert B2.cluster "initdb 실패(logs/b-initdb.log)"
fi
rm -f "$pg_run/pwfile"
if ! as_postgres "$VERIFY_PG_BIN/pg_ctl" -D "$pg_run/data" -l "$pg_run/server.log" -w -t 60 \
  -o "-c config_file=$pg_run/postgresql.conf -c port=$pg_port -c listen_addresses=127.0.0.1 -c unix_socket_directories=''" \
  start >"$VERIFY_LOG_DIR/b-pgctl.log" 2>&1; then
  die_assert B2.cluster "pg_ctl start 실패(logs/b-pgctl.log)"
fi
# 01-roles.sh: 같은 init 스크립트를 같은 환경 변수 이름으로(J132). 값은 env 파일에서 이 하위 셸 환경으로만
if ! (
  set -a
  # shellcheck disable=SC1091
  . "$VERIFY_ENV_DIR/postgres.env"
  set +a
  export PGHOST=127.0.0.1 PGPORT="$pg_port" PGPASSWORD="$POSTGRES_PASSWORD" POSTGRES_USER=postgres
  exec env PATH="$VERIFY_PG_BIN:$PATH" bash infra/postgres/init/01-roles.sh
) >"$VERIFY_LOG_DIR/b-roles.log" 2>&1; then
  die_assert B2.cluster "01-roles.sh 실패(logs/b-roles.log)"
fi
if ! as_postgres env PGDATA="$pg_run/data" PG_HBA_ALLOWED_CIDR=127.0.0.1/32 bash -s <infra/postgres/init/02-hba.sh \
  >"$VERIFY_LOG_DIR/b-hba.log" 2>&1; then
  die_assert B2.cluster "02-hba.sh 실패(logs/b-hba.log)"
fi
as_postgres "$VERIFY_PG_BIN/pg_ctl" -D "$pg_run/data" reload >>"$VERIFY_LOG_DIR/b-pgctl.log" 2>&1 ||
  die_assert B2.cluster "pg_ctl reload 실패"

# 앱 역할 접속용 0600 PGPASSFILE(사후 단언 P1 · C의 SQL 래퍼도 쓴다)
(
  umask 077
  {
    printf '127.0.0.1:%s:mujoong:mujoong_owner:%s\n' "$pg_port" "$(env_value "$VERIFY_ENV_DIR/postgres.env" DB_OWNER_PASSWORD)"
    printf '127.0.0.1:%s:mujoong:core_app:%s\n' "$pg_port" "$(env_value "$VERIFY_ENV_DIR/postgres.env" DB_CORE_PASSWORD)"
    printf '127.0.0.1:%s:mujoong:realtime_app:%s\n' "$pg_port" "$(env_value "$VERIFY_ENV_DIR/postgres.env" DB_REALTIME_PASSWORD)"
  } >"$VERIFY_HOST_DIR/pgpass"
)
export PGPASSFILE="$VERIFY_HOST_DIR/pgpass"
host_psql() {
  local user="$1"
  shift
  "$VERIFY_PG_BIN/psql" -X -q -h 127.0.0.1 -p "$pg_port" -U "$user" -d mujoong -v ON_ERROR_STOP=1 "$@"
}
if ! wait_until 10 "core_app 접속(pg_hba 127.0.0.1/32)" host_psql core_app -At -c 'SELECT 1'; then
  die_assert B2.cluster "pg_hba 적용 뒤 core_app으로 접속하지 못했다"
fi
pass B2.cluster "로컬 PostgreSQL 16(127.0.0.1:$pg_port) · init 역할 · pg_hba 127.0.0.1/32 scram"

log "B2: migrate(HostMigrate)"
flyway_lib="$VERIFY_HOST_DIR/flyway-lib"
rm -rf "$flyway_lib"
mkdir -p "$flyway_lib"
unzip -q -o -j "$CORE_JAR" 'BOOT-INF/lib/*.jar' -d "$flyway_lib"
if ! (
  set -a
  # shellcheck disable=SC1091
  . "$VERIFY_ENV_DIR/migrate.env"
  set +a
  export FLYWAY_URL="jdbc:postgresql://127.0.0.1:$pg_port/mujoong" FLYWAY_USER=mujoong_owner FLYWAY_PASSWORD="$DB_OWNER_PASSWORD"
  unset DB_OWNER_PASSWORD JAVA_TOOL_OPTIONS
  exec "$JAVA" -Duser.timezone=UTC -cp "$flyway_lib/*" scripts/verify/HostMigrate.java \
    infra/migrate/flyway-core.conf backend/core-api/src/main/resources/db/migration/core \
    infra/migrate/flyway-realtime.conf backend/realtime-activity/src/main/resources/db/migration/realtime
) >"$VERIFY_LOG_DIR/b-migrate.log" 2>&1; then
  die_assert B2.migrate "migrate 실패(logs/b-migrate.log)"
fi
pass B2.migrate "migrate(mujoong_owner, infra/migrate/flyway-*.conf, core → realtime)"

# U4b(FR11.4): 시드 순서 001 → 002 → 003. 003이 참여 확인 · 확인 질문 · 공백 안내 문구 6종의 승인 버전을 넣는다
# U8(BR1.1): 004가 음성 없는 승인 발화(APPROVED_SPEECH) 5종을 넣고 HELP_OFFER를 v2로 올린다
# 005: 새 승인 음성 판 8종(INTRO_FREE_TALK v3 등)을 넣고 이전 판을 RETIRED로 바꾼다. 자유대화 첫 안내는 이 판의 음성이
#   service-policy/notice-assets.json과 맞아야 재생된다(맞지 않으면 C8에서 안내가 오지 않는다). 매 회차 001 ~ 004 뒤에 둔다
for i in 1 2; do
  for seed in 001-reference.sql 002-u2-reference.sql 003-u4a-reference.sql 004-u8-reference.sql 005-spoken-audio-reference.sql; do
    host_psql mujoong_owner --single-transaction -f "infra/seed/$seed" >>"$VERIFY_LOG_DIR/b-seed.log" 2>&1 ||
      die_assert B2.seed "시드 $seed ${i}회차 실패(logs/b-seed.log)"
  done
done
counts="$(host_psql mujoong_owner -At -c "SELECT count(*) FILTER (WHERE c.content_code = 'CONSENT_POLICY'), count(*) FILTER (WHERE c.content_code IN ('CLOSING','WAITING_FILLER')) FROM core.core_approved_content c JOIN core.core_approved_content_version v ON v.content_id = c.id WHERE v.status = 'APPROVED'" || true)"
if [ "$counts" = "1|2" ]; then
  pass B2.seed "시드 001-reference.sql 두 번 적용(멱등) → 정책 1 · 승인 문구 2(R4-15)"
else
  die_assert B2.seed "시드 결과가 정책 1 · 문구 2가 아니다(${counts:-없음})"
fi
session_prompts="$(host_psql mujoong_owner -At -c "SELECT count(DISTINCT c.content_code) FROM core.core_approved_content c JOIN core.core_approved_content_version v ON v.content_id = c.id WHERE v.status = 'APPROVED' AND c.content_code IN ('INTRO_FREE_TALK','INTRO_PRACTICE','RE_PROMPT','DECLINE_ALTERNATIVE','SILENCE_WARNING','STOP_CONFIRM')" || true)"
if [ "$session_prompts" = "6" ]; then
  pass B2.seed003 "시드 002 · 003 두 번 적용(멱등) → 세션 승인 문구 6종 APPROVED(U4b 참여 확인 · 공백 안내)"
else
  die_assert B2.seed003 "세션 승인 문구 APPROVED가 6종이 아니다(${session_prompts:-없음})"
fi

# ---- 앱 환경(비밀 아닌 값) --------------------------------------------------------------------------------------------
readonly JVM_OPTS=(-Duser.timezone=UTC -Xms256m -Xmx768m -XX:+ExitOnOutOfMemoryError)
app_env_common=(
  "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:$pg_port/mujoong"
  'SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES=127\.0\.0\.1'
  "MUJOONG_INTERNALHTTP_COREBASEURL=http://127.0.0.1:8080"
  "MUJOONG_INTERNALHTTP_REALTIMEBASEURL=http://127.0.0.1:8081"
)

# ---- B5 프로필 가드(앱을 올리기 전) -----------------------------------------------------------------------------------
guard_run() {
  local app="$1" name="$2" jar envfile
  shift 2
  case "$app" in
    core-api) jar="$CORE_JAR" envfile="$VERIFY_ENV_DIR/core-api.env" ;;
    realtime) jar="$RT_JAR" envfile="$VERIFY_ENV_DIR/realtime.env" ;;
  esac
  local out="$VERIFY_LOG_DIR/b5-$app-$name.log" rc=0
  # shellcheck disable=SC2016  # 자식 bash가 펼친다
  timeout 60 env -i PATH="$PATH" HOME="${HOME:-/root}" LANG=C.UTF-8 TZ=UTC "${app_env_common[@]}" \
    MANAGEMENT_SERVER_PORT=0 SERVER_PORT=0 MUJOONG_PROVIDER_URL=ws://127.0.0.1:8090/v1/live/sessions "$@" \
    bash -c 'set -a; . "$1"; set +a; shift; exec "$@"' guard "$envfile" "$JAVA" "${JVM_OPTS[@]}" -jar "$jar" \
    >"$out" 2>&1 || rc=$?
  if [ "$rc" -eq 0 ] || [ "$rc" -eq 124 ]; then
    echo "FAIL B5.$app.$name: 60초 안에 기동을 거부하지 않았다(rc=$rc)" >&2
    return 1
  fi
  if ! grep -qF 'MUJOONG_STARTUP_REJECTED ' "$out"; then
    echo "FAIL B5.$app.$name: 기동 거부 줄 없이 끝났다(rc=$rc)" >&2
    return 1
  fi
}
guard_case() {
  local name="$1"
  shift
  local pids=() app i=0
  for app in core-api realtime; do
    guard_run "$app" "$name" "$@" &
    pids+=("$!")
  done
  for app in core-api realtime; do
    if wait "${pids[$i]}"; then
      pass "B5.$app.$name" "$app bootJar가 $name 구성에서 기동 거부(비0 · MUJOONG_STARTUP_REJECTED)"
    else
      fail "B5.$app.$name" "$app bootJar가 $name 구성에서 기동을 거부하지 않았다(logs/b5-$app-$name.log)"
    fi
    i=$((i + 1))
  done
}
log "B5: 프로필 가드"
guard_case demo-devlogin SPRING_PROFILES_ACTIVE=demo MUJOONG_DEVLOGIN_ENABLED=true
guard_case demo-dev SPRING_PROFILES_ACTIVE=demo,dev
finish_asserts

# ---- B3 호스트 프로세스 -----------------------------------------------------------------------------------------------
log "B3: 호스트 프로세스 기동"
VERIFY_SPAWN_ENV=()
host_spawn fake-provider "$VERIFY_LOG_DIR/host-fake-provider.jsonl" "" "tools/fake-provider/server.mjs" -- \
  node tools/fake-provider/server.mjs --host 127.0.0.1 --port 8090 --scenario ok --turns join,normal,forbidden
VERIFY_SPAWN_ENV=("${app_env_common[@]}" SPRING_PROFILES_ACTIVE=dev MANAGEMENT_SERVER_PORT=9091
  MUJOONG_PROVIDER_URL=ws://127.0.0.1:8090/v1/live/sessions)
host_spawn realtime "$VERIFY_LOG_DIR/host-realtime.log" "$VERIFY_ENV_DIR/realtime.env" "$RT_JAR" -- \
  "$JAVA" "${JVM_OPTS[@]}" -jar "$RT_JAR"
VERIFY_SPAWN_ENV=("${app_env_common[@]}" SPRING_PROFILES_ACTIVE=dev MANAGEMENT_SERVER_PORT=9090 MUJOONG_DEVLOGIN_ENABLED=true MUJOONG_KNOWLEDGE_NEO4J_ENABLED=false)
host_spawn core-api "$VERIFY_LOG_DIR/host-core.log" "$VERIFY_ENV_DIR/core-api.env" "$CORE_JAR" -- \
  "$JAVA" "${JVM_OPTS[@]}" -jar "$CORE_JAR"
VERIFY_SPAWN_ENV=(NODE_ENV=production PORT=3000 HOSTNAME=127.0.0.1 NEXT_TELEMETRY_DISABLED=1
  "SERVICE_HOST=$VERIFY_SERVICE_HOST" MUJOONG_DEVLOGIN_ENABLED=true)
host_spawn web "$VERIFY_LOG_DIR/host-web.log" "$VERIFY_ENV_DIR/web.env" "$WEB_SERVER" -- node "$WEB_SERVER"
VERIFY_SPAWN_ENV=("SERVICE_HOST=$VERIFY_SERVICE_HOST")
host_spawn edge "$VERIFY_LOG_DIR/host-edge.log" "" "tools/dev-edge/edge.mjs" -- \
  node tools/dev-edge/edge.mjs --cert "$VERIFY_CERT_DIR/live/$VERIFY_SERVICE_HOST/fullchain.pem" \
  --key "$VERIFY_CERT_DIR/live/$VERIFY_SERVICE_HOST/privkey.pem" --listen 127.0.0.1:8443 --service-host "$VERIFY_SERVICE_HOST"
VERIFY_SPAWN_ENV=()

ready_all() {
  curl -fsS --noproxy '*' --max-time 2 -o /dev/null http://127.0.0.1:9090/actuator/health/readiness &&
    curl -fsS --noproxy '*' --max-time 2 -o /dev/null http://127.0.0.1:9091/actuator/health/readiness &&
    curl -fsS --noproxy '*' --max-time 2 -o /dev/null http://127.0.0.1:3000/healthz &&
    curl -fsS --noproxy '*' --max-time 2 -o /dev/null http://127.0.0.1:8090/healthz &&
    vcurl -f -o /dev/null --max-time 2 "$VERIFY_BASE_URL/api/health"
}
if ! wait_until "$(bounded 120)" "두 앱 readiness · web · 가짜 공급자 · 입구" ready_all; then
  for name in fake-provider realtime core-api web edge; do
    read -r pid _ <"$VERIFY_PID_DIR/$name.pid" || true
    kill -0 "${pid:-0}" 2>/dev/null || echo "FAIL B3.$name: 프로세스가 끝났다(logs/host-*.log)" >&2
  done
  die_assert B3.up "호스트 스택이 기한 안에 준비되지 않았다"
fi
pass B3.up "가짜 공급자 · realtime · core-api readiness UP · web /healthz · 입구(dev-edge) /api/health"

# ---- B4 입구 단언 -----------------------------------------------------------------------------------------------------
entry_assertions
finish_asserts
