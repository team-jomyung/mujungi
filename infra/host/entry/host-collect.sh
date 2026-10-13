#!/usr/bin/env bash
# /opt/mujoong/bin/host-collect.sh — 호스트 수집기의 고정 진입점 (U1 3.5 Step 47 · infra-spec 1.4 · 2.1 · 4.2 · monitoring 2.7.1)
# /etc/cron.d/mujoong이 1분마다 `flock -n · timeout 20`으로 부른다. 인자를 그대로 현재 릴리스의 host-collect.sh에 넘긴다.
set -euo pipefail
exec /opt/mujoong/current/bin/host-collect.sh "$@"
