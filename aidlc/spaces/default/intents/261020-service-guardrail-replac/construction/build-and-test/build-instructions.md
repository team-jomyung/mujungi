# 통합 빌드 실행 지시

## Sources

두 유닛의 code-generation-plan.md, unit-test-instructions.md, code-summary.md 및 nfr-requirements/·nfr-design/ 전체를 입력으로 한다. 현재 test-after·Standard·모듈/기능 LINE80%를 보존한다.

## 환경과 의존성

JDK21, Node22, 기존 lockfile의 npm 의존성, Docker/Testcontainers를 사용한다. 설치가 필요할 때 npm ci를 사용한다. 실제 유료 공급자와 아동 자료를 사용하지 않는다. 별도 mujoong-verify만 사용하고 mujoong-manual을 변경하지 않는다. WSL 시간 도구는 /usr/bin/gnudate를 date로 PATH 앞에 둔다. 브라우저/GUI는 이번 실행에서 다시 시작하지 않는다.

## 빌드와 회귀 명령

저장소 루트에서 실행한다.

```sh
(cd backend && ./gradlew --no-daemon clean check -Pmujoong.test.db=docker) && (cd frontend && npm run lint && npx vitest run tests --coverage && npm run build)
```

직전 U17 체크포인트는 2026-10-21T04:33:19Z 종료0이며 동일 소스 전체 빌드의 참고 증거다. Build and Test 실행과 중복 계수하지 않는다. 전체 통합 검증은 integration-test-instructions.md를 따른다. 전제 실패·시험 실패·미검증 목표가 있으면 성공으로 처리하지 않는다.

## 단위 명령 목록

같은 명령은 한 번만 실행한다. 아래 U16은 backend 작업 디렉터리, U17은 저장소 루트다. 수정된 원본 지시서가 생기면 현재 명령 목록을 다시 수집한다.

### u16-service-guardrail

```sh
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.voice.ProviderRequestFactoryWireTest -Pmujoong.test.db=docker
./gradlew --no-daemon :realtime-activity:test --tests '*ServiceGuardrail*' -Pmujoong.test.db=docker
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.dialog.ServicePolicyCatalogServiceGuardrailTest -Pmujoong.test.db=docker
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.session.SessionGuardrailFactsServiceGuardrailIT -Pmujoong.test.db=docker
./gradlew --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.dialog.StopConfirmServiceGuardrailTest -Pmujoong.test.db=docker
./gradlew --no-daemon :core-api:test --tests '*ServiceGuardrail*' -Pmujoong.test.db=docker
./gradlew --no-daemon :contract-model:test --tests '*ServiceGuardrail*'
./gradlew --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail -Pmujoong.test.db=docker
./gradlew --no-daemon :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail -Pmujoong.test.db=docker
./gradlew --no-daemon :contract-model:test :contract-model:jacocoTestCoverageVerification -PincludeTags=service-guardrail -PcoverageScope=service-guardrail 
```

### u17-guardrail-presentation

```sh
cd frontend && npx vitest run tests/parent/common/api/api-types.test.ts tests/device/audio/player.test.ts
cd frontend && npx vitest run tests/parent/common/api/guardrail-presentation.test.ts
cd frontend && npx vitest run tests/parent/common/guardrail-live-updates.test.tsx
cd frontend && npx vitest run tests/parent/features/live/guardrail-participation.test.tsx
cd frontend && npx vitest run tests/parent/features/done/guardrail-done-presentation.test.ts
cd frontend && npx vitest run tests/parent/features/done/guardrail-done-screen.test.tsx
cd frontend && npx vitest run tests/device/ws/guardrail-device-session.test.ts
cd frontend && npx vitest run tests/device/audio/guardrail-player.test.ts
cd frontend && npx vitest run tests/parent/features/live/guardrail-accessibility.test.tsx
cd frontend && npx vitest run tests/parent/common/api/api-types.test.ts tests/parent/common/activity-logic.test.ts tests/parent/features/live/live-12.test.tsx tests/parent/features/live/p1-dialog.test.tsx tests/parent/features/done/done-17.test.tsx tests/parent/features/done/done-17-result.test.tsx tests/parent/features/activity-session-screens.test.tsx tests/parent/features/practice/practice-screens.test.tsx tests/device/ws/device-session.test.ts tests/device/ws/device-session-resume.test.ts tests/device/audio/player.test.ts tests/device/audio/player-progress.test.ts tests/device/audio/barge-in.test.ts tests/device/runtime/device-runtime-voice-session.test.ts tests/device/features/status/status-view.test.tsx tests/parent/common/api/api-client.test.ts tests/parent/common/api/account-api.test.ts tests/parent/features/login/dev-login.test.tsx tests/parent/features/login/login-01.test.tsx
cd frontend && MUJOONG_COVERAGE_SCOPE=guardrail-presentation npx vitest run tests/parent/common/api/guardrail-presentation.test.ts tests/parent/common/guardrail-live-updates.test.tsx tests/parent/features/live/guardrail-participation.test.tsx tests/parent/features/done/guardrail-done-presentation.test.ts tests/parent/features/done/guardrail-done-screen.test.tsx tests/device/ws/guardrail-device-session.test.ts tests/device/audio/guardrail-player.test.ts tests/parent/features/live/guardrail-accessibility.test.tsx tests/parent/common/api/api-types.test.ts tests/parent/common/activity-logic.test.ts tests/parent/features/live/live-12.test.tsx tests/parent/features/live/p1-dialog.test.tsx tests/parent/features/done/done-17.test.tsx tests/parent/features/done/done-17-result.test.tsx tests/parent/features/activity-session-screens.test.tsx tests/parent/features/practice/practice-screens.test.tsx tests/device/ws/device-session.test.ts tests/device/ws/device-session-resume.test.ts tests/device/audio/player.test.ts tests/device/audio/player-progress.test.ts tests/device/audio/barge-in.test.ts tests/device/runtime/device-runtime-voice-session.test.ts tests/device/features/status/status-view.test.tsx tests/parent/common/api/api-client.test.ts tests/parent/common/api/account-api.test.ts tests/parent/features/login/dev-login.test.tsx tests/parent/features/login/login-01.test.tsx --coverage 
```

## 문제 해결

Gradle DB 전제는 Docker 선택을 확인한다. sandbox 쓰기/네트워크 거절은 승인된 명령의 적절한 권한으로 재실행한다. 품질 하한/기존 단언/시간 예산을 낮추거나 시험을 건너뛰지 않는다. 실제 원인과 실패 시점을 test-results.md에 남긴다.

