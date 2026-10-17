# API 문서 (API Documentation)

> 기준(혼합): **이번 갱신** = 커밋 `9bcc706`(= main `7e30ee8` + U11 지시서), 2026-10-16 집중 스캔(인텐트 `261016-u11-judgment-turn`). 갱신 · 확장한 곳은 4절(기기 WS `View`), 5절(`SessionRecordCaptured` 형식 구분), 6절(공급자 선 형식의 빈틈), 새 6A절(Luna Responses API), 새 10절(realtime 앱 안 확장점 포트), 새 11절(판정 관련 계약 문서)이다.
> **그 밖의 문장** = 커밋 `72d7034`(2026-10-15 전체 재스캔) 그대로다(STALE). 계약 정본은 `contracts/`이며, 이 문서는 코드에 실제로 있는 표면을 요약한다. 계약 소유 · 깨는 변경 규칙은 `contracts/README.md`, 편차는 `contracts/deviations.md`(171줄, U8 · U9 편차 표 포함)에 있다.

## 1. 보호자 REST (core-api, 계약 `contracts/parent-api.yaml` 34경로)

구현: `backend/core-api/src/main/java/kr/mujoong/core/**/web/*Controller.java` 등. 컨트롤러 매핑 줄 기준으로 확인했고, 본문 처리 규칙은 얕게 봤다.

| 영역 | 메서드 · 경로 |
|---|---|
| 세션 · 헬스 | `GET /api/session/csrf`, `GET /api/health`, `POST /api/dev/login`(dev 전용) |
| 인증 · 계정 | `POST /api/auth/google`, `POST /api/auth/logout`, `GET /api/me`, `DELETE /api/me` |
| 정책 · 동의 | `GET /api/policies/current`, `GET /api/consents`, `PUT /api/consents` |
| 아이 | `GET /api/child`, `POST /api/child`, `PATCH /api/child`, `GET /api/child/stop-expressions`, `PUT /api/child/stop-expressions` |
| 홈 · 기기 | `GET /api/home`, `GET /api/device`, `POST /api/device/register` |
| 활동 · 세션 | `POST /api/activities/free`, `POST /api/activities/practice`, `GET /api/sessions/current`, `GET /api/sessions/{id}`, `POST /api/sessions/{id}/end` |
| 연습 목표 | `GET /api/goal-selections`, `POST /api/goal-selections`, `PATCH /api/goal-selections/{id}`, `DELETE /api/goal-selections/{id}`, `POST /api/goal-selections/{id}/restore` |
| 결과 | `GET /api/sessions/{id}/result`, `GET /api/sessions/{id}/result/status`, `POST /api/sessions/{id}/result/retry` |
| 기록 | `GET /api/records`, `GET /api/records/{sessionId}` |
| 리포트 | `GET /api/reports/preview`, `POST /api/reports`, `GET /api/reports/{id}`, `GET /api/reports/{id}/pdf`, `POST /api/reports/{id}/saved`, `POST /api/reports/{id}/discard` |
| 웹 푸시 | `PUT /api/push-subscription`, `DELETE /api/push-subscription`, `GET /api/push-subscription/vapid-public-key` |

인증: JDBC 세션 쿠키 + CSRF, Origin 검사, 속도 제한. 오류는 공통 오류 봉투(`contracts/schemas/error-envelope.schema.json`, 코드 목록 `contracts/errors.md`).

## 2. 아이 기기 REST (core-api, 계약 `contracts/device-api.yaml` 4경로)

`DeviceBootstrapController`: `/api/device/bootstrap` · `/api/device/bootstrap/status` · `/api/device/bootstrap/claim` · `/api/device/me`. 기기 쿠키 인증(`DeviceCookieAuthFilter`).

## 3. 앱 사이 내부 HTTP (계약 `contracts/internal-api.yaml` 7경로)

| 제공 앱 | 경로 | 호출자 · 용도 |
|---|---|---|
| core-api | `POST /internal/devices/verify-token` | Realtime이 WS 핸드셰이크 때 기기 토큰 검증 |
| core-api | `/internal/devices/{id}/connection` | Realtime이 기기 연결 상태 보고 |
| core-api | `/internal/practice/scenarios` | Realtime 연습 런타임이 상황 조회 |
| core-api | `POST /internal/events` | Realtime Outbox → Core Inbox |
| realtime-activity | `POST /internal/sessions` | Core가 세션 시작. (U8 편차 U8-b) 선택 속성 `approvedSpeech`(음성 파일 없는 승인 발화) |
| realtime-activity | `POST /internal/sessions/{id}/end` | Core가 보호자 종료 전달 |
| realtime-activity | `GET /internal/sessions/{id}/view` | Core가 세션 뷰 조회 |
| realtime-activity | `POST /internal/events` | Core Outbox → Realtime Inbox |

(계약 7경로와 코드 8개 항목의 차이는 `/internal/events`가 양쪽에 같은 계약으로 있기 때문이다.) 보호: 내부 토큰(`InternalTokenFilter`, Core 쪽은 `InternalAuthInterceptor`로 송신). Realtime은 Spring Security를 쓰지 않는다.

## 4. 기기 WebSocket (realtime-activity, 계약 `contracts/device-ws.asyncapi.yaml`)

- 경로 `/ws/device`(`WebSocketConfig.DEVICE_PATH`), 허용 Origin 1개, 핸드셰이크 인터셉터가 기기 토큰 검증.
- 제어 메시지(JSON 봉투 `WsEnvelope`) 17종 — `WsMessageType`(+ 해석 불가 `UNRECOGNIZED`):
  `HELLO` · `HELLO_OK` · `HEARTBEAT` · `HEARTBEAT_ACK` · `START_SESSION` · `ACK` · `DEVICE_READY` · `SESSION_STATE` · `OUTPUT_BEGIN` · `OUTPUT_END` · `PLAY_APPROVED_CONTENT` · `PLAYBACK_MARK` · `STOP_OUTPUT` · `CLOSE_TRANSPORT` · `VOICE_SESSION_CLOSED` · `DEVICE_STATE` · `ERROR`.
- (2026-10-16 갱신) T19 표시 상태 `View`: `STANDBY` · `LISTENING` · `THINKING` · `CLOSING`(+ `UNRECOGNIZED`, 보내지 않음). '생각 중'은 `SESSION_STATE{view: THINKING}`이다. '말하는 중'은 기기가 실제 재생으로 스스로 정한다(BR9.6). 서버는 `thinking()`이 출력 보류 중이 아닐 때만 THINKING을 보내고 `listeningAgain()`으로 LISTENING에 돌아간다. 판정 턴용 새 WS 메시지는 필요 없고, 기존 `SESSION_STATE`의 보내는 조건만 바뀐다.
- 바이너리 음성 프레임: 입력 타입 바이트 `0x01`, 960바이트 = 20ms PCM16LE 24kHz 모노.
- 닫기 코드: `CloseCode`.
- 프런트 대응 구현: `frontend/src/shared/protocol/`.

## 5. 영속 이벤트 (Outbox/Inbox, 계약 `contracts/events.asyncapi.yaml`)

`EventType` 6종(봉투 `EventEnvelope`):

| 방향 | 이벤트 |
|---|---|
| Realtime → Core | `SessionStateChanged`, `SessionRecordCaptured`, `DeviceStatusChanged`, `DeletionCompleted` |
| Core → Realtime | `DeviceReleased`, `DeletionRequested` |

전달: HTTP `POST /internal/events`, 재시도 backoff(Realtime Outbox 예: 1s→30s, 최대 20회, claim lease 30s). 제어 이벤트 정책 `REARM_ON_START`.

(2026-10-16 갱신) **`SessionRecordCaptured` 형식 구분**: 계약 타입(`contract-model` `event/payload/SessionRecordCaptured`)은 `record`를 `Map<String, Object>`로 해석하지 않고 실어 나른다. `record.practice`에 `flowVersion`(`"practice-conversation-v1"`)이 있으면 새 연습 흐름 형식(`contracts/schemas/practice-record-v2.schema.json`)이고, 없으면 U5b 형식이다(편차 U8-d). `snapshotVersion` · 봉투 `schemaVersion`은 형식 구분에 쓰지 않는다. 계약상 역할: U12가 채우고 U11이 발행하고 U14가 읽는다. 발행 경로는 `finishSettle` → `SessionSettlement.capture` → `RecordCaptureService.write`(스냅숏 행 + Outbox 한 트랜잭션, 세션당 한 번)이고, `practice` 값은 확장점 `practiceRecord()` 결과 그대로라 U11이 계약 타입을 고칠 필요는 없다.

`DeletionCompleted.counts`에 판정 저장 표(`judgment_live`)를 넣을지는 열린 질문이다(U8 contract-summary 4절, U11 · U13 · U15).

## 6. 외부 음성 AI 공급자 (나가는 WebSocket, realtime-activity)

- 주소: `wss://api.openai.com/v1/live/sessions`(`application-{demo,prod}.yml`), 허용 호스트 `api.openai.com` 고정(`ProviderHosts`).
- 모델 상수 `gpt-live-1`, 음성 형식 `pcm16le/24000/1`(입출력).
- 송신 type 6종(`ProviderRequestFactory`): `session.start` · `session.input_audio.append` · `session.close` · `session.response.cancel` · `session.instructions.correct` · `session.response.create`.
- 수신 type 9종(`ProviderClient`): `session.started` · `session.input_audio.speech_stopped` · `session.input_transcript.delta` · `session.input_transcript.done` · `session.output_transcript.delta` · `session.output_audio.delta` · `session.output.done` · `session.error` · `session.closed`. (2026-10-16 확인: 개발자 스캔 보고의 "10종"은 같은 목록을 센 것이며 실제 type 수는 9다.)
- 이 형식은 프로젝트가 정의한 것(결정 J110, 편차 (aa) · (U4b-a), `contracts/ai-io.md` 3절)이며 가짜 공급자(`tools/fake-provider/server.mjs`)가 같은 형식을 흉내 낸다. 위험은 [code-quality-assessment.md](code-quality-assessment.md) TD-2.
- (2026-10-16 갱신) **응답 생성 확인 사건이 없다.** `response.created` 류 수신 사건이 없어 응답 id(`ProviderResponseId`)는 첫 출력 조각(`output_transcript.delta` · `output_audio.delta`)으로만 알 수 있다. 그래서 `session.response.create`와 그 뒤 응답을 1:1로 묶을 공식 신호가 없다(U11-R1).
- (2026-10-16 갱신) 송신 순서: `ProviderClient.Session`의 송신 잠금은 동시 쓰기만 막고 순서를 보장하지 않는다. 순서는 실행기의 직렬 큐(`enqueuePracticeProvider`)를 탈 때만 보장된다(U11-R2).

## 6A. (2026-10-16 갱신) 외부 Luna 판정 API (나가는 HTTP, realtime-activity — U9)

- 호출부: `kr.mujoong.realtime.luna.RealtimeLunaClient.call(LunaRequest)` → `CompletionStage<LunaResult>`. 던지지 않고 실패는 `LunaException(LunaFailureReason)`으로 예외 완료한다.
- 표면: OpenAI Responses API `POST {base-url}/responses`(`mujoong.luna.base-url` 기본 `https://api.openai.com/v1`), 구조화 출력 `json_schema`, `store=false`. SDK 없이 JDK `HttpClient`(`LunaTransport`) + `JsonCodec`(편차 U9-a).
- 목적: `LunaPurpose.JUDGMENT_LIVE` 하나(설정 `mujoong.luna.purpose.judgment-live`: `max-output-tokens` 4000 · `reasoning-effort` medium). 모델 `${OPENAI_JUDGE_MODEL:gpt-6-luna}`.
- 시한: min(요청 마감, `mujoong.timing.judgment.live-budget` 2s). 재시도 없음(`attemptsUsed` 언제나 1). 호출자가 Future를 취소하면 HTTP 요청도 취소한다.
- 요청: `LunaRequest(purpose, promptVersion, instructions, input(Map), aliases, deadline)`. `input`은 `ai-judgment-request.schema.json` 모양 — `judgmentPoint` · `optionSetVersion` · `options` · `childUtterance` · `criteria`(J1 · J4 · J6은 null) · `recentTurns`(≤ 3). 보내기 전 요청 스키마 검증 실패는 `FORBIDDEN_INPUT`(시도 0). 입력 정리 `LunaInputSanitizer`.
- 출력: `ai-judgment-output.schema.json` — `first` · `second` 두 칸, 각 `{option: ^[A-Z][A-Z0-9_]*$, probability: 0~1}`. 스키마는 요청의 `options` 집합 · `first ≠ second` · 확률 합을 묶지 않는다(U11-R5).
- 빈 조건: 환경 변수 `OPENAI_API_KEY`가 있을 때만 `LunaTransport` · `RealtimeLunaClient` 빈이 생긴다(`LunaConfiguration` L51). 없으면 빈 자체가 없다(U11-R6).
- 허용 호스트: demo · prod는 https + `api.openai.com`만, test는 루프백만(`LunaHosts`, BR1.4).
- 감사 로그 `LUNA_CALL` · `LUNA_STARTUP`(ai-io.md 10.8). 실호출 시험은 `./gradlew lunaLiveTest`(태그 `luna-live`)로 격리(편차 U9-c).

## 7. 외부 텍스트 AI 포트 (core-api, 72d7034 기준)

- `core.textai.TextAiProvider`(U5a 연습 상황 생성), `core.result.ResultTextProvider`(U6 결과 · 리포트 문장). 입력 · 출력 규칙은 `contracts/ai-io.md`, 포트 목록은 `contracts/ports.md`.
- 72d7034 기준 운영 어댑터 없음. 기본 빈은 각각 `TextAiException.Reason.UNAVAILABLE` · `PROVIDER_UNAVAILABLE`(TD-1). 환경 변수 이름(`MUJOONG_TEXT_AI_ENDPOINT/MODEL/API_KEY`)만 `application.yml` 주석에 있음. U9 `CoreLunaClient`가 그 뒤 들어왔으나(편차 U9-a) 이번 스캔은 core를 다시 보지 않았다.

## 8. 기타 외부 연동

- Google OAuth: 토큰 · JWKS(`core.identity.GoogleTokenClient` · `GoogleKeysClient` · `GoogleKeyCache`).
- Web Push VAPID: push2u, `core.notification.VapidPushSender` · `PinnedPushHttpClient`.
- Neo4j 지식 그래프: `core.knowledge.KnowledgeGraphLookup` 인터페이스만, `mujoong.knowledge.neo4j.enabled=false`(TD-3).

## 9. 운영 · 문서 엔드포인트

- Actuator: 관리 포트 9090(127.0.0.1), 헬스 · Prometheus 지표.
- springdoc OpenAPI: dev · test 프로필에서만 켜짐.
- 프런트 `/healthz` route.

## 10. (2026-10-16 갱신) realtime 앱 안 확장점 포트 (Java, U11 소유 · U12 구현)

`kr.mujoong.realtime.session.PracticeSessionExtension` — 세션 실행기가 연습 흐름을 붙이는 앱 안 계약(C-DS3). 실행기 → 확장점 한 방향이며 확장점은 실행기를 부르지 않는다.

| 구분 | 내용 |
|---|---|
| 시점(실행기 스레드에서 호출) | `onActive` · `onChildUtterance(Utterance)` · `onExtensionEvent(ExtensionEvent)` · `onSettle(EndContext)` · `practiceRecord()` + 기본 메서드 4개(`controlsOutput()` 등) |
| `Utterance` | 전사 순번 · 시각 · 원문 · 불확실 여부 · 재생 중 여부 |
| `ExtensionEvent.Kind` | `PLAYBACK_DONE` · `PLAYBACK_FACT` · `TIMER_FIRED` · `ASYNC_RESULT`(L88 ~ L93). U11 계약 요약이 말하는 `JUDGMENT_RESULT` · `JUDGMENT_ABSTAINED` · `HELD_OUTPUT_TRANSCRIPT` · `GUARDRAIL_BLOCKED`는 아직 없다 |
| 출력 능력 `PracticeOutput` | `sessionId` · `playApproved(code)` · `instructProvider(text)` · `requestResponse()` · `cancelResponse()` · `holdOutput(bool)` · `schedule(name, delay)` · `cancel(name)` · `post(name, value)`(아무 스레드) · `speak(PracticeSpeech)`(`default false`) · `cancelSpeech(UUID)`(`default false`) |
| 거절 규칙 | ACTIVE · 정산 전 · 살아 있는 확장점일 때만 받음(`practiceActive()`), 아니면 거짓 + 세션 사건 `PRACTICE_EXTENSION_FAILED{reason, capability}` |
| 구현 | 운영: `NoOpPracticeSessionExtension`(무동작) · `practice.RuntimePracticeExtension`(U5b, `controlsOutput()=true`). 시험: `testing.RecordingPracticeExtension` |
| 계약에 없는 능력 | `requestJudgment` · `announceRoleSwitch` · `setThinking`(U11 계약 요약) — `default` 메서드로 더하면 기존 구현을 고칠 필요가 없다 |

## 11. (2026-10-16 갱신) 판정 · 시간 관련 계약 문서 지도

| 문서 | 절 | U11 관련 내용 |
|---|---|---|
| `contracts/ai-io.md` | 3절 | 공급자 선 형식(J110 가정) |
| `contracts/ai-io.md` | 10.1 ~ 10.7 | U8 스키마 9종 · 금지 필드 · 목적 → 호출 경로 → 재시도 분류 · 판 리소스와 세션 고정 · 원문 일치와 제브 생략 · 승인 발화 · 비밀 이름 |
| `contracts/ai-io.md` | 10.8 ~ 10.10 | U9 감사 로그 · 설정 · 호출 방식 · 실패 사유 |
| `contracts/schemas/ai-judgment-request.schema.json` · `ai-judgment-output.schema.json` | — | 판정 요청 · 출력 모양(출력은 선택지 집합을 묶지 않음) |
| `contracts/schemas/practice-record-v2.schema.json` | — | 새 연습 흐름 기록 형식(`flowVersion` 필수) |
| `contracts/timing.md` | 13절 | U8 새 키 7개: `judgment.live-budget`(2s) · `judgment.response-ready`(10s) · `guardrail.jev-budget`(800ms) · `conversation.no-response-wait`(10s) · `conversation.focus-max`(90s) · `conversation.goal-active-limit`(10m) · `knowledge.graph-budget`. 응답 준비 대기 초과 때 `D_HOLD` 되묻기를 말하지만 그 문구 정본은 코드 · 시드에 없다(U11-R8) |
| `contracts/deviations.md` | U8 · U9 편차 표 | U8-a ~ U8-e, U9-a ~ U9-c |
| `contracts/events.asyncapi.yaml` | `SessionRecordCaptured` 설명 | 형식 구분(위 5절) |
| `contracts/device-ws.asyncapi.yaml` | `SESSION_STATE` | `STANDBY · LISTENING · THINKING · CLOSING` |
