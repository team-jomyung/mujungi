# U5b 독립 구현 빌드 지침

## 범위와 환경

저장소 루트에서 Java 21, Gradle wrapper, Node 22/npm, Docker와 폐기 가능한 PostgreSQL을 사용한다. 이미 설치된 frontend/node_modules와 Gradle 캐시를 재사용한다. 새 환경에서는 npm --prefix frontend ci로 잠금 의존성을 설치한다. 실제 공급자 비밀 값·운영 DB·상대 스키마는 사용하지 않는다.

U5b 전용 설정은 practice/u5b-independent.yml이며 자동 로드하지 않는다. V4는 db/pending/realtime에 있고 실제 애플리케이션 자동 마이그레이션에 넣지 않는다. 빌드 성공은 실제 시작 API·빈·화면 활성화·V3→V4 검증 완료가 아니다.

## 실행 명령

```text
./backend/gradlew -p backend --no-daemon :core-api:bootJar :realtime-activity:bootJar
npm --prefix frontend run build
npm --prefix frontend run lint
npm --prefix frontend run typecheck
```

산출물은 backend/core-api/build/libs/core-api.jar, backend/realtime-activity/build/libs/realtime-activity.jar와 frontend/.next다. 빌드 명령의 종료 코드와 산출물 존재를 확인한다. frontend 빌드가 설정/환경 때문에 실패하면 원인을 기록하고 통과로 대체하지 않는다.

## 문제 해결

Gradle 캐시/시험 DB의 호스트 접근 권한이 필요하면 승인된 실행 경로를 사용한다. Docker를 사용할 수 없으면 기존 로컬 PostgreSQL 지원을 확인하되 운영 DB를 대체로 쓰지 않는다. 빈 시험 발견, 커버리지 축소, 기존 단언 삭제로 실패를 숨기지 않는다.

