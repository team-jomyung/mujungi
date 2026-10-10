#!/usr/bin/env bash
# 무중 템플릿 렌더 공용 함수 (U1 3.5 Step 44 · infra-spec 3.3 · SD-1 · J98)
#
# render-nginx · render-pgconf · build-images.sh(release.env)가 source한다. 단독 실행하지 않는다.
#
# mujoong_render_template <템플릿> <출력 파일> <변수 이름>...
#   - 템플릿의 ${이름} 토큰 중 인자로 명시한 이름만 같은 이름의 환경 변수 값으로 바꾼다(envsubst에 변수 목록을 명시한 것과 같다).
#     Nginx 자체 변수($host · $request_uri 등)와 목록 밖 이름은 건드리지 않는다. 값은 글자 그대로 넣고 다시 해석하지 않는다.
#   - 명시한 변수가 비어 있거나, 렌더 뒤에 ${대문자 이름} 토큰이 남으면 실패한다(종료 코드 1, 값은 출력하지 않음).
#   - 호스트 기준선에 gettext(envsubst)가 없어(infra-spec 1.4) awk(mawk · gawk)로 구현한다.
#
# mujoong_require_safe_value <이름> <정규식>
#   - 환경 변수 값이 정규식(ERE, 전체 일치)에 맞지 않으면 이름만 출력하고 실패한다. 설정 주입(; { } 따옴표 줄바꿈)을 막는다.

mujoong_render_template() {
  local template="$1" out="$2"
  shift 2
  if [ ! -f "$template" ]; then
    echo "render: 템플릿이 없다: $template" >&2
    return 1
  fi
  local name
  for name in "$@"; do
    if ! [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
      echo "render: 변수 이름 형식이 틀렸다: $name" >&2
      return 1
    fi
    if [ -z "${!name:-}" ]; then
      echo "render: 템플릿 변수 $name 이(가) 비어 있다" >&2
      return 1
    fi
  done
  local tmp
  tmp="$(mktemp "${out}.XXXXXX")"
  # 이름 목록은 공백으로 이어 넘기고, 값은 awk가 ENVIRON에서 직접 읽는다(명령줄에 값이 나타나지 않는다)
  if ! awk -v names="$*" '
    BEGIN { n = split(names, keys, " ") }
    {
      line = $0
      for (i = 1; i <= n; i++) {
        token = "${" keys[i] "}"
        value = ENVIRON[keys[i]]
        rest = line
        line = ""
        while ((p = index(rest, token)) > 0) {
          line = line substr(rest, 1, p - 1) value
          rest = substr(rest, p + length(token))
        }
        line = line rest
      }
      print line
    }' "$template" >"$tmp"; then
    rm -f -- "$tmp"
    echo "render: 렌더에 실패했다: $template" >&2
    return 1
  fi
  if grep -nE '\$\{[A-Z][A-Z0-9_]*\}' "$tmp" >/dev/null; then
    local left
    left="$(grep -oE '\$\{[A-Z][A-Z0-9_]*\}' "$tmp" | sort -u | tr '\n' ' ')"
    rm -f -- "$tmp"
    echo "render: 렌더 뒤에 바꾸지 않은 토큰이 남았다($template): $left" >&2
    return 1
  fi
  chmod 0644 "$tmp"
  mv -f -- "$tmp" "$out"
}

mujoong_require_safe_value() {
  local name="$1" pattern="$2"
  local value="${!name:-}"
  if [ -z "$value" ]; then
    echo "render: $name 이(가) 비어 있다" >&2
    return 1
  fi
  if ! [[ "$value" =~ ^($pattern)$ ]]; then
    echo "render: $name 값 형식이 허용 범위 밖이다(값은 출력하지 않는다)" >&2
    return 1
  fi
}

# 호스트 이름(포트 없음, 소문자 · 숫자 · 하이픈 · 점, 라벨 63자 이하)
# shellcheck disable=SC2034  # source하는 스크립트가 쓴다
MUJOONG_HOSTNAME_RE='[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*'
