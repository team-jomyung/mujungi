#!/usr/bin/env bash
# 무중 실키 기동용 키 입력 — 키를 저장소 밖 파일에만 둔다 (R-F3 · R-F13)
#
# 쓰는 법: bash scripts/live/keys.sh [--show] [--file <경로>]
#   (기본)  OPENAI_API_KEY · JEV_API_KEY는 화면에 보이지 않게(read -s), JEV_BASE_URL · JEV_MODEL은 보이게 묻는다.
#           이미 값이 있으면 빈 줄(Enter)로 그대로 둔다. 같은 이름의 환경 변수가 이미 있으면 묻지 않고 그 값을 쓴다.
#   --show  값은 보이지 않고 이름별로 설정됨 · 비어 있음만 출력한다
#   --file  저장 위치(기본 ~/.config/mujoong/live.env, 환경 변수 MUJOONG_LIVE_ENV_FILE로도 지정)
# 안전장치:
#   - 저장 위치가 이 git 저장소 안이면 거절한다(push될 수 있는 곳에는 쓰지 않는다).
#   - 파일은 0600, 디렉터리는 0700으로 만든다. 값은 표준 출력 · 로그에 쓰지 않는다.
#   - JEV_API_KEY를 비워 두면 제브는 가짜 대역(STUB)으로 돈다. OPENAI_API_KEY는 필수다.
#   - 키를 채팅 · 이슈 · 커밋에 붙여 넣지 않는다.
# 종료 코드: 0 성공, 1 입력 오류, 64 인자 오류

set -Eeuo pipefail
set +x
umask 077

# 제브 공식 빠른 시작(docs.typesafe.ai/introduction/quickstart)의 값. 비밀이 아니다.
#  - 기준 주소는 호스트까지만: 어댑터가 /v1/systemone를 코드 상수로 붙인다(JevGuardrailClient.SYSTEMONE_PATH).
#  - 모델은 고정 버전: 문서 예시의 jev-latest는 이동 별칭이라 기동 점검이 거부한다. 응답 model이 jev-1.13.0(U13 실호출 보고와 같다).
readonly DEFAULT_JEV_BASE_URL="https://api.typesafe.ai"
readonly DEFAULT_JEV_MODEL="jev-1.13.0"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
file="${MUJOONG_LIVE_ENV_FILE:-$HOME/.config/mujoong/live.env}"
show=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --show) show=1 ;;
    --file) file="${2:-}"; shift ;;
    -h | --help) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "keys: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done

[ -n "$file" ] || { echo "keys: 저장 위치가 비어 있다" >&2; exit 64; }
case "$file" in /*) ;; *) file="$PWD/$file" ;; esac
dir="$(dirname "$file")"

# 저장 위치가 저장소 안이면 거절한다(심볼릭 링크를 풀어서 비교)
real_dir="$(cd "$dir" 2>/dev/null && pwd -P || echo "$dir")"
case "$real_dir/" in
  "$ROOT"/*)
    echo "keys: 저장 위치가 저장소 안($real_dir)이다. 저장소 밖 경로를 쓰세요(기본 ~/.config/mujoong/live.env)" >&2
    exit 1
    ;;
esac

declare -A cur=()
if [ -f "$file" ]; then
  while IFS='=' read -r k v; do
    case "$k" in OPENAI_API_KEY | JEV_API_KEY | JEV_BASE_URL | JEV_MODEL) cur["$k"]="$v" ;; esac
  done <"$file"
fi

[ -n "${cur[JEV_BASE_URL]:-}" ] || cur[JEV_BASE_URL]="$DEFAULT_JEV_BASE_URL"
[ -n "${cur[JEV_MODEL]:-}" ] || cur[JEV_MODEL]="$DEFAULT_JEV_MODEL"

if [ "$show" -eq 1 ]; then
  echo "파일: $file"
  for k in OPENAI_API_KEY JEV_API_KEY JEV_BASE_URL JEV_MODEL; do
    if [ -n "${cur[$k]:-}" ]; then echo "  $k: 설정됨"; else echo "  $k: 비어 있음"; fi
  done
  exit 0
fi

# 값 형식: 공백 · 따옴표 · 줄바꿈 없는 한 줄(env 파일 한 줄로 안전하게 쓸 수 있는 문자만)
valid() { [[ "$1" =~ ^[A-Za-z0-9._:/+=@~-]+$ ]]; }

ask() { # ask <이름> <secret|plain> <설명>
  local name="$1" kind="$2" label="$3" val="${!1:-}"
  if [ -z "$val" ]; then
    local prompt="$label"
    if [ -n "${cur[$name]:-}" ]; then
      if [ "$kind" = secret ]; then prompt="$label (Enter = 현재 값 유지)"; else prompt="$label [${cur[$name]}]"; fi
    fi
    if [ ! -t 0 ]; then
      val="${cur[$name]:-}"
    elif [ "$kind" = secret ]; then
      read -rsp "$prompt: " val
      echo
    else
      read -rp "$prompt: " val
    fi
    [ -n "$val" ] || val="${cur[$name]:-}"
  fi
  if [ -n "$val" ] && ! valid "$val"; then
    echo "keys: $name 값에 쓸 수 없는 문자가 있다(공백 · 따옴표 · 줄바꿈 금지)" >&2
    exit 1
  fi
  cur["$name"]="$val"
}

ask OPENAI_API_KEY secret "OpenAI API 키(GPT-Live · Luna, 필수)"
ask JEV_API_KEY secret "제브 API 키(U13 jevLiveTest와 같은 값, 비우면 제브는 가짜 대역)"
if [ -n "${cur[JEV_API_KEY]}" ]; then
  ask JEV_BASE_URL plain "제브 주소(https://…, 허용 호스트만 기동을 통과한다)"
  ask JEV_MODEL plain "제브 모델 이름(고정 버전, -latest 금지)"
fi

[ -n "${cur[OPENAI_API_KEY]}" ] || { echo "keys: OPENAI_API_KEY는 필수다" >&2; exit 1; }
if [ -n "${cur[JEV_API_KEY]}" ]; then
  if [ -z "${cur[JEV_BASE_URL]:-}" ] || [ -z "${cur[JEV_MODEL]:-}" ]; then
    echo "keys: 제브 키를 쓰면 주소와 모델도 필요하다" >&2
    exit 1
  fi
  case "${cur[JEV_MODEL]}" in *-latest | latest) echo "keys: JEV_MODEL은 고정 버전이어야 한다(-latest 금지)" >&2; exit 1 ;; esac
  case "${cur[JEV_BASE_URL]}" in https://*) ;; *) echo "keys: JEV_BASE_URL은 https 주소여야 한다" >&2; exit 1 ;; esac
fi

mkdir -p "$dir"
chmod 0700 "$dir"
tmp="$(mktemp "$dir/.live.env.XXXXXX")"
{
  echo "OPENAI_API_KEY=${cur[OPENAI_API_KEY]}"
  echo "JEV_BASE_URL=${cur[JEV_BASE_URL]}"
  echo "JEV_MODEL=${cur[JEV_MODEL]}"
  if [ -n "${cur[JEV_API_KEY]}" ]; then
    echo "JEV_MODE=REAL"
    echo "JEV_API_KEY=${cur[JEV_API_KEY]}"
  fi
} >"$tmp"
chmod 0600 "$tmp"
mv -f "$tmp" "$file"
echo "저장했습니다: $file (0600, 저장소 밖). 다음: bash scripts/live/up.sh --help"
