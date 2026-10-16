## Run Record

| 항목 | 값 |
|---|---|
| 날짜 | 2026-10-16 |
| 저장소 | mujungi |
| 커밋 | 7e30ee8b2ad6d157a95d749ab774f3b7c0b9385d |
| 브랜치 | unit/u15-knowledge-graph |
| 인텐트 | 261016-u15-knowledge-graph |
| 범위 | Standard, U15 focused merge, 이전 STALE |
| 수행 | 개발자 handoff 확인 후 사용자 승인으로 이 채팅에서 합성 |

기존 9문서의 관련 없는 본문을 이력으로 보존했다. 이전 deep 경로는 shallow로 내렸으며 이번 분석 범위만 아래 analyzed에 기록한다. source zip 실제 내용, frontend/realtime 구현, CI yaml은 새로 검증하지 않았다. 초기 기준 테스트는 CRLF 초기화 실패였다. Linux 스크립트를 LF로 정규화했고 현재 재시험 중이다. 제품 소스의 Git 내용은 변경되지 않았음을 재확인했다. 개발자 handoff는 현재 인텐트 inception/reverse-engineering/developer-scan.md에 보존한다.

## Scope of Analysis

```yaml
scope_version: 1
kind: partial
intent: 261016-u15-knowledge-graph
fingerprint: abd07370020ae545a8b39bf944e4a736941f33e4
analyzed:
  paths:
    - backend/core-api/src/main/java/kr/mujoong/core/knowledge/
    - backend/core-api/src/main/java/kr/mujoong/core/common/deletion/
    - backend/core-api/src/main/java/kr/mujoong/core/account/DeletionCommander.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/ResultDeletionParticipant.java
    - backend/core-api/src/main/java/kr/mujoong/core/identity/GuardianWriteLock.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/check/ForbiddenDictionary.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/CoreLunaClient.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaPurpose.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaResult.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaInputSanitizer.java
    - backend/core-api/src/main/java/kr/mujoong/core/luna/LunaConfiguration.java
    - backend/core-api/src/main/resources/db/migration/core/V7__u5b_practice_preparation.sql
    - backend/core-api/src/main/resources/db/migration/core/afterMigrate.sql
    - backend/core-api/src/test/java/kr/mujoong/core/knowledge/PragmaticsKnowledgeTest.java
    - backend/core-api/src/test/java/kr/mujoong/core/arch/LunaArchTest.java
    - backend/core-api/build.gradle.kts
    - backend/contract-model/build.gradle.kts
    - backend/contract-model/src/main/java/kr/mujoong/contract/event/payload/DeletionCompleted.java
    - backend/gradle/libs.versions.toml
    - backend/gradle/wrapper/gradle-wrapper.properties
    - backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts
    - contracts/schemas/ai-seed-output.schema.json
    - scripts/verify/a-checks.sh
  components:
    - u15.knowledge-boundary
    - u15.luna-boundary
    - u15.deletion-boundary
    - u15.seed-contract
    - u15.build-verification
shallow:
  paths:
    - **깊게 분석**: Gradle 빌드 설정 전체 · 규약 플러그인, contract-model의 `ws` · `event` · `status`, 음성 공급자 연결 3파일 · 역할 지시문 · 연습 런타임 기본 빈 · WS 설정, 텍스트 AI · 결과 문장 기본 빈, 두 앱 설정 yml(일부 프로필), 두 앱 마이그레이션, 프런트 빌드 · 시험 · 린트 설정과 `src/app` 라우트, tools 패키지 설정, CI 워크플로, HTTP 계약 3종과 계약 README, Prettier 설정.
    - backend/settings.gradle.kts
    - backend/build.gradle.kts
    - backend/gradle/libs.versions.toml
    - backend/gradle.properties
    - backend/gradle/wrapper/gradle-wrapper.properties
    - backend/build-logic/
    - backend/contract-model/build.gradle.kts
    - backend/core-api/build.gradle.kts
    - backend/realtime-activity/build.gradle.kts
    - backend/test-support/build.gradle.kts
    - backend/contract-model/src/main/java/kr/mujoong/contract/ws/
    - backend/contract-model/src/main/java/kr/mujoong/contract/event/
    - backend/contract-model/src/main/java/kr/mujoong/contract/status/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderHosts.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderRequestFactory.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderClient.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/DialogInstructions.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeRuntimeConfiguration.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/WebSocketConfig.java
    - backend/core-api/src/main/java/kr/mujoong/core/textai/TextAiConfiguration.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/ResultTextConfiguration.java
    - backend/core-api/src/main/resources/application.yml
    - backend/realtime-activity/src/main/resources/application.yml
    - backend/realtime-activity/src/main/resources/application-prod.yml
    - backend/realtime-activity/src/main/resources/application-demo.yml
    - backend/core-api/src/main/resources/db/migration/core/
    - backend/realtime-activity/src/main/resources/db/migration/realtime/
    - frontend/package.json
    - frontend/next.config.ts
    - frontend/vitest.config.ts
    - frontend/eslint.config.mjs
    - frontend/src/app/
    - tools/package.json
    - .github/workflows/ci.yml
    - contracts/README.md
    - contracts/parent-api.yaml
    - contracts/device-api.yaml
    - contracts/internal-api.yaml
    - .prettierrc.json
    - backend/core-api/src/main/java/
    - backend/realtime-activity/src/main/java/
    - backend/contract-model/src/main/java/kr/mujoong/contract/
    - backend/test-support/src/main/
    - backend/core-api/src/test/
    - backend/realtime-activity/src/test/
    - backend/contract-model/src/test/
    - backend/test-support/src/test/
    - backend/core-api/src/main/resources/
    - backend/realtime-activity/src/main/resources/
    - backend/coverage-scope/
    - frontend/coverage-scope/
    - frontend/src/
    - frontend/tests/
    - tools/
    - infra/
    - scripts/
    - ci/
    - .github/workflows/deploy.yml
    - contracts/schemas/
    - contracts/device-ws.asyncapi.yaml
    - contracts/events.asyncapi.yaml
    - contracts/deviations.md
    - contracts/ai-io.md
    - contracts/timing.md
    - contracts/errors.md
    - contracts/ports.md
    - docs/
```
