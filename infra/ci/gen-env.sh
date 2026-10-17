#!/usr/bin/env bash
# 무중 검증 · CI용 서비스별 env 파일 생성 — 실행 때 무작위 비밀 (U1 3.5 Step 44 · J127 · J136 · J124 · J108 · infra-spec 3.7 · SD-12 · NFR9.10)
#
# 저장소에는 값 파일을 두지 않는다(J127, infra 시트의 fake.env 대신). 이 스크립트가 실행마다 무작위 값을 만들어
# 인프라 명세 3.7 분배표대로 서비스별 파일에 나눠 쓴다. 받지 않는 비밀은 넣지 않는다.
#   postgres.env  POSTGRES_PASSWORD DB_OWNER_PASSWORD DB_CORE_PASSWORD DB_REALTIME_PASSWORD DB_BACKUP_PASSWORD
#   migrate.env   DB_OWNER_PASSWORD
#   core-api.env  SPRING_DATASOURCE_PASSWORD(=core) INTERNAL_SERVICE_TOKEN MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET
#                 GOOGLE_OAUTH_CLIENT_ID · GOOGLE_OAUTH_CLIENT_SECRET(빈 값 — 검증 스택은 구글을 부르지 않는다)
#                 GOOGLE_OAUTH_REDIRECT_URI(<origin>/parent/login/callback) MUJOONG_SECURITY_ALLOWED_ORIGIN
#                 OPENAI_API_KEY(빈 값 — 검증 · CI는 실제 Luna를 부르지 않는다, R-F1 · U8 SD3)
#   realtime.env  SPRING_DATASOURCE_PASSWORD(=realtime) INTERNAL_SERVICE_TOKEN
#                 MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET MUJOONG_SECURITY_ALLOWED_ORIGIN
#                 OPENAI_API_KEY · JEV_API_KEY(빈 값 — 가짜 공급자는 키를 보지 않고, 실제 GPT-Live · Luna · 제브를 부르지 않는다. U11 BR12.2: 자리값 없음.
#                 U13: 검증 스택은 dev 프로필이라 제브 모드 기본값이 STUB(가짜 제브)이므로 JEV_BASE_URL · JEV_MODEL도 넣지 않는다. 이 둘은 demo 호스트 render-env 몫)
#   web.env       GOOGLE_OAUTH_CLIENT_ID(빈 값) GOOGLE_OAUTH_REDIRECT_URI MUJOONG_SECURITY_ALLOWED_ORIGIN
#                 (구글 이름은 Core · web이 같다 — infra/bin/render-env와 같은 분배, U2 리뷰 3)
#   nginx.env     SERVICE_HOST MUJOONG_SECURITY_ALLOWED_ORIGIN
#   backup.env    DB_BACKUP_PASSWORD BACKUP_RETENTION_DAYS   ─┐ 검증 스택(compose.ci)은 backup · certbot을 끄지만
#   certbot.env   SERVICE_HOST ACME_CONTACT_EMAIL            ─┘ compose.demo.yml 단독 config 검사 · 이미지 점검이 쓴다
#   compose.env   MUJOONG_REGISTRY · MUJOONG_TAG · MUJOONG_ENV_DIR(Compose 보간용 비밀 아닌 값, `docker compose --env-file`)
#
# 값 규칙(J124): 모두 base64url. INTERNAL_SERVICE_TOKEN 43자(256비트), ownerHash 비밀 43자(32바이트 이상), DB 비밀번호 32자.
# 파일 권한: 디렉터리 0700 · 파일 0600(umask 077). 값을 표준 출력 · 로그에 쓰지 않는다(파일 · 변수 이름만 출력).
# 매 실행은 출력 디렉터리의 *.env를 지우고 새 값으로 다시 만든다. 실행 끝 정리는 부르는 쪽(scripts/verify.sh)이 한다.
# --keep-existing이면 서비스 파일이 모두 있을 때 아무것도 바꾸지 않는다(dev: 이미 만든 DB 볼륨의 역할 비밀번호와 맞추려고).
#
# 쓰는 법: bash infra/ci/gen-env.sh [--out <디렉터리>] [--service-host <호스트>] [--origin <http(s)://호스트[:포트]>]
#                                   [--registry <레지스트리>] [--tag <태그>] [--keep-existing]
#   기본값: --out build/verify/env, --service-host localhost, --origin https://localhost:8443(J108),
#           --registry mujoong.local, --tag verify(infra/ci/build-images.sh의 로컬 태그와 같다)
# 종료 코드: 0 성공, 64 인자 오류, 1 그 밖의 오류

set -Eeuo pipefail
set +x
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$ROOT/build/verify/env"
service_host=localhost
origin="https://localhost:8443"
registry=mujoong.local
tag=verify
keep_existing=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --out) out="${2:-}"; shift ;;
    --service-host) service_host="${2:-}"; shift ;;
    --origin) origin="${2:-}"; shift ;;
    --registry) registry="${2:-}"; shift ;;
    --tag) tag="${2:-}"; shift ;;
    --keep-existing) keep_existing=1 ;;
    -h | --help) sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "gen-env: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done

if ! [[ "$service_host" =~ ^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$ ]]; then
  echo "gen-env: --service-host 형식이 틀렸다" >&2
  exit 64
fi
# 검증 스택은 https(J108), dev 호스트 실행만 http://localhost:3000(Secure Context 예외, infra-spec 1.3)
if ! [[ "$origin" =~ ^https://${service_host//./\\.}(:[0-9]{1,5})?$ ]] && ! [[ "$origin" =~ ^http://localhost(:[0-9]{1,5})?$ ]]; then
  echo "gen-env: --origin은 https://<service-host>[:포트] 또는 http://localhost[:포트] 이어야 한다" >&2
  exit 64
fi
if ! [[ "$registry" =~ ^[a-z0-9][a-z0-9./_-]*$ ]] || ! [[ "$tag" =~ ^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$ ]]; then
  echo "gen-env: --registry · --tag 형식이 틀렸다" >&2
  exit 64
fi

# 상대 경로는 현재 디렉터리 기준 절대 경로로 바꾼다(Compose env_file은 절대 경로로 넘긴다)
case "$out" in /*) ;; *) out="$PWD/$out" ;; esac

readonly SERVICE_FILES=(postgres migrate core-api realtime web nginx backup certbot compose)
if [ "$keep_existing" -eq 1 ]; then
  all=1
  for f in "${SERVICE_FILES[@]}"; do
    [ -s "$out/$f.env" ] || all=0
  done
  if [ "$all" -eq 1 ]; then
    echo "gen-env: $out 의 env 파일을 그대로 둔다(--keep-existing)"
    exit 0
  fi
fi

# base64url 무작위 값: <바이트 수>
rand() {
  head -c "$1" /dev/urandom | base64 -w0 | tr '+/' '-_' | tr -d '='
}

postgres_password="$(rand 24)"
owner_password="$(rand 24)"
core_password="$(rand 24)"
realtime_password="$(rand 24)"
backup_password="$(rand 24)"
internal_token="$(rand 32)"
owner_hash_secret="$(rand 32)"

mkdir -p "$out"
chmod 0700 "$out"
rm -f -- "$out"/*.env

# write <파일 이름> <변수=값>... — 값은 파일에만 쓰고, 출력에는 파일 이름과 변수 이름만 낸다
write() {
  local name="$1"
  local file="$out/$name"
  shift
  : >"$file"
  chmod 0600 "$file"
  local line
  for line in "$@"; do
    printf '%s\n' "$line" >>"$file"
  done
  echo "gen-env: $name ($(cut -d= -f1 "$file" | tr '\n' ' ' | sed 's/ $//'))"
}

write postgres.env \
  "POSTGRES_PASSWORD=$postgres_password" \
  "DB_OWNER_PASSWORD=$owner_password" \
  "DB_CORE_PASSWORD=$core_password" \
  "DB_REALTIME_PASSWORD=$realtime_password" \
  "DB_BACKUP_PASSWORD=$backup_password"
write migrate.env \
  "DB_OWNER_PASSWORD=$owner_password"
write core-api.env \
  "SPRING_DATASOURCE_PASSWORD=$core_password" \
  "INTERNAL_SERVICE_TOKEN=$internal_token" \
  "MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET=$owner_hash_secret" \
  "GOOGLE_OAUTH_CLIENT_ID=" \
  "GOOGLE_OAUTH_CLIENT_SECRET=" \
  "GOOGLE_OAUTH_REDIRECT_URI=$origin/parent/login/callback" \
  "MUJOONG_SECURITY_ALLOWED_ORIGIN=$origin" \
  "OPENAI_API_KEY=" \
  "NEO4J_AUTH="
write realtime.env \
  "SPRING_DATASOURCE_PASSWORD=$realtime_password" \
  "INTERNAL_SERVICE_TOKEN=$internal_token" \
  "MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET=$owner_hash_secret" \
  "MUJOONG_SECURITY_ALLOWED_ORIGIN=$origin" \
  "OPENAI_API_KEY=" \
  "JEV_API_KEY="
write web.env \
  "GOOGLE_OAUTH_CLIENT_ID=" \
  "GOOGLE_OAUTH_REDIRECT_URI=$origin/parent/login/callback" \
  "MUJOONG_SECURITY_ALLOWED_ORIGIN=$origin"
write nginx.env \
  "SERVICE_HOST=$service_host" \
  "MUJOONG_SECURITY_ALLOWED_ORIGIN=$origin"
write backup.env \
  "DB_BACKUP_PASSWORD=$backup_password" \
  "BACKUP_RETENTION_DAYS=7"
write certbot.env \
  "SERVICE_HOST=$service_host" \
  "ACME_CONTACT_EMAIL=ops@mujoong.invalid"
write compose.env \
  "MUJOONG_REGISTRY=$registry" \
  "MUJOONG_TAG=$tag" \
  "MUJOONG_ENV_DIR=$out"
