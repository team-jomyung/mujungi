> 현재 범위의 정본은 바로 아래 "제브 어댑터 선 형식 집중 갱신" 절이다(`backend/realtime-activity` · `contracts` · `docs/units/u13` 한정, 2026-10-18, HEAD `421e1ad`). 그 뒤 구현 전 U13 절 · U14 절 · U15 절 · 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## 제브 어댑터 선 형식 현재 검증 구성 요소 (261017-jev-adapter-format)

아래 제목만 이번 집중 스캔의 deep 구성 요소다. 이전 U13(구현 전) · U14 · U15 deep 표시는 이력이며 timestamp의 shallow로 강등했다. 패키지 `kr.mujoong.realtime.guardrail`은 이제 존재한다(19개 파일, 약 2,500줄 안팎). 설계 결정은 하지 않고 사실만 적는다.

### u13.jev-adapter-wire-format
`guardrail/JevGuardrailClient.java`(366줄), `JevHosts` · `JevModels`. 제브 선 형식을 아는 코드 두 곳 중 하나다: 요청은 `requestBody`(닫힌 키 6개 `model · systemPrompt · mode · candidateText · recentContext · policyVersion`), 응답은 `convert`(최상위 `decision · categories · policyFlags · reasonCode · reviewNeeded · policyVersion`). 경로는 상수 `CHECK_PATH = "/v1/guardrail/check"`(형식 가정)이며 코드에 `/v1/systemone`은 없다. 호출 한 번 · 재시도 없음(BR2.1), 시한 `min(마감까지 남은 시간, jev-budget)`, JDK `HttpClient`(리다이렉트 안 따름 · 프록시 없음), 응답 본문 상한 64KiB, 호스트는 생성 때와 보내기 직전 두 번 확인(`ALLOWED_HOSTS = {api.typesafe.ai}`), 키는 호출마다 `Supplier<String>`로 읽어 `Authorization: Bearer` 헤더에만 쓴다.

### u13.guardrail-request-factory
`guardrail/GuardrailRequestFactory`(순수 함수 115줄) · `GuardrailRequest`(레코드, `source` 상수 `GPT_LIVE_GENERATED`, `Mode`, `Speaker`, `ContextTurn`). **계약 내부 형식**을 만들고 공급자 형식은 만들지 않는다: 아이 이름 치환(`"아이"`), 후보 1 ~ 4000자, 맥락 마지막 3턴 · 한 줄 2000자, 정책 판 1 ~ 80자. 질문 · 선택지 · 시스템 지시를 모른다. 호출 지점은 `GuardrailCoordinator.start` 한 곳(`new GuardrailRequestFactory()` 직접 생성).

### u13.verdict-conversion-and-consumers
`guardrail/GuardrailVerdict`(`Decision{ALLOW,BLOCK,REVIEW,FAILED}` · `Failure` 6종) · `GuardrailEventRecord` · `GuardrailEventRecorder` · `GuardrailEventRepository` · `GuardrailEventDeletionParticipant`와 소비자 `session/GuardrailCoordinator`(749줄) · `PracticeSessionExtension.GuardrailBlockedEvent` · `observability/RealtimeMetrics`의 `mujoong.guardrail.*`. 소비자가 실제로 읽는 값은 `decision` · `failure` · `reasonCode`(기록 · 연습 알림) · `latencyMs`다. `categories` · `policyFlags`는 main 코드에서 읽는 곳이 없고 `guardrail_event`에 칸도 없다. 기록 표는 `realtime.guardrail_event`(V6).

### u13.policy-resource-and-contract-schemas
`src/main/resources/guardrail/policy-v1.json`(판 `jev-guardrail-v1`: `version` · `source` · `systemPrompt` · `categories` 7개 · `modes` 3개), `content/LiveContentResources`의 `guardrail` 적재 · 검증(`GUARDRAIL_CATEGORY_COUNT`), `contracts/schemas/guardrail-request.schema.json` · `guardrail-result.schema.json`, `contracts/ai-io.md` 10.1 ~ 10.5 · 10.11, `contracts/deviations.md`의 `(U13-a)` `(U13-b)`, `contracts/timing.md` 13절 제브 행. 분류 체계가 7개(리소스 · 계약 스키마 · 어댑터 `CATEGORIES`)와 11개(AI 동작 명세 §10.2, `PASS` 포함) 두 벌이다.

### u13.guardrail-config-and-live-probe
`guardrail/GuardrailConfiguration` · `GuardrailProperties`(`mode` · `baseUrl` · `model` 세 칸) · `GuardrailMode` · `GuardrailStartupRules` · `GuardrailSupport`, `application.yml`의 `mujoong.guardrail.jev` · `mujoong.timing.guardrail`, 시험 쪽 `JevLiveProbe` · `JevLiveTest`(태그 `jev-live`) · `JevLiveTaskIsolationTest`와 `backend/build.gradle.kts`의 `jevLiveTest` 분리(얕게). 문턱 설정 키는 아직 없다.

### u13.adapter-bound-tests
`JevGuardrailClientTest`(445줄, 시나리오 11개 중 1 · 2 · 3 · 7 · 8번이 가정 형식에 강하게 묶임), `GuardrailRequestFactoryTest`(내부 형식만 봄), `GuardrailConfigurationTest`, `config/ConfigKeyContractTest`의 제브 대목, `arch/TimeLiteralScanTest`의 어댑터 `.orTimeout` 줄 허용 항목, `GuardrailPackageArchTest`, `testing/FakeGuardrailClient`, `content/LiveContentRegistryTest` · `startup/U8RealtimeStartupChecksTest`의 7개 범주 단언, `contract-model`의 `U8ContractSchemasTest`(얕게). 실호출 증거는 없다.

---

## 구현 전 U13 구성 요소 (2026-10-17, 이력 — 구현 전 코드 기준, 재검증하지 않음)

아래 제목은 `analyzed.components`가 아니다. 구현으로 `kr.mujoong.realtime.guardrail` 패키지가 생겼으므로 "새 패키지는 아직 없다" 같은 문장은 더 이상 사실이 아니다.

### (구현 전) u13.output-gate-sealing
`gate/`(`OutputGate` · `GateDecision` · `SegmentBuffer` · `OutputGeneration` · `ApprovedPlayback` · `SeedDictionary`)와 `SessionExecutor`의 봉인 · 검사 · 송신 · 폐기 구간(`sealAndGate` ~2782, `send` ~2831, `discard` ~2866). 동기 · 순수 판정은 `OutputGate`, 흐름은 실행기에 있다. 제브 삽입 지점은 `inspect` 통과 + 연습 원문 일치 뒤, `recheck` 앞이다. 세대 번호가 둘(기기 프레임 `outputGeneration`, 공급자 출력 `outputGen`)이다.

### (구현 전) u13.session-async-handoff
`SessionExecutor` 편지함 규약, `JudgmentCoordinator`(`Env` · `postResult`) · `JudgmentSupport` · `SessionMailbox`. 완료 콜백은 `mailbox.putSafety`로 실행기 스레드에 돌려놓는다. `outputHeld()`(WAITING_CHILD · 종료 확인 · 판정 · 연습 보류 4조건)와 `thinking()`/`listeningAgain()`의 THINKING 유지 조건이 제브 대기와 상호작용한다.

### (구현 전) u13.provider-port-precedent
`judgment/`(`JudgeClient` · `UnavailableJudgeClient` · `LunaJudgeClient` · `JudgmentConfiguration`)와 `luna/`(`LunaConfiguration` · `LunaKeyPresentCondition` · `LunaTransport` · `LunaHosts`). 제브 포트 · 어댑터가 따를 모양.

### (구현 전) u13.approved-text-bypass
`content/`(`LiveContentRegistry.matchApproved` · `LiveContentResources`)와 `voice/PracticeSpeech`(`matches`). 일치 정규화는 ASCII 공백 제거뿐이다(시드 사전의 NFKC와 다름). 구현 전에는 운영 호출자 0개, `Services`에 없음이었다(구현 뒤 `ApprovedVerbatimPort`가 생겼다 — 이번 스캔은 이름만 확인).

### (구현 전) u13.guardrail-persistence
`judgment/JudgmentLiveRecorder` · `JudgmentLiveRepository` · `JudgmentLiveDeletionParticipant`, `log/TranscriptWriter` · `TranscriptRepository` · `AsyncRecorder`, `db/migration/realtime/V5`. 새 표 `guardrail_event`(realtime V6)의 선례와 전사 승인(`approved_for_display`) 경로. 삭제 참여자 순서 100 · 150 · (180) · 200 · 900.

### (구현 전) u13.startup-observability
`startup/`(`RealtimeStartupChecks` · `ProfileGuardEnvironmentPostProcessor` · `SchemaVersionCheck`), `runtime/RealtimeRuntimeConfig`, `observability/`(`RealtimeMetrics` · `EventLogger`), `config/RealtimeProperties`(guardrail 부분), `application*.yml`, `guardrail/policy-v1.json`.

### (구현 전) u13.test-and-contract-surface
시험 구조(`arch` · `testing` · `session/JudgmentExecutorSupport` · `db` · `observability`), `backend/test-support`(`MutableClock` · `ManualTimerDriver` · `LunaResponsesStub`), `backend/contract-model`(`EventLog.Component` · `AttrKey` 위치), `contracts/`(`ai-io.md` 10절 · `timing.md` 13절 · `deviations.md` · `guardrail-*.schema.json`), `backend/coverage-scope/u11.txt` 선례.

---

> 현재 범위의 정본(U14 갱신절, 보존 이력)은 바로 아래다. 그 뒤 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

> 최신 정본: 아래 U10 갱신절(2026-10-17, HEAD eb34c68bf24e4520881b7ffebe1070564554dfc9). 이후 U14/U15/전체 분석 본문은 역사 자료이며, 이번에 검증한 범위는 timestamp의 analyzed 목록만이다.

## U10 현재 검증 구성 요소

아래 제목들이 이번 analyzed.components 정본이다. 뒤의 과거 목록은 보존한 설명이며 현재 deep 검증을 뜻하지 않는다.

### u10.scenario-orchestration
ScenarioService, ScenarioRepository, ScenarioContentPolicy, Scenario, ScenarioConfiguration, ScenarioInternalController. 요청 선점·동일응답 재사용·중복 내용 방지·권한 재확인·발급을 소유한다.

### u10.preparation-compatibility
PracticeStartService, FrozenGoal, PreparedPracticePackageMapper, PracticeSessionContextPort. 기존 행동키 가정과 시작 패키지 첫 대본 전달이 새 목표·선택필드 연결 지점이다.

### u10.luna-boundary
LunaTextAiProvider, TextAiGateway, ScenarioPrompt, GeneratedScenario, ForbiddenExpressionPolicy, CoreLunaClient, LunaRequest, LunaPurpose, LunaCallBudget, LunaInputSanitizer. 이중 재시도·전체 마감·원문 비노출을 보존한다.

### u10.saved-goal-context
SavedGoalReader, PinnedSavedGoalReader, FourElements, practicegoal.PracticeGoalRepository. U14의 실제 CANDIDATE/LEGACY_BEHAVIOR 읽기 경계이며 ScenarioService에는 아직 미연결이다.

### u10.graph-context
ChildKnowledgeService, VariantPlanner, KnowledgeSeed, KnowledgeGraphLookup. 아동별 service와 공개 카탈로그 포트의 차이, 빈값 fallback과 조회 예산을 소유한다.

### u10.contract-persistence
Core V4/V9, ai-scenario-output/review, practice-scenario, internal-practice-scenario-request, core-api/build.gradle.kts. V10·선택응답·실행자원 스키마 단일정본 연결 지점이다.

---

> 현재 범위의 정본은 바로 아래 U14 갱신절이다. 그 뒤 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## U14 현재 검증 구성 요소

아래 제목만 이번 deep 구성 요소다. 과거 deep 표시는 이력이며 timestamp의 shallow로 강등했다.

### u14.practice-goal
CandidateRegistry, PracticeGoalRepository, PracticeDeletionParticipant. 후보/선택 소유·행동 버전 고정·근거 저장·보호자 잠금. PracticeGoal 경계 재정리와 SavedGoalReader 추가 필요.

### u14.result-pipeline
SessionResultHandler, ResultAiSchema, ResultData, JobRunner, ResultQueryService, SceneJudgmentRepository, SceneChipMapper, CandidateScreen, ResultDeletionParticipant. 두 장면·행동 목록 가정과 pending 후보 복구·잠금 경계를 소유한다.

### u14.report-projection
ReportAssembler. 저장된 sceneChip 문구를 재사용해 AFTER_REASK의 별도 의미를 보존하지 못한다.

### u14.knowledge-integration
ChildKnowledgeService, SeedGenerationService, StructureSignature. U15의 실제 서비스이며 비활성/미동의/삭제·조회 실패와 생성 NONE 경계를 보존한다.

### u14.contract-compatibility
엄격 후보/재판정/practice-v2/결과 스키마, frontend 결과 파서, V4/V5 기존 제약, migration 승인 목록. DB·API·파서 변경을 한 묶음으로 검증해야 한다.

---

# 구성 요소 목록 (Component Inventory)

> 최신 확인: 2026-10-16, HEAD 7e30ee8, U15 집중 분석. 아래 최신 절이 U15 관련 과거 설명보다 우선한다. 이전 분석 본문은 이력으로 보존하며 이번 범위 밖의 검증 주장은 shallow로 낮췄다.

## U15 현재 검증 구성 요소

아래 구성 요소만 이번 집중 분석의 deep 구성 요소로 기록한다. 이후 과거 목록의 [깊게] 표시는 당시 분석 수준이며 이번 검증을 뜻하지 않는다.

### u15.knowledge-boundary
PragmaticsKnowledge, KnowledgeGraphLookup, JDBC 정의 저장소. PostgreSQL이 권위 원장, 공개 그래프 조회는 선택적이다. 아동별 구현은 없음.

### u15.luna-boundary
CoreLunaClient, LunaPurpose, LunaResult, LunaInputSanitizer, LunaConfiguration. 키 없는 환경에서 빈이 없으며 SEED_GENERATION은 REQUEST_BOUNDED. 기술/스키마 재시도와 추가 내용 재생성 예산을 공유해야 한다.

### u15.deletion-boundary
DeletionCommander, DeletionParticipant, ResultDeletionParticipant, GuardianWriteLock. 순서·멱등성·예외 재시도·쓰기 경쟁 경계.

### u15.seed-contract
두 필드의 JSON 스키마와 core-api 자원 복사 경로. 구조 서명 확장은 미결정.

### u15.build-verification
core-api/contract-model 빌드, 버전 카탈로그, Java 규약, a-checks. U15 태그/커버리지 파일 추가 필요.

## 이전 분석 기록 — 2026-10-15, 현재 범위 밖은 재검증하지 않음

> 기준: 커밋 `72d7034`, 2026-10-15 전체 재스캔. 아래 H3 제목이 구성 요소 이름이며, [reverse-engineering-timestamp.md](reverse-engineering-timestamp.md)의 `analyzed.components`는 이 제목과 글자 그대로 같다.
> 표시: **[깊게]** = 이번 스캔에서 파일을 읽어 확인, **[얕게]** = 목록 · 매핑 · 크기만 확인.

## 빌드 · 공통

### build-logic
- **[깊게]** `backend/build-logic/`, `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/gradle.properties`, wrapper, 네 모듈 `build.gradle.kts`.
- 책임: 규약 플러그인 `mujoong.java-conventions` — Java 21 툴체인, Spring Boot BOM, 보안 판 고정(Jackson 2/3 · Tomcat), 시험 태그, JaCoCo 줄 80% 하한, `-PcoverageScope=<유닛>` 범위 측정.
- 의존: 모든 백엔드 모듈이 적용.

### code-style-config
- **[깊게]** `.prettierrc.json`(printWidth 100), `frontend/eslint.config.mjs`.
- 책임: 프런트 린트 규칙(경계 · 문구 · getUserMedia · 서비스 워커 제한). Prettier 설정은 있으나 CI 검사 없음(TD-5).

### ci-workflow
- **[깊게]** `.github/workflows/ci.yml`. **[얕게]** `.github/workflows/deploy.yml`, `ci/`.
- 책임: 변경 감지 · 백엔드 check · 유닛 범위 커버리지 · 의존 취약점 · 프런트 · tools · 비밀 · infra 불변식 · 이미지 · 스켈레톤. 세부는 [code-quality-assessment.md](code-quality-assessment.md).

### app-config
- **[깊게]** `backend/core-api/src/main/resources/application.yml`, `backend/realtime-activity/src/main/resources/application{,-prod,-demo}.yml`. **[얕게]** 나머지 프로필 yml.
- 책임: 포트(8080 · 8081 · 관리 9090), DB 연결(스키마별 역할), Flyway 끔, `mujoong.timing.*` 시간 값, Outbox/Inbox 재시도, 공급자 URL(demo · prod).

### db-migrations
- **[깊게]** `backend/core-api/src/main/resources/db/migration/core/`(V1~V7 + `afterMigrate.sql`), `backend/realtime-activity/src/main/resources/db/migration/realtime/`(V1~V4 + `afterMigrate.sql`).
- 책임: 스키마 정본. 실행은 `infra/migrate` 이미지. 다음 번호 core V8 · realtime V5(TD-7). (2026-10-18 갱신: realtime V5는 U11, V6은 U13이 썼다. 다음 realtime 번호는 V7 이후이며 `team.md`의 문장은 낡았다.)

## 계약

### contracts.http-openapi
- **[깊게]** `contracts/README.md`, `contracts/parent-api.yaml`(34경로), `contracts/device-api.yaml`(4경로), `contracts/internal-api.yaml`(7경로).
- **[얕게]** `contracts/schemas/`, `*.asyncapi.yaml`, `deviations.md`, `ai-io.md`, `timing.md`, `errors.md`, `ports.md`.
- 책임: HTTP 계약 정본, 소유 · 깨는 변경 규칙.

### contract-model.ws
- **[깊게]** `backend/contract-model/src/main/java/kr/mujoong/contract/ws/`.
- 책임: 기기 WS 메시지 17종 · 봉투 · 닫기 코드. 사용처: realtime gateway, test-support 기기 시뮬레이터.

### contract-model.event
- **[깊게]** `backend/contract-model/src/main/java/kr/mujoong/contract/event/`.
- 책임: 영속 이벤트 6종 · 봉투 · 집합 종류 · 방향.

### contract-model.status
- **[깊게]** `backend/contract-model/src/main/java/kr/mujoong/contract/status/`.
- 책임: `SessionStatus`(12) · `EndReason` · `EndRequestReason` · `FailureCode` · `ActivityType` · `ConnectionState` · `RegistrationStatus`, 해석 불가 값은 `UNRECOGNIZED`.

### contract-model.support
- **[얕게]** `audio` · `error` · `json` · `log` · `profile` · `secret` · `time` · `trace`.
- 책임: 음성 형식 · 오류 봉투 · JSON 매퍼 · 구조화 로그 · 시계/타이머.

## realtime-activity

### realtime.ws-gateway-config
- **[깊게]** `realtime/gateway/WebSocketConfig.java`.
- 책임: `/ws/device` 등록, 허용 Origin, 핸드셰이크 인터셉터 연결.

### realtime.gateway
- **[얕게]** `realtime/gateway/`(21 파일): `DeviceWebSocketHandler` · `DeviceHandshakeInterceptor` · `VerifyTokenClient` · `HeartbeatHandler` · `ConnectionHandover` · `DisconnectChecker` · `StatusReporter` · `DeviceReleasedHandler` 등.
- 의존: Core 내부 HTTP(토큰 검증 · 연결 보고), session.

### realtime.session
- **[얕게]** `realtime/session/`(34 파일): `SessionExecutor`(3,388줄) · `TransitionTable` · `SessionTimers` · `StartSessionHandler` · `EndRequestHandler` · `RecordCaptureService` · `ResultSnapshotBuilder` · `SessionInternalController` · `PracticeSessionExtension` 등.
- 책임: 세션 상태 기계 · 타이머 · 출력 조율 · 종료 정리 · 기록 캡처 · 연습 확장점.

### realtime.voice-provider
- **[깊게]** `ProviderHosts.java` · `ProviderRequestFactory.java` · `ProviderClient.java`. **[얕게]** 나머지 `voice/`(`ProviderConnector` · `InputRelay` · `AudioTap` · `PracticeInstruction` · `PracticeSpeech`).
- 책임: 음성 공급자 WS 연결 · 송신 6종 · 수신 9종 · 허용 호스트 고정. 위험 TD-2.

### realtime.dialog-instructions
- **[깊게]** `realtime/dialog/DialogInstructions.java`(역할 지시문 · 첫 질문 문장, 42~75행).
- **[얕게]** `StopIntentJudge`(565줄) · `SilenceClock` · `StopConfirmFlow` · `JoinTracker`.

### realtime.gate
- **[얕게]** `realtime/gate/`: `OutputGate` · `SegmentBuffer` · `SeedDictionary` · `ApprovedPlayback` · `ClosingSequence` · `GateDecision`. 자원 `gate/`.

### realtime.practice-runtime-config
- **[깊게]** `realtime/practice/PracticeRuntimeConfiguration.java`.
- 책임: `mujoong.timing.practice` 바인딩, 기본 `EvaluationProvider`(실패 future "판정 공급자 미연결") · `CueProvider.UNAVAILABLE` · Core 상황 클라이언트(46~51행). 위험 TD-1.

### realtime.practice
- **[얕게]** `realtime/practice/`(29 파일) + 자원 `practice/u5b-*.yml`.

### realtime.log-recording
- **[얕게]** `realtime/log/`(대화 기록 · 지연 · 세션 공백), `realtime/recording/`(`FileAudioRecorder` 619줄).

### realtime.messaging
- **[얕게]** `realtime/messaging/`(19 파일): Outbox/Inbox 쌍.

### realtime.platform
- **[얕게]** `common` · `config` · `startup` · `runtime` · `observability` · `web` · `internalhttp` · `health`.

## core-api

### core.textai-config
- **[깊게]** `core/textai/TextAiConfiguration.java`. **[얕게]** 나머지 `core/textai/`(`TextAiProvider` · `TextAiGateway` · `ScenarioPrompt` · `ForbiddenExpressionPolicy` · `TextAiProperties` 등).
- 책임: 기본 `unavailableTextAiProvider` → `UNAVAILABLE`. 위험 TD-1.

### core.result-text-config
- **[깊게]** `core/result/ResultTextConfiguration.java`.
- 책임: 기본 `ResultTextProvider` → `PROVIDER_UNAVAILABLE`. 위험 TD-1 · TD-5(압축 서식).

### core.result
- **[얕게]** `core/result/`(41 파일): `JobRunner` · `SessionResultHandler` · `ResultLevelCalculator` · `ResultAiSchema` · `ForbiddenDictionary` · `TipMatcher` · `ResultRetryService` · `ResultController` 등. 자원 `prompts/result/*.v1.txt` · `forbidden/result-v1.json`.

### core.report
- **[얕게]** `core/report/`(14 파일): `ReportService` · `ReportAssembler` · `ReportPdfRenderer` · `ReportController` · `ReportRepository` 등.

### core.activity
- **[얕게]** `core/activity/`(18 파일): `StartActivityService` · `EndSessionService` · `RealtimeSessionClient` · `SessionPackageBuilder` · `PreparingTimeoutChecker` · `SessionStartBreaker` · 컨트롤러.

### core.practice
- **[얕게]** `core/practice/`(29 파일): `GoalSelectionService` · `ScenarioService` · `PracticeStartService` · `ScenarioInternalController` · `WeightedGoalOrder` 등.

### core.device
- **[얕게]** `core/device/`(17 파일): `RegistrationService` · `BootstrapService` · `DeviceClaimService` · `DeviceTokenVerifier` · `DeviceInternalController` 등.

### core.identity-account
- **[얕게]** `core/identity/` · `core/account/` · `core/consent/` · `core/child/`: Google 로그인 · dev 로그인 · 시드 · 보호자/아이/동의 · 삭제.

### core.record
- **[얕게]** `core/record/`(9 파일).

### core.notification
- **[얕게]** `core/notification/`(29 파일): 구독 · 발송 스케줄 · VAPID 전송 · 고정 HTTP 클라이언트.

### core.messaging
- **[얕게]** `core/messaging/`(14 파일): Outbox/Inbox 쌍 + `SessionStateChangedHandler` · `DeviceStatusChangedHandler`.

### core.knowledge
- **[얕게]** `core/knowledge/`: `KnowledgeGraphLookup` 인터페이스 · JDBC 저장소, Neo4j 어댑터 없음(TD-3).

### core.platform
- **[얕게]** `security` · `web` · `config`(`CoreProperties` 576줄) · `observability` · `startup` · `common` · `internalhttp` · `catalog` · `health`.

## test-support

### test-support
- **[얕게]** `backend/test-support/src/main/`: 시험 DB · 가변 Clock · 계약 대역 HTTP · 가짜 공급자 WS · 기기 시뮬레이터 · 로그 수집. testImplementation 전용.

## frontend

### frontend.build-config
- **[깊게]** `frontend/package.json`, `frontend/next.config.ts`(standalone 출력), `frontend/vitest.config.ts`.

### frontend.app-routes
- **[깊게]** `frontend/src/app/`: `/parent/*` 라우트 15페이지 + route 2개(`login/google`, `push-worker`), `/device` 페이지 + `pcm-capture-worklet` route, `/healthz`, 레이아웃 2개.

### frontend.parent
- **[얕게]** `frontend/src/parent/`: 17 스크린 · api · activity · practice · push · hooks · components. react-query 폴링.

### frontend.device
- **[얕게]** `frontend/src/device/`: D0View · T19View · 상태 기계 · WS 클라이언트 · 마이크 · 오디오 워크릿 · 전원.

### frontend.shared
- **[얕게]** `frontend/src/shared/`: protocol · timing(`timing-defaults.json`) · copy · errors · security · styles · toast.

## 도구 · 운영

### tools.package
- **[깊게]** `tools/package.json`. **[얕게]** `tools/fake-provider/` · `device-simulator/` · `skeleton-e2e/` · `dev-edge/` · `seed/` · `lib/` · `tests/`.
- 책임: 가짜 공급자(같은 `session.*` 선 형식) · 기기 시뮬레이터 · 끝-끝 스켈레톤 · Nginx 대역.

### infra
- **[얕게]** `infra/`: Compose(dev · ci · demo) · nginx · migrate · postgres · backup · bundle · seed · seed-audio · host · bin · aws · ci, `RUNBOOK.md`, `migration-approvals.txt`.

### scripts
- **[얕게]** `scripts/verify.sh` + `scripts/verify/*`(A 점검 · B 스택 · C 스켈레톤 턴), `cloud-setup.sh`.


