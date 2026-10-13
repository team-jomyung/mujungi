#!/usr/bin/env bash
# 무중 PostgreSQL 초기화 2 — pg_hba.conf (U1 3.5 Step 9 · J135 · SD-13 · NFR9.7 · R-02 · SEC-04)
#
# 허용 대역은 필수 환경 변수 PG_HBA_ALLOWED_CIDR 하나로 받는다(기본값 없음, 비면 종료 코드 1).
# 그 대역의 scram-sha-256 한 줄과 나머지 reject만 쓴다. 넘기는 값(비밀 아님, Compose environment:):
#   - Compose demo · ci: 172.30.20.0/24 (backend 서브넷, infra-spec N-2)
#   - 시험 MujoongPostgres: docker 백엔드는 컨테이너가 보는 출발지(브리지 게이트웨이 /32), local 백엔드는 127.0.0.1/32
# 대상 파일은 $PGDATA/pg_hba.conf다. 초기화 중 임시 서버에는 바로 적용되지 않고 본 서버 기동(또는 reload) 때 적용된다.

mujoong_write_hba() (
  set -Eeuo pipefail

  local cidr="${PG_HBA_ALLOWED_CIDR:-}"
  if [ -z "$cidr" ]; then
    echo "02-hba.sh: PG_HBA_ALLOWED_CIDR가 비어 있다(J135)" >&2
    exit 1
  fi

  local ipv4='^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})/([0-9]{1,2})$'
  local ipv6='^[0-9A-Fa-f:]+/([0-9]{1,3})$'
  if [[ "$cidr" =~ $ipv4 ]]; then
    local octet
    for octet in "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}" "${BASH_REMATCH[4]}"; do
      if [ "$((10#$octet))" -gt 255 ]; then
        echo "02-hba.sh: PG_HBA_ALLOWED_CIDR 형식이 틀렸다(IPv4 옥텟)" >&2
        exit 1
      fi
    done
    local prefix=$((10#${BASH_REMATCH[5]}))
    if [ "$prefix" -lt 8 ] || [ "$prefix" -gt 32 ]; then
      echo "02-hba.sh: PG_HBA_ALLOWED_CIDR 접두 길이는 /8 ~ /32여야 한다" >&2
      exit 1
    fi
  elif [[ "$cidr" =~ $ipv6 ]] && [[ "$cidr" == *:* ]]; then
    local prefix6=$((10#${BASH_REMATCH[1]}))
    if [ "$prefix6" -lt 16 ] || [ "$prefix6" -gt 128 ]; then
      echo "02-hba.sh: PG_HBA_ALLOWED_CIDR 접두 길이는 /16 ~ /128여야 한다" >&2
      exit 1
    fi
  else
    echo "02-hba.sh: PG_HBA_ALLOWED_CIDR 형식이 틀렸다(CIDR 표기 하나)" >&2
    exit 1
  fi

  local data_dir="${PGDATA:-}"
  if [ -z "$data_dir" ] || [ ! -d "$data_dir" ]; then
    echo "02-hba.sh: PGDATA 디렉터리가 없다" >&2
    exit 1
  fi

  local tmp
  tmp="$(mktemp "$data_dir/pg_hba.conf.XXXXXX")"
  cat >"$tmp" <<HBA
# 무중 pg_hba.conf — infra/postgres/init/02-hba.sh가 만든다(J135 · SD-13). 손으로 고치지 않는다.
# 허용: PG_HBA_ALLOWED_CIDR 한 대역의 scram-sha-256. 그 밖의 TCP · 유닉스 소켓 접속은 모두 거부한다.
# TYPE  DATABASE  USER  ADDRESS  METHOD
host    all       all   ${cidr}  scram-sha-256
local   all       all            reject
host    all       all   all      reject
HBA
  chmod 600 "$tmp"
  mv -f "$tmp" "$data_dir/pg_hba.conf"
  echo "02-hba.sh: pg_hba.conf를 썼다(허용 대역 1개, 나머지 거부)"
)

mujoong_write_hba
