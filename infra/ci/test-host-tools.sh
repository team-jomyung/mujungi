#!/usr/bin/env bash
# 호스트 도구 시험 — 가짜 aws로 render-env · D3 프로필 확인, 점검 도우미 (U1 3.5 Step 48 · cicd 2.5.1 · 4절 CD-7 · CD-11 · monitoring 4절 MO-3 · MO-4)
#
# 실제 AWS를 부르지 않는다. 임시 디렉터리의 가짜 `aws`(Parameter Store 응답 흉내)를 PATH 앞에 두고, 값은 실행 때 무작위로 만든다(J127).
#   render-env(infra/bin/render-env)
#     - 경로 조회 · 이름 조회 둘 다 서비스별 파일을 분배표(infra-spec 3.7)대로 만든다: 파일 0600 · 디렉터리 0700, 받지 않는 비밀 0건,
#       데이터 소스 비밀번호 매핑, GOOGLE_OAUTH_CLIENT_ID · SECRET 파라미터가 없으면 빈 변수(CD-11), 구글 이름은 core-api · web이 같고
#       redirect는 허용 origin + /parent/login/callback(U2 리뷰 3), 구글 값 하나만 있으면 거부, 출력에 값이 없다
#     - 필수 파라미터 없음 · localhost · .invalid 호스트 · 짧은 내부 토큰 · 렌더에 위험한 문자 · origin 불일치는 종료 코드 1이고
#       이미 있는 파일을 바꾸지 않는다(D3 실패 · 구 버전 유지). aws 호출 실패 · aws 없음은 종료 코드 2
#   D3 프로필 확인(infra/bin/infra-validate --only demo-profile, CD-7): 렌더된 env로 통과, env 파일의 시험 로그인 켬 · demo가 아닌
#     프로필 줄은 실패
#   점검 도우미(Step 47): check-since-last(MO-4 합성 로그) · log-hygiene-check(MO-3 금지 패턴 · 비밀 값 0건)
#     · 사건 코드별 attrs 허용 표가 자바 정본(EventCode · AttrKey)과 같은지(python3)
#     · turn-metrics(MO-4 · NFR5.4 · OD-8, J348): 한 턴 로그로 NFR5.1 · 5.2 · 5.3 값, 표본 규칙(100 이상 p95 · 10 ~ 99 비율 · 10 미만 보류),
#       경보선(A-13), 기기 시계 추정, 미관측 사유, 보존 폴더 · since, 인자 오류
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
  [MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET]="$(rand 32)"
  [SERVICE_HOST]="svc.example.net" [MUJOONG_SECURITY_ALLOWED_ORIGIN]="https://svc.example.net"
  [ACME_CONTACT_EMAIL]="ops@example.net"
  # U13 BR9.1: demo는 제브 키 · 주소 · 모델이 필수다. 키는 실행 때 만든 무작위 값이고 주소는 예약 도메인(RFC 2606)이다(접속하지 않는다)
  [JEV_API_KEY]="$(rand 32)" [JEV_BASE_URL]="https://jev.example.net" [JEV_MODEL]="jev-guardrail-test-1"
  [NEO4J_AUTH]="neo4j/$(rand 24)"
)
readonly SECRET_KEYS=(POSTGRES_PASSWORD DB_OWNER_PASSWORD DB_CORE_PASSWORD DB_REALTIME_PASSWORD DB_BACKUP_PASSWORD
  INTERNAL_SERVICE_TOKEN MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET JEV_API_KEY NEO4J_AUTH)
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
  [neo4j.env]="NEO4J_AUTH"
  [core-api.env]="GOOGLE_OAUTH_CLIENT_ID GOOGLE_OAUTH_CLIENT_SECRET GOOGLE_OAUTH_REDIRECT_URI INTERNAL_SERVICE_TOKEN MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET MUJOONG_SECURITY_ALLOWED_ORIGIN NEO4J_AUTH OPENAI_API_KEY SPRING_DATASOURCE_PASSWORD"
  [realtime.env]="INTERNAL_SERVICE_TOKEN JEV_API_KEY JEV_BASE_URL JEV_MODEL MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET MUJOONG_SECURITY_ALLOWED_ORIGIN OPENAI_API_KEY SPRING_DATASOURCE_PASSWORD"
  [web.env]="GOOGLE_OAUTH_CLIENT_ID GOOGLE_OAUTH_REDIRECT_URI MUJOONG_SECURITY_ALLOWED_ORIGIN"
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
  [ "$(value_of "$dir/neo4j.env" NEO4J_AUTH)" = "${P[NEO4J_AUTH]}" ] || problems+=("neo4j credential mapping")
  [ "$(value_of "$dir/core-api.env" NEO4J_AUTH)" = "${P[NEO4J_AUTH]}" ] || problems+=("core graph credential mapping")
  [ "$(value_of "$dir/core-api.env" GOOGLE_OAUTH_CLIENT_SECRET)" = "" ] || problems+=("GOOGLE_OAUTH_CLIENT_SECRET이 빈 변수가 아니다")
  # U8(SD3): 선택 비밀 OPENAI_API_KEY는 파라미터가 없으면 빈 변수다(앱이 실제 어댑터를 끈다)
  for f in core-api.env realtime.env; do
    [ "$(value_of "$dir/$f" OPENAI_API_KEY)" = "" ] || problems+=("$f OPENAI_API_KEY가 빈 변수가 아니다")
  done
  # U13 BR9.1 · BR9.3: JEV_API_KEY · JEV_BASE_URL · JEV_MODEL은 demo 필수라 realtime.env에만 값이 들어간다
  [ "$(value_of "$dir/realtime.env" JEV_API_KEY)" = "${P[JEV_API_KEY]}" ] || problems+=("realtime JEV_API_KEY 매핑")
  [ "$(value_of "$dir/realtime.env" JEV_BASE_URL)" = "${P[JEV_BASE_URL]}" ] || problems+=("realtime JEV_BASE_URL 매핑")
  [ "$(value_of "$dir/realtime.env" JEV_MODEL)" = "${P[JEV_MODEL]}" ] || problems+=("realtime JEV_MODEL 매핑")
  grep -qE '^JEV_' "$dir/core-api.env" && problems+=("core-api.env에 JEV 변수가 있다")
  # U2 리뷰 3: Core와 web이 같은 이름을 받고 redirect는 허용 origin의 01 콜백 경로다(구글 콘솔에 같은 주소를 등록한다)
  for f in core-api.env web.env; do
    [ "$(value_of "$dir/$f" GOOGLE_OAUTH_CLIENT_ID)" = "" ] || problems+=("$f GOOGLE_OAUTH_CLIENT_ID가 빈 변수가 아니다")
    [ "$(value_of "$dir/$f" GOOGLE_OAUTH_REDIRECT_URI)" = "${P[MUJOONG_SECURITY_ALLOWED_ORIGIN]}/parent/login/callback" ] \
      || problems+=("$f GOOGLE_OAUTH_REDIRECT_URI가 허용 origin의 콜백 주소가 아니다")
  done
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

# ---- render-env: 구글 OAuth 값이 있으면 Core · web 둘 다 같은 이름으로 받는다(U2 리뷰 3) ----------------------------
google_id="u2-$(rand 9).apps.example.net"
write_params "$work/p-google" "GOOGLE_OAUTH_CLIENT_ID=$google_id" "GOOGLE_OAUTH_CLIENT_SECRET=$(rand 24)"
render "$work/p-google" "$work/env-google"
google_problems=()
if [ "$rc" -ne 0 ]; then
  google_problems+=("종료 코드 $rc")
else
  for f in core-api.env web.env; do
    [ "$(value_of "$work/env-google/$f" GOOGLE_OAUTH_CLIENT_ID)" = "$google_id" ] || google_problems+=("$f client id")
  done
  grep -q '^GOOGLE_OAUTH_CLIENT_SECRET=' "$work/env-google/web.env" && google_problems+=("web.env에 client secret이 있다")
  [ -n "$(value_of "$work/env-google/core-api.env" GOOGLE_OAUTH_CLIENT_SECRET)" ] || google_problems+=("core-api client secret 없음")
  grep -qF -- "$(grep '^GOOGLE_OAUTH_CLIENT_SECRET' "$work/p-google" | cut -f2)" "$work/render.out" && google_problems+=("출력에 비밀 값")
fi
if [ "${#google_problems[@]}" -gt 0 ]; then not_ok "render-env-google-oauth" "${google_problems[*]}"; else ok "render-env-google-oauth"; fi

# ---- render-env: U8 AI 키는 앱별 최소 범위로 나뉜다(SD3 — Luna는 core-api · realtime, 제브는 realtime만) ----------------
openai_key="$(rand 32)"
jev_key="$(rand 32)"
write_params "$work/p-ai" -GOOGLE_OAUTH_CLIENT_SECRET "OPENAI_API_KEY=$openai_key" "JEV_API_KEY=$jev_key"
render "$work/p-ai" "$work/env-ai"
ai_problems=()
if [ "$rc" -ne 0 ]; then
  ai_problems+=("종료 코드 $rc")
else
  for f in core-api.env realtime.env; do
    [ "$(value_of "$work/env-ai/$f" OPENAI_API_KEY)" = "$openai_key" ] || ai_problems+=("$f OPENAI_API_KEY")
  done
  [ "$(value_of "$work/env-ai/realtime.env" JEV_API_KEY)" = "$jev_key" ] || ai_problems+=("realtime JEV_API_KEY")
  grep -q '^JEV_API_KEY=' "$work/env-ai/core-api.env" && ai_problems+=("core-api.env에 JEV_API_KEY가 있다")
  for f in web.env nginx.env certbot.env backup.env postgres.env migrate.env; do
    grep -qE '^(OPENAI_API_KEY|JEV_API_KEY|JEV_BASE_URL|JEV_MODEL)=' "$work/env-ai/$f" && ai_problems+=("$f에 AI 키 · 제브 설정이 있다")
  done
  grep -qF -- "$openai_key" "$work/render.out" && ai_problems+=("출력에 OPENAI_API_KEY 값")
  grep -qF -- "$jev_key" "$work/render.out" && ai_problems+=("출력에 JEV_API_KEY 값")
fi
if [ "${#ai_problems[@]}" -gt 0 ]; then not_ok "render-env-u8-ai-keys" "${ai_problems[*]}"; else ok "render-env-u8-ai-keys"; fi

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
write_params "$work/p-missing" -MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET
expect_reject "render-env-missing-required" 1 "$work/p-missing"
write_params "$work/p-graph-missing" -NEO4J_AUTH
expect_reject "render-env-neo4j-auth-missing" 1 "$work/p-graph-missing"
write_params "$work/p-localhost" SERVICE_HOST=localhost MUJOONG_SECURITY_ALLOWED_ORIGIN=https://localhost
expect_reject "render-env-rejects-localhost" 1 "$work/p-localhost"
write_params "$work/p-invalid" SERVICE_HOST=svc.mujoong.invalid MUJOONG_SECURITY_ALLOWED_ORIGIN=https://svc.mujoong.invalid
expect_reject "render-env-rejects-invalid-tld" 1 "$work/p-invalid"
write_params "$work/p-short" "INTERNAL_SERVICE_TOKEN=$(rand 15)"
expect_reject "render-env-short-internal-token" 1 "$work/p-short"
# shellcheck disable=SC2016  # 렌더에 위험한 글자($ · 공백)를 그대로 넣는다
write_params "$work/p-unsafe" 'DB_CORE_PASSWORD=abc def$HOME'
expect_reject "render-env-unsafe-characters" 1 "$work/p-unsafe"
write_params "$work/p-origin" MUJOONG_SECURITY_ALLOWED_ORIGIN=https://other.example.net
expect_reject "render-env-origin-mismatch" 1 "$work/p-origin"
write_params "$work/p-google-half" "GOOGLE_OAUTH_CLIENT_ID=u2.apps.example.net"
expect_reject "render-env-google-id-without-secret" 1 "$work/p-google-half"
# U8: 선택 비밀도 값 문자 집합 검사를 받는다
# shellcheck disable=SC2016  # 렌더에 위험한 글자($ · 공백)를 그대로 넣는다
write_params "$work/p-ai-unsafe" 'JEV_API_KEY=abc def$HOME'
expect_reject "render-env-u8-ai-key-unsafe-characters" 1 "$work/p-ai-unsafe"
# U13 BR9.1: demo는 제브 키 · 주소 · 모델이 없으면 앱이 기동을 거부하므로 렌더 단계에서 먼저 거절한다
write_params "$work/p-jev-nokey" -JEV_API_KEY
expect_reject "render-env-u13-jev-key-missing" 1 "$work/p-jev-nokey"
write_params "$work/p-jev-nourl" -JEV_BASE_URL
expect_reject "render-env-u13-jev-base-url-missing" 1 "$work/p-jev-nourl"
write_params "$work/p-jev-nomodel" -JEV_MODEL
expect_reject "render-env-u13-jev-model-missing" 1 "$work/p-jev-nomodel"
write_params "$work/p-jev-http" "JEV_BASE_URL=http://jev.example.net"
expect_reject "render-env-u13-jev-base-url-not-https" 1 "$work/p-jev-http"
write_params "$work/p-jev-latest" "JEV_MODEL=jev-latest"
expect_reject "render-env-u13-jev-model-moving-alias" 1 "$work/p-jev-latest"
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
  # U9 SD6: LUNA_CALL · LUNA_STARTUP을 더해 19종이다(정본 EventCode.java와 같은 커밋에서 고친다, R-M10)
  if [ "$(wc -l <"$work/allow.java")" -eq 19 ] && diff -q "$work/allow.java" "$work/allow.script" >/dev/null; then
    ok "log-hygiene-allowlist-matches-java(19종)"
  else
    not_ok "log-hygiene-allowlist-matches-java" "$(diff "$work/allow.java" "$work/allow.script" | head -n 4 | paste -sd' ' -)"
  fi
else
  not_ok "log-hygiene-allowlist-matches-java" "python3가 없어 대조하지 못했다(전제 누락)"
fi

# U9 SD6: Luna 사건 두 줄(허용 속성만)은 attrs 위반 0, 허용 밖 속성(input)을 단 LUNA_CALL은 위반 1
r="$work/h-luna"
make_root "$r"
{
  app_line $((now_s - 500)) INFO LUNA_STARTUP '{"lunaState":"DISABLED_NO_KEY"}'
  app_line $((now_s - 400)) INFO LUNA_CALL \
    '{"lunaPurpose":"RESULT_TEXT","lunaOutcome":"OK","model":"gpt-6-luna","responseModel":"gpt-6-luna","promptVersion":"v1","sentFields":"task,turns","latencyMs":812,"attempt":1}'
} >>"$r/c/core-api.log"
run_tool "$r" "$LHC" --since "$since_iso"
luna_clean="$(grep '^CHECK attrs ' "$work/tool.out" || true)"
app_line $((now_s - 300)) WARN LUNA_CALL '{"lunaPurpose":"JUDGMENT_LIVE","lunaOutcome":"TIMEOUT","attempt":1,"input":"x"}' >>"$r/c/realtime.log"
run_tool "$r" "$LHC" --since "$since_iso"
luna_dirty="$(grep '^CHECK attrs ' "$work/tool.out" || true)"
if [ "$luna_clean" = 'CHECK attrs violations=0' ] && [ "$luna_dirty" = 'CHECK attrs violations=1' ]; then
  ok "log-hygiene-luna-events(U9 SD6: LUNA_STARTUP · LUNA_CALL 허용 속성)"
else
  not_ok "log-hygiene-luna-events" "깨끗한 줄 [$luna_clean] · 허용 밖 속성 [$luna_dirty]"
fi

# ---- turn-metrics(MO-4 · NFR5.4 · OD-8, J348): 한 턴 로그만으로 NFR5.1 · 5.2 · 5.3 계산 · 표본 규칙 · 미관측 사유 ---------------------
readonly TM="$ROOT/infra/bin/turn-metrics"
iso_ms() { date -u -d "@$(printf '%d.%03d' $(($1 / 1000)) $(($1 % 1000)))" +%Y-%m-%dT%H:%M:%S.%3NZ; }
# lt <t0 ms> <relayIn> <relayOut|-> <말 끝→재생 시작 ms|-> [clock|recv|none] — LATENCY_TURN 한 줄(시각은 t0 기준 상대값)
lt() {
  local t0="$1" rin="$2" rout="$3" tot="$4" mode="${5:-clock}"
  jq -c -n --arg ts "$(iso_ms "$t0")" --arg te "$(iso_ms "$t0")" --arg fo "$(iso_ms $((t0 + 121)))" \
    --arg sc "$(iso_ms $((t0 + 1027)))" --arg gp "$(iso_ms $((t0 + 1029)))" --arg ff "$(iso_ms $((t0 + 1036)))" \
    --arg ds "$([ "$tot" = - ] || iso_ms $((t0 + tot - 1)))" --arg dr "$([ "$tot" = - ] || iso_ms $((t0 + tot + 1)))" \
    --argjson rin "$rin" --arg rout "$rout" --arg mode "$mode" '
    { ts: $ts, level: "INFO", traceId: null, component: "LatencyRecorder", event: "LATENCY_TURN", ownerHash: null,
      attrs: ({ turnSeq: 1, tSpeechEnd: $te, tSpeechEndSource: "PROVIDER_EVENT", tProviderFirstOut: $fo, tSegmentConfirmed: $sc,
                relayInMs: $rin, providerResponseMs: 104, quietWaitMs: 801 }
        + (if $rout == "-" then { unobservedReason: "GATE_DISCARD" }
           else { tGatePass: $gp, tFirstFrameSent: $ff, relayOutMs: ($rout | tonumber) } end)
        + (if $ds == "" then {}
           elif $mode == "clock" then { tDevicePlayStart: $ds, tDevicePlayStartRecv: $dr, clockOffsetMs: -1, rttMs: 2 }
           else { tDevicePlayStartRecv: $dr, rttMs: 4, unobservedReason: "CLOCK_OFFSET_MISSING" } end)) }'
}
# sis <tFinal ms> <서버 몫 ms> <speaker> — SESSION_ITEMS_SERVED 한 줄(폴링 몫은 1000ms 고정)
sis() {
  jq -c -n --arg ts "$(iso_ms "$1")" --arg tf "$(iso_ms "$1")" --arg rd "$(iso_ms $(($1 + $2)))" --arg fs "$(iso_ms $(($1 + $2 + 1000)))" \
    --arg sp "$3" '{ ts: $ts, level: "INFO", traceId: null, component: "ViewHandler", event: "SESSION_ITEMS_SERVED", ownerHash: null,
      attrs: ({ seq: 1, speaker: $sp, tViewItemReady: $rd, tViewItemFirstServed: $fs } + (if $sp == "CHILD" then { tTranscriptFinal: $tf } else {} end)) }'
}
base_ms=$(((now_s - 3600) * 1000))
tm_run() { # <이름> <입력 파일> → $rc · $work/tm.out
  rc=0
  bash "$TM" --file "$2" >"$work/tm.out" 2>&1 || rc=$?
}
tm_expect() { # <이름> <기대 종료 코드> <출력에 있어야 할 줄(정확히 일치하는 부분 문자열)>...
  local name="$1" want="$2" miss=()
  shift 2
  local s
  for s in "$@"; do grep -qF -- "$s" "$work/tm.out" || miss+=("$s"); done
  if [ "$rc" -ne "$want" ] || [ "${#miss[@]}" -gt 0 ]; then
    not_ok "$name" "종료 코드 $rc(기대 $want), 없는 줄: ${miss[*]:-없음} — $(grep -m1 '^SUMMARY' "$work/tm.out")"
  else
    ok "$name"
  fi
}

# 1 한 턴 로그(3.6 PT-02 꼴): 정상 턴 1 + 게이트 폐기 턴 1, 아이 · AI 행 — 값 그대로 계산하고 표본 10개 미만이라 보류, 미관측 사유 표시
{
  lt "$base_ms" 1 8 1038
  sis "$base_ms" 9 CHILD
  sis $((base_ms + 1033)) 0 AI
  lt $((base_ms + 2169)) 1 - -
  sis $((base_ms + 2169)) 4 CHILD
} >"$work/tm-one.jsonl"
tm_run one "$work/tm-one.jsonl"
tm_expect "turn-metrics-one-turn(PT-02 꼴, 값 · 보류 · 미관측 사유)" 0 \
  "= 9ms(두 값이 다 있는 턴 1개)" "보류(표본 1개 < 10, 값 · 건수만)" \
  "말 끝 → 첫 음성 재생 p95 1038ms(n=1, 기기 시계 보정 1 · 추정 0, 오차 ± 1ms)" \
  "구간 p95: 첫 출력 121ms · 공급자 응답 104ms · 조용 대기 801ms · 게이트 2ms · 첫 프레임 7ms · 재생 시작 9ms" \
  "(서버 몫, CHILD) p95 9ms(n=2)" "폴링 몫 p95 1000ms(참고)" "[미관측] 2턴 중 1턴(50%) — 사유: GATE_DISCARD 1" \
  "SUMMARY turns=2 nfr5.1=보류 nfr5.2=보류 nfr5.3=보류 alerts="

# 2 표본 100개 이상: p95로 판정(모두 목표 안) → 충족 · 종료 코드 0. p95는 가장 가까운 순위(120개 중 114번째: 합계 1400 ~ 1519ms → 1513ms)
: >"$work/tm-ok.jsonl"
for i in $(seq 0 119); do
  t=$((base_ms + i * 5000))
  { lt "$t" $((5 + i % 3)) $((20 + i % 5)) $((1400 + i)); sis "$t" $((40 + i % 10)) CHILD; } >>"$work/tm-ok.jsonl"
done
tm_run ok "$work/tm-ok.jsonl"
tm_expect "turn-metrics-p95-judged(표본 120)" 0 "= 31ms(두 값이 다 있는 턴 120개)" "p95 1513ms(n=120" \
  "SUMMARY turns=120 nfr5.1=충족 nfr5.2=충족 nfr5.3=충족 alerts="

# 3 미달 · 경보선(A-13): relayOut 600ms → NFR5.1 미달, 합계 2.2초 → 목표(2.5초) 안이지만 경보선(2.0초) 초과 → 종료 코드 1
: >"$work/tm-bad.jsonl"
for i in $(seq 0 119); do
  t=$((base_ms + i * 5000))
  { lt "$t" 5 600 2200; sis "$t" 50 CHILD; } >>"$work/tm-bad.jsonl"
done
tm_run bad "$work/tm-bad.jsonl"
tm_expect "turn-metrics-miss-and-alert(A-13)" 1 "→ 미달(p95) · 경보선 초과(A-13)" "→ 충족(p95) · 경보선 초과(A-13)" \
  "SUMMARY turns=120 nfr5.1=미달 nfr5.2=충족 nfr5.3=충족 alerts=NFR5.1,NFR5.2"

# 4 표본 10 ~ 99: 목표 이내 비율 90% 이상이면 충족(18/20), 미만이면 미달(17/20)
ratio_case() { # <파일> <목표 밖 행 수>
  : >"$1"
  local i
  for i in $(seq 0 19); do
    if [ "$i" -lt "$2" ]; then sis $((base_ms + i * 5000)) 900 CHILD; else sis $((base_ms + i * 5000)) 100 CHILD; fi
  done >>"$1"
}
ratio_case "$work/tm-r90.jsonl" 2
tm_run r90 "$work/tm-r90.jsonl"
tm_expect "turn-metrics-ratio-90(표본 20, 18 이내)" 1 "→ 충족(목표 이내 90%) · 경보선 초과(A-13)" "nfr5.3=충족"
ratio_case "$work/tm-r85.jsonl" 3
tm_run r85 "$work/tm-r85.jsonl"
tm_expect "turn-metrics-ratio-85(표본 20, 17 이내)" 1 "→ 미달(목표 이내 85%)" "nfr5.3=미달"

# 5 기기 시계 오프셋 없음(CLOCK_OFFSET_MISSING): tDevicePlayStartRecv − rttMs ÷ 2로 추정하고 추정 수 · 사유를 적는다
lt "$base_ms" 1 8 1500 recv >"$work/tm-est.jsonl"
tm_run est "$work/tm-est.jsonl"
tm_expect "turn-metrics-clock-offset-estimate" 0 "p95 1499ms(n=1, 기기 시계 보정 0 · 추정 1, 오차 ± 2ms)" \
  "[미관측] 1턴 중 1턴(100%) — 사유: CLOCK_OFFSET_MISSING 1"

# 6 호스트 꼴: 합성 컨테이너 로그(docker 시각 앞붙음) + 보존 폴더, since 이전 줄은 뺀다
r="$work/h-turn"
mkdir -p "$r/log" "$r/data" "$r/env" "$r/c/archive"
{
  printf '%s %s\n' "$(dts $((now_s - 9000)))" "$(lt $(((now_s - 9000) * 1000)) 900 900 9000)"
  printf '%s %s\n' "$(dts $((now_s - 5000)))" "$(lt $(((now_s - 5000) * 1000)) 3 9 1200)"
} | gzip >"$r/c/archive/$(date -u -d "@$((now_s - 4000))" +%Y%m%dT%H%M%SZ)-realtime-test.log.gz"
{
  printf '%s %s\n' "$(dts $((now_s - 600)))" "$(lt $(((now_s - 600) * 1000)) 2 7 1100)"
  printf '%s %s\n' "$(dts $((now_s - 600)))" "$(sis $(((now_s - 600) * 1000)) 30 CHILD)"
  printf '%s %s\n' "$(dts $((now_s - 590)))" '{"ts":"x","level":"INFO","event":"METRIC_SNAPSHOT","attrs":{}}'
} >"$r/c/realtime.log"
for svc in core-api web nginx postgres; do : >"$r/c/$svc.log"; done
rc=0
MUJOONG_LOG_DIR="$r/log" MUJOONG_DATA_DIR="$r/data" MUJOONG_ENV_DIR="$r/env" \
  bash "$TM" --container-logs "$r/c" --since "$since_iso" >"$work/tm.out" 2>&1 || rc=$?
tm_expect "turn-metrics-container-logs(보존 폴더 · since)" 0 "LATENCY_TURN 2줄 · SESSION_ITEMS_SERVED 1줄" \
  "p95(relayIn) 3ms(n=2) + p95(relayOut) 9ms(n=2) = 12ms" "SUMMARY turns=2 "

# 7 빈 입력 · 인자 오류
: >"$work/tm-empty.jsonl"
tm_run empty "$work/tm-empty.jsonl"
tm_expect "turn-metrics-no-turns" 0 "[미관측] LATENCY_TURN 줄이 없다(턴 0)" "SUMMARY turns=0 nfr5.1=보류 nfr5.2=보류 nfr5.3=보류 alerts="
bad_args=0
for args in "--nope" "--file $work/없는-파일.jsonl" "--since 2026-10-11 --file $work/tm-empty.jsonl" "--container-logs $r/c --file $work/tm-empty.jsonl"; do
  rc=0
  # shellcheck disable=SC2086  # 인자 목록을 일부러 낱말로 나눈다
  bash "$TM" $args >/dev/null 2>&1 || rc=$?
  [ "$rc" -eq 64 ] || bad_args=$((bad_args + 1))
done
if [ "$bad_args" -eq 0 ]; then ok "turn-metrics-arg-errors(64)"; else not_ok "turn-metrics-arg-errors" "64가 아닌 경우 ${bad_args}건"; fi

echo "test-host-tools: ${total}건 중 어긋남 ${failures}건"
if [ "$failures" -gt 0 ]; then
  exit 1
fi
