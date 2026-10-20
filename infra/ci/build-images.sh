#!/usr/bin/env bash
# 무중 이미지 빌드 — 6종 + 시험 전용 가짜 공급자 (U1 3.5 Step 43 · cicd 2.3.1 · J128 · J134 (가) · R-03 · SD-16 · SD-25)
#
# 호스트가 이미 만든 산출물(검증 명령 A · CI backend · frontend · tools 작업)을 COPY해 이미지를 만든다. 이미지 안에서
# Gradle · npm을 돌리지 않는다(샌드박스 빌드 규칙 R-03: 컨테이너 안 프로세스는 프록시 · CA를 모른다).
#   core-api  ← backend/core-api/build/libs/core-api.jar               (backend/core-api/Dockerfile, 맥락 backend/)
#   realtime  ← backend/realtime-activity/build/libs/realtime-activity.jar (backend/realtime-activity/Dockerfile, 맥락 backend/)
#   web       ← frontend/.next/standalone · .next/static                (frontend/Dockerfile, 맥락 frontend/)
#   migrate   ← 두 앱의 db/migration 위치(BuildKit 이름 있는 맥락) + Flyway CLI 최소 jar(ADD --checksum, J351) (infra/migrate/Dockerfile, 맥락 infra/migrate/)
#   backup    ← postgres:16.15 + supercronic(고정 소스를 고정 Go로 빌드, J352) (infra/backup/Dockerfile, 맥락 infra/backup/)
#   infra     ← infra/ 번들 목록 + 렌더한 release.env                   (infra/bundle/Dockerfile, 맥락 infra/)
#   fake-provider ← tools/(시험 전용, --no-test-images면 빼고, 앱 이미지 6종과 섞지 않는다) (tools/fake-provider/Dockerfile)
#   nginx     ← infra/nginx-ecs/default.conf + infra/nginx/snippets      (infra/nginx-ecs/Dockerfile, 맥락 infra/)
#
# 라벨(모든 이미지): org.opencontainers.image.revision=<git HEAD 40자>, kr.mujoong.source-fingerprint=<infra/ci/source-fingerprint.sh 값>.
# 지문은 빌드 앞뒤로 두 번 계산해 같을 때만 성공한다(빌드 중 소스가 바뀌면 라벨이 거짓이 되므로 실패, J134 (가)).
# 산출물이 자기 소스보다 오래되면(소스 파일이 산출물보다 새것) 빌드하지 않고 실패한다(낡은 jar · standalone에 새 지문을 붙이지 않음).
#
# 샌드박스 빌드 규칙(R-03): 받을 파일(OS 패치 .deb · Flyway jar)은 BuildKit ADD가 sha256으로 고정해 받고 RUN 단계는 네트워크를
# 쓰지 않는다. 예외는 backup의 supercronic 빌드 단계 하나다(Go 모듈을 받는다, J352). 프록시 · CA가 필요한 곳(샌드박스)에서는
# --extra-ca <CA 묶음>을 주면 모든 빌드에 `--network host --secret id=extra_ca --secret id=proxy_env`를 붙이고, 그 단계가
# 비밀 마운트(required=false)로만 읽는다. 값은 빌드 인자 · ENV로 넘기지 않는다(docker history · 실행 이미지에 남지 않음).
# CI에서는 주지 않는다(바로 받는다).
#
# 쓰는 법: bash infra/ci/build-images.sh [--registry <레지스트리>] [--tag <태그>] [--only <이름,...>] [--no-test-images]
#                                        [--extra-ca <파일>] [--log-dir <디렉터리>] [--fingerprint sha256:<64자>]
#   기본값: --registry mujoong.local, --tag verify(infra/ci/gen-env.sh의 compose.env와 같다), --log-dir build/verify/logs/images
#   --only        core-api,realtime,web,migrate,backup,infra,nginx,fake-provider 중 일부만(쉼표 목록)
#   --fingerprint 부르는 쪽이 미리 계산한 지문(scripts/verify.sh 시작 지문). 주면 지금 지문과 같아야 한다
# 표준 출력: 이미지마다 한 줄(이름 · 태그 · 걸린 초). docker build 출력은 로그 디렉터리의 <이름>.log에만 쓴다.
# 종료 코드: 0 성공, 1 빌드 실패 · 지문 불일치 · 낡은 산출물, 2 전제 누락(docker · 산출물 · git), 64 인자 오류

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT
readonly ALL_IMAGES=(core-api realtime web migrate backup infra nginx fake-provider)

usage() {
  sed -n '2,31p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

registry=mujoong.local
tag=verify
only=""
test_images=1
extra_ca=""
log_dir="$ROOT/build/verify/logs/images"
expected_fingerprint=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --registry) registry="${2:-}"; shift ;;
    --tag) tag="${2:-}"; shift ;;
    --only) only="${2:-}"; shift ;;
    --no-test-images) test_images=0 ;;
    --extra-ca) extra_ca="${2:-}"; shift ;;
    --log-dir) log_dir="${2:-}"; shift ;;
    --fingerprint) expected_fingerprint="${2:-}"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "build-images: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done

if ! [[ "$registry" =~ ^[a-z0-9][a-z0-9./_-]*$ ]] || ! [[ "$tag" =~ ^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$ ]]; then
  echo "build-images: --registry · --tag 형식이 틀렸다" >&2
  exit 64
fi
if [ -n "$expected_fingerprint" ] && ! [[ "$expected_fingerprint" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "build-images: --fingerprint는 sha256:<64자 16진수>여야 한다" >&2
  exit 64
fi

selected=()
if [ -n "$only" ]; then
  IFS=',' read -r -a requested <<<"$only"
  for name in "${requested[@]}"; do
    case " ${ALL_IMAGES[*]} " in
      *" $name "*) selected+=("$name") ;;
      *) echo "build-images: --only에 모르는 이미지: $name" >&2; exit 64 ;;
    esac
  done
else
  for name in "${ALL_IMAGES[@]}"; do
    if [ "$name" = fake-provider ] && [ "$test_images" -eq 0 ]; then
      continue
    fi
    selected+=("$name")
  done
fi

# --- 전제 -----------------------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "build-images: docker 데몬에 닿지 못한다" >&2
  exit 2
fi
if ! docker buildx version >/dev/null 2>&1; then
  echo "build-images: docker buildx(BuildKit)가 없다(이름 있는 맥락 · ADD --checksum에 필요)" >&2
  exit 2
fi
if ! git_sha="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)" || ! [[ "$git_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "build-images: git HEAD를 읽지 못했다" >&2
  exit 2
fi
if [ -n "$extra_ca" ] && [ ! -r "$extra_ca" ]; then
  echo "build-images: --extra-ca 파일을 읽을 수 없다" >&2
  exit 2
fi

# 산출물과 그 산출물을 만드는 소스(산출물보다 새 소스 파일이 있으면 낡은 산출물이다)
artifact_of() {
  case "$1" in
    core-api) echo "backend/core-api/build/libs/core-api.jar" ;;
    realtime) echo "backend/realtime-activity/build/libs/realtime-activity.jar" ;;
    web) echo "frontend/.next/standalone/server.js" ;;
    fake-provider) echo "tools/node_modules/ws/package.json" ;;
    *) echo "" ;;
  esac
}
sources_of() {
  case "$1" in
    core-api) echo "backend/core-api/src/main backend/core-api/build.gradle.kts backend/contract-model/src/main backend/contract-model/build.gradle.kts backend/build-logic/src backend/gradle/libs.versions.toml backend/build.gradle.kts backend/settings.gradle.kts" ;;
    realtime) echo "backend/realtime-activity/src/main backend/realtime-activity/build.gradle.kts backend/contract-model/src/main backend/contract-model/build.gradle.kts backend/build-logic/src backend/gradle/libs.versions.toml backend/build.gradle.kts backend/settings.gradle.kts" ;;
    web) echo "frontend/src frontend/public frontend/next.config.ts frontend/package.json frontend/package-lock.json frontend/tsconfig.json" ;;
    fake-provider) echo "tools/package-lock.json" ;;
    *) echo "" ;;
  esac
}

stale=()
for name in "${selected[@]}"; do
  artifact="$(artifact_of "$name")"
  [ -n "$artifact" ] || continue
  if [ ! -f "$ROOT/$artifact" ]; then
    echo "build-images: $name 산출물이 없다: $artifact (검증 명령 A 또는 CI 빌드 작업이 먼저 만든다)" >&2
    exit 2
  fi
  read -r -a src_paths <<<"$(sources_of "$name")"
  existing=()
  for p in "${src_paths[@]}"; do
    [ -e "$ROOT/$p" ] && existing+=("$ROOT/$p")
  done
  if [ "${#existing[@]}" -gt 0 ]; then
    newer="$(find "${existing[@]}" -type f -newer "$ROOT/$artifact" -print -quit 2>/dev/null || true)"
    if [ -n "$newer" ]; then
      stale+=("$name(${newer#"$ROOT"/})")
    fi
  fi
done
if [ "${#stale[@]}" -gt 0 ]; then
  echo "build-images: 산출물이 소스보다 오래됐다(다시 빌드한 뒤 부른다): ${stale[*]}" >&2
  exit 1
fi
has_image() {
  case " ${selected[*]} " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}
if has_image web && [ ! -d "$ROOT/frontend/.next/static" ]; then
  echo "build-images: frontend/.next/static이 없다" >&2
  exit 2
fi
if has_image infra && [ ! -f "$ROOT/infra/RUNBOOK.md" ]; then
  echo "build-images: 번들에 넣을 infra/RUNBOOK.md가 없다(Step 47)" >&2
  exit 2
fi

fingerprint_now() {
  bash "$ROOT/infra/ci/source-fingerprint.sh"
}
fingerprint="$(fingerprint_now)"
if [ -n "$expected_fingerprint" ] && [ "$fingerprint" != "$expected_fingerprint" ]; then
  echo "build-images: 지금 소스 지문이 --fingerprint와 다르다(실행 중 소스가 바뀌었다)" >&2
  exit 1
fi

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$log_dir"

# 번들의 release.env(비밀 아님: 이미지 태그 = SHA, 레지스트리, 지문)
mkdir -p "$work/release"
# shellcheck source=infra/bin/lib/render.sh
. "$ROOT/infra/bin/lib/render.sh"
MUJOONG_REGISTRY="$registry" MUJOONG_TAG="$tag" MUJOONG_GIT_SHA="$git_sha" MUJOONG_SOURCE_FINGERPRINT="$fingerprint" \
  mujoong_render_template "$ROOT/infra/release.env.tmpl" "$work/release/release.env" \
  MUJOONG_REGISTRY MUJOONG_TAG MUJOONG_GIT_SHA MUJOONG_SOURCE_FINGERPRINT

secret_args=()
if [ -n "$extra_ca" ]; then
  # 프록시 값은 umask 077 임시 파일로만 넘긴다(빌드 인자 · ENV 금지)
  printf 'HTTPS_PROXY=%s\nHTTP_PROXY=%s\nNO_PROXY=%s\n' "${HTTPS_PROXY:-${https_proxy:-}}" "${HTTP_PROXY:-${http_proxy:-}}" \
    "${NO_PROXY:-${no_proxy:-}}" >"$work/proxy_env"
  secret_args=(--network host --secret "id=extra_ca,src=$extra_ca" --secret "id=proxy_env,src=$work/proxy_env")
fi

image_ref() {
  case "$1" in
    core-api) echo "$registry/mujoong-core-api:$tag" ;;
    realtime) echo "$registry/mujoong-realtime:$tag" ;;
    web) echo "$registry/mujoong-web:$tag" ;;
    migrate) echo "$registry/mujoong-migrate:$tag" ;;
    backup) echo "$registry/mujoong-backup:$tag" ;;
    infra) echo "$registry/mujoong-infra:$tag" ;;
    fake-provider) echo "$registry/mujoong-fake-provider:$tag" ;;
    nginx) echo "$registry/mujoong-nginx:$tag" ;;
  esac
}

build_one() {
  local name="$1" ref log started
  ref="$(image_ref "$name")"
  log="$log_dir/$name.log"
  local common=(--progress plain --build-arg "GIT_SHA=$git_sha" --build-arg "MUJOONG_SOURCE_FINGERPRINT=$fingerprint"
    -t "$ref" "${secret_args[@]}")
  local args=()
  case "$name" in
    core-api) args=(-f backend/core-api/Dockerfile backend) ;;
    realtime) args=(-f backend/realtime-activity/Dockerfile backend) ;;
    web) args=(-f frontend/Dockerfile frontend) ;;
    migrate)
      args=(-f infra/migrate/Dockerfile
        --build-context core_sql=backend/core-api/src/main/resources/db/migration/core
        --build-context realtime_sql=backend/realtime-activity/src/main/resources/db/migration/realtime
        infra/migrate) ;;
    backup) args=(-f infra/backup/Dockerfile infra/backup) ;;
    infra) args=(-f infra/bundle/Dockerfile --build-context "release=$work/release" infra) ;;
    fake-provider) args=(-f tools/fake-provider/Dockerfile tools) ;;
    nginx) args=(-f infra/nginx-ecs/Dockerfile infra) ;;
  esac
  started="$(date +%s)"
  if ! (cd "$ROOT" && DOCKER_BUILDKIT=1 docker build "${common[@]}" "${args[@]}") >"$log" 2>&1; then
    echo "build-images: $name 빌드 실패(로그 ${log#"$ROOT"/})" >&2
    return 1
  fi
  echo "build-images: $name $ref $(($(date +%s) - started))s"
}

for name in "${selected[@]}"; do
  build_one "$name"
done

# 빌드 중 소스가 바뀌었으면 라벨이 거짓이다
after="$(fingerprint_now)"
if [ "$after" != "$fingerprint" ]; then
  echo "build-images: 빌드 중 소스 지문이 바뀌었다(라벨을 믿을 수 없다). 다시 빌드한다" >&2
  exit 1
fi
echo "build-images: 완료 ${#selected[@]}종 tag=$tag revision=${git_sha:0:12} fingerprint=${fingerprint:0:19}…"
