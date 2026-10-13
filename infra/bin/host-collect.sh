#!/usr/bin/env bash
# host-collect — 호스트 cron 1분 수집기 (U1 3.5 Step 47 · monitoring 2.7 · MO-3 · infra-spec 1.4 · 2.1 · LC-9 · OD-14)
#
# /etc/cron.d/mujoong이 1분마다 `flock -n · timeout 20`으로 고정 진입점 /opt/mujoong/bin/host-collect.sh를 부르고,
# 그 진입점이 현재 릴리스의 이 스크립트를 부른다. 관찰만 하고 컨테이너를 건드리지 않는다(오토힐 없음, monitoring 2.7.1).
# 원인 파악 · 수동 점검용이며 가용성 SLI에는 쓰지 않는다(호스트가 죽으면 못 본다).
#
# 출력 1: /var/log/mujoong/host-collect.log 분당 한 줄(키=값, monitoring 2.7.2)
#   ts ctr(<running>/7) ctrBad readyCore readyRealtime restarts(<서비스>:<RestartCount>,...) diskRoot diskData diskBackup
#   certDays certServedDays backupAgeH cpuPct memPct clockSync clockOffsetMs warn
# 출력 2: /var/log/mujoong/metrics-snapshot.log 앱당 분당 한 줄(ts app 키=값..., monitoring 2.7.3). 관리 포트 /actuator/prometheus를
#   컨테이너 안에서 읽어 허용 목록 지표만 값 그대로 남긴다. 키 = 지표 이름 + 라벨 값(밑줄로 이음). 읽지 못하면 scrape=ERR
# 항목마다 시간 제한(관리 포트 · 인증서 서빙 확인 각 3초)을 두고, 한 항목이 실패해도 나머지를 기록한다(값은 ERR · NA).
# 경고 기준(warn에 키 이름이 들어간다): ctr<7 · ctrBad · ready≠200 · 재시작 증가(같은 컨테이너 id일 때만) · 디스크 80% 이상 ·
#   certDays<20 · certServedDays<20 또는 certDays와 다름 · backupAgeH>26 · clockSync≠OK · |clockOffsetMs|>1000
# 재시작 비교용 상태 파일: /run/mujoong/host-collect.state(재부팅하면 사라진다. 첫 줄은 비교하지 않는다)
#
# 쓰는 법: host-collect.sh [--stdout]   (--stdout: 파일 대신 표준 출력에 쓴다 — 점검 때 한 번 보기)
# 종료 코드: 0 기록함(경고가 있어도 0), 1 로그 파일에 쓰지 못함

set -Euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=infra/bin/lib/host.sh
. "$SCRIPT_DIR/lib/host.sh"

to_stdout=0
case "${1:-}" in
  --stdout) to_stdout=1 ;;
  -h | --help) sed -n '2,21p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
  '') ;;
  *) echo "host-collect: 모르는 인자: $1" >&2; exit 64 ;;
esac

state_dir="${MUJOONG_STATE_DIR:-/run/mujoong}"
mkdir -p "$state_dir" 2>/dev/null || true
state_file="$state_dir/host-collect.state"
warn=()

ts="$(mujoong_now_iso)"
now="$(mujoong_epoch)"

# ---- 컨테이너 ----------------------------------------------------------------------------------------
declare -A cid_of=() status_of=()
if docker info >/dev/null 2>&1; then
  while IFS=$'\t' read -r svc id state status; do
    [ -n "$svc" ] || continue
    cid_of[$svc]="$id"
    case "$status" in
      *'(unhealthy)'*) status_of[$svc]=unhealthy ;;
      *'(health: starting)'*) status_of[$svc]=starting ;;
      *) status_of[$svc]="$state" ;;
    esac
  done < <(timeout 5 docker ps -a --no-trunc --filter "label=com.docker.compose.project=$MUJOONG_PROJECT" \
    --format '{{.Label "com.docker.compose.service"}}\t{{.ID}}\t{{.State}}\t{{.Status}}' 2>/dev/null)
  docker_ok=1
else
  docker_ok=0
fi
running=0
bad=()
for svc in "${MUJOONG_ALWAYS_ON[@]}"; do
  st="${status_of[$svc]:-missing}"
  if [ "$st" = running ]; then
    running=$((running + 1))
  elif [ "$st" = starting ] || [ "$st" = unhealthy ]; then
    # running이지만 아직 healthy가 아니거나 unhealthy
    running=$((running + 1))
    bad+=("$svc")
  else
    bad+=("$svc")
  fi
done
if [ "$docker_ok" -eq 0 ]; then
  ctr="ERR"
  ctr_bad="docker"
  warn+=(ctr)
else
  ctr="$running/${#MUJOONG_ALWAYS_ON[@]}"
  [ "$running" -lt "${#MUJOONG_ALWAYS_ON[@]}" ] && warn+=(ctr)
  if [ "${#bad[@]}" -gt 0 ]; then
    ctr_bad="$(IFS=,; echo "${bad[*]}")"
    warn+=(ctrBad)
  else
    ctr_bad=none
  fi
fi

# ---- readiness(컨테이너 안 관리 포트, Compose 헬스체크와 같은 요청) --------------------------------------
readiness() {
  local cid="${cid_of[$1]:-}" code
  if [ -z "$cid" ]; then
    echo ERR
    return
  fi
  code="$(timeout 4 docker exec "$cid" curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
    http://127.0.0.1:9090/actuator/health/readiness 2>/dev/null)" || true
  [[ "$code" =~ ^[1-5][0-9][0-9]$ ]] || code=ERR
  echo "$code"
}
ready_core="$(readiness core-api)"
ready_realtime="$(readiness realtime)"
[ "$ready_core" = 200 ] || warn+=(readyCore)
[ "$ready_realtime" = 200 ] || warn+=(readyRealtime)

# ---- 재시작 횟수(같은 컨테이너 id일 때만 비교) ---------------------------------------------------------
declare -A prev_id=() prev_count=()
if [ -r "$state_file" ]; then
  while read -r s i c; do
    prev_id[$s]="$i"
    prev_count[$s]="$c"
  done <"$state_file"
fi
restarts=()
new_state=""
increased=0
for svc in "${MUJOONG_ALWAYS_ON[@]}"; do
  cid="${cid_of[$svc]:-}"
  [ -n "$cid" ] || continue
  count="$(timeout 3 docker inspect --format '{{.RestartCount}}' "$cid" 2>/dev/null)" || count=""
  [[ "$count" =~ ^[0-9]+$ ]] || continue
  restarts+=("$svc:$count")
  new_state+="$svc $cid $count"$'\n'
  if [ "${prev_id[$svc]:-}" = "$cid" ] && [ "$count" -gt "${prev_count[$svc]:-0}" ]; then
    increased=1
  fi
done
[ "$increased" -eq 1 ] && warn+=(restarts)
if [ -n "$new_state" ]; then
  printf '%s' "$new_state" >"$state_file.tmp" 2>/dev/null && mv -f "$state_file.tmp" "$state_file" 2>/dev/null
fi
restarts_field="none"
[ "${#restarts[@]}" -gt 0 ] && restarts_field="$(IFS=,; echo "${restarts[*]}")"

# ---- 디스크 -----------------------------------------------------------------------------------------
disk_pct() {
  local v
  v="$(df -P "$1" 2>/dev/null | awk 'NR == 2 { gsub("%", "", $5); print $5 }')"
  [[ "$v" =~ ^[0-9]+$ ]] && echo "$v" || echo ERR
}
disk_root="$(disk_pct /)"
disk_data="$(disk_pct "$MUJOONG_DATA_DIR")"
disk_backup="$(disk_pct "$MUJOONG_BACKUP_VOLUME")"
for pair in "diskRoot:$disk_root" "diskData:$disk_data" "diskBackup:$disk_backup"; do
  v="${pair#*:}"
  if [ "$v" = ERR ] || [ "$v" -ge 80 ]; then
    warn+=("${pair%%:*}")
  fi
done

# ---- 인증서 -------------------------------------------------------------------------------------------
days_until() {
  local end epoch
  end="$(sed -n 's/^notAfter=//p')"
  [ -n "$end" ] || { echo ERR; return; }
  epoch="$(mujoong_to_epoch "$end")" || { echo ERR; return; }
  echo $(((epoch - now) / 86400))
}
cert_days=NA
cert_served=NA
if host="$(mujoong_service_host 2>/dev/null)"; then
  cert_file="$MUJOONG_DATA_DIR/certbot/conf/live/$host/fullchain.pem"
  if [ -r "$cert_file" ]; then
    cert_days="$(openssl x509 -noout -enddate -in "$cert_file" 2>/dev/null | days_until)"
  else
    cert_days=ERR
  fi
  cert_served="$(timeout 3 openssl s_client -connect 127.0.0.1:443 -servername "$host" </dev/null 2>/dev/null \
    | openssl x509 -noout -enddate 2>/dev/null | days_until)"
fi
if [ "$cert_days" = ERR ] || { [ "$cert_days" != NA ] && [ "$cert_days" -lt 20 ]; }; then
  warn+=(certDays)
fi
if [ "$cert_served" = ERR ] || { [ "$cert_served" != NA ] && { [ "$cert_served" -lt 20 ] || [ "$cert_served" != "$cert_days" ]; }; }; then
  warn+=(certServedDays)
fi

# ---- 백업 나이 ----------------------------------------------------------------------------------------
if last="$(mujoong_last_backup_epoch)"; then
  backup_age=$(((now - last) / 3600))
  [ "$backup_age" -gt 26 ] && warn+=(backupAgeH)
else
  backup_age=NA
  warn+=(backupAgeH)
fi

# ---- CPU · 메모리(호스트 /proc) --------------------------------------------------------------------------
cpu_pct=ERR
if read -r _ u1 n1 s1 i1 w1 q1 sq1 st1 _ </proc/stat; then
  sleep 1
  if read -r _ u2 n2 s2 i2 w2 q2 sq2 st2 _ </proc/stat; then
    busy=$(((u2 + n2 + s2 + q2 + sq2 + st2) - (u1 + n1 + s1 + q1 + sq1 + st1)))
    total=$((busy + (i2 + w2) - (i1 + w1)))
    [ "$total" -gt 0 ] && cpu_pct=$((busy * 100 / total))
  fi
fi
mem_pct="$(awk '/^MemTotal:/ { t = $2 } /^MemAvailable:/ { a = $2 } END { if (t > 0) printf "%d", (t - a) * 100 / t; else print "ERR" }' /proc/meminfo 2>/dev/null || echo ERR)"

# ---- 시계(chrony) --------------------------------------------------------------------------------------
clock_sync=ERR
clock_offset=ERR
if command -v chronyc >/dev/null 2>&1 && tracking="$(timeout 3 chronyc tracking 2>/dev/null)"; then
  if grep -qE '^Leap status\s*:\s*Normal' <<<"$tracking"; then clock_sync=OK; else clock_sync=NOK; fi
  clock_offset="$(awk -F: '/^System time/ {
      split($2, a, " "); ms = a[1] * 1000; if (a[3] == "slow") ms = -ms; printf "%d", ms }' <<<"$tracking")"
  [[ "$clock_offset" =~ ^-?[0-9]+$ ]] || clock_offset=ERR
fi
if [ "$clock_sync" != OK ]; then
  warn+=(clockSync)
fi
if [ "$clock_offset" = ERR ] || [ "${clock_offset#-}" -gt 1000 ]; then
  warn+=(clockOffsetMs)
fi

warn_field=none
[ "${#warn[@]}" -gt 0 ] && warn_field="$(IFS=,; echo "${warn[*]}")"
line="ts=$ts ctr=$ctr ctrBad=$ctr_bad readyCore=$ready_core readyRealtime=$ready_realtime restarts=$restarts_field"
line+=" diskRoot=$disk_root diskData=$disk_data diskBackup=$disk_backup certDays=$cert_days certServedDays=$cert_served"
line+=" backupAgeH=$backup_age cpuPct=$cpu_pct memPct=$mem_pct clockSync=$clock_sync clockOffsetMs=$clock_offset warn=$warn_field"

# ---- 지표 스냅샷(허용 목록, monitoring 2.7.3) ---------------------------------------------------------
readonly CORE_METRICS='mujoong_health_up|mujoong_internal_http_breaker_state|mujoong_outbox_pending|mujoong_outbox_oldest_pending_seconds|mujoong_outbox_failed|mujoong_inbox_dead|hikaricp_connections_active|hikaricp_connections_pending|mujoong_db_pool_wait_exceeded_total|jvm_memory_used_bytes|jvm_memory_max_bytes'
readonly REALTIME_EXTRA='mujoong_device_connections_open|mujoong_device_connection_state|mujoong_sessions_active|mujoong_queue_depth|mujoong_input_frames_dropped_total|mujoong_recorder_rejected_total'
snapshot() {
  local app="$1" allow="$2" cid="${cid_of[$1]:-}" body fields
  if [ -n "$cid" ] && body="$(timeout 4 docker exec "$cid" curl -s -f --max-time 3 \
    http://127.0.0.1:9090/actuator/prometheus 2>/dev/null)"; then
    # name{k="v",...} value — 라벨 값만 밑줄로 잇는다(application 라벨은 뺀다). jvm_memory_*는 area="heap"만
    fields="$(awk -v allow="^($allow)$" '
      /^#/ || NF < 2 { next }
      {
        # 라벨 값에 공백이 있을 수 있어(id="G1 Eden Space") 마지막 필드만 값으로 떼어 낸다
        value = $NF; metric = substr($0, 1, length($0) - length($NF)); sub(/[ \t]+$/, "", metric)
        name = metric; labels = ""
        p = index(metric, "{")
        if (p > 0) { name = substr(metric, 1, p - 1); labels = substr(metric, p + 1, length(metric) - p - 1) }
        if (name !~ allow) next
        if (name ~ /^jvm_memory_/ && labels !~ /area="heap"/) next
        key = name
        n = split(labels, parts, ",")
        for (i = 1; i <= n; i++) {
          split(parts[i], kv, "=")
          if (kv[1] == "application" || kv[1] == "") continue
          v = kv[2]; gsub(/"/, "", v); gsub(/[^A-Za-z0-9_]/, "_", v)
          key = key "_" v
        }
        printf " %s=%s", key, value
      }' <<<"$body")"
    printf 'ts=%s app=%s%s\n' "$ts" "$app" "$fields"
  else
    printf 'ts=%s app=%s scrape=ERR\n' "$ts" "$app"
  fi
}
snap_core="$(snapshot core-api "$CORE_METRICS")"
snap_rt="$(snapshot realtime "$CORE_METRICS|$REALTIME_EXTRA")"

if [ "$to_stdout" -eq 1 ]; then
  printf '%s\n%s\n%s\n' "$line" "$snap_core" "$snap_rt"
  exit 0
fi
mkdir -p "$MUJOONG_LOG_DIR"
(
  umask 027
  printf '%s\n' "$line" >>"$MUJOONG_LOG_DIR/host-collect.log" &&
    printf '%s\n%s\n' "$snap_core" "$snap_rt" >>"$MUJOONG_LOG_DIR/metrics-snapshot.log"
) || {
  echo "host-collect: $MUJOONG_LOG_DIR 에 쓰지 못했다" >&2
  exit 1
}
