# U11 판정 턴 기반 — 개발자 코드 스캔 (집중 스캔)

- 작업 기록: `261016-u11-judgment-turn` (범위 refactor · 깊이 Standard · 시험 전략 Standard · 브라운필드)
- 스캔 기준: 브랜치 `unit/u11-judgment-turn` = `main`(7e30ee8) + U11 지시서 커밋 9bcc706. 스캔한 소스는 `main`과 같다(지시서 커밋은 `docs/units/u11/`만 바꿈).
- 기준 문서: `docs/units/u11/brief.md`, 인셉션 정본 `aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/`(contract-summary C-DS3 · C-DS4 · C-DS15 · 4절 열린 질문).
- 스캔 폭: 집중 스캔. 깊게 본 경로는 스냅숏 경로 집합(`backend/realtime-activity/` · `backend/contract-model/.../contract/ws/` · `.../contract/event/` · `contracts/`) 안에서만 골랐다. 코드는 읽기만 했고 실제 공급자는 부르지 않았다.

## Developer Code Scan Results

### Scan Coverage

- **Analyzed deeply**:
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java` (3,388줄 — 필드 · `Services` · `Plan`, 공급자 사건 처리 L1557 ~ L1760, 확장점 신호 · 출력 능력 L1174 ~ L1556, 종료 의도 L1809 ~ L1905, 생각 중 · 게이트 · 공급자 송신 L2116 ~ L2375)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/PracticeSessionExtension.java`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/NoOpPracticeSessionExtension.java`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/RecordCaptureService.java`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionMailbox.java` (메시지 종류 · 안전 편지함)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionTimers.java` (`Kind`)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/gate/` (`OutputGate` · `GateDecision` · `ApprovedPlayback` · `SegmentBuffer` 머리)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/` (`ProviderPort` · `ProviderClient` 선 형식 해석 · `ProviderRequestFactory` · `PracticeInstruction` · `PracticeSpeech` · `ProviderResponseId` · `ProviderConnector` 머리 · `InputRelay` 머리)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/` (`RealtimeLunaClient` · `LunaConfiguration` · `LunaKeyPresentCondition` · `LunaPurpose` · `LunaRequest` · `LunaResult` · `LunaFailureReason` · `LunaProperties` 일부)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/content/LiveContentRegistry.java` · `LiveContentResources.java`(머리)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/dialog/StopIntentJudge.java`(머리) · `JoinTracker.java`(머리) · `DialogInstructions.java`(상수)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/` (`EvaluationProvider` · `CueProvider` · `PracticePorts` · `RuntimePracticeExtension` 머리 · `PracticeRuntimeConfiguration`)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/log/TranscriptWriter.java` · `TranscriptRepository.java`(보이는 행 조회)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/messaging/RealtimeDeletionParticipant.java`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/runtime/RealtimeRuntimeConfig.java` (`Services` 배선)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/config/RealtimeProperties.java` (`Timing` · `JudgmentTiming`)
  - `backend/realtime-activity/src/main/resources/judgment/option-sets-v1.json` · `guardrail/policy-v1.json`(머리) · `application.yml`(judgment · luna 키)
  - `backend/realtime-activity/src/main/resources/db/migration/realtime/` (V1 ~ V4 DDL 목록 · V4 본문 앞부분)
  - `backend/realtime-activity/src/test/java/kr/mujoong/realtime/testing/` (`SessionHarness` · `ManualLauncher` · `FakeProviderPort`)
  - `backend/realtime-activity/src/test/java/kr/mujoong/realtime/arch/` (`RealtimeArchTest` 프레임 경로 · 시계 · 공급자 규칙, `LunaArchTest`, `TimeLiteralScanTest` 허용 목록 규칙)
  - `backend/realtime-activity/src/test/java/kr/mujoong/realtime/config/ConfigKeyContractTest.java` (묶음 규칙)
  - `backend/realtime-activity/src/test/java/kr/mujoong/realtime/db/RealtimeSchemaIT.java` · `practice/PracticeMigrationRegistrationTest.java` (판 번호 · 표 목록 단언)
  - `backend/realtime-activity/build.gradle.kts`
  - `backend/contract-model/src/main/java/kr/mujoong/contract/event/payload/SessionRecordCaptured.java`
  - `backend/contract-model/src/main/java/kr/mujoong/contract/ws/message/View.java` · `StartSession.java`(머리)
  - `contracts/ai-io.md` 3절 · 10.1 ~ 10.7절
  - `contracts/schemas/ai-judgment-request.schema.json` · `ai-judgment-output.schema.json` · `practice-record-v2.schema.json`
  - `contracts/timing.md` 13절
  - `contracts/deviations.md` U8 · U9 편차 표
  - `contracts/events.asyncapi.yaml`(SessionRecordCaptured 설명 L103 ~ L105) · `contracts/device-ws.asyncapi.yaml`(SESSION_STATE THINKING)
- **Skimmed only**:
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/` (파일 목록 · 프레임 경로 지정만)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/messaging/` (참가자 계약 밖은 목록만)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/log/AsyncRecorder.java` (머리 주석만)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/startup/` · `observability/` · `recording/` · `health/` · `web/` · `internalhttp/`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java` · `ResponseEvaluator.java` · `JdbcPracticeRepository.java` (본문 안 읽음)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/` 나머지(`StartSessionHandler`는 고정 · 놓기 호출만, `ResultSnapshotBuilder`는 보이는 행 거르기 한 줄만)
  - `backend/realtime-activity/src/test/` 나머지 시험 165개(이름 · 태그 집계만)
  - `backend/coverage-scope/` (파일 목록과 `u9.txt` 머리), `backend/build.gradle.kts`(`verifyLunaLiveIsolation`), `backend/build-logic/`(태그 · 범위 처리 줄만) — 스냅숏 밖이라 얕게만 봤다
  - `aidlc/spaces/default/intents/261015-doc-sync-code-update/inception/contract-design/contract-summary.md` (C-DS3 · C-DS4 · C-DS15 · 3 · 4절) — 정본 문서라 참고로만 읽었다
  - `tools/fake-provider/` · `infra/` · `frontend/` — 읽지 않음

### Packages Found

- `backend/realtime-activity` — Spring Boot 앱(실행 jar `realtime-activity.jar`) — Java 21 — 기기 WSS · GPT-Live 중계 · 세션 실행기 · 출력 게이트 · Luna 판정 호출부. 운영 Java 194파일 · 28,141줄, 시험 165파일
  - `kr.mujoong.realtime.session` — 세션 직렬 실행기 · 상태 전이 · 확장점 포트 · 기록 캡처(U1 · U3 · U4b)
  - `kr.mujoong.realtime.gate` — 출력 게이트 판정 · 구간 버퍼 · 시드 사전(프레임 경로, 영속 API 금지)
  - `kr.mujoong.realtime.voice` — GPT-Live 선 형식 클라이언트 · 요청 팩토리 · 입력 중계(프레임 경로)
  - `kr.mujoong.realtime.dialog` — 종료 의도 판정 · 참여 판단 · 공백 시계 · 역할 지시문(상태 없는 판정기)
  - `kr.mujoong.realtime.practice` — U5b 레거시 연습 런타임(`RuntimePracticeExtension`, `controlsOutput()=true`)
  - `kr.mujoong.realtime.luna` — U9 Luna 호출부(`RealtimeLunaClient`, `JUDGMENT_LIVE` 하나)
  - `kr.mujoong.realtime.content` — U8 세션 콘텐츠 계획 · 판 리소스(`LiveContentRegistry`)
  - 그 밖: `gateway` · `log` · `messaging` · `recording` · `runtime` · `startup` · `observability` · `config` · `web` · `internalhttp` · `health` · `common`
- `backend/contract-model` — 라이브러리 — Java 21 — 두 앱이 함께 쓰는 계약 타입(`event` 봉투 · payload 6종, `ws` 메시지 18종 · `View`)
- `contracts/` — 계약 문서 — Markdown · YAML(AsyncAPI · OpenAPI) · JSON Schema — 코드와 대조 시험으로 묶인 정본

### Build System

- **Type**: Gradle 8.14 (Kotlin DSL) 다중 모듈, 관례 플러그인 `build-logic`의 `mujoong.java-conventions`
- **Config Files**: `backend/settings.gradle.kts`, `backend/build.gradle.kts`(루트 `verifyLunaLiveIsolation`), `backend/realtime-activity/build.gradle.kts`, `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/coverage-scope/<유닛>.txt`
- **Build Dependencies**:
  - `realtime-activity` → `contract-model`(implementation), `test-support`(testImplementation만, R-F8 · ArchUnit `NO_TEST_CODE_IN_PRODUCTION`)
  - `realtime-activity` `processResources`가 `contracts/schemas/ai-*.schema.json` · `common.schema.json`을 실행 자원 `luna/schemas/`로 복사한다 → 판정 스키마를 고치면 실행 자원도 함께 바뀐다(정본 한 곳)
  - `json-schema-validator`가 실행 범위(U9 Q3-A)
  - `lunaLiveTest`(태그 `luna-live`)는 `check` 그래프에 없고 `test`가 그 태그를 제외하며 `OPENAI_API_KEY`를 시험 JVM에 넘기지 않음을 루트 작업이 강제한다(편차 U9-c)

### APIs Discovered

- 외부 WebSocket(GPT-Live, 우리가 소비) — `voice/ProviderClient` · `contracts/ai-io.md` 3절 — 서버→공급자 6종(`session.start` · `session.input_audio.append` · `session.close` · `session.response.cancel` · `session.instructions.correct` · `session.response.create`), 공급자→서버 10종(`session.started` · `session.input_audio.speech_stopped` · `session.input_transcript.delta/done` · `session.output_transcript.delta` · `session.output_audio.delta` · `session.output.done` · `session.error` · `session.closed`). **응답 생성 확인(`response.created` 류) 사건이 없다** — 응답 id는 첫 출력 조각으로만 알 수 있다
- 외부 HTTP(Luna Responses API, 우리가 소비) — `luna/RealtimeLunaClient` — `POST /v1/responses` 한 종(구조화 출력 `json_schema`, `store=false`)
- 앱 안 Java 포트 — `session/PracticeSessionExtension` — 시점 5개(`onActive` · `onChildUtterance` · `onExtensionEvent` · `onSettle` · `practiceRecord`) + 기본 메서드 4개, 출력 능력 `PracticeOutput` 10개(`speak` · `cancelSpeech`는 기본 `false`), `ExtensionEvent.Kind` 4개(`PLAYBACK_DONE` · `PLAYBACK_FACT` · `TIMER_FIRED` · `ASYNC_RESULT`)
- 기기 WSS — `contracts/device-ws.asyncapi.yaml` — '생각 중'은 `SESSION_STATE{view: THINKING}`(`ws/message/View.THINKING`)로 보낸다
- 이벤트(Outbox) — `contracts/events.asyncapi.yaml` — `SessionRecordCaptured`(realtime 발행), `record.practice` 새 형식은 `flowVersion` 구분(편차 U8-d)
- 새 판정 포트 `JudgeClient` — 아직 없다(`LunaConfiguration` 주석: "U11 몫이라 여기에 없다")

### Frameworks & Libraries

- Java — 21 — 언어(가상 스레드 사용)
- Spring Boot — 4.1 — 앱 틀(webmvc · websocket · jdbc · data-jpa · flyway · actuator · validation)
- JDK `java.net.http.WebSocket` — JDK 21 — GPT-Live 연결(SDK 없음, J110)
- JDK `HttpClient` + `JsonCodec` — JDK 21 — Luna 호출(SDK 없음, 편차 U9-a, Jackson은 `JsonCodec` 한 곳 J139)
- json-schema-validator — `libs.versions.toml` 판 — Luna 요청 · 출력 스키마 검증
- Flyway + PostgreSQL 드라이버 — `libs.versions.toml` 판 — realtime 스키마 V1 ~ V4
- Micrometer Prometheus — `libs.versions.toml` 판 — 지표
- 시험: JUnit 5 · AssertJ · Mockito · ArchUnit · Testcontainers PostgreSQL · jackson-dataformat-yaml(설정 대조용)

### Test Coverage

- **Test Directories**: `backend/realtime-activity/src/test/java/kr/mujoong/realtime/` (arch · config · content · db · dialog · gate · gateway · log · luna · messaging · observability · practice · recording · session · startup · testing · voice · web), 시험 자원 `src/test/resources/application-test.yml`
- **Test Frameworks**: JUnit 5 · AssertJ · Mockito · ArchUnit · Testcontainers(`*IT`, `-Pmujoong.test.db=docker`). 태그 집계: `u1` 54 · `u3` 24 · `u4b` 23 · `u5b` 27 · `u8` 9 · `u9` 5 · `luna-live` 1(격리). `u11` 태그 시험은 아직 없다
- **Coverage Config**: 있음 — JaCoCo 모듈 줄 80%(CI), 유닛 범위 `backend/coverage-scope/<유닛>.txt`(`-PcoverageScope=`; 빈 목록 · 없는 파일이면 실패, J137). `u11.txt`는 아직 없다
- 실행기 시험 장치: `testing/SessionHarness`(수동 시계 `MutableClock` · `ManualTimerDriver` · `FakeProviderPort` · `ManualLauncher` · `FakeDeviceLink` · `MemoryStore`), 확장점 대역 `testing/RecordingPracticeExtension`
- 이번 스캔에서 시험은 돌리지 않았다(읽기 전용). 기준선은 Code Generation 전에 잰다

### Code Quality Indicators

- **Linting**: javac `-Xlint:all,-processing,-serial,-this-escape,-try`(경고는 빌드를 막지 않음, `-Werror` 없음), Java 포매터 · 줄 길이 검사 없음. 구조 규칙은 ArchUnit(`arch/RealtimeArchTest` · `LunaArchTest` · `U8LiveContentArchTest` · `SingleInstanceMarkerTest`)과 `TimeLiteralScanTest`(시간 리터럴 허용 목록, 쓰이지 않는 허용 항목도 실패)
- **CI/CD**: `.github/workflows/ci.yml`의 `ci-gate`(PR 필수 검사), `skeleton`(main 푸시만) — 스냅숏 밖이라 이름만 확인
- **Documentation**: 클래스 · 메서드 주석이 한국어로 촘촘하고 결정 ID(J · BR · U*-J · 편차)를 인용한다. `SessionExecutor` 머리 주석이 U1 · U3 범위까지만 설명하고 U4b · U5b 추가분은 메서드 주석에 흩어져 있다

### Technical Debt Signals

- **거대 클래스**: `session/SessionExecutor.java` 3,388줄 · 상태 필드 약 70개 · 메서드 약 130개, 이 파일만 17번 커밋. U11 · U13 · U12가 차례로 고친다(브리프 · project.md 교정). 판정 턴 상태를 이 클래스 안에 더 쌓으면 U13 · U12 충돌 면이 커진다 → 판정 턴 상태를 별도 협력 객체(실행기 스레드 전용)로 떼는 쪽이 안전하다(설계 판단은 Functional Design 몫)
- **같은 파일 안 두 코드 서식**: U5b 추가분(`speak` L1405 ~ L1428, `enqueuePracticeProvider` L2342 ~ L2361, `PracticeSessionExtension` L148 ~ L166)은 한 줄 여러 문장 · 완전 한정 이름(`java.util.…` 22곳, `kr.mujoong.realtime.voice.PracticeSpeech` 등)을 쓴다. 팀 규칙 Q7-A대로 손대는 부분만 정리 대상이다
- **공급자 송신 순서 보장 없음(동시성)**: `toProvider`(L2324 ~ L2340)는 출력권 없는 경로에서 호출마다 `launcher().launch`로 가상 스레드를 따로 띄운다(L2339). `ProviderClient.Session`의 송신 잠금은 동시 쓰기만 막고 **순서는 보장하지 않는다**. 그래서 `session.instructions.correct` 다음 `session.response.create`(C-DS15)를 `toProvider` 두 번으로 보내면 공급자가 응답 생성을 먼저 받을 수 있다. 순서가 보장되는 길은 출력권 확장용 직렬 큐 `enqueuePracticeProvider`뿐이다(`speak`가 cancel → correct → create를 한 작업 안에서 차례로 보냄, L1418 ~ L1428). 시험 장치 `ManualLauncher`는 등록 순서대로 한 스레드에서 돌려 이 경쟁을 드러내지 못한다
- **보류 중 출력이 '취소'로만 처리됨**: `outputHeld()`(L1600 ~ L1604)이 참이면 `refuseHeldResponse`(L1593)가 그 응답을 `session.response.cancel`로 취소하고 `cancelledResponses`에 넣어 이후 조각(전사 포함)을 버린다. 판정 턴 요구(FR5: 자동 응답 음성은 버리되 **전사는 `judgment_live`에 남김**, `HELD_OUTPUT_TRANSCRIPT` 사건)를 담을 경로가 없다
- **버린 출력 전사가 대화 기록 표에 남음**: 게이트 폐기도 `transcripts.aiFinal(text, false, …)`로 `realtime_transcript`에 원문을 쓰고(`TranscriptWriter` L112 ~ L122) 표시 · 스냅숏에서만 거른다(`ResultSnapshotBuilder` L115 `approvedForDisplay`). `beginOutput`(L1734 ~ L1743)은 응답 첫 조각에서 무조건 `transcripts.aiStart`를 연다(L1740). 판정 턴 보류 출력을 이 경로에 태우면 "대화 기록에 남기지 않는다"를 어긴다 → 보류 출력은 구간 버퍼 · 전사 기록기 앞에서 갈라야 한다
- **'생각 중'이 보류 중엔 안 나감**: `thinking()`(L2135 ~ L2139)은 `!outputHeld()`일 때만 THINKING을 보낸다. 판정 턴 보류를 `outputHeld()`에 그대로 얹으면 FR5 "보낼 응답이 없으면 생각 중 유지"와 부딪힌다. `listeningAgain()`(L2142)은 게이트 폐기 · 대기 문구 경로에서 LISTENING으로 되돌린다
- **늦은 비동기 결과 처리 틀 없음**: `RealtimeLunaClient.call`(L63)의 완료 콜백은 HTTP 클라이언트 스레드에서 돈다. 실행기 상태는 실행기 스레드만 바꾸므로 결과는 편지함(`mailbox.putSafety` 또는 `PracticeOutput.post`)으로 넘겨야 하고, 판정 차례 · 출력 세대 번호로 늦게 온 결과를 버려야 한다(FR5 장애 취소). 비교 기준이 될 '판정 차례 id'가 아직 없다
- **판정 출력 스키마가 선택지 집합을 묶지 않음**: `ai-judgment-output.schema.json`의 `option`은 대문자 코드 패턴만 본다. 요청 `options` 밖 코드 · `first == second` · 확률 합 1 초과는 스키마를 통과한다 → `JudgeClient` 구현이 직접 걸러 보류로 바꿔야 한다(ai-io 10.3 "선택지 밖 코드는 판정 보류")
- **Luna 빈이 키 있을 때만 생김**: `RealtimeLunaClient`는 `@Conditional(LunaKeyPresentCondition)`(`LunaConfiguration` L51)이라 시험 · 키 없는 demo에서는 빈이 없다. `JudgeClient`는 빈이 없을 때의 동작(언제나 보류 대역)과 시험 대역을 따로 정해야 한다(R-F1)
- **레거시 판정 공급자는 운영에서 늘 실패**: `PracticeRuntimeConfiguration` L46 ~ L48 기본 `EvaluationProvider`가 "판정 공급자 미연결" 실패만 돌려준다. 새 `JudgeClient`와 이름 · 역할이 겹치지만 별개로 둔다는 브리프 지시와 같은 사실이다
- **판 번호 단언이 V4에 묶임**: `RealtimeSchemaIT` L46 · L88 · L94(표 목록과 최신 판 "4"), `PracticeMigrationRegistrationTest` L22(`expectedVersion()` == "4"). V5를 더하면 이 단언이 깨진다. `SchemaVersionCheck.expectedVersion()`은 classpath 최대 판을 스스로 계산한다
- **시간 리터럴 · 시계**: 스캔 범위에서 `Instant.now()` · `System.currentTimeMillis()` 직접 사용은 없다(ArchUnit `NO_SYSTEM_TIME`). 숫자 시간 리터럴은 `SegmentBuffer.MAX_BYTES`(20초 분량 바이트, 시간 단위 아님) · `InputRelay.VOICE_ENERGY_THRESHOLD`(진폭) · `LunaTransport` L174(`Retry-After` 해석) 정도이고 허용 목록으로 관리된다
- **D_HOLD 문구가 없음**: `contracts/timing.md` 13절은 응답 준비 대기 초과 때 `D_HOLD` 되묻기를 말하지만, `DialogInstructions`에는 `Correction` 열거와 `JOIN_FIRST_QUESTION`만 있고 U8 승인 발화 시드(ai-io 10.6)에도 `D_HOLD`가 없다

## U11 접점 상세

### 1. 확장점 (C-DS3)

- 포트 위치: `kr.mujoong.realtime.session.PracticeSessionExtension`(U11 소유, U12는 구현만). `ExtensionEvent.Kind`(L88 ~ L93)에 `JUDGMENT_RESULT` · `JUDGMENT_ABSTAINED` · `HELD_OUTPUT_TRANSCRIPT` · `GUARDRAIL_BLOCKED`를 더하면 된다. 운영 · 시험 코드에 이 열거를 `case`로 다 훑는 `switch`는 없다(`case PLAYBACK_DONE` 등 검색 0건). 그래서 값을 더해도 컴파일은 깨지지 않는다.
- `PracticeOutput`에 기본 메서드 선례가 있다: `speak` · `cancelSpeech`가 `default … { return false; }`(L148 ~ L150). `requestJudgment` · `announceRoleSwitch` · `setThinking`도 같은 방식이면 레거시 `RuntimePracticeExtension` · `NoOpPracticeSessionExtension` · 시험 대역이 고칠 필요 없이 돈다.
- 실행기 쪽 구현은 내부 클래스 `PracticeOutputPort`(L1403 ~ L1556). 거절 규칙은 `practiceActive()`(ACTIVE · 정산 전 · 살아 있는 확장점)와 `refusePractice` → `PRACTICE_EXTENSION_FAILED{reason, capability}` 세션 사건.
- 비동기 결과 전달 틀: `post(name, value)` → `mailbox.putSafety(PracticeEvent(ASYNC_RESULT))` → `onPracticeEvent`(L1224) → `practiceSignal`. 판정 결과는 새 `Kind`로 같은 길을 타면 된다. 기술 일시정지 중 출력권 확장의 사건은 `pausedPracticeEvents`에 쌓였다가 풀린다.
- 한 방향 의존: 확장점은 실행기를 부르지 않고 `PracticeOutput`만 쓴다(지금 구조가 이미 그렇다).

### 2. 판정 호출 (C-DS4)

- 쓸 것: `RealtimeLunaClient.call(LunaRequest)` → `CompletionStage<LunaResult>`(던지지 않음, 실패는 `LunaException(사유)` 예외 완료). 시한 = min(마감, `live-budget`), 재시도 없음(`attemptsUsed`는 언제나 1), 호출자가 Future를 취소하면 HTTP 요청도 취소(L125).
- 요청 조립: `LunaRequest(purpose=JUDGMENT_LIVE, promptVersion, instructions, input(Map), aliases, deadline)`. `input`은 `ai-judgment-request.schema.json` 모양(`judgmentPoint` · `optionSetVersion` · `options` · `childUtterance` · `criteria`(J1 · J4 · J6은 null) · `recentTurns` ≤ 3). 보내기 전 요청 스키마 검증 실패는 `FORBIDDEN_INPUT`(시도 0).
- 판 정보: `LiveContentRegistry.plan(sessionId)`의 `judgmentSetVersion`("judgment-v1")과 `LiveContentResources.judgment()`의 지점별 선택지(순서 고정). 판 리소스는 J1 ~ J6을 모두 담고 있다(J1: PARTICIPATE · REST · DECLINE · UNCLEAR · UNREADABLE, J6: END_CLEAR · UNCLEAR · NOT_END · REST). 프롬프트 지시문(instructions) · `promptVersion`의 정본은 아직 없다.
- 보류 규칙 값: `mujoong.judgment.min-confidence` · `min-margin` 키는 아직 없다. `ConfigKeyContractTest`는 yml의 `mujoong.*` 키 집합 = 설정 레코드가 읽는 키 집합을 강제하고, `judgment` 묶음은 `TIMING_GOVERNED_GROUPS`(L73 ~ L75)에 없다(시간 값이 아니라 timing.md 대조 대상 아님). 새 묶음은 `RealtimeProperties` 루트 레코드에 한 칸을 더하면 기존 대조가 그대로 덮는다 — 별도 설정 클래스로 두면 `ConfigKeyContractTest`에 바인딩을 더해야 해 R-F2와 부딪힐 수 있다.
- ArchUnit 제약: `luna` 패키지는 `voice` · 제브 · 로그 프레임워크 · Jackson을 참조할 수 없다(`LunaArchTest` L31 ~ L37). `gate` · `voice` 패키지는 프레임 경로라 JDBC · 저장소 · Outbox를 참조할 수 없다(`RealtimeArchTest` L174 ~ L180). → `JudgeClient` 구현 · `judgment_live` 저장소는 이 두 패키지 밖(예: 새 판정 패키지)에 두어야 한다.

### 3. 게이트 보류 · 지시 뒤 통과 (FR5 · C-DS15)

- 현재 게이트는 상태가 없는 판정기(`OutputGate.inspect` 3 · 4단계, `recheck` 6단계)이고, '닫힘' 개념은 실행기의 `outputHeld()`(WAITING_CHILD · 종료 확인 중 · 확장점 `holdOutput`)가 대신한다. 판정 턴 보류는 새 조건으로 들어가야 하며, U13 제브 단계가 얹힐 자리(브리프 '맞물리는 곳' 1)는 `sealAndGate`(L2165 ~ L2206)의 `inspect` 통과 뒤 · `recheck` 앞이 자연스럽다(출력권 확장의 원문 일치 검사가 이미 그 자리에 있다).
- '지시 뒤 응답만 통과'를 가를 근거: 선 형식에 응답 생성 확인 사건이 없어 `session.response.create`와 그 뒤 첫 `responseId`를 1:1로 묶을 공식 신호가 없다. 지금 코드의 선례는 출력권 확장이 쓰는 방식 — 지시 직전에 출력 세대를 올리고(`bumpGeneration`) 이전 응답 id를 취소 목록에 넣은 뒤, 지시 이후 처음 보는 응답을 받는다(`speak` L1405 ~ L1428). 이 가정이 GPT-Live 실선 형식(FR12.2)에서도 맞는지는 실호출 확인 항목이다.
- 10초 응답 준비 대기: 실행기 시계 종류(`SessionTimers.Kind`, L26 ~ L48)에 판정용 항목이 없다. 시간 값은 이미 `RealtimeProperties.timing().judgment().responseReady()`로 읽을 수 있다(U8). 기준 시각이 될 '말 끝'은 `SpeechStopped`(L1642, tSpeechEnd) 또는 확정 전사(`InputTranscriptDone` L1660) 두 가지가 있다.
- 장애 취소: 공급자 끊김은 `providerLost()`(L1758) · `abortProviderLost()`(L1780), 기술 일시정지는 `startPause`(L2046). 판정 턴이 이 경로에서 '장애 취소'로 기록되고 늦은 결과가 버려지도록 묶어야 한다.

### 4. 종료 표현 (FR3.5)

- 등록 종료 표현은 `Plan.stopExpressions` → `StopIntentJudge`(규칙 판정, 기본 "무중아 그만할래" + 보호자 추가). 확정 전사마다 `judgeStopIntent`(L1809)가 먼저 판정한다 — 출력권 확장일 때는 확장점 전달 **앞**, 아닐 때는 확장점 전달 **뒤** `thinking()` 조건 안에서 부른다(L1676 ~ L1688). 판정 턴 중 종료 표현 → 판정 멈춤을 넣으려면 이 순서를 판정 턴 시작보다 앞에 두어야 한다.
- 자유대화 J1 · J6(FR4.8)과 기존 규칙 판정기의 겹침: J1(참여)은 `JoinTracker`(WAITING_CHILD의 규칙 분류 ACCEPT · REFUSE · TOPIC_REFUSAL · UNCLEAR), J6(종료 의도)은 `StopIntentJudge`(STOP · CONFIRM · NONE)가 이미 같은 질문을 규칙으로 답한다. 둘을 대체할지 · 보조할지(예: 규칙이 UNCLEAR · CONFIRM일 때만 Luna)는 Functional Design에서 정해야 한다.

### 5. 기록 캡처 이벤트 (C-DS2)

- 발행 경로: 실행기 정산 끝(`finishSettle` L1113) → `SessionSettlement.capture(sessionId, practiceRecord, cutoff)` → `RecordCaptureService.write`가 스냅숏 행과 `SessionRecordCaptured` Outbox를 한 트랜잭션에 쓴다(세션당 한 번). `practice` 값은 `PracticeSessionExtension.practiceRecord()`가 낸 객체 그대로다.
- 계약 타입 `SessionRecordCaptured`(contract-model)는 `record`를 `Map<String, Object>`로 해석하지 않고 실어 나른다. `flowVersion`은 `record.practice` 안의 값이라 U11이 계약 타입을 바꿀 필요가 없다(편차 U8-d). 필드를 채우는 일은 U12 몫.

### 6. 저장 (realtime V5 `judgment_live`)

- 표 관례(V4): `realtime.` 스키마 접두, `id uuid PK`, `session_id … REFERENCES realtime.realtime_activity_session(id)`, `guardian_id uuid NOT NULL` + guardian 색인(삭제 참가자용), `data jsonb` + `CHECK (jsonb_typeof(…))`.
- 삭제: `RealtimeDeletionParticipant`가 `order()` 순서로 보호자 데이터를 지운다(음성 100 → 연습 150 → 세션 200 → 메시징 900). 세션 행에 외래 키를 걸면 `judgment_live`는 200보다 앞에서 지워야 한다. 지운 건수 지도에 새 표 이름이 들어가는데, `DeletionCompleted.counts`에 더할지는 contract-summary 4절 열린 질문(U11 · U13 · U15)이다.
- 쓰기 경로: 실행기 스레드에서 DB를 직접 기다리지 않는 관례(전사는 `AsyncRecorder`)가 있다. `judgment_live` 쓰기도 비동기 · 재시도 정책을 정해야 한다.

## Handoff Summary

- **Intent-relevant finding**:
  - 확장점은 더하기만으로 넓힐 수 있다: `PracticeOutput`에 `default false` 선례(`speak` · `cancelSpeech`, `PracticeSessionExtension.java` L148 ~ L150)와 `ASYNC_RESULT` 편지함 경로(`PracticeOutputPort.post` → `onPracticeEvent` L1224)가 이미 있다.
  - 판정 호출은 `RealtimeLunaClient.call`(`luna/RealtimeLunaClient.java` L63)을 그대로 쓸 수 있고, 판 리소스 · 세션 고정은 `LiveContentRegistry`(U8)에 있다. `JudgeClient` 포트 · 보류 규칙 · `judgment_live` 표 · 설정 키(`mujoong.judgment.*`)는 아직 없다.
  - 판정 턴 보류는 지금의 '보류 = 응답 취소' 장치(`SessionExecutor` L1593 ~ L1604)와 '폐기 전사도 `realtime_transcript`에 원문 저장'(`TranscriptWriter` L112, `beginOutput` L1740) 위에 그대로 얹을 수 없다. 보류 출력은 구간 버퍼 · 전사 기록기 앞에서 갈라 `judgment_live`로만 보내야 한다.
- **Risks / follow-up**:
  1. **R1(중단 조건)**: GPT-Live 서버 쪽 전송 · 지시 주입 · 말 끝 신호 · 응답 생성 확인 신호를 코드로는 확인할 수 없다. 지금 선 형식(J110 가정)에는 `response.created` 류 사건이 없어 '지시 뒤 응답만 통과'의 근거를 세대 번호 · 취소 목록 가정에 둔다. FR12.2 실호출(`[실호출-합성]`) 결과 전에는 선 형식을 바꾸지 않는다(ADR-007). GPT-Live용 사람 실행 실호출 작업(Luna의 `lunaLiveTest` 같은 격리 작업)은 아직 없다.
  2. **송신 순서**: `toProvider`가 호출마다 따로 스레드를 띄워(`SessionExecutor` L2339) `instructions.correct` → `response.create` 순서가 보장되지 않는다. 직렬 큐(`enqueuePracticeProvider` L2342)나 한 작업 안 연속 송신(`speak` L1418 ~ L1428 방식)이 필요하다. `ManualLauncher`는 이 경쟁을 시험에서 드러내지 못한다.
  3. **'생각 중' 충돌**: `thinking()`(L2135)이 `outputHeld()`면 THINKING을 보내지 않는다. 판정 턴 보류를 그 조건에 합치면 FR5 '생각 중 유지'를 어긴다.
  4. **V5 판 단언**: `RealtimeSchemaIT`(L46 · L88 · L94)와 U5b의 `PracticeMigrationRegistrationTest`(L22)가 최신 판 "4"와 표 목록을 고정한다. V5를 더하면 이 단언을 고쳐야 하는데, R-F2(다른 단위 시험 단언 금지)와의 관계를 사람이 정해야 한다(앞 유닛 V4도 같은 방식으로 목록을 늘린 것으로 보임).
  5. **판정 출력 검증 빈틈**: 출력 스키마가 선택지 집합 · 1 · 2순위 중복을 막지 않는다. `JudgeClient`가 선택지 밖 코드 · 같은 코드 · 확률 이상을 보류로 바꿔야 한다.
  6. **Luna 빈 조건부**: `OPENAI_API_KEY`가 없으면 `RealtimeLunaClient` 빈이 없다(`LunaConfiguration` L51). 키 없는 환경의 `JudgeClient` 동작(언제나 보류)과 시험 대역을 정해야 한다(R-F1 · R-F15).
  7. **겹치는 판정기**: 자유대화 J1 · J6은 기존 규칙 판정기(`JoinTracker` · `StopIntentJudge`)와 같은 질문이다. 대체/보조 관계를 Functional Design에서 정한다. 등록 종료 표현은 판정 턴보다 먼저 처리되도록 `judgeStopIntent` 호출 순서를 맞춰야 한다.
  8. **설정 키 위치**: `mujoong.judgment.min-confidence` · `min-margin`은 `RealtimeProperties` 루트에 묶어야 `ConfigKeyContractTest`를 고치지 않고 통과한다.
  9. **D_HOLD 문구 · 다음 행동 지시 템플릿 부재**: 응답 준비 대기 초과 때의 무난한 되묻기 · 맞장구 지시문 정본이 코드 · 시드 어디에도 없다. 지시문에 판정 라벨 · 확률 · 기준을 넣지 않는 규칙(FR3.7)은 `PracticeInstruction`(500자 · 제어 문자 금지) 같은 값 타입으로 강제할 수 있다.
  10. **삭제 · 계약 열린 질문**: `judgment_live`의 삭제 참가자 순서(세션 200 앞)와 `DeletionCompleted.counts` 포함 여부(contract-summary 4절)가 정해지지 않았다.
  11. **파일 소유**: `SessionExecutor`는 U13 · U12가 이어서 고친다. 판정 턴 상태를 실행기 스레드 전용 협력 객체로 떼어 두면 뒤 단위와의 충돌 면을 줄일 수 있다.
