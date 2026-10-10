#!/usr/bin/env bash
# 무중 migrate 진입점 (U1 3.5 Step 43 · infra-spec 2.1 migrate · BR8.1 · BR8.2 · J116 · SD-12)
#
# migrate 컨테이너(이미지 mujoong-migrate)의 ENTRYPOINT다. 소유자 역할 mujoong_owner로 두 위치를 core → realtime 순서로 적용하고
# 종료한다(성공 = 종료 코드 0). 앱은 Flyway를 돌리지 않고 이력 버전만 읽는다(BR8.1).
#
# 입력(환경 변수 이름만, 값은 출력하지 않는다)
#   - 필수: DB_OWNER_PASSWORD (migrate.env, infra-spec 3.7)
#   - 선택: MUJOONG_MIGRATE_JDBC_URL (기본 jdbc:postgresql://postgres:5432/mujoong — backend 망 이름, ports.md)
# 인자
#   - 없음 또는 migrate: 두 위치 적용
#   - info | validate: 두 위치의 Flyway info · validate만 돈다(복원 리허설 R5, reliability-design.md 2.12)

set -Eeuo pipefail

command="${1:-migrate}"
case "$command" in
  migrate | info | validate) ;;
  *)
    echo "mujoong-migrate: 모르는 명령: $command (migrate · info · validate)" >&2
    exit 64
    ;;
esac

if [ -z "${DB_OWNER_PASSWORD:-}" ]; then
  echo "mujoong-migrate: 필수 환경 변수 DB_OWNER_PASSWORD가 비어 있다" >&2
  exit 1
fi

# Flyway는 FLYWAY_* 환경 변수를 설정 파일보다 앞서 읽는다. 비밀번호는 환경으로만 넘기고 명령줄 인자로 넘기지 않는다.
export FLYWAY_URL="${MUJOONG_MIGRATE_JDBC_URL:-jdbc:postgresql://postgres:5432/mujoong}"
export FLYWAY_USER="mujoong_owner"
export FLYWAY_PASSWORD="$DB_OWNER_PASSWORD"
unset DB_OWNER_PASSWORD

for location in core realtime; do
  echo "mujoong-migrate: ${location} 위치 ${command} 시작"
  flyway -configFiles="/flyway/conf/flyway-${location}.conf" "$command"
  echo "mujoong-migrate: ${location} 위치 ${command} 완료"
done
