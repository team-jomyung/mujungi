#!/usr/bin/env bash
# 무중 실키 스택 내리기 — 프로젝트 mujoong-live만 대상이다 (mujoong-manual · mujoong-verify는 건드리지 않는다, R-F12)
#
# 쓰는 법: bash scripts/live/down.sh [--purge]
#   (기본)   컨테이너 · 망을 내린다. DB 볼륨과 build/live/ 값 파일은 남겨 다음 up.sh가 그대로 이어 쓴다.
#   --purge  DB 볼륨(mujoong-live_*)과 build/live/(생성 비밀 · 인증서)를 지운다. 키 파일(~/.config/mujoong/live.env)은 지우지 않는다.
# 종료 코드: 0 성공, 2 전제 누락, 64 인자 오류

set -Eeuo pipefail
set +x

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

purge=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --purge) purge=1 ;;
    -h | --help) sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "live-down: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done

docker info >/dev/null 2>&1 || { echo "live-down: Docker 데몬에 닿지 못한다" >&2; exit 2; }

readonly PROJECT=mujoong-live
label="label=com.docker.compose.project=$PROJECT"

# compose 파일 없이 프로젝트 라벨로만 찾는다(값 파일이 이미 지워졌어도 내릴 수 있고, 다른 프로젝트는 라벨이 달라 닿지 않는다)
mapfile -t containers < <(docker ps -aq --filter "$label")
[ "${#containers[@]}" -eq 0 ] || docker rm -f "${containers[@]}" >/dev/null
mapfile -t networks < <(docker network ls -q --filter "$label")
[ "${#networks[@]}" -eq 0 ] || docker network rm "${networks[@]}" >/dev/null
if [ "$purge" -eq 1 ]; then
  mapfile -t volumes < <(docker volume ls -q --filter "$label")
  [ "${#volumes[@]}" -eq 0 ] || docker volume rm "${volumes[@]}" >/dev/null
fi

if [ "$purge" -eq 1 ]; then
  rm -rf "$ROOT/build/live"
  echo "live-down: 볼륨과 build/live/를 지웠습니다. 키 파일은 그대로입니다."
else
  echo "live-down: 내렸습니다. 데이터는 남아 있습니다(지우려면 --purge)."
fi
