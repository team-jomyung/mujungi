# U13 제브 게이트 — 코드 생성 요약

> 작업 기록 `261017-u13-jev-guardrail` · Code Generation(3.5) · 브랜치 `unit/u13-jev-guardrail` · 2026-10-17
> 계획 `code-generation-plan.md`(17단계), 시험 안내 `unit-test-instructions.md`. 모든 합격은 **테스트 대역**(가짜 제브 · 가짜 공급자) 증거다(R-M11). 실제 제브는 한 번도 부르지 않았다(R-F1 · R-F15).

## 1. 만든 · 고친 파일

### 운영 코드(`backend/realtime-activity/src/main/`)
- 새 패키지 `kr.mujoong.realtime.guardrail`(19개): `GuardrailClient` · `GuardrailRequest` · `GuardrailVerdict` · `GuardrailMode` · `GuardrailProperties` · `GuardrailConfiguration` · `GuardrailStartupRules` · `GuardrailSupport` · `GuardrailRequestFactory` · `ApprovedVerbatimPort` · `JevGuardrailClient` · `JevHosts` · `JevModels` · `StubGuardrailClient` · `UnavailableGuardrailClient` · `GuardrailEventRecord` · `GuardrailEventRepository` · `GuardrailEventRecorder` · `GuardrailEventDeletionParticipant`.
- 새 협력 객체 `session/GuardrailCoordinator`. 고친 파일: `SessionExecutor`(호출 지점 8곳) · `SessionMailbox` · `SessionTimers` · `PracticeSessionExtension` · `InstructionTemplates` · `GateDecision` · `OutputGeneration` · `TranscriptWriter` · `LatencyRecorder` · `RealtimeMetrics` · `RealtimeRuntimeConfig` · `RealtimeStartupChecks` · `application.yml`.
- 마이그레이션 `V6__u13_jev_guardrail.sql`(표 `guardrail_event`).
- 계약: `contracts/ai-io.md` · `contracts/deviations.md`((U13-a) · (U13-b)) · `guardrail-request/result.schema.json`.

### 이번 묶음(5)
- 인프라: `infra/bin/render-env`(제브 키 · 주소 · 모델 demo 필수) · `infra/ci/image-checks.sh`(demo 더미 기동 + 키 누락 거부) · `infra/ci/test-host-tools.sh` · `infra/bin/infra-validate` · `infra/ci/gen-env.sh`(주석) · `infra/RUNBOOK.md`(단계 4 · 8.8) · `README.md`.
- 빌드: `mujoong.java-conventions.gradle.kts`(jev-live 제외 · 키 제거) · `backend/build.gradle.kts`(`verifyJevLiveIsolation`) · `backend/realtime-activity/build.gradle.kts`(`jevLiveTest`).
- 시험: `JevLiveProbe` · `JevLiveTest`(`@Tag("jev-live")`, 기본 빌드 밖) · `JevLiveTaskIsolationTest`(`@Tag("u13")`).
- 범위 파일 `backend/coverage-scope/u13.txt`(31개, `SessionExecutor` 제외 + 사유).
- 문서: `docs/units/u13/design.md` · `docs/units/README.md` 현황.

## 2. 핵심 구현 결정

1. **`OutputGate`는 그대로, 협력 객체가 비동기로 잇는다**(U13-D1). 결과는 일반 편지함 `GuardrailArrived`로 실행기 스레드에 돌린다.
2. **유효한 ALLOW만 송신.** 실패는 모두 `FAILED` 결과(자동 ALLOW 없음). 검사표 유효 조건 여덟(요청 id · 두 세대 · 연결 · 상태 · 보류 · 입력 묶음 열림 횟수 · 연습 발화).
3. **대기열 1칸**과 AI 행 보관 손잡이(U13-D4). 앞 검사가 ALLOW로 송신을 마칠 때만 꺼낸다. 앞 검사나 대기열이 있으면 승인 원문 구간도 새 발화로 검사한다.
4. **재지시 턴당 1회**(마지막 서버 지시 또는 `RETRY_FREE_TURN`), 폐기 훅은 `discard()` 한 지점. R-17: discard 마무리는 가지마다 다르므로 협력 객체는 어느 쪽이든 `IDLE`.
5. **연습 재생 일치도 `APPROVED_VERBATIM_SKIPPED`**(사유 `PRACTICE_SPEECH`), 레지스트리 출처 `APPROVED_SPEECH` · `SESSION_SCRIPT`. `GuardrailBlocked`는 `ExtensionEvent.Kind.GUARDRAIL_BLOCKED` 값 record.
6. **안전 타이머** `SessionTimers.Kind.GUARDRAIL_CHECK`(= `jev-budget` 두 배, 만기 시 TIMEOUT 막힘). 정상 어댑터는 `jev-budget`에서 이미 끝난다.
7. **`GuardrailVerdict.Failure.INVALID_REQUEST`** 추가. `RealtimeStartupChecks` ↔ `guardrail` 패키지 순환은 Luna 선례와 같다.
8. **계측**: `mujoong.guardrail.{calls, latency, blocked, timeout, perceived.wait}`(`docs/units/u13/design.md` 5절). 새 사건 코드 없음(U13-D2).
9. **환경**: `JEV_API_KEY`(비밀) · `JEV_BASE_URL` · `JEV_MODEL`(비밀 아님)을 Parameter Store → `render-env` → `realtime.env`로 전달하고 demo에서 모두 필수로 한다. 앱이 demo에서 키 없이 기동을 거부하므로 렌더 단계에서 먼저 거절한다. 새 비밀 이름은 없다(R-M9 목록 변경 없음). `.gitleaks.toml`은 바꾸지 않았다(가짜 값은 실행 때 만들고 저장소에 적지 않는다).
10. **`jevLiveTest`**: `lunaReachTest` 방식. 운영 어댑터 그대로, 합성 표본 5개, 보고서에 원문 없음.

## 3. 시험 커버리지 요약 (테스트 대역)

| 항목 | 결과 |
|---|---|
| `:realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker` | **1,641개**(클래스 186) 통과, 실패 0 · 건너뜀 0 |
| U13 태그 시험 | **169개**(클래스 19), 통과 |
| U13 범위 줄 커버리지(`-PcoverageScope=u13`) | **86.1%**(1,727 / 2,006줄, 31개 운영 파일), 하한 80% 이상 |
| 범위 내 낮은 파일 | `SessionMailbox` 67.1% · `PracticeSessionExtension` 68.6% · `InstructionTemplates` 69.2% · `RealtimeMetrics` 75.4% · `OutputGeneration` 77.4% — 앞 유닛 줄이 섞인 공통 파일 |
| `infra-validate`(`sg docker`) | 8건 통과 |
| `test-infra-validate.sh` | 46건 중 어긋남 0 |
| `test-host-tools.sh`(GNU date 9.4) | 42건 중 어긋남 0 (제브 누락 · http · `-latest` 거절 5건 새로 추가) |
| `jevLiveTest`(키 없음) | `SKIPPED_NO_KEY`, 호출 안 함 |
| 합성 대화 · 실제 API · 실제 기기 | 없음(Build and Test · 사람 몫) |

## 4. 계획 대비 편차

- **`image-checks.sh` ④는 이번 묶음에서 돌리지 않았다.** 이미지를 만들어야 해서 `bash -n` · shellcheck만 확인했고, 처음 돌리는 곳은 Build and Test다.
- **`.gitleaks.toml`을 바꾸지 않았다.** 계획이 '가짜 값을 쓰면 경로 + 줄 정규식 + 사유로 허용'이라고 했으나 가짜 키를 실행 때 `openssl rand`로 만들고 주소는 허용 호스트 형식 · 예약 도메인이라 허용 항목이 필요 없다(R-F9).
- `JEV_BASE_URL` · `JEV_MODEL`도 필수 파라미터로 받는다(계획 문구는 키만 demo 필수). 앱이 셋 다 없으면 기동을 거부하므로 같은 사실로 맞췄다.
- `docs/units/u13/report.md`는 Write 도구가 하위 에이전트의 보고서 파일 쓰기를 막아 이 호출에서 만들지 못했다. 내용은 호출자에게 보고 본문으로 넘겼고, 계획 Step 16 체크박스는 report.md가 쓰인 뒤에 표시한다.
- 계획 체크박스는 요청대로 Step 15 · 15b만 표시했다(앞 단계 1 ~ 12는 비워진 채 커밋으로 확인됨).

## 5. 남은 위험

- 실제 제브 응답 형식 · 지연 · 분류 품질은 모른다(`jevLiveTest`, 사람 몫, R-F15 확인 기록이 먼저).
- demo는 키 · 주소 · 모델이 없으면 기동을 거부한다 — 배포 전 Parameter Store 세 값을 먼저 넣는다.
- `SessionExecutor`는 U12가 이어서 고친다(병합 순서 U13 → U12).
- `team.md`의 '다음 번호 realtime V5'는 낡았다(지금 V6, 다음 V7).
