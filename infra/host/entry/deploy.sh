#!/usr/bin/env bash
# /opt/mujoong/bin/deploy.sh — 배포 고정 진입점 (U1 3.5 Step 47 · infra-spec 4.2 · 4.5.4 · cicd 2.5.3 · 2.6.1 · R4-8)
#
# SSM 문서 mujoong-deploy(infra/aws/ssm-mujoong-deploy.yml)가 부르는 유일한 파일이다. 로직이 없는 얇은 파일이고, 인자를 그대로
# 현재 릴리스의 1단계 스크립트(/opt/mujoong/current/bin/deploy-stage1)에 넘긴다. 1단계가 대상 릴리스의 2단계(deploy-apply)로 넘긴다.
# /opt/mujoong/bin은 심볼릭 링크가 아닌 고정 디렉터리이고, 이 파일이 바뀌면 배포 D9가 임시 파일 → 이름 변경으로 바꾼다.
# 쓰는 법: deploy.sh --sha <40자> --mode DEPLOY|ROLLBACK [--trigger CI|MANUAL] [--run-id <번호>|none]
set -euo pipefail
exec /opt/mujoong/current/bin/deploy-stage1 "$@"
