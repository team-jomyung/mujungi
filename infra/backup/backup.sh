#!/usr/bin/env bash
# 무중 논리 백업 (U1 3.5 Step 43 · RD-20 B1 ~ B8 · NFR4.11 · SD-24 · J84(b) · J128 · infra-spec 4.4)
#
# backup 컨테이너(이미지 mujoong-backup, uid 999)에서 돈다. 배포가 번들의 backup/을 고정 경로 /opt/mujoong/backup에
# 렌더링하고 컨테이너가 읽기 전용으로 마운트한다. 트리거 4종이 모두 이 스크립트를 부른다(infra-spec 4.4):
#   cron(03:00 KST, crontab) · deploy(배포 D2: docker compose exec -T backup /opt/mujoong/backup/backup.sh --trigger deploy)
#   stop(정지 직전) · boot(기동 직후 보충) · manual(수동, 기본값)
#
# 단계(reliability-design.md 2.12)
#   B1 flock으로 겹침을 막는다(이미 실행 중이면 SKIPPED 줄 + 종료 코드 0)
#   B2 시작 줄 · 데이터 등급 표지 확인(mujoong_db.data_class ≠ synthetic이면 REFUSED + 종료 코드 3, SD-24)
#   B3 pg_dump 사용자 정의 형식 → *.partial          B4 역할 정의(--no-role-passwords) → *.partial
#   B5 pg_restore --list · 크기 0 아님 검증             B6 이름 변경으로 확정(mujoong-<UTC>.dump · mujoong-<UTC>-roles.sql)
#   B7 보관 일수 지난 덤프 삭제(새 덤프가 검증을 통과한 뒤에만)  B8 결과 줄
#
# 입력(환경 변수 이름만, 값은 출력하지 않는다)
#   필수: DB_BACKUP_PASSWORD(backup.env, 읽기 전용 역할 mujoong_backup)
#   선택: BACKUP_RETENTION_DAYS(기본 7) · BACKUP_DIR(기본 /backups) · BACKUP_LOG_FILE(기본 /var/log/mujoong/backup.log)
#         PGHOST(기본 postgres) · PGPORT(5432) · PGDATABASE(mujoong) · PGUSER(mujoong_backup)
#
# 결과 줄 형식(backup.log, 한 줄 키=값, UTC — host-collect가 마지막 result=OK 줄의 ts로 backupAgeH를 계산한다)
#   ts=<ISO8601Z> event=BACKUP_START trigger=<트리거>
#   ts=<ISO8601Z> event=BACKUP_END trigger=<트리거> result=<OK|FAIL|SKIPPED|REFUSED> file=<이름|none> bytes=<정수> durationSec=<정수> reason=<코드|none>
# 종료 코드: 0 성공 · 겹쳐서 건너뜀, 1 실패, 3 데이터 등급 거부, 64 인자 오류

set -Eeuo pipefail
umask 077

trigger=manual
retention_days="${BACKUP_RETENTION_DAYS:-7}"

usage() {
  sed -n '2,27p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --trigger)
      trigger="${2:-}"
      shift
      ;;
    --retention-days)
      retention_days="${2:-}"
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "backup.sh: 모르는 인자: $1" >&2
      exit 64
      ;;
  esac
  shift
done

case "$trigger" in
  cron | deploy | stop | boot | manual) ;;
  *)
    echo "backup.sh: --trigger는 cron · deploy · stop · boot · manual 중 하나다" >&2
    exit 64
    ;;
esac
if ! [[ "$retention_days" =~ ^[0-9]{1,3}$ ]] || [ "$retention_days" -lt 1 ]; then
  echo "backup.sh: 보관 일수는 1 ~ 999 정수다" >&2
  exit 64
fi

readonly BACKUP_DIR="${BACKUP_DIR:-/backups}"
readonly LOG_FILE="${BACKUP_LOG_FILE:-/var/log/mujoong/backup.log}"
export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-mujoong}"
export PGUSER="${PGUSER:-mujoong_backup}"
export PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-10}"

now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# 결과 줄은 로그 파일과 표준 출력(컨테이너 로그) 둘에 남긴다. 값(비밀번호 · 데이터)은 쓰지 않는다.
log_line() {
  local line
  line="ts=$(now_iso) $*"
  printf '%s\n' "$line"
  if ! printf '%s\n' "$line" >>"$LOG_FILE" 2>/dev/null; then
    echo "backup.sh: 결과 줄을 $LOG_FILE 에 쓰지 못했다" >&2
  fi
}

started_epoch="$(date -u +%s)"
stage=init
partial_dump=""
partial_roles=""

finish() {
  # $1 result, $2 file, $3 bytes, $4 reason
  local duration=$(($(date -u +%s) - started_epoch))
  log_line "event=BACKUP_END trigger=$trigger result=$1 file=$2 bytes=$3 durationSec=$duration reason=$4"
}

on_error() {
  local rc=$?
  trap - ERR
  [ -n "$partial_dump" ] && rm -f -- "$partial_dump"
  [ -n "$partial_roles" ] && rm -f -- "$partial_roles"
  finish FAIL none 0 "$stage"
  exit "$rc"
}
trap on_error ERR

if [ ! -d "$BACKUP_DIR" ] || [ ! -w "$BACKUP_DIR" ]; then
  log_line "event=BACKUP_END trigger=$trigger result=FAIL file=none bytes=0 durationSec=0 reason=backup_dir"
  exit 1
fi

# B1 겹침 방지
exec 9>"$BACKUP_DIR/.backup.lock"
if ! flock -n 9; then
  log_line "event=BACKUP_END trigger=$trigger result=SKIPPED file=none bytes=0 durationSec=0 reason=locked"
  exit 0
fi

# B2 시작 줄과 데이터 등급 표지
log_line "event=BACKUP_START trigger=$trigger"
stage=password
if [ -z "${DB_BACKUP_PASSWORD:-}" ]; then
  finish FAIL none 0 missing_password
  exit 1
fi
# libpq가 환경 변수에서 읽는다. 명령줄 인자로 넘기지 않는다.
export PGPASSWORD="$DB_BACKUP_PASSWORD"
unset DB_BACKUP_PASSWORD

stage=data_class
data_class="$(psql -X -q -t -A -v ON_ERROR_STOP=1 -c "SELECT coalesce(current_setting('mujoong_db.data_class', true), '')")"
if [ "$data_class" != "synthetic" ]; then
  # 표지가 없거나 synthetic이 아니면 실제 데이터로 보고 거부한다(SD-24 · J124 fail-closed)
  finish REFUSED none 0 data_class
  exit 3
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final_dump="$BACKUP_DIR/mujoong-$stamp.dump"
final_roles="$BACKUP_DIR/mujoong-$stamp-roles.sql"
partial_dump="$final_dump.partial"
partial_roles="$final_roles.partial"

# B3 사용자 DB 전체(core · realtime · flyway 스키마, 한 스냅샷)
stage=pg_dump
pg_dump --format=custom --no-password --file="$partial_dump"

# B4 역할 정의(비밀번호 없이). 연결은 mujoong DB로 한다(postgres DB 연결 권한에 기대지 않음)
stage=pg_dumpall
pg_dumpall --roles-only --no-role-passwords --no-password --database="$PGDATABASE" --file="$partial_roles"

# B5 검증
stage=verify
if [ ! -s "$partial_dump" ] || [ ! -s "$partial_roles" ]; then
  false
fi
pg_restore --list "$partial_dump" >/dev/null

# B6 확정(같은 디렉터리 안 이름 변경이라 원자적)
stage=rename
mv -f -- "$partial_dump" "$final_dump"
partial_dump=""
mv -f -- "$partial_roles" "$final_roles"
partial_roles=""

# B7 보관 일수 지난 덤프 삭제(방금 확정한 파일은 제외)
stage=retention
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'mujoong-*.dump' -o -name 'mujoong-*-roles.sql' \) \
  -mmin "+$((retention_days * 1440))" ! -name "mujoong-$stamp*" -delete
# 지난 실행이 남긴 partial(비정상 종료) 정리
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'mujoong-*.partial' -mmin +60 -delete

# B8 결과 줄
trap - ERR
bytes="$(stat -c %s "$final_dump")"
finish OK "$(basename "$final_dump")" "$bytes" none
