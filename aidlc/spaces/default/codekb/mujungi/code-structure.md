# 코드 구조 (Code Structure)

> 기준(혼합): **이번 갱신** = 커밋 `9bcc706`(= main `7e30ee8` + U11 지시서), 2026-10-16 집중 스캔(인텐트 `261016-u11-judgment-turn`). 갱신한 곳은 `contracts/` 행, contract-model `ws` · `event` 행, realtime-activity 표 전체, realtime 자원 파일, 「realtime 세션 실행기 내부 지도」 절, 코드 패턴의 표시 항목이다.
> **그 밖의 문장** = 커밋 `72d7034`(2026-10-15 전체 재스캔) 그대로이며 그 뒤 core-api · frontend · tools 변경은 다시 확인하지 않았다(STALE).
> 구성 요소별 책임은 [component-inventory.md](component-inventory.md), 빌드 의존은 [dependencies.md](dependencies.md)를 본다.

## 저장소 최상위

| 경로 | 분류 | 내용 |
|---|---|---|
| `backend/` | 앱 소스 | Gradle 멀티 프로젝트: `contract-model` · `core-api` · `realtime-activity` · `test-support` + 포함 빌드 `build-logic`, `coverage-scope/*.txt` |
| `frontend/` | 앱 소스 | Next.js 웹 앱 1개(`src/app` 라우트 · `src/parent` · `src/device` · `src/shared`), `tests/u1…u7`, `coverage-scope/*.json` |
| `tools/` | 시험 도구 | `fake-provider` · `device-simulator` · `skeleton-e2e` · `dev-edge` · `seed` · `lib`, `tests/` |
| `contracts/` | 계약 정본 | (2026-10-16 갱신) OpenAPI 3개 · AsyncAPI 2개 · JSON Schema 71개(U8이 Luna · 제브 스키마 9종 · `practice-record-v2` 등을 더함) · 규칙 문서(`timing` 233줄 · `errors` · `ports` · `ai-io` 393줄 · `deviations` 171줄) |
| `infra/` | 배포 구성 | Compose(dev · ci · demo) · nginx · migrate · postgres · backup · bundle · seed · seed-audio · host · bin · aws · ci 스크립트, `RUNBOOK.md`, `migration-approvals.txt` |
| `scripts/` | 검증 | `verify.sh`(A · B · C 단계) + `verify/*`, `cloud-setup.sh` |
| `ci/` | CI 보조 | `security-gate.py`, `security-exceptions.yml` |
| `.github/workflows/` | CI/CD | `ci.yml`, `deploy.yml` |
| `docs/` | 문서 | `units/<u>/{brief,design,report}.md`(U11 지시서 `docs/units/u11/brief.md` 포함), `erd/`, `change-history.md`, `project-description.md`, `aidlc_drafts/` |
| `aidlc/`, `.claude/` 등 | 작업 방식 | AI-DLC 기록 · 설정(스캔 제외) |

## 백엔드 패키지 구조

루트 패키지: `kr.mujoong.contract` · `kr.mujoong.core` · `kr.mujoong.realtime` · (test-support) 하위.

### contract-model (`kr.mujoong.contract`, 약 4.2천 줄)

| 패키지 | 파일 · 줄 | 내용 |
|---|---|---|
| `ws` | 22 · 996 | `WsMessageType`(17종 + `UNRECOGNIZED`) · `WsEnvelope` · `WsPayload` · `CloseCode` · `message/*`(메시지 레코드 17개 + 보기 열거 `View`: STANDBY · LISTENING · THINKING · CLOSING · UNRECOGNIZED) (2026-10-16 재확인) |
| `event` | 10 · 595 | `EventType`(6종) · `EventEnvelope` · `EventPayload` · `AggregateType` · `payload/*`(6종). `SessionRecordCaptured.record`는 `Map<String, Object>`로 해석 없이 실어 나름 (2026-10-16 재확인) |
| `status` | 7 · 202 | `SessionStatus` · `EndReason` · `EndRequestReason` · `FailureCode` · `ActivityType` · `ConnectionState` · `RegistrationStatus` |
| `log` | 5 · 956 | 구조화 로그 |
| `json` | 1 · 448 | Jackson 3 직접 사용 유일 지점 |
| `time` · `audio` · `error` · `profile` · `secret` · `trace` | 소형 | 시계 · 음성 형식 · 오류 봉투 · 프로필 · 비밀 · 추적 |

### core-api (`kr.mujoong.core`, 23 패키지, 약 23.6천 줄 — 72d7034 기준)

| 패키지 | 파일 · 줄 | 역할 |
|---|---|---|
| `activity` | 18 · 2,787 | 활동 시작 · 종료 · 현재 세션 · 세션 뷰, Realtime 내부 클라이언트 |
| `device` | 17 · 2,222 | 등록 코드 · 부트스트랩 · claim · 기기 토큰 검증 · 연결 문구 |
| `messaging` | 14 · 2,024 | Core Outbox/Inbox, 상태 · 기기 상태 이벤트 처리기 |
| `identity` | 20 · 1,938 | Google 로그인 · dev 로그인 · 시드 · 보호자/아이/동의 엔티티 |
| `notification` | 29 · 1,710 | 웹 푸시 구독 · 발송 스케줄 · VAPID 전송 |
| `practice` | 29 · 1,586 | 목표 선택 · 상황 · 연습 시작 · 내부 상황 API |
| `web` | 14 · 1,205 | 공통 웹(홈 · 정책 · 오류 처리 등) |
| `result` | 41 · 1,169 | 결과 작업 · 수준 계산 · AI 결과 스키마 · 금지 사전 · 팁 · 재시도 (압축 서식, TD-5) |
| `security` | 10 · 1,135 | 보안 설정 · 기기 쿠키 · Origin · 내부 토큰 · 속도 제한 |
| `record` | 9 · 957 | 기록 목록 · 상세 |
| `observability` | 7 · 952 | 지표 · 로그 |
| `config` | 4 · 688 | `CoreProperties`(576줄) 등 |
| `consent` · `startup` · `account` · `child` · `common` | 각 5~8 | 동의 · 기동 점검 · 계정 삭제 · 아이 · 공통 |
| `report` | 14 · 495 | 리포트 조립 · PDF 렌더 · 보존 (압축 서식) |
| `internalhttp` | 2 · 377 | 내부 HTTP 클라이언트 · 인증 인터셉터 |
| `catalog` · `textai` · `knowledge` · `health` | 소형 | 승인 콘텐츠 목록 · 텍스트 AI 포트 · 지식 그래프 포트 · 헬스 |

U9 이후 core에 Luna 호출부(`CoreLunaClient`, `contracts/deviations.md` U9-a)가 더해졌으나 이번 스캔은 core를 다시 세지 않았다.

### realtime-activity (`kr.mujoong.realtime`, 19 패키지, 194 파일 · 28,141줄 — 2026-10-16 재집계)

| 패키지 | 파일 · 줄 | 역할 |
|---|---|---|
| `session` | 34 · 7,978 | 세션 상태 · 전이 표 · 타이머(`SessionTimers.Kind` 11종) · 직렬 실행기(`SessionExecutor` 3,388줄) · 편지함(`SessionMailbox`) · 확장점 포트(`PracticeSessionExtension` · `NoOpPracticeSessionExtension`) · 정산 · 기록 캡처(`RecordCaptureService` · `ResultSnapshotBuilder`) · 내부 컨트롤러(`web/`) |
| `gateway` | 21 · 3,552 | `/ws/device` 핸들러 · 핸드셰이크 · 하트비트 · 연결 상태 · 소켓 등록 (얕게) |
| `messaging` | 19 · 1,960 | Realtime Outbox/Inbox, 삭제 참여자 계약 `RealtimeDeletionParticipant`(`order()`: 음성 100 → 연습 150 → 세션 200 → 메시징 900) |
| `log` | 9 · 1,948 | 대화 기록(`TranscriptWriter` · `TranscriptRepository` · `AsyncRecorder`) · 지연 · 세션 공백 기록 |
| `practice` | 29 · 1,609 | U5b 레거시 연습 런타임(`RuntimePracticeExtension`, `controlsOutput()=true`), 포트 `EvaluationProvider` · `CueProvider` · `PracticePorts`, 기본 빈 `PracticeRuntimeConfiguration` |
| `luna` | 15 · 1,529 | **U9 신규.** `RealtimeLunaClient`(목적 `JUDGMENT_LIVE` 하나) · `LunaTransport`(JDK `HttpClient`) · `LunaConfiguration`(키 있을 때만 빈) · `LunaKeyPresentCondition` · `LunaRequest` · `LunaResult` · `LunaException` · `LunaFailureReason` · `LunaPurpose` · `LunaProperties` · `LunaSchemas` · `LunaInputSanitizer` · `SanitizedInput` · `LunaHosts` · `LunaStartup` |
| `voice` | 10 · 1,398 | 공급자 포트(`ProviderPort`) · 선 형식 클라이언트(`ProviderClient`) · 요청 생성(`ProviderRequestFactory`) · 연결(`ProviderConnector`) · 입력 중계(`InputRelay`) · 음성 탭 · 연습 지시 값 타입(`PracticeInstruction` 500자 · 제어 문자 금지, `PracticeSpeech`) · `ProviderResponseId` |
| `dialog` | 5 · 1,208 | 역할 지시문(`DialogInstructions`: `Correction` 열거 · `JOIN_FIRST_QUESTION`) · 침묵 시계 · 종료 의도 판정(`StopIntentJudge`) · 종료 확인 · 참여 추적(`JoinTracker`). 상태 없는 판정기 |
| `observability` | 8 · 1,151 | 지표 |
| `startup` | 8 · 921 | 기동 점검 |
| `gate` | 6 · 885 | 출력 게이트(`OutputGate` 상태 없음: `inspect` · `recheck`) · 판정 결과(`GateDecision`) · 문장 버퍼(`SegmentBuffer`) · 시드 사전 · 승인 재생(`ApprovedPlayback`) · 마무리 순서. 프레임 경로(영속 API 금지) |
| `recording` | 3 · 798 | 원음 녹음(`FileAudioRecorder`) |
| `common` | 8 · 682 | 공통 |
| `web` | 6 · 560 | 웹 |
| `config` | 2 · 553 | `RealtimeProperties`(루트 레코드, `Timing` · `JudgmentTiming` 포함) |
| `content` | 2 · 504 | **U8 신규.** `LiveContentRegistry`(세션별 판 고정 · `plan(sessionId)`) · `LiveContentResources`(판정 선택지 · 가드레일 정책 판 리소스 읽기) |
| `internalhttp` | 2 · 370 | 내부 HTTP |
| `runtime` | 4 · 326 | 실행기 배선(`RealtimeRuntimeConfig`: `Services` 묶음 · 런처 · 타이머 드라이버) |
| `health` | 2 · 191 | 헬스 |

시험: `backend/realtime-activity/src/test/java/kr/mujoong/realtime/` 164 파일(arch · config · content · db · dialog · gate · gateway · log · luna · messaging · observability · practice · recording · session · startup · testing · voice · web).

### test-support

`db`(PostgreSQL 시험 DB, 971줄) · `device`(기기 시뮬레이터) · `http`(계약 대역) · `provider`(가짜 공급자 WS) · `time`(가변 Clock) · `log` · `secret`.

## 자원 파일

- core: `application{,-dev,-demo,-prod,-load-test}.yml`, `db/migration/core/V1~V7 + afterMigrate.sql`, `prompts/result/*.v1.txt`, `forbidden/result-v1.json`, `textai/u5a-forbidden-patterns.txt`, `report/`(폰트 등), `logback-spring.xml`. (72d7034 기준)
- realtime (2026-10-16 갱신): `application{,-dev,-demo,-prod}.yml`(`mujoong.timing.judgment.*` · `mujoong.luna.*` 포함, `luna.model` 기본 `${OPENAI_JUDGE_MODEL:gpt-6-luna}`), `db/migration/realtime/V1__realtime_base` · `V2__u3_voice_conversation` · `V3__u4b_session_lifecycle` · `V4__u5b_practice` + `afterMigrate.sql`, `gate/`(시드 사전), `judgment/option-sets-v1.json`(J1 ~ J6 선택지), `guardrail/policy-v1.json`, `practice/u5b-*.yml`, `logback-spring.xml`. 빌드 때 `luna/schemas/`에 `contracts/schemas/ai-*.schema.json` · `common.schema.json`이 복사된다.

## realtime 세션 실행기 내부 지도 (2026-10-16 집중 스캔)

`session/SessionExecutor.java`(3,388줄) 중 U11이 닿는 구간. 줄 번호는 커밋 `9bcc706` 기준.

| 구간 | 줄 | 내용 |
|---|---|---|
| 확장점 신호 · 출력 능력 | L1174 ~ L1556 | `practiceSignal`, `onPracticeEvent`(L1224, `ASYNC_RESULT` 등 처리), 기술 일시정지 중 `pausedPracticeEvents` 적재, 내부 클래스 `PracticeOutputPort`(L1403 ~), `practiceActive()` · `refusePractice` → `PRACTICE_EXTENSION_FAILED{reason, capability}` |
| 공급자 사건 처리 | L1557 ~ L1760 | `refuseHeldResponse`(L1593), `outputHeld()`(L1600), `SpeechStopped`(L1642) → `thinking()`, `InputTranscriptDone`(L1660) → 참여 응답 · 확장점 · 종료 의도(L1676 ~ L1688), `beginOutput`(L1734 ~ L1743, 첫 조각에 `transcripts.aiStart`), `providerLost()`(L1758) |
| 공급자 끊김 · 중단 | L1780 ~ | `abortProviderLost()` |
| 종료 의도 | L1809 ~ L1905 | `judgeStopIntent`(`StopIntentJudge` 결과 STOP · CONFIRM · NONE 처리) |
| 기술 일시정지 | L2046 ~ | `startPause` |
| 생각 중 · 게이트 · 공급자 송신 | L2116 ~ L2375 | `thinking()`(L2135) · `listeningAgain()`(L2142), `sealAndGate`(L2165 ~ L2206), `toProvider`(L2324 ~ L2340) · `enqueuePracticeProvider`(L2342 ~ L2361) |
| 정산 | L1113 | `finishSettle` → `SessionSettlement.capture` |

## 프런트엔드 구조 (72d7034 기준)

- `src/app/` 라우트: `/parent`(홈) · `/parent/login`(+ `callback`, `google` route) · `/parent/dev-login` · `/parent/consent` · `/parent/child`(+ `stop-expressions`) · `/parent/device`(+ `register`) · `/parent/free` · `/parent/practice` · `/parent/sessions/[sessionId]`(+ `done`) · `/parent/records`(+ `[sessionId]`) · `/parent/reports/new` · `/parent/reports/[reportId]` · `/parent/push-worker`(route) · `/device` · `/device/pcm-capture-worklet`(route) · `/healthz`(route).
- `src/parent/`: `screens/`(17 스크린: Login01 · LoginCallback · DevLogin · Consent02 · Child03 · Home04 · Device05 · Register05a · Free08 · Live12 · Done17 · Practice18 · Records26 · RecordDetail27 · ReportRange28 · Report30 · StopExpressions01), `api/` · `activity/` · `practice/` · `push/` · `hooks/` · `components/`.
- `src/device/`: `components/`(D0View · T19View · DebugLog), `machine/`(화면 상태 기계) · `ws/` · `mic/` · `audio/` · `runtime/` · `power/` · `api/`.
- `src/shared/`: `protocol/`(WS 계약 대응) · `timing/`(`timing-defaults.json`) · `copy/`(화면 문구) · `errors/` · `security/` · `styles/` · `toast/`.

## 코드 패턴

- **포트 + 기본 빈**: 외부 AI · 지식 그래프는 인터페이스와 `@ConditionalOnMissingBean` 기본 실패 빈(TD-1). (2026-10-16 갱신) U9 Luna는 반대로 **키가 있을 때만 실제 빈**(`@Conditional(LunaKeyPresentCondition)`)이고 기본 빈이 없다 — 호출하는 쪽이 빈 부재를 처리해야 한다.
- **삭제 참여자**: 각 패키지가 `*DeletionParticipant`를 구현해 계정 삭제에 참여. realtime은 `order()` 오름차순 실행.
- **Outbox/Inbox 쌍**: Writer · Sender · Repository · Receiver · Dispatcher · Repoller · CleanupJob · `ControlEventRearm`이 양 앱에 대칭으로 있음.
- **전이 표**: `TransitionTable` · `SessionTransitions` · `TransitionService`로 상태 전이를 표로 관리.
- **(2026-10-16 갱신) 세션당 직렬 실행기 + 편지함**: 다른 스레드는 `SessionMailbox`(안전 편지함 `putSafety` 포함)에 메시지를 넣고 실행기 스레드만 상태를 바꾼다. 공급자 · 기기 송신은 보조 작업(가상 스레드)으로 내보낸다.
- **(2026-10-16 갱신) 확장점 능력 추가는 `default` 메서드**: `PracticeOutput.speak` · `cancelSpeech`가 `default … { return false; }` — 기존 구현(`RuntimePracticeExtension` · `NoOpPracticeSessionExtension` · 시험 대역 `RecordingPracticeExtension`)을 고치지 않고 능력을 넓힌다.
- **(2026-10-16 갱신) 세대 번호 · 취소 목록**: 출력권 확장의 `speak`는 `bumpGeneration` · `cancelledResponses`로 이전 응답을 버리고 지시 뒤 첫 응답을 받는다.
- **(2026-10-16 갱신) 같은 파일 안 두 서식**: `SessionExecutor`의 U5b 추가분(`speak` L1405 ~ L1428, `enqueuePracticeProvider`)과 `PracticeSessionExtension` L148 ~ L166은 한 줄 여러 문장 · 완전 한정 이름(`java.util.…`, `kr.mujoong.realtime.voice.PracticeSpeech`)을 쓴다. 팀 규칙 Q7-A대로 손대는 부분만 정리 대상.
- **주석**: 한국어 Javadoc, 결정 ID(J · BR · U*-J · 편차) 인용. `SessionExecutor` 머리 주석은 U1 · U3 범위까지만 설명하고 U4b · U5b 추가분은 메서드 주석에 흩어져 있다.
- **화면 문구**: 프런트 화면 문구는 `shared/copy` 모듈만(ESLint `react/jsx-no-literals`).
