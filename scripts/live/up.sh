#!/usr/bin/env bash
# 무중 실키 전체 기동 — 개발자 PC 로컬 전용 (검증 스택 변형, infra/compose.live.yml)
#
# 쓰는 법: bash scripts/live/up.sh (--local | --tag <커밋 SHA>) [--registry <레지스트리>] [--port <포트>]
#   --local            이 작업 트리에서 이미지를 로컬로 빌드해 쓴다(병합 전 코드도 시험할 수 있다. JDK 21 · Node 22 필요)
#   --tag <SHA>        CI가 main 병합 때 GHCR에 올린 이미지를 당겨 쓴다(40자 커밋 SHA, latest 없음)
#   --registry <값>    --tag일 때의 레지스트리(기본 ghcr.io/team-jomyung)
#   --port <포트>      호스트에 여는 HTTPS 포트(기본 9443, 127.0.0.1에만 게시). 한 번 정하면 down.sh --purge 전에는 바꾸지 않는다
# 먼저 키를 넣어 둔다: bash scripts/live/keys.sh  (키는 ~/.config/mujoong/live.env, 저장소 밖)
# 하는 일: env · 자체 서명 인증서 · nginx · postgres 설정을 build/live/(저장소에 올라가지 않음)에 만들고 → 이미지 준비 →
#   postgres → migrate → 시드 → 전체 기동. 프로젝트 이름은 mujoong-live다. mujoong-manual · mujoong-verify는 건드리지 않는다(R-F12).
# 이 스택은 실제 OpenAI(GPT-Live · Luna)와 제브를 부른다. 시험 · CI · verify.sh에서는 쓰지 않는다(R-F1).
# 종료 코드: 0 성공, 1 실패, 2 전제 누락, 64 인자 오류

set -Eeuo pipefail
set +x
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

mode=""
tag=""
registry="ghcr.io/team-jomyung"
port=9443
while [ "$#" -gt 0 ]; do
  case "$1" in
    --local) mode=local ;;
    --tag) mode=pull; tag="${2:-}"; shift ;;
    --registry) registry="${2:-}"; shift ;;
    --port) port="${2:-}"; shift ;;
    -h | --help) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "live-up: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done
[ -n "$mode" ] || { echo "live-up: --local 또는 --tag <커밋 SHA> 중 하나가 필요하다(--help)" >&2; exit 64; }
[[ "$port" =~ ^[0-9]{4,5}$ ]] || { echo "live-up: --port는 4~5자리 숫자여야 한다" >&2; exit 64; }
if [ "$mode" = pull ]; then
  [[ "$tag" =~ ^[0-9a-f]{40}$ ]] || { echo "live-up: --tag는 40자 커밋 SHA여야 한다(latest · 이동 태그 금지, R-F10)" >&2; exit 64; }
  [[ "$registry" =~ ^[a-z0-9.-]+(:[0-9]+)?(/[a-z0-9._-]+)*$ ]] || { echo "live-up: --registry 형식이 아니다" >&2; exit 64; }
fi

log() { printf 'live-up: %s\n' "$*"; }
die() { printf 'live-up: %s\n' "$*" >&2; exit "${2:-1}"; }
prereq() { printf 'live-up: 전제 누락: %s\n' "$*" >&2; exit 2; }

# ---- 전제 ------------------------------------------------------------------------------------------------------------
command -v docker >/dev/null || prereq "docker가 없다"
docker info >/dev/null 2>&1 || prereq "Docker 데몬에 닿지 못한다(권한이면 'sg docker -c \"bash scripts/live/up.sh …\"')"
cver="$(docker compose version --short 2>/dev/null || true)"
if [ -z "$cver" ] || [ "$(printf '%s\n2.24.4\n' "${cver#v}" | sort -V | head -n 1)" != "2.24.4" ]; then
  prereq "Docker Compose v2.24.4 이상이 필요하다(지금 ${cver:-없음})"
fi
command -v openssl >/dev/null || prereq "openssl이 없다"

live_env="${MUJOONG_LIVE_ENV_FILE:-$HOME/.config/mujoong/live.env}"
[ -f "$live_env" ] || prereq "키 파일이 없다($live_env). 먼저: bash scripts/live/keys.sh"
grep -q '^OPENAI_API_KEY=.' "$live_env" || prereq "키 파일에 OPENAI_API_KEY가 없다. bash scripts/live/keys.sh"
perm="$(stat -c %a "$live_env")"
[ "$perm" = 600 ] || die "키 파일 권한이 600이 아니다($perm): chmod 600 로 고친 뒤 다시 하세요"
case "$(cd "$(dirname "$live_env")" && pwd -P)/" in "$ROOT"/*) die "키 파일이 저장소 안에 있다. 저장소 밖으로 옮기세요" ;; esac

# 포트 점검(8443 등 다른 스택과 겹치는지)
if command -v ss >/dev/null && ss -ltn "sport = :$port" 2>/dev/null | grep -q LISTEN; then
  if ! docker ps --filter "name=mujoong-live-nginx" --format '{{.Names}}' | grep -q .; then
    die "127.0.0.1:$port를 이미 다른 프로세스가 쓰고 있다. --port로 다른 포트를 고르세요"
  fi
fi

# ---- 작업 폴더(저장소에 올라가지 않는다: .gitignore의 build/) ----------------------------------------------------
live_dir="$ROOT/build/live"
env_dir="$live_dir/env"
mkdir -p "$live_dir"
chmod 0700 "$live_dir"
if [ -f "$live_dir/.port" ] && [ "$(cat "$live_dir/.port")" != "$port" ]; then
  die "이전 실행의 포트($(cat "$live_dir/.port"))와 다르다. 값 파일이 그 포트에 묶여 있으니 먼저: bash scripts/live/down.sh --purge"
fi
echo "$port" >"$live_dir/.port"

# ---- 이미지 ----------------------------------------------------------------------------------------------------------
if [ "$mode" = local ]; then
  reg=mujoong.local
  img_tag=live
  # 이미지에 복사되는 산출물은 컨테이너 사용자가 읽어야 하므로 이 구간만 umask 022로 만든다(비밀 파일 구간은 077 유지)
  (
    umask 022
    log "산출물 빌드(bootJar · Next.js standalone) — 처음에는 몇 분 걸린다"
    (cd backend && ./gradlew --no-daemon :core-api:bootJar :realtime-activity:bootJar) || die "백엔드 빌드 실패"
    (cd frontend && rm -rf .next && npm ci && npx --no-install next build) || die "프런트 빌드 실패"
    log "이미지 빌드(core-api · realtime · web · migrate)"
    bash infra/ci/build-images.sh --registry "$reg" --tag "$img_tag" --only core-api,realtime,web,migrate --no-test-images \
      --log-dir "$live_dir/logs/images" || die "이미지 빌드 실패(build/live/logs/images/)"
  )
else
  reg="$registry"
  img_tag="$tag"
  log "이미지 확인: $reg/mujoong-*:${tag:0:12}…"
  for name in core-api realtime web migrate; do
    ref="$reg/mujoong-$name:$img_tag"
    if ! docker manifest inspect "$ref" >/dev/null 2>&1; then
      die "이미지를 찾지 못했다: $ref
  - main 병합 때 CI가 올린 커밋 SHA인지 확인하세요(병합 전 코드는 --local).
  - 비공개 패키지면 먼저 로그인하세요: docker login ghcr.io -u <GitHub 사용자> (읽기 권한 토큰, 채팅에 붙이지 말 것)"
    fi
  done
  for name in core-api realtime web migrate; do
    docker pull --quiet "$reg/mujoong-$name:$img_tag" >/dev/null || die "pull 실패: mujoong-$name"
  done
fi

# ---- env · 인증서 · nginx · postgres 설정 ----------------------------------------------------------------------------
log "준비: env · 인증서 · nginx · postgres 설정 (build/live/)"
bash infra/ci/gen-env.sh --out "$env_dir" --service-host localhost --origin "https://localhost:$port" \
  --registry "$reg" --tag "$img_tag" --keep-existing >/dev/null
# --keep-existing는 compose.env도 그대로 둔다 → 이미지 위치 · 태그만 지금 값으로 맞춘다(비밀 아님)
sed -i -E "s|^MUJOONG_REGISTRY=.*|MUJOONG_REGISTRY=$reg|; s|^MUJOONG_TAG=.*|MUJOONG_TAG=$img_tag|" "$env_dir/compose.env"
[ -f "$live_dir/certs/live/localhost/fullchain.pem" ] || bash infra/ci/gen-selfsigned.sh --out "$live_dir/certs" --host localhost >/dev/null
bash infra/bin/render-nginx --out "$live_dir/nginx" --env-file "$env_dir/nginx.env" >/dev/null
bash infra/bin/render-pgconf --out "$live_dir/postgres/postgresql.conf" >/dev/null
chmod -R go+rX "$live_dir/nginx" "$live_dir/postgres"

# ---- Compose ---------------------------------------------------------------------------------------------------------
export MUJOONG_VERIFY_DIR="$live_dir"
export MUJOONG_LIVE_ENV_FILE="$live_env"
export MUJOONG_LIVE_PORT="$port"
export MUJOONG_VERIFY_EDGE_PREFIX="${MUJOONG_LIVE_EDGE_PREFIX:-172.30.40}"
export MUJOONG_VERIFY_BACKEND_PREFIX="${MUJOONG_LIVE_BACKEND_PREFIX:-172.30.50}"
# 신뢰 프록시 정규식: nginx 고정 IP(<edge 대역>.10)의 점을 이스케이프한다
export MUJOONG_LIVE_PROXY_REGEX="${MUJOONG_VERIFY_EDGE_PREFIX//./\\.}\\.10"
readonly PROJECT=mujoong-live
dc() {
  docker compose -p "$PROJECT" --env-file "$env_dir/compose.env" \
    -f infra/compose.demo.yml -f infra/compose.ci.yml -f infra/compose.live.yml "$@"
}
dc --profile migrate config --quiet >/dev/null 2>"$live_dir/config.err" || die "병합 설정이 유효하지 않다(build/live/config.err)"

log "postgres 기동"
dc up -d --wait --wait-timeout 120 postgres >/dev/null || die "postgres가 healthy가 되지 않았다"
log "migrate"
dc --profile migrate run --rm migrate >"$live_dir/migrate.log" 2>&1 || die "migrate 실패(build/live/migrate.log)"

pg_image="$(grep -E '^\s+image:\s*postgres:' infra/compose.demo.yml | head -n 1 | sed -E 's/^\s+image:\s*//')"
owner_psql() {
  docker run --rm -i --network "${PROJECT}_backend" --cap-drop ALL --security-opt no-new-privileges:true \
    --env-file "$env_dir/migrate.env" -v "$ROOT/infra/seed:/seed:ro" --entrypoint sh "$pg_image" -c \
    'PGPASSWORD="$DB_OWNER_PASSWORD" exec psql -X -q -h postgres -U mujoong_owner -d mujoong -v ON_ERROR_STOP=1 "$@"' \
    owner-psql "$@"
}
log "시드(멱등, 두 번 적용)"
for _ in 1 2; do
  for seed in infra/seed/[0-9][0-9][0-9]-*.sql; do
    owner_psql --single-transaction -f "/seed/$(basename "$seed")" || die "시드 실패: $(basename "$seed")"
  done
done

log "전체 기동(nginx · web · core-api · realtime)"
dc up -d --wait --wait-timeout 240 || { dc ps -a --format '{{.Service}} {{.State}} {{.Health}}' >&2 || true; die "기동 실패(docker compose -p $PROJECT logs <서비스>)"; }

cat <<EOF

live-up: 기동했습니다. (프로젝트 $PROJECT, 이미지 $reg @ ${img_tag:0:12})
  보호자 화면 시험 로그인 : https://localhost:$port/parent/dev-login
  기기 화면               : https://localhost:$port/device
  브라우저 인증서 경고    : 자체 서명 인증서입니다. 경고에서 계속하거나 아래 CA를 신뢰 목록에 넣으세요.
                            $live_dir/certs/ca.crt
  로그 보기               : docker compose -p $PROJECT logs -f realtime   (비밀 값은 로그에 없습니다)
  끄기                    : bash scripts/live/down.sh   (데이터까지 지우기: --purge)
EOF
