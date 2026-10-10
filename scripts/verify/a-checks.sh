#!/usr/bin/env bash
# 무중 검증 명령 A — 정적 · 단위 · 통합 (U1 3.5 Step 49 · 계획 ## 검증 명령(C안) A 1 ~ 3 · J107 · J131 · J132 · J137)
#
# 쓰는 법: bash scripts/verify/a-checks.sh backend [--db auto|docker|local] | frontend | tools
#   backend   cd backend && ./gradlew --no-daemon clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=<db>
#             저장소 전체 회귀(유닛 범위 명령이 아님): 세 모듈 단위 · 통합 · ArchUnit · 설정 키 대조 · 모듈 전체 JaCoCo 80% LINE.
#             끝나면 bootJar 두 개가 있는지 보고, 시험 결과 XML에서 시험 수 · 실패 · 건너뜀을 센다.
#   frontend  cd frontend && npm ci && npx --no-install eslint . && npx --no-install vitest run tests/u1 --coverage &&
#             npx --no-install next build. 그 뒤 .next/static을 .next/standalone/.next/static에 복사한다(host 모드 web ·
#             이미지 빌드가 같은 standalone을 쓴다. Next.js standalone 출력은 정적 자원을 복사하지 않는다).
#   tools     cd tools && npm ci && npx --no-install vitest run tests/u1
# 커버리지 하한(80%)은 각 도구의 설정이 판정한다(JaCoCo check · Vitest thresholds). 이 스크립트는 하한을 바꾸지 않는다.
# 출력: 도구 출력은 그대로(scripts/verify.sh가 build/verify/logs/A*.log로 보낸다) + 끝에 PASS · NOTE 줄.
# 종료 코드: 0 통과, 1 실패, 2 전제 누락, 64 인자 오류

set -Eeuo pipefail
# shellcheck source=scripts/verify/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$VERIFY_ROOT"

part="${1:-}"
shift || true
db=auto
while [ "$#" -gt 0 ]; do
  case "$1" in
    --db) db="${2:-}"; shift ;;
    *) echo "a-checks: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done
case "$db" in auto | docker | local) ;; *) echo "a-checks: --db는 auto · docker · local" >&2; exit 64 ;; esac

backend() {
  [ -x backend/gradlew ] || die_prereq "backend/gradlew가 없다"
  find_java21 >/dev/null || die_prereq "JDK 21이 없다(Gradle 툴체인, J139)"
  log "gradle clean check verifyBootJarHasNoTestClasses (DB 시험 백엔드 $db)"
  (cd backend && ./gradlew --no-daemon --console=plain clean check verifyBootJarHasNoTestClasses "-Pmujoong.test.db=$db")
  local jar missing=0
  for jar in backend/core-api/build/libs/core-api.jar backend/realtime-activity/build/libs/realtime-activity.jar; do
    if [ ! -f "$jar" ]; then
      fail A1.bootjar "$jar가 없다(verifyBootJarHasNoTestClasses가 bootJar를 만들지 않았다)"
      missing=1
    fi
  done
  [ "$missing" -eq 0 ] && pass A1.bootjar "bootJar 두 개 · 시험 클래스 0"
  # 시험 수(JUnit XML). 값은 숫자뿐이다
  local totals
  totals="$(find backend -path '*/build/test-results/test/*.xml' -print0 2>/dev/null | xargs -0 -r cat |
    grep -oE '<testsuite [^>]*' | awk '
      { t = f = e = s = 0
        if (match($0, /tests="[0-9]+"/)) t = substr($0, RSTART + 7, RLENGTH - 8)
        if (match($0, /failures="[0-9]+"/)) f = substr($0, RSTART + 10, RLENGTH - 11)
        if (match($0, /errors="[0-9]+"/)) e = substr($0, RSTART + 8, RLENGTH - 9)
        if (match($0, /skipped="[0-9]+"/)) s = substr($0, RSTART + 9, RLENGTH - 10)
        T += t; F += f + e; S += s }
      END { printf "%d %d %d", T, F, S }')"
  local tests fails skipped
  read -r tests fails skipped <<<"${totals:-0 0 0}"
  if [ "${tests:-0}" -eq 0 ]; then
    fail A1.tests "시험 결과 XML에 시험이 0건이다"
  elif [ "${fails:-0}" -ne 0 ]; then
    fail A1.tests "시험 실패 ${fails}건"
  else
    pass A1.tests "시험 ${tests}건 · 실패 0 · 건너뜀 ${skipped} · DB 시험 백엔드 요청 $db · JaCoCo 80% LINE(check)"
  fi
  finish_asserts
}

frontend() {
  have npm || die_prereq "npm이 없다"
  [ "$(node_major)" = 22 ] || die_prereq "Node 22가 아니다"
  log "frontend: npm ci"
  (cd frontend && npm ci --no-audit --no-fund)
  log "frontend: eslint"
  (cd frontend && npx --no-install eslint .)
  log "frontend: vitest run tests/u1 --coverage"
  (cd frontend && npx --no-install vitest run tests/u1 --coverage)
  log "frontend: next build"
  (cd frontend && NEXT_TELEMETRY_DISABLED=1 npx --no-install next build)
  [ -f frontend/.next/standalone/server.js ] || die_assert A2.standalone "next build가 .next/standalone/server.js를 만들지 않았다"
  rm -rf frontend/.next/standalone/.next/static
  cp -a frontend/.next/static frontend/.next/standalone/.next/static
  if [ -d frontend/public ]; then
    rm -rf frontend/.next/standalone/public
    cp -a frontend/public frontend/.next/standalone/public
  fi
  pass A2.build "eslint · vitest(80% 줄) · next build · standalone 정적 자원"
  local summary=frontend/coverage/coverage-summary.json
  if [ -f "$summary" ]; then
    note A2.coverage "줄 커버리지 $(jq -r '.total.lines.pct' "$summary")%(src/**)"
  fi
}

tools() {
  have npm || die_prereq "npm이 없다"
  [ "$(node_major)" = 22 ] || die_prereq "Node 22가 아니다"
  log "tools: npm ci"
  (cd tools && npm ci --no-audit --no-fund)
  log "tools: vitest run tests/u1"
  (cd tools && npx --no-install vitest run tests/u1)
  pass A3.tools "tools 시험 통과"
}

case "$part" in
  backend) backend ;;
  frontend) frontend ;;
  tools) tools ;;
  *) echo "a-checks: backend · frontend · tools 중 하나를 준다" >&2; exit 64 ;;
esac
