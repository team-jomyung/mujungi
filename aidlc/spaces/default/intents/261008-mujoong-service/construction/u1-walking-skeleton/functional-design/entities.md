# U1 u1-walking-skeleton — 엔터티 모델 (Functional Design 3.1)

> Functional Design(3.1) 산출물 · U1 워킹 스켈레톤 · 깊이 Standard · 2026-10-09 (클라우드 세션, 팀장 대리 판단)
> 입력: `unit-of-work.md`(U1 정의), `unit-of-work-story-map.md`(U1이 닿는 스토리 8개), `requirements.md`(FR2.1 · FR2.2 · FR2.3 · NFR6 · NFR9), `components.md`(엔터티 소유 표 · J1~J14 · 기기 상태 대응표), `contract-summary.md`(C2 · C7 · C9 · C11 · 시간 계약 · 포트), ERD v0.1 DDL(`mujoong_ERD_PostgreSQL_1007.sql`). 질문과 대리 답은 `functional-design-questions.md`(Q1 ~ Q12), 준하 제안(가정) J42 ~ J53은 `functional-spec.md` 끝 표.
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
| Guardian | core_guardian | core | GuardianIdentity | minimal(시드) | U2 |
| LoginSession | core_spring_session(+_attributes) | core | GuardianIdentity | minimal(시험 로그인) | U2 |
| Consent | core_consent | core | ConsentPolicy | minimal(시드) | U2 |
| Child | core_child | core | ChildProfile | minimal(시드) | U2 |
| StopExpression | core_stop_expression | core | ChildProfile | 표만 만듦(스켈레톤은 기본 표현만) | U2 |
| ApprovedContent · ApprovedContentVersion | core_approved_content · core_approved_content_version | core | ApprovedContentCatalog | minimal(시드 문구) | U2 |
| Activity | core_activity | core | ActivityCoordinator | minimal(자유대화 한 건) | U4 |
| ActivitySession | realtime_activity_session | realtime | SessionLifecycle | minimal | U4 |
| SessionEvent | realtime_session_event | realtime | SessionLifecycle | minimal | U4 |
| SessionStopExpression | realtime_session_stop_expression | realtime | SessionLifecycle | minimal(기본 표현 한 행) | U4 |
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
    description: 아이 기기 한 대의 등록 수명(코드 발급 → 등록 → 해제)과 Realtime이 보고한 연결 상태 사본. 계정당 활성 기기는 하나다.
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: guardian_id, type: uuid, required: false, references: Guardian.id, constraints: "REGISTERED이면 필수, UNREGISTERED · EXPIRED이면 비어 있다" }
      - { name: registration_code, type: "string(8)", required: true, constraints: "알파벳 [2-9A-HJKMNP-Z] 8글자 대문자. UNREGISTERED 행끼리 유일(부분 유일 색인). 등록 뒤에도 05의 '코드 앞 4자리' 표시를 위해 남긴다" }
      - { name: code_issued_at, type: timestamp, required: true }
      - { name: code_expires_at, type: timestamp, required: true, constraints: "code_issued_at + 24시간(bootstrap.code-ttl)" }
      - { name: bootstrap_secret_hash, type: "string(64)", required: true, unique: true, constraints: "bootstrap 비밀의 SHA-256 소문자 16진수. 비밀 원문은 저장하지 않는다" }
      - { name: device_token_hash, type: "string(64)", required: false, unique: true, constraints: "기기 토큰의 SHA-256. claim 전에는 비어 있고 RELEASED가 되면 비운다" }
      - { name: token_issued_at, type: timestamp, required: false }
      - { name: first_connected_at, type: timestamp, required: false, constraints: "첫 WSS 인증(verify-token 성공) 시각. 값이 생기면 claim이 닫힌다" }
      - { name: registration_status, type: "string(20)", required: true, allowed_values: [UNREGISTERED, REGISTERED, RELEASED, EXPIRED], default: UNREGISTERED }
      - { name: registered_at, type: timestamp, required: false }
      - { name: released_at, type: timestamp, required: false }
      - { name: release_reason, type: "string(30)", required: false, allowed_values: [REREGISTERED, ACCOUNT_DELETED] }
      - { name: reported_connection_state, type: "string(20)", required: false, allowed_values: [OFFLINE, CONNECTED, UNSTABLE, DISCONNECTED, UNRESPONSIVE], constraints: "Realtime이 보고한 값의 사본. 한 번도 보고가 없으면 비어 있다" }
      - { name: reported_at, type: timestamp, required: false, constraints: "사본에 반영한 가장 늦은 보고 시각(보고의 reportedAt 또는 이벤트의 occurredAt). 단조 증가" }
      - { name: last_seen_at, type: timestamp, required: false }
      - { name: connection_since, type: timestamp, required: false }
      - { name: rtt_ms, type: int, required: false, constraints: ">= 0" }
      - { name: recent_disconnect_count, type: int, required: true, default: 0, constraints: ">= 0, 최근 5분 창 끊김 횟수(Realtime 값)" }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }
      - { name: row_version, type: bigint, required: true, default: 0, constraints: "낙관적 잠금" }
    constraints:
      - "부분 유일: guardian_id (WHERE registration_status = 'REGISTERED') — 계정당 활성 기기 하나(AC2.2.7)"
      - "부분 유일: registration_code (WHERE registration_status = 'UNREGISTERED') — 발급 중인 코드끼리 겹치지 않는다"
      - "검사: registration_status = 'REGISTERED' 이면 guardian_id · registered_at이 반드시 있다. device_token_hash는 claim 뒤에만 채워진다"
      - "검사: registration_status = 'RELEASED' 이면 released_at · release_reason이 있고 device_token_hash는 비어 있다"
      - "검사: registration_status IN ('UNREGISTERED','EXPIRED') 이면 guardian_id는 비어 있다"
      - "상태 전이는 UNREGISTERED → REGISTERED · EXPIRED, REGISTERED → RELEASED만 허용(되돌림 · 건너뜀 없음, EXPIRED · RELEASED는 끝)"
    relationships:
      - { to: Guardian, cardinality: "many-to-one(활성은 최대 1)", direction: "Device → Guardian", note: "같은 core 스키마라 DB 외래 키 가능" }
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
      - { name: aggregate_type, type: "string(100)", required: false, allowed_values: [ActivitySession, Device, DataDeletionRequest] }
      - { name: aggregate_id, type: uuid, required: false }
      - { name: guardian_id, type: uuid, required: false, constraints: "계정 삭제 참가자가 찾는 열(ERD v0.2 추가). 보호자와 무관한 이벤트는 비어 있다" }
      - { name: payload_json, type: json, required: true, constraints: "전달 전까지만 내용을 가진다. PUBLISHED가 되는 즉시 {} 로 비운다(BR5.11). 전사 원문 · 원음 · 비밀을 담지 않는다" }
      - { name: payload_hash, type: "string(64)", required: true, constraints: "정규화 payload의 SHA-256. 비운 뒤에도 남는다(충돌 판정용, ERD v0.2 추가)" }
      - { name: status, type: "string(30)", required: true, allowed_values: [PENDING, PUBLISHED, FAILED], default: PENDING }
      - { name: retry_count, type: int, required: true, default: 0, constraints: ">= 0, <= 20" }
      - { name: available_at, type: timestamp, required: true, constraints: "이 시각 이후에 발송 대상" }
      - { name: published_at, type: timestamp, required: false }
      - { name: last_error, type: text, required: false, constraints: "상태 코드 · 예외 종류만. 페이로드 내용을 넣지 않는다" }
      - { name: purged_at, type: timestamp, required: false, constraints: "payload를 비운 시각(ERD v0.2 추가)" }
      - { name: created_at, type: timestamp, required: true }
    constraints:
      - "색인: (status, available_at, created_at) — 발송 폴러가 쓴다"
      - "색인: (guardian_id) — 계정 삭제"
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
      - { name: aggregate_id, type: uuid, required: false }
      - { name: guardian_id, type: uuid, required: false, constraints: "ERD v0.2 추가" }
      - { name: payload_json, type: json, required: true, constraints: "처리 끝(PROCESSED · IGNORED)이면 {} 로 비운다(BR5.11)" }
      - { name: payload_hash, type: "string(64)", required: true }
      - { name: status, type: "string(30)", required: true, allowed_values: [RECEIVED, PROCESSED, IGNORED, DEAD], default: RECEIVED }
      - { name: retry_count, type: int, required: true, default: 0, constraints: ">= 0, <= 5 (처리기 재호출 횟수)" }
      - { name: next_attempt_at, type: timestamp, required: false, constraints: "RECEIVED이고 처리기가 실패했을 때 다음 호출 시각(ERD v0.2 추가)" }
      - { name: received_at, type: timestamp, required: true }
      - { name: processed_at, type: timestamp, required: false }
      - { name: last_error, type: text, required: false }
      - { name: purged_at, type: timestamp, required: false }
    constraints:
      - "event_id가 기본 키라 같은 eventId는 한 행뿐이다(중복 차단의 근거)"
      - "색인: (status, next_attempt_at) — 처리기 재호출 폴러"
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
      - { name: schema_version, type: int, required: true, default: 1 }
      - { name: aggregate_type, type: "string(100)", required: false, allowed_values: [ActivitySession, Device, DataDeletionRequest] }
      - { name: aggregate_id, type: uuid, required: false }
      - { name: guardian_id, type: uuid, required: false }
      - { name: payload_json, type: json, required: true }
      - { name: payload_hash, type: "string(64)", required: true }
      - { name: status, type: "string(30)", required: true, allowed_values: [PENDING, PUBLISHED, FAILED], default: PENDING }
      - { name: retry_count, type: int, required: true, default: 0 }
      - { name: available_at, type: timestamp, required: true }
      - { name: published_at, type: timestamp, required: false }
      - { name: last_error, type: text, required: false }
      - { name: purged_at, type: timestamp, required: false }
      - { name: created_at, type: timestamp, required: true }
    constraints:
      - "CoreOutboxEvent와 같은 색인 · 검사"
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
      - { name: aggregate_id, type: uuid, required: false }
      - { name: guardian_id, type: uuid, required: false }
      - { name: payload_json, type: json, required: true }
      - { name: payload_hash, type: "string(64)", required: true }
      - { name: status, type: "string(30)", required: true, allowed_values: [RECEIVED, PROCESSED, IGNORED, DEAD], default: RECEIVED }
      - { name: retry_count, type: int, required: true, default: 0 }
      - { name: next_attempt_at, type: timestamp, required: false }
      - { name: received_at, type: timestamp, required: true }
      - { name: processed_at, type: timestamp, required: false }
      - { name: last_error, type: text, required: false }
      - { name: purged_at, type: timestamp, required: false }
    constraints:
      - "CoreInboxEvent와 같은 색인 · 검사"
    relationships: []

  # ───────────── 스켈레톤이 지나가는 최소 엔터티 (소유 유닛이 완성) ─────────────
  - name: Guardian
    table: core_guardian
    schema: core
    owner_component: GuardianIdentity
    u1_role: minimal
    u1_columns_used: [id, status]
    description: 보호자 계정. U1은 시드 한 명만 쓴다(구글 로그인 · 계정 삭제 접수는 U2).
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
    description: Spring Session JDBC 표준 테이블. 시험 로그인과 이후 구글 로그인이 같은 세션 계층을 쓴다(J44).
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
    description: 4항목 동의 기록. U1은 시드로 4항목을 GIVEN으로 넣고 시작 게이트(ConsentGate)가 읽는다.
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
    description: 아이별 추가 종료 표현. 표만 만들고 스켈레톤은 고정 기본 표현("무중아 그만할래")만 쓴다.
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
    description: 승인 문구 종류. U1 시드는 CLOSING(마무리 인사)과 WAITING_FILLER(게이트 폐기 때 대체 문구) 두 건.
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
      - { name: audio_path, type: "string(500)", required: false, constraints: "Nginx 정적 경로 아래 상대 경로. 개인정보 없는 파일" }
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
    u1_columns_used: [session_id, guardian_id, child_id, device_id, activity_type, status, status_version, end_reason, failure_code, confirmed, start_idempotency_key, request_hash, requested_at, started_at, ended_at]
    description: Core가 가진 세션 사본. 시작 검사 · 04 · 12 · 17의 읽기 원천이며 Realtime의 SessionStateChanged 이벤트로만 진행한다(J5). 보호자당 종료 아닌 세션은 하나다.
    attributes:
      - { name: session_id, type: uuid, required: true, unique: true, constraints: "Core가 시작 요청 때 발급(J11)" }
      - { name: guardian_id, type: uuid, required: true, references: Guardian.id }
      - { name: child_id, type: uuid, required: true, references: Child.id }
      - { name: device_id, type: uuid, required: true, references: Device.id }
      - { name: activity_type, type: "string(30)", required: true, allowed_values: [FREE_TALK, PRACTICE], constraints: "U1은 FREE_TALK만" }
      - { name: status, type: "string(30)", required: true, allowed_values: [REQUESTED, PREPARING, DEVICE_ACK, WAITING_CHILD, ACTIVE, CLOSING, ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED], default: REQUESTED }
      - { name: status_version, type: int, required: true, default: 0, constraints: "Realtime이 매기는 세션별 단조 증가 값. 0 = Core만 아는 REQUESTED" }
      - { name: end_reason, type: "string(50)", required: false, allowed_values: [GUARDIAN_STOP, CHILD_STOP, SILENCE, DISCONNECT, CONSENT_WITHDRAWN, UNCONFIRMED, DECLINED, FAILED] }
      - { name: failure_code, type: "string(50)", required: false, allowed_values: [DEVICE_NO_ACK, SCENARIO_GEN_FAILED, PROVIDER_UNAVAILABLE, SERVER_RESTART] }
      - { name: confirmed, type: boolean, required: true, default: false, constraints: "기기 멈춤을 확인했는가" }
      - { name: start_idempotency_key, type: "string(100)", required: true }
      - { name: request_hash, type: "string(64)", required: true, constraints: "시작 요청 본문의 SHA-256. 같은 키에 다른 본문이면 IDEMPOTENCY_CONFLICT 판정에 쓴다" }
      - { name: requested_at, type: timestamp, required: true }
      - { name: started_at, type: timestamp, required: false }
      - { name: ended_at, type: timestamp, required: false }
      - { name: observed_at, type: timestamp, required: false, constraints: "마지막으로 Realtime 이벤트를 반영한 시각" }
      - { name: created_at, type: timestamp, required: true }
      - { name: updated_at, type: timestamp, required: true }
    constraints:
      - "유일: (guardian_id, start_idempotency_key) — 같은 키 재요청은 같은 세션"
      - "부분 유일: guardian_id (WHERE status IN ('REQUESTED','PREPARING','DEVICE_ACK','WAITING_CHILD','ACTIVE','CLOSING')) — 진행 중 세션 하나(AC3.1.8)"
      - "status_version은 줄어들지 않는다(받은 값이 같거나 작으면 버림)"
    relationships:
      - { to: Device, cardinality: many-to-one, direction: "Activity → Device" }
      - { to: ActivitySession, cardinality: "one-to-one(논리)", direction: "Activity ← ActivitySession", note: "스키마가 달라 논리 참조(session_id)" }

  - name: ActivitySession
    table: realtime_activity_session
    schema: realtime
    owner_component: SessionLifecycle
    u1_role: minimal
    u1_columns_used: [id, guardian_id, child_id, device_id, activity_type, status, status_version, start_idempotency_key, gpt_live_session_id, consent_version, data_collection_allowed, transcript_storage_allowed, child_context_json, closing_content_version_id, end_reason, termination_requested_at, output_stopped_at, transport_closed_at, provider_closed_at, started_at, ended_at, row_version]
    description: Realtime이 가진 진행 중 세션의 주인 행. J12에 따라 practice_goal_selection_id · current_practice_stage는 이 표에서 뺀다(realtime_session_goal로 옮김). status_version을 더한다(ERD v0.2).
    attributes:
      - { name: id, type: uuid, required: true, unique: true, constraints: "Core의 session_id와 같은 값" }
      - { name: guardian_id, type: uuid, required: true }
      - { name: child_id, type: uuid, required: true }
      - { name: device_id, type: uuid, required: true, constraints: "ERD v0.1의 voice_client_id를 논리 참조 device_id로 쓴다(둘 다 두지 않는다 — 아래 ERD v0.2 변경)" }
      - { name: activity_type, type: "string(30)", required: true }
      - { name: status, type: "string(30)", required: true, allowed_values: [PREPARING, DEVICE_ACK, WAITING_CHILD, ACTIVE, CLOSING, ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED], constraints: "REQUESTED는 Core 사본에만 있다" }
      - { name: status_version, type: int, required: true, constraints: "PREPARING = 1부터 전이마다 +1(ERD v0.2 추가)" }
      - { name: start_idempotency_key, type: "string(100)", required: true, unique: true }
      - { name: gpt_live_session_id, type: "string(255)", required: false, constraints: "공급자 세션 ID(세션 의존 명령 용). 외부로 노출하지 않는다" }
      - { name: consent_version, type: int, required: true }
      - { name: data_collection_allowed, type: boolean, required: true }
      - { name: transcript_storage_allowed, type: boolean, required: true }
      - { name: child_context_json, type: json, required: false, constraints: "displayName · age · interests만(NFR3). 08 입력 원문은 넣지 않는다" }
      - { name: closing_content_version_id, type: uuid, required: false, constraints: "시작 때 고정한 마무리 인사 버전(core 논리 참조)" }
      - { name: end_reason, type: "string(50)", required: false }
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
      - { name: op_id, type: "string(100)", required: false }
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
    description: 세션이 시작 때 고정한 종료 표현. U1은 기본 표현 한 행만 쓴다.
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: session_id, type: uuid, required: true, references: ActivitySession.id }
      - { name: expression_text, type: "string(100)", required: true }
      - { name: is_default, type: boolean, required: true, default: false }
      - { name: sort_order, type: int, required: true }
      - { name: created_at, type: timestamp, required: true }
    constraints: []
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
    description: 기기 한 대의 연결 상태 원장. 연결 5단계 판정은 U3가 완성하고 U1은 OFFLINE · CONNECTED · DISCONNECTED(40초)만 쓴다.
    attributes:
      - { name: device_id, type: uuid, required: true, unique: true, constraints: "core_device.id의 논리 참조" }
      - { name: guardian_id, type: uuid, required: true, constraints: "verify-token 응답 값" }
      - { name: child_id, type: uuid, required: false }
      - { name: connection_state, type: "string(20)", required: true, allowed_values: [OFFLINE, CONNECTED, UNSTABLE, DISCONNECTED, UNRESPONSIVE], default: OFFLINE }
      - { name: connection_id, type: uuid, required: false, constraints: "현재 WSS 연결 식별자. OFFLINE이면 비어 있다" }
      - { name: connection_since, type: timestamp, required: false }
      - { name: last_seen_at, type: timestamp, required: false }
      - { name: last_rtt_ms, type: int, required: false }
      - { name: recent_disconnects, type: int, required: true, default: 0 }
      - { name: updated_at, type: timestamp, required: true }
    constraints: []
    relationships: []
```

## 3. 요약

- **U1이 소유하는 엔터티는 다섯**이다: Device(등록 수명 + 연결 상태 사본), Core · Realtime의 Outbox와 Inbox 네 개. 나머지 열세 개는 스켈레톤 경로가 지나가도록 같은 마이그레이션(V1)이 표를 만들지만 동작은 소유 유닛이 완성한다.
- **Device**는 등록 축(UNREGISTERED · REGISTERED · RELEASED · EXPIRED)만 가진다. 연결 축(OFFLINE ~ UNRESPONSIVE)은 Realtime이 주인이고 Core 행에는 보고받은 사본만 적는다. 05 · 04의 5단계 표시와 시작 판정은 두 축을 읽을 때 합친다(`rules.md` BR4.x).
- **Outbox/Inbox**는 한 쌍의 규칙을 두 앱이 각자 자기 스키마에서 지킨다. ERD v0.1 열에 `schema_version` · `guardian_id` · `payload_hash` · `purged_at`(Inbox는 `next_attempt_at`도)을 더해 중복 판정 · 충돌 판정 · 민감 페이로드 비우기 · 계정 삭제를 가능하게 한다.
- **부분 유일 색인 셋**이 동시성 규칙의 DB 근거다: 계정당 활성 기기 하나(core_device), 보호자당 진행 세션 하나(core_activity와 realtime_activity_session 둘 다), 발급 중 코드 유일(core_device).

## 4. ERD v0.2로 바꿀 것 (U1 몫)

`docs/cloud_run_report.md`의 'ERD v0.2로 바꿀 것'에 합쳐 넣는다.

| # | 대상 | 변경 | 까닭 |
|---|---|---|---|
| E1 | core_device (신설) | 위 Device 열 전부 | J4 · J13, 개발 명세 Device 상태 7개를 등록 축으로 |
| E2 | realtime_device_connection (신설) | 위 DeviceConnection 열 | 연결 원장 분리(J4) |
| E3 | core_activity (신설) | 위 Activity 열 · 부분 유일 색인 | 세션 사본(J5) |
| E4 | core_outbox_event · core_inbox_event · realtime_outbox_event · realtime_inbox_event | `schema_version` · `guardian_id` · `payload_hash` · `purged_at` 추가, Inbox는 `next_attempt_at` 추가, Inbox status에 `IGNORED` · `DEAD` | 중복 · 충돌 판정, 민감 페이로드 비우기, 계정 삭제(J49 · J50) |
| E5 | realtime_activity_session | `status_version` 추가, `voice_client_id`를 `device_id`로 이름 변경(논리 참조 core_device.id), `practice_goal_selection_id` · `current_practice_stage` 삭제(J12) | 사본 순서 판정, 기기 식별 |
| E6 | core_stop_expression | `expression_suffix`(10자) → `expression_text`(20자), `normalized_suffix` → `normalized_text` | J12 · SET-01 2 ~ 20자 |
| E7 | core_approved_content_version | `audio_path`는 Nginx 정적 경로 아래 상대 경로로 의미를 고정 | 승인 음성 전달(J30) |
