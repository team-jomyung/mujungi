> 현재 범위의 정본은 바로 아래 "제브 어댑터 선 형식 집중 갱신" 절이다(`backend/realtime-activity` · `contracts` · `docs/units/u13` 한정, 2026-10-18, HEAD `421e1ad`). 그 뒤 구현 전 U13 절 · U14 절 · U15 절 · 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## 제브 어댑터 선 형식 집중 갱신 — 외부 호출과 내부 형식 (261017-jev-adapter-format, 2026-10-18)

코드를 읽은 정적 사실이다. 실제 제브 API를 부른 증거는 없고(`jevLiveTest`는 키 없이 `SKIPPED_NO_KEY`까지만), 여기 적은 "현재 코드의 가정 형식"은 검증된 공급자 형식이 아니다(R-M11). 설계 결정은 하지 않는다.

### A. 현재 코드의 제브 호출 (나가는 HTTP, 가정 형식)

- 호출부: `kr.mujoong.realtime.guardrail.JevGuardrailClient`(포트 `GuardrailClient.check(GuardrailRequest, Instant deadline) → CompletionStage<GuardrailVerdict>`). 외부 SDK 없이 JDK `HttpClient`(리다이렉트 안 따름 · 프록시 없음) + `JsonCodec`(Jackson 한 곳, J139).
- 주소: 설정 `mujoong.guardrail.jev.base-url`(환경 변수 `JEV_BASE_URL`)은 **기준 주소**이고 경로는 코드 상수 `CHECK_PATH = "/v1/guardrail/check"`(형식 가정)가 붙인다. `systemone`은 코드에 없다. 허용 호스트 `api.typesafe.ai` 하나(`JevHosts.ALLOWED_HOSTS`), 호스트 모드 STRICT(https + 허용 호스트) · DEV(STRICT 또는 루프백) · TEST(루프백만), 사용자 정보 · 질의 · 조각이 있는 주소 거부(ai-io.md 10.11). 생성자와 보내기 직전 두 번 확인한다.
- 모델: 설정 `mujoong.guardrail.jev.model`(`JEV_MODEL`). `JevModels.problem`이 형식 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`를 확인하고 이동 별칭(`latest`로 끝나는 이름)을 거부한다. 고정 버전 모델을 제브가 받는지는 어디에도 확인이 없다.
- 키: `JEV_API_KEY`(비밀)를 호출마다 `Supplier<String>`으로 읽어 `Authorization: Bearer <키>` 헤더에만 쓴다. 필드로 보관하지 않고 `toString()`은 `JevGuardrailClient[mode=REAL]`뿐이다(R-F3). 헤더는 그 밖에 `Content-Type: application/json`. `Accept`는 보내지 않는다.
- 요청 본문(닫힌 키 6개, 가정): `{ model, systemPrompt, mode, candidateText, recentContext:[{speaker,text}], policyVersion }`. `systemPrompt`는 `guardrail/policy-v1.json`의 문자열 한 덩어리, `mode`는 `FREE_TALK | PRACTICE_PEER | PRACTICE_COACH`, `recentContext[].speaker`는 `CHILD | PARTNER | COACH | MUJOONG`. 공식 요청(아래 B)과 겹치는 키는 `model` 하나다.
- 응답 읽기(`convert`, 가정): 상태 코드가 2xx가 아니면 본문을 읽지 않고 `TRANSPORT_ERROR`. 2xx이면 본문 전체를 JSON 객체로 읽어 `decision`(`ALLOW|BLOCK|REVIEW`) · `categories`(필수, 7개 이름 안, 중복 불가) · `policyFlags`(선택, `^[A-Z][A-Z0-9_]*$`) · `reasonCode`(필수 정규식) · `policyVersion`(필수 1 ~ 80자) · `reviewNeeded`(선택 boolean, `ALLOW`인데 true면 모순) 를 확인한다. 응답 `policyVersion`이 요청의 판과 다르면 `POLICY_MISMATCH`.
- 시한 · 재시도 · 크기: 시한은 `min(마감까지 남은 시간, mujoong.timing.guardrail.jev-budget 800ms)`, 0 이하면 보내지 않고 `TIMEOUT`. 호출 한 번, 재시도 없음(BR2.1). 응답 본문 상한 64KiB(`MAX_RESPONSE_BYTES`) 초과 시 `TRANSPORT_ERROR`. 429 · 529 · 5xx · 3xx 모두 `TRANSPORT_ERROR` 한 가지이고 구분하지 않는다.
- 오류 매핑(`GuardrailVerdict.Failure` 6종): `UNAVAILABLE`(모델 별칭 · 형식 오류, 키 없음) · `INVALID_REQUEST`(정책 판의 시스템 지시 없음, 요청을 만들 수 없음) · `TRANSPORT_ERROR`(주소 모양 · 허용 호스트 밖 · 비2xx · 64KiB 초과 · 전송 오류) · `TIMEOUT`(시한 초과 · 취소) · `INVALID_JSON`(본문이 객체 아님, 필수 필드 · 값 위반) · `POLICY_MISMATCH`.
- 감사 · 기록: 어댑터는 아무것도 기록하지 않는다. 기록은 소비자 쪽(`GuardrailEventRecorder` → `realtime.guardrail_event`, V6)이 사유 코드 · 정책 판 · 지연만 남긴다(R-F14).

### B. 제브 공식 형식 (공식 문서 기준, 코드 미반영)

> 이 절의 내용은 개발자 스캔 문서나 코드에 없는 **외부 정본**이다. **공식 문서 기준이며 코드에 반영되지 않았다.** 출처: https://docs.typesafe.ai/introduction/quickstart , https://docs.typesafe.ai/api . 오류 본문 스키마는 문서에 없다.

- 요청: `POST https://api.typesafe.ai/v1/systemone`, 인증 `Authorization: Bearer ...`.
  - 본문 필드: `state` · `model` · `questions`.
  - `questions`: 질문 ID → 질문 객체. 질문 `type`은 `choice | score | noul` 중 하나이고 `instructions`와 `criteria`를 가진다. `choice` 질문의 `criteria`는 **선택지 라벨 → 설명** 맵이며 선택지는 최대 255개다.
- 응답: `model` · `answers` · `usage`.
  - `answers`: 질문 ID별 답. `choice` 답은 `type` · `choice`(고른 라벨) · `probabilities`(선택지별 확률, 합 1) · `confidence`(0 ~ 1).
  - `usage`: `input_tokens` · `output_tokens`.
- 오류 상태: 400 · 401 · 413 · 415 · 422 · 429 · 529. 오류 본문 스키마는 문서에 없다.

A와 B의 대응(사실만): 요청은 `model`만 겹치고, 응답에는 A가 읽는 `decision · categories · policyFlags · reasonCode · reviewNeeded · policyVersion`이 B에 없다. B에서 오는 `choice`와 `probabilities`를 받을 자리는 현재 `GuardrailVerdict` · `guardrail_event`에 없다.

### C. 내부 형식 계약 (어댑터 안에서만 공급자 형식과 바뀐다)

**요청 `contracts/schemas/guardrail-request.schema.json`**(닫힌 객체): 필수 `source`(상수 `GPT_LIVE_GENERATED`) · `mode` · `candidate`(1 ~ 4000자) · `policyVersion`(1 ~ 80자). 선택 `recentContext`(최대 3개, `speaker` enum 4 + `text` 1 ~ 2000자). 식별자 칸 없음(NFR3.1). 스키마 description이 "공급자 실제 요청은 어댑터 안에서만 이 형식에서 바꾼다"고 적는다. 예시 `policyVersion`은 `jev-guardrail-v1`(편차 `(U13-b)`로 정정됨).

**결과 `contracts/schemas/guardrail-result.schema.json`**(닫힌 객체): 필수 `decision`(`ALLOW|BLOCK|REVIEW`) · `categories`(유일 배열, 최대 7, enum `DIAGNOSIS · TREATMENT · SCORE · CAUSE · GENERALIZATION · EMOTION_CLAIM · PRESSURE`) · `reasonCode`(`^[A-Z][A-Z0-9_]{0,79}$`) · `policyVersion`. 선택 `policyFlags` · `reviewNeeded`. description이 "어댑터가 공급자 응답을 바꿔 만드는 내부 형식"이라고 못 박는다. `FAILED`는 스키마에 없다. 이 스키마의 첫 `examples` 항목을 `JevGuardrailClientTest`가 공급자 응답으로 그대로 보낸다.

**관련 계약 문서**: `ai-io.md` 10.1 ~ 10.5 · 10.11(설정 표 `mujoong.guardrail.jev.{mode,base-url,model}`, 호스트 규칙), `timing.md` 13절 `mujoong.timing.guardrail.jev-budget = 800ms`("넘기면 막힘, 자동 ALLOW 없음"), `deviations.md`의 `(U13-a)` `(U13-b)`. 문턱 설정 키는 어느 문서에도 없다.

### D. 내부 포트 · 소비 경계

- 포트 `GuardrailClient.check(...)` 하나. 구현 3종: `JevGuardrailClient`(REAL), `StubGuardrailClient`(대역, `STUB_ALLOW`), `UnavailableGuardrailClient`(비활성). 새 HTTP 엔드포인트는 없다.
- 완료 결과의 입구는 `SessionMailbox.GuardrailArrived(requestId, verdict)`와 `GuardrailRetryNotHeard(retryId)` 두 메시지다.
- 소비자가 읽는 `GuardrailVerdict` 필드: `decision` · `failure` · `reasonCode` · `latencyMs`(+ `eventDecision()`). `categories` · `policyFlags`는 main에서 읽는 곳이 없다.
- 지표(`RealtimeMetrics`): `mujoong.guardrail.blocked{mode,decision=block|review|error}` · `mujoong.guardrail.timeout{mode}` · `mujoong.guardrail.latency{mode}` · `mujoong.guardrail.calls{mode,source}`. 지표 이름 표가 `contracts/`에 있는지는 이번 스캔에서 확인하지 못했다(구현 전 스캔의 G5 참조).
- 연습 알림: `PracticeSessionExtension.GuardrailBlockedEvent(correlationId, mode, reasonCode, retried)` — 연습 모드 요청의 두 번째 막힘에서만. `mode == FREE_TALK`이면 생성자가 거부한다.
- DB: `realtime.guardrail_event`(V6) 쓰기만(기록 레코드 `GuardrailEventRecord`의 필드 `id · guardianId · sessionId · mode · sourceKind · decision · reasonCode · policyVersion · latencyMs · createdAt`; 열 이름 중 `decision` · `reason_code` · `policy_version` · `latency_ms`는 V6에서 확인). `categories` · `policyFlags` · 확률 칸은 없다.

---

## 구현 전 U13 계약 지도 (2026-10-17, 이력 — 구현 전 스캔)

> 아래 중 "브리프상 `POST …/v1/systemone`", "새 메시지가 필요하다", "`(U13-a)`부터 기록한다"는 구현 전 서술이다. 현재 코드는 위 A ~ D가 우선한다. `(U13-a)` `(U13-b)`는 이제 존재한다.

**외부 공급자(제브)**: 브리프상 `POST …/v1/systemone`이며 주소 · 모델은 설정 기본값이다. 응답 형식은 아직 모른다(브리프 "첫 작업: 위험 확인"). 어댑터 한 곳에서 내부 형식으로 변환하고, 실호출은 사람 몫이다(R-F1 · R-F13 · R-F15).

**요청 `contracts/schemas/guardrail-request.schema.json`**: 필수 `source`(상수 `GPT_LIVE_GENERATED`) · `mode`(`FREE_TALK` · `PRACTICE_PEER` · `PRACTICE_COACH`) · `candidate`(1~4000자) · `policyVersion`(1~80자). 선택 `recentContext`(최대 3개, `speaker` ∈ `CHILD · PARTNER · COACH · MUJOONG`, `text` 1~2000자). `additionalProperties: false`, 식별자 · 키 속성 없음.

**결과 `guardrail-result.schema.json`**: 필수 `decision`(ALLOW · BLOCK · REVIEW) · `categories`(7개 enum, 중복 없음) · `reasonCode`(`^[A-Z][A-Z0-9_]{0,79}$`) · `policyVersion`. 선택 `policyFlags` · `reviewNeeded`. BLOCK · REVIEW · 시간 초과 · 연결 실패 · 잘못된 JSON · 정책 판 불일치는 송신하지 않고 실검사 모드에서 자동 ALLOW는 없다.

**관련 계약 문서**
- `ai-io.md` 10절: 10.1 스키마 표, 10.2 금지 필드와 R-F15(키를 넣기 전 RUNBOOK 단계 4 기록), 10.3 재시도 분류(제브 행 없음, G11), 10.4 판 리소스, 10.5 원문 일치 · 생략(ASCII 공백 제거 정규화만), 10.7 비밀 이름, 10.8 로그 표(`LUNA_CALL`만, 제브용 표 없음).
- `timing.md` 13절: `mujoong.timing.guardrail.jev-budget = 800ms`("넘기면 막힘, 자동 ALLOW 없음"). 프런트 `timing-defaults.json`에는 넣지 않는다(편차 U8-c).
- `deviations.md`: `(U8-a)` 스키마 9종, `(U8-c)` 시간 키 7개. 제브 실호출이 확정되면 `(U13-a)`부터 기록한다. `errors.md`에 제브 관련 새 HTTP 오류 code는 없다.
- 판 불일치 주의(G12): 요청 스키마 예시의 `policyVersion: "guardrail-v1"`은 실제 판 `jev-guardrail-v1`과 다르다. 어댑터 변환 시험이 예시를 그대로 쓰면 판 불일치가 생긴다. (구현 뒤: `(U13-b)`로 예시가 `jev-guardrail-v1`로 정정되었다.)
- 제브 요청 · 결과를 코드와 대조하는 시험은 realtime에 아직 없다(`LunaHostsTest`가 스키마 파일 존재만 한 줄 확인). 어댑터 변환 시험이 이 역할을 겸하게 된다. (구현 뒤: `JevGuardrailClientTest`가 이 역할을 한다.)

**realtime 내부 경계**: 새 HTTP 엔드포인트는 필요 없다. 비동기 결과의 입구는 `SessionMailbox` 메시지(약 20종, `JudgmentArrived` · `JudgmentInstructionFailed` 등)이고 제브 결과용 새 메시지(예: `GuardrailArrived`)가 필요하다. 완료 콜백은 `mailbox.putSafety`만 부른다.

**이벤트 계약**: 삭제 참여자를 더해도 `DeletionCompleted.Counts(sessions, transcripts, audioFiles, practiceRecords)`는 바꾸지 않는 것이 U11 선례이고 가장 작다(바꾸면 `events.asyncapi.yaml` · `event-payloads.schema.json` · core 소비자가 따라오며 R-M10).

---

> 현재 범위의 정본(U14 갱신절, 보존 이력)은 바로 아래다. 그 뒤 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

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

---

## U11 집중 갱신 (261016-u11-judgment-turn, 2026-10-16 스캔 — main 병합 때 보존한 절)

> main의 U14 · U15 갱신본을 바탕으로 두고, U11 작업 기록의 집중 스캔에서 갱신한 절만 아래에 보존했다. 줄 번호 · 커밋은 2026-10-16 `9bcc706` 기준이며 PR1(선 형식 정비) 이전 코드다.

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

## 10. (2026-10-16 갱신) realtime 앱 안 확장점 포트 (Java, U11 소유 · U12 구현)

`kr.mujoong.realtime.session.PracticeSessionExtension` — 세션 실행기가 연습 흐름을 붙이는 앱 안 계약(C-DS3). 실행기 → 확장점 한 방향이며 확장점은 실행기를 부르지 않는다.

| 구분 | 내용 |
|---|---|
| 시점(실행기 스레드에서 호출) | `onActive` · `onChildUtterance(Utterance)` · `onExtensionEvent(ExtensionEvent)` · `onSettle(EndContext)` · `practiceRecord()` + 기본 메서드 4개(`controlsOutput()` 등) |
| `Utterance` | 전사 순번 · 시각 · 원문 · 불확실 여부 · 재생 중 여부 |
| `ExtensionEvent.Kind` | `PLAYBACK_DONE` · `PLAYBACK_FACT` · `TIMER_FIRED` · `ASYNC_RESULT`(L88 ~ L93). U11 계약 요약이 말하는 `JUDGMENT_RESULT` · `JUDGMENT_ABSTAINED` · `HELD_OUTPUT_TRANSCRIPT` · `GUARDRAIL_BLOCKED`는 아직 없다 (2026-10-18 보충: U13 구현으로 `GUARDRAIL_BLOCKED`는 `SessionExecutor`가 `practiceBlocked`에서 쓰는 것이 보인다) |
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
| `contracts/deviations.md` | U8 · U9 편차 표 | U8-a ~ U8-e, U9-a ~ U9-c (2026-10-18 보충: U13 편차 `(U13-a)` `(U13-b)` 추가됨) |
| `contracts/events.asyncapi.yaml` | `SessionRecordCaptured` 설명 | 형식 구분(위 5절) |
| `contracts/device-ws.asyncapi.yaml` | `SESSION_STATE` | `STANDBY · LISTENING · THINKING · CLOSING` |
