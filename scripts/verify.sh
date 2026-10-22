#!/usr/bin/env bash
# 무중 검증 명령 C안 — bash scripts/verify.sh (U1 3.5 Step 49 · 사람 결정 2 · 10 · 11 · J103 · J133 · J134 · J144)
#
# 저장소 루트에서 실행한다(이 스크립트가 자기 위치에서 루트를 직접 잡는다). 실공급자는 부르지 않는다(가짜 공급자).
#
# 쓰는 법: bash scripts/verify.sh [--mode auto|compose|host] [--engine | --skip-a] [--deadline <초>] [--help]
#   --mode auto     (기본) docker info와 postgres 고정 태그 확보(로컬에 있거나 pull 성공)가 되면 compose, 아니면 host(J133)
#   --mode compose  이미지 빌드 → docker compose(demo + ci 덧붙임)로 검증 스택(nginx 127.0.0.1:8443)
#   --mode host     Docker 없이: 로컬 PostgreSQL 16 클러스터 + bootJar 두 개 + Next.js standalone + 가짜 공급자 +
#                   Nginx 대역(tools/dev-edge, 127.0.0.1:8443). 확인하지 못하는 항목은 report.txt에 NOT_VERIFIED(host)로 남긴다
#   --engine        엔진 checkpoint용(J134 (가), 사람 결정 10): A와 이미지 빌드를 하지 않는다. 앞선 전체 실행이 만든 산출물
#                   (compose: 이미지 라벨, host: build/verify/fingerprint)의 소스 지문이 지금 지문과 같을 때만 B · C · 사후 단언을
#                   내부 기한 110초로 돈다. 다르거나 없으면 종료 코드 1
#   --skip-a        스켈레톤 경량(CI skeleton 기본): A1 ~ A4(백엔드 · 프런트 · tools 전체 회귀와 비밀 스캔)를 건너뛰고
#                   A0에서 기동 산출물(bootJar 두 개 · 시험 클래스 0 검사 · Next.js standalone · tools 의존성)만 만든다.
#                   B(이미지 빌드 포함) · C · 사후 단언은 전체 실행과 같다. 건너뛴 A는 report.txt에 NOT_VERIFIED(skip-a)로 남긴다.
#                   회귀는 PR의 ci-gate와 수동 전체 실행 · Build and Test가 맡는다. --engine과 함께 쓰지 않는다
#   --deadline <초> 전체 내부 기한(기본 전체 실행 5400초, --engine 110초). 단계마다 기본 기한과 남은 시간 중 작은 값을 쓴다
#   환경 변수 KEEP=1  끝에 스택(컨테이너 · host 프로세스 · 로컬 클러스터)만 남긴다. 생성 비밀 · 비밀 목록은 그래도 지운다
#
# 단계(각 단계의 출력은 build/verify/logs/<단계>.log, 표준 출력은 단계마다 한 줄):
#   A1 backend   cd backend && ./gradlew --no-daemon clean check verifyBootJarHasNoTestClasses (전체 회귀 · ArchUnit ·
#                설정 키 대조 · 모듈 전체 JaCoCo 보고, DB 시험 백엔드는 compose=auto · host=local, J132)
#   A2 frontend  npm ci · eslint . · vitest run tests --coverage(보고 전용, 유닛 시험 폴더 전부) · next build(+ standalone에 정적 자원 복사)
#   A3 tools     npm ci · vitest run tests (J131, 유닛 시험 폴더 전부)
#   A4 secrets   scripts/verify/a-secrets.sh — 비밀 스캔 ① · 무시 규칙 ② (SEC-10)
#   A0 산출물    (--skip-a) a-checks.sh artifacts — bootJar · verifyBootJarHasNoTestClasses · next build(+ 정적 자원) · tools npm ci
#   F  지문      A 동안 소스가 바뀌지 않았는지 보고 build/verify/fingerprint에 소스 지문 · 산출물 해시를 적는다(J134)
#   E0 지문      (--engine) 미리 만든 산출물의 지문이 지금 소스 지문과 같은가
#   B  기동      compose: b-stack.sh(이미지 빌드 · 병합 설정 단언 · migrate · 시드 두 번 · 프로필 가드 · up --wait · 3종 호출 ·
#                SD-3 단언) / host: b-host.sh(같은 순서, 로컬 클러스터 · 호스트 프로세스)
#   C  한 턴     c-skeleton-turn.sh → node tools/skeleton-e2e/run-c.mjs (C1 ~ C12: D0 → 시험 로그인 → 05a → 04 안정 → 08 →
#                음성 1턴 → 게이트 경유 → P1 → ENDED)
#   P1 격리      assert-schema-isolation.sh — 교차 스키마 쓰기 · 앱 역할 CREATE TABLE이 SQLSTATE 42501 (B1 ②)
#   P2 위생      assert-log-hygiene.sh — 필수 로그 줄 존재 + 비밀 목록 값이 로그 · 보고 · 표준 출력에 0건 (B1 ③)
#   P3 값        a-secrets.sh --values — 생성 비밀 · 본 비밀 값이 추적 · 무시되지 않은 파일에 0건 (a-secrets ③)
#   T  작업 트리 시작과 끝의 git status --porcelain=v1 -uall · git diff --stat · 내용 해시가 같은가(SEC-18)
#
# 결과: build/verify/report.txt(첫 줄 mode=<모드>, 단계마다 한 줄과 소요 초, 마지막 줄 RESULT). 비밀 값은 쓰지 않는다.
#   단계 PASS 줄은 그 단계가 종료 코드 0으로 끝난 뒤에만 쓴다. 단계 건너뛰기는 --skip-a(A1 ~ A4)뿐이고 B · C · 사후 단언은 건너뛰지 않는다.
# 종료 코드: 0 = A · B · C · 사후 단언 모두 통과, 1 = 어느 단언이든 실패(기한 초과 포함), 2 = 전제 누락(JDK 21 · Node 22 ·
#   compose 모드의 Docker + Compose v2.24.4 이상 · host 모드의 PostgreSQL 16 바이너리 · git 작업 트리 등, 통과로 보고하지 않음),
#   64 = 인자 오류

set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=scripts/verify/lib.sh
. scripts/verify/lib.sh
cd "$VERIFY_ROOT"

# 바깥(예: 시험이 부른 검증 명령)에서 물려받은 내부 변수를 쓰지 않는다
unset MUJOONG_VERIFY_FRAGMENT MUJOONG_VERIFY_DEADLINE MUJOONG_VERIFY_MODE MUJOONG_VERIFY_ENGINE MUJOONG_VERIFY_START_FINGERPRINT

usage() {
  # 2행부터 이어지는 머리 주석 묶음만 낸다(첫 비주석 줄에서 멈춘다)
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

mode=auto
engine=0
skip_a=0
deadline_total=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode) mode="${2:-}"; shift ;;
    --engine) engine=1 ;;
    --skip-a) skip_a=1 ;;
    --deadline) deadline_total="${2:-}"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "verify: 모르는 인자: $1 (--help)" >&2; exit 64 ;;
  esac
  shift
done
case "$mode" in auto | compose | host) ;; *) echo "verify: --mode는 auto · compose · host 중 하나다" >&2; exit 64 ;; esac
if [ "$engine" -eq 1 ] && [ "$skip_a" -eq 1 ]; then
  echo "verify: --engine과 --skip-a는 함께 쓰지 않는다" >&2
  exit 64
fi
if [ -z "$deadline_total" ]; then
  if [ "$engine" -eq 1 ]; then deadline_total=110; else deadline_total=5400; fi
fi
if ! [[ "$deadline_total" =~ ^[1-9][0-9]*$ ]]; then
  echo "verify: --deadline은 양의 정수(초)다" >&2
  exit 64
fi

STARTED_AT="$(date +%s)"
readonly STARTED_AT
export MUJOONG_VERIFY_DEADLINE=$((STARTED_AT + deadline_total))
readonly REPORT="$VERIFY_DIR/report.txt"
readonly STDOUT_LOG="$VERIFY_LOG_DIR/verify-stdout.log"
readonly FRAGMENT_DIR="$VERIFY_DIR/report.d"
readonly TREE_START="$VERIFY_DIR/tree-start.txt"
readonly TREE_END="$VERIFY_DIR/tree-end.txt"
STACK_STARTED=""
RESULT_WRITTEN=0

# ---------------------------------------------------------------------------------------------------------------------
# 출력: 표준 출력에는 단계마다 한 줄. 같은 줄을 verify-stdout.log(로그 위생 대조 대상)와 report.txt에 남긴다
# ---------------------------------------------------------------------------------------------------------------------
say() {
  printf '%s\n' "$*"
  printf '%s\n' "$*" >>"$STDOUT_LOG"
}
report() {
  printf '%s\n' "$*" >>"$REPORT"
}
say_report() {
  say "$*"
  report "$*"
}

# ---------------------------------------------------------------------------------------------------------------------
# 정리: 시작할 때(이전 실행이 SIGKILL로 trap 없이 끝났을 수 있음)와 끝날 때
# ---------------------------------------------------------------------------------------------------------------------
cleanup_stack() {
  host_stop_all || true
  host_pg_stop || true
  if docker_usable; then
    docker compose -p "$VERIFY_PROJECT" down -v --remove-orphans -t 3 >>"$VERIFY_LOG_DIR/cleanup.log" 2>&1 || true
  fi
}

remove_secrets() {
  rm -rf -- "$VERIFY_ENV_DIR" "$VERIFY_C_DIR" "$VERIFY_CERT_DIR" "$VERIFY_HOST_DIR/pgpass" "$VERIFY_DIR/postgres"
}

# shellcheck disable=SC2317,SC2329  # on_exit(trap)이 부른다(J345: 0.9.0 SC2317 · 0.10+ SC2329 오판)
collect_logs() {
  [ -n "$STACK_STARTED" ] || return 0
  if [ "$STACK_STARTED" = compose ] && docker_usable; then
    # 실패 조사용(위생 단언 P2는 따로 모은 compose.log를 본다)
    docker compose -p "$VERIFY_PROJECT" logs --no-color >"$VERIFY_LOG_DIR/compose-final.log" 2>&1 || true
    chmod 0600 "$VERIFY_LOG_DIR/compose-final.log" 2>/dev/null || true
  fi
}

# shellcheck disable=SC2317,SC2329  # trap이 부른다(J345: 0.9.0 SC2317 · 0.10+ SC2329 오판)
on_exit() {
  local rc=$?
  trap - EXIT
  set +e
  collect_logs
  if [ "${KEEP:-0}" = 1 ]; then
    say "verify: KEEP=1 — 스택을 남긴다(생성 비밀 · 비밀 목록은 지운다)"
  else
    cleanup_stack
  fi
  remove_secrets
  if [ "$RESULT_WRITTEN" -eq 0 ] && [ -f "$REPORT" ]; then
    local word=FAIL
    [ "$rc" -eq 2 ] && word=PREREQ
    [ "$rc" -eq 0 ] && word=PASS
    say_report "RESULT $word rc=$rc total=$(($(date +%s) - STARTED_AT))s"
  fi
  exit "$rc"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$VERIFY_DIR"
# 이전 실행의 보고를 남기지 않는다(이번 실행이 일찍 끝나도 낡은 RESULT 줄을 읽지 않게)
: >"$REPORT"
rm -rf -- "$VERIFY_LOG_DIR" "$FRAGMENT_DIR"
mkdir -p "$VERIFY_LOG_DIR" "$FRAGMENT_DIR"
: >"$STDOUT_LOG"
# 이전 실행의 잔여물(SIGKILL로 trap이 돌지 않았을 수 있다): 스택 · host 프로세스 · 생성 비밀 · 비밀 목록
cleanup_stack
remove_secrets

# ---------------------------------------------------------------------------------------------------------------------
# 작업 트리 상태(SEC-18): 상태 목록 · diff 통계 · diff 내용 해시 · 미추적 파일 내용 해시.
# 감사 기록 조각(aidlc/spaces/*/intents/*/audit/*.md)은 훅 · 엔진이 명령 밖에서 덧붙이는 기록이라 뺀다(code-summary J 후보)
# ---------------------------------------------------------------------------------------------------------------------
readonly TREE_EXCLUDE=':(exclude,glob)aidlc/spaces/*/intents/*/audit/**'
tree_state() {
  echo "## status"
  git status --porcelain=v1 -uall -- . "$TREE_EXCLUDE"
  echo "## diff-stat"
  git diff --stat -- . "$TREE_EXCLUDE"
  echo "## diff-sha256"
  git diff --binary HEAD -- . "$TREE_EXCLUDE" | sha256sum | cut -d' ' -f1
  echo "## untracked-sha256"
  git ls-files -z --others --exclude-standard -- . "$TREE_EXCLUDE" | LC_ALL=C sort -z |
    while IFS= read -r -d '' f; do
      if [ -f "$f" ] && [ ! -L "$f" ]; then
        printf '%s %s\n' "$(git hash-object -- "$f")" "$f"
      else
        printf 'special %s\n' "$f"
      fi
    done | sha256sum | cut -d' ' -f1
}

# ---------------------------------------------------------------------------------------------------------------------
# 단계 실행: run_stage <id> <기본 기한 초> <설명> -- <명령...>
#   기한 = min(기본 기한, 전체 남은 시간). 넘으면 timeout이 프로세스 그룹째 멈춘다(TERM, 5초 뒤 KILL).
#   반환: 0 통과, 1 실패 · 기한 초과, 2 전제 누락
# ---------------------------------------------------------------------------------------------------------------------
run_stage() {
  local id="$1" cap="$2" desc="$3"
  shift 3
  [ "${1:-}" = "--" ] && shift
  local now left limit
  now="$(date +%s)"
  left=$((MUJOONG_VERIFY_DEADLINE - now))
  if [ "$left" -le 0 ]; then
    say_report "$id TIMEOUT 0s 전체 기한 ${deadline_total}초를 넘겨 시작하지 못했다 — $desc"
    return 1
  fi
  limit="$cap"
  [ "$left" -lt "$limit" ] && limit="$left"
  local fragment="$FRAGMENT_DIR/$id"
  local stage_log="$VERIFY_LOG_DIR/$id.log"
  : >"$fragment"
  local t0 rc=0
  t0="$(date +%s)"
  MUJOONG_VERIFY_FRAGMENT="$fragment" timeout --kill-after=5 "$limit" "$@" >"$stage_log" 2>&1 </dev/null || rc=$?
  local dt=$(($(date +%s) - t0))
  if [ -s "$fragment" ]; then
    cat "$fragment" >>"$REPORT"
  fi
  case "$rc" in
    0)
      say_report "$id PASS ${dt}s $desc"
      return 0
      ;;
    124 | 137)
      say_report "$id TIMEOUT ${dt}s 기한 ${limit}초를 넘겼다 — $desc (logs/$id.log)"
      return 1
      ;;
    2)
      say_report "$id PREREQ ${dt}s 전제 누락 — $desc (logs/$id.log)"
      grep -E '^PREREQ ' "$stage_log" | head -n 3 | cut -c1-300 | while IFS= read -r line; do say_report "  $line"; done
      return 2
      ;;
    *)
      say_report "$id FAIL rc=$rc ${dt}s $desc (logs/$id.log)"
      grep -E '^FAIL ' "$stage_log" | head -n 5 | cut -c1-300 | while IFS= read -r line; do say_report "  $line"; done
      return 1
      ;;
  esac
}

# stage_or_exit <run_stage 인자...> — 실패하면 그 종료 코드로 끝낸다(다음 단계는 앞 단계에 기댄다)
stage_or_exit() {
  local rc=0
  run_stage "$@" || rc=$?
  if [ "$rc" -ne 0 ]; then
    exit "$rc"
  fi
}

prereq_exit() {
  say_report "PREREQ $*"
  exit 2
}

# ---------------------------------------------------------------------------------------------------------------------
# 전제 · 모드
# ---------------------------------------------------------------------------------------------------------------------

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  report "mode=$mode"
  prereq_exit "git 작업 트리가 아니다(작업 트리 비교 SEC-18 · 소스 지문 J134에 필요)"
fi

resolve_mode() {
  if [ "$mode" != auto ]; then
    echo "$mode"
    return
  fi
  if docker_usable && compose_version_ok; then
    local image
    image="$(pg_image)"
    if docker image inspect "$image" >/dev/null 2>&1 || docker pull -q "$image" >>"$VERIFY_LOG_DIR/mode.log" 2>&1; then
      echo compose
      return
    fi
  fi
  echo host
}
resolved="$(resolve_mode)"
readonly resolved
export MUJOONG_VERIFY_MODE="$resolved" MUJOONG_VERIFY_ENGINE="$engine"

report "mode=$resolved"
report "engine=$engine"
report "skip_a=$skip_a"
report "started=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
report "revision=$(git rev-parse --short=12 HEAD 2>/dev/null || echo none)"
say "verify: mode=$resolved engine=$engine skip_a=$skip_a 기한 ${deadline_total}초 (보고 build/verify/report.txt)"

missing=()
java21="$(find_java21 || true)"
[ -n "$java21" ] || missing+=("JDK 21(java)")
[ "$(node_major)" = 22 ] || missing+=("Node 22")
for tool in curl jq openssl sha256sum timeout setsid; do
  have "$tool" || missing+=("$tool")
done
[ -f infra/ci/source-fingerprint.sh ] || missing+=("infra/ci/source-fingerprint.sh")
if [ "$engine" -eq 0 ]; then
  have npm || missing+=("npm")
fi
if [ "$resolved" = compose ]; then
  docker_usable || missing+=("Docker 데몬")
  if docker_usable && ! compose_version_ok; then missing+=("Docker Compose v2.24.4 이상"); fi
else
  for bin in initdb pg_ctl postgres psql; do
    [ -x "$VERIFY_PG_BIN/$bin" ] || missing+=("PostgreSQL 16 $VERIFY_PG_BIN/$bin")
  done
  have unzip || missing+=("unzip")
  if [ "$(id -u)" -eq 0 ]; then
    have runuser || missing+=("runuser")
    id postgres >/dev/null 2>&1 || missing+=("postgres 사용자")
  fi
fi
if [ "${#missing[@]}" -gt 0 ]; then
  prereq_exit "전제 누락: ${missing[*]}"
fi
PATH="$(dirname "$java21"):$PATH"
export PATH

if [ "$resolved" = host ]; then
  # 사람 결정 11 · J133: host 모드 통과를 C안 통과로 인정하되, 확인하지 못한 항목은 보고에서 빼지 않는다(CI skeleton 몫)
  report "NOT_VERIFIED(host) 이미지 6종 빌드 · 라벨 · 비루트 · 시험 클래스 0(CI images · skeleton 작업)"
  report "NOT_VERIFIED(host) Compose 병합 설정 단언 B1(게시 포트 127.0.0.1:8443 하나 · 서비스별 비밀 이름 = 분배표)"
  report "NOT_VERIFIED(host) Nginx 렌더 설정 · nginx -t · 접근 로그 줄(입구는 tools/dev-edge 대역)"
  report "NOT_VERIFIED(host) 망 격리(edge · backend internal 망, 고정 IP, pg_hba 172.30.20.0/24)"
  report "NOT_VERIFIED(host) 프로필 가드의 이미지 기동 거부(host 모드는 같은 bootJar를 java -jar로 띄워 확인)"
fi

tree_state >"$TREE_START"
START_FINGERPRINT="$(bash infra/ci/source-fingerprint.sh)"
readonly START_FINGERPRINT
readonly SOURCE_MANIFEST_START="$VERIFY_DIR/source-manifest-start.txt"
bash infra/ci/source-fingerprint.sh --manifest >"$SOURCE_MANIFEST_START"
export MUJOONG_VERIFY_START_FINGERPRINT="$START_FINGERPRINT"
report "fingerprint=$START_FINGERPRINT"

# ---------------------------------------------------------------------------------------------------------------------
# 산출물 지문(J134 (가)): 전체 실행이 A 뒤에 적고, --engine이 대조한다
# ---------------------------------------------------------------------------------------------------------------------
readonly HOST_ARTIFACTS=(backend/core-api/build/libs/core-api.jar backend/realtime-activity/build/libs/realtime-activity.jar
  frontend/.next/standalone/server.js frontend/.next/BUILD_ID)

# shellcheck disable=SC2317,SC2329  # 아래 함수 · run_fn이 부른다(J345: 간접 호출을 0.9.0은 SC2317, 0.10+는 SC2329로 오판)
artifact_lines() {
  local f
  for f in "${HOST_ARTIFACTS[@]}"; do
    if [ -f "$f" ]; then
      printf 'artifact %s %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$f"
    else
      printf 'artifact missing %s\n' "$f"
    fi
  done
}

# shellcheck disable=SC2317,SC2329  # run_fn이 이름으로 부른다(J345: 간접 호출 오판)
record_fingerprint() {
  local now
  now="$(bash infra/ci/source-fingerprint.sh)"
  if [ "$now" != "$START_FINGERPRINT" ]; then
    local changed
    changed="$(diff <(cut -d' ' -f3- "$SOURCE_MANIFEST_START" | sort) <(bash infra/ci/source-fingerprint.sh --manifest | cut -d' ' -f3- | sort) |
      grep -E '^[<>]' | cut -c3- | sort -u | head -n 8 | paste -sd' ' - || true)"
    if [ -z "$changed" ]; then
      changed="$(diff "$SOURCE_MANIFEST_START" <(bash infra/ci/source-fingerprint.sh --manifest) | grep -E '^>' |
        cut -d' ' -f4- | head -n 8 | paste -sd' ' - || true)"
    fi
    echo "FAIL F: A 동안 소스 지문이 바뀌었다(실행 중 소스가 고쳐졌다): ${changed:-경로 미확인}" >&2
    return 1
  fi
  if artifact_lines | grep -q '^artifact missing '; then
    echo "FAIL F: A가 만들어야 할 산출물이 없다: $(artifact_lines | grep '^artifact missing ' | cut -d' ' -f3 | paste -sd' ' -)" >&2
    return 1
  fi
  {
    echo "source $START_FINGERPRINT"
    echo "revision $(git rev-parse HEAD)"
    artifact_lines
  } >"$VERIFY_FINGERPRINT_FILE.tmp"
  mv -f "$VERIFY_FINGERPRINT_FILE.tmp" "$VERIFY_FINGERPRINT_FILE"
  echo "PASS F 소스 지문 · 산출물 해시 기록(build/verify/fingerprint)"
}

# shellcheck disable=SC2317,SC2329  # run_fn이 이름으로 부른다(J345: 간접 호출 오판)
engine_check() {
  local problems=()
  if [ "$resolved" = compose ]; then
    local name ref label
    for name in "${VERIFY_STACK_IMAGES[@]}"; do
      ref="$VERIFY_REGISTRY/mujoong-$name:$VERIFY_TAG"
      if ! label="$(docker image inspect "$ref" --format '{{index .Config.Labels "kr.mujoong.source-fingerprint"}}' 2>/dev/null)"; then
        problems+=("$ref 없음")
      elif [ "$label" != "$START_FINGERPRINT" ]; then
        problems+=("$ref 지문 다름")
      fi
    done
  else
    if [ ! -f "$VERIFY_FINGERPRINT_FILE" ]; then
      problems+=("build/verify/fingerprint 없음")
    else
      grep -qxF "source $START_FINGERPRINT" "$VERIFY_FINGERPRINT_FILE" || problems+=("소스 지문 다름")
      local expected actual
      expected="$(grep '^artifact ' "$VERIFY_FINGERPRINT_FILE" || true)"
      actual="$(artifact_lines)"
      [ "$expected" = "$actual" ] || problems+=("bootJar · standalone 산출물이 기록과 다름")
    fi
  fi
  [ -f tools/node_modules/ws/package.json ] || problems+=("tools/node_modules 없음(npm ci)")
  if [ "${#problems[@]}" -gt 0 ]; then
    echo "FAIL E0: 미리 만든 산출물을 쓸 수 없다 — ${problems[*]}. 먼저 bash scripts/verify.sh 전체 실행으로 만든다" >&2
    return 1
  fi
  echo "PASS E0 미리 만든 산출물의 소스 지문이 지금과 같다"
}

# run_fn <id> <설명> <함수> — 짧은 내부 확인(외부 대기 없음)
run_fn() {
  local id="$1" desc="$2" fn="$3" rc=0 t0
  t0="$(date +%s)"
  "$fn" >"$VERIFY_LOG_DIR/$id.log" 2>&1 || rc=$?
  local dt=$(($(date +%s) - t0))
  if [ "$rc" -eq 0 ]; then
    say_report "$id PASS ${dt}s $desc"
  else
    say_report "$id FAIL rc=$rc ${dt}s $desc (logs/$id.log)"
    grep -E '^FAIL ' "$VERIFY_LOG_DIR/$id.log" | head -n 3 | cut -c1-300 | while IFS= read -r line; do say_report "  $line"; done
    exit 1
  fi
}

# shellcheck disable=SC2317,SC2329  # run_fn이 이름으로 부른다(J345: 간접 호출 오판)
tree_compare() {
  tree_state >"$TREE_END"
  if cmp -s "$TREE_START" "$TREE_END"; then
    echo "PASS T 작업 트리가 시작과 같다"
    return 0
  fi
  # 경로 목록만 낸다(내용 · 값은 내지 않는다). 상태 목록이 같으면 내용만 바뀐 것이다
  local changed
  changed="$(diff <(sed -n '/^## status$/,/^## diff-stat$/p' "$TREE_START") <(sed -n '/^## status$/,/^## diff-stat$/p' "$TREE_END") |
    grep -E '^[<>] ' | cut -c3- | head -n 10 | paste -sd';' - || true)"
  echo "FAIL T: 작업 트리가 실행 중 바뀌었다: ${changed:-경로 목록은 같고 추적 · 미추적 파일 내용이 바뀌었다}" >&2
  return 1
}

# ---------------------------------------------------------------------------------------------------------------------
# 실행
# ---------------------------------------------------------------------------------------------------------------------
if [ "$engine" -eq 1 ]; then
  run_fn E0 "미리 만든 산출물 지문 대조(J134 (가))" engine_check
elif [ "$skip_a" -eq 1 ]; then
  # 스켈레톤 경량: 회귀는 ci-gate · 수동 전체 실행이 맡는다. 건너뛴 것을 보고에서 빼지 않는다
  report "NOT_VERIFIED(skip-a) A1 backend 전체 회귀 · A2 frontend eslint · vitest · A3 tools vitest · A4 비밀 스캔"
  stage_or_exit A0 1800 "기동 산출물(bootJar · 시험 클래스 0 · next build · tools npm ci, 시험 없음)" --     bash scripts/verify/a-checks.sh artifacts
  run_fn F "소스 지문 · 산출물 해시 기록(J134)" record_fingerprint
else
  db_backend=auto
  [ "$resolved" = host ] && db_backend=local
  stage_or_exit A1 2400 "backend 회귀(gradle clean check verifyBootJarHasNoTestClasses, DB 시험 백엔드 $db_backend)" -- \
    bash scripts/verify/a-checks.sh backend --db "$db_backend"
  stage_or_exit A2 1200 "frontend(npm ci · eslint · vitest · next build)" -- bash scripts/verify/a-checks.sh frontend
  stage_or_exit A3 600 "tools(npm ci · vitest)" -- bash scripts/verify/a-checks.sh tools
  stage_or_exit A4 300 "비밀 스캔 ① · 무시 규칙 ②" -- bash scripts/verify/a-secrets.sh
  run_fn F "소스 지문 · 산출물 해시 기록(J134)" record_fingerprint
fi

engine_flag=()
[ "$engine" -eq 1 ] && engine_flag=(--engine)
STACK_STARTED="$resolved"
if [ "$resolved" = compose ]; then
  stage_or_exit B 1500 "compose 스택(이미지 · 병합 설정 · migrate · 시드 · 프로필 가드 · 기동 · 3종 호출 · SD-3)" -- \
    bash scripts/verify/b-stack.sh "${engine_flag[@]}"
else
  stage_or_exit B 600 "host 스택(로컬 클러스터 · migrate · 시드 · 프로필 가드 · 기동 · 3종 호출 · SD-3)" -- \
    bash scripts/verify/b-host.sh "${engine_flag[@]}"
fi
stage_or_exit C 300 "기기 시뮬레이터 한 턴(C1 ~ C12)" -- bash scripts/verify/c-skeleton-turn.sh --mode "$resolved"
stage_or_exit P1 180 "교차 스키마 쓰기 · CREATE TABLE 42501(B1 ②)" -- \
  bash scripts/verify/assert-schema-isolation.sh --mode "$resolved"
stage_or_exit P2 180 "로그 위생(B1 ③)" -- bash scripts/verify/assert-log-hygiene.sh --mode "$resolved" \
  --stdout-log "$STDOUT_LOG" --report "$REPORT"
stage_or_exit P3 300 "비밀 값이 추적 · 무시되지 않은 파일에 0건(a-secrets ③)" -- \
  bash scripts/verify/a-secrets.sh --values "$VERIFY_SECRETS_LIST" --exclude-values "$VERIFY_C_DIR/fixture-texts.list"
run_fn T "작업 트리 시작 · 끝 비교(SEC-18)" tree_compare

RESULT_WRITTEN=1
say_report "RESULT PASS rc=0 total=$(($(date +%s) - STARTED_AT))s"
exit 0
