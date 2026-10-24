#!/usr/bin/env bash
# 무중 검증 명령 B — compose 모드 기동과 3종 호출 (U1 3.5 Step 49 · 계획 ## 검증 명령(C안) B 1 ~ 5 · J108 · J136 · J144 ·
# SEC-02 · SEC-08 · SEC-12 · SD-3 · SD-14 · CD-7 · R4-15 · R3-21)
#
# 쓰는 법: bash scripts/verify/b-stack.sh [--engine]
#   (기본)    infra/ci/build-images.sh로 검증 스택 이미지를 로컬 태그(mujoong.local/mujoong-*:verify)로 만든 뒤 아래를 돈다
#   --engine  이미지를 만들지 않는다(scripts/verify.sh E0가 라벨 지문을 이미 대조했다, J134 (가))
# 순서:
#   준비  gen-env(서비스별 비밀, umask 077) · gen-selfsigned(CA · localhost) · render-nginx · render-pgconf
#   B1    병합 설정(demo + ci): config --quiet 유효, config --format json을 umask 077 임시 파일로만 받아 단언 —
#         호스트 게시 포트는 nginx 127.0.0.1:8443→443 하나, 서비스별 비밀 변수 이름 = 인프라 명세 3.7 분배표,
#         certbot · backup 없음, realtime 공급자 주소 = 가짜 공급자(J141). 출력 파일은 바로 지운다
#   B2    postgres(init) → `--profile migrate run --rm migrate`(소유자 · 두 위치) → 시드 001-reference.sql 두 번 → 정책 1 · 문구 2
#   B5    프로필 가드(SD-14 · CD-7 · SEC-08): 빌드한 core-api · realtime 이미지를 `run --rm --no-deps`로
#         (i) demo + MUJOONG_DEVLOGIN_ENABLED=true (ii) demo,dev 로 띄워 60초 안에 비0 + MUJOONG_STARTUP_REJECTED 줄.
#         앱을 올리기 전에 돈다(run은 서비스의 고정 IP를 그대로 써서, 실행 중인 같은 서비스와 주소가 겹친다)
#   B3    up -d --wait: realtime · core-api readiness → web → nginx, fake-provider
#   B4    입구 단언(lib.sh entry_assertions): health · /device · WSS 101 → 4401 · /internal · /actuator 403 ·
#         64KB+1 → 400 봉투 · 위조 X-Trace-Id · 원시 URI 우회 400 · SNI · Host other.invalid 거절
# 출력: 단언마다 PASS · FAIL 한 줄. docker compose config 펼침 · 비밀 값은 출력하지 않는다.
# 종료 코드: 0 통과, 1 단언 실패, 2 전제 누락

set -Eeuo pipefail
# shellcheck source=scripts/verify/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$VERIFY_ROOT"

engine=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --engine) engine=1 ;;
    *) echo "b-stack: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done

docker_usable || die_prereq "Docker 데몬에 닿지 못한다"
compose_version_ok || die_prereq "Docker Compose v2.24.4 이상이 아니다(!override, J136)"
for tool in jq openssl curl node; do
  have "$tool" || die_prereq "$tool이 없다"
done
[ -f tools/node_modules/ws/package.json ] || die_prereq "tools/node_modules가 없다(cd tools && npm ci)"

PG_IMAGE="$(pg_image)"
readonly PG_IMAGE
readonly BACKEND_NET="${VERIFY_PROJECT}_backend"

# ---- 준비 ------------------------------------------------------------------------------------------------------------
log "준비: env · 인증서 · nginx · postgres 설정"
bash infra/ci/gen-env.sh --out "$VERIFY_ENV_DIR" --service-host "$VERIFY_SERVICE_HOST" --origin "$VERIFY_ORIGIN" \
  --registry "$VERIFY_REGISTRY" --tag "$VERIFY_TAG"
bash infra/ci/gen-selfsigned.sh --out "$VERIFY_CERT_DIR" --host "$VERIFY_SERVICE_HOST"
bash infra/bin/render-nginx --out "$VERIFY_DIR/nginx" --env-file "$VERIFY_ENV_DIR/nginx.env"
bash infra/bin/render-pgconf --out "$VERIFY_DIR/postgres/postgresql.conf"
chmod -R go+rX "$VERIFY_DIR/nginx" "$VERIFY_DIR/postgres"

if [ "$engine" -eq 0 ]; then
  build_args=(--registry "$VERIFY_REGISTRY" --tag "$VERIFY_TAG" --only "$(IFS=,; echo "${VERIFY_STACK_IMAGES[*]}")"
    --log-dir "$VERIFY_LOG_DIR/images")
  [ -n "${MUJOONG_VERIFY_START_FINGERPRINT:-}" ] && build_args+=(--fingerprint "$MUJOONG_VERIFY_START_FINGERPRINT")
  # 샌드박스 빌드 규칙(R-03 · Step 43): 이 CA 묶음이 있는 곳(에이전트 샌드박스)에서만 BuildKit 비밀로 넘긴다. CI는 넘기지 않는다
  if [ -z "${CI:-}" ] && [ -r /root/.ccr/ca-bundle.crt ]; then
    build_args+=(--extra-ca /root/.ccr/ca-bundle.crt)
  fi
  log "이미지 빌드: ${VERIFY_STACK_IMAGES[*]}"
  rc=0
  bash infra/ci/build-images.sh "${build_args[@]}" || rc=$?
  case "$rc" in
    0) pass B0.images "이미지 ${#VERIFY_STACK_IMAGES[@]}종 빌드(라벨: revision · 소스 지문)" ;;
    2) die_prereq "이미지 빌드 전제 누락(build-images.sh 종료 코드 2)" ;;
    *) die_assert B0.images "이미지 빌드 실패(종료 코드 $rc, logs/images/)" ;;
  esac
else
  for name in "${VERIFY_STACK_IMAGES[@]}"; do
    docker image inspect "$VERIFY_REGISTRY/mujoong-$name:$VERIFY_TAG" >/dev/null 2>&1 ||
      die_assert B0.images "미리 만든 이미지가 없다: mujoong-$name"
  done
  pass B0.images "미리 만든 이미지 사용(--engine, 지문은 E0에서 대조)"
fi

# ---- B1 병합 설정 단언 ----------------------------------------------------------------------------------------------
if ! dc --profile migrate config --quiet >"$VERIFY_LOG_DIR/b1-config.err" 2>&1; then
  die_assert B1.config "demo + ci 병합 설정이 유효하지 않다(config --quiet, logs/b1-config.err)"
fi
pass B1.config "demo + ci 병합 설정 config --quiet 유효"

b1_assertions() {
  local tmp json
  tmp="$(umask 077 && mktemp -d)"
  json="$tmp/config.json"
  # 펼친 설정은 비밀 값을 담는다: umask 077 임시 파일에만 두고, 출력에는 단언 결과 줄만 쓴다(SEC-02 · CD-11)
  if ! (umask 077 && dc --profile migrate config --format json >"$json" 2>/dev/null); then
    rm -rf "$tmp"
    die_assert B1.json "config --format json 실패"
  fi
  local ports
  ports="$(jq -c '[.services | to_entries[] | .key as $s | (.value.ports // [])[] |
      {service: $s, host_ip: (.host_ip // ""), published: (.published // "" | tostring), target: .target}]' "$json")"
  if [ "$ports" = '[{"service":"nginx","host_ip":"127.0.0.1","published":"8443","target":443}]' ]; then
    pass B1.ports "호스트 게시 포트는 nginx 127.0.0.1:8443→443 하나"
  else
    fail B1.ports "호스트 게시 포트가 nginx 127.0.0.1:8443 하나가 아니다: $(jq -r '[.[] | "\(.service):\(.host_ip):\(.published)->\(.target)"] | join(" ")' <<<"$ports")"
  fi
  # 서비스별 비밀 변수 이름(이름만 비교한다. 값은 jq 안에서만 읽힌다)
  local names_json expected actual
  names_json="$(printf '%s\n' "${VERIFY_SECRET_NAMES[@]}" | jq -R . | jq -sc .)"
  actual="$(jq -c --argjson secret "$names_json" '[.services | to_entries[] |
      {key, value: ([(.value.environment // {}) | keys[] | select(. as $k | $secret | index($k))] | sort)}] | from_entries' "$json")"
  # U8(SD3) · U15: AI 키의 기존 분배를 유지하고 기존 NEO4J_AUTH를 core-api에도 전달한다(검증에서는 빈 값).
  expected='{"core-api":["GOOGLE_OAUTH_CLIENT_SECRET","INTERNAL_SERVICE_TOKEN","MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET","NEO4J_AUTH","OPENAI_API_KEY","SPRING_DATASOURCE_PASSWORD"],"fake-provider":[],"migrate":["DB_OWNER_PASSWORD"],"nginx":[],"postgres":["DB_BACKUP_PASSWORD","DB_CORE_PASSWORD","DB_OWNER_PASSWORD","DB_REALTIME_PASSWORD","POSTGRES_PASSWORD"],"realtime":["INTERNAL_SERVICE_TOKEN","JEV_API_KEY","MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET","OPENAI_API_KEY","SPRING_DATASOURCE_PASSWORD"],"web":[]}'
  if [ "$(jq -S -c . <<<"$actual")" = "$(jq -S -c . <<<"$expected")" ]; then
    pass B1.secrets "서비스별 비밀 변수 이름 = 인프라 명세 3.7 분배표(7개 서비스)"
  else
    fail B1.secrets "비밀 변수 분배가 3.7 표와 다르다: $(jq -r --argjson e "$expected" 'to_entries | map(select(.value != ($e[.key] // "없음"))) | map(.key) | join(",")' <<<"$actual") (이름 비교, 값 미출력)"
  fi
  local present
  present="$(jq -r '[.services | keys[] | select(. == "certbot" or . == "backup")] | join(",")' "$json")"
  if [ -z "$present" ]; then pass B1.disabled "certbot · backup 꺼짐"; else fail B1.disabled "검증 스택에 켜진 서비스: $present"; fi
  if jq -e '.services.realtime.environment.MUJOONG_PROVIDER_URL | startswith("ws://fake-provider:")' "$json" >/dev/null 2>&1; then
    pass B1.provider "realtime 공급자 주소 = 가짜 공급자(실공급자 아님, J141)"
  else
    fail B1.provider "realtime 공급자 주소가 가짜 공급자가 아니다"
  fi
  rm -rf "$tmp"
}
b1_assertions
finish_asserts

# ---- B2 postgres · migrate · 시드 ------------------------------------------------------------------------------------
log "B2: postgres 기동"
if ! dc up -d --wait --wait-timeout "$(bounded 120)" postgres; then
  die_assert B2.postgres "postgres가 healthy가 되지 않았다"
fi
pass B2.postgres "postgres(init: 역할 · 스키마 · pg_hba) healthy"
log "B2: migrate"
if ! timeout "$(bounded 180)" docker compose -p "$VERIFY_PROJECT" --env-file "$VERIFY_ENV_DIR/compose.env" \
  -f infra/compose.demo.yml -f infra/compose.ci.yml --profile migrate run --rm migrate; then
  die_assert B2.migrate "migrate가 종료 코드 0으로 끝나지 않았다"
fi
pass B2.migrate "migrate(mujoong_owner, core → realtime) 종료 코드 0"

# owner_psql <psql 인자...> — backend 망 일회성 컨테이너. 비밀번호는 env 파일 → 컨테이너 환경으로만(명령줄 인자 · -e 금지)
owner_psql() {
  docker run --rm -i --network "$BACKEND_NET" --cap-drop ALL --security-opt no-new-privileges:true \
    --env-file "$VERIFY_ENV_DIR/migrate.env" -v "$VERIFY_ROOT/infra/seed:/seed:ro" --entrypoint sh "$PG_IMAGE" -c \
    'PGPASSWORD="$DB_OWNER_PASSWORD" exec psql -X -q -h postgres -U mujoong_owner -d mujoong -v ON_ERROR_STOP=1 "$@"' \
    owner-psql "$@"
}
# U4b(FR11.4): 시드 순서 001 → 002 → 003. 003이 참여 확인 · 확인 질문 · 공백 안내 문구 6종의 승인 버전을 넣는다
# U8(BR1.1): 004가 음성 없는 승인 발화(APPROVED_SPEECH) 5종을 넣고 HELP_OFFER를 v2로 올린다
# 005: 새 승인 음성 판 8종(INTRO_FREE_TALK v3 등)을 넣고 이전 판을 RETIRED로 바꾼다. 자유대화 첫 안내는 이 판의 음성이
#   service-policy/notice-assets.json과 맞아야 재생된다(맞지 않으면 C8에서 안내가 오지 않는다). 매 회차 001 ~ 004 뒤에 둔다
# 006: 연습 대화 C안의 음성 없는 고정 승인 발화(APPROVED_SPEECH) 6종을 넣는다. 매 회차 005 뒤에 둔다
for i in 1 2; do
  for seed in 001-reference.sql 002-u2-reference.sql 003-u4a-reference.sql 004-u8-reference.sql 005-spoken-audio-reference.sql \
    006-practice-direct-ask-reference.sql; do
    if ! owner_psql --single-transaction -f "/seed/$seed"; then
      die_assert B2.seed "시드 $seed ${i}회차 실패"
    fi
  done
done
counts="$(owner_psql -At -c "SELECT count(*) FILTER (WHERE c.content_code = 'CONSENT_POLICY'), count(*) FILTER (WHERE c.content_code IN ('CLOSING','WAITING_FILLER')) FROM core.core_approved_content c JOIN core.core_approved_content_version v ON v.content_id = c.id WHERE v.status = 'APPROVED'" || true)"
if [ "$counts" = "1|2" ]; then
  pass B2.seed "시드 001-reference.sql 두 번 적용(멱등) → 정책 1 · 승인 문구 2(R4-15)"
else
  die_assert B2.seed "시드 결과가 정책 1 · 문구 2가 아니다(${counts:-없음})"
fi
session_prompts="$(owner_psql -At -c "SELECT count(DISTINCT c.content_code) FROM core.core_approved_content c JOIN core.core_approved_content_version v ON v.content_id = c.id WHERE v.status = 'APPROVED' AND c.content_code IN ('INTRO_FREE_TALK','INTRO_PRACTICE','RE_PROMPT','DECLINE_ALTERNATIVE','SILENCE_WARNING','STOP_CONFIRM')" || true)"
if [ "$session_prompts" = "6" ]; then
  pass B2.seed003 "시드 002 · 003 두 번 적용(멱등) → 세션 승인 문구 6종 APPROVED(U4b 참여 확인 · 공백 안내)"
else
  die_assert B2.seed003 "세션 승인 문구 APPROVED가 6종이 아니다(${session_prompts:-없음})"
fi
direct_ask_speech="$(owner_psql -At -c "SELECT count(DISTINCT c.content_code) FROM core.core_approved_content c JOIN core.core_approved_content_version v ON v.content_id = c.id WHERE v.status = 'APPROVED' AND v.audio_path IS NULL AND c.content_type = 'APPROVED_SPEECH' AND c.content_code IN ('VARIANT_OFFER','RETRY_PROMPT','INPUT_CONFIRM','SCENE_CLOSE','WAIT_LEAD','ASK_DEFAULT')" || true)"
if [ "$direct_ask_speech" = "6" ]; then
  pass B2.seed006 "시드 006 두 번 적용(멱등) → 연습 C안 고정 승인 발화 6종 APPROVED(음성 없음)"
else
  die_assert B2.seed006 "연습 C안 고정 승인 발화 APPROVED가 6종이 아니다(${direct_ask_speech:-없음})"
fi

# ---- B5 프로필 가드(앱을 올리기 전) -----------------------------------------------------------------------------------
guard_run() {
  local svc="$1" name="$2"
  shift 2
  local out="$VERIFY_LOG_DIR/b5-$svc-$name.log" rc=0
  timeout 60 docker compose -p "$VERIFY_PROJECT" --env-file "$VERIFY_ENV_DIR/compose.env" \
    -f infra/compose.demo.yml -f infra/compose.ci.yml run --rm --no-deps "$@" "$svc" >"$out" 2>&1 || rc=$?
  if [ "$rc" -eq 0 ] || [ "$rc" -eq 124 ]; then
    echo "FAIL B5.$svc.$name: 60초 안에 기동을 거부하지 않았다(rc=$rc)" >&2
    return 1
  fi
  if ! grep -qF 'MUJOONG_STARTUP_REJECTED ' "$out"; then
    echo "FAIL B5.$svc.$name: 기동 거부 줄(MUJOONG_STARTUP_REJECTED) 없이 끝났다(rc=$rc)" >&2
    return 1
  fi
  return 0
}
guard_case() {
  local name="$1"
  shift
  local pids=() svc rc=0
  for svc in core-api realtime; do
    guard_run "$svc" "$name" "$@" &
    pids+=("$!")
  done
  local i=0
  for svc in core-api realtime; do
    if wait "${pids[$i]}"; then
      pass "B5.$svc.$name" "$svc 이미지가 $name 구성에서 기동 거부(비0 · MUJOONG_STARTUP_REJECTED)"
    else
      fail "B5.$svc.$name" "$svc 이미지가 $name 구성에서 기동을 거부하지 않았다(logs/b5-$svc-$name.log)"
      rc=1
    fi
    i=$((i + 1))
  done
  return "$rc"
}
log "B5: 프로필 가드"
guard_case demo-devlogin -e SPRING_PROFILES_ACTIVE=demo -e MUJOONG_DEVLOGIN_ENABLED=true || true
guard_case demo-dev -e SPRING_PROFILES_ACTIVE=demo,dev || true
finish_asserts

# ---- B3 기동 ----------------------------------------------------------------------------------------------------------
log "B3: up --wait"
if ! dc up -d --wait --wait-timeout "$(bounded 240)"; then
  dc ps -a --format '{{.Service}} {{.State}} {{.Health}}' >&2 || true
  die_assert B3.up "검증 스택이 healthy가 되지 않았다(logs/compose-final.log)"
fi
unhealthy="$(dc ps --format '{{.Service}} {{.State}} {{.Health}}' | awk '$2 != "running" || ($3 != "" && $3 != "healthy")' || true)"
if [ -n "$unhealthy" ]; then
  die_assert B3.up "건강하지 않은 서비스: $(paste -sd, - <<<"$unhealthy")"
fi
pass B3.up "nginx · web · core-api · realtime · postgres · fake-provider running/healthy"

# ---- B4 입구 단언 -----------------------------------------------------------------------------------------------------
entry_assertions
finish_asserts
