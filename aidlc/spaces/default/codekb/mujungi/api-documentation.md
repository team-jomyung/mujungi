> 현재 범위의 정본은 바로 아래 U14 갱신절이다. 그 뒤 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## U14 현재 API·계약 차이

ResultQueryService.Scene은 sceneNo, exposureType, sceneChip, chipText, notDoneReason만 낸다. raw timing과 flowVersion은 현재 없다. session-result-response는 additionalProperties=false이고 sceneNo도 [1,2]로 닫혀 있으므로 선택 필드·enum 확장조차 엄격 소비자 영향을 분류해야 한다(contracts/README.md:30,50).

기존 후보 DTO와 스키마는 reasonText를 공개하고 frontend/src/parent/api/results.ts:28은 text(c.reasonText)를 필수 파싱한다. 새 후보에서 내부 reasonText를 제거하면 이 파서도 함께 바꿔야 한다. 대안은 공개 displayEvidence를 추가하고 파서에서 기존 화면 모델의 reasonText로 옮기는 최소 어댑터다. 27 화면 소스는 유지할 수 있으나 계약의 필드 삭제·의미 변경 분류 및 legacy fallback 회귀가 필요하다. 원문 AI reasonText를 공개하는 해결은 C-DS12를 충족하지 않는다. 이 선택은 후속 설계 사항이다.

U15 실제 API: seedSummaries(guardian,child), skillContext(guardian,child,seedId) → Optional<SkillContext(seed,graphVersion)>, generateSeed(Request) → Result(seedId,reason). Request에는 guardianId,childId,activityId,candidateRef,skillRef와 StructureSignature 5값이 필수다. 현재 goal 후보 계약의 fourElements만으로 이 5값이 제공되지는 않는다. 추론을 임의 상수로 채우지 말고 후보 생성 계약/프롬프트에서 안전한 내부 구조를 제공할지 설계한다.

SavedGoalReader의 C-DS10은 U14 신규 제공 대상이며 구현 완료로 적지 않는다.

---

# API 문서 (API Documentation)

> 최신 확인: 2026-10-16, HEAD 7e30ee8, U15 집중 분석. 아래 최신 절이 U15 관련 과거 설명보다 우선한다. 이전 분석 본문은 이력으로 보존하며 이번 범위 밖의 검증 주장은 shallow로 낮췄다.

## U15 포트 현황 및 계약 차이

현재 KnowledgeGraphLookup은 findRelatedBehaviorKeys(String)만 제공하며 공개 카탈로그 전용이다. U15 공개 HTTP 엔드포인트는 발견하지 않았다. C-DS11의 seedSummaries, skillContext, variantPlan, writeGeneratedSeed, deleteChild 및 초기화는 추가 설계·구현 대상이다. 아동별 메서드는 명시적 childId와 소유 관계 검증을 필요로 한다.

ai-seed-output.schema.json은 skillName(1~80), invariantSentence(1~200), additionalProperties=false다. 구조 서명·허용값 및 정본 A §6.4 길이 규칙은 추가 확인 대상이다. DeletionCompleted.Counts는 기존 realtime 삭제 항목 4개이며 U15 때문에 임의 확장하지 않는다.

## 이전 분석 기록 — 2026-10-15, 현재 범위 밖은 재검증하지 않음

> 기준: 커밋 `72d7034`, 2026-10-15 전체 재스캔. 계약 정본은 `contracts/`이며, 이 문서는 코드에 실제로 있는 표면을 요약한다. 계약 소유 · 깨는 변경 규칙은 `contracts/README.md`, 편차는 `contracts/deviations.md`(67행)에 있다.

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
| realtime-activity | `POST /internal/sessions` | Core가 세션 시작 |
| realtime-activity | `POST /internal/sessions/{id}/end` | Core가 보호자 종료 전달 |
| realtime-activity | `GET /internal/sessions/{id}/view` | Core가 세션 뷰 조회 |
| realtime-activity | `POST /internal/events` | Core Outbox → Realtime Inbox |

(계약 7경로와 코드 8개 항목의 차이는 `/internal/events`가 양쪽에 같은 계약으로 있기 때문이다.) 보호: 내부 토큰(`InternalTokenFilter`, Core 쪽은 `InternalAuthInterceptor`로 송신). Realtime은 Spring Security를 쓰지 않는다.

## 4. 기기 WebSocket (realtime-activity, 계약 `contracts/device-ws.asyncapi.yaml`)

- 경로 `/ws/device`(`WebSocketConfig.DEVICE_PATH`), 허용 Origin 1개, 핸드셰이크 인터셉터가 기기 토큰 검증.
- 제어 메시지(JSON 봉투 `WsEnvelope`) 17종 — `WsMessageType`:
  `HELLO` · `HELLO_OK` · `HEARTBEAT` · `HEARTBEAT_ACK` · `START_SESSION` · `ACK` · `DEVICE_READY` · `SESSION_STATE` · `OUTPUT_BEGIN` · `OUTPUT_END` · `PLAY_APPROVED_CONTENT` · `PLAYBACK_MARK` · `STOP_OUTPUT` · `CLOSE_TRANSPORT` · `VOICE_SESSION_CLOSED` · `DEVICE_STATE` · `ERROR`.
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

## 6. 외부 음성 AI 공급자 (나가는 WebSocket, realtime-activity)

- 주소: `wss://api.openai.com/v1/live/sessions`(`application-{demo,prod}.yml`), 허용 호스트 `api.openai.com` 고정(`ProviderHosts`).
- 모델 상수 `gpt-live-1`, 음성 형식 `pcm16le/24000/1`(입출력).
- 송신 type 6종(`ProviderRequestFactory`): `session.start` · `session.input_audio.append` · `session.close` · `session.response.cancel` · `session.instructions.correct` · `session.response.create`.
- 수신 type 9종(`ProviderClient`): `session.started` · `session.input_audio.speech_stopped` · `session.input_transcript.delta` · `session.input_transcript.done` · `session.output_transcript.delta` · `session.output_audio.delta` · `session.output.done` · `session.error` · `session.closed`.
- 이 형식은 프로젝트가 정의한 것(결정 J110, 편차 (aa) · (U4b-a))이며 가짜 공급자(`tools/fake-provider/server.mjs`)가 같은 형식을 흉내 낸다. 위험은 [code-quality-assessment.md](code-quality-assessment.md) TD-2.

## 7. 외부 텍스트 AI 포트 (core-api)

- `core.textai.TextAiProvider`(U5a 연습 상황 생성), `core.result.ResultTextProvider`(U6 결과 · 리포트 문장). 입력 · 출력 규칙은 `contracts/ai-io.md`, 포트 목록은 `contracts/ports.md`.
- 운영 어댑터 없음. 기본 빈은 각각 `TextAiException.Reason.UNAVAILABLE` · `PROVIDER_UNAVAILABLE`(TD-1). 환경 변수 이름(`MUJOONG_TEXT_AI_ENDPOINT/MODEL/API_KEY`)만 `application.yml` 주석에 있음.

## 8. 기타 외부 연동

- Google OAuth: 토큰 · JWKS(`core.identity.GoogleTokenClient` · `GoogleKeysClient` · `GoogleKeyCache`).
- Web Push VAPID: push2u, `core.notification.VapidPushSender` · `PinnedPushHttpClient`.
- Neo4j 지식 그래프: `core.knowledge.KnowledgeGraphLookup` 인터페이스만, `mujoong.knowledge.neo4j.enabled=false`(TD-3).

## 9. 운영 · 문서 엔드포인트

- Actuator: 관리 포트 9090(127.0.0.1), 헬스 · Prometheus 지표.
- springdoc OpenAPI: dev · test 프로필에서만 켜짐.
- 프런트 `/healthz` route.
