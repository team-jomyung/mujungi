#!/usr/bin/env bash
# 호스트 도구 시험 — 가짜 aws로 render-env · D3 프로필 확인, 점검 도우미 (U1 3.5 Step 48 · cicd 2.5.1 · 4절 CD-7 · CD-11 · monitoring 4절 MO-3 · MO-4)
#
# 실제 AWS를 부르지 않는다. 임시 디렉터리의 가짜 `aws`(Parameter Store 응답 흉내)를 PATH 앞에 두고, 값은 실행 때 무작위로 만든다(J127).
#   render-env(infra/bin/render-env)
#     - 경로 조회 · 이름 조회 둘 다 서비스별 파일을 분배표(infra-spec 3.7)대로 만든다: 파일 0600 · 디렉터리 0700, 받지 않는 비밀 0건,
#       데이터 소스 비밀번호 매핑, U2 전 GOOGLE_OAUTH_CLIENT_SECRET 파라미터가 없으면 빈 변수(CD-11), 출력에 값이 없다
#     - 필수 파라미터 없음 · localhost · .invalid 호스트 · 짧은 내부 토큰 · 렌더에 위험한 문자 · origin 불일치는 종료 코드 1이고
#       이미 있는 파일을 바꾸지 않는다(D3 실패 · 구 버전 유지). aws 호출 실패 · aws 없음은 종료 코드 2
#   D3 프로필 확인(infra/bin/infra-validate --only demo-profile, CD-7): 렌더된 env로 통과, env 파일의 시험 로그인 켬 · demo가 아닌
#     프로필 줄은 실패
#   점검 도우미(Step 47): check-since-last(MO-4 합성 로그) · log-hygiene-check(MO-3 금지 패턴 · 비밀 값 0건)
#     · 사건 코드별 attrs 허용 표가 자바 정본(EventCode · AttrKey)과 같은지(python3)
#
# 쓰는 법: bash infra/ci/test-host-tools.sh
# 표준 출력: 사례마다 `ok <이름>` 또는 `not ok <이름>: <이유>`. 종료 코드: 0 모두 기대대로, 1 하나라도 어긋남, 2 전제 누락(jq · base64)

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT
readonly RENDER_ENV="$ROOT/infra/bin/render-env"
readonly VALIDATE="$ROOT/infra/bin/infra-validate"

for tool in jq base64 sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "test-host-tools: 전제 누락: $tool" >&2
    exit 2
  fi
done

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
total=0
ok() {
  total=$((total + 1))
  echo "ok $1"
}
not_ok() {
  total=$((total + 1))
  failures=$((failures + 1))
  echo "not ok $1: $2"
}

rand() { head -c "$1" /dev/urandom | base64 -w0 | tr '+/' '-_' | tr -d '='; }

# ---- 가짜 aws ------------------------------------------------------------------------------------------
# FAKE_AWS_PARAMS: "이름<TAB>값" 줄 파일(경로 접두 없이). FAKE_AWS_FAIL=1이면 호출 실패.
mkdir -p "$work/bin"
cat >"$work/bin/aws" <<'FAKE'
#!/usr/bin/env bash
# 시험용 가짜 aws: ssm get-parameters-by-path · get-parameters만 흉내 낸다
set -Eeuo pipefail
[ "${FAKE_AWS_FAIL:-0}" = 1 ] && { echo "fake aws: AccessDenied" >&2; exit 255; }
[ "${1:-}" = ssm ] || exit 254
op="${2:-}"
shift 2
path=/mujoong/demo/
names=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --path) path="$2"; shift ;;
    --names) shift; while [ "$#" -gt 0 ] && [[ "$1" != --* ]]; do names+=("$1"); shift; done; continue ;;
    *) ;;
  esac
  shift
done
echo "$op ${#names[@]}" >>"${FAKE_AWS_CALLS:-/dev/null}"
jq -R -s --arg path "$path" --arg op "$op" --args '
  [ split("\n")[] | select(length > 0) | split("\t") | {Name: ($path + .[0]), Value: .[1], Type: "SecureString"} ] as $all
  | if $op == "get-parameters" then { Parameters: [ $all[] | select(.Name as $n | $ARGS.positional | index($n)) ] }
    else { Parameters: $all } end' "${names[@]}" <"$FAKE_AWS_PARAMS"
FAKE
chmod 0755 "$work/bin/aws"
export FAKE_AWS_CALLS="$work/aws-calls"

# 파라미터 집합(값은 실행 때 무작위, 출력하지 않는다). 호스트는 문서용 예약 도메인(RFC 2606)이고 실제 도메인이 아니다(J98)
declare -A P=(
  [POSTGRES_PASSWORD]="$(rand 24)" [DB_OWNER_PASSWORD]="$(rand 24)" [DB_CORE_PASSWORD]="$(rand 24)"
  [DB_REALTIME_PASSWORD]="$(rand 24)" [DB_BACKUP_PASSWORD]="$(rand 24)" [INTERNAL_SERVICE_TOKEN]="$(rand 32)"
  [PROVIDER_API_KEY]="$(rand 32)" [MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET]="$(rand 32)"
  [SERVICE_HOST]="svc.example.net" [MUJOONG_SECURITY_ALLOWED_ORIGIN]="https://svc.example.net"
  [ACME_CONTACT_EMAIL]="ops@example.net"
)
readonly SECRET_KEYS=(POSTGRES_PASSWORD DB_OWNER_PASSWORD DB_CORE_PASSWORD DB_REALTIME_PASSWORD DB_BACKUP_PASSWORD
  INTERNAL_SERVICE_TOKEN PROVIDER_API_KEY MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET)
# write_params <파일> [이름=값 덮어쓰기 · -이름 빼기]...
write_params() {
  local file="$1"
  shift
  declare -A q=()
  local k
  for k in "${!P[@]}"; do q[$k]="${P[$k]}"; done
  local arg
  for arg in "$@"; do
    if [[ "$arg" == -* ]]; then
      unset "q[${arg#-}]"
    else
      q[${arg%%=*}]="${arg#*=}"
    fi
  done
  : >"$file"
  for k in "${!q[@]}"; do printf '%s\t%s\n' "$k" "${q[$k]}" >>"$file"; done
}
# 출력에 생성 값이 없는지(값 비출력 규칙)
no_values_in() {
  local k
  for k in "${SECRET_KEYS[@]}"; do
    if grep -qF -- "${P[$k]}" "$1"; then
      return 1
    fi
  done
}
render() {
  # render <파라미터 파일> <출력 디렉터리> [render-env 인자...] → 종료 코드는 $rc
  local params="$1" out="$2"
  shift 2
  rc=0
  FAKE_AWS_PARAMS="$params" PATH="$work/bin:$PATH" bash "$RENDER_ENV" --out "$out" "$@" >"$work/render.out" 2>&1 || rc=$?
}
tree_sum() { (cd "$1" && find . -type f -print0 | sort -z | xargs -0 sha256sum) | sha256sum | cut -d' ' -f1; }

# ---- render-env: 정상 ---------------------------------------------------------------------------------
declare -A WANT=(
  [postgres.env]="DB_BACKUP_PASSWORD DB_CORE_PASSWORD DB_OWNER_PASSWORD DB_REALTIME_PASSWORD POSTGRES_PASSWORD"
  [migrate.env]="DB_OWNER_PASSWORD"
  [core-api.env]="GOOGLE_OAUTH_CLIENT_SECRET INTERNAL_SERVICE_TOKEN MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET MUJOONG_SECURITY_ALLOWED_ORIGIN SPRING_DATASOURCE_PASSWORD"
  [realtime.env]="INTERNAL_SERVICE_TOKEN MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET MUJOONG_SECURITY_ALLOWED_ORIGIN PROVIDER_API_KEY SPRING_DATASOURCE_PASSWORD"
  [web.env]="MUJOONG_SECURITY_ALLOWED_ORIGIN"
  [nginx.env]="MUJOONG_SECURITY_ALLOWED_ORIGIN SERVICE_HOST"
  [certbot.env]="ACME_CONTACT_EMAIL SERVICE_HOST"
  [backup.env]="BACKUP_RETENTION_DAYS DB_BACKUP_PASSWORD"
)
value_of() { grep -E "^$2=" "$1" | head -n 1 | cut -d= -f2-; }
check_rendered() {
  local name="$1" dir="$2" problems=() f got
  [ "$(stat -c %a "$dir")" = 700 ] || problems+=("디렉터리 권한 $(stat -c %a "$dir")")
  for f in "${!WANT[@]}"; do
    if [ ! -f "$dir/$f" ]; then
      problems+=("$f 없음")
      continue
    fi
    [ "$(stat -c %a "$dir/$f")" = 600 ] || problems+=("$f 권한 $(stat -c %a "$dir/$f")")
    got="$(cut -d= -f1 "$dir/$f" | sort | paste -sd' ' -)"
    [ "$got" = "${WANT[$f]}" ] || problems+=("$f 변수 [$got] ≠ [${WANT[$f]}]")
  done
  [ -e "$dir/neo4j.env" ] && problems+=("NEO4J_AUTH 파라미터가 없는데 neo4j.env가 생겼다")
  [ "$(value_of "$dir/core-api.env" GOOGLE_OAUTH_CLIENT_SECRET)" = "" ] || problems+=("GOOGLE_OAUTH_CLIENT_SECRET이 빈 변수가 아니다")
  [ "$(value_of "$dir/core-api.env" SPRING_DATASOURCE_PASSWORD)" = "${P[DB_CORE_PASSWORD]}" ] || problems+=("core-api 데이터 소스 비밀번호 매핑")
  [ "$(value_of "$dir/realtime.env" SPRING_DATASOURCE_PASSWORD)" = "${P[DB_REALTIME_PASSWORD]}" ] || problems+=("realtime 데이터 소스 비밀번호 매핑")
  [ "$(value_of "$dir/backup.env" BACKUP_RETENTION_DAYS)" = 7 ] || problems+=("보관 일수 7 아님")
  no_values_in "$work/render.out" || problems+=("출력에 비밀 값이 있다")
  if [ "${#problems[@]}" -gt 0 ]; then not_ok "$name" "${problems[*]}"; else ok "$name"; fi
}

write_params "$work/p-ok" -GOOGLE_OAUTH_CLIENT_SECRET
render "$work/p-ok" "$work/env-path"
if [ "$rc" -ne 0 ]; then not_ok "render-env-by-path" "종료 코드 $rc"; else check_rendered "render-env-by-path(OAuth 빈 변수 CD-11)" "$work/env-path"; fi

: >"$FAKE_AWS_CALLS"
render "$work/p-ok" "$work/env-names" --by-names
if [ "$rc" -ne 0 ]; then
  not_ok "render-env-by-names" "종료 코드 $rc"
elif ! grep -q '^get-parameters ' "$FAKE_AWS_CALLS" || grep -q '^get-parameters-by-path' "$FAKE_AWS_CALLS" \
  || awk '$1 == "get-parameters" && ($2 > 10 || $2 < 1) { bad = 1 } END { exit bad ? 0 : 1 }' "$FAKE_AWS_CALLS"; then
  not_ok "render-env-by-names" "이름 조회(get-parameters, 한 번에 10개 이하)로만 부르지 않았다"
else
  check_rendered "render-env-by-names" "$work/env-names"
fi

# ---- render-env: 실패하면 아무 파일도 바꾸지 않는다(D3 실패 · 구 버전 유지) --------------------------------
expect_reject() {
  local name="$1" want_rc="$2" params="$3"
  shift 3
  local before after
  before="$(tree_sum "$work/env-path")"
  render "$params" "$work/env-path" "$@"
  after="$(tree_sum "$work/env-path")"
  if [ "$rc" -ne "$want_rc" ]; then
    not_ok "$name" "종료 코드 $rc(기대 $want_rc) — $(head -n 1 "$work/render.out" | cut -c1-160)"
  elif [ "$before" != "$after" ]; then
    not_ok "$name" "실패했는데 env 파일이 바뀌었다"
  elif ! no_values_in "$work/render.out"; then
    not_ok "$name" "출력에 비밀 값이 있다"
  else
    ok "$name"
  fi
}
write_params "$work/p-missing" -PROVIDER_API_KEY
expect_reject "render-env-missing-required" 1 "$work/p-missing"
write_params "$work/p-localhost" SERVICE_HOST=localhost MUJOONG_SECURITY_ALLOWED_ORIGIN=https://localhost
expect_reject "render-env-rejects-localhost" 1 "$work/p-localhost"
write_params "$work/p-invalid" SERVICE_HOST=svc.mujoong.invalid MUJOONG_SECURITY_ALLOWED_ORIGIN=https://svc.mujoong.invalid
expect_reject "render-env-rejects-invalid-tld" 1 "$work/p-invalid"
write_params "$work/p-short" "INTERNAL_SERVICE_TOKEN=$(rand 15)"
expect_reject "render-env-short-internal-token" 1 "$work/p-short"
# shellcheck disable=SC2016  # 렌더에 위험한 글자($ · 공백)를 그대로 넣는다
write_params "$work/p-unsafe" 'PROVIDER_API_KEY=abc def$HOME'
expect_reject "render-env-unsafe-characters" 1 "$work/p-unsafe"
write_params "$work/p-origin" MUJOONG_SECURITY_ALLOWED_ORIGIN=https://other.example.net
expect_reject "render-env-origin-mismatch" 1 "$work/p-origin"
FAKE_AWS_FAIL=1 expect_reject "render-env-aws-failure" 2 "$work/p-ok"
# aws가 없는 PATH: render-env가 쓰는 도구만 링크한 디렉터리
mkdir -p "$work/noaws"
for t in bash mktemp jq base64 cat chmod mv mkdir rm basename cut tr sed wc env head; do
  ln -sf "$(command -v "$t")" "$work/noaws/$t"
done
before="$(tree_sum "$work/env-path")"
rc=0
PATH="$work/noaws" bash "$RENDER_ENV" --out "$work/env-path" >"$work/render.out" 2>&1 || rc=$?
if [ "$rc" -ne 2 ] || [ "$before" != "$(tree_sum "$work/env-path")" ]; then
  not_ok "render-env-aws-missing" "종료 코드 $rc(기대 2) 또는 파일 변경"
else
  ok "render-env-aws-missing"
fi
render "$work/p-invalid" "$work/env-rehearsal" --allow-local-host
if [ "$rc" -eq 0 ] && [ -f "$work/env-rehearsal/nginx.env" ]; then ok "render-env-allow-local-host(리허설)"; else not_ok "render-env-allow-local-host" "종료 코드 $rc"; fi

# ---- D3 프로필 확인(CD-7) -----------------------------------------------------------------------------
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  printf 'MUJOONG_REGISTRY=ghcr.io/example\nMUJOONG_TAG=0123456789abcdef0123456789abcdef01234567\n' >"$work/release.env"
  d3() {
    rc=0
    bash "$VALIDATE" --only demo-profile,secret-env-files,compose-invariants --env-dir "$1" --release-env "$work/release.env" \
      >"$work/d3.out" 2>&1 || rc=$?
  }
  d3 "$work/env-path"
  if [ "$rc" -eq 0 ]; then ok "d3-rendered-env-demo"; else not_ok "d3-rendered-env-demo" "$(grep -m1 '^FAIL' "$work/d3.out" | cut -c1-200)"; fi
  cp -a "$work/env-path" "$work/env-devlogin"
  printf 'MUJOONG_DEVLOGIN_ENABLED=true\n' >>"$work/env-devlogin/core-api.env"
  d3 "$work/env-devlogin"
  if [ "$rc" -eq 1 ] && grep -qF 'MUJOONG_DEVLOGIN_ENABLED가 켜져 있다' "$work/d3.out"; then ok "d3-rejects-devlogin"; else not_ok "d3-rejects-devlogin" "종료 코드 $rc"; fi
  cp -a "$work/env-path" "$work/env-profile"
  printf 'SPRING_PROFILES_ACTIVE=demo,dev\n' >>"$work/env-profile/realtime.env"
  d3 "$work/env-profile"
  if [ "$rc" -eq 1 ] && grep -qF 'realtime env 파일에 demo가 아닌 SPRING_PROFILES_ACTIVE' "$work/d3.out"; then ok "d3-rejects-demo-dev"; else not_ok "d3-rejects-demo-dev" "종료 코드 $rc"; fi
  if no_values_in "$work/d3.out"; then ok "d3-output-has-no-values"; else not_ok "d3-output-has-no-values" "출력에 비밀 값"; fi
else
  not_ok "d3-profile-check" "docker compose가 없어 확인하지 못했다(전제 누락)"
fi

# ---- 점검 도우미(Step 47): 합성 로그로 MO-3 · MO-4 ----------------------------------------------------------
# 호스트 경로는 임시 뿌리로 바꾸고(MUJOONG_LOG_DIR · MUJOONG_DATA_DIR · MUJOONG_ENV_DIR), 컨테이너 로그는 --container-logs 합성 디렉터리를 쓴다.
# 시각은 지금 기준 상대값이고, 비밀 값 · 전사 같은 문장은 실행 때 만든다(J127).
readonly CSL="$ROOT/infra/bin/check-since-last"
readonly LHC="$ROOT/infra/bin/log-hygiene-check"
dts() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%S.%NZ; }    # docker logs --timestamps 꼴
its() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%S.000Z; }   # 앱 로그 ts 꼴
hts() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }       # 호스트 사건 파일 ts 꼴
now_s="$(date -u +%s)"
since_iso="$(hts $((now_s - 7200)))"
app_line() { # <epoch> <level> <event> <attrs JSON>
  printf '%s {"ts":"%s","level":"%s","traceId":"%032x","component":"test","event":"%s","ownerHash":null,"attrs":%s}\n' \
    "$(dts "$1")" "$(its "$1")" "$2" "$1" "$3" "$4"
}
hc_line() { # <epoch> <나머지 키=값>
  printf 'ts=%s %s\n' "$(hts "$1")" "$2"
}
readonly HC_OK='ctr=7/7 ctrBad=none readyCore=200 readyRealtime=200 restarts=core-api:0 diskRoot=40 diskData=10 diskBackup=5 certDays=60 certServedDays=60 backupAgeH=3 cpuPct=5 memPct=40 clockSync=OK clockOffsetMs=1 warn=none'
make_root() { # <뿌리> — 깨끗한 호스트 파일 · 컨테이너 로그(알림 0)
  local r="$1"
  mkdir -p "$r/log" "$r/data" "$r/env" "$r/c/archive"
  hc_line $((now_s - 3600)) "$HC_OK" >"$r/log/host-collect.log"
  hc_line $((now_s - 60)) "$HC_OK" >>"$r/log/host-collect.log"
  app_line $((now_s - 3000)) INFO STARTUP_RECOVERY '{"recoveredSessions":0,"reopenedInboxRows":0,"resetConnections":0,"rearmedControlEvents":0}' >"$r/c/core-api.log"
  app_line $((now_s - 2900)) INFO METRIC_SNAPSHOT '{"outboxOldestPendingSec":3,"outboxFailed":0,"inboxDead":0,"poolWaitMaxMs":5,"sysCpuAvgPct":10,"sysMemMaxPct":30,"poolActive":1}' >>"$r/c/core-api.log"
  app_line $((now_s - 2900)) INFO METRIC_SNAPSHOT '{"outboxOldestPendingSec":0,"outboxFailed":0,"inboxDead":0,"poolWaitMaxMs":0,"sysCpuAvgPct":12,"sysMemMaxPct":31,"poolActive":0}' >"$r/c/realtime.log"
  printf '%s ts=%s requestId=%032x method=GET uri=/device status=200 bytes=10 rt=0.001 urt=0.001 upstream=172.30.10.11:3000 client=203.0.113.9\n' \
    "$(dts $((now_s - 2000)))" "$(hts $((now_s - 2000)))" 1 >"$r/c/nginx.log"
  printf '%s 2026 UTC [1] LOG:  database system is ready to accept connections\n' "$(dts $((now_s - 3500)))" >"$r/c/postgres.log"
  : >"$r/c/web.log"
  printf 'SPRING_DATASOURCE_PASSWORD=%s\nINTERNAL_SERVICE_TOKEN=%s\nMUJOONG_SECURITY_ALLOWED_ORIGIN=https://svc.example.net\n' \
    "$(rand 24)" "$(rand 32)" >"$r/env/core-api.env"
  chmod 0600 "$r/env/core-api.env"
}
run_tool() { # <뿌리> <도구> [인자...] → $rc · $work/tool.out
  local r="$1" tool="$2"
  shift 2
  rc=0
  MUJOONG_LOG_DIR="$r/log" MUJOONG_DATA_DIR="$r/data" MUJOONG_ENV_DIR="$r/env" \
    bash "$tool" --container-logs "$r/c" "$@" >"$work/tool.out" 2>&1 || rc=$?
}
env_value() { grep -E "^$2=" "$1" | cut -d= -f2-; }

# MO-4 음성: 깨끗한 입력은 알림 0 · 종료 코드 0
make_root "$work/h-clean"
run_tool "$work/h-clean" "$CSL" --since "$since_iso"
if [ "$rc" -eq 0 ] && grep -qx 'SUMMARY alerts=0 ' "$work/tool.out"; then
  ok "check-since-last-clean(알림 0)"
else
  not_ok "check-since-last-clean" "종료 코드 $rc · $(grep -m1 -E 'ALERT|SUMMARY' "$work/tool.out" | cut -c1-200)"
fi

# MO-4 양성: 네 기준(WARN 5분 창 · PENDING 나이 · FAILED · 풀 대기 5연속)을 넘긴 값, 재시작을 끼운 값(보존 폴더의 재시작 전 최댓값),
# 보존 폴더에 나뉜 값, since 이전 값(무시돼야 함), 호스트 수집 경고(A-4 · A-5 · A-15 · A-16 · A-17)
r="$work/h-alert"
make_root "$r"
{
  # 재시작 전(보존 폴더): PENDING 75초 · 풀 대기 150ms가 연속 5개, 그리고 since 이전의 999초(무시)
  app_line $((now_s - 9000)) INFO METRIC_SNAPSHOT '{"outboxOldestPendingSec":999,"outboxFailed":0,"inboxDead":0,"poolWaitMaxMs":0,"sysCpuAvgPct":1,"sysMemMaxPct":1,"poolActive":0}'
  for i in 0 1 2 3 4; do
    app_line $((now_s - 6000 + i * 60)) INFO METRIC_SNAPSHOT \
      '{"outboxOldestPendingSec":75,"outboxFailed":0,"inboxDead":0,"poolWaitMaxMs":150,"sysCpuAvgPct":10,"sysMemMaxPct":30,"poolActive":1}'
  done
} | gzip >"$r/c/archive/$(date -u -d "@$((now_s - 5600))" +%Y%m%dT%H%M%SZ)-core-api-test.log.gz"
{
  # 재시작 뒤(현재 로그): 게이지가 0으로 돌아간 값, 3분 안의 WARN 12줄(그중 하나는 repeat 3), 이벤트 최종 실패 1건
  app_line $((now_s - 1800)) INFO METRIC_SNAPSHOT '{"outboxOldestPendingSec":0,"outboxFailed":0,"inboxDead":0,"poolWaitMaxMs":0,"sysCpuAvgPct":10,"sysMemMaxPct":30,"poolActive":0}'
  for i in $(seq 0 10); do
    app_line $((now_s - 1200 + i * 15)) WARN TIMEOUT_FIRED '{"type":"DEVICE_ACK","thresholdMs":20000,"elapsedMs":20001}'
  done
  app_line $((now_s - 1030)) WARN SECURITY_AUTH_FAILED '{"kind":"DEVICE_TOKEN","statusCode":401,"repeat":3}'
  app_line $((now_s - 900)) WARN EVENT_DELIVERY "{\"eventId\":\"$(cat /proc/sys/kernel/random/uuid)\",\"eventType\":\"DeviceStatusChanged\",\"status\":\"FAILED\",\"retryCount\":20,\"durationMs\":5}"
} >>"$r/c/core-api.log"
for i in 0 1 2 3 4; do
  app_line $((now_s - 2400 + i * 60)) INFO METRIC_SNAPSHOT \
    '{"outboxOldestPendingSec":0,"outboxFailed":0,"inboxDead":0,"poolWaitMaxMs":0,"sysCpuAvgPct":55,"sysMemMaxPct":30,"poolActive":0}'
done >>"$r/c/realtime.log"
{
  hc_line $((now_s - 9000)) 'ctr=1/7 ctrBad=nginx readyCore=ERR readyRealtime=ERR warn=ctr'
  hc_line $((now_s - 1800)) "${HC_OK/ctr=7\/7 ctrBad=none readyCore=200/ctr=6/7 ctrBad=web readyCore=503}"
  hc_line $((now_s - 1740)) "${HC_OK/readyCore=200/readyCore=503}"
  hc_line $((now_s - 60)) "$(sed -e 's/diskRoot=40/diskRoot=85/' -e 's/certDays=60/certDays=10/' -e 's/backupAgeH=3/backupAgeH=30/' <<<"$HC_OK")"
} >"$r/log/host-collect.log"
run_tool "$r" "$CSL" --since "$since_iso" --no-hygiene
summary="$(grep '^SUMMARY' "$work/tool.out" || true)"
missing=()
for a in A-4 A-5 A-7 A-9 A-10 A-11 A-14 A-15 A-16 A-17; do
  [[ ",${summary#* alerts=* }," == *",$a,"* ]] || missing+=("$a")
done
if [ "$rc" -ne 1 ] || [ "${#missing[@]}" -gt 0 ]; then
  not_ok "check-since-last-alerts(MO-4)" "종료 코드 $rc, 빠진 알림 ${missing[*]:-없음} — $summary"
elif ! grep -qE '^\[4\] PENDING 최장 나이 최댓값=75초' "$work/tool.out"; then
  not_ok "check-since-last-max-across-restart" "재시작 전 보존 폴더의 최댓값(75)이 아니다(since 이전 999를 셌거나 보존 폴더를 읽지 않음)"
elif ! grep -qE '^\[3\] WARN 이상 5분 창 최대=(1[4-9]|[2-9][0-9])건' "$work/tool.out"; then
  not_ok "check-since-last-warn-window" "$(grep -m1 '^\[3\]' "$work/tool.out")"
else
  ok "check-since-last-alerts(MO-4: 네 기준 · 재시작 사이 최댓값 · 보존 폴더 · since 이전 무시)"
fi
# --record는 last-check를 쓰고, 다음 실행의 기본 since가 된다
run_tool "$r" "$CSL" --since "$since_iso" --no-hygiene --record "점검 1회차"
if [ -s "$r/data/last-check" ] && grep -qE '^ts=[0-9T:-]+Z note=점검 1회차$' "$r/data/last-check"; then
  run_tool "$r" "$CSL" --no-hygiene
  if grep -q '(출처 last-check)' "$work/tool.out"; then ok "check-since-last-record"; else not_ok "check-since-last-record" "다음 실행이 last-check를 읽지 않았다"; fi
else
  not_ok "check-since-last-record" "last-check를 쓰지 않았다"
fi

# MO-3 음성: 깨끗한 로그는 위반 0
run_tool "$work/h-clean" "$LHC" --since "$since_iso"
if [ "$rc" -eq 0 ] && grep -q '^SUMMARY violations=0 ' "$work/tool.out"; then
  ok "log-hygiene-clean(위반 0)"
else
  not_ok "log-hygiene-clean" "종료 코드 $rc · $(grep -m3 -E '^(CHECK|SUMMARY)' "$work/tool.out" | paste -sd' ' -)"
fi
# MO-3 양성: 점검마다 금지 값을 하나씩 흘리면 그 점검이 건수로 잡고, 값은 출력하지 않는다
r="$work/h-dirty"
make_root "$r"
secret="$(env_value "$r/env/core-api.env" SPRING_DATASOURCE_PASSWORD)"
transcript="오늘은 공룡 그림을 그렸어 $(rand 6)"
printf '%s\n' "$transcript" >"$work/values.list"
chmod 0600 "$work/values.list"
t=$((now_s - 600))
{
  printf '%s {"ts":"%s","level":"INFO","traceId":null,"component":"x","event":"STARTUP_RECOVERY","ownerHash":null,"attrs":{},"message":"m"}\n' "$(dts $t)" "$(its $t)"
  app_line $((t + 1)) INFO SESSION_TRANSITION '{"from":"ACTIVE","to":"CLOSING","transcript":"x"}'
  printf '%s APP_ERROR db password=%s\n' "$(dts $((t + 2)))" "$secret"
  printf '%s WARN Set-Cookie: MUJOONG_SESSION=abc\n' "$(dts $((t + 3)))"
  printf '%s child said: %s\n' "$(dts $((t + 4)))" "$transcript"
} >>"$r/c/core-api.log"
printf '%s ts=%s requestId=%032x method=GET uri=/api/home?token=1 status=200 bytes=1 rt=0 urt=0 upstream=- client=-\n' \
  "$(dts $((t + 5)))" "$(hts $((t + 5)))" 2 >>"$r/c/nginx.log"
printf '%s 2026 UTC [9] DETAIL:  Key (code)=(X) already exists.\n' "$(dts $((t + 6)))" >>"$r/c/postgres.log"
run_tool "$r" "$LHC" --since "$since_iso" --values-file "$work/values.list"
problems=()
[ "$rc" -eq 1 ] || problems+=("종료 코드 $rc")
for want in 'CHECK format violations=1' 'CHECK attrs violations=1' 'CHECK names violations=2' 'CHECK secrets violations=2' \
  'CHECK nginx violations=1' 'CHECK postgres violations=1'; do
  grep -qxF "$want" "$work/tool.out" || problems+=("[$want] 없음: $(grep -m1 "^${want% *}" "$work/tool.out")")
done
if grep -qF -- "$secret" "$work/tool.out" || grep -qF -- "$transcript" "$work/tool.out"; then
  problems+=("출력에 값이 있다")
fi
if [ "${#problems[@]}" -gt 0 ]; then
  not_ok "log-hygiene-violations(MO-3)" "${problems[*]}"
else
  ok "log-hygiene-violations(MO-3: 형식 · attrs · 금지 이름 · 비밀 값 · nginx 쿼리 · postgres DETAIL, 값 비출력)"
fi
# check-since-last가 위생 위반을 A-23으로 올린다
run_tool "$r" "$CSL" --since "$since_iso"
if grep -qE '^SUMMARY alerts=[0-9]+ .*A-23' "$work/tool.out"; then ok "check-since-last-hygiene(A-23)"; else not_ok "check-since-last-hygiene" "$(grep -m1 '^\[3-3\]' "$work/tool.out")"; fi
# values-file 권한이 600이 아니면 거부(64)
chmod 0644 "$work/values.list"
run_tool "$r" "$LHC" --since "$since_iso" --values-file "$work/values.list"
if [ "$rc" -eq 64 ]; then ok "log-hygiene-values-file-perm"; else not_ok "log-hygiene-values-file-perm" "종료 코드 $rc"; fi

# 허용 표 대조: log-hygiene-check의 사건 코드별 attrs 키 = 자바 정본(EventCode · AttrKey)
if command -v python3 >/dev/null 2>&1; then
  python3 -I - "$ROOT/backend/contract-model/src/main/java/kr/mujoong/contract/log/AttrKey.java" \
    "$ROOT/backend/contract-model/src/main/java/kr/mujoong/contract/log/EventCode.java" >"$work/allow.java" <<'PY'
import re, sys
attr = dict(re.findall(r'^\s+([A-Z0-9_]+)\("([A-Za-z0-9]+)",\s*ValueType', open(sys.argv[1], encoding="utf-8").read(), re.M))
body = open(sys.argv[2], encoding="utf-8").read().split("public enum EventCode {", 1)[1].split(";", 1)[0]
rows = []
for m in re.finditer(r"([A-Z_]+)\(Level\.\w+,\s*(?:true|false),\s*(?:true|false),\s*EnumSet\.of\(([^)]*)\)", body, re.S):
    keys = sorted(attr[c.strip()] for c in m.group(2).split(",") if c.strip())
    rows.append(m.group(1) + " " + ",".join(keys))
print("\n".join(sorted(rows)))
PY
  bash "$LHC" --print-allowlist >"$work/allow.script"
  if [ "$(wc -l <"$work/allow.java")" -eq 17 ] && diff -q "$work/allow.java" "$work/allow.script" >/dev/null; then
    ok "log-hygiene-allowlist-matches-java(17종)"
  else
    not_ok "log-hygiene-allowlist-matches-java" "$(diff "$work/allow.java" "$work/allow.script" | head -n 4 | paste -sd' ' -)"
  fi
else
  not_ok "log-hygiene-allowlist-matches-java" "python3가 없어 대조하지 못했다(전제 누락)"
fi

echo "test-host-tools: ${total}건 중 어긋남 ${failures}건"
if [ "$failures" -gt 0 ]; then
  exit 1
fi
