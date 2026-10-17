#!/usr/bin/env bash
# 무중 이미지 점검 1 ~ 8 (U1 3.5 Step 48 · cicd 2.3.2 · 4절 CD-2 · CD-7 · CD-8 · R4-12 · R4-16 · R4-17 · SD-16 · SD-25)
#
# CI images 작업과 로컬이 infra/ci/build-images.sh로 만든 이미지(기본 mujoong.local/mujoong-*:verify)를 점검한다.
#   ① 이미지 설정(ENV · 라벨 · 기록)에 비밀 변수 이름 · 키 형식 · 프록시 · CA 값이 없고, 우리가 넣은 파일(앱 클래스 · 화면 번들 ·
#      SQL · 번들)에 키 형식 문자열이 없으며, 브라우저 번들(.next/static)에 비밀 변수 이름이 없다
#   ② 비루트 실행(앱 · migrate · backup · 시험 도구), 베이스 이미지 태그 고정(latest 없음)
#   ③ 취약점 스캔(Trivy, high 이상, ci/security-exceptions.yml 예외). 도구가 없으면 SKIP(미실행)으로 보고하고 실패로 세지 않는다
#   ④ demo 프로필 더미 기동: postgres(999 · cap_drop ALL · read_only · tmpfs) → migrate → 시드 두 번(정책 1 · 문구 2) →
#      두 앱 readiness UP(공급자 접속 없음) → backup 스케줄러 생존 + 더미 덤프 1회 OK → 프로필 가드(demo + dev, demo + 시험 로그인)와
#      필수 비밀 누락이 기동을 거부(CD-7)
#   ⑤ 번들 이미지 파일 목록(환경 파일 · compose.ci.yml · compose.dev.yml · ci/ 0건)과 번들의 compose.demo.yml 불변식 통과
#   ⑥ 앱 이미지에 시험 클래스 · 시험 설정 · 시험 라이브러리 0건(SD-25)
#   ⑦ web을 실제 옵션(read_only · cap_drop ALL · tmpfs · HOSTNAME=0.0.0.0 · PORT=3000)으로 띄워 컨테이너 안 127.0.0.1:3000/healthz 200(R4-16)
#   ⑧ 업스트림이 없는 구성에서 / · /api/health · /ws/device가 상태 502를 유지하고 본문만 점검 페이지 · JSON(UPSTREAM_UNAVAILABLE)이며,
#      앱이 낸 503은 그대로 통과한다(R4-17 · NFR4.13). 앱 컨테이너 셋을 멈추면(docker stop, 5회 되풀이) 첫 요청은 연결 시한 3초 +
#      여유(4.5초) 안, 그다음 요청은 1초 안에 502(본문 그대로)이고 다시 띄우면 앱 응답(J347 · J354 · J355). 연결한 뒤 답하지 않는
#      앱(hang)은 읽기 시한 15초 뒤 504(봉투 · 점검 페이지, J354). Nginx DNS 옵션은 compose.demo.yml nginx의 dns_opt 그대로(J355)
#
# 쓰는 법: bash infra/ci/image-checks.sh [--registry <레지스트리>] [--tag <태그>] [--only 1,2,...] [--image <이름>=<참조>]...
#   이름: core-api realtime web migrate backup infra fake-provider. --image로 하나씩 바꿀 수 있다(시험용).
# 출력: 점검마다 `PASS ⑤` · `FAIL ⑤: <사유>` · `SKIP ③: <사유>` 한 줄. 비밀 값 · 설정 펼침은 출력하지 않는다(시험 값은 실행 때 만든다, J127).
# 종료 코드: 0 FAIL 없음, 1 FAIL 있음, 2 전제 누락(docker · 이미지 · openssl · jq), 64 인자 오류
# 네트워크: ④는 Compose 프로젝트 mujoong-imgchk(demo 망 서브넷 172.30.10.0/24 · 172.30.20.0/24)를 쓰므로 같은 서브넷의
# 다른 스택(검증 스택 mujoong-verify 등)과 동시에 돌리지 않는다. ⑧은 따로 172.30.99.0/24 시험 망을 만든다.

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT
readonly NAMES=(core-api realtime web migrate backup infra fake-provider)

registry=mujoong.local
tag=verify
only="1,2,3,4,5,6,7,8"
declare -A override=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --registry) registry="${2:-}"; shift ;;
    --tag) tag="${2:-}"; shift ;;
    --only) only="${2:-}"; shift ;;
    --image)
      if ! [[ "${2:-}" =~ ^(core-api|realtime|web|migrate|backup|infra|fake-provider)=.+$ ]]; then
        echo "image-checks: --image는 <이름>=<참조>다" >&2
        exit 64
      fi
      override["${2%%=*}"]="${2#*=}"
      shift
      ;;
    -h | --help) sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "image-checks: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done
if ! [[ "$only" =~ ^[1-8](,[1-8])*$ ]]; then
  echo "image-checks: --only는 1 ~ 8 쉼표 목록이다" >&2
  exit 64
fi
selected() { [[ ",$only," == *",$1,"* ]]; }

ref() {
  local name="$1"
  if [ -n "${override[$name]:-}" ]; then
    echo "${override[$name]}"
  else
    echo "$registry/mujoong-$name:$tag"
  fi
}

for tool in docker jq openssl; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "image-checks: 전제 누락: $tool" >&2
    exit 2
  fi
done
if ! docker info >/dev/null 2>&1; then
  echo "image-checks: 전제 누락: docker 데몬" >&2
  exit 2
fi
absent=()
for name in "${NAMES[@]}"; do
  docker image inspect "$(ref "$name")" >/dev/null 2>&1 || absent+=("$(ref "$name")")
done
if [ "${#absent[@]}" -gt 0 ]; then
  echo "image-checks: 전제 누락: 이미지가 없다(bash infra/ci/build-images.sh 먼저): ${absent[*]}" >&2
  exit 2
fi

umask 077
work="$(mktemp -d)"
project=mujoong-imgchk
edge_net=mujoong-imgchk-edge8
cleanup() {
  local rc=$?
  if [ -f "$work/compose.env" ]; then
    MUJOONG_VERIFY_DIR="$work/verify" docker compose -p "$project" --env-file "$work/compose.env" \
      -f "$ROOT/infra/compose.demo.yml" -f "$ROOT/infra/compose.ci.yml" -f "$work/imgchk.yml" \
      down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  docker rm -f imgchk-web imgchk-ngx imgchk-up11 imgchk-up12 imgchk-up13 >/dev/null 2>&1 || true
  docker network rm "$edge_net" >/dev/null 2>&1 || true
  [ -n "${cid_cleanup:-}" ] && docker rm -f "$cid_cleanup" >/dev/null 2>&1
  rm -rf "$work"
  exit "$rc"
}
trap cleanup EXIT

fails=0
pass() { echo "PASS $1"; }
fail() {
  echo "FAIL $1: $2"
  fails=$((fails + 1))
}
skip() { echo "SKIP $1: $2"; }
short_err() { head -n 3 "$1" | cut -c1-240 | tr '\n' ' ' | sed 's/ $//'; }

# 키 형식(값 모양) — 출력은 파일 이름 · 건수만. 비밀 스캔(a-secrets)이 이 파일을 키로 오인하지 않게 개인 키 머리글은 글자를 나눠 적는다
readonly KEY_PATTERNS='AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,}|(^|[^A-Za-z0-9_-])sk-[A-Za-z0-9_-]{20,}|-----BEGIN ([A-Z]+ )?PRIV''ATE KEY-----|xox[abprs]-[A-Za-z0-9-]{10,}|[a-z][a-z0-9+.-]*://[^/[:space:]:@"'\'']+:[^/[:space:]@"'\'']+@[A-Za-z0-9.-]+'
readonly SECRET_NAME_PATTERN='POSTGRES_PASSWORD|DB_(OWNER|CORE|REALTIME|BACKUP)_PASSWORD|INTERNAL_SERVICE_TOKEN|OPENAI_API_KEY|JEV_API_KEY|MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET|GOOGLE_OAUTH_CLIENT_SECRET|GHCR_PULL_TOKEN|NEO4J_AUTH|SPRING_DATASOURCE_PASSWORD'
readonly PROXY_PATTERN='https?_proxy|HTTPS?_PROXY|NO_PROXY|ca-bundle|\.ccr|extra_ca|proxy_env'

# copy_out <이미지> <경로> <대상 디렉터리> — 컨테이너를 실행하지 않고 파일을 꺼낸다
copy_out() {
  local cid
  cid="$(docker create "$1" /none 2>/dev/null || docker create "$1")"
  cid_cleanup="$cid"
  mkdir -p "$3"
  docker cp "$cid:$2" "$3/" >/dev/null 2>&1 || { docker rm -f "$cid" >/dev/null 2>&1; cid_cleanup=""; return 1; }
  docker rm -f "$cid" >/dev/null 2>&1
  cid_cleanup=""
}

# ---- ① ------------------------------------------------------------------------------------------------
check1() {
  local problems=() name img
  for name in "${NAMES[@]}"; do
    img="$(ref "$name")"
    docker image inspect "$img" --format '{{json .Config.Env}}{{json .Config.Labels}}{{json .Config.Entrypoint}}{{json .Config.Cmd}}' >"$work/cfg"
    docker history --no-trunc --format '{{.CreatedBy}}' "$img" >"$work/hist"
    grep -qE "$SECRET_NAME_PATTERN" "$work/cfg" && problems+=("$name 설정에 비밀 변수 이름")
    grep -qE "$KEY_PATTERNS" "$work/cfg" "$work/hist" && problems+=("$name 설정 · 기록에 키 형식 문자열")
    grep -qE "$PROXY_PATTERN" "$work/cfg" "$work/hist" && problems+=("$name 설정 · 기록에 프록시 · CA 값")
  done
  # 우리가 넣은 파일
  local -A paths=([core-api]=/app/BOOT-INF/classes [realtime]=/app/BOOT-INF/classes [web]=/app/.next
    [migrate]=/flyway/sql [infra]=/bundle [fake-provider]=/app/fake-provider)
  for name in "${!paths[@]}"; do
    rm -rf "$work/c1"
    if ! copy_out "$(ref "$name")" "${paths[$name]}" "$work/c1"; then
      problems+=("$name ${paths[$name]}를 꺼내지 못했다")
      continue
    fi
    local hits
    hits="$(grep -rlE "$KEY_PATTERNS" "$work/c1" 2>/dev/null | sed "s#^$work/c1/##" | head -n 5 | paste -sd, - || true)"
    [ -n "$hits" ] && problems+=("$name 파일에 키 형식 문자열($hits)")
    if [ "$name" = web ]; then
      hits="$(grep -rlE "$SECRET_NAME_PATTERN" "$work/c1/.next/static" 2>/dev/null | sed "s#^$work/c1/##" | head -n 5 | paste -sd, - || true)"
      [ -n "$hits" ] && problems+=("브라우저 번들에 비밀 변수 이름($hits)")
    fi
  done
  if [ "${#problems[@]}" -gt 0 ]; then fail ① "${problems[*]}"; else pass ①; fi
}

# ---- ② ------------------------------------------------------------------------------------------------
check2() {
  local problems=() name user
  for name in core-api realtime web migrate backup fake-provider; do
    user="$(docker image inspect "$(ref "$name")" --format '{{.Config.User}}')"
    case "$user" in
      '' | root | 0 | 0:* | root:*) problems+=("$name가 root로 실행된다") ;;
    esac
  done
  local f from
  for f in backend/core-api/Dockerfile backend/realtime-activity/Dockerfile frontend/Dockerfile infra/migrate/Dockerfile \
    infra/backup/Dockerfile tools/fake-provider/Dockerfile; do
    while read -r from; do
      if [[ "$from" =~ ^\$\{([A-Z_]+)\}$ ]]; then
        from="$(grep -E "^ARG ${BASH_REMATCH[1]}=" "$ROOT/$f" | head -n 1 | cut -d= -f2-)"
      fi
      [ "$from" = scratch ] && continue
      if [ -z "$from" ] || [[ "$from" != *:* ]] || [[ "$from" == *:latest ]]; then
        problems+=("$f 베이스 태그가 고정되지 않았다(${from:-없음})")
      fi
    done < <(grep -E '^FROM\s' "$ROOT/$f" | awk '{print $2}')
  done
  if [ "${#problems[@]}" -gt 0 ]; then fail ② "${problems[*]}"; else pass ②; fi
}

# ---- ③ ------------------------------------------------------------------------------------------------
check3() {
  if ! command -v trivy >/dev/null 2>&1; then
    skip ③ "trivy가 없어 미실행(CI images 작업이 돌린다)"
    return
  fi
  local ignore="$work/trivyignore" problems=() name
  : >"$ignore"
  # 예외 목록: ci/security-exceptions.yml의 만료되지 않은 id만(형식: exceptions: [{id, reason, expires: YYYY-MM-DD}])
  if [ -f "$ROOT/ci/security-exceptions.yml" ] && command -v python3 >/dev/null 2>&1; then
    python3 -I - "$ROOT/ci/security-exceptions.yml" >"$ignore" <<'PY' || { fail ③ "예외 목록 형식 오류"; return; }
import datetime, re, sys
today = datetime.date.today().isoformat()
item = {}
items = []
for line in open(sys.argv[1], encoding="utf-8"):
    m = re.match(r"^\s*-\s*id:\s*(\S+)", line)
    if m:
        if item: items.append(item)
        item = {"id": m.group(1).strip("'\"")}
        continue
    m = re.match(r"^\s*(reason|expires):\s*(.+?)\s*$", line)
    if m and item:
        item[m.group(1)] = m.group(2).strip("'\"")
if item: items.append(item)
for it in items:
    if not it.get("reason") or not re.match(r"^\d{4}-\d{2}-\d{2}$", it.get("expires", "")):
        sys.exit("사유 · 만료일이 없는 예외: " + it["id"])
    if it["expires"] >= today:
        print(it["id"])
PY
  fi
  # 취약점은 종료 코드 3, Trivy 자체 오류(이미지를 못 읽음 · DB 실패 등)는 1이다. 둘 다 FAIL이지만 사유를 나눠 적는다(J353:
  # 3.6에서 실행기 /tmp 읽기 전용 오류를 '취약점'으로 잘못 셌다). 판정 인자(심각도 · --ignore-unfixed · 예외 목록)는 그대로다
  local rc
  for name in core-api realtime web migrate backup; do
    rc=0
    trivy image --quiet --severity HIGH,CRITICAL --ignore-unfixed --exit-code 3 --ignorefile "$ignore" \
      "$(ref "$name")" >"$work/trivy-$name.log" 2>&1 || rc=$?
    case "$rc" in
      0) ;;
      3) problems+=("$name high 이상 취약점 $(grep -oE '^Total: [0-9]+' "$work/trivy-$name.log" | awk '{ n += $2 } END { print n + 0 }')건") ;;
      *) problems+=("$name trivy 실행 오류(rc=$rc): $(grep -m1 -E 'FATAL|ERROR' "$work/trivy-$name.log" | cut -c1-160)") ;;
    esac
  done
  if [ "${#problems[@]}" -gt 0 ]; then fail ③ "${problems[*]}"; else pass ③; fi
}

# ---- ④ ------------------------------------------------------------------------------------------------
dc4() {
  MUJOONG_VERIFY_DIR="$work/verify" docker compose -p "$project" --env-file "$work/compose.env" \
    -f "$ROOT/infra/compose.demo.yml" -f "$ROOT/infra/compose.ci.yml" -f "$work/imgchk.yml" "$@"
}
check4() {
  local problems=()
  mkdir -p "$work/verify"
  bash "$ROOT/infra/ci/gen-env.sh" --out "$work/verify/env" --registry "$registry" --tag "$tag" >/dev/null
  cp "$work/verify/env/compose.env" "$work/compose.env"
  bash "$ROOT/infra/bin/render-pgconf" --out "$work/verify/postgres/postgresql.conf" >/dev/null
  chmod 0755 "$work/verify" "$work/verify/postgres"
  # demo 프로필은 GPT-Live 키가 필수다(U11 BR12.2). gen-env.sh는 키 자리값을 넣지 않으므로 더미 기동에만 실행 때 만든 무작위 값을 이 덮어쓰기
  # 파일(임시 폴더, 실행 끝에 지움)로 준다. 접속하지 않고 어디에도 남기지 않는다(R-F3)
  local dummy_key
  dummy_key="$(openssl rand -hex 24)"
  # demo 프로필 · 실공급자 주소 형식(접속하지 않는다) · backup 켬. 이미지 이름은 --image 덮어쓰기를 반영한다
  cat >"$work/imgchk.yml" <<YAML
services:
  core-api:
    image: $(ref core-api)
    environment:
      SPRING_PROFILES_ACTIVE: demo
      MUJOONG_DEVLOGIN_ENABLED: "false"
  realtime:
    image: $(ref realtime)
    environment:
      SPRING_PROFILES_ACTIVE: demo
      MUJOONG_PROVIDER_URL: wss://api.openai.com/v1/live/sessions
      OPENAI_API_KEY: $dummy_key
  migrate:
    image: $(ref migrate)
  fake-provider:
    image: $(ref fake-provider)
  backup:
    image: $(ref backup)
    profiles: !reset []
    volumes:
      - imgchk-backups:/backups
      - imgchk-backup-log:/var/log/mujoong
      - $ROOT/infra/backup:/opt/mujoong/backup:ro
volumes:
  imgchk-backups: {}
  imgchk-backup-log: {}
YAML
  if ! dc4 --profile migrate run --rm migrate >"$work/c4-migrate.log" 2>&1; then
    fail ④ "migrate 실패: $(grep -m1 -iE 'error|fail' "$work/c4-migrate.log" | cut -c1-200)"
    return
  fi
  # 시드 두 번(멱등, R4-15). 소유자 비밀번호는 env 파일로만 넘긴다(명령줄 인자 · -e 금지)
  local net="${project}_backend" i pg_image
  pg_image="$(grep -E '^\s+image:\s*postgres:' "$ROOT/infra/compose.demo.yml" | head -n 1 | sed -E 's/^\s+image:\s*//')"
  for i in 1 2; do
    if ! docker run --rm --network "$net" --env-file "$work/verify/env/migrate.env" -v "$ROOT/infra/seed:/seed:ro" \
      --entrypoint sh "$pg_image" -c \
      'PGPASSWORD="$DB_OWNER_PASSWORD" psql -X -q -h postgres -U mujoong_owner -d mujoong -v ON_ERROR_STOP=1 --single-transaction -f /seed/001-reference.sql' \
      >"$work/c4-seed.log" 2>&1; then
      fail ④ "시드 $i회차 실패: $(short_err "$work/c4-seed.log")"
      return
    fi
  done
  local counts
  counts="$(docker run --rm --network "$net" --env-file "$work/verify/env/migrate.env" --entrypoint sh "$pg_image" -c \
    'PGPASSWORD="$DB_OWNER_PASSWORD" psql -X -At -h postgres -U mujoong_owner -d mujoong -c "SELECT count(*) FILTER (WHERE c.content_code = '\''CONSENT_POLICY'\''), count(*) FILTER (WHERE c.content_code IN ('\''CLOSING'\'','\''WAITING_FILLER'\'')) FROM core.core_approved_content c JOIN core.core_approved_content_version v ON v.content_id = c.id WHERE v.status = '\''APPROVED'\''"' 2>"$work/c4-count.err" || true)"
  [ "$counts" = "1|2" ] || problems+=("시드 결과가 정책 1 · 문구 2가 아니다($counts)")
  if ! dc4 up -d --wait --wait-timeout 180 postgres core-api realtime backup >"$work/c4-up.log" 2>&1; then
    fail ④ "demo 프로필 기동 실패: $(grep -m2 -iE 'unhealthy|error|exited' "$work/c4-up.log" | cut -c1-200 | paste -sd' ' -)"
    return
  fi
  # postgres 실행 옵션(실제 컨테이너)
  local pg
  pg="$(dc4 ps -q postgres)"
  [ "$(docker inspect "$pg" --format '{{.Config.User}} {{.HostConfig.ReadonlyRootfs}} {{.HostConfig.CapDrop}}')" = "999:999 true [ALL]" ] \
    || problems+=("postgres 실행 옵션이 999:999 · read_only · cap_drop ALL이 아니다")
  # backup: 스케줄러 생존 + 더미 덤프 1회
  local bk
  bk="$(dc4 ps -q backup)"
  [ "$(docker inspect "$bk" --format '{{.State.Health.Status}}')" = healthy ] || problems+=("backup 스케줄러가 healthy가 아니다")
  if ! dc4 exec -T backup /opt/mujoong/backup/backup.sh --trigger manual >"$work/c4-backup.log" 2>&1 \
    || ! grep -qE 'event=BACKUP_END trigger=manual result=OK' "$work/c4-backup.log"; then
    problems+=("더미 덤프 실패: $(grep -m1 -E 'BACKUP_END' "$work/c4-backup.log" | cut -c1-200)")
  fi
  # 프로필 가드 · 필수 비밀(CD-7): 60초 안에 비0으로 끝나야 한다. `run`은 서비스의 edge 고정 IP를 그대로 쓰므로
  # 실행 중인 같은 서비스와 주소가 겹친다(Address already in use) — 두 앱 컨테이너만 지우고 postgres는 둔다
  # (DB가 없으면 필수 비밀 점검 전에 DB 연결 실패로 끝나 거부를 확인할 수 없다)
  dc4 rm -s -f core-api realtime >/dev/null 2>&1 || true
  local svc spec rc
  for svc in core-api realtime; do
    for spec in "SPRING_PROFILES_ACTIVE=demo,dev" "MUJOONG_DEVLOGIN_ENABLED=true" "INTERNAL_SERVICE_TOKEN="; do
      rc=0
      MUJOONG_VERIFY_DIR="$work/verify" timeout 90 docker compose -p "$project" --env-file "$work/compose.env" \
        -f "$ROOT/infra/compose.demo.yml" -f "$ROOT/infra/compose.ci.yml" -f "$work/imgchk.yml" \
        run --rm --no-deps -e "$spec" "$svc" \
        >"$work/c4-guard.log" 2>&1 || rc=$?
      if [ "$rc" -eq 0 ] || [ "$rc" -eq 124 ]; then
        problems+=("$svc가 ${spec%%=*}=… 구성에서 기동을 거부하지 않았다(rc=$rc)")
      elif ! grep -qF 'MUJOONG_STARTUP_REJECTED ' "$work/c4-guard.log"; then
        # 다른 이유(설정 오류 등)로 끝난 것을 거부로 세지 않는다
        problems+=("$svc ${spec%%=*}=… 구성이 기동 거부 줄 없이 끝났다(rc=$rc)")
      fi
    done
  done
  # demo realtime은 GPT-Live 키가 비면 기동을 거부한다(U11 BR12.2): 위 덮어쓰기의 더미 키를 빈 값으로 되돌려 확인한다
  rc=0
  MUJOONG_VERIFY_DIR="$work/verify" timeout 90 docker compose -p "$project" --env-file "$work/compose.env" \
    -f "$ROOT/infra/compose.demo.yml" -f "$ROOT/infra/compose.ci.yml" -f "$work/imgchk.yml" \
    run --rm --no-deps -e "OPENAI_API_KEY=" realtime >"$work/c4-guard.log" 2>&1 || rc=$?
  if [ "$rc" -eq 0 ] || [ "$rc" -eq 124 ]; then
    problems+=("realtime이 OPENAI_API_KEY 없는 demo 구성에서 기동을 거부하지 않았다(rc=$rc)")
  elif ! grep -qF 'MUJOONG_STARTUP_REJECTED ' "$work/c4-guard.log"; then
    problems+=("realtime OPENAI_API_KEY 없는 demo 구성이 기동 거부 줄 없이 끝났다(rc=$rc)")
  fi
  dc4 down -v --remove-orphans >/dev/null 2>&1 || true
  if [ "${#problems[@]}" -gt 0 ]; then fail ④ "${problems[*]}"; else pass ④; fi
}

# ---- ⑤ ------------------------------------------------------------------------------------------------
check5() {
  local problems=() out
  if ! out="$(bash "$ROOT/infra/bin/infra-validate" --only bundle --bundle-image "$(ref infra)" 2>&1)"; then
    problems+=("$(grep -m1 '^FAIL' <<<"$out" | cut -c1-300)")
  fi
  rm -rf "$work/c5"
  if copy_out "$(ref infra)" /bundle/compose.demo.yml "$work/c5" && copy_out "$(ref infra)" /bundle/release.env "$work/c5"; then
    if ! out="$(bash "$ROOT/infra/bin/infra-validate" --only compose-config,compose-invariants,secret-env-files \
      --compose "$work/c5/compose.demo.yml" --release-env "$work/c5/release.env" 2>&1)"; then
      problems+=("번들 compose.demo.yml: $(grep -m1 '^FAIL' <<<"$out" | cut -c1-300)")
    fi
    cmp -s "$work/c5/compose.demo.yml" "$ROOT/infra/compose.demo.yml" || problems+=("번들 compose.demo.yml이 저장소 파일과 다르다")
  else
    problems+=("번들에서 compose.demo.yml · release.env를 꺼내지 못했다")
  fi
  if [ "${#problems[@]}" -gt 0 ]; then fail ⑤ "${problems[*]}"; else pass ⑤; fi
}

# ---- ⑥ ------------------------------------------------------------------------------------------------
check6() {
  local problems=() name hits
  for name in core-api realtime; do
    rm -rf "$work/c6"
    if ! copy_out "$(ref "$name")" /app/BOOT-INF "$work/c6"; then
      problems+=("$name /app/BOOT-INF를 꺼내지 못했다")
      continue
    fi
    hits="$( (cd "$work/c6/BOOT-INF" && {
      find classes -path 'classes/kr/mujoong/testsupport/*' -print
      find classes -name '*.class' | grep -E '/[A-Za-z0-9_]+(Test|Tests|IT|TestCase)(\$[^/]*)?\.class$' || true
      find classes -maxdepth 1 -name 'application-test.*' -print
      find classes -path 'classes/db/testmigration/*' -print
      find lib -maxdepth 1 -type f | sed 's#^lib/##' \
        | grep -E '^(test-support|junit-|mockito-|assertj-|testcontainers|archunit|spring-test-|spring-boot-test-|spring-boot-testcontainers|hamcrest|awaitility|jsonassert|xmlunit|Java-WebSocket)' || true
    }) | head -n 5 | paste -sd, -)"
    [ -n "$hits" ] && problems+=("$name 시험 항목($hits)")
  done
  if [ "${#problems[@]}" -gt 0 ]; then fail ⑥ "${problems[*]}"; else pass ⑥; fi
}

# ---- ⑦ ------------------------------------------------------------------------------------------------
check7() {
  docker rm -f imgchk-web >/dev/null 2>&1 || true
  if ! docker run -d --name imgchk-web --network none --init --read-only --cap-drop ALL \
    --security-opt no-new-privileges:true --tmpfs /tmp:size=64m --tmpfs /app/.next/cache:size=64m,uid=1000,gid=1000,mode=0700 \
    -e HOSTNAME=0.0.0.0 -e PORT=3000 -e MUJOONG_SECURITY_ALLOWED_ORIGIN=https://localhost:8443 \
    "$(ref web)" >/dev/null 2>"$work/c7.err"; then
    fail ⑦ "web 컨테이너 기동 실패: $(short_err "$work/c7.err")"
    return
  fi
  local i ok=0
  for i in $(seq 1 30); do
    if docker exec imgchk-web wget -q -O /dev/null -T 2 http://127.0.0.1:3000/healthz >/dev/null 2>&1; then
      ok=1
      break
    fi
    sleep 1
  done
  docker rm -f imgchk-web >/dev/null 2>&1 || true
  if [ "$ok" -eq 1 ]; then pass ⑦; else fail ⑦ "30초 안에 127.0.0.1:3000/healthz가 200이 아니다"; fi
}

# ---- ⑧ ------------------------------------------------------------------------------------------------
# 업스트림 정지 · 멈춤의 기대(NFR4.13 · RD-16 · reliability-design 2.11 'Nginx 502는 업스트림 접속 불가일 때만'). ⑧ 설계 J357, 근거 J354 ~ J356
readonly C8_CYCLES=5      # 정지 → 다시 띄우기 되풀이 횟수(타이밍에 흔들리지 않음을 보인다)
readonly C8_FIRST_MAX=4.5 # 정지 직후 첫 요청: 연결 시한(proxy_connect_timeout 3초, J356) + 여유 1.5초 안 502
readonly C8_NEXT_MAX=1    # 그다음 요청: 1초 안 502
readonly C8_HANG_MIN=14   # 연결한 뒤 답하지 않는 앱(hang): 읽기 시한(proxy_read_timeout 15초) 뒤 504
readonly C8_HANG_MAX=20
check8() {
  local problems=() host=localhost dir="$work/c8"
  mkdir -p "$dir/certs/live/$host" "$dir/stub"
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 1 -subj "/CN=$host" \
    -addext "subjectAltName=DNS:$host" -keyout "$dir/certs/live/$host/privkey.pem" -out "$dir/certs/live/$host/fullchain.pem" \
    >/dev/null 2>&1
  SERVICE_HOST=$host MUJOONG_SECURITY_ALLOWED_ORIGIN="https://$host:8443" \
    bash "$ROOT/infra/bin/render-nginx" --out "$dir/nginx" >/dev/null
  chmod -R go+rX "$dir"
  # 업스트림은 서비스 이름(web · core-api · realtime)을 Docker 내장 DNS로 다시 해석한다(J347). 시험 망(172.30.99.0/24, demo · 검증
  # 스택 망과 겹치지 않게)에 같은 이름(망 별칭)의 대역을 고정 IP로 둔다(다시 띄우거나 바꿔 끼워도 같은 주소): web · realtime은
  # 포트를 듣지 않는 컨테이너(연결 거부 → 502), core-api는 /api/health에 503을 내는 앱 대역
  cat >"$dir/stub/refuse.conf" <<'NGX'
events {}
http { server { listen 9; return 444; } }
NGX
  cat >"$dir/stub/app503.conf" <<'NGX'
events {}
http { server { listen 8080; location / { add_header Retry-After 5 always; default_type application/json;
  return 503 '{"code":"SERVICE_NOT_READY","message":"stub","traceId":"stub"}'; } } }
NGX
  local image dnsopt
  image="$(grep -E '^\s+image:\s*nginx:' "$ROOT/infra/compose.demo.yml" | head -n 1 | sed -E 's/^\s+image:\s*//')"
  # Nginx 컨테이너의 DNS 옵션은 compose.demo.yml nginx의 dns_opt 그대로(ndots:1 — 내장 DNS가 멈춘 앱 이름을 바깥 DNS로 넘기지 않고
  # 곧바로 '없음'으로 답한다. 없으면 러너의 호스트 해석기에 따라 옛 주소가 남는다, 3.6 CI ⑧ · J355)
  dnsopt="$(sed -nE '/^\s+dns_opt:\s*\["[^"]+"\]\s*$/{s/^\s+dns_opt:\s*\["([^"]+)"\]\s*$/\1/p;q}' "$ROOT/infra/compose.demo.yml")"
  if [ -z "$dnsopt" ]; then
    fail ⑧ "compose.demo.yml nginx에 dns_opt가 없다(J355)"
    return
  fi
  docker network rm "$edge_net" >/dev/null 2>&1 || true
  docker network create --subnet 172.30.99.0/24 "$edge_net" >/dev/null
  run_stub() { # <컨테이너 이름> <IP> <서비스 이름(망 별칭)> <설정>
    docker run -d --name "$1" --network "$edge_net" --ip "$2" --network-alias "$3" -v "$dir/stub/$4:/etc/nginx/stub.conf:ro" \
      --entrypoint nginx "$image" -g 'daemon off;' -c /etc/nginx/stub.conf >/dev/null
  }
  # 멈춘 앱(hang) 대역: 연결은 받고 아무것도 답하지 않는다(같은 IP · 별칭으로 바꿔 끼운다, 시험 전용 이미지의 node)
  run_hang() { # <컨테이너 이름> <IP> <서비스 이름(망 별칭)> <포트>
    docker run -d --name "$1" --network "$edge_net" --ip "$2" --network-alias "$3" --entrypoint node "$(ref fake-provider)" \
      -e "require('net').createServer(() => {}).listen($4)" >/dev/null
  }
  run_stub imgchk-up11 172.30.99.11 web refuse.conf
  run_stub imgchk-up12 172.30.99.12 core-api app503.conf
  run_stub imgchk-up13 172.30.99.13 realtime refuse.conf
  docker run -d --name imgchk-ngx --network "$edge_net" --ip 172.30.99.10 --dns-option "$dnsopt" -p 127.0.0.1::443 \
    -v "$dir/nginx:/etc/nginx/mujoong:ro" -v "$dir/certs:/etc/letsencrypt:ro" \
    --entrypoint nginx "$image" -g 'daemon off;' -c /etc/nginx/mujoong/nginx.conf >/dev/null
  local port
  port="$(docker port imgchk-ngx 443/tcp | head -n 1 | sed 's/.*://')"
  local -a ws=(--http1.1 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13'
    -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==')
  # c8 <최대 초> <본문 파일> <경로> [curl 인자...] — 한 줄 "<상태> <걸린 초>"(연결 실패 · 시간 초과는 상태 000). curl은 실패해도
  # -w 줄을 내므로 그 한 줄만 낸다(두 줄을 내면 읽는 쪽 파이프가 먼저 닫혀 SIGPIPE로 끝난다)
  c8() {
    local max="$1" body="$2" p="$3" out
    shift 3
    out="$(curl -sS --noproxy '*' --max-time "$max" --cacert "$dir/certs/live/$host/fullchain.pem" \
      --resolve "$host:$port:127.0.0.1" -o "$body" -w '%{http_code} %{time_total}' "$@" "https://$host:$port$p" 2>/dev/null)" || true
    echo "${out:-000 $max}"
  }
  # want <꼬리표> <"상태 초"> <본문 파일> <기대 상태> <본문 json|page|app> <초 상한> [초 하한] — 어긋나면 problems에 더한다
  want() {
    local code="" secs="" mark desc
    read -r code secs <<<"$2" || true
    case "$5" in
      json) mark='"code":"UPSTREAM_UNAVAILABLE"' desc='UPSTREAM_UNAVAILABLE' ;;
      page) mark='지금은 연결할 수 없어요' desc='점검 페이지' ;;
      *) mark='"code":"SERVICE_NOT_READY"' desc='앱이 낸 SERVICE_NOT_READY' ;;
    esac
    if [ "$code" != "$4" ] || ! grep -qF -- "$mark" "$3" 2>/dev/null \
      || ! awk -v s="${secs:-0}" -v hi="$6" -v lo="${7:-0}" 'BEGIN { exit !(s + 0 < hi + 0 && s + 0 >= lo + 0) }'; then
      problems+=("$1 상태 ${code:-없음} · ${secs:-?}초(기대 $4 + $desc, ${7:+${7}초 이상 }${6}초 안)")
    fi
  }
  local line i
  # 1) 연결 거부: / · /ws/device는 502(점검 페이지 · 봉투)이고, 앱이 낸 503은 그대로 통과한다. 기동 직후에는 Nginx가 아직 듣지
  #    않거나 이름 해석 전일 수 있어 잠깐(최대 10초) 다시 부른다
  for i in $(seq 1 20); do
    line="$(c8 10 "$dir/b-web" /)"
    [ "${line%% *}" != 000 ] && break
    sleep 0.5
  done
  want "/" "$line" "$dir/b-web" 502 page 10
  want "/ws/device" "$(c8 10 "$dir/b-ws" /ws/device "${ws[@]}")" "$dir/b-ws" 502 json 10
  for i in $(seq 1 20); do
    line="$(c8 10 "$dir/b-api" /api/health)"
    [ "${line%% *}" = 503 ] && break
    sleep 0.5
  done
  want "앱이 낸 503 통과(/api/health)" "$line" "$dir/b-api" 503 app 10
  # 2) 앱 컨테이너 정지(NFR4.13 · J354 · J355): 세 업스트림을 함께 멈추고 첫 요청을 동시에 보낸다. 이름 재해석 전이면 옛 주소로 가서
  #    연결 시한(3초)을 넘기고(연결조차 못 함 = 업스트림 접속 불가 → 502), 재해석 뒤면 업스트림이 비어 곧바로 502다. 그다음 요청은
  #    1초 안 502, 다시 띄우면 앱 응답. C8_CYCLES회 되풀이한다
  local cycle pids=()
  for cycle in $(seq 1 "$C8_CYCLES"); do
    docker stop -t 2 imgchk-up11 imgchk-up12 imgchk-up13 >/dev/null
    pids=()
    c8 10 "$dir/b-api" /api/health >"$dir/r-api" &
    pids+=("$!")
    c8 10 "$dir/b-ws" /ws/device "${ws[@]}" >"$dir/r-ws" &
    pids+=("$!")
    c8 10 "$dir/b-web" / >"$dir/r-web" &
    pids+=("$!")
    wait "${pids[@]}"
    want "정지 ${cycle}회 첫 요청 /api/health" "$(head -n 1 "$dir/r-api")" "$dir/b-api" 502 json "$C8_FIRST_MAX"
    want "정지 ${cycle}회 첫 요청 /ws/device" "$(head -n 1 "$dir/r-ws")" "$dir/b-ws" 502 json "$C8_FIRST_MAX"
    want "정지 ${cycle}회 첫 요청 /" "$(head -n 1 "$dir/r-web")" "$dir/b-web" 502 page "$C8_FIRST_MAX"
    want "정지 ${cycle}회 다음 요청 /api/health" "$(c8 10 "$dir/b-api" /api/health)" "$dir/b-api" 502 json "$C8_NEXT_MAX"
    want "정지 ${cycle}회 다음 요청 /ws/device" "$(c8 10 "$dir/b-ws" /ws/device "${ws[@]}")" "$dir/b-ws" 502 json "$C8_NEXT_MAX"
    want "정지 ${cycle}회 다음 요청 /" "$(c8 10 "$dir/b-web" /)" "$dir/b-web" 502 page "$C8_NEXT_MAX"
    docker start imgchk-up11 imgchk-up12 imgchk-up13 >/dev/null
    for i in $(seq 1 20); do
      line="$(c8 10 "$dir/b-api" /api/health)"
      [ "${line%% *}" = 503 ] && break
      sleep 0.5
    done
    want "다시 띄운 뒤(${cycle}회) /api/health" "$line" "$dir/b-api" 503 app 10
  done
  # 3) 연결한 뒤 답하지 않는 앱(hang)은 그대로 504다(J354): core-api · web 자리에 연결만 받는 대역을 같은 IP · 별칭으로 바꿔 끼우고,
  #    Nginx가 그 대역에 연결해 기다리는 것(1초 안에 끝나지 않음)을 본 뒤 읽기 시한(15초)이 지나기를 동시에 기다린다
  docker rm -f imgchk-up11 imgchk-up12 >/dev/null
  run_hang imgchk-up11 172.30.99.11 web 3000
  run_hang imgchk-up12 172.30.99.12 core-api 8080
  local p ready
  for p in /api/health /; do
    ready=0
    for i in $(seq 1 20); do
      line="$(c8 1 "$dir/b-probe" "$p")"
      if [ "${line%% *}" = 000 ]; then
        ready=1
        break
      fi
      sleep 0.5
    done
    [ "$ready" -eq 1 ] || problems+=("멈춘 앱 대역에 연결해 기다리지 않는다($p 상태 ${line%% *})")
  done
  pids=()
  c8 25 "$dir/b-api" /api/health >"$dir/r-api" &
  pids+=("$!")
  c8 25 "$dir/b-web" / >"$dir/r-web" &
  pids+=("$!")
  wait "${pids[@]}"
  want "멈춘 앱(hang) /api/health" "$(head -n 1 "$dir/r-api")" "$dir/b-api" 504 json "$C8_HANG_MAX" "$C8_HANG_MIN"
  want "멈춘 앱(hang) /" "$(head -n 1 "$dir/r-web")" "$dir/b-web" 504 page "$C8_HANG_MAX" "$C8_HANG_MIN"
  docker rm -f imgchk-ngx imgchk-up11 imgchk-up12 imgchk-up13 >/dev/null 2>&1 || true
  docker network rm "$edge_net" >/dev/null 2>&1 || true
  if [ "${#problems[@]}" -gt 0 ]; then fail ⑧ "${problems[*]}"; else pass ⑧; fi
}

selected 1 && check1
selected 2 && check2
selected 3 && check3
selected 4 && check4
selected 5 && check5
selected 6 && check6
selected 7 && check7
selected 8 && check8

if [ "$fails" -gt 0 ]; then
  echo "image-checks: FAIL ${fails}건"
  exit 1
fi
echo "image-checks: FAIL 없음"
