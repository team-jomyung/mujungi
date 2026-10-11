#!/bin/sh
# 무중 Nginx 실행과 인증서 갱신 표지 감시 (U1 3.5 Step 44 · infra-spec 2.1 nginx · certbot · NFR9.1 · V-4)
#
# nginx 컨테이너의 명령이다: ["/bin/sh", "/etc/nginx/mujoong/reload-watch.sh"]. render-nginx가 렌더 디렉터리에 복사한다.
#   1. nginx를 전경(daemon off)으로 띄운다.
#   2. 60초마다 갱신 표지 파일(certbot deploy hook이 touch하는 /etc/letsencrypt/renewed.flag)의 변경 시각을 보고,
#      바뀌었으면 `nginx -t`가 통과할 때만 `nginx -s reload`로 다시 읽는다(Docker 소켓 없이, infra-spec 2.1).
#   3. TERM · INT · QUIT을 받으면 nginx에 QUIT(정상 종료)을 보내고 끝날 때까지 기다린다. nginx가 스스로 끝나면 같은 종료 코드로 끝난다.
# 환경(비밀 아님, 모두 선택): MUJOONG_NGINX_CONF(기본 /etc/nginx/mujoong/nginx.conf) · MUJOONG_RENEW_FLAG · MUJOONG_RELOAD_WATCH_INTERVAL(초)
# busybox sh(nginx alpine 이미지)에서 돈다.

set -eu

conf="${MUJOONG_NGINX_CONF:-/etc/nginx/mujoong/nginx.conf}"
flag="${MUJOONG_RENEW_FLAG:-/etc/letsencrypt/renewed.flag}"
interval="${MUJOONG_RELOAD_WATCH_INTERVAL:-60}"

case "$interval" in
  '' | *[!0-9]*)
    echo "reload-watch: MUJOONG_RELOAD_WATCH_INTERVAL은 초 단위 정수다" >&2
    exit 64
    ;;
esac

stamp() {
  if [ -e "$flag" ]; then
    stat -c '%Y' "$flag" 2>/dev/null || echo unreadable
  else
    echo none
  fi
}

# 시작 전에 설정을 검사한다(틀린 설정이면 기동하지 않는다)
nginx -t -q -c "$conf"

nginx -c "$conf" -g 'daemon off;' &
nginx_pid=$!

stopping=0
# shellcheck disable=SC2317,SC2329  # trap이 부른다(J345: shellcheck 0.9.0은 trap 처리기 본문을 SC2317, 0.10+는 SC2329로 오판)
stop() {
  stopping=1
  kill -QUIT "$nginx_pid" 2>/dev/null || true
}
trap stop TERM INT QUIT

last="$(stamp)"
while kill -0 "$nginx_pid" 2>/dev/null; do
  sleep "$interval" &
  sleep_pid=$!
  wait "$sleep_pid" 2>/dev/null || kill "$sleep_pid" 2>/dev/null || true
  [ "$stopping" -eq 1 ] && break
  now="$(stamp)"
  if [ "$now" != "$last" ]; then
    if nginx -t -q -c "$conf"; then
      nginx -s reload -c "$conf"
      echo "reload-watch: 갱신 표지가 바뀌어 설정을 다시 읽었다"
    else
      echo "reload-watch: 갱신 표지가 바뀌었지만 nginx -t가 실패해 다시 읽지 않았다" >&2
    fi
    last="$now"
  fi
done

set +e
wait "$nginx_pid"
rc=$?
if [ "$stopping" -eq 1 ]; then
  exit 0
fi
exit "$rc"
