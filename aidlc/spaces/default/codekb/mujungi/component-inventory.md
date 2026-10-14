# 컴포넌트 목록 (Component Inventory)

> 기준 커밋 `3748083`. 아래 `###` 제목이 `reverse-engineering-timestamp.md` `analyzed.components`의 이름과 글자 그대로 같다. 상태 평가: 건강(healthy) · 주의(at-risk) · 저하(degraded). 괄호 숫자는 운영 파일 수.

## 요약

| 컴포넌트 | 종류 | 상태 | U4b 관련도 |
|---|---|---|---|
| contract-model | 라이브러리 모듈 | 건강 | 중(스냅숏 해시 골든 · 사유 열거는 이미 있음) |
| test-support | 시험 라이브러리 모듈 | 건강 | 중(대역 재사용) |
| build-logic | 빌드 규약 | 건강 | 저(커버리지 범위 파일만) |
| contracts | 계약 정본 | 건강 | 높음(timing · deviations 편집) |
| realtime.session | 앱 패키지 | 주의 | 매우 높음 |
| realtime.dialog | 앱 패키지 | 주의 | 높음 |
| realtime.gateway | 앱 패키지 | 건강 | 중 |
| realtime.voice | 앱 패키지 | 건강 | 저 ~ 중 |
| realtime.gate | 앱 패키지 | 건강 | 높음 |
| realtime.log | 앱 패키지 | 주의 | 높음 |
| realtime.messaging | 앱 패키지 | 주의 | 높음 |
| realtime.recording | 앱 패키지 | 건강 | 중(삭제) |
| realtime.runtime | 앱 패키지 | 건강 | 저 |
| realtime.internalhttp | 앱 패키지 | 건강 | 저 |
| realtime.startup | 앱 패키지 | 건강 | 중(스키마 판) |
| realtime.web | 앱 패키지 | 건강 | 저 |
| realtime.config | 앱 패키지 | 주의 | 높음(시간 키) |
| realtime.observability | 앱 패키지 | 건강 | 저 |
| realtime.common | 앱 패키지 | 건강 | 저 |

'주의' 판정의 근거는 `code-quality-assessment.md` 「기술 부채」에 있다.

## 모듈 · 계약

### contract-model
- **책임**: 두 앱이 같은 이름으로 쓰는 계약 타입(상태 · 사유 · 실패 코드 열거, 이벤트 봉투 · 페이로드, 기기 WSS 메시지, 정규화 JSON · 해시, 시계 추상화, 구조화 로그, 오류 코드, 오디오 프레임 코덱, 프로필 가드).
- **의존**: Jackson 3 · SLF4J/Logback만. Spring · JPA 금지(ArchUnit).
- **피의존**: `realtime-activity`(implementation) · `core-api`(implementation) · `test-support`(api).
- **비고**: `SessionStatus` 11상태 · `EndReason` 8사유가 이미 정의돼 U4b에 열거 추가가 필요 없다. `SessionRecordCaptured` 페이로드는 `record` · `termination`을 Map으로 둔다.

### test-support
- **책임**: 시험 전용 대역 — `MujoongPostgres`(Testcontainers 또는 로컬 PG 바이너리, 두 위치 Flyway 적용, 역할별 DataSource) · `DeviceSimulatorClient` · `ContractStubServer` · `ReceiverDouble` · `FakeProviderWsServer`(Java-WebSocket) · `LogCapture` · `MutableClock` · `ManualTimerDriver` · `TestSecrets`.
- **의존**: `contract-model`(api), Testcontainers, Java-WebSocket.
- **피의존**: 두 앱의 `testImplementation`만(운영 의존 금지, ArchUnit · `verifyBootJarHasNoTestClasses`).
- **비고**: `coverageFloorApplies=false`.

### build-logic
- **책임**: 규약 플러그인 `mujoong.java-conventions`(Java 21 툴체인 · `-Xlint:all` · 재현 가능 아카이브 · UTC 시험 JVM · 태그 필터 · 시험 DB 모드 · 계약 · 인프라 파일을 시험 입력으로 · JaCoCo 80% · 커버리지 범위 · 보안 판 강제)와 `MujoongBuildExtension.coverageFloorApplies`.
- **의존**: Gradle kotlin-dsl. **피의존**: 모든 모듈.

### contracts
- **책임**: API · 메시지 · 이벤트 · 시간 값 · 오류 · 편차의 정본(OpenAPI 3 · AsyncAPI 2 · JSON Schema 51개 · Markdown).
- **피의존**: 두 앱의 시험(`ContractFilesTest` · `InternalApi` · `ConfigKeyContractTest`)이 시험 입력으로 읽는다.
- **비고**: `timing.md` 2절의 `join-wait` 등은 '이름만 예약'. U5b와 병합 충돌 지점(`timing.md` · `deviations.md`).

## realtime-activity 패키지

### realtime.session
- **책임**: SessionLifecycle 핵심. 세션당 직렬 실행기, 전이 표, 전이 기록(한 전이 = 한 트랜잭션 + `SessionStateChanged` Outbox), 시계(Kind 6종: DEVICE_ACK · CLOSING_CONFIRM · DISCONNECT · SEGMENT_QUIET · PLAYBACK_EXPIRY · REDELIVER), 편지함, 시작 · 끝내기 처리, 묘비, 고아 세션 점검, 진행 조회, 세션 저장소.
- **주요 클래스 (24 + web 1)**: `SessionExecutor`(1,832줄) · `TransitionTable` · `TransitionService`/`SessionTransitions` · `SessionTimers` · `SessionMailbox` · `SessionRegistry` · `StartSessionHandler` · `EndRequestHandler` · `TombstoneMap` · `OrphanSessionChecker` · `SessionStateEmitter` · `SessionViewQuery` · `ActivitySessionRepository`/`Row`(29열) · `SessionEventRepository` · `SessionStopExpressionRepository` · `SessionGapPort` · `ChildContext` · `ConsentSnapshot` · `NewActivitySession` · `web/SessionInternalController`.
- **의존**: dialog · gate · voice · gateway(송신) · log · recording · messaging · runtime · config · common.db · contract-model.
- **피의존**: web(컨트롤러 경유 Core) · gateway(편지함 사건) · voice(공급자 사건).

### realtime.dialog
- **책임**: 대화 판정 순수 로직. `StopIntentJudge`(565줄, STOP/CONFIRM/NONE + `continues` 해제 판정, 상태를 바꾸지 않음) · `DialogInstructions`(역할 지시문 · 교정 문장).
- **비고**: `JoinTracker`가 **없다**(U3 1차 리뷰에서 제거). 커밋 `5354a32`(및 `867f0a0`)에 `JoinTracker.java` · `JoinTrackerTest.java`가 있으며 공개 API는 `JoinTracker(Duration noReply)` · `promptPlayed` · `reply` · `tick` · `classify` · `phase` · `prompts` · `isFinished` · `MAX_PROMPTS = 3`.

### realtime.gateway
- **책임**: 기기 WSS — 설정(`/ws/device`) · 핸드셰이크 · 연결 상태 기계 · 연결당 송신 스레드 · HELLO 인계(4001) · 연결 상태 저장과 Core 보고 · 끊김(40초 무음) · 하트비트 · 토큰 검증 클라이언트 · `DeviceReleased` 처리 · 소켓 레지스트리 · 프레임 검사.
- **주요 클래스 (21)**: `WebSocketConfig` · `DeviceHandshakeInterceptor` · `DeviceWebSocketHandler` · `DeviceConnection` · `ConnectionSender` · `ConnectionHandover` · `ConnectionStateWriter`/`DeviceStatusPort` · `DeviceConnectionRepository` · `DeviceDisconnectRepository` · `DisconnectChecker` · `HeartbeatHandler` · `StatusReporter` · `VerifyTokenClient` · `DeviceReleasedHandler` · `SocketRegistry` · `WsFrameGuard` · `OutputBundle`.
- **의존**: internalhttp · session(편지함) · messaging(InboxHandler) · contract-model `ws`.

### realtime.voice
- **책임**: 외부 음성 공급자 중계. `ProviderClient`(JDK WebSocket) · `ProviderPort` · `ProviderConnector`(세션당 가상 스레드) · `ProviderRequestFactory`(유일한 요청 생성 경로) · `ProviderHosts`(허용 목록) · `InputRelay` · `AudioTap` · `ProviderResponseId`.
- **의존**: runtime(TaskLauncher) · config. 공급자 클라이언트 사용은 voice 패키지로 한정(ArchUnit).

### realtime.gate
- **책임**: 출력 관문. `OutputGate` · `SegmentBuffer`(20초 상한) · `SeedDictionary`(`gate/seed-dictionary.json`) · `GateDecision` · `ClosingSequence`(CLOSING 진입 순서 BR6.10) · `ApprovedPlayback`.
- **의존**: gateway(송신) · contract-model `ws`.

### realtime.log
- **책임**: 비동기 기록. `AsyncRecorder`(531줄) · `TranscriptWriter`/`Repository`/`Row`/`Entry` · `LatencyRecorder`(LATENCY_TURN) · `SessionGapRepository`/`SessionGapWriter`(DISCONNECT · UNCONFIRMED) · `RealtimeVoiceDeletionParticipant`(부르는 곳 없음).
- **의존**: common.db · runtime.

### realtime.messaging
- **책임**: Outbox(`RealtimeOutboxWriter` · `RealtimeOutboxRepository` · `RealtimeOutboxSender` · `ControlEventRearm`), Inbox(`web/RealtimeInboxController` · `RealtimeInboxReceiver` · `RealtimeInboxDispatcher` · `RealtimeInboxRepoller` · `RealtimeInboxRepository` · `InboxHandler`), `MessagingCleanupJob`, `RealtimeMessagingDeletionParticipant`(부르는 곳 없음), 값 타입.
- **의존**: internalhttp(송신) · common.db · runtime(PeriodicJobs) · contract-model `event`.

### realtime.recording
- **책임**: PCM 녹음. `AudioRecordings` · `FileAudioRecorder`(619줄, 전용 스레드) · `AudioRecordingRepository`.

### realtime.runtime
- **책임**: 스레드 · 시계 기반. `PeriodicJobs`(이름 있는 단일 스레드 5종: outbox-sender · inbox-repoll · maintenance · recorder-tick · metric-sample) · `TaskLauncher`(가상 스레드, 시험은 inline) · `TimerClocks`(safety · 기타) · `RealtimeRuntimeConfig`(`MujoongClock` 빈).

### realtime.internalhttp
- **책임**: Realtime → Core HTTP. `InternalHttpClient`(대상 그룹별 JDK HttpClient) · `InternalAuthInterceptor`(`X-Internal-Token` · `X-Trace-Id`).

### realtime.startup
- **책임**: 기동 · 종료 순서. `ProfileGuardEnvironmentPostProcessor`(S1) · `RealtimeStartupChecks`(S1b) · `RealtimeStartupSequence`(S2 ~ S6) · `SchemaVersionCheck`(Flyway 이력 표 읽기만) · `DataClassCheck` · `DrainingLifecycle`(T1 ~ T6) · `StartupFailureLine`.

### realtime.web
- **책임**: 필터 체인과 공통 웹. `TraceIdFilter`(F1) · `ReadinessGateFilter`(F1b) · `InternalTokenFilter`(C-INT) · `InFlightRequests` · `InternalBody`(2MB) · `ApiExceptionHandler`.

### realtime.config
- **책임**: 설정 바인딩. `RealtimeProperties`(450줄) · `RealtimePropertiesConfig`.
- **비고**: `SessionTiming`(90행)은 `deviceAckTimeout` · `closingConfirm` 두 필드뿐.

### realtime.observability
- **책임**: 지표 · 스냅숏 · 상태 점검. `observability`(6, `RealtimeMetrics` 지표 이름 한 곳) + `snapshot`(2) + `health`(2).

### realtime.common
- **책임**: 공용 기반. `common.db`(`DbRetry` · `DbSessionLimits` · `Transactions` · `ClockParams` · `SqlErrors` · `DbPoolWaitMonitor`) · `common.collect`(1) · `common.scaling`(`@SingleInstanceAssumption`).

## 의존 방향 요약

교차 모듈 · 외부 의존은 `dependencies.md`에 있다. 앱 내부 핵심 방향: `web`/`gateway`/`voice` → `session` → {`dialog`, `gate`, `log`, `recording`, `messaging`} → `common.db`; `gateway`/`messaging` → `internalhttp`; 모두 → `runtime` · `config` · `contract-model`.

## Sources
- 개발자 스캔 `developer-scan.md` (Packages Found · Technical Debt Signals · Handoff)
- `find backend/realtime-activity/src/main/java -type d`로 패키지 구조 직접 확인
