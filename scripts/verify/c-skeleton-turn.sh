#!/usr/bin/env bash
# 무중 검증 명령 C — 기기 시뮬레이터 한 턴 (U1 3.5 Step 49 · 계획 ## 검증 명령(C안) C 1 ~ 12 · J131 · J143 · J144 · SEC-07 · SEC-16)
#
# 실행 중인 검증 스택(compose: Nginx 127.0.0.1:8443, host: tools/dev-edge)에 node tools/skeleton-e2e/run-c.mjs를 돌린다.
# 프로필 dev + MUJOONG_DEVLOGIN_ENABLED=true(B가 띄운 스택), 모든 변경 요청에 Origin https://localhost:8443, 모든 대기에 기한.
#   - 가짜 공급자 기록 · realtime 로그: compose는 `docker compose logs -f --no-log-prefix`를 파일로 받고(이 스크립트가 끝에 멈춤),
#     host는 호스트 프로세스의 출력 파일을 그대로 넘긴다.
#   - SQL 래퍼(build/verify/c/sql.sh, 실행 때 만든다, 비밀 없음): `sql.sh core|realtime`이 표준 입력 SQL을 그 앱 역할로 돌린다.
#     compose는 postgres 컨테이너에서 env 파일(--env-file)로 비밀번호를 받아 자기 backend 주소로 접속하고(pg_hba 172.30.20.0/24),
#     host는 0600 PGPASSFILE로 127.0.0.1에 접속한다. 비밀번호는 명령줄 인자로 넘기지 않는다.
#   - 비밀 목록(build/verify/c/secrets.list, 0600): 먼저 gen-env 값(DB 비밀번호 5종 · 내부 토큰 · 공급자 키 자리값 · ownerHash
#     비밀)을 넣고, run-c가 실행 중 본 값(토큰 · 쿠키 · CSRF와 SHA-256, 등록 코드, 전사 · AI 말 · 폐기 문장)을 덧붙인다(B1 ③).
#   - 기한: 전체 남은 시간(scripts/verify.sh의 MUJOONG_VERIFY_DEADLINE)에서 5초를 뺀 값을 --total-timeout으로 준다.
#
# 쓰는 법: bash scripts/verify/c-skeleton-turn.sh --mode compose|host
# 출력: run-c의 단계 줄(C1 ~ C12 PASS|FAIL|TIMEOUT)과 결과. 비밀 값은 출력하지 않는다(run-c가 가린다).
# 종료 코드: 0 통과, 1 단언 실패 · 기한 초과, 2 전제 누락

set -Eeuo pipefail
# shellcheck source=scripts/verify/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$VERIFY_ROOT"

mode=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode) mode="${2:-}"; shift ;;
    *) echo "c-skeleton-turn: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done
case "$mode" in compose | host) ;; *) echo "c-skeleton-turn: --mode compose|host" >&2; exit 64 ;; esac

[ "$(node_major)" = 22 ] || die_prereq "Node 22가 아니다"
[ -f tools/node_modules/ws/package.json ] || die_prereq "tools/node_modules가 없다(cd tools && npm ci)"
[ -f "$VERIFY_CERT_DIR/ca.crt" ] || die_prereq "자체 서명 CA가 없다(B가 먼저 만든다)"
[ -d "$VERIFY_ENV_DIR" ] || die_prereq "생성 비밀 디렉터리가 없다(B가 먼저 만든다)"

umask 077
mkdir -p "$VERIFY_C_DIR"
chmod 0700 "$VERIFY_C_DIR"

# ---- 비밀 목록: gen-env 값 -------------------------------------------------------------------------------------------
: >>"$VERIFY_SECRETS_LIST"
chmod 0600 "$VERIFY_SECRETS_LIST"
{
  for f in "$VERIFY_ENV_DIR"/*.env; do
    while IFS= read -r line || [ -n "$line" ]; do
      key="${line%%=*}"
      value="${line#*=}"
      [ -n "$value" ] || continue
      for name in "${VERIFY_SECRET_NAMES[@]}"; do
        if [ "$key" = "$name" ]; then
          printf '%s\n' "$value"
          break
        fi
      done
    done <"$f"
  done
} | LC_ALL=C sort -u >>"$VERIFY_SECRETS_LIST"
pass C0.secrets "gen-env 비밀 값 $(wc -l <"$VERIFY_SECRETS_LIST")개를 비밀 목록에 넣음(값 미출력)"

# ---- 기록 파일 · SQL 래퍼 --------------------------------------------------------------------------------------------
followers=()
stop_followers() {
  local p
  for p in "${followers[@]}"; do
    kill "$p" 2>/dev/null || true
  done
  for p in "${followers[@]}"; do
    wait "$p" 2>/dev/null || true
  done
  followers=()
}
trap stop_followers EXIT

sql_cmd="$VERIFY_C_DIR/sql.sh"
if [ "$mode" = compose ]; then
  provider_record="$VERIFY_LOG_DIR/fake-provider.jsonl"
  realtime_log="$VERIFY_LOG_DIR/realtime-follow.jsonl"
  dc logs -f --no-log-prefix --no-color fake-provider >"$provider_record" 2>>"$VERIFY_LOG_DIR/c-follow.err" &
  followers+=("$!")
  dc logs -f --no-log-prefix --no-color realtime >"$realtime_log" 2>>"$VERIFY_LOG_DIR/c-follow.err" &
  followers+=("$!")
  pg_cid="$(dc ps -q postgres)"
  [ -n "$pg_cid" ] || die_assert C0.sql "postgres 컨테이너가 없다"
  cat >"$sql_cmd" <<SH
#!/usr/bin/env bash
# run-c SQL 래퍼(compose, scripts/verify/c-skeleton-turn.sh가 실행 때 만든다. 비밀 없음)
set -Eeuo pipefail
case "\${1:-}" in
  core) user=core_app env=core-api.env ;;
  realtime) user=realtime_app env=realtime.env ;;
  *) echo "sql.sh: core|realtime" >&2; exit 2 ;;
esac
exec docker exec -i --env-file "$VERIFY_ENV_DIR/\$env" "$pg_cid" sh -c \\
  'PGPASSWORD="\$SPRING_DATASOURCE_PASSWORD" exec psql -X -q -h "\$(hostname -i | cut -d" " -f1)" -U "\$1" -d mujoong -At -F "|" -v ON_ERROR_STOP=1 -f -' \\
  sql "\$user"
SH
else
  provider_record="$VERIFY_LOG_DIR/host-fake-provider.jsonl"
  realtime_log="$VERIFY_LOG_DIR/host-realtime.log"
  pg_port="$(cat "$VERIFY_HOST_DIR/pg.port" 2>/dev/null || true)"
  [ -n "$pg_port" ] || die_prereq "로컬 클러스터 포트를 모른다(B가 먼저 띄운다)"
  [ -f "$VERIFY_HOST_DIR/pgpass" ] || die_prereq "PGPASSFILE이 없다(B가 먼저 만든다)"
  cat >"$sql_cmd" <<SH
#!/usr/bin/env bash
# run-c SQL 래퍼(host, scripts/verify/c-skeleton-turn.sh가 실행 때 만든다. 비밀 없음 — 비밀번호는 0600 PGPASSFILE)
set -Eeuo pipefail
case "\${1:-}" in
  core) user=core_app ;;
  realtime) user=realtime_app ;;
  *) echo "sql.sh: core|realtime" >&2; exit 2 ;;
esac
export PGPASSFILE="$VERIFY_HOST_DIR/pgpass"
exec "$VERIFY_PG_BIN/psql" -X -q -h 127.0.0.1 -p "$pg_port" -U "\$user" -d mujoong -At -F '|' -v ON_ERROR_STOP=1 -f -
SH
fi
chmod 0700 "$sql_cmd"
for f in "$provider_record" "$realtime_log"; do
  [ -e "$f" ] || : >"$f"
done
if [ "$(printf 'SELECT current_user;\n' | bash "$sql_cmd" core 2>/dev/null || true)" != core_app ] ||
  [ "$(printf 'SELECT current_user;\n' | bash "$sql_cmd" realtime 2>/dev/null || true)" != realtime_app ]; then
  die_assert C0.sql "SQL 래퍼로 core_app · realtime_app 접속을 확인하지 못했다"
fi
pass C0.sql "SQL 래퍼 core_app · realtime_app 접속"

# ---- run-c -----------------------------------------------------------------------------------------------------------
total="$(($(remaining_seconds) - 5))"
[ "$total" -ge 10 ] || die_assert C.deadline "남은 시간이 ${total}초라 한 턴을 돌릴 수 없다"
rc=0
umask 022
node tools/skeleton-e2e/run-c.mjs \
  --provider-record "$provider_record" --realtime-log "$realtime_log" --sql-cmd "$sql_cmd" \
  --base-url "$VERIFY_BASE_URL" --origin "$VERIFY_ORIGIN" --ca "$VERIFY_CERT_DIR/ca.crt" \
  --secrets-file "$VERIFY_SECRETS_LIST" --out "$VERIFY_C_DIR/result.json" --repo-root "$VERIFY_ROOT" \
  --total-timeout "$total" >"$VERIFY_LOG_DIR/run-c.out" 2>&1 || rc=$?
cat "$VERIFY_LOG_DIR/run-c.out"
# 단계 줄을 보고 조각으로(run-c 출력은 비밀을 가린 줄이다)
grep -E '^C[0-9]+ (PASS|FAIL|TIMEOUT) ' "$VERIFY_LOG_DIR/run-c.out" | cut -c1-200 | while IFS= read -r line; do
  verify_fragment "$line"
done || true
stop_followers
# 가짜 공급자의 합성 대본 문장(저장소 tools/fake-provider/scenarios.mjs에 원래 있다). 로그 위생(P2)은 이 문장도 찾지만,
# 저장소 파일 대조(P3, a-secrets ③)에서는 뺀다 — 시험 문장이 그 정의 파일에 있는 것은 누출이 아니다
node --input-type=module -e '
import { pathToFileURL } from "node:url";
const m = await import(pathToFileURL(process.argv[1]).href);
for (const script of Object.values(m.TURN_SCRIPTS)) {
  for (const key of ["childText", "aiText"]) if (typeof script[key] === "string" && script[key]) console.log(script[key]);
}' "$VERIFY_ROOT/tools/fake-provider/scenarios.mjs" >"$VERIFY_C_DIR/fixture-texts.list" ||
  die_assert C0.fixtures "합성 대본 문장 목록을 만들지 못했다"
chmod 0600 "$VERIFY_C_DIR/fixture-texts.list"
case "$rc" in
  0) pass C.turn "C1 ~ C12 통과(D0 → 시험 로그인 → 05a → 04 안정 → 08 → 음성 1턴 → 게이트 경유 → P1 → ENDED)" ;;
  2) die_prereq "run-c 인자 · 전제 누락(logs/run-c.out)" ;;
  *) die_assert C.turn "run-c 종료 코드 $rc(logs/run-c.out)" ;;
esac
