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
- 책임: 스키마 정본. 실행은 `infra/migrate` 이미지. 다음 번호 core V8 · realtime V5(TD-7).

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
