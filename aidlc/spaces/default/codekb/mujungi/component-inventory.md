# 구성 요소 목록 (Component Inventory)

> 기준(혼합): **이번 갱신** = 커밋 `9bcc706`(= main `7e30ee8` + U11 지시서), 2026-10-16 집중 스캔(인텐트 `261016-u11-judgment-turn`). realtime-activity 절 · contract-model `ws` · `event` · 새 계약 구성 요소를 갱신 · 확장했다. **그 밖의 항목** = 커밋 `72d7034`(2026-10-15 전체 재스캔) 그대로다(STALE).
> 아래 H3 제목이 구성 요소 이름이며, [reverse-engineering-timestamp.md](reverse-engineering-timestamp.md)의 `analyzed.components`는 이 제목과 글자 그대로 같다.
> 표시: **[깊게 10-16]** = 2026-10-16 집중 스캔에서 파일을 읽어 확인. **[깊게 10-15 · 미재확인]** = 2026-10-15 전체 재스캔에서 읽었으나 이번에 다시 확인하지 않음(저장소 판정 STALE이라 범위 블록에서 `shallow`로 내림). **[얕게]** = 목록 · 매핑 · 크기만 확인.
> 건강 표시(설계 담당 판단): **건강** · **주의**(변경 충돌 · 숨은 제약이 있음) · **위험**(이번 작업이 그대로 기대면 요구를 어김).

## 빌드 · 공통

### build-logic
- **[깊게 10-15 · 미재확인]** `backend/build-logic/`, `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/gradle.properties`, wrapper, 네 모듈 `build.gradle.kts`.
- 책임: 규약 플러그인 `mujoong.java-conventions` — Java 21 툴체인, Spring Boot BOM, 보안 판 고정(Jackson 2/3 · Tomcat), 시험 태그, JaCoCo 줄 80% 하한, `-PcoverageScope=<유닛>` 범위 측정.
- (2026-10-16 확인) `backend/realtime-activity/build.gradle.kts`: `processResources`가 `contracts/schemas/ai-*.schema.json` · `common.schema.json`을 `luna/schemas/`로 복사, `json-schema-validator` 실행 범위, `lunaLiveTest`(태그 `luna-live`)는 `check` 그래프 밖. 루트 `verifyLunaLiveIsolation`이 `test`의 태그 제외와 키 비전달을 강제(루트는 얕게).
- 의존: 모든 백엔드 모듈이 적용.

### code-style-config
- **[깊게 10-15 · 미재확인]** `.prettierrc.json`(printWidth 100), `frontend/eslint.config.mjs`.
- 책임: 프런트 린트 규칙(경계 · 문구 · getUserMedia · 서비스 워커 제한). Prettier 설정은 있으나 CI 검사 없음(TD-5).

### ci-workflow
- **[깊게 10-15 · 미재확인]** `.github/workflows/ci.yml`. **[얕게]** `.github/workflows/deploy.yml`, `ci/`.
- 책임: 변경 감지 · 백엔드 check · 유닛 범위 커버리지 · 의존 취약점 · 프런트 · tools · 비밀 · infra 불변식 · 이미지 · 스켈레톤. 세부는 [code-quality-assessment.md](code-quality-assessment.md).

### app-config
- **[깊게 10-15 · 미재확인]** `backend/core-api/src/main/resources/application.yml`, `backend/realtime-activity/src/main/resources/application{,-prod,-demo}.yml`. **[얕게]** 나머지 프로필 yml.
- 책임: 포트(8080 · 8081 · 관리 9090), DB 연결(스키마별 역할), Flyway 끔, `mujoong.timing.*` 시간 값, Outbox/Inbox 재시도, 공급자 URL(demo · prod).
- realtime `application.yml`은 2026-10-16에 다시 읽었다 → [realtime.live-resources](#realtimelive-resources) 참고.

### db-migrations
- **[깊게 10-15 · 미재확인]** `backend/core-api/src/main/resources/db/migration/core/`(V1~V7 + `afterMigrate.sql`).
- **[깊게 10-16]** `backend/realtime-activity/src/main/resources/db/migration/realtime/`(V1 ~ V4 + `afterMigrate.sql`, V4 본문 앞부분).
- 책임: 스키마 정본. 실행은 `infra/migrate` 이미지. 다음 번호 core V8 · realtime V5(TD-7).
- realtime V4 표 관례: `realtime.` 스키마 접두 · `id uuid PK` · `session_id … REFERENCES realtime.realtime_activity_session(id)` · `guardian_id uuid NOT NULL` + guardian 색인 · `data jsonb` + `CHECK (jsonb_typeof(…))`.
- 건강: **주의** — V5를 더하면 `RealtimeSchemaIT`(표 목록 · 최신 판 "4")와 `PracticeMigrationRegistrationTest`(`expectedVersion()` == "4") 단언이 깨진다(U11-R4).

## 계약

### contracts.http-openapi
- **[깊게 10-15 · 미재확인]** `contracts/README.md`, `contracts/parent-api.yaml`(34경로), `contracts/device-api.yaml`(4경로), `contracts/internal-api.yaml`(7경로).
- 책임: HTTP 계약 정본, 소유 · 깨는 변경 규칙.

### contracts.ai-events-rules
- **[깊게 10-16]** `contracts/ai-io.md`(3절 · 10.1 ~ 10.7절), `contracts/schemas/ai-judgment-request.schema.json` · `ai-judgment-output.schema.json` · `practice-record-v2.schema.json`, `contracts/timing.md` 13절, `contracts/deviations.md` U8 · U9 편차 표, `contracts/events.asyncapi.yaml`(`SessionRecordCaptured` 설명), `contracts/device-ws.asyncapi.yaml`(`SESSION_STATE` 보기 값).
- **[얕게]** 나머지 스키마(71개 중), `errors.md`, `ports.md`, ai-io.md 1 · 2 · 4 ~ 8 · 10.8 ~ 10.10절.
- 책임: AI 입출력 · 공급자 선 형식 · 판 리소스 · 시간 키 · 이벤트 형식 구분 · 편차의 정본. 판정 스키마는 빌드 때 realtime 실행 자원으로 복사된다(정본 한 곳).
- 건강: **주의** — 판정 출력 스키마가 선택지 집합 · 1 · 2순위 중복 · 확률 합을 묶지 않음(U11-R5), `D_HOLD` 문구 정본 없음(U11-R8), 응답 생성 확인 사건 없음(U11-R1).

### contract-model.ws
- **[깊게 10-16]** `message/View.java`, `message/StartSession.java`(머리). **[깊게 10-15 · 미재확인]** 나머지 `backend/contract-model/src/main/java/kr/mujoong/contract/ws/`(2026-10-16에는 파일 목록 · `WsMessageType` 값만 다시 봄).
- 책임: 기기 WS 메시지 17종(+ `UNRECOGNIZED`) · 봉투 · 닫기 코드 · 보기 `View`(STANDBY · LISTENING · THINKING · CLOSING). 사용처: realtime gateway · session, test-support 기기 시뮬레이터.
- 건강: **건강** — 판정 턴은 새 WS 메시지 없이 `SESSION_STATE{view: THINKING}`의 보내는 조건만 바꾼다.

### contract-model.event
- **[깊게 10-16]** `payload/SessionRecordCaptured.java`. **[깊게 10-15 · 미재확인]** 나머지 `backend/contract-model/src/main/java/kr/mujoong/contract/event/`(파일 목록만 다시 봄).
- 책임: 영속 이벤트 6종 · 봉투 · 집합 종류 · 방향. `SessionRecordCaptured.record`는 `Map<String, Object>`로 해석 없이 운반 → `record.practice.flowVersion` 추가에 계약 타입 변경 불필요(편차 U8-d).
- 건강: **건강**.

### contract-model.status
- **[깊게 10-15 · 미재확인]** `backend/contract-model/src/main/java/kr/mujoong/contract/status/`.
- 책임: `SessionStatus`(12) · `EndReason` · `EndRequestReason` · `FailureCode` · `ActivityType` · `ConnectionState` · `RegistrationStatus`, 해석 불가 값은 `UNRECOGNIZED`.

### contract-model.support
- **[얕게]** `audio` · `error` · `json` · `log` · `profile` · `secret` · `time` · `trace`.
- 책임: 음성 형식 · 오류 봉투 · JSON 매퍼 · 구조화 로그 · 시계/타이머.

## realtime-activity

### realtime.ws-gateway-config
- **[깊게 10-15 · 미재확인]** `realtime/gateway/WebSocketConfig.java`.
- 책임: `/ws/device` 등록, 허용 Origin, 핸드셰이크 인터셉터 연결.

### realtime.gateway
- **[얕게]** `realtime/gateway/`(21 파일 · 3,552줄): `DeviceWebSocketHandler` · `DeviceHandshakeInterceptor` · `VerifyTokenClient` · `HeartbeatHandler` · `ConnectionHandover` · `DisconnectChecker` · `StatusReporter` · `DeviceReleasedHandler` 등. 2026-10-16에는 파일 목록 · 프레임 경로 지정만 봤다.
- 의존: Core 내부 HTTP(토큰 검증 · 연결 보고), session.

### realtime.session
- **[깊게 10-16]** `SessionExecutor.java`(필드 · `Services` · `Plan`, L1174 ~ L1760 · L1809 ~ L1905 · L2116 ~ L2375), `PracticeSessionExtension.java`, `NoOpPracticeSessionExtension.java`, `RecordCaptureService.java`, `SessionMailbox.java`, `SessionTimers.java`(`Kind`). **[얕게]** 나머지 `realtime/session/`(34 파일 · 7,978줄 중; `StartSessionHandler`는 고정 · 놓기 호출만, `ResultSnapshotBuilder`는 보이는 행 거르기 한 줄만).
- 책임: 세션 상태 기계 · 세션당 직렬 실행기 · 편지함 · 타이머 · 출력 조율(보류 · 게이트 · 생각 중) · 공급자 송신 · 종료 정리 · 기록 캡처 · 확장점 포트 소유.
- 확장점 포트: 시점 5개 + 기본 메서드 4개, 출력 능력 10개(`speak` · `cancelSpeech`는 `default false`), `ExtensionEvent.Kind` 4개. 운영 구현은 `NoOpPracticeSessionExtension` · `RuntimePracticeExtension`.
- 의존: voice · gate · dialog · content · log · practice(포트 구현) · messaging(Outbox) · runtime.
- 건강: **위험** — 3,388줄 · 상태 필드 약 70 · 메서드 약 130, 이 파일만 17번 커밋. U11 · U13 · U12가 차례로 고친다(U11-R10). 보류 = 응답 취소(U11-R3), '생각 중' 보류 조건(U11-R11), 출력권 없는 송신 순서(U11-R2), 늦은 비동기 결과 거르기 틀 없음(U11-R12).

### realtime.voice-provider
- **[깊게 10-16]** `ProviderPort` · `ProviderClient`(선 형식 해석) · `ProviderRequestFactory` · `PracticeInstruction` · `PracticeSpeech` · `ProviderResponseId` · `ProviderConnector`(머리) · `InputRelay`(머리). **[깊게 10-15 · 미재확인]** `ProviderHosts.java`. **[얕게]** `AudioTap`.
- 책임: 음성 공급자 WS 연결 · 송신 6종 · 수신 9종 · 허용 호스트 고정. `PracticeInstruction`은 500자 상한 · 제어 문자 금지 값 타입.
- 제약: `voice`는 프레임 경로라 JDBC · 저장소 · Outbox를 참조할 수 없다(`RealtimeArchTest` L174 ~ L180).
- 건강: **위험** — 선 형식 자체 정의(TD-2), 응답 생성 확인 사건 없음(U11-R1), 송신 잠금이 순서를 보장하지 않음(U11-R2).

### realtime.luna
- **[깊게 10-16]** `RealtimeLunaClient` · `LunaConfiguration` · `LunaKeyPresentCondition` · `LunaPurpose` · `LunaRequest` · `LunaResult` · `LunaFailureReason` · `LunaProperties`(일부). **[얕게]** `LunaTransport` · `LunaSchemas` · `LunaInputSanitizer` · `SanitizedInput` · `LunaHosts` · `LunaStartup` · `LunaException`(15 파일 · 1,529줄 중).
- 책임: U9 Luna 판정 호출부. `call(LunaRequest)` → `CompletionStage<LunaResult>`(던지지 않음), 목적 `JUDGMENT_LIVE` 하나, 시한 min(마감, `live-budget`), 재시도 없음, Future 취소 = HTTP 취소, 요청 스키마 검증(`FORBIDDEN_INPUT`), 입력 정리, 감사 로그.
- 제약: `luna` 패키지는 `voice` · 제브 · 로그 프레임워크 · Jackson을 참조할 수 없다(`LunaArchTest` L31 ~ L37). 빈은 `OPENAI_API_KEY`가 있을 때만(L51).
- 소비자: 아직 없음(`LunaConfiguration` 주석 "U11 몫이라 여기에 없다").
- 건강: **주의** — 키 없는 환경 · 시험에서 빈이 없어 호출 쪽이 대역 · '언제나 보류'를 정해야 한다(U11-R6). 완료 콜백이 HTTP 클라이언트 스레드에서 돈다.

### realtime.content
- **[깊게 10-16]** `realtime/content/LiveContentRegistry.java` · `LiveContentResources.java`(머리).
- 책임: U8 세션 콘텐츠 계획 · 판 리소스. `plan(sessionId)`가 세션별 판(`judgmentSetVersion` "judgment-v1" 등)을 고정, `LiveContentResources.judgment()`가 지점별 선택지(순서 고정)를 준다. 승인 발화(`approvedSpeech`) 겹침 처리(편차 U8-b).
- 건강: **건강** — 프롬프트 지시문 · `promptVersion` 정본은 아직 없다.

### realtime.dialog
- **[깊게 10-16]** `DialogInstructions.java`(상수: `Correction` 열거 · `JOIN_FIRST_QUESTION`), `StopIntentJudge.java`(머리), `JoinTracker.java`(머리). **[얕게]** `SilenceClock` · `StopConfirmFlow`.
- 책임: 역할 지시문 · 침묵 시계 · 종료 의도 규칙 판정(STOP · CONFIRM · NONE, 등록 표현 기본 "무중아 그만할래" + 보호자 추가) · 종료 확인 · 참여 규칙 분류(ACCEPT · REFUSE · TOPIC_REFUSAL · UNCLEAR). 상태 없는 판정기.
- 이전 이름: 2026-10-15 판의 `realtime.dialog-instructions`(DialogInstructions만 깊게)를 이 항목으로 넓혔다.
- 건강: **주의** — 자유대화 J1(참여) · J6(종료 의도)은 `JoinTracker` · `StopIntentJudge`와 같은 질문이라 대체/보조 관계를 정해야 한다(U11-R7). `D_HOLD` 되묻기 문구가 없다(U11-R8).

### realtime.gate
- **[깊게 10-16]** `OutputGate` · `GateDecision` · `ApprovedPlayback` · `SegmentBuffer`(머리). **[얕게]** `SeedDictionary` · `ClosingSequence`, 자원 `gate/`.
- 책임: 공급자 출력 문장 단위 판정(`inspect` 3 · 4단계, `recheck` 6단계, 상태 없음) · 문장 구간 버퍼 · 승인 재생 · 마무리 순서. '닫힘'은 게이트가 아니라 실행기 `outputHeld()`가 대신한다.
- 제약: 프레임 경로라 JDBC · 저장소 · Outbox를 참조할 수 없다.
- 건강: **주의** — U13 제브 단계가 `sealAndGate`의 `inspect` 뒤 · `recheck` 앞에 얹힌다(U11과 같은 메서드 주변).

### realtime.practice-runtime-config
- **[깊게 10-16]** `realtime/practice/PracticeRuntimeConfiguration.java`.
- 책임: `mujoong.timing.practice` 바인딩, 기본 `EvaluationProvider`(L46 ~ L48, 실패 future "판정 공급자 미연결") · `CueProvider.UNAVAILABLE` · Core 상황 클라이언트. 위험 TD-1.
- 건강: **주의** — 레거시 `EvaluationProvider`는 새 `JudgeClient`와 이름 · 역할이 겹치지만 별개로 둔다(지시서).

### realtime.practice
- **[깊게 10-16]** `EvaluationProvider` · `CueProvider` · `PracticePorts` · `RuntimePracticeExtension`(머리). **[얕게]** 나머지 `realtime/practice/`(29 파일 · 1,609줄; `PracticeProtocol` · `ResponseEvaluator` · `JdbcPracticeRepository` 본문 안 읽음) + 자원 `practice/u5b-*.yml`.
- 책임: U5b 레거시 연습 런타임. 확장점 포트를 구현하며 `controlsOutput()=true`(출력권 소유) → 모든 공급자 송신이 직렬 큐를 탄다.

### realtime.log-recording
- **[깊게 10-16]** `log/TranscriptWriter.java` · `log/TranscriptRepository.java`(보이는 행 조회). **[얕게]** `log/AsyncRecorder.java`(머리 주석만) · 나머지 `log/`, `recording/`(`FileAudioRecorder`).
- 책임: 대화 기록(`aiStart` · `aiFinal(text, approved, at)` · `childFinal`) · 지연 · 세션 공백 · 원음 녹음. 실행기는 DB를 기다리지 않고 `AsyncRecorder`로 쓴다.
- 사실: 게이트 폐기 문장도 `aiFinal(text, false, …)`로 `realtime_transcript`에 원문이 저장되고(L112 ~ L122) 표시 · 스냅숏에서만 걸러진다(`ResultSnapshotBuilder` L115 `approvedForDisplay`).
- 건강: **위험** — 판정 턴 보류 출력을 이 경로에 태우면 "대화 기록에 남기지 않는다"를 어긴다(U11-R3).

### realtime.messaging
- **[깊게 10-16]** `messaging/RealtimeDeletionParticipant.java`. **[얕게]** 나머지 `realtime/messaging/`(19 파일).
- 책임: Realtime Outbox/Inbox 쌍, 삭제 참여자 계약(`order()` 오름차순: 음성 100 → 연습 150 → 세션 200 → 메시징 900, 반환 '이름 → 건수' 지도, 멱등).
- 건강: **주의** — `judgment_live`가 세션 행에 외래 키를 걸면 200보다 앞 순서여야 하고, `DeletionCompleted.counts` 포함 여부가 열린 질문(U11-R13).

### realtime.platform
- **[깊게 10-16]** `config/RealtimeProperties.java`(`Timing` · `JudgmentTiming`), `runtime/RealtimeRuntimeConfig.java`(`Services` 배선). **[얕게]** `common` · `startup` · `observability` · `web` · `internalhttp` · `health` · 나머지 `runtime`.
- 책임: 설정 루트 레코드 · 실행기 배선 · 기동 · 지표 · 웹 · 내부 HTTP · 헬스.
- 사실: `mujoong.judgment.min-confidence` · `min-margin` 키는 아직 없다. `ConfigKeyContractTest`는 yml의 `mujoong.*` 키 집합 = 설정 레코드가 읽는 키 집합을 강제한다.
- 건강: **주의** — 새 키 묶음은 `RealtimeProperties` 루트에 한 칸으로 더해야 기존 대조가 그대로 덮는다(U11-R8).

### realtime.live-resources
- **[깊게 10-16]** `backend/realtime-activity/src/main/resources/judgment/option-sets-v1.json`(J1 ~ J6), `guardrail/policy-v1.json`(머리), `application.yml`(`mujoong.timing.judgment` · `guardrail` · `conversation`, `mujoong.luna.*`).
- 책임: U8 판 리소스와 realtime 설정 정본. J1: PARTICIPATE · REST · DECLINE · UNCLEAR · UNREADABLE, J6: END_CLEAR · UNCLEAR · NOT_END · REST. `judgment.live-budget` 2s · `response-ready` 10s.
- 건강: **건강**.

### realtime.test-harness
- **[깊게 10-16]** `testing/SessionHarness.java` · `ManualLauncher.java` · `FakeProviderPort.java`, `arch/RealtimeArchTest.java` · `LunaArchTest.java` · `TimeLiteralScanTest.java`, `config/ConfigKeyContractTest.java`, `db/RealtimeSchemaIT.java`, `practice/PracticeMigrationRegistrationTest.java`. **[얕게]** 나머지 시험 164 파일(이름 · 태그 집계만).
- 책임: 실행기 시험 장치(수동 시계 `MutableClock` · `ManualTimerDriver` · `FakeProviderPort` · `ManualLauncher` · `FakeDeviceLink` · `MemoryStore`), 확장점 대역 `RecordingPracticeExtension`, 구조 규칙(ArchUnit: 프레임 경로 · 시계 · 공급자 · Luna 격리 · 시험 코드 금지), 시간 리터럴 허용 목록(쓰이지 않는 허용 항목도 실패), 설정 키 대조, 스키마 판 단언.
- 건강: **주의** — `ManualLauncher`는 등록 순서대로 한 스레드에서 돌려 송신 순서 경쟁을 드러내지 못한다(U11-R2). 판 단언이 V4에 묶임(U11-R4).

## core-api (72d7034 기준, 미재확인)

### core.textai-config
- **[깊게 10-15 · 미재확인]** `core/textai/TextAiConfiguration.java`. **[얕게]** 나머지 `core/textai/`(`TextAiProvider` · `TextAiGateway` · `ScenarioPrompt` · `ForbiddenExpressionPolicy` · `TextAiProperties` 등).
- 책임: 기본 `unavailableTextAiProvider` → `UNAVAILABLE`. 위험 TD-1.

### core.result-text-config
- **[깊게 10-15 · 미재확인]** `core/result/ResultTextConfiguration.java`.
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
- **[얕게]** `backend/test-support/src/main/`: 시험 DB · 가변 Clock · 계약 대역 HTTP · 가짜 공급자 WS · 기기 시뮬레이터 · 로그 수집. testImplementation 전용(R-F8 · ArchUnit `NO_TEST_CODE_IN_PRODUCTION`).

## frontend (72d7034 기준, 미재확인)

### frontend.build-config
- **[깊게 10-15 · 미재확인]** `frontend/package.json`, `frontend/next.config.ts`(standalone 출력), `frontend/vitest.config.ts`.

### frontend.app-routes
- **[깊게 10-15 · 미재확인]** `frontend/src/app/`: `/parent/*` 라우트 15페이지 + route 2개(`login/google`, `push-worker`), `/device` 페이지 + `pcm-capture-worklet` route, `/healthz`, 레이아웃 2개.

### frontend.parent
- **[얕게]** `frontend/src/parent/`: 17 스크린 · api · activity · practice · push · hooks · components. react-query 폴링.

### frontend.device
- **[얕게]** `frontend/src/device/`: D0View · T19View · 상태 기계 · WS 클라이언트 · 마이크 · 오디오 워크릿 · 전원.

### frontend.shared
- **[얕게]** `frontend/src/shared/`: protocol · timing(`timing-defaults.json`) · copy · errors · security · styles · toast.

## 도구 · 운영

### tools.package
- **[깊게 10-15 · 미재확인]** `tools/package.json`. **[얕게]** `tools/fake-provider/` · `device-simulator/` · `skeleton-e2e/` · `dev-edge/` · `seed/` · `lib/` · `tests/`.
- 책임: 가짜 공급자(같은 `session.*` 선 형식) · 기기 시뮬레이터 · 끝-끝 스켈레톤 · Nginx 대역.

### infra
- **[얕게]** `infra/`: Compose(dev · ci · demo) · nginx · migrate · postgres · backup · bundle · seed · seed-audio · host · bin · aws · ci, `RUNBOOK.md`, `migration-approvals.txt`.

### scripts
- **[얕게]** `scripts/verify.sh` + `scripts/verify/*`(A 점검 · B 스택 · C 스켈레톤 턴), `cloud-setup.sh`.
