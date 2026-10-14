# API 문서 (API Documentation)

> 기준 커밋 `3748083`. 계약 정본은 `contracts/`이며 이 문서는 Realtime이 제공 · 소비하는 면만 요약한다. 필드 단위 정의는 각 계약 파일을 따른다.

## 1. API 면 한눈에

| 면 | 방향 | 전송 | 계약 정본 | Realtime 코드 |
|---|---|---|---|---|
| 내부 HTTP (Realtime 제공) | Core → Realtime | HTTP + `X-Internal-Token` | `contracts/internal-api.yaml` | `session/web/SessionInternalController` · `messaging/web/RealtimeInboxController` |
| 내부 HTTP (Core 제공) | Realtime → Core | HTTP + `X-Internal-Token` · `X-Trace-Id` | `contracts/internal-api.yaml` | `internalhttp/InternalHttpClient` · `gateway/VerifyTokenClient` · `gateway/StatusReporter` · `messaging/RealtimeOutboxSender` |
| 기기 WSS | 기기 ↔ Realtime | WebSocket `/ws/device` | `contracts/device-ws.asyncapi.yaml` · `schemas/ws-messages.schema.json` · `ws-envelope.schema.json` | `gateway/*` · `contract-model` `contract.ws` |
| 영속 이벤트 | 양방향 | Outbox → HTTP → Inbox | `contracts/events.asyncapi.yaml` · `schemas/event-envelope.schema.json` · `event-payloads.schema.json` | `messaging/*` · `contract-model` `contract.event` |
| 공급자 WSS (외부) | Realtime → 공급자 | JDK WebSocket | `contracts/ai-io.md` 2 · 3절 | `voice/ProviderClient` · `ProviderRequestFactory` |
| 관리 · 지표 | 운영자 | HTTP 127.0.0.1:9090 | — | Actuator `health` · `prometheus` |

## 2. 내부 HTTP — Realtime 제공 (4개)

공통: 필터 체인 `TraceIdFilter`(F1) → `ReadinessGateFilter`(F1b, 준비 전 거절) → `InternalTokenFilter`(C-INT, `X-Internal-Token`). 본문 상한 2MB(`InternalBody`). 오류는 `ApiExceptionHandler`가 `error-envelope.schema.json` 모양으로 낸다(`contracts/errors.md`).

| 연산 | 메서드 · 경로 | 요청 · 응답 스키마 | 현재 동작 메모 |
|---|---|---|---|
| startSession | `POST /internal/sessions` | `internal-session-start-request` / `internal-session-start-response` | 세션 패키지 수신, 키 멱등. `approvedContent` 필수 키는 CLOSING · WAITING_FILLER 둘, 그 밖은 `additionalProperties`로 허용. `SessionExecutor.Plan`은 `closing` · `waitingFiller`만 담고 `activityType`을 싣지 않음 |
| endSessionInternal | `POST /internal/sessions/{sessionId}/end` | `internal-session-end-request` / `internal-session-end-response` | J54 응답표. `EndRequestReason` = GUARDIAN_STOP · CONSENT_WITHDRAWN · CORE_ABANDONED. 현재 `CONSENT_WITHDRAWN`은 400 VALIDATION_FAILED. 알 수 없는 ID의 CORE_ABANDONED는 `TombstoneMap`에 묘비를 남김 |
| getSessionViewInternal | `GET /internal/sessions/{sessionId}/view?sinceSeq` | `internal-session-view` | 말풍선(전사) · 끊김 구간. `technicalPause` · `silenceWarning`은 현재 늘 `null` |
| receiveEvent | `POST /internal/events` | `event-envelope` + `event-payloads` | Realtime Inbox. 저장 후 디스패처가 배분 |

## 3. 내부 HTTP — Realtime이 호출 (Core 제공, 3개)

| 메서드 · 경로 | 호출자 | 용도 |
|---|---|---|
| `POST /internal/devices/verify-token` | `VerifyTokenClient` | 기기 WSS 핸드셰이크 후 기기 토큰 검증 (`internal-verify-token-request/response`) |
| `PUT /internal/devices/{deviceId}/connection` | `StatusReporter` (10초 주기) | 기기 연결 상태 보고 (`internal-device-connection-report`) |
| `POST /internal/events` | `RealtimeOutboxSender` | Realtime Outbox 이벤트를 Core Inbox로 전달 |

`InternalHttpClient`는 대상 그룹별 JDK `HttpClient`를 두고 `InternalAuthInterceptor`가 `X-Internal-Token` · `X-Trace-Id`를 붙인다.

## 4. 기기 WSS (`/ws/device`)

- 핸드셰이크: Origin 정확 일치 + 쿠키 `MUJOONG_DEVICE` (`DeviceHandshakeInterceptor`), 이후 Core 토큰 검증.
- 연결 상태: AWAIT_VERIFY → AWAIT_HELLO → READY → CLOSED (`DeviceConnection`). 새 HELLO는 이전 연결을 `CloseCode` 4001 REPLACED로 닫는다.
- 프레임: 제어 메시지(JSON, `WsEnvelope`) 17종 + 이진 프레임 0x01(기기 → 서버 입력 오디오) · 0x02(서버 → 기기 출력 오디오), 코덱 `AudioFrameCodec`. `WsFrameGuard`가 프레임을 검사한다.
- `opId`가 필요한 명령 4종: `START_SESSION` · `PLAY_APPROVED_CONTENT` · `STOP_OUTPUT` · `CLOSE_TRANSPORT` (기기 `ACK`로 확인).
- 주요 메시지(`contract-model/.../ws/message/*`): Hello · StartSession · Ack · DeviceReady · SessionState · OutputBegin/OutputEnd · PlayApprovedContent · PlaybackMark · StopOutput · CloseTransport · VoiceSessionClosed · DeviceState · View · WsError 등.
- U4b가 쓸 값은 이미 계약에 있다: `CloseTransport.Reason`은 CHILD_STOP · SILENCE · CONSENT_WITHDRAWN · DECLINED 포함(`ws-messages.schema.json` 218행), `View`는 STANDBY · LISTENING · THINKING · CLOSING, `PLAY_APPROVED_CONTENT`는 자유 코드를 받는다. 기기 프로토콜 변경 없이 구현 가능.

## 5. 영속 이벤트 (6종, `EventType`, `CURRENT_SCHEMA_VERSION = 1`)

| 이벤트 | 방향 | Realtime 현재 상태 |
|---|---|---|
| `SessionStateChanged` | Realtime → Core | 생산함(모든 전이, `SessionStateEmitter` · `RealtimeOutboxWriter`) |
| `SessionRecordCaptured` | Realtime → Core | **생산하지 않음**. 페이로드는 `record` · `termination`을 해석하지 않은 Map으로 둠(모양은 U4 소유). Core 구독자 `SessionRecordCapturedHandler`(U4a)가 기다림 |
| `DeviceStatusChanged` | Realtime → Core | 생산함 |
| `DeletionCompleted` | Realtime → Core | **생산하지 않음** |
| `DeviceReleased` | Core → Realtime | 처리함(`DeviceReleasedHandler`, 유일한 `InboxHandler` 구현) |
| `DeletionRequested` | Core → Realtime | **처리기 없음** → IGNORED로 커밋 |

전달 보장: Outbox(같은 트랜잭션) → 전용 송신 스레드(`SKIP LOCKED`) → 수신 측 Inbox(멱등 저장 · 선점 배분 · 재폴링). 최소 1회 전달이므로 처리기는 멱등이어야 한다. `snapshotHash`는 `JsonCodec.sha256OfCanonical`로 계산하며 `SnapshotHashGoldenTest`(contract-model, `u4a` 태그)가 골든 값을 고정한다 — U4b는 null 키 처리 규칙과 골든 하나를 더해야 Core와 맞는다.

## 6. 공급자 WSS (외부)

`ProviderClient`가 JDK `java.net.http.WebSocket`으로 연결한다(SDK 없음). 인증 `Authorization: Bearer ${PROVIDER_API_KEY}`(환경 변수). 호스트는 `ProviderHosts` 코드 상수 허용 목록, 요청은 `ProviderRequestFactory`의 레코드 3종으로만 만든다(ArchUnit 강제). 연결은 세션당 가상 스레드(`ProviderConnector`). `mujoong.provider.url`은 `application-demo.yml` · `application-prod.yml`에만 있고 비어 있으면 기동 점검 S1b가 거부한다.

## 7. 계약 검증 장치

- `contract-model` `ContractFilesTest`: 계약 파일 메타 검증.
- `realtime-activity` `testing/InternalApi`: 내부 API 응답을 JSON Schema(networknt, 2020-12)로 검증.
- `ConfigKeyContractTest`: `contracts/timing.md` ↔ `application.yml` ↔ 설정 레코드 대조.
- 계약 편차는 `contracts/deviations.md`에 기록한다(U4b 설계 7절 (U4b-a) ~ (U4b-c) 예정).

## Sources
- 개발자 스캔 `developer-scan.md` (APIs Discovered · Handoff Risks)
- `ls contracts contracts/schemas` 직접 확인
