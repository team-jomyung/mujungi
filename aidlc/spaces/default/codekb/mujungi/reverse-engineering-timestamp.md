# 리버스 엔지니어링 시점 (Reverse Engineering Timestamp)

| 항목 | 값 |
|---|---|
| 수행 날짜 | 2026-10-16 |
| 저장소 | `mujungi` (프로젝트 루트, 단일 저장소) |
| 브랜치 | `unit/u11-judgment-turn` (= main `7e30ee8` + U11 지시서 커밋, 지시서 커밋은 `docs/units/u11/`만 바꿔 앱 소스는 main과 같음) |
| 커밋 | `9bcc706d36a60cafe9a6d867796aa5cb720a1025` (`9bcc706`), main `7e30ee8b2ad6d157a95d749ab774f3b7c0b9385d` |
| 인텐트 | `261016-u11-judgment-turn` (범위 refactor · 깊이 Standard · 브라운필드) |
| 스캔 종류 | 집중 스캔 + 기존 저장소 판정 **STALE** — 이번에 깊게 본 영역의 절만 갱신 · 확장하고 나머지 문장은 보존. 범위 블록은 이번 실행만 `analyzed`에 적고 이전 `analyzed`는 `shallow`로 내림. `kind: partial` |
| 직전 저장소 | 인텐트 `261015-doc-sync-code-update`, 커밋 `72d7034`, 2026-10-15 전체 재스캔(`kind: partial`, fingerprint `7df0a347e53499719b8e456892784b84cf15a208`) |
| 스냅숏 경로 집합 | `backend/realtime-activity/`, `backend/contract-model/src/main/java/kr/mujoong/contract/ws/`, `backend/contract-model/src/main/java/kr/mujoong/contract/event/`, `contracts/` |
| 수행 | 1단계 개발자 코드 스캔(`inception/reverse-engineering/developer-scan.md`) → 2단계 설계 담당 합성 |
| 깊이 | Standard |

## 범위 설명

- **왜 집중 스캔인가**: U11(판정 턴 기반)은 realtime 세션 실행기 · 확장점 · 게이트 · 공급자 송신 · Luna 호출부 · 판 리소스 · 판정 계약에 닿는다. 직전 저장소(72d7034)는 이 경로를 얕게만 봤고, 그 뒤 U8 · U9가 main에 병합돼 realtime에 `content` · `luna` 패키지가 생겼다(저장소 STALE).
- **깊게 분석(이번 실행)**: `SessionExecutor`의 공급자 사건 처리 · 확장점 신호 · 출력 능력 · 종료 의도 · 생각 중 · 게이트 · 공급자 송신 구간, 확장점 포트와 무동작 구현, 기록 캡처, 편지함 · 시계 종류, 게이트 4파일, 공급자 8파일, Luna 8파일, 판 리소스(`content/`), 대화 판정기 머리 · 지시문 상수, 연습 포트 · 레거시 런타임 머리 · 기본 빈, 대화 기록기 · 저장소, 삭제 참여자 계약, 실행기 배선 · 설정 루트, 판 리소스 · 설정 yml · realtime 마이그레이션, 실행기 시험 장치 · ArchUnit · 설정 대조 · 스키마 판 단언 시험, realtime 빌드 파일, contract-model `SessionRecordCaptured` · `View` · `StartSession`(머리), 계약 문서(ai-io 3 · 10.1 ~ 10.7절, 판정 스키마 2종 · 기록 v2 스키마, timing 13절, 편차 U8 · U9, 두 AsyncAPI의 해당 부분).
- **얕게(이번 실행에서 새로 훑음)**: realtime `gateway/` · `messaging/`(삭제 참여자 밖) · `log/AsyncRecorder.java`(머리 주석) · `startup/` · `observability/` · `recording/` · `health/` · `web/` · `internalhttp/`, 나머지 `luna/` 파일, `PracticeProtocol` · `ResponseEvaluator` · `JdbcPracticeRepository`(본문 안 읽음), `StartSessionHandler`(고정 · 놓기 호출만) · `ResultSnapshotBuilder`(한 줄만), 나머지 시험 164 파일(이름 · 태그만), `backend/coverage-scope/`, 루트 `backend/build.gradle.kts`(`verifyLunaLiveIsolation`), `backend/build-logic/`(태그 · 범위 줄만).
- **보존(직전 저장소 그대로, 미재확인)**: core-api · frontend · tools · infra · scripts · CI · HTTP 계약 관련 문장. 이 경로는 U8 · U9 병합 뒤 다시 보지 않았으므로 문서마다 「72d7034 기준」으로 표시했다.
- **설계 담당 합성 단계의 재확인**: realtime 패키지별 파일 · 줄 수(19 패키지 · 194 파일 · 28,141줄), 시험 파일 수(164)와 태그 집계, contract-model `ws` · `event` 파일 목록 · `WsMessageType` 값 · `View` 값, `contracts/` 파일 목록(스키마 71개)과 문서 줄 수 · 절 제목, `SessionExecutor` L1403 ~ L1430 · L1588 ~ L1606 · L1636 ~ L1692 · L1730 ~ L1745 · L2130 ~ L2146 · L2320 ~ L2362, `PracticeSessionExtension` L80 ~ L170, `LunaConfiguration` L40 ~ L60, `SessionTimers.Kind`, 판 번호 단언 줄, realtime `application.yml`의 judgment · luna 키, `realtime-activity/build.gradle.kts` 의존 · `processResources`, `ai-judgment-output.schema.json` 필드.
- **개발자 스캔과의 차이**: 공급자 수신 type은 개발자 보고의 "10종"이 아니라 9종이다(같은 목록). realtime 시험 파일은 165가 아니라 164로 셌다.
- **작업 트리**: 앱 소스 변경 없음. 미추적 `.aidlc-unit7-diagnostic/` · 문서 Zone.Identifier 파일은 스캔 대상이 아니다.
- **산출물 변경 요약**: 9개 모두 머리에 혼합 기준을 적고, realtime · 계약 절을 갱신했다. 새 절: architecture 「U11 판정 턴 접점」 · 상호작용 4) · 5) · 「U11 관점 위험」, code-structure 「realtime 세션 실행기 내부 지도」, api-documentation 6A · 10 · 11절, dependencies 6절, code-quality-assessment 6절(U11-R1 ~ U11-R13). component-inventory는 realtime 구성 요소를 나누고(`realtime.luna` · `realtime.content` · `realtime.live-resources` · `realtime.test-harness` · `contracts.ai-events-rules` 새로 만듦, `realtime.dialog-instructions`를 `realtime.dialog`로 넓힘) 건강 표시를 더했다.

## Scope of Analysis

```yaml
scope_version: 1
kind: partial
intent: 261016-u11-judgment-turn
fingerprint: 0d9e9bf168ade0d2a08f86f48e66fa2a9aaea6dd
analyzed:
  paths:
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/PracticeSessionExtension.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/NoOpPracticeSessionExtension.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/RecordCaptureService.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionMailbox.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionTimers.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/OutputGate.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/GateDecision.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/ApprovedPlayback.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/SegmentBuffer.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderPort.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderClient.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderRequestFactory.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/PracticeInstruction.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/PracticeSpeech.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderResponseId.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderConnector.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/InputRelay.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/RealtimeLunaClient.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/LunaConfiguration.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/LunaKeyPresentCondition.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/LunaPurpose.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/LunaRequest.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/LunaResult.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/LunaFailureReason.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/LunaProperties.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/content/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/StopIntentJudge.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/JoinTracker.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/DialogInstructions.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/EvaluationProvider.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/CueProvider.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticePorts.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/RuntimePracticeExtension.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeRuntimeConfiguration.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/log/TranscriptWriter.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/log/TranscriptRepository.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/messaging/RealtimeDeletionParticipant.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/runtime/RealtimeRuntimeConfig.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/config/RealtimeProperties.java
    - backend/realtime-activity/src/main/resources/judgment/
    - backend/realtime-activity/src/main/resources/guardrail/
    - backend/realtime-activity/src/main/resources/application.yml
    - backend/realtime-activity/src/main/resources/db/migration/realtime/
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/testing/SessionHarness.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/testing/ManualLauncher.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/testing/FakeProviderPort.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/arch/RealtimeArchTest.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/arch/LunaArchTest.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/arch/TimeLiteralScanTest.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/config/ConfigKeyContractTest.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/db/RealtimeSchemaIT.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/practice/PracticeMigrationRegistrationTest.java
    - backend/realtime-activity/build.gradle.kts
    - backend/contract-model/src/main/java/kr/mujoong/contract/event/payload/SessionRecordCaptured.java
    - backend/contract-model/src/main/java/kr/mujoong/contract/ws/message/View.java
    - backend/contract-model/src/main/java/kr/mujoong/contract/ws/message/StartSession.java
    - contracts/ai-io.md
    - contracts/schemas/ai-judgment-request.schema.json
    - contracts/schemas/ai-judgment-output.schema.json
    - contracts/schemas/practice-record-v2.schema.json
    - contracts/timing.md
    - contracts/deviations.md
    - contracts/events.asyncapi.yaml
    - contracts/device-ws.asyncapi.yaml
  components:
    - realtime.session
    - realtime.gate
    - realtime.voice-provider
    - realtime.luna
    - realtime.content
    - realtime.dialog
    - realtime.practice-runtime-config
    - realtime.practice
    - realtime.log-recording
    - realtime.messaging
    - realtime.platform
    - realtime.live-resources
    - realtime.test-harness
    - contract-model.ws
    - contract-model.event
    - contracts.ai-events-rules
shallow:
  paths:
    - backend/settings.gradle.kts
    - backend/build.gradle.kts
    - backend/gradle/libs.versions.toml
    - backend/gradle.properties
    - backend/gradle/wrapper/gradle-wrapper.properties
    - backend/build-logic/
    - backend/contract-model/build.gradle.kts
    - backend/core-api/build.gradle.kts
    - backend/test-support/build.gradle.kts
    - backend/contract-model/src/main/java/kr/mujoong/contract/ws/
    - backend/contract-model/src/main/java/kr/mujoong/contract/event/
    - backend/contract-model/src/main/java/kr/mujoong/contract/status/
    - backend/contract-model/src/main/java/kr/mujoong/contract/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderHosts.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/WebSocketConfig.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/messaging/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/log/AsyncRecorder.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/startup/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/observability/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/recording/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/health/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/web/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/internalhttp/
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/ResponseEvaluator.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/JdbcPracticeRepository.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/StartSessionHandler.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/ResultSnapshotBuilder.java
    - backend/realtime-activity/src/main/java/
    - backend/realtime-activity/src/main/resources/application-prod.yml
    - backend/realtime-activity/src/main/resources/application-demo.yml
    - backend/realtime-activity/src/main/resources/
    - backend/realtime-activity/src/test/
    - backend/core-api/src/main/java/kr/mujoong/core/textai/TextAiConfiguration.java
    - backend/core-api/src/main/java/kr/mujoong/core/result/ResultTextConfiguration.java
    - backend/core-api/src/main/java/
    - backend/core-api/src/main/resources/application.yml
    - backend/core-api/src/main/resources/db/migration/core/
    - backend/core-api/src/main/resources/
    - backend/core-api/src/test/
    - backend/contract-model/src/test/
    - backend/test-support/src/main/
    - backend/test-support/src/test/
    - backend/coverage-scope/
    - frontend/package.json
    - frontend/next.config.ts
    - frontend/vitest.config.ts
    - frontend/eslint.config.mjs
    - frontend/src/app/
    - frontend/src/
    - frontend/tests/
    - frontend/coverage-scope/
    - tools/package.json
    - tools/
    - infra/
    - scripts/
    - ci/
    - .github/workflows/ci.yml
    - .github/workflows/deploy.yml
    - .prettierrc.json
    - contracts/README.md
    - contracts/parent-api.yaml
    - contracts/device-api.yaml
    - contracts/internal-api.yaml
    - contracts/schemas/
    - contracts/errors.md
    - contracts/ports.md
    - docs/
```
