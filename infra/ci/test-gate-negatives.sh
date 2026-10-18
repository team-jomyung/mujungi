#!/usr/bin/env bash
# 무중 관문 음성 대조 CD-1 (U1 3.5 Step 49 · cicd ## 4 CD-1 · SEC-10 · NFR9.6 · NFR11.1 · cicd 2.7.2)
#
# 관문이 '실제로 막는지'를 본다. 관문마다 먼저 깨끗한 입력에서 통과(양성 대조)하고, 실행 때 임시 디렉터리에 만든 위반 샘플에서
# 실패(음성 대조)해야 한다. 양성 대조가 없으면 도구 오류로 실패해도 음성 대조가 통과한 것처럼 보이므로 둘 다 본다.
#   1 secrets     scripts/verify/a-secrets.sh  — 임시 git 저장소(실제 .gitignore 사본)에 실행 때 만든 가짜 키(AWS 키 id 모양 ·
#                                                개인 키 머리글)를 넣으면 실패. 가짜 값은 이 스크립트에 글자 그대로 쓰지 않는다
#   2 compose     infra/bin/infra-validate --only compose-invariants — compose.demo.yml 사본의 web에 게시 포트를 더하면 실패
#   3 nginx       infra/bin/infra-validate --only nginx — 템플릿 사본에 모르는 지시어를 넣으면 nginx -t가 실패
#   4 migrations  infra/bin/infra-validate --only migrations — 파괴적 마이그레이션(DROP COLUMN · RENAME)이 든 사본이면 실패
#   5 eslint      frontend ESLint — 기기 영역 파일(표준 입력, 가상 경로 src/device/…)이 보호자 영역을 import하면
#                 no-restricted-imports · import/no-restricted-paths로 실패(NFR11.1 · PD-15)
# 취약 의존성 · 커버리지 하락 · ci-gate 경로 필터 동작은 첫 Actions 실행(3.6)에서 확인한다(계획 Step 49).
#
# 쓰는 법: bash infra/ci/test-gate-negatives.sh
# 출력: 관문마다 `PASS <관문>` · `FAIL <관문>: <사유>` · `MISSING <관문>: <도구>`. 샘플 내용 · 값은 출력하지 않는다.
# 종료 코드: 0 모두 기대대로, 1 하나라도 기대와 다름, 2 FAIL은 없지만 도구가 없어 못 돈 관문이 있음(Docker 없음 등)

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT
cd "$ROOT"

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fails=0
missing=0
pass() { echo "PASS $1 ${2:-}"; }
fail() {
  echo "FAIL $1: $2"
  fails=$((fails + 1))
}
miss() {
  echo "MISSING $1: $2"
  missing=$((missing + 1))
}
have() { command -v "$1" >/dev/null 2>&1; }
docker_ok() { have docker && docker info >/dev/null 2>&1; }

# expect <관문> <기대 rc> <로그 파일> <명령...> — 종료 코드가 기대와 같은가
run_rc() {
  local log="$1"
  shift
  local rc=0
  "$@" >"$log" 2>&1 || rc=$?
  echo "$rc"
}

# ---- 1 secrets ------------------------------------------------------------------------------------------------------
gate_secrets() {
  local repo="$work/secrets-repo" rc
  mkdir -p "$repo"
  cp "$ROOT/.gitignore" "$repo/.gitignore"
  printf 'clean sample\n' >"$repo/README.md"
  git -C "$repo" init -q
  git -C "$repo" add -A
  rc="$(run_rc "$work/s0.log" bash "$ROOT/scripts/verify/a-secrets.sh" --root "$repo")"
  if [ "$rc" != 0 ]; then
    fail secrets "양성 대조(깨끗한 저장소)가 통과하지 않았다(rc=$rc)"
    return
  fi
  # 가짜 키를 실행 때 만든다(AWS 키 id 모양: 접두 + 대문자 · 숫자 16자)
  local fake_id
  fake_id="AKIA$(head -c 64 /dev/urandom | tr -dc 'A-Z0-9' | head -c 16)"
  [ "${#fake_id}" -eq 20 ] || fake_id="AKIA$(printf '%016d' "$RANDOM")"
  printf 'aws_access_key_id = %s\n' "$fake_id" >"$repo/config.ini"
  rc="$(run_rc "$work/s1.log" bash "$ROOT/scripts/verify/a-secrets.sh" --root "$repo")"
  if [ "$rc" != 1 ] || ! grep -q 'config.ini' "$work/s1.log"; then
    fail secrets "가짜 AWS 키 id 샘플을 막지 못했다(rc=$rc)"
    return
  fi
  rm -f "$repo/config.ini"
  { printf -- '-----BEGIN %s-----\n' "PRIVATE KEY"; head -c 48 /dev/urandom | base64; printf -- '-----END %s-----\n' "PRIVATE KEY"; } \
    >"$repo/notes.txt"
  rc="$(run_rc "$work/s2.log" bash "$ROOT/scripts/verify/a-secrets.sh" --root "$repo")"
  if [ "$rc" != 1 ] || ! grep -q 'notes.txt' "$work/s2.log"; then
    fail secrets "개인 키 머리글 샘플을 막지 못했다(rc=$rc)"
    return
  fi
  if grep -qF "$fake_id" "$work/s1.log" "$work/s2.log"; then
    fail secrets "a-secrets 출력에 걸린 값이 나왔다"
    return
  fi
  pass secrets "깨끗한 저장소 통과 · 가짜 AWS 키 id · 개인 키 머리글 거부(값 미출력)"
}

# ---- 2 compose ------------------------------------------------------------------------------------------------------
gate_compose() {
  if ! docker_ok; then
    miss compose "Docker 데몬(docker compose config)"
    return
  fi
  local rc sample="$work/compose-sample/compose.demo.yml"
  rc="$(run_rc "$work/c0.log" bash "$ROOT/infra/bin/infra-validate" --only compose-invariants)"
  if [ "$rc" != 0 ]; then
    fail compose "양성 대조(compose.demo.yml)가 통과하지 않았다(rc=$rc)"
    return
  fi
  mkdir -p "$(dirname "$sample")"
  # web 서비스에 호스트 게시 포트를 더한다(불변식: 게시 포트는 nginx 80 · 443뿐)
  awk '{ print } /^  web:$/ && !done { print "    ports:"; print "      - \"3000:3000\""; done = 1 }' \
    "$ROOT/infra/compose.demo.yml" >"$sample"
  if cmp -s "$sample" "$ROOT/infra/compose.demo.yml"; then
    fail compose "샘플을 만들지 못했다(web 서비스 줄을 찾지 못함)"
    return
  fi
  rc="$(run_rc "$work/c1.log" bash "$ROOT/infra/bin/infra-validate" --only compose-invariants --compose "$sample")"
  if [ "$rc" = 1 ] && grep -q '^FAIL compose-invariants' "$work/c1.log"; then
    pass compose "compose.demo.yml 통과 · web 게시 포트 추가 거부"
  else
    fail compose "게시 포트 추가 샘플을 막지 못했다(rc=$rc)"
  fi
}

# ---- 3 nginx --------------------------------------------------------------------------------------------------------
gate_nginx() {
  if ! docker_ok; then
    miss nginx "Docker 데몬(컨테이너 안 nginx -t)"
    return
  fi
  local rc src="$work/nginx-src"
  rc="$(run_rc "$work/n0.log" bash "$ROOT/infra/bin/infra-validate" --only nginx)"
  if [ "$rc" != 0 ]; then
    fail nginx "양성 대조(저장소 템플릿)가 통과하지 않았다(rc=$rc)"
    return
  fi
  cp -a "$ROOT/infra/nginx" "$src"
  # http 블록 첫머리에 존재하지 않는 지시어를 넣는다
  awk '{ print } /^http \{/ && !done { print "    mujoong_gate_negative_unknown_directive on;"; done = 1 }' \
    "$ROOT/infra/nginx/nginx.conf.tmpl" >"$src/nginx.conf.tmpl"
  if cmp -s "$src/nginx.conf.tmpl" "$ROOT/infra/nginx/nginx.conf.tmpl"; then
    fail nginx "샘플을 만들지 못했다(http 블록을 찾지 못함)"
    return
  fi
  rc="$(run_rc "$work/n1.log" bash "$ROOT/infra/bin/infra-validate" --only nginx --nginx-src "$src")"
  if [ "$rc" = 1 ] && grep -q '^FAIL nginx' "$work/n1.log"; then
    pass nginx "저장소 템플릿 통과 · 모르는 지시어 nginx -t 거부"
  else
    fail nginx "틀린 Nginx 설정 샘플을 막지 못했다(rc=$rc)"
  fi
}

# ---- 4 migrations ---------------------------------------------------------------------------------------------------
gate_migrations() {
  if ! have python3; then
    miss migrations "python3"
    return
  fi
  local rc core=backend/core-api/src/main/resources/db/migration/core
  local realtime=backend/realtime-activity/src/main/resources/db/migration/realtime
  rc="$(run_rc "$work/m0.log" bash "$ROOT/infra/bin/infra-validate" --only migrations --migrations "$ROOT/$core" \
    --migrations "$ROOT/$realtime")"
  if [ "$rc" != 0 ]; then
    fail migrations "양성 대조(두 마이그레이션 위치)가 통과하지 않았다(rc=$rc)"
    return
  fi
  local sample="$work/migration-sample"
  cp -a "$ROOT/$core" "$sample"
  cat >"$sample/V900__gate_negative.sql" <<'SQL'
-- 관문 음성 대조 샘플(실행 때만 존재): expand 단계에서 금지된 파괴적 변경
ALTER TABLE core.core_device DROP COLUMN code_prefix;
ALTER TABLE core.core_child RENAME TO core_child_old;
SQL
  rc="$(run_rc "$work/m1.log" bash "$ROOT/infra/bin/infra-validate" --only migrations --migrations "$sample")"
  if [ "$rc" = 1 ] && grep -q '^FAIL migrations' "$work/m1.log"; then
    pass migrations "두 위치 통과 · DROP COLUMN · RENAME 거부"
  else
    fail migrations "파괴적 마이그레이션 샘플을 막지 못했다(rc=$rc)"
  fi
}

# ---- 5 eslint -------------------------------------------------------------------------------------------------------
gate_eslint() {
  if [ ! -x "$ROOT/frontend/node_modules/.bin/eslint" ]; then
    miss eslint "frontend/node_modules(cd frontend && npm ci)"
    return
  fi
  local rc
  rc="$(cd "$ROOT/frontend" && printf 'export const gateNegativeSample = 1;\n' |
    run_rc "$work/e0.log" npx --no-install eslint --stdin --stdin-filename src/device/gate-negative-sample.ts)"
  if [ "$rc" != 0 ]; then
    fail eslint "양성 대조(기기 영역의 경계 안 파일)가 통과하지 않았다(rc=$rc)"
    return
  fi
  rc="$(cd "$ROOT/frontend" &&
    printf 'import { ParentProviders } from "@/parent/common/ParentProviders";\n\nexport const gateNegativeSample = ParentProviders;\n' |
    run_rc "$work/e1.log" npx --no-install eslint --stdin --stdin-filename src/device/gate-negative-sample.ts)"
  if [ "$rc" = 1 ] && grep -qE 'no-restricted-imports|import/no-restricted-paths' "$work/e1.log"; then
    pass eslint "경계 안 파일 통과 · 기기 → 보호자 import 거부(NFR11.1)"
  else
    fail eslint "경계를 넘는 import 샘플을 막지 못했다(rc=$rc)"
  fi
}

gate_secrets
gate_compose
gate_nginx
gate_migrations
gate_eslint

if [ "$fails" -gt 0 ]; then
  echo "test-gate-negatives: FAIL ${fails}건"
  exit 1
fi
if [ "$missing" -gt 0 ]; then
  echo "test-gate-negatives: 도구가 없어 못 돈 관문 ${missing}개(통과로 보고하지 않는다)"
  exit 2
fi
echo "test-gate-negatives: 모든 관문이 깨끗한 입력은 통과시키고 위반 샘플은 막았다"
