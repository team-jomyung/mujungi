# U6 빌드 지시

## 준비

JDK21, Node22, PostgreSQL16 또는 Docker, 기존 Gradle wrapper8.14.3과 frontend lockfile을 사용한다. 현 세션 JAVA_HOME=/tmp/jdk-21.0.12.1+1, GRADLE_USER_HOME=/tmp/u6-gradle이다. 새 환경 frontend 의존성은 npm ci로 설치한다. 비밀값은 출력하지 않는다.

## 명령과 순서

backend에서 `./gradlew --no-daemon :core-api:bootJar :core-api:verifyBootJarHasNoTestClasses`, frontend에서 `npm run build`, `npm run lint`, `npm run typecheck`를 실행한다. 운영 jar 시험 항목0과 성공 종료가 기준이다. 먼저 integration-test-instructions.md의 삭제 회귀를 재현한다. 실패하면 이번 검증을 중단하고 미실행 명령을 Unverified로 마감한다. DB·Gradle 시험은 순차 실행한다.

## Sources

construction/code-generation/code-generation-plan.md · unit-test-instructions.md · code-summary.md; inception/requirements-analysis/requirements.md; construction/functional-design/functional-spec.md。
