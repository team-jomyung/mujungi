# API 문서 (API Documentation)

> 기준 커밋 `7a22b67`. 계약 정본은 `contracts/`다. 여기서는 표면과 U6 관련 사실만 적는다.

## 1. 보호자 REST (`/api`, core-api) — `contracts/parent-api.yaml`

경로 22개: `dev/login` · `session/csrf` · `health` · `home` · `device` · `device/register` · `activities/free` · `sessions/{id}` · `sessions/{id}/end` · `auth/google` · `auth/logout` · `me` · `policies/current` · `consents` · `child` · `child/stop-expressions` · `sessions/current` · `records` · `records/{sessionId}` · `goal-selections` · `goal-selections/{id}` · `goal-selections/{id}/restore`.

**C5(결과 · 리포트) 경로는 계약 파일에 없다.** U6 지시서가 더할 경로: `GET /api/sessions/{id}/result` · `/result/status` · `POST /result/retry`, `GET /api/reports/preview` · `POST /api/reports` · `GET /api/reports/{id}` · `/pdf` · `POST /saved` · `/discard`(정본 contract-summary 3절).

### 기록 (U4a) — `record/web/RecordController.java`

| 요청 | 응답 |
|---|---|
| `GET /api/records?month=yyyy-MM` | 날짜별 기록 수 |
| `GET /api/records?date=yyyy-MM-dd` | 줄: `linkable` · `goalCount` · `interrupted` (FAILED 제외) |
| `GET /api/records/{sessionId}` | `session` · `goals`(이름 · 순서 · 상태) · `utterances` · `gaps` · `recordScope` · `endConfirmed`. FAILED → 404, DECLINED → 머리만 |

스키마: `contracts/schemas/records-response.schema.json` · `record-detail-response.schema.json`. 판정 · 구조화 · 08 원문은 응답에 없다(FR4.3 · A10).

### 연습 목표 (U5a) — `practice/web/GoalSelectionController.java`

`GET/POST /api/goal-selections`, `PATCH/DELETE /api/goal-selections/{id}`, `POST /api/goal-selections/{id}/restore`. 27의 후보 「담기」가 `POST`를 쓸 예정.

## 2. 내부 HTTP (C-INT, 내부 토큰) — `contracts/internal-api.yaml`

7개: `/internal/sessions`(시작) · `/internal/sessions/{id}/end` · `/internal/sessions/{id}/view` · `/internal/devices/verify-token` · `/internal/devices/{id}/connection` · `/internal/events`(Outbox 수신) · `/internal/practice/scenarios`(C10, Core 소유).

## 3. 앱 사이 이벤트 (Outbox/Inbox) — `contract-model/.../event/EventType.java`, `contracts/events.asyncapi.yaml`

| 이벤트 | 방향 |
|---|---|
| `SessionStateChanged` · `SessionRecordCaptured` · `DeviceStatusChanged` · `DeletionCompleted` | Realtime → Core |
| `DeviceReleased` · `DeletionRequested` | Core → Realtime |

`SessionRecordCaptured.record` = `{utterances, gaps, recordScope{from,to}, practice, audio}` + `snapshotHash`. **`record.practice`의 내부 모양(goals[].responses[] · cues[] · scenarioId)은 `contracts/` 파일에 없고 정본 contract-summary 6절에만 있다.** 현재 값은 늘 null(U5b 전).

## 4. 앱 내부 사건 (Spring) — core-api

`ActivityRecorded(sessionId, guardianId, childId, activityType, finalStatus, recordedAt)` — 기록 첫 저장 트랜잭션에서 발행, 구독은 `@TransactionalEventListener(AFTER_COMMIT)` 전제. 운영 구독자 0개(시험 `U4aActivityApiIT.RecordedEvents`만).

## 5. Java 포트 (core-api 내부) — `contracts/ports.md` 4절

| 포트 | 소유 · 방향 | 기본 빈 (현재) |
|---|---|---|
| `CandidateRegistry.register(sessionId, childId, candidates≤100)` · `viewForSession` | U5a 제공 | 실제 구현. 근거 kind ∈ {GUARDIAN_INPUT, CHILD_RECALL}, `reasonText` ≤2000자 · 금지 사전 통과, 정의 pin은 ACTIVE만 |
| `CandidateEvidencePort` | U5a 소비, U6가 어댑터 제공 | 늘 `false` → 모든 등록 VALIDATION_FAILED |
| `GoalMetadataPort` | U5a 소비 | null 메타 |
| `PracticeSessionContextPort` · `ScenarioRecordPort` | U5a 소비 | SERVICE_NOT_READY |
| `KnowledgeGraphLookup` | U5a | Neo4j 꺼짐이면 빈 목록 |
| `TextAiProvider` · `TextAiGateway` | U5a | `generateScenario` 한 메서드, 기본 UNAVAILABLE |
| `ScenarioLookup` · `BehaviorDefinitionLookup` | U6가 U5a 컴포넌트에 더할 예정 | **타입 없음**. 재료: `ScenarioRepository.find(UUID)` · `PragmaticsKnowledge.requireVersion(key, version)`(RETIRED 허용) |

## 6. AI 입출력 — `contracts/ai-io.md`

6절(U6 분석 AI): 6.1 공통 지시 · 6.2 기록용 재판정 출력 JSON · 장면 칩 표 · 6.3 결과 문장 · 6.4 결과 수준 · 6.5 리포트 10항목 · 6.6 금지 표현 2단계. 7절은 U5a `TextAiGateway`. 코드에는 6절용 입출력 타입이 없다.

## 7. 기기 WebSocket

`contracts/device-ws.asyncapi.yaml`, `contract-model/.../ws/message/`(18종). Realtime 소유, U6 무관.

## Sources

- 개발자 스캔 `.../developer-scan.md`(APIs Discovered · U6 관련 코드 위치)
- `docs/units/u6/brief.md`(C5 경로 목록)
