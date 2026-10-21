# Reverse Engineering Timestamp

## Run Record

- Date: 2026-10-20
- Commit: 2e3d7b0e0796844166fb54b21220512ff20ce9e1
- Branch: feat/service-guardrail-replacement
- Intent: 261020-service-guardrail-replac
- Method: Focused scan. 기존 STALE store의 관련 없는 본문은 보존하고 이번 지시문 교체 분석을 추가했다.
- Evidence: 정적 소스와 사용자 ZIP 내부 문서만 읽었다. 앱 변경·시험 실행·유료 AI 호출·수동 스택 조작 없음.

### 범위 해석

아래 개별 파일만 이번에 깊게 읽었다. SessionExecutor와 contracts/ai-io.md는 관련 구역만 읽어 shallow에 둔다. ZIP은 애플리케이션 소스 지문에 포함하지 않으며 developer-scan.md에 원본 해시와 검증 결과를 별도로 기록했다. 아래 이전 실행의 수치·줄 번호·환경 설명은 역사적 기록이며 현행 인증이 아니다. 기존 analyzed.paths는 STALE이므로 모두 shallow로 내렸다.

## 이전 실행 기록 — 보존


## Run Record

- Date: 2026-10-20
- Commit: 2c431bb00b5580032ca5d0bf73d8bd591fb03749
- Branch: ops/synthetic-voice-e2e
- Intent: 261020-synthetic-voice-e2e
- Method: Focused scan, Standard. STALE store의 기존 9개 문장을 보존하고 이번 음성 경로 분석을 덧붙였다.
- Evidence: 정적 소스 조사만. 앱 변경·TTS 생성·실호출·브라우저 음성 시험 없음.

### 커버리지 해석

아래 analyzed.paths는 이번에 집중해 이해한 개별 파일만 가리킨다. SessionExecutor는 전사·출력·게이트 구역, GptLiveProbe는 직접 연결·입력·설정 구역에 한정되며 파일의 무관한 모든 동작을 인증하지 않는다. 이전 store가 ./ 전체를 분석했다고 적었지만 STALE이고, 그 실행 기록 자체에도 core-api·계약 등의 깊이 한계가 있었으므로 이번 deep coverage로 승계하지 않는다. 이전 ./와 shallow 목록은 shallow로 보존했다. 제외한 빌드·의존성·캐시·프레임워크 폴더는 shallow에 새로 넣지 않는다.

## Previous Run Record — 역사적 기록

다음은 기존 문장을 그대로 보존한 기록이다. 옛 kind:full 설명은 당시 주장이고 현재 검증 범위가 아니다.

## 이번 실행 기록 (261018-structure-refactor)

| 항목 | 값 |
|---|---|
| 날짜 | 2026-10-18 |
| 커밋 | 570a625 (전체 해시 570a625d261e0edb4984f7466b865707faa47e83, 원격 `main`과 같음) |
| 브랜치 | claude/refactor-structure |
| 인텐트 | 261018-structure-refactor |
| 범위 · 깊이 | scope refactor, 깊이 Minimal, 브라운필드, 단일 저장소(프로젝트 루트) |
| 스캔 방식 | 전체 재스캔(Full rescan) — 9개 산출물을 이번 실행 기준으로 통째로 새로 썼다(부분 병합 아님) |
| 방법 | 개발자 읽기 전용 스캔(handoff `inception/reverse-engineering/developer-scan.md`) + 아키텍트 합성. 앱 · 시험 · 빌드 · 외부 공급자는 실행하지 않았다 |

- **스캔 깊이에 대한 정직성 노트**: 이번 스캔은 구조 · 이름 정리 의도에 맞춰 이름 · 경로 문자열 참조와 안전망을 집중해 읽었다. 핸드오프의 Scan Coverage가 "깊게 분석"과 "훑기만"을 나눠 적었고, `core-api` 내부, `contracts` 본문, `docs` 본문, `infra` 스크립트 내부, 엔드포인트 수는 깊게 읽지 않았다(`shallow.paths`). 그럼에도 스냅샷과 요청 범위가 저장소 전체(`./`)이고 이번 실행이 전체 재스캔이므로 요청된 breadth에 따라 `kind: full`로 기록한다.
- **증거 구분(R-M11)**: 이번 실행의 증거는 정적 코드 읽기와 `wc` · `grep` 개수뿐이다. 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공은 어느 것도 이번 실행의 증거가 아니다.
- **미확인 사항**: `code-quality-assessment.md` 마지막 절 참조(순서 고정 시험 유무, `Live12` 시험 위치, 필수 검사 이름).


## Scope of Analysis

```yaml
scope_version: 1
kind: partial
intent: 261020-service-guardrail-replac
fingerprint: c5577822c642b4b65e6840698ad8a961f2d42bc3
analyzed:
  paths:
    - backend/realtime-activity/build.gradle.kts
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/DialogInstructions.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/InstructionTemplates.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/StopConfirmFlow.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderRequestFactory.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderPort.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/ChildContext.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/JoinFlow.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/ProviderSendQueue.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/GuardrailCoordinator.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/OutputGate.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/content/LiveContentResources.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailRequestFactory.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailRequest.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailVerdict.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/GuardrailProperties.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/JevQuestion.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/JevGuardrailClient.java
    - backend/realtime-activity/src/main/resources/guardrail/policy-v2.json
    - backend/realtime-activity/src/main/resources/application.yml
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/dialog/DialogInstructionsTest.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/ProviderRequestFactoryWireTest.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/guardrail/GuardrailPolicyV2Test.java
  components:
    - backend/realtime-activity
shallow:
  paths:
    - backend/realtime-activity/build.gradle.kts
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/JoinFlow.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/JoinTracker.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/OutputGate.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/SegmentBuffer.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderClient.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/log/LatencyRecorder.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionViewQuery.java
    - backend/realtime-activity/src/test/java/kr/mujoong/realtime/voice/GptLiveProbe.java
    - backend/core-api/src/main/java/kr/mujoong/core/activity/web/ActivityController.java
    - backend/contract-model/src/main/java/kr/mujoong/contract/audio/AudioFrameCodec.java
    - backend/contract-model/src/main/java/kr/mujoong/contract/ws/message/PlaybackMark.java
    - frontend/src/device/audio/capture.ts
    - frontend/src/device/audio/player.ts
    - frontend/src/device/audio/audioContext.ts
    - frontend/src/device/audio/resample.ts
    - frontend/src/device/ws/deviceSession.ts
    - frontend/src/device/runtime/deviceRuntime.ts
    - frontend/src/device/mic/micPermission.ts
    - frontend/src/device/api/deviceApi.ts
    - frontend/src/parent/common/api/live.ts
    - frontend/src/parent/common/hooks/useLivePolling.ts
    - frontend/src/parent/features/live/useLiveScreen.ts
    - frontend/src/parent/common/components/TranscriptList.tsx
    - frontend/src/parent/common/components/transcriptTimeline.ts
    - frontend/src/parent/features/free/api.ts
    - frontend/src/parent/features/free/useFreeTalkScreen.ts
    - frontend/src/shared/protocol/frameCodec.ts
    - frontend/src/app/device/page.tsx
    - frontend/src/app/parent/sessions/[sessionId]/page.tsx
    - tools/device-simulator/simulator.mjs
    - tools/seed/gen-session-wav.mjs
    - tools/seed/gen-approved-wav.mjs
    - infra/compose.live.yml
    - docs/live-run.md
    - ./
    - backend/core-api/
    - backend/contract-model/
    - backend/test-support/
    - infra/
    - ci/
    - scripts/verify/
    - scripts/live/
    - tools/
    - contracts/
    - docs/units/
    - .github/workflows/deploy.yml
    - frontend/src/app/
    - frontend/src/device/
    - backend/realtime-activity/src/main/resources/application.yml
    - tools/skeleton-e2e/steps.mjs
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/content/LiveContentRegistry.java
    - backend/realtime-activity/src/main/java/kr/mujoong/realtime/practiceflow/
    - contracts/ai-io.md
    - contracts/timing.md
    - contracts/schemas/
    - docs/
```

