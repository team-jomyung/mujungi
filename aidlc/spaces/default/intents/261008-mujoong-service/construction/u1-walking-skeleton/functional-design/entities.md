# U1 u1-walking-skeleton — 엔터티 모델 (Functional Design 3.1)

> Functional Design(3.1) 산출물 · U1 워킹 스켈레톤 · 깊이 Standard · 2026-10-09 (클라우드 세션, 팀장 대리 판단)
> 입력: `unit-of-work.md`(U1 정의), `unit-of-work-story-map.md`(U1이 닿는 스토리 8개), `requirements.md`(FR2.1 · FR2.2 · FR2.3 · NFR6 · NFR9), `components.md`(엔터티 소유 표 · J1~J14 · 기기 상태 대응표), `contract-summary.md`(C2 · C7 · C9 · C11 · 시간 계약 · 포트), ERD v0.1 DDL(`mujoong_ERD_PostgreSQL_1007.sql`). 질문과 대리 답은 `functional-design-questions.md`(Q1 ~ Q19), 준하 제안(가정) J42 ~ J60은 `functional-spec.md` 끝 표.
> 이 문서는 데이터 모양만 적는다. 규칙은 `rules.md`(BRx.y), 순서 있는 동작과 상태 기계는 `functional-spec.md`.

## 1. 소유와 범위

U1은 완성하는 컴포넌트의 엔터티는 **전부** 설계하고(`u1_role: owned`), 스켈레톤 경로가 지나가는 다른 컴포넌트의 엔터티는 **테이블 모양만** 적고 동작은 소유 유닛에 맡긴다(`u1_role: minimal`, J42 · J43). 테이블 모양은 Domain Design 최종 컬럼(J12 반영)이고 소유 유닛은 열 · 색인을 더하기만 한다. 스키마 접두어가 쓰는 앱이다: `core_` = Core API만, `realtime_` = Realtime Activity만. 서로 다른 스키마를 가리키는 열은 DB 외래 키 없는 논리 참조(UUID)다.

| 엔터티 | 테이블 | 스키마 | 소유 컴포넌트 | U1 역할 | 완성 유닛 |
|---|---|---|---|---|---|
| Device | core_device | core | DeviceRegistry | owned | U1 |
| CoreOutboxEvent | core_outbox_event | core | CoreMessaging | owned | U1 |
| CoreInboxEvent | core_inbox_event | core | CoreMessaging | owned | U1 |
| RealtimeOutboxEvent | realtime_outbox_event | realtime | RealtimeMessaging | owned | U1 |
| RealtimeInboxEvent | realtime_inbox_event | realtime | RealtimeMessaging | owned | U1 |
| Guardian | core_guardian | core | GuardianIdentity | minimal(dev 시드) | U2 |
| LoginSession | core_spring_session(+_attributes) | core | GuardianIdentity | minimal(dev 시험 로그인) | U2 |
| Consent | core_consent | core | ConsentPolicy | minimal(dev 시드) | U2 |
| Child | core_child | core | ChildProfile | minimal(dev 시드) | U2 |
| StopExpression | core_stop_expression | core | ChildProfile | 표만 만듦(U1은 행을 넣지 않는다 — 기본 종료 표현은 코드 상수, BR6.5) | U2 |
| ApprovedContent · ApprovedContentVersion | core_approved_content · core_approved_content_version | core | ApprovedContentCatalog | minimal(dev 시드 문구 · 임시 음성) | U2 |
| Activity | core_activity | core | ActivityCoordinator | minimal(자유대화 한 건) | U4 |
| ActivitySession | realtime_activity_session | realtime | SessionLifecycle | minimal | U4 |
| SessionEvent | realtime_session_event | realtime | SessionLifecycle | minimal | U4 |
| SessionStopExpression | realtime_session_stop_expression | realtime | SessionLifecycle | minimal(기본 종료 표현 한 행, is_default true) | U4 |
| Transcript | realtime_transcript | realtime | ConversationLog | minimal | U3 |
| DeviceConnection | realtime_device_connection | realtime | DeviceGateway | minimal | U3 |

## 2. 엔터티 (source of truth)

```yaml
entities:
  # ───────────── U1이 완성하는 엔터티 ─────────────
  - name: Device
    table: core_device
    schema: core
    owner_component: DeviceRegistry
    u1_role: owned
    description: 아이 기기 한 대의 등록 수명(코드 발급 → 등록 → 해제 → 계정 삭제 때 행 삭제)과 Realtime이 보고한 연결 상태 사본. 계정당 활성 기기는 하나다.
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: guardian_id, type: uuid, required: false, references: Guardian.id, constraints: "REGISTERED이면 필수, UNREGISTERED · EXPIRED이면 비어 있다. RELEASED는 해제 전 값을 그대로 둔다(계정 삭제 참가자가 이 열로 찾는다, BR2.10). 외래 키는 ON DELETE 없음(RESTRICT)이다" }
      - { name: registration_code, type: "string(8)", required: true, constraints: "알파벳 [2-9A-HJKMNP-Z] 8글자 대문자. UNREGISTERED 행끼리 유일(부분 유일 색인). 등록 뒤에도 05의 '코드 앞 4자리' 표시를 위해 남기고, 응답이 유실돼 같은 코드를 다시 보낸 같은 보호자의 재시도를 REGISTERED 행에서 알아보는 데도 쓴다(BR2.1 ②). EXPIRED 행도 NOT NULL 값이 그대로 남고 UNREGISTERED 부분 유일 색인에서만 빠진다(BR1.4)" }
      - { name: code_issued_at, type: timestamp, required: true }
      - { name: code_expires_at, type: timestamp, required: true, constraints: "code_issued_at + 24시간(bootstrap.code-ttl)" }
      - { name: bootstrap_secret_hash, type: "string(64)", required: true, unique: true, constraints: "bootstrap 비밀의 SHA-256 소문자 16진수. 비밀 원문은 저장하지 않는다" }
      - { name: device_token_hash, type: "string(64)", required: false, unique: true, constraints: "기기 토큰의 SHA-256. claim 전에는 비어 있다. 첫 연결 전(first_connected_at이 비어 있는 동안)의 claim 재호출은 새 해시로 덮어써 앞 토큰을 무효로 만든다(회전, BR3.1 · BR3.3). RELEASED가 되면 비운다(재등록 · 계정 삭제, BR3.3)" }
      - { name: token_issued_at, type: timestamp, required: false, constraints: "토큰을 발급한 마지막 시각(회전 포함)" }
      - { name: first_connected_at, type: timestamp, required: false, constraints: "첫 WSS 인증(verify-token 성공) 시각. 값이 생기면 claim이 닫힌다" }
      - { name: registration_status, type: "string(20)", required: true, allowed_values: [UNREGISTERED, REGISTERED, RELEASED, EXPIRED], default: UNREGISTERED }
      - { name: registered_at, type: timestamp, required: false }
      - { name: released_at, type: timestamp, required: false }
      - { name: release_reason, type: "string(30)", required: false, allowed_values: [REREGISTERED, ACCOUNT_DELETED] }
      - { name: reported_connection_state, type: "string(20)", required: false, allowed_values: [OFFLINE, CONNECTED, UNSTABLE, DISCONNECTED, UNRESPONSIVE], constraints: "Realtime이 보고한 값의 사본(보고의 state, 보고에서 필수 — 없으면 400 INVALID_ENVELOPE, BR4.1 · J59(i)). 한 번도 보고가 없으면 비어 있고, 이 기기는 BR4.3 ②로 UNRESPONSIVE처럼 보여 주되 교체 등록은 막지 않는다(BR2.4 · J46)" }
      - { name: reported_at, type: timestamp, required: false, constraints: "사본에 반영한 가장 늦은 보고 시각. PUT 보고는 reportedAt(Realtime이 그 보고가 담은 연결 행을 읽은 시각이며 전송 시각이 아니다, BR4.8)이고 보고에서 필수다(없으면 400 INVALID_ENVELOPE, J59(i)). 이벤트 경로는 DeviceStatusChanged를 받은 Inbox 행의 occurred_at(상태 UPDATE 직전에 Realtime 앱 Clock으로 읽은 시각이며 트랜잭션 시작 시각 now()가 아니다)이다. 둘은 같은 Realtime 시계라 서로 비교한다. 읽고 비교하고 쓰지 않고 'WHERE registration_status = REGISTERED AND (reported_at IS NULL OR reported_at < :t)' 조건부 UPDATE 한 문장으로만 올리므로 줄어들지 않는다(BR4.1 · BR4.2)" }
      - { name: last_seen_at, type: timestamp, required: false, constraints: "보고의 lastSeenAt(DeviceConnection.last_seen_at의 사본). 보고에 없으면 COALESCE로 기존 값을 유지한다(BR4.1)" }
      - { name: connection_since, type: timestamp, required: false, constraints: "보고의 since" }
      - { name: rtt_ms, type: int, required: false, constraints: ">= 0, 보고의 rttMs(DeviceConnection.last_rtt_ms의 사본). 보고에 없으면 COALESCE로 기존 값을 유지한다(BR4.1)" }
      - { name: recent_disconnect_count, type: int, required: true, default: 0, constraints: ">= 0, 최근 5분 창 끊김 횟수(Realtime 값). 보고의 recentDisconnects(DeviceConnection.recent_disconnects의 사본). 보고에 없으면 SET절의 COALESCE(:n, 기존 열)로 기존 값을 유지해 NOT NULL 열에 NULL을 쓰지 않는다(BR4.1)" }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }
      - { name: row_version, type: bigint, required: true, default: 0, constraints: "낙관적 잠금" }
    constraints:
      - "부분 유일: guardian_id (WHERE registration_status = 'REGISTERED') — 계정당 활성 기기 하나(AC2.2.7). PostgreSQL의 유일 색인은 문장마다 즉시 검사되고 부분 색인은 DEFERRABLE로 만들 수 없으므로, 교체 등록은 옛 행을 먼저 RELEASED로 바꾼 뒤 새 행을 REGISTERED로 바꾼다(BR2.5). 위반(23505)은 409 DEVICE_ALREADY_ACTIVE로 번역한다(BR2.4)"
      - "부분 유일: registration_code (WHERE registration_status = 'UNREGISTERED') — 발급 중인 코드끼리 겹치지 않는다. EXPIRED 행은 registration_code NOT NULL 값이 남은 채 이 색인에서만 빠지므로 같은 코드를 새 발급에서 다시 뽑아도 된다(BR1.4)"
      - "검사: registration_status = 'REGISTERED' 이면 guardian_id · registered_at이 반드시 있다. device_token_hash는 claim 뒤에만 채워진다"
      - "검사: registration_status = 'RELEASED' 이면 released_at · release_reason이 있고 device_token_hash는 비어 있다"
      - "검사: registration_status IN ('UNREGISTERED','EXPIRED') 이면 guardian_id는 비어 있다"
      - "상태 전이는 UNREGISTERED → REGISTERED · EXPIRED, REGISTERED → RELEASED만 허용(되돌림 · 건너뜀 없음, EXPIRED · RELEASED는 끝)"
      - "외래 키: guardian_id → core_guardian.id는 ON DELETE 없음(RESTRICT)이고 core_activity.device_id → core_device.id도 같다. 계정 삭제는 core_activity(U4 참가자) → core_device(Device 참가자, BR2.10) → core_guardian(U2) 순서여야 하며 RESTRICT가 이 순서를 강제한다"
      - "삭제: EXPIRED이면서 7일이 지난 행은 정리 작업이 지운다(BR1.4). 등록 전 UNREGISTERED(24시간 뒤 EXPIRED가 된다) · EXPIRED 행은 guardian_id가 없어 계정 삭제 참가자의 대상이 아니며 이 7일 정리 작업이 지운다(BR2.10). 계정 삭제 참가자는 guardian_id가 같은 행(REGISTERED는 RELEASED로 바꾼 뒤, 재등록으로 RELEASED가 된 옛 행 포함)을 지운다(BR2.10)"
    relationships:
      - { to: Guardian, cardinality: "many-to-one(활성은 최대 1)", direction: "Device → Guardian", note: "같은 core 스키마라 DB 외래 키를 두며 ON DELETE 없음(RESTRICT)" }
      - { to: DeviceConnection, cardinality: "one-to-one(논리)", direction: "Device ← DeviceConnection", note: "스키마가 달라 논리 참조(device_id)" }

  - name: CoreOutboxEvent
    table: core_outbox_event
    schema: core
    owner_component: CoreMessaging
    u1_role: owned
    description: Core가 Realtime에 보낼 영속 이벤트(DeviceReleased · DeletionRequested). 업무 변경과 같은 트랜잭션에서 저장하고 트랜잭션 밖에서 발송한다.
    attributes:
      - { name: event_id, type: uuid, required: true, unique: true }
      - { name: event_type, type: "string(100)", required: true, allowed_values: [DeviceReleased, DeletionRequested] }
      - { name: schema_version, type: int, required: true, default: 1, constraints: ">= 1 (ERD v0.2 추가)" }
      - { name: occurred_at, type: timestamp, required: true, constraints: "envelope의 occurredAt. 업무 변경 트랜잭션의 시각이며 받는 쪽이 순서를 가릴 때의 기준이다(BR4.1 · BR5.10, ERD v0.2 추가)" }
      - { name: aggregate_type, type: "string(100)", required: true, allowed_values: [ActivitySession, Device, DataDeletionRequest], constraints: "envelope 필수 필드(BR5.4). 모든 Core 이벤트에 집합체가 있다" }
      - { name: aggregate_id, type: uuid, required: true, constraints: "envelope 필수 필드(BR5.4)" }
      - { name: guardian_id, type: uuid, required: false, constraints: "계정 삭제 참가자가 찾는 열(ERD v0.2 추가). 보호자와 무관한 이벤트는 비어 있다" }
      - { name: payload_json, type: json, required: true, constraints: "전달 전까지만 내용을 가진다. PUBLISHED가 되는 즉시 {} 로 비운다(BR5.11). FAILED 행은 created_at 7일 뒤에 비운다. 전사 원문 · 원음 · 비밀을 담지 않는다" }
      - { name: payload_hash, type: "string(64)", required: true, constraints: "정규화 payload의 SHA-256. 비운 뒤에도 남는다(충돌 판정용, ERD v0.2 추가)" }
      - { name: status, type: "string(30)", required: true, allowed_values: [PENDING, PUBLISHED, FAILED], default: PENDING }
      - { name: retry_count, type: int, required: true, default: 0, constraints: ">= 0, <= 20. retry_count가 20인 행이 또 실패하면 FAILED가 되고 값은 20으로 남는다(BR5.3)" }
      - { name: available_at, type: timestamp, required: true, constraints: "이 시각 이후에 발송 대상" }
      - { name: published_at, type: timestamp, required: false }
      - { name: last_error, type: text, required: false, constraints: "상태 코드 · 예외 종류만. 페이로드 내용을 넣지 않는다" }
      - { name: purged_at, type: timestamp, required: false, constraints: "payload를 비운 시각(ERD v0.2 추가)" }
      - { name: created_at, type: timestamp, required: true }
    constraints:
      - "색인: (status, available_at, created_at) — 발송 폴러가 쓴다"
      - "색인: (guardian_id) — Core 쪽 계정 삭제 참가자(BR5.14, 앱마다 하나씩 둘 중 Core 참가자이며 U2 삭제 지휘가 부른다)가 쓴다. 참가자는 PUBLISHED · FAILED 행만 지우고 PENDING 행(DeviceReleased · DeletionRequested)은 남긴다"
      - "PUBLISHED이면 purged_at이 채워지거나 payload가 이미 비어 있다"
    relationships: []

  - name: CoreInboxEvent
    table: core_inbox_event
    schema: core
    owner_component: CoreMessaging
    u1_role: owned
    description: Realtime이 보낸 영속 이벤트(SessionStateChanged · SessionRecordCaptured · DeviceStatusChanged · DeletionCompleted)를 영속 수락한 기록. 같은 eventId는 한 번만 업무에 반영한다.
    attributes:
      - { name: event_id, type: uuid, required: true, unique: true }
      - { name: source_service, type: "string(50)", required: true, allowed_values: [REALTIME] }
      - { name: event_type, type: "string(100)", required: true }
      - { name: schema_version, type: int, required: true, default: 1 }
      - { name: occurred_at, type: timestamp, required: true, constraints: "수신 envelope의 occurredAt 값. DeviceStatusChanged처럼 시각으로 최신을 가리는 처리기(BR4.1)가 재호출 · 재시작 복구 때도 DB 행만으로 비교할 수 있게 저장한다(ERD v0.2 추가)" }
      - { name: aggregate_id, type: uuid, required: true, constraints: "envelope 필수 필드(BR5.4). aggregateType은 저장하지 않는다 — 처리기는 event_type으로 고른다(ERD v0.2 추가)" }
      - { name: guardian_id, type: uuid, required: false, constraints: "ERD v0.2 추가" }
      - { name: payload_json, type: json, required: true, constraints: "처리 끝(PROCESSED · IGNORED)이면 {} 로 비운다(BR5.11). DEAD 행은 received_at 7일 뒤에 비운다" }
      - { name: payload_hash, type: "string(64)", required: true }
      - { name: status, type: "string(30)", required: true, allowed_values: [RECEIVED, PROCESSED, IGNORED, DEAD], default: RECEIVED }
      - { name: retry_count, type: int, required: true, default: 0, constraints: ">= 0, <= 5 (처리기 실패 횟수). 실패할 때마다 +1이고 1 · 2 · 4 · 8 · 16초 뒤에 다시 부른다. 다섯 번 다시 불러도 실패하면 6번째 실패는 DEAD가 되고 값은 5로 남는다(BR5.8)" }
      - { name: next_attempt_at, type: timestamp, required: false, constraints: "RECEIVED 행에서는 NULL이 아니다. INSERT 때 received_at으로 채워 방금 받은 행도 폴러에 잡히게 하고(BR5.6), 처리기를 부르기 전 선점 UPDATE가 now + 30초로 올리며(BR5.7), 처리기가 실패하면 다음 호출 시각으로 갱신한다(BR5.8). 앱 시작 때는 모든 RECEIVED 행을 now로 당긴다(BR5.7 · BR5.12). 처음부터 IGNORED로 들어온 행은 비어도 된다(BR5.9). ERD v0.2 추가" }
      - { name: received_at, type: timestamp, required: true }
      - { name: processed_at, type: timestamp, required: false }
      - { name: last_error, type: text, required: false }
      - { name: purged_at, type: timestamp, required: false }
    constraints:
      - "event_id가 기본 키라 같은 eventId는 한 행뿐이다(중복 차단의 근거)"
      - "색인: (status, next_attempt_at) — 처리기 재호출 폴러"
      - "검사: status = 'RECEIVED' 이면 next_attempt_at이 NULL이 아니다. RECEIVED가 아닌 행은 비어도 된다"
      - "모르는 eventType(처리기 없음)이거나 schemaVersion이 앱이 아는 최대값보다 큰 이벤트는 RECEIVED를 거치지 않고 처음부터 IGNORED로 payload를 비워(purged_at 채움) 커밋하고 200으로 답한다 — 부를 처리기가 없으므로 next_attempt_at은 비어도 된다(BR5.6 · BR5.9)"
      - "RECEIVED로 오래 남은 행: 200 직후 처리기를 부르기 전에 앱이 죽었거나 선점(now + 30초)이 지나도록 처리기가 끝나지 않은 행이다. 폴러가 next_attempt_at이 지난 행을 다시 집는다. 앱 시작 때는 HTTP 수신을 열기 전에 'SET next_attempt_at = now WHERE status = RECEIVED' 한 문장으로 모든 RECEIVED 행의 시각을 당긴 뒤(retry_count는 바꾸지 않는다) 평소의 선점 경로(BR5.7)로 한 번씩 처리기를 부른다(BR5.12). 처리기는 멱등이므로 겹쳐도 결과가 같다. 계속 실패하면 DEAD가 되고 DEAD의 payload는 7일 뒤 비운다(BR5.8 · BR5.11)"
      - "색인: (guardian_id) — Core 쪽 계정 삭제 참가자(BR5.14, 앱마다 하나씩 둘 중 Core 참가자)가 쓴다. 참가자는 PROCESSED · IGNORED · DEAD 행만 지우고 RECEIVED 행은 남긴다. 단 대화 원문을 담은 SessionRecordCaptured의 RECEIVED 행은 payload_json을 {} 로 비우고 purged_at을 채운 뒤 IGNORED로 닫는다(BR5.14)"
    relationships: []

  - name: RealtimeOutboxEvent
    table: realtime_outbox_event
    schema: realtime
    owner_component: RealtimeMessaging
    u1_role: owned
    description: Realtime이 Core에 보낼 영속 이벤트(SessionStateChanged · SessionRecordCaptured · DeviceStatusChanged · DeletionCompleted). CoreOutboxEvent와 같은 모양 · 같은 규칙이다.
    attributes:
      - { name: event_id, type: uuid, required: true, unique: true }
      - { name: event_type, type: "string(100)", required: true, allowed_values: [SessionStateChanged, SessionRecordCaptured, DeviceStatusChanged, DeletionCompleted] }
      - { name: schema_version, type: int, required: true, default: 1, constraints: ">= 1" }
      - { name: occurred_at, type: timestamp, required: true, constraints: "envelope의 occurredAt이며 받는 쪽(Core)이 DeviceStatusChanged의 최신을 가릴 때의 기준이다(BR4.1 · BR5.10). DeviceStatusChanged는 상태 UPDATE 직전에 Realtime 앱 Clock으로 읽은 시각(트랜잭션 시작 시각 now()가 아니다)이고 PUT 보고의 reportedAt(연결 행을 읽은 시각)과 같은 Realtime 시계를 쓴다(BR4.8). 그 밖의 이벤트는 상태가 바뀐 업무 트랜잭션의 시각이다(ERD v0.2 추가)" }
      - { name: aggregate_type, type: "string(100)", required: true, allowed_values: [ActivitySession, Device, DataDeletionRequest], constraints: "envelope 필수 필드(BR5.4)" }
      - { name: aggregate_id, type: uuid, required: true, constraints: "envelope 필수 필드(BR5.4)" }
      - { name: guardian_id, type: uuid, required: false }
      - { name: payload_json, type: json, required: true, constraints: "CoreOutboxEvent와 같다(PUBLISHED가 되는 즉시 {} 로 비우고 FAILED 행은 7일 뒤 비운다, BR5.11). SessionRecordCaptured는 대화 원문을 담으므로 계정 삭제 참가자가 상태와 무관하게 즉시 비운다(BR5.14)" }
      - { name: payload_hash, type: "string(64)", required: true }
      - { name: status, type: "string(30)", required: true, allowed_values: [PENDING, PUBLISHED, FAILED], default: PENDING }
      - { name: retry_count, type: int, required: true, default: 0, constraints: ">= 0, <= 20" }
      - { name: available_at, type: timestamp, required: true }
      - { name: published_at, type: timestamp, required: false }
      - { name: last_error, type: text, required: false, constraints: "상태 코드 · 예외 종류만. 계정 삭제 참가자가 PENDING SessionRecordCaptured 행을 닫을 때는 ACCOUNT_DELETED(BR5.14)" }
      - { name: purged_at, type: timestamp, required: false }
      - { name: created_at, type: timestamp, required: true }
    constraints:
      - "CoreOutboxEvent와 같은 색인 · 검사"
      - "색인: (guardian_id) — Realtime 쪽 계정 삭제 참가자(BR5.14, 앱마다 하나씩 둘 중 Realtime 참가자)가 쓴다. Realtime의 DeletionRequested 처리기가 U4의 호출 순서 안에서 부르며 realtime 스키마에만 쓴다(BR8.1). PUBLISHED · FAILED 행만 지우고 PENDING 행(DeletionCompleted 등)은 남기며, DeletionCompleted가 PUBLISHED가 된 뒤 U4가 참가자를 다시 불러 남은 행을 지운다. 대화 원문을 담은 SessionRecordCaptured는 상태와 무관하게 payload를 비우고 PENDING이면 FAILED(ACCOUNT_DELETED)로 닫는다(BR5.14)"
    relationships: []

  - name: RealtimeInboxEvent
    table: realtime_inbox_event
    schema: realtime
    owner_component: RealtimeMessaging
    u1_role: owned
    description: Core가 보낸 영속 이벤트(DeviceReleased · DeletionRequested)를 영속 수락한 기록. CoreInboxEvent와 같은 모양 · 같은 규칙이다.
    attributes:
      - { name: event_id, type: uuid, required: true, unique: true }
      - { name: source_service, type: "string(50)", required: true, allowed_values: [CORE] }
      - { name: event_type, type: "string(100)", required: true }
      - { name: schema_version, type: int, required: true, default: 1 }
      - { name: occurred_at, type: timestamp, required: true, constraints: "수신 envelope의 occurredAt 값(CoreInboxEvent와 같다, ERD v0.2 추가)" }
      - { name: aggregate_id, type: uuid, required: true, constraints: "envelope 필수 필드(BR5.4). aggregateType은 저장하지 않는다(ERD v0.2 추가)" }
      - { name: guardian_id, type: uuid, required: false }
      - { name: payload_json, type: json, required: true, constraints: "CoreInboxEvent와 같다(처리 끝이면 {} 로 비우고 DEAD는 7일 뒤 비운다, BR5.11)" }
      - { name: payload_hash, type: "string(64)", required: true }
      - { name: status, type: "string(30)", required: true, allowed_values: [RECEIVED, PROCESSED, IGNORED, DEAD], default: RECEIVED }
      - { name: retry_count, type: int, required: true, default: 0, constraints: ">= 0, <= 5. CoreInboxEvent와 같다 — 6번째 실패는 DEAD가 되고 값은 5로 남는다(BR5.8)" }
      - { name: next_attempt_at, type: timestamp, required: false, constraints: "CoreInboxEvent와 같다. RECEIVED 행에서는 NULL이 아니며 INSERT 때 received_at, 처리기 선점 때 now + 30초, 처리기 실패 때 다음 호출 시각, 앱 시작 때 now로 당긴다. 처음부터 IGNORED로 들어온 행은 비어도 된다(BR5.6 · BR5.7 · BR5.8 · BR5.9 · BR5.12)" }
      - { name: received_at, type: timestamp, required: true }
      - { name: processed_at, type: timestamp, required: false }
      - { name: last_error, type: text, required: false }
      - { name: purged_at, type: timestamp, required: false }
    constraints:
      - "CoreInboxEvent와 같은 색인 · 검사(모르는 eventType · 새 schemaVersion은 처음부터 IGNORED, 앱 시작 때 RECEIVED 행의 next_attempt_at을 당긴 뒤 평소 선점 경로로 재처리 포함)"
      - "색인: (guardian_id) — Realtime 쪽 계정 삭제 참가자(BR5.14, 앱마다 하나씩 둘 중 Realtime 참가자)가 쓴다. PROCESSED · IGNORED · DEAD 행만 지우고 RECEIVED 행(DeviceReleased · DeletionRequested)은 남긴다(BR5.14)"
    relationships: []

  # ───────────── 스켈레톤이 지나가는 최소 엔터티 (소유 유닛이 완성) ─────────────
  - name: Guardian
    table: core_guardian
    schema: core
    owner_component: GuardianIdentity
    u1_role: minimal
    u1_columns_used: [id, status]
    description: 보호자 계정. U1은 dev 프로필의 DevSeedRunner가 넣는 시드 한 명만 쓴다(구글 로그인 · 계정 삭제 접수는 U2, J44). 이 행을 가리키는 core_device의 외래 키는 ON DELETE 없음(RESTRICT)이라 보호자 행 삭제는 기기 · 활동 행 삭제 뒤 맨 마지막이다(BR2.10).
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: status, type: "string(30)", required: true, allowed_values: [ACTIVE, DELETION_REQUESTED], default: ACTIVE }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }
    constraints: []
    relationships: []

  - name: LoginSession
    table: "core_spring_session · core_spring_session_attributes"
    schema: core
    owner_component: GuardianIdentity
    u1_role: minimal
    u1_columns_used: [primary_id, session_id, principal_name, expiry_time]
    description: Spring Session JDBC 표준 테이블. 시험 로그인(dev 프로필 한정)과 이후 구글 로그인(U2, demo · prod)이 같은 세션 계층을 쓴다(J44).
    attributes:
      - { name: primary_id, type: "string(36)", required: true, unique: true }
      - { name: principal_name, type: "string(100)", required: false, constraints: "guardianId 문자열" }
      - { name: expiry_time, type: bigint, required: true, constraints: "마지막 요청 + 30일(login.session-idle, 갱신형)" }
    constraints: []
    relationships: []

  - name: Consent
    table: core_consent
    schema: core
    owner_component: ConsentPolicy
    u1_role: minimal
    u1_columns_used: [actor_guardian_id, child_id, consent_type, consent_status, policy_version, effective_from, effective_to]
    description: 4항목 동의 기록. U1은 dev 시드로 4항목을 GIVEN으로 넣고 시작 게이트(ConsentGate)가 읽는다. 읽은 정책 버전 문자열과 4항목 값은 세션 패키지의 consentSnapshot으로 Realtime에 건네진다(ActivitySession.consent_snapshot_json).
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: actor_guardian_id, type: uuid, required: true, references: Guardian.id }
      - { name: child_id, type: uuid, required: true, references: Child.id }
      - { name: consent_type, type: "string(50)", required: true, allowed_values: [VOICE_AND_TEXT, GUARDIAN_VIEW, REPORT_SHARING, RAW_AUDIO] }
      - { name: consent_status, type: "string(30)", required: true, allowed_values: [GIVEN, WITHDRAWN] }
      - { name: consent_version, type: int, required: true }
      - { name: policy_version, type: "string(50)", required: false }
      - { name: effective_from, type: timestamp, required: true }
      - { name: effective_to, type: timestamp, required: false }
      - { name: created_at, type: timestamp, required: true }
    constraints: []
    relationships: []

  - name: Child
    table: core_child
    schema: core
    owner_component: ChildProfile
    u1_role: minimal
    u1_columns_used: [id, guardian_id, display_name, age, interests_json, status]
    description: 보호자당 아이 한 명. U1은 시드 한 명을 세션 패키지의 대화 문맥으로 쓴다.
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: guardian_id, type: uuid, required: true, unique: true, references: Guardian.id }
      - { name: display_name, type: "string(100)", required: true, constraints: "1 ~ 10자 검증은 U2" }
      - { name: age, type: int, required: false, constraints: "6 ~ 12 검증은 U2" }
      - { name: interests_json, type: json, required: false }
      - { name: status, type: "string(30)", required: true }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }
      - { name: deleted_at, type: timestamp, required: false }
    constraints: []
    relationships: []

  - name: StopExpression
    table: core_stop_expression
    schema: core
    owner_component: ChildProfile
    u1_role: minimal
    u1_columns_used: []
    description: 아이별 추가 종료 표현. U1은 표만 만들고 행을 넣지 않는다 — 기본 종료 표현 '무중아 그만할래'는 코드 상수이고 세션 패키지의 stopExpressions로 건네져 Realtime이 realtime_session_stop_expression에 저장하므로(BR6.5) 이 표에 두지 않는다. 추가 표현의 관리는 U2.
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: child_id, type: uuid, required: true, references: Child.id }
      - { name: expression_text, type: "string(20)", required: true, constraints: "J12: expression_suffix를 expression_text 20자로 바꾼다(ERD v0.2)" }
      - { name: normalized_text, type: "string(40)", required: true }
      - { name: sort_order, type: int, required: true }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }
    constraints: []
    relationships: []

  - name: ApprovedContent
    table: core_approved_content
    schema: core
    owner_component: ApprovedContentCatalog
    u1_role: minimal
    u1_columns_used: [id, content_code, content_type]
    description: 승인 문구 종류. U1 시드는 CLOSING(마무리 인사)과 WAITING_FILLER(게이트 폐기 때 대체 문구) 두 건이다(J52). 정식 음성은 실제 녹음(B26) 전이므로 dev 시드는 임시 문구 + 임시 WAV 두 개를 둔다(J60).
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: content_code, type: "string(100)", required: true, unique: true }
      - { name: content_type, type: "string(50)", required: true }
      - { name: created_at, type: timestamp, required: true }
    constraints: []
    relationships: []

  - name: ApprovedContentVersion
    table: core_approved_content_version
    schema: core
    owner_component: ApprovedContentCatalog
    u1_role: minimal
    u1_columns_used: [id, content_id, version, content_text, audio_path, status]
    description: 승인 문구 버전. 세션은 시작 때 쓸 버전을 고정한다(A5).
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: content_id, type: uuid, required: true, references: ApprovedContent.id }
      - { name: version, type: int, required: true }
      - { name: content_text, type: text, required: true }
      - { name: audio_path, type: "string(500)", required: false, constraints: "Nginx 정적 경로 아래 상대 경로. 개인정보 없는 파일. U1 dev 시드는 개발용 임시 WAV 두 개(CLOSING은 3초 이내)를 가리킨다(J60)" }
      - { name: status, type: "string(30)", required: true, allowed_values: [DRAFT, APPROVED, RETIRED] }
      - { name: created_at, type: timestamp, required: true }
    constraints:
      - "유일: (content_id, version)"
    relationships: []

  - name: Activity
    table: core_activity
    schema: core
    owner_component: ActivityCoordinator
    u1_role: minimal
    u1_columns_used: [session_id, guardian_id, child_id, device_id, activity_type, status, status_version, end_reason, failure_code, confirmed, local_failed, start_result_code, start_idempotency_key, request_hash, requested_at, started_at, ended_at, observed_at]
    description: Core가 가진 세션 사본. 시작 검사 · 04 · 12 · 17의 읽기 원천이며 Realtime의 SessionStateChanged 이벤트로만 진행한다(J5). 예외는 둘이다. 하나는 Core가 Realtime의 이벤트 없이 직접 FAILED로 닫는 로컬 FAILED(local_failed)로 원인은 확정 거절(BR6.6), 호출 실패(BR6.7), PREPARING 10초 시한(BR6.12 ①) 셋이다. 다른 하나는 시작 거절 전에 Realtime 진행 조회로 낡은 사본을 대조해 반영하는 BR6.1 ②다(BR6.17). 보호자당 종료 아닌 세션은 하나다.
    attributes:
      - { name: session_id, type: uuid, required: true, unique: true, constraints: "Core가 시작 요청 때 발급(J11)" }
      - { name: guardian_id, type: uuid, required: true, references: Guardian.id }
      - { name: child_id, type: uuid, required: true, references: Child.id }
      - { name: device_id, type: uuid, required: true, references: Device.id, constraints: "외래 키는 ON DELETE 없음(RESTRICT)이라 core_activity 행이 core_device 행보다 먼저 지워져야 한다(BR2.10)" }
      - { name: activity_type, type: "string(30)", required: true, allowed_values: [FREE_TALK, PRACTICE], constraints: "U1은 FREE_TALK만" }
      - { name: status, type: "string(30)", required: true, allowed_values: [REQUESTED, PREPARING, DEVICE_ACK, WAITING_CHILD, ACTIVE, CLOSING, ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED], default: REQUESTED }
      - { name: status_version, type: int, required: true, default: 0, constraints: "Realtime이 매기는 세션별 단조 증가 값. 0 = Core만 아는 REQUESTED(BR6.6 · BR6.7 · BR6.12 ①로 닫은 로컬 FAILED도 0 그대로)" }
      - { name: end_reason, type: "string(50)", required: false, allowed_values: [GUARDIAN_STOP, CHILD_STOP, SILENCE, DISCONNECT, CONSENT_WITHDRAWN, UNCONFIRMED, DECLINED, FAILED], constraints: "payload 값 그대로. U1 대응은 ENDED = GUARDIAN_STOP, ENDED_UNCONFIRMED = UNCONFIRMED, ABORTED = DISCONNECT, FAILED = FAILED(로컬 FAILED 포함, BR6.8 · BR6.14)" }
      - { name: failure_code, type: "string(50)", required: false, allowed_values: [DEVICE_NO_ACK, SCENARIO_GEN_FAILED, PROVIDER_UNAVAILABLE, SERVER_RESTART], constraints: "payload 값 그대로. 로컬 FAILED이면 비어 있다(BR6.1 ② · BR6.6 · BR6.7 · BR6.12 ①)" }
      - { name: confirmed, type: boolean, required: true, default: false, constraints: "기기 멈춤을 확인했는가" }
      - { name: local_failed, type: boolean, required: true, default: false, constraints: "Core가 Realtime 이벤트 없이 직접 FAILED로 닫은 행이면 true(시작 호출 시간 초과 · 연결 실패 · 503 BR6.7, Realtime의 확정 거절 409 · 423 BR6.6, PREPARING 10초 시한 BR6.12 ①, BR6.1 ②의 대조에서 Realtime이 모르는 세션으로 밝혀진 사본). true인 행은 이후 SessionStateChanged로 바뀌지 않는다(BR5.10 · BR6.14) — 다만 종료 상태가 아닌 이벤트가 오면 CORE_ABANDONED 취소를 Realtime에 다시 보낸다(BR6.14). ERD v0.2 신설 열" }
      - { name: start_result_code, type: int, required: false, allowed_values: [202, 423, 409, 503], constraints: "local_failed이면 필수이고 아니면 비어 있다. 로컬 FAILED로 닫을 때 그 시작 요청의 응답 상태를 적어 같은 idempotencyKey 재요청이 그대로 재생하게 한다(BR6.2). 503 = 호출 실패 · 시간 초과(BR6.7, REALTIME_UNAVAILABLE), 423 · 409 = Realtime 확정 거절을 번역한 DEVICE_NOT_STABLE · SESSION_IN_PROGRESS(BR6.6), 202 = PREPARING 10초 시한(BR6.12 ①)과 BR6.1 ②의 대조로 닫은 행으로 응답은 {sessionId, status: REQUESTED}이고 12가 폴링으로 FAILED를 받아 08로 돌아간다. 응답 본문은 상태 코드에서 정해지므로 코드만 저장한다. ERD v0.2 신설 열" }
      - { name: start_idempotency_key, type: "string(100)", required: true }
      - { name: request_hash, type: "string(64)", required: true, constraints: "시작 요청 본문의 SHA-256. 같은 키에 다른 본문이면 IDEMPOTENCY_CONFLICT 판정에 쓴다" }
      - { name: requested_at, type: timestamp, required: true }
      - { name: started_at, type: timestamp, required: false, constraints: "status가 처음 ACTIVE로 바뀐 SessionStateChanged 이벤트의 at(BR6.14). 낡은 ACTIVE 이벤트(statusVersion이 크지 않아 사본 갱신은 버려지는 것)로도 채워질 수 있다 — 이 열이 비어 있을 때만 'WHERE started_at IS NULL' 조건부로 이 열 하나만 채우고 status · status_version은 건드리지 않는다" }
      - { name: ended_at, type: timestamp, required: false, constraints: "종료 상태(ENDED · ENDED_UNCONFIRMED · ABORTED · FAILED) 이벤트의 at(BR6.14). 로컬 FAILED는 Core가 닫은 시각" }
      - { name: observed_at, type: timestamp, required: false, constraints: "Core가 사본을 마지막으로 갱신한 시각(이벤트를 반영한 처리 시각이며 이벤트 payload의 at이 아니다, BR6.14). 12 응답의 observedAt은 이 값과 Core가 마지막으로 Realtime 진행 조회에 성공한 시각 중 늦은 값이고 조회 성공 시각은 Core 메모리에만 둬도 된다. stale = now − observedAt > core.device-status-stale(30초)이고 조회 한 번의 실패만으로는 stale이 아니다(BR6.15)" }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }
    constraints:
      - "유일: (guardian_id, start_idempotency_key) — 같은 키 재요청은 같은 세션이다. 동시에 겹친 INSERT가 이 유일 위반이면 승자 행을 다시 읽어 같은 응답을 돌려주고, 그 키의 행이 로컬 FAILED이면 start_result_code를 그대로 재생한다(BR6.2)"
      - "부분 유일: guardian_id (WHERE status IN ('REQUESTED','PREPARING','DEVICE_ACK','WAITING_CHILD','ACTIVE','CLOSING')) — 진행 중 세션 하나(AC3.1.8)"
      - "status_version은 줄어들지 않는다. 사본 갱신은 읽고 비교하고 쓰지 않고 한 문장의 조건부 UPDATE로 한다: WHERE session_id = :id AND status_version < :v AND NOT (status = 'FAILED' AND local_failed). 행 수가 0이면 행을 다시 읽는다 — 사본이 로컬 FAILED이고 payload의 status가 종료 상태가 아니면 CORE_ABANDONED 취소를 다시 보내고(실패하면 예외 → BR5.8 재호출, BR6.14), 그 밖의 0행(낮거나 같은 statusVersion)만 버린다(BR5.10)"
      - "검사: local_failed = true 이면 status = 'FAILED' · failure_code는 비어 있고 start_result_code가 있다. status_version은 BR6.6 · BR6.7 · BR6.12 ①로 닫은 행에서 0 그대로이고, BR6.1 ②의 대조로 닫은 행은 그때까지 반영한 값을 줄이지 않고 둔다"
      - "로컬 FAILED를 쓰는 문장은 BR6.6 · BR6.7 · BR6.12 ① 모두 'WHERE status = REQUESTED AND status_version = 0' 조건부다. 0행이면 Realtime 이벤트가 사본을 앞선 것이므로 로컬 FAILED를 쓰지 않고, BR6.6 · BR6.7은 202 {sessionId, status: REQUESTED}로 답하며 BR6.7은 취소도 보내지 않는다"
      - "BR6.1 ②의 대조: 막는 사본이 REQUESTED가 아니면 Realtime 진행 조회로 받은 status · statusVersion을 위 조건부 UPDATE로 반영한 뒤 다시 판정하고, Realtime이 모르는 세션이면 사본을 로컬 FAILED(failure_code 비움)로 닫는다(이벤트가 FAILED · DEAD로 끝나 사본이 영구히 낡은 경우의 계정 잠김 방지, BR6.17)"
      - "외래 키: device_id → core_device.id는 ON DELETE 없음(RESTRICT). 계정 삭제 순서는 core_activity → core_device → core_guardian이다(BR2.10)"
    relationships:
      - { to: Device, cardinality: many-to-one, direction: "Activity → Device", note: "DB 외래 키(ON DELETE 없음, RESTRICT)" }
      - { to: ActivitySession, cardinality: "one-to-one(논리)", direction: "Activity ← ActivitySession", note: "스키마가 달라 논리 참조(session_id)" }

  - name: ActivitySession
    table: realtime_activity_session
    schema: realtime
    owner_component: SessionLifecycle
    u1_role: minimal
    u1_columns_used: [id, guardian_id, child_id, device_id, activity_type, status, status_version, start_idempotency_key, gpt_live_session_id, consent_version, consent_checked_at, consent_snapshot_json, policy_version, data_collection_allowed, transcript_storage_allowed, child_context_json, closing_content_version_id, end_reason, end_detail, termination_requested_at, output_stopped_at, transport_closed_at, provider_closed_at, started_at, ended_at, row_version]
    description: Realtime이 가진 진행 중 세션의 주인 행. J12에 따라 practice_goal_selection_id · current_practice_stage는 이 표에서 뺀다(realtime_session_goal로 옮김). status_version과 동의 스냅샷 열(consent_snapshot_json · policy_version)을 더한다(ERD v0.2). 모든 전이는 'UPDATE ... SET status = :to, status_version = status_version + 1, row_version = row_version + 1 WHERE id = :id AND status = :from' 한 문장이고 행 수가 1일 때만 성립한다(BR6.8). INSERT는 ON CONFLICT (id) DO NOTHING으로 한다(BR6.6 ④).
    attributes:
      - { name: id, type: uuid, required: true, unique: true, constraints: "Core의 session_id와 같은 값" }
      - { name: guardian_id, type: uuid, required: true }
      - { name: child_id, type: uuid, required: true }
      - { name: device_id, type: uuid, required: true, constraints: "ERD v0.1의 voice_client_id를 논리 참조 device_id로 쓴다(둘 다 두지 않는다 — 아래 ERD v0.2 변경)" }
      - { name: activity_type, type: "string(30)", required: true }
      - { name: status, type: "string(30)", required: true, allowed_values: [PREPARING, DEVICE_ACK, WAITING_CHILD, ACTIVE, CLOSING, ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED], constraints: "REQUESTED는 Core 사본에만 있다" }
      - { name: status_version, type: int, required: true, constraints: "PREPARING = 1부터 전이마다 +1(ERD v0.2 추가)" }
      - { name: start_idempotency_key, type: "string(100)", required: true, constraints: "Core와 같은 (guardian_id, start_idempotency_key) 범위의 의미 — 같은 보호자의 같은 키는 같은 세션이다. 키 단독 유일이 아니다(다른 보호자가 우연히 같은 키를 써도 서로를 막지 않는다). 세션을 유일하게 가르는 것은 id다(BR6.6 ①)" }
      - { name: gpt_live_session_id, type: "string(255)", required: false, constraints: "공급자 세션 ID(세션 의존 명령 용). 외부로 노출하지 않는다" }
      - { name: consent_version, type: int, required: false, constraints: "ERD v0.1 열을 유지하되 NOT NULL을 NULL 허용으로 바꾼다. 세션 시작 때의 동의 정수 버전인데 U1 패키지에는 정수 버전이 없다(consentSnapshot에는 정책 버전 문자열 policyVersion만 있다). U1은 비워 두며 U3 · U4가 채운다" }
      - { name: consent_checked_at, type: timestamp, required: true, constraints: "ERD v0.1 열을 유지한다(NOT NULL). 동의 스냅샷을 확인한 시각. 세션 패키지에 별도 필드가 없어 U1은 Realtime이 시작 명령을 받은 시각으로 채운다(Core가 BR6.1 ①을 확인한 직후의 같은 요청이다)" }
      - { name: consent_snapshot_json, type: json, required: true, constraints: "패키지의 consentSnapshot 그대로 {policyVersion, voiceAndText, guardianView, reportSharing, rawAudio}. 다섯 값이 모두 있어야 한다(필수). 정책 버전 문자열 · 보호자 열람 동의 · 원음 동의(U3가 원음 저장 여부를 정할 값)를 담는 자리다(ERD v0.2 추가)" }
      - { name: policy_version, type: "string(50)", required: true, constraints: "consent_snapshot_json.policyVersion과 같은 값을 꺼내 둔 열(조회 · 감사용, ERD v0.2 추가)" }
      - { name: data_collection_allowed, type: boolean, required: true, constraints: "consent_snapshot_json에서 파생: data_collection_allowed = voiceAndText" }
      - { name: transcript_storage_allowed, type: boolean, required: true, constraints: "consent_snapshot_json에서 파생: transcript_storage_allowed = voiceAndText" }
      - { name: child_context_json, type: json, required: false, constraints: "displayName · age · interests만(NFR3). 08 입력 원문은 넣지 않는다" }
      - { name: intro_content_version_id, type: uuid, required: false, constraints: "ERD v0.1 열을 유지한다. 시작 때 고정한 인트로 승인 문구 버전(core 논리 참조). U1 패키지는 CLOSING · WAITING_FILLER만 고정하므로 비어 있고 인트로는 U4가 채운다" }
      - { name: closing_content_version_id, type: uuid, required: false, constraints: "시작 때 고정한 마무리 인사 버전(core 논리 참조)" }
      - { name: end_reason, type: "string(50)", required: false, constraints: "ENDED = GUARDIAN_STOP, ENDED_UNCONFIRMED = UNCONFIRMED, ABORTED = DISCONNECT, FAILED = FAILED(BR6.8). Core 취소 요청의 내부 reason CORE_ABANDONED는 요청 사유일 뿐 이 열에 쓰지 않는다 — 그 정리로 닫힌 세션도 FAILED(failure_code 비움)다(BR6.7 · J54)" }
      - { name: end_detail, type: text, required: false, constraints: "ERD v0.1 열을 유지한다. 종료 상세. U1은 FAILED일 때 failure_code(DEVICE_NO_ACK · PROVIDER_UNAVAILABLE · SERVER_RESTART, BR6.8 · BR6.12)를 적어 SessionStateChanged의 failureCode로 내보내고 그 밖의 종료와 CORE_ABANDONED 정리는 비운다. 전사 원문 · 개인정보를 넣지 않는다" }
      - { name: termination_requested_at, type: timestamp, required: false }
      - { name: output_stopped_at, type: timestamp, required: false }
      - { name: transport_closed_at, type: timestamp, required: false }
      - { name: provider_closed_at, type: timestamp, required: false }
      - { name: started_at, type: timestamp, required: false }
      - { name: ended_at, type: timestamp, required: false }
      - { name: row_version, type: bigint, required: true }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }
    constraints:
      - "부분 유일: guardian_id (WHERE status IN ('PREPARING','DEVICE_ACK','WAITING_CHILD','ACTIVE','CLOSING')) — Realtime 2차 방어(SESSION_IN_PROGRESS)"
      - "부분 유일: device_id (WHERE 같은 조건) — 기기당 진행 세션 하나"
      - "종료 상태(ENDED · ENDED_UNCONFIRMED · ABORTED · DECLINED · FAILED)는 되돌아가지 않는다"
      - "consent_snapshot_json과 파생 열: data_collection_allowed · transcript_storage_allowed는 voiceAndText와 같고, policy_version은 policyVersion과 같다. 세션 시작 뒤 바뀌지 않는다(스냅샷)"
      - "상태 전이는 WHERE id = :id AND status = :from 조건부 UPDATE 한 문장이며 행 수가 1일 때만 성립해 전이마다 승자가 하나다(BR6.8). status_version과 row_version이 함께 오른다. 출발 상태가 둘 이상인 FAILED 전이(시한 · 취소 · 재시작)는 세션 행을 FOR UPDATE로 읽어 현재 상태로 failure_code를 정한 뒤 'WHERE status = :읽은 상태'로 쓰고, 0행이면 한 번 다시 읽어 아직 출발 집합 안이면 다시 시도하며 20초 시한이 만난 ACTIVE · 종료 상태는 무시한다(BR6.8)"
      - "INSERT는 ON CONFLICT (id) DO NOTHING이다. 충돌이거나 보호자 · 기기 부분 유일 색인 위반이면 id로 다시 읽어 행이 있으면 현재 상태를 202로 돌려주고 없을 때만 409 SESSION_IN_PROGRESS다 — 시간 초과 재시도가 첫 호출과 겹쳐도 재시도가 실패를 만들지 않는다(BR6.6 ④)"
      - "세션의 40초 시계(BR6.12 ④, ACTIVE에서 START_SESSION을 받은 소켓의 마지막 기기 메시지 수신 시각)는 Realtime 메모리의 세션별 값이라 이 표에 열이 없다. 재시작하면 BR6.13이 비종료 세션을 모두 FAILED로 닫으므로 영속하지 않는다"
      - "Core 취소(BR6.7)가 만드는 10분 묘비(모르는 sessionId의 취소 · 늦은 시작 거절용)도 Realtime 메모리에만 두며 어느 표에도 저장하지 않는다. 재시작하면 사라지고 그때 늦게 만들어진 세션은 BR6.14의 재취소로 닫힌다"
    relationships:
      - { to: SessionEvent, cardinality: one-to-many, direction: "ActivitySession → SessionEvent" }
      - { to: Transcript, cardinality: one-to-many, direction: "ActivitySession → Transcript" }
      - { to: SessionStopExpression, cardinality: one-to-many, direction: "ActivitySession → SessionStopExpression" }

  - name: SessionEvent
    table: realtime_session_event
    schema: realtime
    owner_component: SessionLifecycle
    u1_role: minimal
    u1_columns_used: [id, session_id, event_type, op_id, payload_json, created_at]
    description: 세션 안에서 일어난 명령 · 전이의 기록. payload_json에는 전사 원문을 넣지 않는다.
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: session_id, type: uuid, required: true, references: ActivitySession.id }
      - { name: event_type, type: "string(100)", required: true }
      - { name: op_id, type: "string(100)", required: false, constraints: "명령 · 요청의 식별자. 끝내기 요청이 수락될 때 그 requestId를 여기에 기록한다(BR6.9)" }
      - { name: payload_json, type: json, required: false }
      - { name: created_at, type: timestamp, required: true }
    constraints: []
    relationships: []

  - name: SessionStopExpression
    table: realtime_session_stop_expression
    schema: realtime
    owner_component: SessionLifecycle
    u1_role: minimal
    u1_columns_used: [id, session_id, expression_text, is_default, sort_order]
    description: 세션이 시작 때 고정한 종료 표현. U1은 기본 종료 표현 '무중아 그만할래' 한 행(is_default true)만 쓴다. 이 표현은 코드 상수이고 Core가 세션 패키지의 stopExpressions로 건네면 Realtime이 세션마다 한 행으로 저장한다(BR6.5). 추가 표현(is_default false)은 U2 · U4가 더한다.
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: session_id, type: uuid, required: true, references: ActivitySession.id }
      - { name: expression_text, type: "string(100)", required: true }
      - { name: is_default, type: boolean, required: true, default: false, constraints: "ERD v0.1 열이다(열 추가 없음). 기본 종료 표현이면 true. U1은 세션마다 true 한 행만 쓴다" }
      - { name: sort_order, type: int, required: true }
      - { name: created_at, type: timestamp, required: true }
    constraints:
      - "U1에서는 세션마다 is_default = true 한 행(코드 상수 '무중아 그만할래')이다. core_stop_expression은 U1이 비워 두므로 이 행은 Core 표가 아니라 세션 패키지에서 온다(BR6.5)"
    relationships: []

  - name: Transcript
    table: realtime_transcript
    schema: realtime
    owner_component: ConversationLog
    u1_role: minimal
    u1_columns_used: [id, session_id, sequence_no, speaker, text, assembly_status, approved_for_display, started_at, ended_at]
    description: 전사 조각과 확정본. 보호자 응답에는 아이 전사와 게이트를 통과한 AI 말만 나간다(approved_for_display, J12).
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: session_id, type: uuid, required: true, references: ActivitySession.id }
      - { name: sequence_no, type: int, required: true, constraints: "세션 안에서 1부터 증가, 확정 전 조각은 같은 번호로 갱신" }
      - { name: speaker, type: "string(30)", required: true, allowed_values: [CHILD, AI] }
      - { name: text, type: text, required: true }
      - { name: assembly_status, type: "string(30)", required: true, allowed_values: [PARTIAL, FINAL] }
      - { name: review_needed, type: boolean, required: true, default: false }
      - { name: approved_for_display, type: boolean, required: true, default: false, constraints: "AI 말은 게이트 통과 뒤에만 true. 아이 전사는 true" }
      - { name: started_at, type: timestamp, required: false }
      - { name: ended_at, type: timestamp, required: false }
      - { name: created_at, type: timestamp, required: true }
      - { name: deleted_at, type: timestamp, required: false }
    constraints:
      - "유일: (session_id, sequence_no)"
    relationships: []

  - name: DeviceConnection
    table: realtime_device_connection
    schema: realtime
    owner_component: DeviceGateway
    u1_role: minimal
    u1_columns_used: [device_id, guardian_id, child_id, connection_state, connection_id, connection_since, last_seen_at]
    description: 기기 한 대의 연결 상태 원장. 연결 판정의 완성(UNSTABLE · 5분 창 · 지연 1초 · 히스테리시스 · 정식 명령 ACK에 따른 UNRESPONSIVE 확장)은 U3가 맡고, U1은 OFFLINE · CONNECTED · DISCONNECTED(마지막 기기 메시지 뒤 40초 — 닫기 프레임 없이 끊긴 연결과 소켓은 열려 있지만 무음인 연결 모두)와 UNRESPONSIVE를 쓴다. UNRESPONSIVE는 ① START_SESSION 뒤 20초 안에 ACK 또는 DEVICE_READY가 빠졌을 때(START_SESSION 전송 실패 포함)와 ② CLOSING 뒤 30초 안에 VOICE_SESSION_CLOSED가 없을 때에만 U1이 기록하고(둘 다 도착했는데 공급자 쪽 문제로 실패한 경우에는 쓰지 않는다), UNRESPONSIVE · DISCONNECTED인 연결도 같은 connection_id의 다음 HEARTBEAT가 오면 CONNECTED로 되돌리며 새 연결의 HELLO_OK도 CONNECTED로 만든다(BR4.8 ③ · BR6.6 ⑤ · BR6.12, J55).
    attributes:
      - { name: device_id, type: uuid, required: true, unique: true, constraints: "core_device.id의 논리 참조" }
      - { name: guardian_id, type: uuid, required: true, constraints: "verify-token 응답 값" }
      - { name: child_id, type: uuid, required: false }
      - { name: connection_state, type: "string(20)", required: true, allowed_values: [OFFLINE, CONNECTED, UNSTABLE, DISCONNECTED, UNRESPONSIVE], default: OFFLINE, constraints: "U1이 쓰는 값은 OFFLINE · CONNECTED · DISCONNECTED · UNRESPONSIVE. UNSTABLE 판정은 U3. UNRESPONSIVE는 명령 시한을 넘긴 두 경우에만 쓴다(위 설명, BR4.8 ③). 상태가 바뀔 때마다 같은 트랜잭션에서 DeviceStatusChanged를 Outbox에 쓴다(BR4.8)" }
      - { name: connection_id, type: uuid, required: false, constraints: "현재 WSS 연결 식별자. OFFLINE이면 비어 있고, Realtime 재시작 때 CONNECTED · UNSTABLE 행을 DISCONNECTED로 바꾸면서 함께 비운다(BR6.13). 교체는 HELLO_OK 시점이다(인증이 아니다, BR3.7 · J59(l)): 한 트랜잭션에서 이 기기의 행을 FOR UPDATE로 잠그고(첫 접속이면 INSERT ... ON CONFLICT (device_id) DO UPDATE) 읽은 옛 connection_id의 소켓이 살아 있으면 4001 REPLACED로 닫은 뒤 connection_id · connection_state(CONNECTED) · connection_since를 새 값으로 덮고 DeviceStatusChanged를 같은 트랜잭션에 쓴다. 잠금을 나중에 얻은 연결이 이기며 기대값 비교는 하지 않는다. verify-token만 통과하고 HELLO가 없거나 틀려 4408 · 4400으로 닫히는 연결은 이 행을 건드리지 않는다. 교체 이후의 DISCONNECTED · UNRESPONSIVE · OFFLINE 기록과 해당 DeviceStatusChanged, HEARTBEAT에 따른 CONNECTED 복귀는 WHERE device_id = :d AND connection_id = :cid 조건부로 하고 행 수가 0이면 기록 없이 버린다 — 대체돼 닫힌 옛 연결과 HELLO_OK 전에 닫힌 연결(1013 · 4400 · 4401 · 4408)의 close 처리가 새 연결의 상태를 덮지 못한다. 예외는 DeviceReleased 처리(BR3.8)의 OFFLINE 하나로, 이 기록만 WHERE device_id = :d를 조건으로 하고 connection_id를 비운다(재시작 뒤 DISCONNECTED 행 포함)" }
      - { name: connection_since, type: timestamp, required: false }
      - { name: last_seen_at, type: timestamp, required: false, constraints: "마지막 기기 메시지(HEARTBEAT 포함)의 시각. 기기 연결 상태의 DISCONNECTED 판정(BR4.8)에만 쓴다 — 연결별 메모리 시계가 아니라 이 영속 값을 기준으로 주기적으로 점검하고, 기록은 'WHERE device_id = :d AND connection_id = :cid AND last_seen_at <= :점검시각 − 40초' 조건부라 점검이 읽은 뒤 도착한 HEARTBEAT를 덮지 않는다. 세션의 40초 시계(BR6.12 ④)가 아니다 — 그것은 Realtime 메모리의 세션별 값이다" }
      - { name: last_rtt_ms, type: int, required: false }
      - { name: recent_disconnects, type: int, required: true, default: 0 }
      - { name: updated_at, type: timestamp, required: true }
    constraints:
      - "한 기기에 한 행(device_id 유일)이고 첫 접속에서 만들어진다. 한 번도 접속하지 않은 기기는 행이 없다 — Core는 이를 사본 없음으로 본다(BR4.3 ②)"
    relationships:
      - { to: Device, cardinality: "one-to-one(논리)", direction: "DeviceConnection → Device", note: "스키마가 달라 논리 참조(device_id). Core 사본의 열 대응은 Device.reported_* 참고" }
```

## 3. 요약

- **U1이 소유하는 엔터티는 다섯**이다: Device(등록 수명 + 연결 상태 사본), Core · Realtime의 Outbox와 Inbox 네 개. 나머지 열세 개는 스켈레톤 경로가 지나가도록 같은 마이그레이션(V1)이 표를 만들지만 동작은 소유 유닛이 완성한다. 엔터티는 모두 열여덟 개다.
- **Device**는 등록 축(UNREGISTERED · REGISTERED · RELEASED · EXPIRED)만 가진다. 연결 축(OFFLINE ~ UNRESPONSIVE)은 Realtime이 주인이고 Core 행에는 보고받은 사본만 적는다. 05 · 04의 5단계 표시와 시작 판정은 두 축을 읽을 때 합친다(`rules.md` BR4.x). EXPIRED 행도 `registration_code` NOT NULL 값이 남고 UNREGISTERED 부분 유일 색인에서만 빠지며, 등록 전 EXPIRED · UNREGISTERED 행은 `guardian_id`가 없어 계정 삭제 참가자가 아니라 7일 정리 작업이 지운다(BR1.4 · BR2.10). 연결 원장 `realtime_device_connection`에서 U1은 OFFLINE · CONNECTED · DISCONNECTED와 UNRESPONSIVE를 쓴다. UNRESPONSIVE는 START_SESSION 뒤 20초 안에 ACK · DEVICE_READY가 빠졌을 때와 CLOSING 뒤 30초 안에 VOICE_SESSION_CLOSED가 없을 때뿐이고, 같은 연결의 다음 HEARTBEAT나 새 연결의 HELLO_OK로 CONNECTED가 된다(J55). 연결 교체는 HELLO_OK 시점에 행을 FOR UPDATE로 잠그고 이후 기록은 `connection_id` 조건부다(BR3.7). UNSTABLE 판정은 U3가 완성한다.
- **Outbox/Inbox**는 한 쌍의 규칙을 두 앱이 각자 자기 스키마에서 지킨다. ERD v0.1 열에 Outbox는 `schema_version` · `occurred_at` · `guardian_id` · `payload_hash` · `purged_at` 다섯 열(11열 → 16열)을, Inbox는 `schema_version` · `occurred_at` · `aggregate_id` · `guardian_id` · `payload_hash` · `next_attempt_at` · `purged_at` 일곱 열(9열 → 16열)을 더해 중복 판정 · 충돌 판정 · 최신 판정 · 민감 페이로드 비우기 · 계정 삭제를 가능하게 한다. `occurred_at`은 envelope 시각을 DB 행에 남겨 재호출 · 재시작 복구에서도 BR4.1 비교를 하게 한다. Inbox `next_attempt_at`은 INSERT 때 `received_at`으로 채워 방금 받은 RECEIVED 행도 폴러에 잡히게 하고 처리기 선점 때 now + 30초로 올리며, 앱 시작 때는 HTTP 수신 전에 모든 RECEIVED 행을 now로 당긴 뒤 평소 선점 경로로 한 번씩 처리기를 부른다(BR5.6 · BR5.7 · BR5.12). 모르는 `eventType` · 새 `schemaVersion`은 RECEIVED를 거치지 않고 처음부터 IGNORED로 들어온다(BR5.9). Inbox `retry_count`는 실패마다 +1이고 6번째 실패가 DEAD이며 값은 5로 남는다(BR5.8). Outbox의 `aggregate_type` · `aggregate_id`는 envelope 필수 필드(BR5.4)라 NOT NULL이다.
- **부분 유일 색인은 종류가 셋(색인은 다섯 개)**이고 동시성 규칙의 DB 근거다. 계정당 활성 기기 하나(core_device의 guardian_id), 발급 중 코드 유일(core_device의 registration_code), 진행 중 세션 하나(core_activity의 guardian_id, realtime_activity_session의 guardian_id와 device_id). 부분 유일 색인은 문장마다 즉시 검사되고 지연시킬 수 없으므로 교체 등록은 옛 기기 행을 먼저 RELEASED로 바꾼 뒤 새 행을 REGISTERED로 바꾼다(BR2.5).
- **동시에 쓰는 사본**은 읽고 비교하고 쓰지 않고 조건부 UPDATE 한 문장으로 갱신한다: core_device의 연결 사본(`reported_at`, BR4.1), core_activity(`status_version`과 `local_failed`, BR5.10 — 로컬 FAILED를 쓰는 문장도 `WHERE status = REQUESTED AND status_version = 0` 조건부, BR6.6 · BR6.7 · BR6.12 ①), realtime_activity_session의 상태 전이(BR6.8 — 출발 상태가 둘 이상인 FAILED 전이는 `FOR UPDATE`로 읽고 `WHERE status = :읽은 상태`로 쓴다), realtime_device_connection(HELLO_OK 때 `FOR UPDATE` 교체와 이후 `connection_id` 조건부 기록, BR3.7). 예외는 DeviceReleased 처리의 OFFLINE 하나로 `device_id`만 조건이다(BR3.8).
- **영속 열이 없는 값**: 세션의 40초 시계(BR6.12 ④)는 Realtime 메모리의 세션별 값이고, Core 취소가 만드는 10분 묘비(BR6.7)도 Realtime 메모리다. 영속 `last_seen_at`은 기기 연결 DISCONNECTED 판정(BR4.8)에만 쓴다. 기본 종료 표현 '무중아 그만할래'는 코드 상수라 `core_stop_expression`에 행이 없고, Realtime이 세션 패키지에서 받아 `realtime_session_stop_expression`에 `is_default` true 한 행으로 저장한다(BR6.5).
- **계정 삭제 순서**(J58): core_activity(U4 참가자) → core_device(Device 참가자, BR2.10) → core_guardian(U2) 순서이고 `core_device.guardian_id`와 `core_activity.device_id` 외래 키는 ON DELETE 없음(RESTRICT)이라 순서를 어기면 삭제가 실패해 다시 시도된다. U1의 참가자는 Device 참가자(BR2.10)와 메시징 Outbox/Inbox 참가자(BR5.14)이고 메시징 참가자는 앱마다 하나씩 둘이다 — Core 쪽(core_outbox_event · core_inbox_event)은 U2 삭제 지휘가, Realtime 쪽(realtime_outbox_event · realtime_inbox_event)은 Realtime의 DeletionRequested 처리기가 U4의 호출 순서 안에서 부른다. 각 참가자는 자기 스키마에만 쓰고, 전달이 끝난 Outbox/Inbox 행만 지우며 아직 전달 · 처리 중인 제어 이벤트(PENDING · RECEIVED)는 남긴다.

## 4. ERD v0.2로 바꿀 것 (U1 몫)

`docs/cloud_run_report.md`의 'ERD v0.2로 바꿀 것'에 합쳐 넣는다. 열 수는 위 yaml의 실제 열 수이고 ERD v0.1이 있는 표는 `mujoong_ERD_PostgreSQL_1007.sql`의 열 수와 견준 값이다.

| # | 대상 | 변경 | 까닭 |
|---|---|---|---|
| E1 | core_device (신설) | 위 Device 열 전부(22열). 부분 유일 색인 둘(guardian_id는 REGISTERED, registration_code는 UNREGISTERED). `guardian_id` → core_guardian.id 외래 키는 ON DELETE 없음(RESTRICT). EXPIRED 행도 `registration_code` NOT NULL 값을 남긴다 | J4 · J13, 개발 명세 Device 상태 7개를 등록 축으로. 교체 등록 순서(BR2.5) · 계정 삭제 순서(BR2.10) |
| E2 | realtime_device_connection (신설) | 위 DeviceConnection 열(10열). `connection_state`에 UNRESPONSIVE 포함 | 연결 원장 분리(J4). U1이 UNRESPONSIVE를 쓰는 때는 START_SESSION 뒤 20초 · CLOSING 뒤 30초 명령 시한을 넘긴 두 경우뿐이다(J55). 교체는 HELLO_OK 시점(BR3.7) |
| E3 | core_activity (신설) | 위 Activity 열 전부(20열, `local_failed` 불리언 포함 — 기본 false, Core가 이벤트 없이 닫은 로컬 FAILED 표시 · `start_result_code` 정수 포함 — 202 · 423 · 409 · 503, local_failed이면 필수) · 부분 유일 색인. `device_id` → core_device.id 외래 키는 ON DELETE 없음(RESTRICT) | 세션 사본(J5). 로컬 FAILED를 이벤트가 덮어쓰지 못하게 함(BR5.10 · BR6.14). 같은 키 재요청이 로컬 FAILED의 첫 응답을 그대로 재생(BR6.2) |
| E4 | core_outbox_event · realtime_outbox_event (각 11열 → 16열), core_inbox_event · realtime_inbox_event (각 9열 → 16열) | Outbox 두 표: `schema_version` · `occurred_at` · `guardian_id` · `payload_hash` · `purged_at` 5열 추가, `aggregate_type` · `aggregate_id`를 NULL 허용에서 NOT NULL로 변경. Inbox 두 표: `schema_version` · `occurred_at` · `aggregate_id` · `guardian_id` · `payload_hash` · `next_attempt_at` · `purged_at` 7열 추가(`next_attempt_at`은 INSERT 때 `received_at`으로 채움), status에 `IGNORED` · `DEAD` | 중복 · 충돌 판정, 최신 판정(BR4.1), 처리기 선점 · 재호출(BR5.7), 민감 페이로드 비우기, 계정 삭제(J49 · J50 · J58) |
| E5 | realtime_activity_session (29열 → 29열) | 추가 3열: `status_version` · `consent_snapshot_json`(패키지의 consentSnapshot) · `policy_version`. 유지(ERD v0.1 열, 위 yaml에 다시 적음): `consent_checked_at` · `intro_content_version_id` · `end_detail`. `consent_version`은 NOT NULL에서 NULL 허용으로 바꾼다(U1 패키지에 정수 버전이 없어 U1은 비워 두고 U3 · U4가 채운다). `voice_client_id`를 `device_id`로 이름 변경(논리 참조 core_device.id), 삭제 3열: `practice_goal_selection_id` · `current_practice_stage`(J12) · `activity_context_id`(U1이 쓰지 않는다 — 08 입력은 Realtime에 건네지 않는다, NFR3. 필요하면 U4가 열을 더한다) | 사본 순서 판정, 기기 식별, 동의 스냅샷(정책 버전 · 보호자 열람 · 원음 동의)을 담을 자리 |
| E6 | core_stop_expression | `expression_suffix`(10자) → `expression_text`(20자), `normalized_suffix` → `normalized_text` | J12 · SET-01 2 ~ 20자 |
| E7 | core_approved_content_version | `audio_path`는 Nginx 정적 경로 아래 상대 경로로 의미를 고정. U1 dev 시드는 임시 WAV 두 개(J60) | 승인 음성 전달(J30) |
