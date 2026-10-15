# 리버스 엔지니어링 시점 (Reverse Engineering Timestamp)

| 항목 | 값 |
|---|---|
| 수행 날짜 | 2026-10-15 |
| 저장소 | `mujungi` (프로젝트 루트, 단일 저장소) |
| 브랜치 | `feat/doc-sync-code-update` (= origin/main `72d7034`, 앱 코드는 커밋과 같음) |
| 커밋 | `72d70344495b41768feb34208c1aaca88d65296d` (`72d7034`) |
| 인텐트 | `261015-doc-sync-code-update` |
| 스캔 종류 | 전체 재스캔(사람 결정) — 9개 산출물 통째 교체, 범위 블록은 이번 실행만으로 작성. 깊게 본 범위가 저장소 일부라 `kind: partial` |
| 사전 스냅숏 | paths `./`, store_generation `sha256:c32875ebd6d3dcda1968c6900052cc9d10a025c9704ac35325739223f96bdf99`, source_fingerprint `git:b59e231b61408caec80b386d72452aa1a19b3e81` |
| 수행 | 1단계 개발자 코드 스캔 → 2단계 아키텍트 합성 |
| 깊이 | Standard |

## 범위 설명

- 이전 저장소(인텐트 `261015-local-run-readiness`, 로컬 실행 · 통합 관점)의 산출물은 이어 쓰지 않았다. 이번 산출물은 모두 이번 스캔 결과로 다시 썼다. 그 결과 이전 저장소가 깊게 봤던 infra · scripts · tools 실행 경로 일부는 이번 블록에서 `shallow.paths`로 내려갔다.
- **깊게 분석**: Gradle 빌드 설정 전체 · 규약 플러그인, contract-model의 `ws` · `event` · `status`, 음성 공급자 연결 3파일 · 역할 지시문 · 연습 런타임 기본 빈 · WS 설정, 텍스트 AI · 결과 문장 기본 빈, 두 앱 설정 yml(일부 프로필), 두 앱 마이그레이션, 프런트 빌드 · 시험 · 린트 설정과 `src/app` 라우트, tools 패키지 설정, CI 워크플로, HTTP 계약 3종과 계약 README, Prettier 설정.
- **얕게**: 두 앱의 업무 로직 본문(패키지 · 클래스 목록 · REST 매핑 줄 · 크기만), contract-model의 나머지 패키지, test-support, 시험 디렉터리(개수 · 태그), 나머지 자원 파일, 커버리지 범위 목록, 프런트 `src`의 parent · device · shared와 시험, tools 하위 도구, infra, scripts, ci, deploy 워크플로, AsyncAPI · 스키마 · 규칙 문서, docs.
- 아키텍트 합성 단계에서 패키지별 파일 · 줄 수, 상태 enum 값, `ProviderRequestFactory` 상수, `PracticeRuntimeConfiguration` 46~51행, `WebSocketConfig` 등록 줄, 두 앱 yml 키 묶음, 마이그레이션 파일 목록, 프런트 라우트 목록을 다시 확인했다.
- 작업 트리: 앱 소스 변경 없음. 스캔 시작 시 CodeKB 9개 파일에 이전 인텐트(`261015-local-run-readiness`)가 게시한 커밋 전 수정이 있었고, 이번 게시가 그것을 통째로 교체한다.

## Scope of Analysis

```yaml
scope_version: 1
kind: partial
intent: 261015-doc-sync-code-update
fingerprint: 7df0a347e53499719b8e456892784b84cf15a208
analyzed:
  paths:
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
  components:
    - build-logic
    - code-style-config
    - ci-workflow
    - app-config
    - db-migrations
    - contracts.http-openapi
    - contract-model.ws
    - contract-model.event
    - contract-model.status
    - realtime.ws-gateway-config
    - realtime.voice-provider
    - realtime.dialog-instructions
    - realtime.practice-runtime-config
    - core.textai-config
    - core.result-text-config
    - frontend.build-config
    - frontend.app-routes
    - tools.package
shallow:
  paths:
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
