## Developer Code Scan Results

> 인텐트 `261013-u4b-activity-realtime` · 저장소 `mujungi`(프로젝트 루트) · 기준 커밋 `3748083`(브랜치 `unit/u4b-activity-realtime`) · 스캔 일자 2026-10-13 · 집중 스캔(첫 스캔, 기존 저장소 없음).
> 역할: 리버스 엔지니어링 1단계(개발자 코드 스캔). 아키텍트가 이 결과를 9개 codekb 산출물로 합성한다.

### Scan Coverage
- **Analyzed deeply**:
  - `backend/realtime-activity/` (운영 소스 125개 파일 · 약 20,360줄, 시험 92개 파일, `src/main/resources` 설정 · Flyway V1 · V2 · afterMigrate, `build.gradle.kts`, `Dockerfile` 머리)
  - `backend/contract-model/` (운영 소스 54개 파일 · 약 4,178줄, 시험 15개, `build.gradle.kts`)
  - `backend/test-support/` (운영 9개 · 시험 4개, `build.gradle.kts`)
  - `backend/build-logic/` (`mujoong.java-conventions.gradle.kts`, `MujoongBuildExtension.kt`, 빌드 설정)
  - `contracts/` (`timing.md` · `internal-api.yaml` 경로 · `events.asyncapi.yaml` · `schemas/internal-session-start-request.schema.json` · `schemas/ws-messages.schema.json` · `schemas/internal-session-view.schema.json` 해당 부분)
- **Skimmed only**:
  - `backend/settings.gradle.kts` · `backend/build.gradle.kts` · `backend/gradle/libs.versions.toml` · `backend/gradle/wrapper/gradle-wrapper.properties` · `backend/gradle.properties` (판 확인용)
  - `backend/coverage-scope/` (u1 · u3 목록 형식과 realtime 줄 수만)
  - `backend/core-api/` (Realtime과 맞닿는 경계만: `FlywayConfIT` · `SchemaPrivilegeIT`의 realtime 기대값, `SessionRecordCapturedHandler` · `DeletionCommander` 파일 존재)
  - `.github/workflows/` (`ci.yml` 작업 이름 · 실행 명령)
  - `frontend/` (`package.json` 판만)
  - `infra/` (디렉터리 목록, `migrate/flyway-realtime.conf` 존재)
  - `docs/units/README.md` · `docs/units/u4b/brief.md` · `docs/units/u4b/design.md` 1 ~ 4절 · `docs/units/u3/report.md` JoinTracker 관련 줄 (방향 잡기용, 코드 아님)

### Packages Found
Gradle 멀티 프로젝트 `mujoong-backend` (`settings.gradle.kts`: `contract-model` · `core-api` · `realtime-activity` · `test-support`, 포함 빌드 `build-logic`).

- `contract-model` — java-library — Java 21 — 두 앱이 같은 이름으로 쓰는 계약 타입만 둔다(Spring · JPA 의존 없음, ArchUnit으로 강제). 하위 패키지:
  - `contract.status` — 열거형: `SessionStatus`(11상태 + `UNRECOGNIZED`, `REQUESTED`는 Core 사본 전용) · `EndReason`(8사유 GUARDIAN_STOP · CHILD_STOP · SILENCE · DISCONNECT · CONSENT_WITHDRAWN · UNCONFIRMED · DECLINED · FAILED) · `FailureCode`(DEVICE_NO_ACK · SCENARIO_GEN_FAILED · PROVIDER_UNAVAILABLE · SERVER_RESTART) · `EndRequestReason`(GUARDIAN_STOP · CONSENT_WITHDRAWN · CORE_ABANDONED) · `ActivityType` · `ConnectionState` · `RegistrationStatus`
  - `contract.event` — `EventType`(영속 이벤트 6종, 방향 · 제어 이벤트 여부 · `CURRENT_SCHEMA_VERSION = 1`) · `EventEnvelope` · `payload/*`(SessionStateChanged · SessionRecordCaptured · DeviceStatusChanged · DeletionCompleted · DeviceReleased · DeletionRequested)
  - `contract.ws` — `WsMessageType`(기기 WSS 제어 메시지 17종) · `WsEnvelope` · `CloseCode` · `message/*`(Hello · StartSession · Ack · DeviceReady · SessionState · OutputBegin/End · PlayApprovedContent · PlaybackMark · StopOutput · CloseTransport · VoiceSessionClosed · DeviceState · View · WsError 등)
  - `contract.json`(`JsonCodec`: 정규화 JSON · `sha256OfCanonical` — snapshotHash 계산 원천) · `contract.time`(`MujoongClock` · `TimerDriver` · `DelayQueueTimerDriver`) · `contract.log`(`EventCode` · `AttrKey` · `EventLog` · `OwnerHasher` · `StructuredLogFormatter`) · `contract.error` · `contract.audio`(`AudioFrameCodec`) · `contract.profile`(`ProfileGuardRules`) · `contract.secret` · `contract.trace`
- `realtime-activity` — Spring Boot 앱(bootJar `realtime-activity.jar`, 포트 8081 · 관리 9090) — Java 21 — 기기 WSS · 세션 상태 기계 · 공급자 중계 · 출력 게이트 · 전사 기록 · Realtime Outbox/Inbox. 패키지(`kr.mujoong.realtime.*`):
  - `session`(24 + web 1) — SessionLifecycle 핵심. `SessionExecutor`(1,832줄, 세션당 직렬 실행기 · 편지함 소비 · 전이 승자만 부수 효과) · `TransitionTable`(U1 전이 표, `Trigger` 12종) · `TransitionService`/`SessionTransitions`(한 전이 = 한 짧은 트랜잭션 + SessionStateChanged Outbox) · `SessionTimers`(Kind 6종: DEVICE_ACK · CLOSING_CONFIRM · DISCONNECT · SEGMENT_QUIET · PLAYBACK_EXPIRY · REDELIVER, 세대 번호) · `SessionMailbox`(안전 L0H 무제한 · 일반 L0N 1,000) · `SessionRegistry`(INIT 선등록) · `StartSessionHandler` · `EndRequestHandler` · `TombstoneMap` · `OrphanSessionChecker` · `SessionStateEmitter` · `SessionViewQuery` · `ActivitySessionRepository`/`Row`(29열) · `SessionEventRepository` · `SessionStopExpressionRepository` · `SessionGapPort` · `ChildContext` · `ConsentSnapshot` · `web/SessionInternalController`
  - `dialog`(2) — `StopIntentJudge`(565줄, 종료 의도 판정 STOP/CONFIRM/NONE + `continues` 해제 판정, 상태를 바꾸지 않음) · `DialogInstructions`(역할 지시문 · 교정 문장). `JoinTracker`는 **현재 없음**(아래 Handoff 참조)
  - `gateway`(21) — 기기 WSS: `WebSocketConfig`(`/ws/device`) · `DeviceHandshakeInterceptor` · `DeviceWebSocketHandler` · `DeviceConnection`(AWAIT_VERIFY → AWAIT_HELLO → READY → CLOSED) · `ConnectionSender`(연결당 송신 스레드 하나) · `ConnectionHandover`(HELLO_OK 인계 · 4001 REPLACED) · `ConnectionStateWriter`/`DeviceStatusPort` · `DeviceConnectionRepository` · `DeviceDisconnectRepository`(U3) · `DisconnectChecker`(40초 무음) · `HeartbeatHandler` · `StatusReporter`(10초 주기 Core 보고) · `VerifyTokenClient` · `DeviceReleasedHandler`(유일한 `InboxHandler` 구현) · `SocketRegistry` · `WsFrameGuard` · `OutputBundle`
  - `voice`(8) — `ProviderClient`(JDK `java.net.http.WebSocket`, SDK 없음) · `ProviderPort` · `ProviderConnector`(세션당 가상 스레드) · `ProviderRequestFactory`(공급자 요청의 유일한 생성 경로) · `ProviderHosts`(허용 목록 코드 상수) · `InputRelay` · `AudioTap` · `ProviderResponseId`
  - `gate`(6) — `OutputGate` · `SegmentBuffer`(20초 상한) · `SeedDictionary`(`gate/seed-dictionary.json`) · `GateDecision` · `ClosingSequence`(CLOSING 진입 순서 BR6.10) · `ApprovedPlayback`
  - `log`(9) — `AsyncRecorder` · `TranscriptWriter`/`Repository`/`Row`/`Entry` · `LatencyRecorder`(LATENCY_TURN) · `SessionGapRepository`/`SessionGapWriter`(U3, DISCONNECT · UNCONFIRMED) · `RealtimeVoiceDeletionParticipant`(U3, 부르는 곳 없음)
  - `messaging`(16 + web 1) — Outbox: `RealtimeOutboxWriter`(활성 트랜잭션 필수) · `RealtimeOutboxRepository` · `RealtimeOutboxSender`(전용 스레드, SKIP LOCKED) · `ControlEventRearm`. Inbox: `web/RealtimeInboxController`(`POST /internal/events`) · `RealtimeInboxReceiver` · `RealtimeInboxDispatcher`(선점 UPDATE) · `RealtimeInboxRepoller` · `RealtimeInboxRepository` · `InboxHandler`. 기타: `MessagingCleanupJob` · `RealtimeMessagingDeletionParticipant`(U1, 부르는 곳 없음) · 값 타입
  - `recording`(3) — `AudioRecordings` · `FileAudioRecorder`(619줄, 전용 스레드 · PCM 파일) · `AudioRecordingRepository`(U3)
  - `runtime`(4) — `PeriodicJobs`(이름 있는 단일 스레드 5종: outbox-sender · inbox-repoll · maintenance · recorder-tick · metric-sample) · `TaskLauncher`(가상 스레드, 시험은 inline) · `TimerClocks`(시계 2개: safety · 기타) · `RealtimeRuntimeConfig`(`MujoongClock` 빈)
  - `internalhttp`(2) — `InternalHttpClient`(Realtime → Core, 대상 그룹별 JDK HttpClient) · `InternalAuthInterceptor`(`X-Internal-Token` · `X-Trace-Id`)
  - `startup`(7) — `ProfileGuardEnvironmentPostProcessor`(S1) · `RealtimeStartupChecks`(S1b) · `RealtimeStartupSequence`(S2 ~ S6: 스키마 판 → 데이터 등급 → 비종료 세션 FAILED(SERVER_RESTART) → …) · `SchemaVersionCheck`(앱은 Flyway를 실행하지 않고 이력 표만 읽음) · `DataClassCheck` · `DrainingLifecycle`(T1 ~ T6) · `StartupFailureLine`
  - `web`(6) — 필터 체인 `TraceIdFilter`(F1) · `ReadinessGateFilter`(F1b) · `InternalTokenFilter`(C-INT) · `InFlightRequests` · `InternalBody`(2MB 상한) · `ApiExceptionHandler`
  - `observability`(6 + snapshot 2) · `health`(2) · `config`(2: `RealtimeProperties` 450줄 · `RealtimePropertiesConfig`) · `common.db`(6: `DbRetry` · `DbSessionLimits` · `Transactions` · `ClockParams` · `SqlErrors` · `DbPoolWaitMonitor`) · `common.collect`(1) · `common.scaling`(`@SingleInstanceAssumption`)
- `test-support` — java-library(시험 전용, testImplementation으로만 의존) — Java 21 — `MujoongPostgres`(docker = Testcontainers / local 호스트 바이너리, 두 위치 Flyway 적용, 역할별 DataSource) · `DeviceSimulatorClient` · `ContractStubServer` · `ReceiverDouble` · `FakeProviderWsServer`(Java-WebSocket) · `LogCapture` · `MutableClock` · `ManualTimerDriver` · `TestSecrets`
- `build-logic` — Gradle 포함 빌드(kotlin-dsl) — Kotlin — 규약 플러그인 `mujoong.java-conventions` + `MujoongBuildExtension.coverageFloorApplies`
- `core-api` — Spring Boot 앱 — Java 21 — (훑기만) Core 쪽. Realtime 경계: `SessionRecordCapturedHandler`(U4a 구독자) · `DeletionCommander`(DeletionRequested 발행) · `InternalHttpClient`(Realtime 내부 API 호출)

### Build System
- **Type**: Gradle 8.14.3 (wrapper, sha256 고정) · Kotlin DSL · 버전 카탈로그 `gradle/libs.versions.toml` · Java 21 툴체인(자동 다운로드 끔) · `org.gradle.parallel=true`
- **Config Files**: `backend/settings.gradle.kts` · `backend/build.gradle.kts`(루트는 `base`만) · `backend/build-logic/{settings,build}.gradle.kts` · `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts` · `backend/build-logic/src/main/kotlin/kr/mujoong/buildlogic/MujoongBuildExtension.kt` · `backend/gradle/libs.versions.toml` · `backend/gradle.properties` · 모듈별 `build.gradle.kts` · `backend/realtime-activity/Dockerfile`(호스트가 만든 bootJar를 COPY, 이미지 안 Gradle 없음) · `backend/coverage-scope/*.txt`
- **Build Dependencies**:
  - `realtime-activity` → `contract-model` (implementation)
  - `realtime-activity` → `test-support` (testImplementation만)
  - `test-support` → `contract-model` (api)
  - `core-api` → `contract-model`(implementation) · `test-support`(testImplementation) — 훑기
  - 모든 모듈 → `build-logic`(`mujoong.java-conventions` 플러그인)
  - `realtime-activity` ↛ `core-api` (코드 · core 스키마 참조 금지, `RealtimeArchTest.realtimeNeverReferencesCoreCodeOrTheCoreSchema`)
- **규약 플러그인 핵심**: `-Xlint:all,-processing,-serial,-this-escape,-try` · `-parameters` · 재현 가능 아카이브 · 시험 JVM `-Duser.timezone=UTC` · 태그 필터 `-PincludeTags` / `-PexcludeTags` · `-Pmujoong.test.db=auto|docker|local` · 시험 입력에 `contracts/` · `infra/postgres` · `infra/seed` · `infra/migrate` · 두 앱의 `db/**` 포함 · `verifyBootJarHasNoTestClasses` 작업 · 보안 패치 판 강제(Tomcat 11.0.26 제약, Jackson3 3.1.7 · Jackson2 2.22.3 BOM)

### APIs Discovered
- 내부 HTTP(Realtime 제공, C-INT 체인: `X-Internal-Token` · 준비 상태 게이트 · 본문 2MB) — `backend/realtime-activity/.../session/web/SessionInternalController.java` · `messaging/web/RealtimeInboxController.java` · 계약 `contracts/internal-api.yaml` — 4개:
  - `POST /internal/sessions`(startSession, 세션 패키지 · 키 멱등)
  - `POST /internal/sessions/{sessionId}/end`(endSessionInternal, J54 응답표 · 현재 `CONSENT_WITHDRAWN`은 400 VALIDATION_FAILED)
  - `GET /internal/sessions/{sessionId}/view?sinceSeq`(getSessionViewInternal, `technicalPause` · `silenceWarning`은 현재 늘 `null`)
  - `POST /internal/events`(receiveEvent, Realtime Inbox)
- 내부 HTTP(Realtime이 호출, Core 제공) — `internalhttp/InternalHttpClient.java` · `gateway/VerifyTokenClient.java` · `gateway/StatusReporter.java` · `messaging/RealtimeOutboxSender.java` — 3개: `POST /internal/devices/verify-token` · `PUT /internal/devices/{deviceId}/connection` · `POST /internal/events`(Core Inbox)
- 기기 WSS — `gateway/WebSocketConfig.java`(`/ws/device`, Origin 정확 일치 · 쿠키 `MUJOONG_DEVICE`) · 계약 `contracts/device-ws.asyncapi.yaml` · `contract-model/.../ws/WsMessageType.java` — 제어 메시지 17종 + 이진 프레임 0x01(입력) · 0x02(출력). opId 필요 명령 4종(START_SESSION · PLAY_APPROVED_CONTENT · STOP_OUTPUT · CLOSE_TRANSPORT). `CloseTransport.Reason`은 이미 CHILD_STOP · SILENCE · CONSENT_WITHDRAWN · DECLINED를 포함(스키마 `ws-messages.schema.json` 218행), `View`는 STANDBY · LISTENING · THINKING · CLOSING
- 영속 이벤트(Outbox → HTTP → Inbox) — `contract-model/.../event/EventType.java` · `contracts/events.asyncapi.yaml` — 6종: Realtime → Core 4(SessionStateChanged · SessionRecordCaptured · DeviceStatusChanged · DeletionCompleted), Core → Realtime 2(DeviceReleased · DeletionRequested). 현재 Realtime이 **생산하지 않는** 것: SessionRecordCaptured · DeletionCompleted. 현재 Realtime이 **처리하지 않는** 것: DeletionRequested(처리기가 없어 IGNORED로 커밋)
- 공급자 WSS(외부) — `voice/ProviderClient.java` · 계약 `contracts/ai-io.md` 2 · 3절 — JDK WebSocket, `Authorization: Bearer ${PROVIDER_API_KEY}`, 요청 레코드 3종(`ProviderRequestFactory`)
- 관리 · 지표 — Actuator(관리 포트 9090, 127.0.0.1) · Micrometer Prometheus(`RealtimeMetrics` 이름 한 곳)

### Frameworks & Libraries
(판 번호가 없는 항목은 Spring Boot BOM이 정한다 — `gradle/libs.versions.toml`)
- Spring Boot — 4.1.1 — 앱 프레임워크(BOM 플랫폼). 사용 스타터: webmvc · websocket · data-jpa · jdbc · flyway · actuator · validation (Realtime은 Spring Security 없음)
- Java — 21 (툴체인 · `--release 21`) — 가상 스레드 사용(`TaskLauncher`)
- Gradle — 8.14.3 — 빌드
- Flyway — BOM — `flyway-core` · `flyway-database-postgresql`(앱은 validate/이력 읽기만, 적용은 migrate 컨테이너 · 시험 백엔드)
- PostgreSQL JDBC — BOM — DB 드라이버 (DB 16, 시험은 Testcontainers 또는 로컬 바이너리)
- HikariCP — BOM — 커넥션 풀(앱당 10 이하)
- Jackson 3 (`tools.jackson`) — 3.1.7(보안 패치 BOM) — JSON(직접 import는 `kr.mujoong.contract.json` 한 곳). Jackson 2 BOM 2.22.3도 덮어씀
- Tomcat embed — 11.0.26(보안 제약) — 서블릿 컨테이너
- Micrometer Prometheus registry — BOM — 지표
- SLF4J · Logback — BOM — 구조화 로그(`StructuredLogFormatter`, `logback-spring.xml`)
- springdoc-openapi — 3.1.1 — 카탈로그에 있음(Core dev · test 전용, Realtime 미사용)
- JUnit Jupiter / Platform — BOM — 시험(Platform 태그)
- AssertJ · Mockito(+ junit-jupiter) — BOM — 단위 시험
- ArchUnit — 1.5.1 — 구조 규칙 시험
- Testcontainers (postgresql · junit-jupiter) — BOM — DB 통합 시험
- networknt json-schema-validator — 3.0.6 — 계약 JSON Schema 2020-12 검증
- Java-WebSocket — 1.6.0 — 가짜 공급자 WS 서버(test-support 전용)
- JaCoCo — 0.8.14 — 커버리지
- (훑기) 프런트엔드: Next.js 16.4.0 · React 19.3.0 · TypeScript 5.9.3 · ESLint 9.39.5 · Vitest 4.1.11
- 기반 이미지: `eclipse-temurin:21.0.12_8-jre-noble`(다이제스트 고정) + openssl .deb 스냅숏 패치 단계

### Test Coverage
- **Test Directories**: `backend/realtime-activity/src/test/java/kr/mujoong/realtime/{arch,common,config,db,dialog,gate,gateway,log,messaging,observability,recording,session,startup,testing,voice,web}` · `backend/realtime-activity/src/test/resources/application-test.yml` · `backend/contract-model/src/test/java` · `backend/test-support/src/test/java`
  - 규모: realtime-activity `@Test` 526개(시험 클래스 약 80개 + `testing/` 지원 14개), contract-model + test-support `@Test` 140개
  - 태그(realtime-activity, `@Tag` 선언 수): `u1` 54 · `u3` 23 · `db` 28. `u4b` 태그는 아직 없음. contract-model에는 `u1` 18 · `u4a` 1(`SnapshotHashGoldenTest` 클래스 — snapshotHash 골든 값, U4b가 null 처리 골든을 더할 자리) · `u5a` 1(`ErrorCodeTest` 안 메서드 하나)
  - 세션 관련 시험: `TransitionTableTest` · `SessionTimersTest` · `SessionExecutorTest` · `SessionExecutorFaultTest` · `SessionExecutorU3Test` · `SessionExecutorReview{,2,3}U3Test` · `SessionLifecycleIT` · `SessionVoiceU3IT/Test` · `ActivitySessionRepositoryIT` · `SessionRecordsRepositoryIT` · `web/RealtimeInternalApiIT` · `session/web/SessionInternalControllerU3Test` · `dialog/StopIntentJudgeTest`
  - 실행기 단위 시험 대역(`testing/`): `SessionHarness` · `InMemoryTransitions` · `FakeDeviceLink` · `FakeProviderPort` · `FakeSessionGaps` · `FakeAudioRecordings` · `ManualTime` · `ManualLauncher` · `RealtimeItEnvironment` · `RealtimeTestApp` · `InternalApi`(계약 스키마 검증 도우미)
  - 구조 · 규약 시험: `RealtimeArchTest`(core 참조 금지 · test-support 의존 금지 · 프레임 경로 JDBC/파일/Outbox 금지 · 시스템 시계 직접 읽기 금지 · 공급자 클라이언트는 voice만 · 요청은 팩토리만) · `TimeLiteralScanTest`(운영 소스 시간 숫자 상수 0건, 허용 목록) · `SingleInstanceMarkerTest` · `ConfigKeyContractTest`(`contracts/timing.md` ↔ `application.yml` ↔ 설정 레코드 이름 · 기본값 대조)
- **Test Frameworks**: JUnit 5 (Platform 태그) · AssertJ · Mockito · ArchUnit 1.5.1 · Testcontainers(PostgreSQL 16) / 로컬 PG 바이너리 · Spring Boot Test(webmvc · data-jpa · jdbc test slices) · networknt JSON Schema 검증 · test-support 대역(가변 Clock · 수동 타이머 · 기기 시뮬레이터 · 가짜 공급자 · 계약 대역 서버 · LogCapture)
- **Coverage Config**: present — JaCoCo 0.8.14, `check`가 `jacocoTestCoverageVerification`에 의존, LINE COVEREDRATIO ≥ 0.80(제외 목록 없음, `test-support`만 `coverageFloorApplies=false`). `-PcoverageScope=<이름>`이면 `backend/coverage-scope/<이름>.txt`의 이 모듈 운영 소스만 측정(목록이 비거나 파일이 없으면 빌드 실패). 현재 목록: `u1.txt`(301줄, realtime 119줄) · `u3.txt`(44줄, realtime 41줄) · `u2` · `u4a` · `u5a`(realtime 0줄). `u4b.txt`는 아직 없음. CI(`ci.yml` backend)는 범위 없이 모듈 전체 80%로 `clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker`를 돈다

### Code Quality Indicators
- **Linting**: 백엔드 전용 린터(Checkstyle · Spotless · PMD · SpotBugs · Error Prone) 없음. 대신 `javac -Xlint:all`(일부 제외) · ArchUnit 규칙 · `TimeLiteralScanTest` · `ConfigKeyContractTest` · `verifyBootJarHasNoTestClasses`가 품질 관문 역할. 저장소 루트 `.editorconfig` 존재. 프런트는 `frontend/eslint.config.mjs`(ESLint 9, 훑기)
- **CI/CD**: `.github/workflows/ci.yml` — 작업 `changes`(경로 필터) · `backend`(gradle check + bootJar 시험 클래스 0) · `backend-deps`(Trivy high 이상, `ci/security-exceptions.yml`) · `frontend`(ESLint · Vitest 80% · next build · 번들 비밀 검사) · `frontend-deps` · `tools` · `secrets`(gitleaks) · `infra-validate`(마이그레이션 expand 검사 포함, 승인 목록 `infra/migration-approvals.txt`) · `images`. `.github/workflows/deploy.yml` — OIDC · SSM 배포(저장소 변수 `SERVICE_HOST` · `AUTO_DEPLOY`가 없어 실제 배포는 건너뜀, docs/units/README.md)
- **Documentation**: 코드 문서화 수준 높음 — 거의 모든 클래스에 한국어 Javadoc이 있고 규칙 · 결정 ID(BR · J · U3-R · U3-J · SD · RD · PD 등)로 설계에 추적된다. 계약 정본은 `contracts/`(README · timing · errors · deviations · ports · ai-io · OpenAPI/AsyncAPI · JSON Schema 51개). 유닛별 `docs/units/<unit>/{brief,design,report}.md`. 운영 런북 `infra/RUNBOOK.md`. 계약 파일 자체는 `ContractFilesTest`(contract-model)가 메타 검증

### Technical Debt Signals
- **흔들리는 U1 시험** — `backend/realtime-activity/src/test/java/kr/mujoong/realtime/web/RealtimeInternalApiIT.java:164`(`coreAbandonedClosesNonTerminalSessionsAndLeavesATombstoneForUnknownIds`). 191 ~ 193행에서 `dbOnlySession(CLOSING)`으로 만든 실행기 없는 CLOSING 행이 `application-test.yml`의 `closing-confirm: 3s` · `orphan-check-interval: 1s` 때문에 단언 전에 고아 점검으로 FAILED가 될 수 있다(부하 시 202 대신 409). docs/units/README.md · U3 report 9절 · U4b brief 「먼저 할 것」 2에서 U4b 몫으로 지정
- **U4 확장 지점이 주석으로만 표시된 미완 경로**(코드 안 `U4` 언급 19곳):
  - `session/TransitionTable.java:18` — WAITING_CHILD · DECLINED · CHILD_STOP · SILENCE · CONSENT_WITHDRAWN 전이 없음. `END_REASONS`가 도착 상태로 사유를 정한다(ENDED → GUARDIAN_STOP 고정) — 설계는 '사건이 사유를 정함'으로 바꿈
  - `session/EndRequestHandler.java:29` · `SessionMailbox.java:124` · `web/SessionInternalController.java:47` — CONSENT_WITHDRAWN은 400
  - `session/SessionExecutor.java:226-270 · 803-838` — STOP이면 출력만 막고 ACTIVE 유지(`stopIntent` 플래그) · CONFIRM은 이벤트만 기록 · `CHILD_STOP_LIFTED` 해제 경로. U4b에서 STOP → CLOSING(CHILD_STOP)이 되면 해제 경로의 뜻이 바뀜
  - `messaging/InboxHandler.java:14` — DeletionRequested 처리기 없음(IGNORED)
  - `messaging/RealtimeMessagingDeletionParticipant.java:31` · `log/RealtimeVoiceDeletionParticipant.java:34,169` — 부르는 곳 없는 삭제 참가자 둘
  - `session/NewActivitySession.java:14` — `intro_content_version_id`는 늘 null
  - `log/SessionGapRepository.java:20` — UNCONFIRMED 구간은 자리만
  - `web/SessionInternalController.java:223-224` — `technicalPause` · `silenceWarning` 늘 null
- **시간 키 미배선** — `contracts/timing.md` 2절(58 ~ 62행)의 `join-wait` · `join-wait-after-reprompt` · `silence-warning` · `silence-end` · `device.gap-display`는 '이름만 예약'. `config/RealtimeProperties.java:90`의 `SessionTiming`은 `deviceAckTimeout` · `closingConfirm` 두 필드뿐이고 `application.yml` · `application-test.yml`에도 없음. 설계 4절의 신설 3키(`stop-confirm-wait` · `record-settle` · `mujoong.session.deletion-stop-wait`)도 계약에 없음. `ConfigKeyContractTest` · `TimeLiteralScanTest`가 세 곳(계약 · yml · 레코드)을 함께 고치도록 강제
- **신 클래스(God class) 경향** — `session/SessionExecutor.java` 1,832줄(메시지 처리 · 시계 · 게이트 · 종료 의도 · 재연결 · 정리를 한 클래스가 가짐, 세션 단일 스레드 설계상 의도된 집중). U4b가 참여 확인 · 공백 · 마무리 사유를 더하면 더 커짐. 그 밖 500줄 넘는 파일: `dialog/StopIntentJudge.java`(565) · `log/AsyncRecorder.java`(531) · `recording/FileAudioRecorder.java`(619)
- **스키마 판 기대값이 여러 시험에 박혀 있음** — `db/RealtimeSchemaIT.java:47-98`(표 10개 · 이력 0/1/2 · 최고 판 "2" `containsExactly`/`hasSize(10)`), `backend/core-api/src/test/java/kr/mujoong/core/db/FlywayConfIT.java:87,99,102`(realtime 이력 · `migrationsExecuted == 2` · 표 합계 33), `SchemaPrivilegeIT`. U3 V2 때(커밋 47db8f2) 이 셋과 `RealtimeDb`를 함께 고쳤다
- **세션 패키지 계약의 승인 문구 키** — `contracts/schemas/internal-session-start-request.schema.json:66-80`은 `approvedContent` 필수 키가 CLOSING · WAITING_FILLER 둘이고 그 밖 키는 `additionalProperties`로 허용. `SessionExecutor.Plan`(190행)은 `closing` · `waitingFiller`만 담고 활동 종류(`activityType`)도 담지 않음 — 시작 안내(INTRO_FREE_TALK · INTRO_PRACTICE) · 확인 질문 · 재안내 문구를 고르려면 Plan 확장 필요
- **결과 스냅숏 저장소 없음** — realtime V1 · V2에 결과 입력 스냅숏 표가 없고 `SessionRecordCaptured`를 쓰는 코드가 없다(`contract-model/.../SessionRecordCaptured.java`는 `record` · `termination`을 해석하지 않은 Map으로 둠, U4가 모양 소유)
- **TODO/FIXME/HACK 0건**, 운영 소스 `@SuppressWarnings` 2건 — 표식 부채는 적음
- **작업 트리 상태(스캔 범위 밖, 참고)** — 셸 스크립트 · `backend/gradlew` 41개 파일이 인덱스에서 100755 → 100644로, 작업 트리에서 다시 100755로 바뀌어 `MM` 상태(파일 모드 왕복). 커밋 시 실행 비트가 빠질 위험

## Handoff Summary
- **Intent-relevant finding**:
  1. 상태 기계는 `contract-model`에 11상태 · 8사유가 이미 정의돼 있지만(`contract-model/.../status/SessionStatus.java` · `EndReason.java`) Realtime 전이 표는 U1 범위뿐이다. `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/TransitionTable.java:24-60`의 `Trigger` 12종에는 WAITING_CHILD · DECLINED 도착 · CHILD_STOP · SILENCE · CONSENT_WITHDRAWN 사건이 없고, 93-97행 `END_REASONS`는 도착 상태 → 사유 고정 매핑(ENDED = GUARDIAN_STOP)이다. `ACTIVATED`(DEVICE_ACK → ACTIVE)는 설계상 DEVICE_ACK → WAITING_CHILD로 바뀌어야 한다. DB는 이미 준비돼 있다: V1 `realtime_activity_session_status_ck`(V1 54 ~ 56행)가 WAITING_CHILD · DECLINED를 허용하고 진행 중 유일 색인(75 ~ 80행)도 WAITING_CHILD를 포함하므로 상태 추가에 제약 변경이 필요 없다.
  2. `JoinTracker`는 main에 없다. 커밋 `5354a32`(및 `867f0a0`)에 `dialog/JoinTracker.java` · `JoinTrackerTest.java`가 있고, 공개 API는 `JoinTracker(Duration noReply)` 단일 창 생성자 · `promptPlayed` · `reply` · `tick` · `classify` · `phase` · `prompts` · `isFinished` · `MAX_PROMPTS = 3`이다. U4b-J4는 두 창(join-wait · join-wait-after-reprompt)을 받도록 넓히라고 한다.
  3. 종료 의도: `session/SessionExecutor.java:811-838`이 `StopIntentJudge` 결과로 STOP이면 출력만 막고 ACTIVE를 유지하며(`stopIntent`), CONFIRM은 `CHILD_STOP_UNCLEAR` 이벤트만, `continues`면 `CHILD_STOP_LIFTED`로 푼다. CLOSING(CHILD_STOP) 전이와 확인 질문 재생을 붙일 자리다.
  4. 삭제: `RealtimeMessagingDeletionParticipant.deleteByGuardian` · `RealtimeVoiceDeletionParticipant.deleteByGuardian`(둘 다 멱등 · `Map<String,Integer>` counts 반환)이 준비돼 있으나 부르는 곳이 없고, `InboxHandler` 구현은 `DeviceReleasedHandler` 하나뿐이라 DeletionRequested는 IGNORED로 커밋된다. 세션 행(`realtime_activity_session`)을 지우는 참가자는 없다(Voice 참가자 Javadoc 45행: '세션 행은 U4 몫').
  5. 시계: `SessionTimers.Kind`(26 ~ 38행)에 참여 대기 · 공백 · 점선 · 확인 질문 대기 · 정산 시계가 없고, 끊김 시계(`onDisconnectCheck`, `SessionExecutor.java:1480`)는 `status != ACTIVE`면 무시한다(WAITING_CHILD 확장 필요).
- **Risks / follow-up**:
  - **realtime V3 추가 시 U1 · Core 시험 기대값 충돌**: `RealtimeSchemaIT`(u1 태그)의 `containsExactly` 이력 · 최고 판 "2" · 표 10개, 그리고 `backend/core-api/src/test/java/kr/mujoong/core/db/FlywayConfIT.java`(realtime `migrationsExecuted == 2` · 표 합계 33) · `SchemaPrivilegeIT`이 V3에서 깨진다. U4b brief는 'U1 · U3 시험의 단언'과 `backend/core-api`를 건드리지 말라고 하지만, U3(커밋 47db8f2)는 V2 때 이 시험들을 고쳤다. 요구사항 단계에서 '스키마 판 기대값 갱신은 예외로 허용'을 명시적으로 결정해야 한다. U5b(realtime V4)와 병합 순서(V3 → V4, `outOfOrder` 꺼짐)도 같이 맞춰야 한다.
  - **CI 마이그레이션 expand 검사**: V3가 기존 표(예: `realtime_session_gap`, `realtime_activity_session`)에 제약을 더하면 `infra-validate`에 걸린다 → `infra/migration-approvals.txt` 한 줄 + 리뷰 필요(brief). 더하기만 하는 새 표는 해당 없음.
  - **시간 키 3중 동기화**: 새 키마다 `contracts/timing.md`(2절 행을 1절로 옮기기 · 신설 키 추가) · `application.yml` · `application-test.yml` · `RealtimeProperties.SessionTiming`(또는 새 레코드)을 함께 고쳐야 `ConfigKeyContractTest`가 통과하고, 코드에 숫자 시간 상수를 두면 `TimeLiteralScanTest`가 실패한다. `contracts/timing.md`는 U5b와 병합 충돌 지점(docs/units/README.md).
  - **흔들리는 `RealtimeInternalApiIT`**: CLOSING 동작을 바꾸면 더 흔들릴 수 있다. 행 생성을 단언 직전으로 옮기거나 이 클래스만 시한을 늘리는 방안(brief) — 단 이것도 'U1 시험 단언 불변' 원칙과 경계가 맞닿으므로 단언 의미를 바꾸지 않는 수정임을 기록해야 한다.
  - **세션 패키지 · Plan 확장**: 시작 안내 · 확인 질문 · 재안내 등 승인 문구는 계약상 `approvedContent`의 추가 키로 들어온다(필수는 CLOSING · WAITING_FILLER뿐). 문구가 없을 때 소리 없이 시계만 도는 경로(U4b-R15)를 시험으로 지켜야 한다. `Plan`에 `activityType`을 실어야 INTRO_FREE_TALK / INTRO_PRACTICE를 고를 수 있다(U5b brief 87행이 이를 확인 항목으로 둠).
  - **snapshotHash 계약**: `JsonCodec.sha256OfCanonical`이 원천이고 `SnapshotHashGoldenTest`(contract-model)가 골든 값을 고정한다. null 키 처리 규칙을 계약 6절에 적고 골든 하나를 더해야 Core U4a 구독자(`core-api/.../record/SessionRecordCapturedHandler.java`)와 맞는다.
  - **`SessionExecutor` 비대화**: 1,832줄에 U4b 기능을 더하면 2,500줄을 넘을 수 있다. `JoinTracker` · `StopIntentJudge`처럼 판정을 순수 클래스로 빼고 실행기는 배선만 하는 기존 패턴(설계 표 '판정 · 할 일만, 실행기에 붙지 않음')을 유지할지 functional-design에서 결정 필요.
  - **커버리지 범위**: `backend/coverage-scope/u4b.txt` 신설(U1 형식, realtime 운영 소스만), 범위 줄 80%와 CI 모듈 전체 80%를 둘 다 지켜야 한다. 시험 태그 `u4b`는 아직 어디에도 없다.
  - **작업 트리 파일 모드 왕복**(`MM` 41개): 첫 커밋 전에 정리하지 않으면 실행 비트(100755)가 빠진 스크립트가 커밋될 수 있다 — 스캔 범위 밖이지만 Construction 전에 확인 권장.
