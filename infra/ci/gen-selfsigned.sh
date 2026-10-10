#!/usr/bin/env bash
# 무중 검증 · dev용 자체 서명 인증서 — 실행 때 만든다 (U1 3.5 Step 49 · J108 · J127 · J136 · SEC-12)
#
# 저장소에는 인증서 · 키를 두지 않는다(.gitignore: *.pem · *.key, build/). 실행마다 새 CA와 서버 인증서를 만든다.
#   <out>/ca.crt                          자체 서명 CA 인증서(클라이언트가 --cacert · ca 옵션으로 신뢰한다)
#   <out>/live/<host>/fullchain.pem       서버 인증서(SAN: DNS:<host>, localhost면 IP:127.0.0.1 · ::1도)
#   <out>/live/<host>/privkey.pem         서버 키(EC P-256)
# 경로 모양은 Let's Encrypt와 같다. nginx 설정(conf.d/https443.conf)이 /etc/letsencrypt/live/${SERVICE_HOST}/를 읽고
# compose.ci.yml이 <out>을 /etc/letsencrypt에 읽기 전용으로 붙인다. host 모드 Nginx 대역(tools/dev-edge)도 같은 파일을 쓴다.
#
# CA 키는 임시 디렉터리에서만 쓰고 서명 뒤 지운다(남는 키는 서버 키 하나). 유효 기간은 2일이다(검증 한 번이면 충분).
# 키 권한: root로 돌면 0600. root가 아니면 0644다 — nginx 컨테이너 마스터(root, CAP_DAC_OVERRIDE 없음, cap_drop ALL)는
# 다른 uid 소유의 0600 파일을 읽지 못한다(GitHub 러너 등). 이 키는 실행 한 번에만 쓰는 버림 키이고 build/(무시됨)에 있으며
# 검증 명령이 끝에 지운다.
#
# 쓰는 법: bash infra/ci/gen-selfsigned.sh [--out <디렉터리>] [--host <이름>]
#   기본값: --out build/verify/certs(저장소 루트 기준), --host localhost
# 출력: 만든 파일 경로만(키 내용 · 지문은 출력하지 않는다). openssl 출력은 버린다.
# 종료 코드: 0 성공, 1 생성 실패, 2 전제 누락(openssl), 64 인자 오류

set -Eeuo pipefail
set +x
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$ROOT/build/verify/certs"
host=localhost

while [ "$#" -gt 0 ]; do
  case "$1" in
    --out) out="${2:-}"; shift ;;
    --host) host="${2:-}"; shift ;;
    -h | --help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "gen-selfsigned: 모르는 인자: $1" >&2; exit 64 ;;
  esac
  shift
done

if [ -z "$out" ]; then
  echo "gen-selfsigned: --out이 비었다" >&2
  exit 64
fi
if ! [[ "$host" =~ ^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$ ]] || [[ "$host" =~ ^[0-9.]+$ ]]; then
  echo "gen-selfsigned: --host는 소문자 호스트 이름이어야 한다(IP 주소 불가)" >&2
  exit 64
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo "gen-selfsigned: 전제 누락: openssl" >&2
  exit 2
fi
case "$out" in /*) ;; *) out="$PWD/$out" ;; esac

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

san="DNS:$host"
if [ "$host" = localhost ]; then
  san="$san,IP:127.0.0.1,IP:::1"
fi

cat >"$work/ca.cnf" <<'CNF'
[req]
distinguished_name = dn
prompt = no
x509_extensions = v3_ca
[dn]
CN = mujoong verify CA (throwaway)
[v3_ca]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
CNF

cat >"$work/server.ext" <<CNF
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = $san
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
CNF

if ! {
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 \
    -config "$work/ca.cnf" -keyout "$work/ca.key" -out "$work/ca.crt" &&
    openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=$host" \
      -keyout "$work/server.key" -out "$work/server.csr" &&
    openssl x509 -req -in "$work/server.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" -CAcreateserial \
      -days 2 -sha256 -extfile "$work/server.ext" -out "$work/server.crt" &&
    openssl verify -CAfile "$work/ca.crt" "$work/server.crt"
} >"$work/openssl.log" 2>&1; then
  echo "gen-selfsigned: 인증서 생성 실패(openssl)" >&2
  exit 1
fi

live="$out/live/$host"
mkdir -p "$live"
# 컨테이너(nginx)가 디렉터리를 지나갈 수 있어야 한다. 디렉터리에는 비밀이 없다
chmod 0755 "$out" "$out/live" "$live"
install -m 0644 "$work/ca.crt" "$out/ca.crt"
install -m 0644 "$work/server.crt" "$live/fullchain.pem"
if [ "$(id -u)" -eq 0 ]; then
  install -m 0600 "$work/server.key" "$live/privkey.pem"
else
  install -m 0644 "$work/server.key" "$live/privkey.pem"
fi

echo "gen-selfsigned: ${out#"$ROOT"/}/ca.crt · ${live#"$ROOT"/}/{fullchain,privkey}.pem ($host, 2일)"
