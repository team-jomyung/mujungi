#!/usr/bin/env bash
# infra-validate 시험 — 위반 샘플마다 기대 결과를 단언한다 (U1 3.5 Step 48 · cicd 2.2.3 · 2.7.2 · 4절 CD-1 · CD-8 · R4-17)
#
# 샘플(infra/ci/invariant-samples/, 기준 파일 위에 얹는 덧붙임 파일)과 실행 때 임시 디렉터리에 만든 변형으로
# infra/bin/infra-validate가 통과해야 할 것은 통과시키고 막아야 할 것은 막는지 본다.
#
#   기준                                   기대
#   저장소 그대로(검사 8종)                통과
#   bad-three-networks.yml                 실패 invariant-7 (3망 안 기각, R4-1 · D-8)
#   bad-extra-port.yml                     실패 invariant-1 (관리 포트 게시)
#   bad-postgres-no-user.yml               실패 invariant-11 (postgres root 시작)
#   bad-cap-add.yml                        실패 invariant-11 (nginx 밖 cap_add)
#   bad-core-depends-realtime.yml          실패 invariant-12 (core-api → realtime 의존)
#   bad-unreceived-secret.yml              실패 invariant-5 · secret-env-files (받지 않는 비밀)
#   bad-inline-secret.yml                  실패 secret-env-files (environment:에 비밀 이름)
#   bad-latest-tag.yml                     실패 invariant-8 (latest 태그)
#   bad-profile-demo-dev.yml               실패 demo-profile (demo + dev, CD-7)
#   bad-devlogin-on.yml                    실패 demo-profile (demo에서 시험 로그인, CD-7)
#   남의 env 파일 하나만 받는 변형          실패 secret-env-files (자기 이름 env 파일 규칙, Compose 판과 무관 J346)
#   nginx dns_opt ndots를 지운 변형         실패 nginx-dns (멈춘 앱 이름을 바깥 DNS로 넘기지 않음, NFR4.13 J355)
#   ok-dev-loopback.yml (dev)              통과 (dev 127.0.0.1 게시는 허용)
#   bad-dev-public-port.yml (dev)          실패 (dev 0.0.0.0 게시)
#   배포 D3 꼴(--env-dir · --release-env)  통과(env 파일 안의 비밀은 environment: 위반이 아니다 — Compose v2.38 · v5.6 모두, J346)
#   D3 env 파일에 demo가 아닌 프로필 줄    실패 demo-profile (env 파일 자체 검사, Compose 판과 무관 J346)
#   U8 AI 키 분배 변형 3종                 실패 invariant-5(제브 키가 core-api로 · realtime에 Luna 키 없음) · secret-env-files(environment:에 JEV_API_KEY)
#   Nginx 템플릿 변형 9종                  실패 nginx (방어 지시어 빠짐 · XFF 이어 붙이기 · 5xx 상태 치환 · ws 버퍼링 · 문법 오류 · 정규식 location ·
#                                          고정 IP 업스트림(이름 재해석 없음, NFR4.13 J347) · 504 나누기 빠짐(JSON · 화면 경로, J354))
#   마이그레이션 변형                      금지 문장 실패 · 허용 문장 통과 · --allow-contract 통과
#   번들 Dockerfile 변형                   실패 bundle (compose.ci.yml 복사)
#
# 쓰는 법: bash infra/ci/test-infra-validate.sh
# 표준 출력: 사례마다 `ok <이름>` 또는 `not ok <이름>: <이유>`. 종료 코드: 0 모두 기대대로, 1 하나라도 어긋남,
#            2 전제 누락(docker · jq · openssl · shellcheck · python3) — 통과로 보지 않는다

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT
readonly VALIDATE="$ROOT/infra/bin/infra-validate"
readonly SAMPLES="$ROOT/infra/ci/invariant-samples"
readonly DEMO="$ROOT/infra/compose.demo.yml"
readonly DEV="$ROOT/infra/compose.dev.yml"

for tool in docker jq openssl shellcheck python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "test-infra-validate: 전제 누락: $tool" >&2
    exit 2
  fi
done
if ! docker info >/dev/null 2>&1; then
  echo "test-infra-validate: 전제 누락: docker 데몬" >&2
  exit 2
fi

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

failures=0
total=0
# expect <이름> <기대 종료 코드> <출력에 있어야 할 글자(빈 값이면 생략)> -- <infra-validate 인자...>
expect() {
  local name="$1" want_rc="$2" want_text="$3"
  shift 4
  total=$((total + 1))
  local rc=0
  bash "$VALIDATE" "$@" >"$work/out" 2>&1 || rc=$?
  if [ "$rc" -ne "$want_rc" ]; then
    echo "not ok $name: 종료 코드 $rc(기대 $want_rc) — $(grep -m2 -E '^(FAIL|MISSING)' "$work/out" | cut -c1-200 | paste -sd' ' -)"
    failures=$((failures + 1))
    return
  fi
  if [ -n "$want_text" ] && ! grep -qF -- "$want_text" "$work/out"; then
    echo "not ok $name: 출력에 '$want_text'가 없다 — $(grep -m2 -E '^(FAIL|MISSING)' "$work/out" | cut -c1-200 | paste -sd' ' -)"
    failures=$((failures + 1))
    return
  fi
  echo "ok $name"
}

# 1 저장소 그대로
expect "repo-all-checks" 0 "모두 통과(8건)" --

# 2 Compose 불변식 샘플(demo)
inv=(--only "compose-config,compose-invariants,secret-env-files" --compose "$DEMO")
expect "bad-three-networks" 1 "invariant-7:" -- "${inv[@]}" --compose "$SAMPLES/bad-three-networks.yml"
expect "bad-extra-port" 1 "invariant-1:" -- "${inv[@]}" --compose "$SAMPLES/bad-extra-port.yml"
expect "bad-postgres-no-user" 1 "invariant-11: postgres" -- "${inv[@]}" --compose "$SAMPLES/bad-postgres-no-user.yml"
expect "bad-cap-add" 1 "invariant-11: web에 cap_add" -- "${inv[@]}" --compose "$SAMPLES/bad-cap-add.yml"
expect "bad-core-depends-realtime" 1 "invariant-12:" -- "${inv[@]}" --compose "$SAMPLES/bad-core-depends-realtime.yml"
expect "bad-unreceived-secret(5)" 1 "invariant-5: web" -- "${inv[@]}" --compose "$SAMPLES/bad-unreceived-secret.yml"
expect "bad-unreceived-secret(files)" 1 "FAIL secret-env-files" -- "${inv[@]}" --compose "$SAMPLES/bad-unreceived-secret.yml"
expect "bad-inline-secret" 1 "environment:에 비밀 OPENAI_API_KEY" -- "${inv[@]}" --compose "$SAMPLES/bad-inline-secret.yml"
expect "bad-latest-tag" 1 "invariant-8:" -- "${inv[@]}" --compose "$SAMPLES/bad-latest-tag.yml"
prof=(--only demo-profile --compose "$DEMO")
expect "demo-profile-as-is" 0 "" -- "${prof[@]}"
expect "bad-profile-demo-dev" 1 "core-api SPRING_PROFILES_ACTIVE가 demo 하나가 아니다(demo,dev)" -- "${prof[@]}" --compose "$SAMPLES/bad-profile-demo-dev.yml"
expect "bad-devlogin-on" 1 "core-api MUJOONG_DEVLOGIN_ENABLED가 켜져 있다" -- "${prof[@]}" --compose "$SAMPLES/bad-devlogin-on.yml"
# 남의 env 파일 하나만 받는 변형(파일 수는 1). Compose v2.38은 config에서 env_file 키를 지우므로 이름 검사가 판과 무관한지 본다(J346)
cat >"$work/other-env-file.yml" <<'YAML'
services:
  web:
    env_file: !override
      - ${MUJOONG_ENV_DIR:-/opt/mujoong/env}/realtime.env
YAML
expect "env-file-of-other-service" 1 "web가 남의 env 파일을 받는다" -- --only secret-env-files --compose "$DEMO" --compose "$work/other-env-file.yml"
# nginx의 dns_opt ndots가 빠지면 내장 DNS가 멈춘 앱 이름을 바깥 DNS로 넘겨 옛 주소가 남을 수 있다(3.6 CI ⑧, J355)
cat >"$work/nginx-no-ndots.yml" <<'YAML'
services:
  nginx:
    dns_opt: !reset []
YAML
expect "nginx-dns-no-ndots" 1 "nginx-dns: nginx에 dns_opt ndots:1이 없다" -- "${inv[@]}" --compose "$work/nginx-no-ndots.yml"

# 3 dev 파일(불변식은 demo에만, dev는 127.0.0.1 게시만)
dev=(--kind dev --only "compose-config,compose-invariants,secret-env-files" --compose "$DEV")
expect "dev-as-is" 0 "" -- "${dev[@]}"
expect "ok-dev-loopback" 0 "" -- "${dev[@]}" --compose "$SAMPLES/ok-dev-loopback.yml"
expect "bad-dev-public-port" 1 "127.0.0.1이 아닌 주소" -- "${dev[@]}" --compose "$SAMPLES/bad-dev-public-port.yml"

# 4 배포 D3 꼴: 이미 렌더된 env 디렉터리 + release.env, 호스트 MemTotal 70% 상한
bash "$ROOT/infra/ci/gen-env.sh" --out "$work/d3/env" >/dev/null
printf 'MUJOONG_REGISTRY=ghcr.io/example\nMUJOONG_TAG=0123456789abcdef0123456789abcdef01234567\n' >"$work/d3/release.env"
expect "d3-env-dir" 0 "" -- --only compose-invariants,secret-env-files --env-dir "$work/d3/env" \
  --release-env "$work/d3/release.env" --mem-limit-bytes 5700000000
expect "d3-memtotal-too-small" 1 "invariant-3:" -- --only compose-invariants --env-dir "$work/d3/env" \
  --release-env "$work/d3/release.env" --mem-limit-bytes 4000000000
# env 파일 안의 비밀(값이 든 렌더 결과)은 environment: 위반이 아니다 — Compose v2.38이 env 파일을 펼쳐도 같다(J346)
expect "d3-env-file-secrets-are-not-environment" 0 "PASS secret-env-files" -- --only secret-env-files,demo-profile \
  --env-dir "$work/d3/env" --release-env "$work/d3/release.env"
cp -a "$work/d3/env" "$work/d3/env-profile"
printf 'SPRING_PROFILES_ACTIVE=demo,dev\n' >>"$work/d3/env-profile/realtime.env"
expect "d3-env-file-profile-line" 1 "realtime env 파일에 demo가 아닌 SPRING_PROFILES_ACTIVE" -- --only demo-profile \
  --env-dir "$work/d3/env-profile" --release-env "$work/d3/release.env"
# U8(SD3): OPENAI_API_KEY는 core-api · realtime, JEV_API_KEY는 realtime만 받는다(분배표). 위 d3-env-dir이 바른 분배를 통과시키고,
# 아래 변형은 제브 키가 core-api로 새거나 realtime에서 Luna 키가 빠지면 invariant-5로 막히는지 본다
cp -a "$work/d3/env" "$work/d3/env-jev-core"
printf 'JEV_API_KEY=\n' >>"$work/d3/env-jev-core/core-api.env"
expect "u8-jev-key-leaks-to-core-api" 1 "invariant-5: core-api 비밀 이름" -- --only compose-invariants \
  --env-dir "$work/d3/env-jev-core" --release-env "$work/d3/release.env" --mem-limit-bytes 5700000000
cp -a "$work/d3/env" "$work/d3/env-no-openai"
grep -v '^OPENAI_API_KEY=' "$work/d3/env/realtime.env" >"$work/d3/env-no-openai/realtime.env"
expect "u8-openai-key-missing-in-realtime" 1 "invariant-5: realtime 비밀 이름" -- --only compose-invariants \
  --env-dir "$work/d3/env-no-openai" --release-env "$work/d3/release.env" --mem-limit-bytes 5700000000
cat >"$work/inline-jev.yml" <<'YAML'
services:
  realtime:
    environment:
      JEV_API_KEY: placeholder-not-a-secret
YAML
expect "u8-inline-jev-key" 1 "environment:에 비밀 JEV_API_KEY" -- "${inv[@]}" --compose "$work/inline-jev.yml"

# 5 Nginx 템플릿 변형(사본을 고쳐 --nginx-src로 준다)
nginx_variant() {
  local name="$1" file="$2" script="$3"
  rm -rf "$work/ngx-$name"
  cp -a "$ROOT/infra/nginx" "$work/ngx-$name"
  python3 -I - "$work/ngx-$name/$file" "$script" <<'PY'
import sys
path, script = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
old, new = script.split("=>", 1)
if old not in text:
    sys.exit("변형 대상 글자가 없다: " + old)
open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))
PY
}
nginx_variant no-reject conf.d/https443.conf.tmpl "    ssl_reject_handshake on;=>"
expect "nginx-no-reject-handshake" 1 "ssl_reject_handshake on;" -- --only nginx --nginx-src "$work/ngx-no-reject"
# shellcheck disable=SC2016  # Nginx 변수를 글자 그대로 바꾼다
nginx_variant xff snippets/proxy-common.conf 'proxy_set_header X-Forwarded-For $remote_addr;=>proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;'
expect "nginx-xff-append" 1 'proxy_add_x_forwarded_for 사용' -- --only nginx --nginx-src "$work/ngx-xff"
nginx_variant status-swap snippets/error-json.conf 'error_page 502 /__mujoong/error/502.json;=>error_page 502 =503 /__mujoong/error/502.json;'
expect "nginx-5xx-status-swap" 1 "5xx 상태 치환" -- --only nginx --nginx-src "$work/ngx-status-swap"
nginx_variant ws-buffering conf.d/https443.conf.tmpl "        proxy_buffering off;=>"
expect "nginx-ws-buffering" 1 "proxy_buffering off" -- --only nginx --nginx-src "$work/ngx-ws-buffering"
nginx_variant syntax conf.d/https443.conf.tmpl "    ssl_session_tickets off;=>    ssl_session_tickets off"
expect "nginx-syntax-error" 1 "nginx -t https/compose" -- --only nginx --nginx-src "$work/ngx-syntax"
nginx_variant regex-location conf.d/https443.conf.tmpl '    location ^~ /internal/ {=>    location ~ ^/internal/ {'
expect "nginx-regex-location" 1 "정규식 location" -- --only nginx --nginx-src "$work/ngx-regex-location"
# 고정 IP 업스트림으로 되돌리면 멈춘 앱이 3초 뒤 504가 된다(NFR4.13, J347) — 이름 재해석(resolve)이 빠지면 실패
nginx_variant fixed-ip upstreams/compose.conf "    server core-api:8080 resolve;=>    server 172.30.10.12:8080;"
expect "nginx-fixed-ip-upstream" 1 "업스트림 mujoong_core_api가 core-api:8080 resolve가 아니다" -- --only nginx --nginx-src "$work/ngx-fixed-ip"
# 504 나누기가 빠지면 멈춘 앱의 옛 주소로 연결 시한이 지난 요청이 504가 된다(NFR4.13 · RD-16, J354) — JSON 경로 · 화면 경로 각각
nginx_variant no-504-split snippets/error-json.conf "error_page 504 = @mujoong_upstream_504;=>error_page 504 /__mujoong/error/504.json;"
expect "nginx-504-not-split" 1 "error_page 504 나누기(@mujoong_upstream_504) 연결 없음" -- --only nginx --nginx-src "$work/ngx-no-504-split"
nginx_variant screen-504 conf.d/https443.conf.tmpl "        error_page 504 = @mujoong_screen_504;=>        error_page 504 /__mujoong/maintenance.html;"
expect "nginx-screen-504-not-split" 1 "화면 경로(location ^~ /_next/static/)의 502 · 504 점검 페이지 · 504 나누기가 없다" -- --only nginx --nginx-src "$work/ngx-screen-504"

# 6 마이그레이션 expand 검사
mig() {
  local name="$1" sql="$2"
  mkdir -p "$work/mig-$name"
  printf 'CREATE TABLE core.sample (id uuid PRIMARY KEY, label varchar(20) NOT NULL);\nALTER TABLE core.sample ADD CONSTRAINT sample_label_ck CHECK (label <> %s);\n' "''" \
    >"$work/mig-$name/V1__base.sql"
  printf '%s\n' "$sql" >"$work/mig-$name/V2__change.sql"
}
mig drop-column "ALTER TABLE core.sample DROP COLUMN label;"
expect "migration-drop-column" 1 "V2__change.sql 문장 1: 삭제" -- --only migrations --migrations "$work/mig-drop-column"
mig rename "ALTER TABLE core.sample RENAME COLUMN label TO title;"
expect "migration-rename" 1 "이름 변경" -- --only migrations --migrations "$work/mig-rename"
mig type "ALTER TABLE core.sample ALTER COLUMN label TYPE varchar(40);"
expect "migration-type-change" 1 "타입 변경" -- --only migrations --migrations "$work/mig-type"
mig not-null "ALTER TABLE core.sample ADD COLUMN note varchar(10) NOT NULL;"
expect "migration-add-not-null" 1 "기본값 없는 널 불허" -- --only migrations --migrations "$work/mig-not-null"
mig constraint "ALTER TABLE core.sample ADD CONSTRAINT sample_label_len CHECK (length(label) > 2);"
expect "migration-strengthen-constraint" 1 "기존 표 제약 강화" -- --only migrations --migrations "$work/mig-constraint"
mig expand "ALTER TABLE core.sample ADD COLUMN note varchar(10) DEFAULT 'x' NOT NULL; ALTER TABLE core.sample ADD COLUMN memo text; CREATE INDEX sample_label_ix ON core.sample (label);"
expect "migration-expand-ok" 0 "" -- --only migrations --migrations "$work/mig-expand"
expect "migration-allow-contract" 0 "" -- --only migrations --migrations "$work/mig-drop-column" --allow-contract
# 승인 목록: 파일 · 문장 번호 · 문장 해시가 모두 맞는 문장만 넘긴다(다른 문장 · 형식이 틀린 줄은 실패)
mkdir -p "$work/mig-approved/core"
printf 'CREATE TABLE core.sample (id uuid PRIMARY KEY, label varchar(20) NOT NULL);\n' >"$work/mig-approved/core/V1__base.sql"
printf 'ALTER TABLE core.sample ADD CONSTRAINT sample_label_len CHECK (length(label) > 2);\n' >"$work/mig-approved/core/V2__change.sql"
approved_hash="$(printf '%s' 'ALTER TABLE CORE.SAMPLE ADD CONSTRAINT SAMPLE_LABEL_LEN CHECK (LENGTH(LABEL) > 2)' | sha256sum | cut -c1-12)"
printf '# 시험\ncore/V2__change.sql 1 %s | 승인: 시험 2026-10-13 | 사유: 시험 표\n' "$approved_hash" >"$work/approvals-ok.txt"
expect "migration-approved-statement" 0 "" -- --only migrations --migrations "$work/mig-approved/core" \
  --migration-approvals "$work/approvals-ok.txt"
printf 'core/V2__change.sql 1 000000000000 | 승인: 시험 2026-10-13 | 사유: 다른 문장의 해시\n' >"$work/approvals-other.txt"
expect "migration-approval-other-statement" 1 "기존 표 제약 강화" -- --only migrations --migrations "$work/mig-approved/core" \
  --migration-approvals "$work/approvals-other.txt"
printf 'core/V2__change.sql 1 %s | 사유: 승인한 사람이 없다\n' "$approved_hash" >"$work/approvals-unsigned.txt"
expect "migration-approval-unsigned" 1 "승인 목록 1행" -- --only migrations --migrations "$work/mig-approved/core" \
  --migration-approvals "$work/approvals-unsigned.txt"
# 저장소 승인 목록(infra/migration-approvals.txt)으로 실제 두 위치가 통과한다
expect "migration-repo-approvals" 0 "" -- --only migrations

# 7 번들 Dockerfile 변형
mkdir -p "$work/bundle"
cp "$ROOT/infra/bundle/Dockerfile" "$work/bundle/Dockerfile"
cp "$ROOT/infra/bundle/Dockerfile.dockerignore" "$work/bundle/Dockerfile.dockerignore"
printf 'COPY compose.ci.yml /bundle/compose.ci.yml\n' >>"$work/bundle/Dockerfile"
expect "bundle-copies-ci-file" 1 "번들 목록 밖을 복사한다: compose.ci.yml" -- --only bundle --bundle-dockerfile "$work/bundle/Dockerfile"

echo "test-infra-validate: ${total}건 중 어긋남 ${failures}건"
if [ "$failures" -gt 0 ]; then
  exit 1
fi
