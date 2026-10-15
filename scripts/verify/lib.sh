# shellcheck shell=bash
# shellcheck disable=SC2034  # 상수는 이 파일을 source하는 단계 스크립트가 쓴다
# 무중 검증 명령 공통 함수 (U1 3.5 Step 49 · J103 · J108 · J127 · J133 · J134 · J144 · SEC-01 · SEC-05 · SEC-09)
#
# scripts/verify.sh와 scripts/verify/*.sh가 source한다. 이 파일은 함수 · 상수만 정의하고 아무것도 실행하지 않는다.
# 규칙(계획 Step 49):
#   - 모든 스크립트는 `set -Eeuo pipefail`로 시작하고 저장소 루트를 자기 위치에서 직접 잡는다(VERIFY_ROOT).
#   - 루프백 · 8443 요청이 HTTPS_PROXY로 가지 않게 NO_PROXY를 두고, curl은 항상 --noproxy와 자체 서명 CA를 쓴다.
#   - 단언 결과는 `PASS <id> …` · `FAIL <id>: …` 한 줄씩. 값(비밀 · 설정 펼침)은 출력하지 않는다.
#   - 종료 코드: 단언 실패 1, 전제 누락 2(VERIFY_EXIT_PREREQ), 그 밖의 오류도 비0.
#   - 대기에는 모두 기한이 있다. 전체 기한은 scripts/verify.sh가 MUJOONG_VERIFY_DEADLINE(epoch 초)로 넘긴다.

export NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1

VERIFY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly VERIFY_ROOT
readonly VERIFY_DIR="$VERIFY_ROOT/build/verify"
readonly VERIFY_LOG_DIR="$VERIFY_DIR/logs"
readonly VERIFY_ENV_DIR="$VERIFY_DIR/env"
readonly VERIFY_C_DIR="$VERIFY_DIR/c"
readonly VERIFY_CERT_DIR="$VERIFY_DIR/certs"
readonly VERIFY_HOST_DIR="$VERIFY_DIR/host"
readonly VERIFY_PID_DIR="$VERIFY_HOST_DIR/pids"
readonly VERIFY_SECRETS_LIST="$VERIFY_C_DIR/secrets.list"
readonly VERIFY_FINGERPRINT_FILE="$VERIFY_DIR/fingerprint"

readonly VERIFY_EXIT_ASSERT=1
readonly VERIFY_EXIT_PREREQ=2

# 검증 스택(J108 · J136): Compose 프로젝트 · 로컬 이미지 · 입구
readonly VERIFY_PROJECT=mujoong-verify
readonly VERIFY_REGISTRY=mujoong.local
readonly VERIFY_TAG=verify
readonly VERIFY_SERVICE_HOST=localhost
readonly VERIFY_BASE_URL="https://localhost:8443"
readonly VERIFY_ORIGIN="https://localhost:8443"
# 검증 스택이 쓰는 이미지(compose.ci.yml이 끄는 backup · certbot과 번들 infra는 빼고, 시험 도구 fake-provider는 넣는다)
readonly VERIFY_STACK_IMAGES=(core-api realtime web migrate fake-provider)
# host 모드 포트(J133): core 8080 · realtime 8081 · 관리 9090 · 9091 · web 3000 · 가짜 공급자 8090 · 입구 8443
readonly VERIFY_HOST_PORTS=(8080 8081 9090 9091 3000 8090 8443)

# 호스트 PostgreSQL 16 바이너리(J132 · J133). 다른 경로에 설치한 호스트는 MUJOONG_PG_BIN으로 알려 준다
VERIFY_PG_BIN="${MUJOONG_PG_BIN:-/usr/lib/postgresql/16/bin}"

# 비밀 변수 이름 등록부(SD-12 · infra-spec 3.7). 앱 안 이름(SPRING_DATASOURCE_PASSWORD)과 neo4j 것도 함께 본다
readonly VERIFY_SECRET_NAMES=(POSTGRES_PASSWORD DB_OWNER_PASSWORD DB_CORE_PASSWORD DB_REALTIME_PASSWORD DB_BACKUP_PASSWORD
  INTERNAL_SERVICE_TOKEN PROVIDER_API_KEY OPENAI_API_KEY JEV_API_KEY MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET
  GOOGLE_OAUTH_CLIENT_SECRET GHCR_PULL_TOKEN SPRING_DATASOURCE_PASSWORD NEO4J_AUTH)

# ---------------------------------------------------------------------------------------------------------------------
# 결과 줄 · 보고 조각
# ---------------------------------------------------------------------------------------------------------------------
# scripts/verify.sh가 단계마다 MUJOONG_VERIFY_FRAGMENT(보고 조각 파일)를 준다. 단계 스크립트는 단언 결과를 여기에 한 줄씩
# 남기고, verify.sh가 단계가 끝난 뒤 report.txt에 옮긴다(단계 PASS 줄은 단계가 종료 코드 0으로 끝난 뒤에만 verify.sh가 쓴다).
VERIFY_FAILS=0

verify_fragment() {
  if [ -n "${MUJOONG_VERIFY_FRAGMENT:-}" ]; then
    printf '  %s\n' "$*" >>"$MUJOONG_VERIFY_FRAGMENT"
  fi
}

# pass <id> <설명> — 단언 하나가 통과한 뒤에만 부른다
pass() {
  local id="$1"
  shift
  echo "PASS $id $*"
  verify_fragment "$id PASS $*"
}

# fail <id> <사유> — 단언 실패를 센다(바로 끝내지 않는다). 사유에 값을 넣지 않는다
fail() {
  local id="$1"
  shift
  echo "FAIL $id: $*" >&2
  verify_fragment "$id FAIL $*"
  VERIFY_FAILS=$((VERIFY_FAILS + 1))
}

# note <id> <설명> — 판정이 아닌 기록(측정값 · 확인하지 못한 항목)
note() {
  local id="$1"
  shift
  echo "NOTE $id $*"
  verify_fragment "$id NOTE $*"
}

# 지금까지 실패가 있으면 종료 코드 1로 끝낸다
finish_asserts() {
  if [ "$VERIFY_FAILS" -gt 0 ]; then
    echo "단언 실패 ${VERIFY_FAILS}건" >&2
    exit "$VERIFY_EXIT_ASSERT"
  fi
}

# 단언 실패로 바로 끝낸다
die_assert() {
  fail "$@"
  exit "$VERIFY_EXIT_ASSERT"
}

# 전제 누락으로 끝낸다(통과로 보고하지 않는다)
die_prereq() {
  echo "PREREQ $*" >&2
  verify_fragment "PREREQ $*"
  exit "$VERIFY_EXIT_PREREQ"
}

log() {
  printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"
}

# ---------------------------------------------------------------------------------------------------------------------
# 기한
# ---------------------------------------------------------------------------------------------------------------------
# remaining_seconds — 전체 기한까지 남은 초(최소 1). 기한이 없으면 큰 값
remaining_seconds() {
  local deadline="${MUJOONG_VERIFY_DEADLINE:-}"
  if [ -z "$deadline" ]; then
    echo 86400
    return
  fi
  local left=$((deadline - $(date +%s)))
  if [ "$left" -lt 1 ]; then
    left=1
  fi
  echo "$left"
}

# bounded <초> — 주어진 기한과 전체 남은 시간 중 작은 값
bounded() {
  local want="$1" left
  left="$(remaining_seconds)"
  if [ "$want" -lt "$left" ]; then echo "$want"; else echo "$left"; fi
}

# wait_until <초> <설명> <명령...> — 명령이 성공할 때까지 0.5초 간격으로 다시 부른다. 넘으면 1
wait_until() {
  local limit="$1" what="$2"
  shift 2
  local deadline=$(($(date +%s) + limit))
  while :; do
    if "$@" >/dev/null 2>&1; then
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "기한 ${limit}초 안에 '$what'에 닿지 못했다" >&2
      return 1
    fi
    sleep 0.5
  done
}

# ---------------------------------------------------------------------------------------------------------------------
# 도구 · 판
# ---------------------------------------------------------------------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

# java_major <java 실행 파일> — 주 판 번호(못 읽으면 0)
java_major() {
  local out
  out="$("$1" -version 2>&1 | grep -m1 -E 'version "' || true)"
  if [[ "$out" =~ version\ \"([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  else
    echo 0
  fi
}

# find_java21 — JDK 21의 java 경로(JAVA_HOME · PATH · /usr/lib/jvm 순). 없으면 비0
find_java21() {
  local candidate
  for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null || true)" /usr/lib/jvm/*21*/bin/java; do
    if [ -z "$candidate" ] || [ ! -x "$candidate" ]; then continue; fi
    if [ "$(java_major "$candidate")" = 21 ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

# node_major — node 주 판 번호(없으면 0)
node_major() {
  local v
  v="$(node --version 2>/dev/null || true)"
  if [[ "$v" =~ ^v([0-9]+)\. ]]; then echo "${BASH_REMATCH[1]}"; else echo 0; fi
}

# version_ge <a.b.c> <x.y.z>
version_ge() {
  local IFS=.
  local -a a b
  read -r -a a <<<"$1"
  read -r -a b <<<"$2"
  local i
  for i in 0 1 2; do
    local x="${a[$i]:-0}" y="${b[$i]:-0}"
    x="${x%%[!0-9]*}"
    y="${y%%[!0-9]*}"
    if ((10#${x:-0} > 10#${y:-0})); then return 0; fi
    if ((10#${x:-0} < 10#${y:-0})); then return 1; fi
  done
  return 0
}

# docker_usable — 데몬에 닿는가
docker_usable() {
  have docker && docker info >/dev/null 2>&1
}

# compose_version_ok — Compose v2.24.4 이상(!override, J136)
compose_version_ok() {
  local v
  v="$(docker compose version --short 2>/dev/null || true)"
  v="${v#v}"
  [ -n "$v" ] && version_ge "$v" 2.24.4
}

# postgres 고정 태그(compose.demo.yml이 정본)
pg_image() {
  grep -E '^\s+image:\s*postgres:' "$VERIFY_ROOT/infra/compose.demo.yml" | head -n 1 | sed -E 's/^\s+image:\s*//'
}

# ---------------------------------------------------------------------------------------------------------------------
# env 파일(gen-env.sh가 만든 build/verify/env/*.env) — 값은 변수에만 담고 출력하지 않는다
# ---------------------------------------------------------------------------------------------------------------------
# env_value <파일> <이름> — 그 줄의 값(없으면 빈 문자열). 파일을 셸로 source하지 않는다
env_value() {
  local line
  line="$(grep -m1 -E "^$2=" "$1" 2>/dev/null || true)"
  printf '%s' "${line#*=}"
}

# ---------------------------------------------------------------------------------------------------------------------
# HTTP · Compose
# ---------------------------------------------------------------------------------------------------------------------
# vcurl [curl 인자...] — 검증 스택 호출. 프록시를 거치지 않고 자체 서명 CA만 믿는다(TLS 검증은 끄지 않는다)
vcurl() {
  curl -sS --noproxy localhost,127.0.0.1 --cacert "$VERIFY_CERT_DIR/ca.crt" --max-time 15 "$@"
}

# dc [compose 인자...] — 검증 스택 Compose(demo + ci 덧붙임, J136)
dc() {
  docker compose -p "$VERIFY_PROJECT" --env-file "$VERIFY_ENV_DIR/compose.env" \
    -f "$VERIFY_ROOT/infra/compose.demo.yml" -f "$VERIFY_ROOT/infra/compose.ci.yml" "$@"
}

# ---------------------------------------------------------------------------------------------------------------------
# host 모드 프로세스(J133) — setsid로 띄워 프로세스 그룹째 멈춘다. pid 파일: <이름>.pid = "<pid> <표지>"
# 표지는 환경 변수 MUJOONG_VERIFY_SPAWN=<저장소 루트>:<이름>이다. 프로세스 제목을 바꾸는 서버(Next.js는 cmdline을
# 'next-server (v…)'로 바꾼다)도 /proc/<pid>/environ으로 알아보고, 재사용된 pid · 다른 작업 트리의 프로세스는 건드리지 않는다.
# ---------------------------------------------------------------------------------------------------------------------
# host_spawn <이름> <로그 파일> <env 파일 또는 ''> <설명> -- <명령...>
#   환경은 비우고(env -i: 세션의 프록시 · JAVA_TOOL_OPTIONS를 물려주지 않음) PATH · HOME · LANG · TZ와 env 파일 값만 준다.
#   env 파일은 자식 bash가 읽으므로 비밀 값이 명령줄 인자에 나타나지 않는다. 비밀이 아닌 값은 VERIFY_SPAWN_ENV 배열로 준다.
VERIFY_SPAWN_ENV=()
host_spawn() {
  local name="$1" logfile="$2" envfile="$3"
  shift 4
  [ "${1:-}" = "--" ] && shift
  mkdir -p "$VERIFY_PID_DIR"
  local marker="MUJOONG_VERIFY_SPAWN=$VERIFY_ROOT:$name"
  # shellcheck disable=SC2016  # 자식 bash가 펼친다
  setsid env -i PATH="$PATH" HOME="${HOME:-/root}" LANG=C.UTF-8 TZ=UTC "$marker" "${VERIFY_SPAWN_ENV[@]}" \
    bash -c 'if [ -n "$1" ]; then set -a; . "$1"; set +a; fi; shift; exec "$@"' mujoong-spawn "$envfile" "$@" \
    >"$logfile" 2>&1 </dev/null &
  printf '%s %s\n' "$!" "$marker" >"$VERIFY_PID_DIR/$name.pid"
}

# pid_alive_with <pid> <표지> — 살아 있고 환경에 표지(MUJOONG_VERIFY_SPAWN=…)가 정확히 있는가
pid_alive_with() {
  local pid="$1" marker="$2"
  [ -n "$marker" ] && [ -r "/proc/$pid/environ" ] || return 1
  tr '\0' '\n' <"/proc/$pid/environ" 2>/dev/null | grep -qxF -- "$marker"
}

# host_stop_all — pid 파일의 프로세스 그룹을 TERM → 10초 뒤 KILL
host_stop_all() {
  [ -d "$VERIFY_PID_DIR" ] || return 0
  local f pid marker pids=()
  for f in "$VERIFY_PID_DIR"/*.pid; do
    [ -e "$f" ] || continue
    read -r pid marker <"$f" || true
    if [ -n "${pid:-}" ] && pid_alive_with "$pid" "${marker:-}"; then
      kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
      pids+=("$pid")
    fi
    rm -f "$f"
  done
  local i p alive
  for i in $(seq 1 20); do
    alive=0
    for p in "${pids[@]}"; do
      kill -0 "$p" 2>/dev/null && alive=1
    done
    [ "$alive" -eq 0 ] && break
    sleep 0.5
  done
  for p in "${pids[@]}"; do
    kill -KILL -- "-$p" 2>/dev/null || kill -KILL "$p" 2>/dev/null || true
  done
}

# as_postgres <명령...> — root면 postgres 사용자로(initdb · pg_ctl은 root로 돌지 않는다, J132)
as_postgres() {
  if [ "$(id -u)" -eq 0 ]; then
    runuser -u postgres -- "$@"
  else
    "$@"
  fi
}

# host_pg_stop — b-host.sh가 띄운 로컬 클러스터를 멈추고 임시 디렉터리를 지운다
host_pg_stop() {
  local f="$VERIFY_HOST_DIR/pg.dir" dir
  [ -f "$f" ] || return 0
  dir="$(cat "$f")"
  if [ -n "$dir" ] && [ -d "$dir/data" ] && [ -f "$dir/data/postmaster.pid" ]; then
    as_postgres "$VERIFY_PG_BIN/pg_ctl" -D "$dir/data" -m fast -w -t 20 stop >/dev/null 2>&1 || true
  fi
  # 지우기 전에 서버 로그를 로그 위생 대상으로 남긴다(B1 ③ host 모드)
  if [ -n "$dir" ] && [ -f "$dir/server.log" ] && [ -d "$VERIFY_LOG_DIR" ]; then
    cp "$dir/server.log" "$VERIFY_LOG_DIR/host-postgres.log" 2>/dev/null || true
  fi
  case "$dir" in
    /tmp/mujoong-verify-pg.*) rm -rf -- "$dir" ;;
  esac
  rm -f "$f"
}

# ---------------------------------------------------------------------------------------------------------------------
# B4 입구 단언(compose: Nginx, host: tools/dev-edge) — 3종 호출 + SD-3 중 B1 필수(R3-21 341행) + SEC-12
# ---------------------------------------------------------------------------------------------------------------------
# WSS 탐침: 쿠키 없이 허용 Origin으로 업그레이드 → {"status":101,"close":4401} 꼴 한 줄. ws 패키지는 tools/node_modules에서 읽는다.
# TLS는 자체 서명 CA만 믿는다(검증을 끄지 않는다). 프록시 에이전트를 쓰지 않는다.
readonly VERIFY_WS_PROBE='
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
const [url, origin, caFile, toolsPackage] = process.argv.slice(1);
const WebSocket = createRequire(toolsPackage)("ws");
const result = { status: null, close: null };
const done = () => { console.log(JSON.stringify(result)); process.exit(0); };
const ws = new WebSocket(url, { origin, ca: readFileSync(caFile), handshakeTimeout: 8000 });
ws.on("upgrade", (res) => { result.status = res.statusCode; });
ws.on("unexpected-response", (_req, res) => { result.status = res.statusCode; done(); });
ws.on("close", (code) => { result.close = code; done(); });
ws.on("error", (error) => { result.error = error.code ?? "error"; });
setTimeout(() => { result.error = result.error ?? "timeout"; done(); }, 12000);
'

# http_code <출력 본문 파일> [curl 인자...] — 상태 코드(연결 실패면 000)
http_code() {
  local body="$1"
  shift
  vcurl -o "$body" -w '%{http_code}' "$@" 2>/dev/null || true
}

entry_assertions() {
  local base="$VERIFY_BASE_URL" tmp code
  tmp="$(mktemp -d)"
  # (1) 3종 호출
  code="$(http_code "$tmp/body" "$base/api/health")"
  if [ "$code" = 200 ] && jq -e '.status == "UP"' "$tmp/body" >/dev/null 2>&1; then
    pass B4.health 'GET /api/health 200 {"status":"UP"}'
  else
    fail B4.health "GET /api/health 상태 $code(기대 200 · status UP)"
  fi
  code="$(http_code "$tmp/body" "$base/device")"
  if [ "$code" = 200 ]; then pass B4.device "GET /device 200"; else fail B4.device "GET /device 상태 $code(기대 200)"; fi
  local ws
  ws="$(node --input-type=module -e "$VERIFY_WS_PROBE" "wss://localhost:8443/ws/device" "$VERIFY_ORIGIN" \
    "$VERIFY_CERT_DIR/ca.crt" "$VERIFY_ROOT/tools/package.json" 2>/dev/null || echo '{}')"
  if jq -e '.status == 101 and .close == 4401' <<<"$ws" >/dev/null 2>&1; then
    pass B4.ws "/ws/device 업그레이드 101(허용 Origin · 쿠키 없음) → 4401 닫힘"
  else
    fail B4.ws "/ws/device 결과 $(jq -c '{status, close, error}' <<<"$ws" 2>/dev/null || echo '?')(기대 101 → 4401)"
  fi
  # (2) SD-3 중 B1 필수: 내부 경로 403 · 본문 64KB · 추적 id 덮어쓰기 · 원시 URI 우회 400
  local path
  for path in /internal/x /actuator/health; do
    code="$(http_code "$tmp/body" "$base$path")"
    if [ "$code" = 403 ]; then pass "B4.forbidden$path" "GET $path 403"; else fail "B4.forbidden$path" "GET $path 상태 $code(기대 403)"; fi
  done
  head -c 65537 /dev/zero | tr '\0' 'a' >"$tmp/big"
  code="$(http_code "$tmp/body" -X POST -H 'Content-Type: application/json' -H "Origin: $VERIFY_ORIGIN" \
    --data-binary "@$tmp/big" "$base/api/device/register")"
  if [ "$code" = 400 ] && jq -e '.code == "VALIDATION_FAILED"' "$tmp/body" >/dev/null 2>&1; then
    pass B4.body64k "64KB+1 본문 POST /api/device/register → 400 VALIDATION_FAILED 봉투"
  else
    fail B4.body64k "64KB+1 본문 상태 $code(기대 400 VALIDATION_FAILED)"
  fi
  local forged
  forged="$(openssl rand -hex 16)"
  code="$(vcurl -D "$tmp/headers" -o "$tmp/body" -w '%{http_code}' -H "X-Trace-Id: $forged" "$base/api/device/me" 2>/dev/null || true)"
  local header_trace body_trace
  header_trace="$(grep -i '^x-trace-id:' "$tmp/headers" 2>/dev/null | head -n 1 | cut -d: -f2- | tr -d ' \r' || true)"
  body_trace="$(jq -r '.traceId // empty' "$tmp/body" 2>/dev/null || true)"
  if [ "$code" = 401 ] && [ -n "$header_trace" ] && [ "$header_trace" != "$forged" ] && [ -n "$body_trace" ] &&
    [ "$body_trace" != "$forged" ]; then
    pass B4.trace "위조 X-Trace-Id가 응답 헤더 · 봉투 traceId에 없다(OD-7)"
  else
    fail B4.trace "위조 X-Trace-Id 판정 실패(상태 $code, 헤더 $([ -n "$header_trace" ] && echo 있음 || echo 없음), 위조 값 그대로 $([ "$header_trace" = "$forged" ] || [ "$body_trace" = "$forged" ] && echo 예 || echo 아니오))"
  fi
  code="$(http_code "$tmp/body" --path-as-is "$base/api/..;/internal/x")"
  if [ "$code" = 400 ]; then pass B4.rawuri "/api/..;/internal/x 400"; else fail B4.rawuri "/api/..;/internal/x 상태 $code(기대 400)"; fi
  # (3) SEC-12: 서비스 호스트가 아닌 SNI는 TLS에서 거절(대조: localhost SNI는 CA로 검증 통과)
  local good bad
  good="$(openssl s_client -connect 127.0.0.1:8443 -servername localhost -CAfile "$VERIFY_CERT_DIR/ca.crt" \
    -verify_return_error </dev/null 2>&1 || true)"
  bad="$(timeout 10 openssl s_client -connect 127.0.0.1:8443 -servername other.invalid </dev/null 2>&1 || true)"
  if grep -q 'Verify return code: 0 (ok)' <<<"$good" && ! grep -q -- '-----BEGIN CERTIFICATE-----\|^Server certificate' <<<"$bad" &&
    grep -qiE 'no peer certificate available|alert|errno|unexpected eof|Connection reset' <<<"$bad"; then
    pass B4.sni "SNI other.invalid TLS 거절(대조 localhost 검증 통과)"
  else
    fail B4.sni "SNI 판정 실패(localhost 검증 $(grep -q 'Verify return code: 0 (ok)' <<<"$good" && echo 통과 || echo 실패), other.invalid 인증서 받음 $(grep -q '^Server certificate' <<<"$bad" && echo 예 || echo 아니오))"
  fi
  code="$(http_code "$tmp/body" -H 'Host: other.invalid' "$base/api/health")"
  if [[ "$code" =~ ^[23] ]]; then
    fail B4.host "Host: other.invalid 요청이 상태 $code로 처리됐다(기대 거절)"
  else
    pass B4.host "Host: other.invalid 요청 거절(상태 $code)"
  fi
  rm -rf "$tmp"
}
