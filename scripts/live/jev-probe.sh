#!/usr/bin/env bash
# 제브 실호출 확인(U13 jevLiveTest) — 키 파일(~/.config/mujoong/live.env)의 값으로 돌린다
#
# 쓰는 법: bash scripts/live/jev-probe.sh [--file <키 파일>]
#   U13의 jevLiveTest를 그대로 부른다(합성 표본 5개: 허용 기대 1 · 막힘 기대 4를 운영 어댑터로 한 번씩 보낸다).
#   키 파일의 JEV_API_KEY · JEV_BASE_URL · JEV_MODEL을 이 명령 한 줄의 환경 변수로만 넘기고 셸에 남기지 않는다.
#   보고서(build/jev-live/report.txt)에는 표본 이름 · 판정 · 지연 등만 있고 키 · 주소 · 문장 원문은 없다(R-F14).
#   결과는 [실호출-합성]으로 따로 적는다(R-M11). 실제 아이 데이터는 보내지 않는다.
# 전제: 키 파일에 제브 값이 있을 것(bash scripts/live/keys.sh), 제브 R-F15 확인 기록이 있을 것(infra/RUNBOOK.md 단계 4), JDK 21.
# 종료 코드: 0 실행함(판정은 보고서 확인), 1 시험 실패, 2 전제 누락, 64 인자 오류

set -Eeuo pipefail
set +x

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
file="${MUJOONG_LIVE_ENV_FILE:-$HOME/.config/mujoong/live.env}"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --file) file="${2:-}"; shift ;;
    -h | --help) sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "jev-probe: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done

[ -f "$file" ] || { echo "jev-probe: 키 파일이 없다($file). 먼저: bash scripts/live/keys.sh" >&2; exit 2; }
declare -A v=()
while IFS='=' read -r k val; do
  case "$k" in JEV_API_KEY | JEV_BASE_URL | JEV_MODEL) v["$k"]="$val" ;; esac
done <"$file"
for k in JEV_API_KEY JEV_BASE_URL JEV_MODEL; do
  if [ -z "${v[$k]:-}" ]; then
    echo "jev-probe: 키 파일에 $k가 없다. 제브 값을 넣으세요: bash scripts/live/keys.sh" >&2
    exit 2
  fi
done

cd "$ROOT/backend"
rc=0
JEV_API_KEY="${v[JEV_API_KEY]}" JEV_BASE_URL="${v[JEV_BASE_URL]}" JEV_MODEL="${v[JEV_MODEL]}" \
  ./gradlew --no-daemon :realtime-activity:jevLiveTest || rc=$?

report="$ROOT/backend/realtime-activity/build/jev-live/report.txt"
if [ -f "$report" ]; then
  echo "---- $report"
  cat "$report"
fi
exit "$rc"
