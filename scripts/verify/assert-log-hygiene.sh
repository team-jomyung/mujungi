#!/usr/bin/env bash
# 무중 사후 단언 B1 ③ — 로그 위생 (U1 3.5 Step 49 · SEC-06 · F14 · SD-15 · NFR9.8 · BR8.7 · BR5.13)
#
# 1. 로그를 모은다(스택을 내리기 전). 가짜 공급자 · 시뮬레이터 출력은 전사를 직접 만드는 도구라 대상이 아니다.
#      compose  docker compose logs --no-color core-api realtime web nginx postgres → build/verify/logs/compose.log
#               (Nginx 접근 로그는 stdout → Docker json-file이라 여기에 든다)
#      host     호스트 프로세스 로그(host-core · host-realtime · host-web · host-edge)와 로컬 클러스터 서버 로그 → host-all.log
# 2. 빈 수집으로 통과하지 않는다: STARTUP_RECOVERY(core · realtime 각각) · SESSION_TRANSITION(→ ENDED) · LATENCY_TURN 줄과
#    접근 로그 줄(compose: Nginx log_format mujoong, host: dev-edge EDGE_ACCESS)이 각각 1개 이상 있어야 한다.
# 3. build/verify/c/secrets.list(0600, gen-env 값 + run-c가 본 값)의 값을 `grep -F -c -f`로 대조해 일치 건수만 낸다:
#    모은 앱 로그 · report.txt · verify 표준 출력 · 검증 단계 로그(B · C · P1). 0이 아니면 종료 코드 1.
#
# 쓰는 법: bash scripts/verify/assert-log-hygiene.sh --mode compose|host [--stdout-log <파일>] [--report <파일>]
# 출력: 파일별 일치 건수(숫자)만. 걸린 값 · 줄은 출력하지 않는다.
# 종료 코드: 0 통과, 1 단언 실패, 2 전제 누락

set -Eeuo pipefail
# shellcheck source=scripts/verify/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$VERIFY_ROOT"

mode=""
stdout_log=""
report_file=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode) mode="${2:-}"; shift ;;
    --stdout-log) stdout_log="${2:-}"; shift ;;
    --report) report_file="${2:-}"; shift ;;
    *) echo "assert-log-hygiene: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done
case "$mode" in compose | host) ;; *) echo "assert-log-hygiene: --mode compose|host" >&2; exit 64 ;; esac
[ -f "$VERIFY_SECRETS_LIST" ] || die_prereq "비밀 목록이 없다(C가 먼저 만든다)"

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# ---- 1. 수집 ---------------------------------------------------------------------------------------------------------
if [ "$mode" = compose ]; then
  docker_usable || die_prereq "Docker 데몬에 닿지 못한다"
  collected="$VERIFY_LOG_DIR/compose.log"
  dc logs --no-color core-api realtime web nginx postgres >"$collected" 2>&1 || die_assert P2.collect "docker compose logs 실패"
  core_lines() { grep -E '^core-api' "$collected" || true; }
  realtime_lines() { grep -E '^realtime' "$collected" || true; }
  access_lines() { grep -E '^nginx[^|]*\| ts=[^ ]+ requestId=[^ ]+ method=[A-Z]+ uri=/[^ ]* status=[0-9]{3} ' "$collected" || true; }
  access_label="Nginx 접근 로그"
else
  collected="$VERIFY_LOG_DIR/host-all.log"
  pg_log=""
  if [ -f "$VERIFY_HOST_DIR/pg.dir" ]; then
    pg_log="$(cat "$VERIFY_HOST_DIR/pg.dir")/server.log"
  fi
  : >"$collected"
  for f in host-core.log host-realtime.log host-web.log host-edge.log; do
    [ -f "$VERIFY_LOG_DIR/$f" ] || die_assert P2.collect "호스트 로그가 없다: $f"
    sed "s/^/${f%.log} | /" "$VERIFY_LOG_DIR/$f" >>"$collected"
  done
  if [ -n "$pg_log" ] && [ -r "$pg_log" ]; then
    sed 's/^/host-postgres | /' "$pg_log" >>"$collected"
  else
    die_assert P2.collect "로컬 클러스터 서버 로그를 읽지 못했다"
  fi
  core_lines() { grep -E '^host-core \| ' "$collected" || true; }
  realtime_lines() { grep -E '^host-realtime \| ' "$collected" || true; }
  access_lines() { grep -E '^host-edge \| .*"event":"EDGE_ACCESS"' "$collected" || true; }
  access_label="Nginx 대역(dev-edge) 접근 로그"
fi
chmod 0600 "$collected"
note P2.collect "$(wc -l <"$collected")줄 수집(${collected#"$VERIFY_ROOT"/})"

# ---- 2. 필수 줄 ------------------------------------------------------------------------------------------------------
# require_count <id> <설명> <줄 수>
require_count() {
  if [ "${3:-0}" -ge 1 ]; then
    pass "$1" "$2 ${3}줄"
  else
    fail "$1" "$2 줄이 없다(빈 수집으로 통과하지 않는다)"
  fi
}
count_lines() { grep -c . || true; }
require_count P2.startup.core "core-api STARTUP_RECOVERY" \
  "$(core_lines | { grep -F '"event":"STARTUP_RECOVERY"' || true; } | count_lines)"
require_count P2.startup.realtime "realtime STARTUP_RECOVERY" \
  "$(realtime_lines | { grep -F '"event":"STARTUP_RECOVERY"' || true; } | count_lines)"
require_count P2.ended "realtime SESSION_TRANSITION(→ ENDED)" \
  "$(realtime_lines | { grep -F '"event":"SESSION_TRANSITION"' || true; } | { grep -F '"to":"ENDED"' || true; } | count_lines)"
require_count P2.latency "realtime LATENCY_TURN" \
  "$(realtime_lines | { grep -F '"event":"LATENCY_TURN"' || true; } | count_lines)"
require_count P2.access "$access_label" "$(access_lines | count_lines)"

# ---- 3. 비밀 값 대조 -------------------------------------------------------------------------------------------------
[ "$(stat -c '%a' "$VERIFY_SECRETS_LIST")" = 600 ] || fail P2.list "비밀 목록 권한이 600이 아니다"
grep -v -x '' "$VERIFY_SECRETS_LIST" >"$work/patterns" || true
pattern_count="$(wc -l <"$work/patterns")"
# gen-env 값 8개(DB 5 · 내부 토큰 · 공급자 키 자리값 · ownerHash 비밀) + run-c가 본 값이 있어야 한다
if [ "$pattern_count" -lt 12 ]; then
  fail P2.list "비밀 목록이 ${pattern_count}개뿐이다(gen-env 값 + 실행 중 본 값이 있어야 한다)"
fi

targets=("$collected")
[ -n "$report_file" ] && targets+=("$report_file")
[ -n "$stdout_log" ] && targets+=("$stdout_log")
for stage in B C P1 run-c.out; do
  f="$VERIFY_LOG_DIR/$stage"
  [ -f "$f" ] || f="$VERIFY_LOG_DIR/$stage.log"
  [ -f "$f" ] && targets+=("$f")
done
total=0
for f in "${targets[@]}"; do
  [ -f "$f" ] || die_assert P2.target "대조 대상이 없다: ${f#"$VERIFY_ROOT"/}"
  c="$(grep -F -c -f "$work/patterns" -- "$f" || true)"
  echo "P2 대조 ${f#"$VERIFY_ROOT"/}: ${c:-0}건"
  total=$((total + ${c:-0}))
done
if [ "$total" -eq 0 ]; then
  pass P2.secrets "비밀 값 ${pattern_count}개 × 대상 ${#targets[@]}개 파일, 일치 0건"
else
  fail P2.secrets "비밀 값이 로그 · 보고 · 출력에 ${total}줄 나타났다(파일별 건수는 logs/P2.log, 값 미출력)"
fi
finish_asserts
