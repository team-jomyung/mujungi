#!/usr/bin/env bash
# 무중 호스트 스크립트 공용 함수 (U1 3.5 Step 47 · infra-spec 1.4 · 4.2 · 4.5 · cicd 2.6 · 2.10 · monitoring 2.6 · 2.7)
#
# demo 호스트의 단계 스크립트(deploy-stage1 · deploy-apply · smoke · host-collect.sh · backup-trigger · power-mark ·
# check-since-last · log-hygiene-check)가 source한다. 단독 실행하지 않는다.
#
# 경로(infra-spec 4.2가 정본). 아래 환경 변수로 바꿀 수 있는 것은 배포 리허설 · 시험 호스트에서 같은 스크립트를 다른 뿌리에
# 돌리기 위해서다. demo 호스트에서는 아무것도 설정하지 않고 기본값을 쓴다.
#   MUJOONG_OPT            /opt/mujoong            (current · releases/ · bin/ · env/ · nginx/ · postgres/ · backup/)
#   MUJOONG_LOG_DIR        /var/log/mujoong        (deploy.log · power.log · backup.log · host-collect.log · archive/)
#   MUJOONG_DATA_DIR       /var/lib/mujoong        (데이터 EBS: postgres/data · certbot/ · last-check)
#   MUJOONG_BACKUP_VOLUME  /var/lib/mujoong-backup (백업 EBS)
#   MUJOONG_AUDIO_DIR      /srv/mujoong/approved-audio
#   MUJOONG_ENV_DIR        $MUJOONG_OPT/env        (서비스별 env 파일, root 0600 — compose.demo.yml도 같은 변수를 읽는다)
#   MUJOONG_PROJECT        mujoong                 (Compose 프로젝트 이름, infra-spec N-3)
#
# 값(비밀)은 이 함수들이 출력하지 않는다. env 파일은 셸로 source하지 않고 필요한 이름 한 줄만 읽는다.

: "${MUJOONG_OPT:=/opt/mujoong}"
: "${MUJOONG_LOG_DIR:=/var/log/mujoong}"
: "${MUJOONG_DATA_DIR:=/var/lib/mujoong}"
: "${MUJOONG_BACKUP_VOLUME:=/var/lib/mujoong-backup}"
: "${MUJOONG_AUDIO_DIR:=/srv/mujoong/approved-audio}"
: "${MUJOONG_ENV_DIR:=$MUJOONG_OPT/env}"
: "${MUJOONG_PROJECT:=mujoong}"
export MUJOONG_OPT MUJOONG_LOG_DIR MUJOONG_DATA_DIR MUJOONG_BACKUP_VOLUME MUJOONG_AUDIO_DIR MUJOONG_ENV_DIR MUJOONG_PROJECT

# 상시 컨테이너 8개. Neo4j 포함, migrate만 일회성
# shellcheck disable=SC2034  # source하는 스크립트가 쓴다
MUJOONG_ALWAYS_ON=(nginx web core-api realtime postgres certbot backup neo4j)

mujoong_now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }
mujoong_epoch() { date -u +%s; }

# ISO 8601(또는 date -d가 읽는 형식) → 초. 읽지 못하면 실패
mujoong_to_epoch() { date -u -d "$1" +%s 2>/dev/null; }

# env 파일에서 이름 하나의 값을 읽어 표준 출력으로 돌려준다(부르는 쪽이 변수에만 담는다). 파일 · 줄이 없으면 실패
mujoong_env_value() {
  local file="$1" name="$2" line
  [ -r "$file" ] || return 1
  line="$(grep -m 1 -E "^${name}=" "$file")" || return 1
  printf '%s' "${line#*=}"
}

# 서비스 호스트(비밀 아님, nginx.env). 없으면 실패
mujoong_service_host() {
  local host
  host="$(mujoong_env_value "$MUJOONG_ENV_DIR/nginx.env" SERVICE_HOST)" || return 1
  [[ "$host" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$ ]] || return 1
  printf '%s' "$host"
}

# 허용 origin(비밀 아님, nginx.env)
mujoong_allowed_origin() {
  mujoong_env_value "$MUJOONG_ENV_DIR/nginx.env" MUJOONG_SECURITY_ALLOWED_ORIGIN
}

# 릴리스 폴더의 release.env 값 하나(비밀 아님: MUJOONG_REGISTRY · MUJOONG_TAG · MUJOONG_GIT_SHA · MUJOONG_SOURCE_FINGERPRINT)
mujoong_release_value() {
  mujoong_env_value "$1/release.env" "$2"
}

# 리전: 환경 변수(AWS_REGION · AWS_DEFAULT_REGION) → 인스턴스 메타데이터(IMDSv2, 홉 1이라 호스트 프로세스만 닿는다)
mujoong_aws_region() {
  if [ -n "${AWS_REGION:-}" ]; then
    printf '%s' "$AWS_REGION"
    return 0
  fi
  if [ -n "${AWS_DEFAULT_REGION:-}" ]; then
    printf '%s' "$AWS_DEFAULT_REGION"
    return 0
  fi
  local token region
  token="$(curl -sS --noproxy '*' --max-time 2 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
    http://169.254.169.254/latest/api/token 2>/dev/null)" || return 1
  region="$(curl -sS --noproxy '*' --max-time 2 -H "X-aws-ec2-metadata-token: $token" \
    http://169.254.169.254/latest/meta-data/placement/region 2>/dev/null)" || return 1
  [[ "$region" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || return 1
  printf '%s' "$region"
}

# 호스트 사건 파일에 한 줄(키=값, UTC). 파일은 root 0640(infra-spec 1.4 · R4-11). 값에 비밀 · 개인정보를 넣지 않는다
mujoong_event_line() {
  local file="$1"
  shift
  (
    umask 027
    printf 'ts=%s %s\n' "$(mujoong_now_iso)" "$*" >>"$file"
  )
}

mujoong_deploy_log() { mujoong_event_line "$MUJOONG_LOG_DIR/deploy.log" "$@"; }

# 프로젝트의 서비스 컨테이너 id(하나 또는 빈 값). Compose 파일 없이 라벨로 찾는다
mujoong_container_id() {
  docker ps -aq --no-trunc --filter "label=com.docker.compose.project=$MUJOONG_PROJECT" \
    --filter "label=com.docker.compose.service=$1" 2>/dev/null | head -n 1
}

# 컨테이너 헬스 상태(healthy · unhealthy · starting · none · missing)
mujoong_health_of() {
  local cid
  cid="$(mujoong_container_id "$1")"
  if [ -z "$cid" ]; then
    echo missing
    return
  fi
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo missing
}

# healthy가 될 때까지 기다린다: mujoong_wait_healthy <서비스> <제한 초>
mujoong_wait_healthy() {
  local svc="$1" limit="$2" deadline status
  deadline=$(($(mujoong_epoch) + limit))
  while :; do
    status="$(mujoong_health_of "$svc")"
    [ "$status" = healthy ] && return 0
    [ "$(mujoong_epoch)" -ge "$deadline" ] && return 1
    sleep 2
  done
}

# 컨테이너 로그 모으기(check-since-last · log-hygiene-check 공용, monitoring 2.4.3 '현재 로그와 보존 폴더를 시각순으로 이어서').
#   mujoong_collect_logs <since ISO(UTC, 끝 Z)> <출력 디렉터리> [<합성 로그 디렉터리>]
#   출력: <출력>/<서비스>.log — 줄마다 "<RFC3339 UTC 시각> <원래 줄>"(docker logs --timestamps 형식), since 이후만, 시각순.
#   원천: 보존 폴더 <로그 디렉터리>/archive/<UTC>-<서비스>-<배포 id>.log.gz(배포 D5 · D6 · D7이 남김) + 현재 컨테이너 로그.
#   합성 로그 디렉터리를 주면(시험 · 리허설) 현재 로그 대신 <디렉터리>/<서비스>.log, 보존 폴더 대신 <디렉터리>/archive/를 읽는다.
#   서비스: core-api realtime web nginx postgres. 값은 출력하지 않는다(파일은 umask 077 디렉터리에 두고 부르는 쪽이 지운다).
MUJOONG_LOG_SERVICES=(core-api realtime web nginx postgres)
mujoong_collect_logs() {
  local since="$1" out="$2" src="${3:-}" svc cid f archive cut
  # 문자열 비교용 기준(소수 초가 있는 docker 시각과 같은 모양): …:SSZ → …:SS.000000000Z
  cut="$since"
  [[ "$cut" == *.*Z ]] || cut="${cut%Z}.000000000Z"
  archive="$MUJOONG_LOG_DIR/archive"
  [ -n "$src" ] && archive="$src/archive"
  for svc in "${MUJOONG_LOG_SERVICES[@]}"; do
    : >"$out/$svc.raw"
    for f in "$archive"/*-"$svc"-*.log.gz; do
      [ -e "$f" ] || continue
      gzip -dc "$f" >>"$out/$svc.raw" 2>/dev/null || true
    done
    if [ -n "$src" ]; then
      [ -r "$src/$svc.log" ] && cat "$src/$svc.log" >>"$out/$svc.raw"
    else
      cid="$(mujoong_container_id "$svc")"
      if [ -n "$cid" ]; then
        docker logs --timestamps --since "$since" "$cid" >>"$out/$svc.raw" 2>&1 || true
      fi
    fi
    # 첫 필드(시각)가 since 이상인 줄만, 시각순(같은 시각은 원래 순서 유지)
    awk -v since="$cut" '$1 >= since' "$out/$svc.raw" | sort -s -k1,1 >"$out/$svc.log"
    rm -f "$out/$svc.raw"
  done
}

# 마지막 성공 백업 시각(backup.log의 마지막 BACKUP_END result=OK 줄 ts, 초). 없으면 실패
mujoong_last_backup_epoch() {
  local file="$MUJOONG_LOG_DIR/backup.log" ts
  [ -r "$file" ] || return 1
  ts="$(grep -E 'event=BACKUP_END( |$)' "$file" | grep -E '(^| )result=OK( |$)' | tail -n 1 \
    | sed -nE 's/^ts=([^ ]+).*/\1/p')"
  [ -n "$ts" ] || return 1
  mujoong_to_epoch "$ts"
}
