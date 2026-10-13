# 무중 계약 요약 (Contract Design 2.8)

> Contract Design(2.8) 산출물 · 깊이 Standard · 2026-10-09 (클라우드 세션, 팀장 대리 판단)
> 입력: `unit-of-work.md`(U1~U7) · `unit-of-work-dependency.md`(DAG · 통합 지점 표), `components.md`(엔터티 소유 · 앱 사이 접점 셋 · 이벤트 6종), `requirements.md`(FR · NFR5 · NFR6 · NFR9 · A1 · A2 · A10), `stories.md`(B4~B6 · B9~B11 · B13~B15 · OQ13 · OQ14), 질문 파일 `contract-design-questions.md`(Q1~Q9), 20_개발명세_1008(API · 이벤트 · 상태 기계), 50_ADR_2S(protocol · ADR-004 · 008 · 009 · 010).
> 경로 이름은 개발 명세 1008을 따르고 화면 기준 변경(2초 폴링 · /api/child 단수 · bootstrap은 Core · 10개 항목 리포트)을 반영했다. 상태 · 사유 이름은 화면 설계 그대로다. 숫자 시간값은 7절 시간 계약 한 곳에만 적는다.

## 1. 계약 목록

| # | 제공 유닛 | 소비자 | 방식 | 소유(스펙 담당) |
|---|---|---|---|---|
| C1 | U2 보호자 계정 (+U1 시험 로그인) | External: 보호자 웹(ParentWeb, Android Chrome) | 동기 REST · 세션 쿠키 | U2 (A) |
| C2 | U1 · U2 기기 등록 · 상태 | External: 보호자 웹 · 아이 기기 웹(DeviceWeb) | 동기 REST | U1 (B) |
| C3 | U4 활동 세션 · 활동 기록 | External: 보호자 웹 | 동기 REST(12는 2초 폴링) | U4 (B) |
| C4 | U5 연습(담은 목표) | External: 보호자 웹 | 동기 REST | U5 (C) |
| C5 | U6 결과와 리포트 | External: 보호자 웹 | 동기 REST · PDF | U6 (E) |
| C6 | U7 웹 푸시 | External: 보호자 웹(서비스 워커) | 동기 REST · Web Push | U7 (A) |
| C7 | U3 음성 대화(DeviceGateway) | External: 아이 기기 웹 | WSS(JSON 제어 + 바이너리 음성) | U3 (B) |
| C8 | U4 SessionLifecycle | U4 · U5 ActivityCoordinator(Core) | 내부 HTTP(시작 · 끝내기 · 진행 조회) | U4 (B) |
| C9 | U1 DeviceRegistry | U3 DeviceGateway(Realtime) | 내부 HTTP(토큰 확인 · 상태 보고) | U1 (B) |
| C10 | U5 PracticeGoal | U5 PracticeProtocol(Realtime) | 내부 HTTP(다음 목표 상황) | U5 (C · D) |
| C11 | U1 메시징(전달 틀) | U2 · U3 · U4 · U7 | 영속 이벤트 6종(Outbox → Inbox, 내부 HTTP 전달) | 이벤트별 발행 유닛 |
| C12 | U2 · U4 · U5 · U6 | 같은 앱 안 다른 유닛 | 앱 안 포트 · 앱 내부 사건(공유 스키마) | 포트 제공 유닛 |
| C13 | U1 실행 골격 | 모든 유닛 | 공유 DB 스키마 · 쓰기 권한 | U1 (B) |
| C14 | U5 연습 · U3 게이트 · U6 결과 | AI 호출 쪽 | AI 입출력 계약 뼈대(형식만, 본문은 D) | U5 (D) |
| T | (모든 유닛) | (모든 유닛) | 시간 계약(WBS 1.7.6) | U4 (B) |

## 2. 공통 규칙

- **인증:** 보호자 API는 세션 쿠키(`MUJOONG_SESSION`, HttpOnly · Secure · SameSite=Lax, 비활성 만료 30일 갱신형) + 상태 변경 요청의 `X-CSRF-TOKEN` 헤더. 아이 기기 REST · WSS는 HttpOnly 쿠키(`MUJOONG_DEVICE_BOOTSTRAP` · `MUJOONG_DEVICE`)만 쓴다 — 기기 웹 JS는 비밀을 읽지도 저장하지도 않는다. 기기 WSS는 Handshake에서 쿠키 + 허용 Origin을 확인한다. 내부 HTTP는 `X-Internal-Token`(환경 변수 공유 비밀, 사용자 권한 아님) + Docker 내부망 한정, Nginx가 `/internal/**`을 밖에서 막는다(J21 · J22 · J26 · J29).
- **자원 소유:** 다른 보호자의 자원은 404(NOT_FOUND)로 숨긴다. 계정 삭제가 접수된 보호자는 `/api/me` 외 모든 API가 403 `ACCOUNT_DELETION_IN_PROGRESS`.
- **오류 봉투:** `{ "code": "...", "message": "...", "traceId": "..." }`. 화면 문구는 code로 고른다(mockups 9절). `message`에 전사 원문 · 개인정보를 넣지 않는다.
- **멱등:** 시작 · 리포트 생성은 `idempotencyKey`(UUID, 클라이언트 생성), 끝내기는 `requestId`. 같은 키 재요청은 첫 결과를 그대로 돌려준다. 같은 키에 다른 본문이면 409 `IDEMPOTENCY_CONFLICT`.
- **시각:** 모두 ISO 8601 UTC(`2026-10-15T09:00:00Z`). 날짜 질의(`date`, `month`, `from`, `to`)는 한국 시간(Asia/Seoul) 달력 날짜다.
- **노출 금지 필드:** 진행 중 판정값(label · reason) · 기록용 재판정 원값(value · timing · rationale) · 구조화 결과 원본(contentJson) · 보호자 08 입력 원문 · 상황 본문 · 판정 기준은 어떤 보호자 응답에도 없다. 기록용 재판정은 장면 결과 칩(`sceneChip` 5종)으로만, 경험 구조화는 27 대화 기록 칸 · 리포트 5항목의 출처 표시 문장으로만 나간다(FR4.2 · FR4.3 · A10). 계약 시험에서 응답 스키마에 금지 필드가 없음을 확인한다.

## 3. 보호자 REST (C1 ~ C6)

```yaml
openapi: 3.0.3
info: { title: 무중 Core 보호자 API, version: "1" }
servers: [ { url: 'https://{host}/api' } ]
components:
  securitySchemes:
    session: { type: apiKey, in: cookie, name: MUJOONG_SESSION }
    csrf: { type: apiKey, in: header, name: X-CSRF-TOKEN }
  schemas:
    Error: { type: object, required: [code, message], properties: { code: { type: string }, message: { type: string }, traceId: { type: string } } }
    Me:
      type: object
      properties:
        guardianId: { type: string, format: uuid }
        consentStatus: { type: string, enum: [NOT_GIVEN, GIVEN, OUTDATED] }
        childRegistered: { type: boolean }
        deletionStatus: { type: string, enum: [NONE, IN_PROGRESS] }
    Child:
      type: object
      properties:
        childId: { type: string, format: uuid }
        displayName: { type: string, minLength: 1, maxLength: 10 }
        age: { type: integer, minimum: 6, maximum: 12 }
        interests: { type: array, items: { type: string } }
    DeviceView:
      type: object
      properties:
        registration: { type: object, properties: { status: { type: string, enum: [NONE, REGISTERED] }, codePrefix: { type: string, example: 7F3K }, registeredAt: { type: string, format: date-time } } }
        network: { type: object, properties: { state: { type: string, enum: [STABLE, UNSTABLE, DISCONNECTED, UNRESPONSIVE, NONE, UNKNOWN] }, rttMs: { type: integer }, recentDisconnects: { type: integer }, since: { type: string, format: date-time }, lastSeenAt: { type: string, format: date-time }, lastReportedAt: { type: string, format: date-time } } }
        verdict: { type: object, properties: { canStart: { type: boolean }, reasonCode: { type: string, enum: [NO_DEVICE, DEVICE_UNSTABLE, DEVICE_DISCONNECTED, DEVICE_UNRESPONSIVE, DEVICE_STATUS_UNKNOWN] } } }
    SessionSummary:
      type: object
      properties:
        sessionId: { type: string, format: uuid }
        activityType: { type: string, enum: [FREE_TALK, PRACTICE] }
        status: { type: string, enum: [REQUESTED, PREPARING, DEVICE_ACK, WAITING_CHILD, ACTIVE, CLOSING, ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED] }
        endReason: { type: string, enum: [GUARDIAN_STOP, CHILD_STOP, SILENCE, DISCONNECT, CONSENT_WITHDRAWN, UNCONFIRMED, DECLINED, FAILED], nullable: true }
        failureCode: { type: string, enum: [DEVICE_NO_ACK, SCENARIO_GEN_FAILED, PROVIDER_UNAVAILABLE, SERVER_RESTART], nullable: true }
        confirmed: { type: boolean }
        requestedAt: { type: string, format: date-time }
        startedAt: { type: string, format: date-time, nullable: true }
        endedAt: { type: string, format: date-time, nullable: true }
    LiveView:
      allOf:
        - $ref: '#/components/schemas/SessionSummary'
        - type: object
          properties:
            statusVersion: { type: integer }
            serverTime: { type: string, format: date-time }
            observedAt: { type: string, format: date-time, description: Core가 Realtime에서 마지막으로 받은 시각 }
            stale: { type: boolean, description: true면 12는 "기기 상태 확인 불가 + 마지막 확인 시각" }
            technicalPause: { type: object, nullable: true, properties: { kind: { type: string, enum: [DEVICE, PROVIDER] }, since: { type: string, format: date-time } } }
            silenceWarning: { type: object, nullable: true, properties: { silentSince: { type: string, format: date-time }, endsAt: { type: string, format: date-time } } }
            practice: { type: object, nullable: true, properties: { goalIndex: { type: integer }, goalCount: { type: integer }, goalName: { type: string }, stage: { type: string, enum: [SCENARIO_INTRO, FIRST_RESPONSE, HELP_OFFER, CUE, AFTER_CUE_RESPONSE, NEW_SCENARIO_RESPONSE] } } }
            gaps: { type: array, items: { type: object, properties: { from: { type: string, format: date-time }, to: { type: string, format: date-time, nullable: true }, kind: { type: string, enum: [DISCONNECT, UNCONFIRMED] } } } }
            items: { type: array, items: { $ref: '#/components/schemas/Bubble' } }
            nextSeq: { type: integer }
    Bubble:
      type: object
      properties:
        seq: { type: integer }
        speaker: { type: string, enum: [CHILD, AI] }
        text: { type: string }
        final: { type: boolean }
        uncertain: { type: boolean }
        startedAt: { type: string, format: date-time }
        endedAt: { type: string, format: date-time, nullable: true }
    GoalSelection:
      type: object
      properties:
        goalSelectionId: { type: string, format: uuid }
        displayName: { type: string }
        isFavorite: { type: boolean }
        sortOrder: { type: integer }
        selectedAt: { type: string, format: date-time }
security: [ { session: [] } ]
paths:
  /auth/google:
    post:
      summary: 구글 인가 코드 교환 → 세션 쿠키 (01)
      security: []
      requestBody: { content: { application/json: { schema: { type: object, required: [code, redirectUri], properties: { code: { type: string }, redirectUri: { type: string } } } } } }
      responses: { "200": { description: 로그인, content: { application/json: { schema: { $ref: '#/components/schemas/Me' } } } }, "401": { description: 구글 교환 실패 } }
  /auth/logout:
    post: { summary: 로그아웃(세션 종료 요청 아님, B2), security: [ { session: [], csrf: [] } ], responses: { "204": { description: ok } } }
  /dev/login:
    post: { summary: U1 시험 로그인 — dev 프로필에서만 존재, 운영 빌드에서는 404, security: [], responses: { "200": { description: 시드 보호자 세션 } } }
  /me:
    get: { summary: 01 분기용 내 상태, responses: { "200": { description: 내 상태, content: { application/json: { schema: { $ref: '#/components/schemas/Me' } } } } } }
    delete: { summary: P7 계정 삭제 접수 — 즉시 차단 · 로그아웃, security: [ { session: [], csrf: [] } ], responses: { "202": { description: '{deletionRequestId}' } } }
  /policies/current:
    get: { summary: 02 · 02-1 약관(네 항목, 생성형 AI 고지 포함), security: [], responses: { "200": { description: '{policyVersion, items[{type, title, collect, purpose, retention, ifDenied, aiNotice}]}' } } }
  /consents:
    get: { summary: 동의 상태, responses: { "200": { description: '{status: NOT_GIVEN|GIVEN|OUTDATED, policyVersion, items[{type, status}]}' } } }
    put:
      summary: 4항목 동의 저장(전부 true만 받음)
      security: [ { session: [], csrf: [] } ]
      requestBody: { content: { application/json: { schema: { type: object, required: [policyVersion, items], properties: { policyVersion: { type: string }, items: { type: array, minItems: 4, maxItems: 4, items: { type: object, properties: { type: { type: string, enum: [VOICE_AND_TEXT, GUARDIAN_VIEW, REPORT_SHARING, RAW_AUDIO] }, agreed: { type: boolean, enum: [true] } } } } } } } } }
      responses: { "204": { description: 저장 }, "400": { description: VALIDATION_FAILED(4개 미만 · false 포함) } }
  /child:
    get: { responses: { "200": { description: 아이 정보, content: { application/json: { schema: { $ref: '#/components/schemas/Child' } } } }, "404": { description: CHILD_NOT_REGISTERED } } }
    post: { summary: 03 처음 등록, security: [ { session: [], csrf: [] } ], responses: { "201": { description: 등록 }, "409": { description: CHILD_EXISTS } } }
    patch: { summary: 03 수정(대상 아이는 바뀌지 않음), security: [ { session: [], csrf: [] } ], responses: { "200": { description: 수정 } } }
  /child/stop-expressions:
    get: { summary: SET-01 목록, responses: { "200": { description: '{default: "무중아 그만할래", items[{id, text, sortOrder}]}' } } }
    put: { summary: SET-01 저장(통째 교체, 2~20자 · 최대 5개 · 중복 금지), security: [ { session: [], csrf: [] } ], responses: { "204": { description: 저장 }, "400": { description: VALIDATION_FAILED } } }
  /home:
    get: { summary: 04 세 구역 한 번에, responses: { "200": { description: '{child, device: DeviceView, currentSession: SessionSummary|null, lock: {locked, reasonCode}}' } } }
  /device:
    get: { summary: 05 두 줄 + 판정 카드, responses: { "200": { description: 기기 보기, content: { application/json: { schema: { $ref: '#/components/schemas/DeviceView' } } } } } }
  /device/register:
    post:
      summary: 05a 코드 등록
      security: [ { session: [], csrf: [] } ]
      requestBody: { content: { application/json: { schema: { type: object, required: [code], properties: { code: { type: string, pattern: '^[2-9A-HJKMNP-Z]{8}$' } } } } } }
      responses: { "201": { description: '{deviceId, codePrefix, registeredAt}' }, "404": { description: CODE_NOT_FOUND(없는 코드 · 만료) }, "409": { description: CODE_ALREADY_REGISTERED | DEVICE_ALREADY_ACTIVE(연결된 기존 기기, J13) }, "429": { description: TOO_MANY_ATTEMPTS(코드 맞히기 막기, 7절 device.register-failure-limit) } }
  /activities/free:
    post:
      summary: 08 자유대화 시작
      security: [ { session: [], csrf: [] } ]
      requestBody: { content: { application/json: { schema: { type: object, required: [idempotencyKey], properties: { idempotencyKey: { type: string, format: uuid }, context: { type: object, properties: { knownContent: { type: string, maxLength: 200 }, situationType: { type: string, enum: [FRIEND, SCHOOL, FAMILY, ACADEMY, OTHER] }, sourceType: { type: string, enum: [SEEN, HEARD_FROM_CHILD] }, question: { type: string, maxLength: 200 } } } } } } } }
      responses: { "202": { description: '{sessionId, status: REQUESTED}' }, "403": { description: CONSENT_MISSING }, "409": { description: SESSION_IN_PROGRESS }, "423": { description: DEVICE_NOT_STABLE }, "503": { description: REALTIME_UNAVAILABLE } }
  /activities/practice:
    post:
      summary: 18 연습 시작(U5 확장)
      security: [ { session: [], csrf: [] } ]
      requestBody: { content: { application/json: { schema: { type: object, required: [idempotencyKey, goalSelectionIds], properties: { idempotencyKey: { type: string, format: uuid }, goalSelectionIds: { type: array, minItems: 1, items: { type: string, format: uuid } } } } } } }
      responses: { "202": { description: '{sessionId, status: REQUESTED, goalCount}' }, "403": { description: CONSENT_MISSING }, "409": { description: SESSION_IN_PROGRESS }, "422": { description: SCENARIO_GEN_FAILED(첫 목표 상황 두 번 실패) }, "423": { description: DEVICE_NOT_STABLE } }
  /sessions/current:
    get: { summary: 04 지금 활동 · 알림 진입 재조회, responses: { "200": { description: 'SessionSummary 또는 {session: null}' } } }
  /sessions/{sessionId}:
    get:
      summary: 12 진행 폴링(2초) · 17 상태
      parameters: [ { name: sessionId, in: path, required: true, schema: { type: string, format: uuid } }, { name: sinceSeq, in: query, schema: { type: integer, default: 0 } } ]
      responses: { "200": { description: 진행 보기, content: { application/json: { schema: { $ref: '#/components/schemas/LiveView' } } } }, "404": { description: NOT_FOUND } }
  /sessions/{sessionId}/end:
    post:
      summary: P1 끝내기(GUARDIAN_STOP)
      security: [ { session: [], csrf: [] } ]
      parameters: [ { name: sessionId, in: path, required: true, schema: { type: string, format: uuid } } ]
      requestBody: { content: { application/json: { schema: { type: object, required: [requestId], properties: { requestId: { type: string, format: uuid } } } } } }
      responses: { "202": { description: '{status: CLOSING}' }, "409": { description: SESSION_NOT_ACTIVE(이미 끝남 — 화면은 17로) }, "503": { description: REALTIME_UNAVAILABLE(앱은 "전송하지 못함") } }
  /records:
    get:
      summary: 26 달력(month) · 그날 목록(date)
      parameters: [ { name: month, in: query, schema: { type: string, example: 2026-10 } }, { name: date, in: query, schema: { type: string, format: date } } ]
      responses: { "200": { description: 'month → {days[{date, count}]}; date → {items[{sessionId, activityType, startedAt, endedAt, finalStatus, endReason, linkable}]} (DECLINED는 linkable=false)' } }
  /records/{sessionId}:
    get: { summary: 27 대화 기록 칸 · 연습이면 고른 목표 이름, responses: { "200": { description: '{session: SessionSummary, goals[{displayName, order, status}], utterances: Bubble[], gaps[], recordScope{from, to}, endConfirmed}' } } }
  /sessions/{sessionId}/result:
    get: { summary: 27 결과(연습 장면 결과 · 결과 문장) · 생활 팁 · 목표 후보 칸, responses: { "200": { description: '{jobStatus: NONE|REQUESTED|RUNNING|DONE|FAILED, resultLevel?: FULL|PARTIAL|INSUFFICIENT, sentences[{goalSelectionId?, text}], practice?{goals[{goalSelectionId, displayName, order, scenes[{sceneNo, exposureType: ORIGINAL|NEW_SCENARIO, sceneChip: DONE_IMMEDIATE|DONE_AFTER_HELP|NEXT_TIME|ON_HOLD|NOT_DONE, chipText, notDoneReason?}], help{count, consent: ACCEPTED|DECLINED|NO_RESPONSE|NONE}}]}, tips[{supportItemId, condition, content, limitation}], noTipReason?, candidates[{recommendationId, displayName, reasonText, evidence[{utteranceSeq, kind}], selected, goalSelectionId?}]}' } } }
  /sessions/{sessionId}/result/status:
    get: { summary: 17 · 27 정리 상태, responses: { "200": { description: '{sessionStatus, confirmed, jobStatus}' } } }
  /sessions/{sessionId}/result/retry:
    post: { summary: 「다시 시도」(jobStatus=FAILED일 때만), security: [ { session: [], csrf: [] } ], responses: { "202": { description: '{jobStatus: REQUESTED}' }, "409": { description: JOB_NOT_FAILED } } }
  /goal-selections:
    get: { summary: 18 목록(★ 묶음 → 담은 순), responses: { "200": { description: '{items: GoalSelection[]}' } } }
    post: { summary: 27 「담기」, security: [ { session: [], csrf: [] } ], responses: { "201": { description: GoalSelection }, "409": { description: ALREADY_SELECTED } } }
  /goal-selections/{goalSelectionId}:
    patch: { summary: ★ 즐겨찾기 토글, security: [ { session: [], csrf: [] } ], responses: { "200": { description: GoalSelection } } }
    delete: { summary: 18 「×」 · 27 「담김 ✓」 취소(소프트 삭제), security: [ { session: [], csrf: [] } ], responses: { "200": { description: '{restoreUntil}' } } }
  /goal-selections/{goalSelectionId}/restore:
    post: { summary: 「되돌리기」, security: [ { session: [], csrf: [] } ], responses: { "200": { description: GoalSelection }, "410": { description: RESTORE_EXPIRED } } }
  /reports/preview:
    get: { summary: 28 기간 안 활동(진행 중 제외), parameters: [ { name: from, in: query, required: true, schema: { type: string, format: date } }, { name: to, in: query, required: true, schema: { type: string, format: date } } ], responses: { "200": { description: '{firstRecordDate, activities[{sessionId, date, activityType, startedAt}]}' } } }
  /reports:
    post:
      summary: 28 「활동 N개로 리포트 만들기」
      security: [ { session: [], csrf: [] } ]
      requestBody: { content: { application/json: { schema: { type: object, required: [from, to, sessionIds, idempotencyKey], properties: { from: { type: string, format: date }, to: { type: string, format: date }, sessionIds: { type: array, minItems: 1, items: { type: string, format: uuid } }, idempotencyKey: { type: string, format: uuid } } } } } }
      responses: { "202": { description: '{reportId, status: GENERATING}' }, "400": { description: VALIDATION_FAILED(미래 날짜 · 0건) } }
  /reports/{reportId}:
    get: { summary: 만드는 중 폴링 · 30 리포트 확인 · 저장 확인 시트, responses: { "200": { description: '{status: GENERATING|READY|FAILED|SAVED|DISCARDED, level?: FULL|PARTIAL|INSUFFICIENT, periodFrom, periodTo, activityCount, citationCount, child{displayName, age}, notice, sections[10]{sectionNo, title, text, missing, citations[{sessionId, utteranceSeq, quote, at, source: TRANSCRIPT|GUARDIAN_INPUT|CHILD_RECALL}]}} — sections는 READY · SAVED에만' } } }
  /reports/{reportId}/pdf:
    get: { summary: 30 PDF 받기(READY · SAVED만), responses: { "200": { description: PDF 본문, content: { application/pdf: {} } }, "409": { description: REPORT_NOT_READY } } }
  /reports/{reportId}/saved:
    post: { summary: 저장 사실 기록(전달 · 수신 아님), security: [ { session: [], csrf: [] } ], responses: { "204": { description: ok } } }
  /reports/{reportId}/discard:
    post: { summary: 30 「나중에」 · 저장 전 「←」 — 리포트 버림, security: [ { session: [], csrf: [] } ], responses: { "204": { description: ok } } }
  /push-subscription:
    put: { summary: U7 웹 푸시 구독, security: [ { session: [], csrf: [] } ], responses: { "204": { description: '{endpoint, keys{p256dh, auth}, platform}' } } }
    delete: { summary: 구독 해제, security: [ { session: [], csrf: [] } ], responses: { "204": { description: ok } } }
```

웹 푸시 본문(U7, Notification → 브라우저): `{ "type": "VOICE_PROBLEM" | "DEVICE_STATUS_UNKNOWN" | "ENDED_BY_CONNECTION" | "ACTIVITY_COMPLETED", "activityType": "FREE_TALK" | "PRACTICE", "sessionId": "...", "sentAt": "..." }`. 서비스 워커가 type(완료는 activityType까지)으로 문구 4종(FR7.10 표)을 고른다. 아이 이름 · 발화 원문 · 대화 내용 · 점수 · 기록 정리 완료 여부는 넣지 않는다. ACTIVITY_COMPLETED는 ACTIVE를 거친 세션이 CHILD_STOP · GUARDIAN_STOP · SILENCE로 ENDED(confirmed=true)가 됐을 때만 사건 키 `{sessionId}:ACTIVITY_COMPLETED`로 한 번 보낸다. 알림을 누르면 04가 `/api/sessions/current`로 다시 묻는다.

## 4. 아이 기기 REST + 기기 WSS (C2 · C7)

### 4.1 아이 기기 REST (C2 — 아이 기기 웹 → Core DeviceRegistry)

흐름: 기기 웹 시작 → `GET /api/device/me` → 200이면 T19, 401이면 D0. D0은 `POST /api/device/bootstrap`으로 코드를 받아 보여 주고 2초마다 `GET /api/device/bootstrap/status`를 묻는다. REGISTERED가 되면 `POST /api/device/bootstrap/claim`으로 기기 쿠키를 받고 "등록되었어요"(3초) 뒤 T19로 간다. 보호자 쪽 05a 등록은 3절 `/device/register`다. bootstrap은 Core가 맡는다(J4. 개발 명세의 "Realtime(/device, /ws)"를 대체).

```yaml
openapi: 3.0.3
info: { title: 무중 Core 아이 기기 API, version: "1" }
servers: [ { url: 'https://{host}/api' } ]
components:
  securitySchemes:
    deviceBootstrap: { type: apiKey, in: cookie, name: MUJOONG_DEVICE_BOOTSTRAP }
    device: { type: apiKey, in: cookie, name: MUJOONG_DEVICE }
paths:
  /device/bootstrap:
    post:
      summary: D0 코드 받기. 유효한 bootstrap 쿠키가 이미 있으면 같은 코드를 돌려준다
      security: []
      responses:
        "201": { description: '{code, expiresAt} + Set-Cookie MUJOONG_DEVICE_BOOTSTRAP(HttpOnly · Secure · SameSite=Strict · Path=/api/device · Max-Age 24시간)' }
        "429": { description: RATE_LIMITED(7절 bootstrap.rate-limit) }
  /device/bootstrap/status:
    get:
      summary: D0 등록 확인(2초 폴링)
      security: [ { deviceBootstrap: [] } ]
      responses:
        "200": { description: '{status: PENDING|REGISTERED|EXPIRED, expiresAt}. EXPIRED면 기기 웹이 조용히 POST /device/bootstrap' }
        "401": { description: BOOTSTRAP_NOT_FOUND(쿠키 없음 · 모르는 값) → POST /device/bootstrap }
  /device/bootstrap/claim:
    post:
      summary: 등록된 코드로 기기 토큰 받기. 토큰은 응답 본문에 없고 쿠키로만 간다. 첫 WSS 인증 전까지는 다시 부를 수 있으며 부를 때마다 새 토큰으로 바꾸고 앞 토큰을 무효로 한다(응답 유실 대비)
      security: [ { deviceBootstrap: [] } ]
      responses:
        "200": { description: '{deviceId} + Set-Cookie MUJOONG_DEVICE(HttpOnly · Secure · SameSite=Strict · Path=/ · Max-Age 400일) + MUJOONG_DEVICE_BOOTSTRAP 삭제' }
        "409": { description: NOT_REGISTERED_YET(아직 PENDING) }
        "410": { description: BOOTSTRAP_CLOSED(만료 · 토큰이 이미 첫 연결에 쓰임) → 기기 웹은 /device/me로 확인 }
  /device/me:
    get:
      summary: 기기 웹 시작 때 등록 확인. 200이면 MUJOONG_DEVICE 쿠키 Max-Age를 400일로 다시 늘린다(갱신형 — 서버 쪽 토큰은 만료 없음, B4)
      security: [ { device: [] } ]
      responses:
        "200": { description: '{deviceId, registeredAt}. 아이 정보는 주지 않는다' }
        "401": { description: DEVICE_NOT_REGISTERED(쿠키 없음 · 모르는 토큰 · RELEASED) → D0 }
```

- 서버는 bootstrap 비밀 · 기기 토큰의 해시만 저장한다(core_device). 원문은 Set-Cookie 응답 한 번에만 있다.
- 기기 웹은 「켜기」로 마이크 · 소리 허용을 받은 뒤에 WSS에 접속한다(D-16 브라우저 마이크 정책). 마이크 거부는 접속 전에 T19 "마이크 허용 안 됨"으로 끝나므로 WSS 계약에 거부 경로가 없다.

### 4.2 기기 WSS (C7 — 아이 기기 웹 ↔ Realtime DeviceGateway)

**접속 · 인증.** `wss://{host}/ws/device`(Nginx → realtime:8081). Handshake에서 ① Origin이 허용 목록(서비스 origin 하나, 환경 변수)에 없으면 403으로 업그레이드하지 않는다. ② `MUJOONG_DEVICE` 쿠키의 토큰을 Core에 확인한다(5절 C9 `verify-token`). 없거나 무효 · RELEASED면 업그레이드한 뒤 바로 4401로 닫는다 — 브라우저는 실패한 Handshake의 HTTP 상태를 읽지 못하므로 기기 웹이 "다시 등록"과 "네트워크 문제"를 가를 수 있게 하려는 것이다. Core 확인이 1초 안에 안 되면 1013으로 닫는다. ③ 업그레이드 뒤 5초 안에 첫 메시지 HELLO가 와야 한다(4408). URL · HELLO에는 토큰을 넣지 않는다(J29). 같은 기기의 새 연결이 인증되면 옛 연결을 4001로 닫는다(J22).

**프레임.** 텍스트 프레임 = JSON 제어 메시지, 바이너리 프레임 = 음성. 제어 봉투: `{ "type", "seq", "sentAt", "opId"?, "sessionId"?, "payload" }`. `seq`는 연결마다 · 방향마다 1부터 1씩 는다. `opId`는 확인이 필요한 서버 명령에만 붙는 불투명 UUID이고 기기는 `ACK.ackOf`로 돌려준다.

| 방향 | type | 언제 | payload 주요 필드 | 확인 · 뜻 |
|---|---|---|---|---|
| 기기 → 서버 | HELLO | 접속 직후 첫 메시지 | appVersion · audio(지원 형식) · resume{sessionId}? | HELLO_OK |
| 서버 → 기기 | HELLO_OK | HELLO를 받으면 | connectionId · serverTime · heartbeatIntervalMs · audio(입력 형식 확정) · session{sessionId, view, resumed}? | — |
| 기기 → 서버 | HEARTBEAT | 5초마다(세션과 무관) | hbSeq · lastRttMs(앞 왕복 지연) | HEARTBEAT_ACK |
| 서버 → 기기 | HEARTBEAT_ACK | HEARTBEAT를 받자마자 | hbSeq · serverTime | 기기가 왕복 지연을 재서 다음 HEARTBEAT에 싣는다 |
| 서버 → 기기 | START_SESSION | 세션 PREPARING 뒤(시작 push) | opId · sessionId · approvedAudio[{contentCode, contentVersionId, audioUrl}] | ACK(= DEVICE_ACK 상태 근거) → DEVICE_READY |
| 기기 → 서버 | ACK | opId가 있는 명령을 받자마자 | ackOf | 받았다는 뜻만. 재생 · 중단 · 참여의 증거가 아니다 |
| 기기 → 서버 | DEVICE_READY | 마이크 · 플레이어 준비와 승인 음성 미리 받기를 마치면 | sessionId · mic(OK/UNAVAILABLE) · player(OK/UNAVAILABLE) · prefetch(OK/PARTIAL) | 하나라도 UNAVAILABLE이면 시작 실패(보호자에게는 DEVICE_NO_ACK와 같은 문구) |
| 서버 → 기기 | SESSION_STATE | T19 표시가 바뀔 때 | sessionId? · view(STANDBY/LISTENING/THINKING/CLOSING) | '말하는 중'은 기기가 실제 재생으로 스스로 정한다 |
| 서버 → 기기 | OUTPUT_BEGIN | 게이트를 통과한 AI 말 묶음 시작 | sessionId · playbackId · source(GPT_LIVE/SCENARIO_SCRIPT) | 뒤따르는 바이너리 0x02가 이 묶음 |
| 서버 → 기기 | OUTPUT_END | 그 묶음 마지막 프레임 뒤 | playbackId · totalMs | — |
| 서버 → 기기 | PLAY_APPROVED_CONTENT | 승인 문구(시작 안내 · 재안내 · 거절 대안 · 공백 경고 · 종료 의도 확인 · 도움 동의 질문 · 대기 문구 · 마무리 인사) | opId · sessionId · playbackId · contentCode · contentVersionId | ACK · PLAYBACK_MARK |
| 서버 → 기기 | STOP_OUTPUT | 출력 취소(게이트 취소 · 끝내기 · 마무리 앞 · 서버 쪽 끼어들기) | opId · sessionId · reason | ACK. 실제로 멈춘 재생은 PLAYBACK_MARK(STOPPED)로만 확인 |
| 기기 → 서버 | PLAYBACK_MARK | 실제 재생 시작 · 1초마다 진행 · 끝 · 멈춤 · 실패 | sessionId · playbackId · status(STARTED/PROGRESS/COMPLETED/STOPPED/FAILED) · playedRange{fromMs, toMs}(모르면 null) · observedAt · stopCause? · failureReason? | 기기 플레이어가 관측한 범위만. 송신량 · 승인량을 재생량으로 쓰지 않는다 |
| 서버 → 기기 | CLOSE_TRANSPORT | 세션 끝(마무리 인사 뒤 · 끝내기 · 끊김 처리 · 시작 실패) | opId · sessionId · reason | ACK → VOICE_SESSION_CLOSED |
| 기기 → 서버 | VOICE_SESSION_CLOSED | 마이크 송신 · 재생 · 대기열 정리를 마치면 | sessionId · outputStoppedAt · micClosedAt | 기기 멈춤 확인(ENDED confirmed 근거). WSS는 대기용으로 남는다 |
| 기기 → 서버 | DEVICE_STATE | 마이크 · 소리 · 탭 보임 상태가 바뀔 때 | mic(OK/LOST) · player(OK/ERROR) · visibility(VISIBLE/HIDDEN) · wakeLock(HELD/RELEASED) | 마이크 문제와 연결 장애를 가르는 근거(FR-F18) |
| 서버 → 기기 | ERROR | 형식이 틀린 메시지(연결은 유지) | code(UNKNOWN_TYPE/BAD_PAYLOAD) · refSeq | — |
| 기기 → 서버 | 바이너리 0x01 | DEVICE_READY 뒤 ~ CLOSE_TRANSPORT 전, 20ms마다 | 입력 음성 | — |
| 서버 → 기기 | 바이너리 0x02 | OUTPUT_BEGIN 뒤 | 승인된 출력 음성 | — |

| 음성 항목 | 값 | 출처 |
|---|---|---|
| 표본 형식 | PCM 16비트 부호 있는 정수, 리틀엔디언 | 다섯 문서 모두 값 없음(ADR protocol "인코딩 · 프레임 TBD") → J27 |
| 표본율 · 채널 | 24,000Hz · 모노 | 값 없음 → J27. GPT-Live 입출력 형식과 같게 해 변환 없이 중계하려는 값. 다르면 VoiceRelay가 변환한다(11절) |
| 입력 프레임 | 20ms = 480표본 = 960바이트 | 값 없음 → J27 |
| 출력 프레임 | 길이 자유(2바이트 배수). 공급자에게 받은 조각을 게이트 뒤 그대로 | 값 없음 → J27 |
| 바이너리 머리 | 1바이트 종류(0x01 입력 · 0x02 출력) + 4바이트 프레임 순번(uint32, 빅엔디언) + 본문 | 값 없음 → J27 |
| 승인 문구 음성 파일 | WAV(PCM16 · 24kHz · 모노), 개인정보 없는 정적 파일 `audioUrl`(Nginx), START_SESSION 때 미리 받기 | 값 없음 → J30 |

**규칙.**

- 기기는 말의 뜻을 판정하지 않는다. CHILD_STOP_REQUEST · CHILD_PARTICIPATED · PAUSE_REQUEST 같은 메시지는 없다. 종료 표현 · 참여 판단은 Realtime이 입력 음성으로 한다(ADR protocol).
- 대기 중(세션 없음)에는 입력 음성을 보내지 않는다. 마이크는 「켜기」 때 열어 두되 프레임은 DEVICE_READY 뒤부터 CLOSE_TRANSPORT 전까지만 보낸다.
- 끼어들기: 재생 중 기기 로컬 음성 활동(에코 제거를 켠 마이크의 소리 크기)이 200ms 이어지면 기기가 바로 재생을 멈추고 대기열을 비운 뒤 PLAYBACK_MARK(STOPPED, stopCause LOCAL_VOICE_ACTIVITY)를 보낸다. 목표 0.5초(7절). 뜻 판정이 아니며 남은 출력 버리기 · 모델 맥락 교정은 서버가 한다(J31).
- 연결이 끊기면 기기는 재생 · 대기열을 바로 버리고(FR-E11) 1 · 2 · 4초 뒤, 그 뒤로는 5초마다 다시 접속한다. HELLO의 `resume.sessionId`로 이어가기를 청하고, 서버는 세션이 끝나지 않았고 끊김 40초 전이면 `resumed: true`를 준다. 끊기기 전 출력은 이어서 재생하지 않는다(새 playbackId만 재생).
- 「끄기」: 기기는 정리한 뒤 close 1000(reason `DEVICE_OFF`)으로 닫는다. 서버는 이를 끊김(DISCONNECTED)과 구분해 OFFLINE(아이가 끔)으로 둔다. 진행 중 세션을 바로 끝낼지는 3.1 U4가 정한다(11절).
- 순서 · 중복: 받는 쪽은 같은 `seq`를 한 번만 처리한다. 서버는 옛 연결에서 온 늦은 메시지와 끝난 세션의 메시지를 버린다(늦은 보고로 세션을 되살리거나 시계를 시작하지 않는다). ACK를 받지 못한 채 연결이 바뀌면 서버는 같은 `opId`로 새 연결에 다시 보내고, 기기는 같은 `opId`를 두 번 실행하지 않는다.
- 대기열 상한: 기기는 재생 대기열이 20초 분량을 넘으면 그 묶음을 버리고 PLAYBACK_MARK(FAILED, QUEUE_OVERFLOW)를 보낸다.

| 닫기 코드 | 이름 | 누가 · 언제 | 기기 웹 동작 |
|---|---|---|---|
| 1000 | DEVICE_OFF | 기기 「끄기」 | 꺼짐 |
| 1001 | GOING_AWAY | 서버 재시작 · 배포 | 다시 접속 |
| 1011 | SERVER_ERROR | 서버 오류 | 다시 접속 |
| 1013 | TRY_AGAIN_LATER | Core 토큰 확인 불가 | 다시 접속 |
| 4001 | REPLACED | 같은 기기의 새 연결이 인증됨 | 꺼짐(자동 재접속 안 함 — 두 창이 서로 밀어내는 반복 방지) |
| 4400 | PROTOCOL_ERROR | HELLO 형식 오류 등 | 다시 접속 |
| 4401 | UNAUTHORIZED | 쿠키 없음 · 무효 · RELEASED(재등록 · 계정 삭제, DeviceReleased) | `GET /api/device/me` → 401이면 D0 |
| 4408 | HELLO_TIMEOUT | 업그레이드 뒤 HELLO 없음 | 다시 접속 |

```yaml
asyncapi: 2.6.0
info: { title: 무중 기기 WSS(아이 기기 웹 ↔ Realtime DeviceGateway), version: "1" }
servers:
  public: { url: '{host}/ws/device', protocol: wss, description: 'Nginx → realtime:8081. Handshake에서 MUJOONG_DEVICE 쿠키 + 허용 Origin 확인' }
channels:
  /ws/device:
    publish:
      description: 기기 → 서버
      message:
        oneOf:
          - $ref: '#/components/messages/HELLO'
          - $ref: '#/components/messages/HEARTBEAT'
          - $ref: '#/components/messages/ACK'
          - $ref: '#/components/messages/DEVICE_READY'
          - $ref: '#/components/messages/PLAYBACK_MARK'
          - $ref: '#/components/messages/VOICE_SESSION_CLOSED'
          - $ref: '#/components/messages/DEVICE_STATE'
          - $ref: '#/components/messages/INPUT_AUDIO'
    subscribe:
      description: 서버 → 기기
      message:
        oneOf:
          - $ref: '#/components/messages/HELLO_OK'
          - $ref: '#/components/messages/HEARTBEAT_ACK'
          - $ref: '#/components/messages/START_SESSION'
          - $ref: '#/components/messages/SESSION_STATE'
          - $ref: '#/components/messages/OUTPUT_BEGIN'
          - $ref: '#/components/messages/OUTPUT_END'
          - $ref: '#/components/messages/PLAY_APPROVED_CONTENT'
          - $ref: '#/components/messages/STOP_OUTPUT'
          - $ref: '#/components/messages/CLOSE_TRANSPORT'
          - $ref: '#/components/messages/ERROR'
          - $ref: '#/components/messages/OUTPUT_AUDIO'
components:
  schemas:
    Envelope:
      type: object
      required: [type, seq, sentAt]
      properties:
        type: { type: string }
        seq: { type: integer, minimum: 1, description: 연결마다 · 방향마다 1부터 }
        sentAt: { type: string, format: date-time }
        opId: { type: string, format: uuid, description: 확인이 필요한 서버 명령에만 }
        sessionId: { type: string, format: uuid, nullable: true }
        payload: { type: object }
    AudioFrame:
      type: string
      format: binary
      description: '[0] 종류(0x01 입력 · 0x02 출력) / [1..4] 프레임 순번 uint32 빅엔디언 / [5..] PCM16LE 24kHz 모노. 입력은 20ms = 960바이트'
  messages:
    HELLO: { payload: { type: object, required: [appVersion, audio], properties: { appVersion: { type: string }, audio: { type: array, items: { type: string, example: pcm16le/24000/1 } }, resume: { type: object, nullable: true, properties: { sessionId: { type: string, format: uuid } } } } } }
    HELLO_OK: { payload: { type: object, properties: { connectionId: { type: string, format: uuid }, serverTime: { type: string, format: date-time }, heartbeatIntervalMs: { type: integer, example: 5000 }, audio: { type: string, example: pcm16le/24000/1 }, session: { type: object, nullable: true, properties: { sessionId: { type: string, format: uuid }, view: { type: string, enum: [STANDBY, LISTENING, THINKING, CLOSING] }, resumed: { type: boolean } } } } } }
    HEARTBEAT: { payload: { type: object, required: [hbSeq], properties: { hbSeq: { type: integer }, lastRttMs: { type: integer, nullable: true } } } }
    HEARTBEAT_ACK: { payload: { type: object, properties: { hbSeq: { type: integer }, serverTime: { type: string, format: date-time } } } }
    START_SESSION: { payload: { type: object, required: [approvedAudio], properties: { approvedAudio: { type: array, items: { type: object, properties: { contentCode: { type: string }, contentVersionId: { type: string, format: uuid }, audioUrl: { type: string } } } } } } }
    ACK: { payload: { type: object, required: [ackOf], properties: { ackOf: { type: string, format: uuid } } } }
    DEVICE_READY: { payload: { type: object, properties: { mic: { type: string, enum: [OK, UNAVAILABLE] }, player: { type: string, enum: [OK, UNAVAILABLE] }, prefetch: { type: string, enum: [OK, PARTIAL] } } } }
    SESSION_STATE: { payload: { type: object, properties: { view: { type: string, enum: [STANDBY, LISTENING, THINKING, CLOSING] } } } }
    OUTPUT_BEGIN: { payload: { type: object, required: [playbackId, source], properties: { playbackId: { type: string, format: uuid }, source: { type: string, enum: [GPT_LIVE, SCENARIO_SCRIPT] } } } }
    OUTPUT_END: { payload: { type: object, properties: { playbackId: { type: string, format: uuid }, totalMs: { type: integer } } } }
    PLAY_APPROVED_CONTENT: { payload: { type: object, required: [playbackId, contentCode, contentVersionId], properties: { playbackId: { type: string, format: uuid }, contentCode: { type: string, description: '코드 목록은 U2 ApprovedContentCatalog 소유(3.1 확정). 예: INTRO_FREE_TALK · INTRO_PRACTICE(AC6.3.2) · RE_PROMPT · DECLINE_ALTERNATIVE · SILENCE_WARNING · STOP_CONFIRM(AC6.4.5) · HELP_OFFER(AC6.5.3) · CLOSING · WAITING_FILLER' }, contentVersionId: { type: string, format: uuid } } } }
    STOP_OUTPUT: { payload: { type: object, properties: { reason: { type: string, enum: [GATE_CANCEL, GUARDIAN_STOP, CHILD_STOP, SILENCE, BEFORE_CLOSING, SERVER_BARGE_IN, TECHNICAL] } } } }
    PLAYBACK_MARK: { payload: { type: object, required: [playbackId, status, observedAt], properties: { playbackId: { type: string, format: uuid }, status: { type: string, enum: [STARTED, PROGRESS, COMPLETED, STOPPED, FAILED] }, playedRange: { type: object, nullable: true, properties: { fromMs: { type: integer }, toMs: { type: integer } } }, observedAt: { type: string, format: date-time }, stopCause: { type: string, nullable: true, enum: [LOCAL_VOICE_ACTIVITY, STOP_OUTPUT, CLOSE_TRANSPORT, DISCONNECTED] }, failureReason: { type: string, nullable: true, enum: [DECODE_ERROR, AUDIO_DEVICE_ERROR, QUEUE_OVERFLOW, FETCH_FAILED] } } } }
    CLOSE_TRANSPORT: { payload: { type: object, properties: { reason: { type: string, enum: [ENDED, GUARDIAN_STOP, CHILD_STOP, SILENCE, DISCONNECT, CONSENT_WITHDRAWN, DECLINED, FAILED] } } } }
    VOICE_SESSION_CLOSED: { payload: { type: object, required: [outputStoppedAt, micClosedAt], properties: { outputStoppedAt: { type: string, format: date-time }, micClosedAt: { type: string, format: date-time } } } }
    DEVICE_STATE: { payload: { type: object, properties: { mic: { type: string, enum: [OK, LOST] }, player: { type: string, enum: [OK, ERROR] }, visibility: { type: string, enum: [VISIBLE, HIDDEN] }, wakeLock: { type: string, enum: [HELD, RELEASED] } } } }
    ERROR: { payload: { type: object, properties: { code: { type: string, enum: [UNKNOWN_TYPE, BAD_PAYLOAD] }, refSeq: { type: integer } } } }
    INPUT_AUDIO: { contentType: application/octet-stream, payload: { $ref: '#/components/schemas/AudioFrame' } }
    OUTPUT_AUDIO: { contentType: application/octet-stream, payload: { $ref: '#/components/schemas/AudioFrame' } }
```

**문서 값과 이 계약의 값.** 기기 쪽 물리값은 문서마다 비었거나 달랐다. 상위 문서 값이 있으면 그것을, 없으면 준하 제안을 썼다.

| 항목 | 문서에 있던 값 | 이 계약 |
|---|---|---|
| WSS 경로 · 토큰 전달 | 개발 명세 API 표 `WS /ws/device?token=`. ADR-008(Proposed): 허용 Origin + 고정 ID, 장기 인증을 localStorage/sessionStorage에 두지 않음, 운영용 기기 인증 저장 · 전달은 TBD | `/ws/device` + HttpOnly 쿠키 + 허용 Origin(J29). 쿼리 토큰은 Nginx 접근 로그에 남아 NFR9와 어긋난다 |
| bootstrap 위치 | 개발 명세: Realtime | Core DeviceRegistry(J4) |
| 메시지 이름 | ADR protocol(후보): STOP_OUTPUT · PLAY_APPROVED_CONTENT · CLOSE_TRANSPORT · SESSION_STATE · PING · ACK · HEARTBEAT · DEVICE_READY · PLAYBACK_FINISHED/playbackMark · VOICE_SESSION_CLOSED · TRANSPORT_STATE. 시작 push · 입력 음성 · 출력 음성은 이름 TBD | 후보 이름은 그대로, 빈 것만 새 이름(J30). PING → HEARTBEAT_ACK, PLAYBACK_FINISHED → PLAYBACK_MARK(COMPLETED), TRANSPORT_STATE → DEVICE_STATE |
| playbackMark 필드 | ADR protocol(후보): sessionId · connectionAttemptId · playbackId · sequence · playbackStatus · observedAt · playedRange(미확인이면 null + 사유) · failureReason | 그대로. connectionAttemptId는 서버가 연결로 알므로 메시지에서 뺐고 sequence는 봉투 seq |
| heartbeat · 끊김 | ADR(Proposed): heartbeat 약 5초, OFFLINE 약 15초. 개발 명세 · NFR6 · B10: heartbeat 5초 · 끊김 40초 · 지연 1초 · 5분 안 2회 | 5초 · 40초 · 1초 · 5분 안 2회(상위 문서) |
| 끼어들기 | TS-N04: 0.5초 안 재생 멈춤. 감지 주체는 GPT-Live 기능 명세 L-D03 결정 대상 | 기기 로컬 감지(J31) |
| 끊김 때 기기 | FR-E11: 기기가 받아 둔 응답 재생을 스스로 멈추고 복구 뒤 이어 재생하지 않음 | 그대로 |
| 오디오 형식 · 프레임 | 다섯 문서 모두 없음 | J27 |

## 5. Core ↔ Realtime 내부 HTTP (C8 · C9 · C10)

```yaml
openapi: 3.0.3
info: { title: 무중 내부 API(앱 사이), version: "1" }
components:
  securitySchemes:
    internal: { type: apiKey, in: header, name: X-Internal-Token }
  schemas:
    Scenario:
      type: object
      properties:
        scenarioId: { type: string, format: uuid }
        kind: { type: string, enum: [ORIGINAL, NEW_SCENARIO] }
        relationType: { type: string }
        opportunityCondition: { type: string }
        scenarioText: { type: string, description: 아이에게 들려줄 상황(만들어진 상황임을 안내하는 문장 포함) }
        partnerRoleText: { type: string, description: 대화 역할 지시(판정 기준 · 정답 문장 없음) }
        triggerScript: { type: string, description: '기회 트리거 승인 대본(AC6.5.1). 기기에는 OUTPUT_BEGIN source=SCENARIO_SCRIPT 묶음으로 간다 — 개인 정보가 들 수 있어 정적 파일(audioUrl)로 두지 않는다. 음성을 만드는 방법은 3.1 U5' }
        validation: { type: object, properties: { goalIncluded: { type: boolean }, triggerIncluded: { type: boolean }, forbiddenFound: { type: boolean } } }
security: [ { internal: [] } ]
paths:
  # ── C8: Core(ActivityCoordinator) → Realtime(SessionLifecycle) — http://realtime:8081/internal ──
  /internal/sessions:
    post:
      summary: 세션 시작 명령(세션 패키지). sessionId는 Core가 발급(J11)
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [sessionId, idempotencyKey, guardianId, childId, deviceId, activityType, consentSnapshot, childContext, stopExpressions, approvedContent]
              properties:
                sessionId: { type: string, format: uuid }
                idempotencyKey: { type: string, format: uuid }
                guardianId: { type: string, format: uuid }
                childId: { type: string, format: uuid }
                deviceId: { type: string, format: uuid }
                activityType: { type: string, enum: [FREE_TALK, PRACTICE] }
                consentSnapshot: { type: object, properties: { policyVersion: { type: string }, voiceAndText: { type: boolean }, guardianView: { type: boolean }, reportSharing: { type: boolean }, rawAudio: { type: boolean } } }
                childContext: { type: object, description: 대화 역할용 최소 문맥(NFR3) — 08 입력 원문은 넣지 않는다, properties: { displayName: { type: string }, age: { type: integer }, interests: { type: array, items: { type: string } } } }
                stopExpressions: { type: array, items: { type: object, properties: { text: { type: string }, isDefault: { type: boolean } } } }
                approvedContent:
                  type: object
                  description: 세션에 고정할 승인 문구 버전(A5)
                  additionalProperties: { type: object, properties: { contentVersionId: { type: string, format: uuid }, text: { type: string }, audioUrl: { type: string } } }
                  example: { INTRO_FREE_TALK: {}, INTRO_PRACTICE: {}, RE_PROMPT: {}, DECLINE_ALTERNATIVE: {}, SILENCE_WARNING: {}, STOP_CONFIRM: {}, HELP_OFFER: {}, CLOSING: {}, WAITING_FILLER: {} }
                practice:
                  type: object
                  nullable: true
                  properties:
                    goals:
                      type: array
                      items:
                        type: object
                        properties:
                          goalSelectionId: { type: string, format: uuid }
                          order: { type: integer }
                          displayName: { type: string }
                          behaviorKey: { type: string }
                          behaviorVersion: { type: integer }
                          judgmentCriteria: { type: string, description: 판정 역할에만 쓰고 대화 역할 지시문에 넣지 않는다 }
                          firstScenario: { $ref: '#/components/schemas/Scenario', nullable: true, description: 첫 목표에만 }
      responses: { "202": { description: '{status: PREPARING}' }, "409": { description: SESSION_IN_PROGRESS(계정당 1개, Realtime 2차 방어) }, "423": { description: DEVICE_NOT_CONNECTED } }
  /internal/sessions/{sessionId}/end:
    post:
      summary: 끝내기 명령(보호자 · 계정 삭제)
      requestBody: { content: { application/json: { schema: { type: object, required: [requestId, reason], properties: { requestId: { type: string, format: uuid }, reason: { type: string, enum: [GUARDIAN_STOP, CONSENT_WITHDRAWN] } } } } } }
      responses: { "202": { description: '{status: CLOSING}' }, "409": { description: SESSION_NOT_ACTIVE } }
  /internal/sessions/{sessionId}/view:
    get:
      summary: 진행 조회(Core가 소유 확인 뒤 중계). 응답 모양은 보호자 LiveView에서 observedAt · stale을 뺀 것
      parameters: [ { name: sinceSeq, in: query, schema: { type: integer } } ]
      responses: { "200": { description: LiveView(내부) }, "404": { description: NOT_FOUND } }
  # ── C9: Realtime(DeviceGateway) → Core(DeviceRegistry) — http://core:8080/internal ──
  /internal/devices/verify-token:
    post:
      summary: WSS Handshake 쿠키(MUJOONG_DEVICE)의 기기 토큰 확인. 처음 확인되면 그 토큰을 '첫 연결됨'으로 표시해 claim을 닫는다(결과는 Realtime이 캐시, DeviceReleased로 무효)
      requestBody: { content: { application/json: { schema: { type: object, required: [deviceToken], properties: { deviceToken: { type: string } } } } } }
      responses: { "200": { description: '{deviceId, guardianId, childId, registrationStatus: REGISTERED}' }, "401": { description: INVALID_DEVICE_TOKEN(미등록 · RELEASED · 없음) } }
  /internal/devices/{deviceId}/connection:
    put:
      summary: 연결 상태 주기 보고(최신값 덮어쓰기, 유실 허용)
      requestBody: { content: { application/json: { schema: { type: object, properties: { state: { type: string, enum: [OFFLINE, CONNECTED, UNSTABLE, DISCONNECTED, UNRESPONSIVE] }, rttMs: { type: integer }, recentDisconnects: { type: integer }, since: { type: string, format: date-time }, lastSeenAt: { type: string, format: date-time }, reportedAt: { type: string, format: date-time } } } } } }
      responses: { "204": { description: ok } }
  # ── C10: Realtime(PracticeProtocol) → Core(PracticeGoal) — http://core:8080/internal ──
  /internal/practice/scenarios:
    post:
      summary: 다음 목표(또는 새 상황)의 상황 생성 · 확인 · 재생성 1회. 내준 상황은 다시 쓰지 않는다
      requestBody: { content: { application/json: { schema: { type: object, required: [sessionId, goalSelectionId, kind], properties: { sessionId: { type: string, format: uuid }, goalSelectionId: { type: string, format: uuid }, kind: { type: string, enum: [ORIGINAL, NEW_SCENARIO] }, previousScenarioId: { type: string, format: uuid, nullable: true } } } } } }
      responses: { "200": { description: 확인된 상황, content: { application/json: { schema: { $ref: '#/components/schemas/Scenario' } } } }, "422": { description: SCENARIO_GEN_FAILED(두 번 실패) } }
```

## 6. 영속 이벤트 6종 (C11)

각 앱은 상대 앱의 `POST /internal/events`(Inbox)로 이벤트를 하나씩 보낸다. 수신 쪽은 Inbox에 커밋한 뒤에만 200을 돌려주고, 같은 `eventId`는 한 번만 업무에 반영한다(ADR-010).

```yaml
asyncapi: 2.6.0
info: { title: 무중 내부 영속 이벤트, version: "1" }
channels:
  core-inbox:
    description: Realtime → Core. 전달 = POST http://core:8080/internal/events
    publish:
      message: { oneOf: [ { $ref: '#/components/messages/SessionStateChanged' }, { $ref: '#/components/messages/SessionRecordCaptured' }, { $ref: '#/components/messages/DeviceStatusChanged' }, { $ref: '#/components/messages/DeletionCompleted' } ] }
  realtime-inbox:
    description: Core → Realtime. 전달 = POST http://realtime:8081/internal/events
    publish:
      message: { oneOf: [ { $ref: '#/components/messages/DeviceReleased' }, { $ref: '#/components/messages/DeletionRequested' } ] }
components:
  schemas:
    Envelope:
      type: object
      required: [eventId, eventType, schemaVersion, occurredAt, aggregateType, aggregateId, payload]
      properties:
        eventId: { type: string, format: uuid }
        eventType: { type: string }
        schemaVersion: { type: integer, minimum: 1 }
        occurredAt: { type: string, format: date-time }
        aggregateType: { type: string, enum: [ActivitySession, Device, DataDeletionRequest] }
        aggregateId: { type: string, format: uuid }
        payload: { type: object }
  messages:
    SessionStateChanged:
      summary: U4 발행 · U4 ActivityCoordinator(사본) · U7 Notification 구독
      payload:
        type: object
        required: [sessionId, guardianId, status, statusVersion, at]
        properties:
          sessionId: { type: string, format: uuid }
          guardianId: { type: string, format: uuid }
          childId: { type: string, format: uuid }
          activityType: { type: string, enum: [FREE_TALK, PRACTICE] }
          status: { type: string, enum: [PREPARING, DEVICE_ACK, WAITING_CHILD, ACTIVE, CLOSING, ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED] }
          statusVersion: { type: integer, description: 세션별 단조 증가. 받는 쪽은 자기 값보다 작거나 같으면 버린다 }
          endReason: { type: string, nullable: true }
          failureCode: { type: string, nullable: true }
          confirmed: { type: boolean }
          technicalPause: { type: object, nullable: true, properties: { kind: { type: string, enum: [DEVICE, PROVIDER] }, since: { type: string, format: date-time } } }
          at: { type: string, format: date-time }
    SessionRecordCaptured:
      summary: U4 발행(종료 상태에서 한 번, 다시 보내도 같은 snapshotHash) · U4 ActivityRecord 구독
      payload:
        type: object
        required: [sessionId, guardianId, childId, activityType, finalStatus, snapshotId, snapshotHash]
        properties:
          sessionId: { type: string, format: uuid }
          guardianId: { type: string, format: uuid }
          childId: { type: string, format: uuid }
          activityType: { type: string, enum: [FREE_TALK, PRACTICE] }
          finalStatus: { type: string, enum: [ENDED, ENDED_UNCONFIRMED, ABORTED, DECLINED, FAILED] }
          endReason: { type: string }
          confirmed: { type: boolean }
          startedAt: { type: string, format: date-time, nullable: true }
          endedAt: { type: string, format: date-time }
          termination: { type: object, properties: { requestedAt: { type: string, format: date-time }, outputStoppedAt: { type: string, format: date-time }, transportClosedAt: { type: string, format: date-time }, providerClosedAt: { type: string, format: date-time } } }
          snapshotId: { type: string, format: uuid }
          snapshotVersion: { type: integer }
          snapshotHash: { type: string, description: 'sha256:<hex> — record 정규화 JSON의 해시' }
          record:
            type: object
            description: DECLINED면 utterances · practice · audio가 빈 배열(기록 없음, FR3.5)
            properties:
              utterances: { type: array, items: { type: object, properties: { seq: { type: integer }, speaker: { type: string, enum: [CHILD, AI] }, text: { type: string }, uncertain: { type: boolean }, startedAt: { type: string, format: date-time }, endedAt: { type: string, format: date-time } } } }
              gaps: { type: array, items: { type: object, properties: { from: { type: string, format: date-time }, to: { type: string, format: date-time }, kind: { type: string, enum: [DISCONNECT, UNCONFIRMED] } } } }
              recordScope: { type: object, properties: { from: { type: string, format: date-time }, to: { type: string, format: date-time } } }
              practice:
                type: object
                nullable: true
                description: 진행 중 판정값을 담는다. Core는 기록용 재판정(U6)의 입력으로만 쓰고 보호자 응답 · 리포트에 내보내지 않는다(FR4.3)
                properties:
                  goals: { type: array, items: { type: object, properties: { goalSelectionId: { type: string }, displayName: { type: string }, order: { type: integer }, status: { type: string, enum: [DONE, SKIPPED, NOT_REACHED] }, skipReason: { type: string, nullable: true }, scenarios: { type: array, items: { type: object, properties: { scenarioId: { type: string }, exposureType: { type: string, enum: [ORIGINAL, NEW_SCENARIO] }, exposedAt: { type: string } } } }, responses: { type: array, items: { type: object, properties: { scenarioId: { type: string, description: 'scenarios[].scenarioId — 장면 연결(U6 기록용 재판정 입력)' }, responseType: { type: string, enum: [FIRST, AFTER_CUE, NEW_SCENARIO] }, utteranceSeq: { type: integer, nullable: true }, notPerformedReason: { type: string, nullable: true }, label: { type: string, enum: [functional, partial, none, uncertain, NOT_PERFORMED] } } } }, cues: { type: array, items: { type: object, properties: { scenarioId: { type: string }, consentResponse: { type: string, enum: [ACCEPTED, DECLINED, NO_RESPONSE] }, deliveredText: { type: string }, containsAnswerSentence: { type: boolean }, givenAt: { type: string } } } } } } }
              audio: { type: array, items: { type: object, properties: { recordingId: { type: string }, speaker: { type: string }, storagePath: { type: string }, startedAt: { type: string }, endedAt: { type: string } } } }
    DeviceStatusChanged:
      summary: U3 발행(상태가 바뀔 때만) · U1 DeviceRegistry · U7 구독
      payload: { type: object, required: [deviceId, state, since], properties: { deviceId: { type: string, format: uuid }, state: { type: string, enum: [OFFLINE, CONNECTED, UNSTABLE, DISCONNECTED, UNRESPONSIVE] }, rttMs: { type: integer }, recentDisconnects: { type: integer }, since: { type: string, format: date-time }, lastSeenAt: { type: string, format: date-time } } }
    DeletionCompleted:
      summary: Realtime 발행(U4 SessionLifecycle가 Realtime 쪽 참가자를 모두 돌린 뒤) · U2 AccountDeletion 구독
      payload: { type: object, required: [deletionRequestId, guardianId, completedAt], properties: { deletionRequestId: { type: string, format: uuid }, guardianId: { type: string, format: uuid }, completedAt: { type: string, format: date-time }, counts: { type: object, properties: { sessions: { type: integer }, transcripts: { type: integer }, audioFiles: { type: integer }, practiceRecords: { type: integer } } } } }
    DeviceReleased:
      summary: U1 DeviceRegistry 발행 · U3 DeviceGateway 구독(토큰 캐시 지우고 연결 닫기)
      payload: { type: object, required: [deviceId, reason], properties: { deviceId: { type: string, format: uuid }, guardianId: { type: string, format: uuid }, reason: { type: string, enum: [REREGISTERED, ACCOUNT_DELETED] } } }
    DeletionRequested:
      summary: U2 AccountDeletion 발행 · U4 SessionLifecycle 구독(Realtime 쪽 삭제 지휘)
      payload: { type: object, required: [deletionRequestId, guardianId, requestedAt], properties: { deletionRequestId: { type: string, format: uuid }, guardianId: { type: string, format: uuid }, childId: { type: string, format: uuid }, requestedAt: { type: string, format: date-time } } }
```

순서 · 중복: 전역 순서를 가정하지 않는다. 세션 이벤트는 `statusVersion`으로, 기기 이벤트는 `since`로 최신만 반영한다. 같은 `eventId`에 다른 내용이 오면 충돌로 기록하고 반영하지 않는다(ADR-010 §2.5).

## 7. 시간 계약 (T, WBS 1.7.6)

값은 이 표 한 곳에만 둔다. 코드는 설정 파일(`mujoong.timing.*`)로 읽고, 시험은 같은 키를 줄여 주입한다(NFR6). 11/2 측정 뒤 바뀌면 이 표를 먼저 고친다.

| 키 | 값 | 시계 시작점(t0) → 끝 | 근거 |
|---|---|---|---|
| session.preparing-timeout | 10초 | REQUESTED → PREPARING 진입 실패면 FAILED | B5 |
| session.device-ack-timeout | 20초 | START_SESSION 송신 → 이 안에 ACK와 DEVICE_READY가 모두 없으면 FAILED(DEVICE_NO_ACK), 기기 UNRESPONSIVE | NFR6 · B7 |
| session.join-wait | 20초 | 시작 안내 재생 완료 → 아이 응답 없음이면 재안내 1회 | NFR6 · Q7 |
| session.join-wait-after-reprompt | 20초 | 재안내 재생 완료 → 응답 없음이면 DECLINED(합계 40초) | NFR6 |
| session.silence-warning | 4분 | 아이 마지막 확정 전사 끝(또는 첫 안내 재생 완료) → 공백 경고 · 기기 안내 1회 | FR7.9 · B9 |
| session.silence-end | 6분 | 같은 t0 → SILENCE로 CLOSING | FR7.9 · B9 |
| session.closing-confirm | 30초 | CLOSING 진입 → 기기 멈춤 확인 없으면 ENDED_UNCONFIRMED | NFR6 · B11 |
| device.heartbeat-interval | 5초 | 기기가 HEARTBEAT 송신 주기 | ADR-005 · J27 |
| device.hello-timeout | 5초 | WSS 업그레이드 → HELLO 없으면 4408 | J29 |
| device.reconnect-backoff | 1 · 2 · 4초, 그 뒤 5초 간격 | 연결 끊김 → 기기 재접속 시도(켜짐 상태에서만) | J31 |
| device.playback-mark-interval | 1초 | 재생 중 PLAYBACK_MARK(PROGRESS) 주기 | J30 |
| device.output-queue-max | 20초 분량 | 기기 재생 대기열 상한, 넘으면 그 묶음 FAILED(QUEUE_OVERFLOW) | J31 |
| device.cookie-max-age | 400일(갱신형) | `GET /api/device/me` 성공마다 MUJOONG_DEVICE 쿠키 수명을 다시 400일로(크롬 쿠키 수명 상한). 서버 쪽 토큰은 만료 없음 | B4 · J29 |
| device.gap-display | 10초 | 마지막 기기 메시지 → 12 점선 · "대화 연결 복구 중" | B10 |
| device.disconnect | 40초 | 마지막 기기 메시지 → DISCONNECTED, 진행 중이면 ABORTED(DISCONNECT) → P2 | NFR6 · B10 |
| device.unstable-rtt | 1초 | heartbeat 왕복 지연이 넘으면 UNSTABLE | NFR6 |
| device.unstable-disconnects | 5분 안 2회 | 최근 5분 창 끊김 횟수 ≥ 2면 UNSTABLE, 2회 미만 · 지연 1초 이하로 복귀 | NFR6 · B7 |
| device.status-report-interval | 10초 | Realtime → Core 연결 상태 주기 보고 | J26 |
| core.device-status-stale | 30초 | Core가 마지막 보고 받은 시각 → "기기 상태 확인 불가"(B6) · 푸시 후보 | B6 |
| parent.poll-interval | 2초 | 12 진행 화면 폴링(다른 화면은 진입 때 1회) | A2 |
| bootstrap.code-ttl | 24시간 | 코드 발급 → 만료면 기기 웹이 새 코드 | J22 |
| bootstrap.status-poll | 2초 | D0 등록 상태 확인 주기 | J22 |
| bootstrap.rate-limit | IP당 1시간 20회 | `POST /api/device/bootstrap` 남용 막기 → 429 | J29 |
| device.register-failure-limit | 보호자당 10분 10회 | 05a 코드 등록 실패 → 429 TOO_MANY_ATTEMPTS(31^8 코드 맞히기 막기) | J29 |
| practice.first-response-wait | 20초 | 상황 · 트리거 재생 완료 → 응답 없으면 NOT_PERFORMED | B15 |
| practice.help-consent-wait | 10초 | 도움 동의 질문 재생 완료 → 응답 없으면 거부로 기록 | B15 |
| practice.cue-budget | 5초 | Cue 요청 → 재생 시작, 넘으면 Cue 미제공으로 기록 · 새 상황 | FR5.5 · B15 |
| practice.judge-budget | 5초 + 재시도 1회 | 판정 요청 → 실패면 uncertain | FR7.6 |
| practice.scenario-request-timeout | 15초 | 다음 목표 상황 요청 → 초과면 그 목표 미실시 | J26 |
| gate.barge-in-stop | 0.5초 | 아이 끼어들기 → 기기 재생 중단(기기 로컬) | FR7.8 · NFR5 · TS-N04 |
| gate.local-voice-min | 200ms | 재생 중 기기 로컬 음성 활동이 이만큼 이어지면 끼어들기로 보고 멈춘다(0.5초 예산 안) | J31 |
| result.job-attempts | 3회 | 결과 · 리포트 작업 자동 시도 상한(간격 5 · 15초) | NFR8 · J3 |
| report.ready-target | 60초 | 리포트 요청 → READY | NFR8 |
| goal.restore-window | 5초 | 「×」 뒤 「되돌리기」 가능 시간(토스트 3초 이상, 초점이 있으면 유지) | FR4.1 · B25 |
| login.session-idle | 30일 | 마지막 요청 → 세션 만료(갱신형) | A1 |

## 8. 앱 안 포트 · 공유 스키마 (C12 · C13)

같은 Spring 앱 안에서 유닛이 다른 유닛 컴포넌트를 부르는 자리다. 포트는 제공 유닛이 인터페이스로 공개하고 소비 유닛은 그 인터페이스만 부른다(ADR-007 §3 · ADR-019).

```yaml
shared-schema:
  ports:
    ConsentGate:            # 제공 U2 → 소비 U4 · U5
      check: { in: guardianId, out: { ok: boolean, status: [NOT_GIVEN, GIVEN, OUTDATED], snapshot: { policyVersion: string, voiceAndText: boolean, guardianView: boolean, reportSharing: boolean, rawAudio: boolean } } }
    ChildContext:           # 제공 U2 → 소비 U4 · U6(Report)
      get: { in: guardianId, out: { childId: uuid, displayName: string, age: int, interests: [string], stopExpressions: [ { text: string, isDefault: boolean } ] } }
    ApprovedContentPin:     # 제공 U2 → 소비 U4 · U5
      pin: { in: [contentCode], out: { contentCode: { contentVersionId: uuid, text: string, audioUrl: string } } }
    DeviceReadiness:        # 제공 U1 → 소비 U4 · U2
      get: { in: guardianId, out: { deviceId: uuid, canStart: boolean, reasonCode: string } }
    DeletionParticipant:    # 제공(계약) U2 → 구현 U1 · U3(Realtime 쪽은 U4가 대신 호출) · U4 · U5 · U6 · U7
      deleteByGuardian: { in: { guardianId: uuid, deletionRequestId: uuid }, out: { deleted: { entity: count } }, rule: "멱등. 두 번 불러도 같은 결과. 파일 포함" }
    PracticeStart:          # 제공 U5 → U4 ActivityCoordinator 확장 지점
      buildPackage: { in: { guardianId: uuid, goalSelectionIds: [uuid] }, out: { goals: [ { goalSelectionId: uuid, order: int, displayName: string, behaviorKey: string, behaviorVersion: int, judgmentCriteria: string } ], firstScenario: Scenario }, errors: [SCENARIO_GEN_FAILED] }
    PracticeRunner:         # 제공 U5 → U4 SessionLifecycle 확장 지점(Realtime)
      start: { in: { sessionId: uuid, goals: list }, events_out: [GoalStarted, StageChanged, GoalFinished, PracticeFinished] }
      stop: { in: { sessionId: uuid, reason: string } }
    CandidateRegistry:      # 제공 U5 → 소비 U6
      register: { in: { sessionId: uuid, childId: uuid, candidates: [ { behaviorKey: string, behaviorVersion: int, reasonText: string, evidence: [ { utteranceSeq: int, kind: string } ] } ] }, out: [ recommendationId ] }
      viewForSession: { in: { sessionId: uuid, guardianId: uuid }, out: [ { recommendationId: uuid, displayName: string, reasonText: string, selected: boolean } ] }
    ScenarioLookup:         # 제공 U5 PracticeGoal → 소비 U6(기록용 재판정 입력)
      get: { in: { scenarioIds: [uuid] }, out: [ { scenarioId: uuid, goalSelectionId: uuid, behaviorKey: string, behaviorVersion: int, situation: string, partnerUtterance: string, triggerScript: string } ] }
    BehaviorDefinitionLookup: # 제공 U5 PragmaticsKnowledge → 소비 U6(기록용 재판정 · 리포트 7항목)
      get: { in: { behaviorKey: string, behaviorVersion: int }, out: { displayName: string, coreFunction: string, judgmentCriteria: string } }
    JobRunner:              # 제공 U6 ResultPipeline → 소비 U6 Report(처리기 등록)
      register: { in: { jobType: [SESSION_RESULT, REPORT], handler: "handle(job) → DONE | FAILED(code)" } }
      submit: { in: { jobType: string, subjectId: uuid, idempotencyKey: string }, out: { jobId: uuid, status: REQUESTED } }
  in-app-events:
    ActivityRecorded:       # U4 ActivityRecord 발행 → U5 PracticeGoal · U6 ResultPipeline 구독(같은 앱 안, 커밋 뒤 전달)
      payload: { sessionId: uuid, guardianId: uuid, childId: uuid, activityType: string, finalStatus: string, recordedAt: date-time }
  database:                 # C13 — U1 실행 골격이 만든다
    instance: PostgreSQL 1대
    schemas:
      core: { owner_role: core_app, writes: "Core 컴포넌트만", realtime_app: "권한 없음" }
      realtime: { owner_role: realtime_app, writes: "Realtime 컴포넌트만", core_app: "권한 없음" }
    migrations: { tool: Flyway, applied_by: "배포 단계의 별도 마이그레이션 역할", apps: "validate만" }
    cross_references: "다른 스키마를 가리키는 컬럼은 DB FK 없는 논리 참조(UUID)"
```

## 9. AI 입출력 계약 뼈대 (C14, WBS 1.7.3)

형식만 고정한다. 판정 기준 본문 · 프롬프트 · 금지 표현 사전 내용 · 대기 문구는 D 담당 AI 입출력 계약 문서가 채운다(OQ4 · OQ14 · OQ15).

| 항목 | 형식 | 쓰는 곳 |
|---|---|---|
| 연습 단계 enum | SCENARIO_INTRO · FIRST_RESPONSE · HELP_OFFER · CUE · AFTER_CUE_RESPONSE · NEW_SCENARIO_RESPONSE | realtime_session_goal.current_stage · 12 연습 머리 |
| 시도 · 반응 종류 | attempt_type: ORIGINAL · NEW_SCENARIO / response_type: FIRST · AFTER_CUE · NEW_SCENARIO | realtime_practice_attempt · response |
| 판정 출력 | `{ "label": "functional"\|"partial"\|"none"\|"uncertain", "reason": "...", "evidenceSpan": {"utteranceSeq": n}, "reviewNeeded": bool }`, 무반응은 판정 없이 `NOT_PERFORMED` | ResponseEvaluator → realtime_evaluation |
| Cue 브리프(Realtime 안) | `{ "situationSummary", "partnerUtterance", "focus" }` — 정답 문장 · 판정 기준 · 보호자 원문 없음 | PracticeProtocol → VoiceRelay 지시 |
| 상황 생성 출력 | 5절 `Scenario` 스키마 + `validation` | PracticeGoal(TextAiGateway) |
| 목표 후보 출력 | `{ behaviorKey, behaviorVersion, reasonText, evidence[{utteranceSeq, kind: GUARDIAN_INPUT\|CHILD_RECALL}] }` — 근거 없는 후보 금지 | ResultPipeline → CandidateRegistry |
| 경험 구조화 출력 | `{ characters[], situations[], partnerUtterances[], childReactions[], explanations[], each{text, source: GUARDIAN_INPUT\|CHILD_TRANSCRIPT, ref} }` — 의도 · 감정 필드 없음 | ResultPipeline → core_structured_experience |
| 기록용 재판정 출력 | `{ goals[{ goalSelectionId, scenes[{ sceneNo, firstResponse{utteranceSeq, quote}, afterHelpResponse{utteranceSeq, quote}, value: functional\|partial\|none\|uncertain\|null, timing: IMMEDIATE\|AFTER_HELP, rationale, notDoneReason }] }] }` — 온도 0, 보호자에게는 칩으로만 | ResultPipeline → core_scene_judgment(U6) |
| 결과 문장 출력 | `{ sentences[{ goalSelectionId?, text, citations[{utteranceSeq}] }] }` — 맞지 않으면 고정 템플릿 | ResultPipeline → core_result(U6) |
| 리포트 항목 출력 | `{ sections[{ sectionNo: 2\|5\|6\|10, text, missing, citations[{activityNo, utteranceSeq, quote}] }] }` — 나머지 항목은 서버 집계 | Report → core_report_section(U6) |
| 금지 표현 2차 분류 출력 | `{ violation: bool, category, span }` — 인용 부호 안 아이 전사는 금지 아님 | TextAiGateway(U6) |
| 금지 표현 사전 항목 | `{ id, pattern, category: DIAGNOSIS\|TREATMENT\|SCORE\|CAUSE\|GENERALIZATION\|EMOTION_CLAIM\|PRESSURE, appliesTo: [OUTPUT_GATE, RESULT_TEXT, PDF, APPROVED_CONTENT] }` | OutputGate · TextAiGateway · Report |

## 10. 계약 소유 규칙

- 스펙 파일은 저장소 한 곳(`contracts/`)에 둔다: `parent-api.yaml`(3절) · `device-api.yaml`(4절) · `internal-api.yaml`(5절) · `events.yaml`(6절) · `timing.md`(7절) · `ports.md`(8절) · `ai-io.md`(9절). 위치 이름은 Code Generation에서 확정한다.
- 1절 '소유' 유닛 담당이 스펙을 고친다. 소비 유닛 담당은 리뷰어다.
- **더하기만 안전:** 새 필드 · 새 enum 값 추가는 바로 머지할 수 있다. 받는 쪽은 모르는 필드를 무시하고, 모르는 enum 값은 '알 수 없음'으로 처리한다(오류로 끊지 않는다).
- **깨는 변경:** 필드 삭제 · 이름 변경 · 의미 변경 · 필수화는 제공 · 소비 담당이 함께 고치고 같은 날 양쪽 코드를 머지한다(경로 버전 없음, J25). 이벤트는 `schemaVersion`을 올린다.
- **계약 시험:** 제공 쪽은 스펙대로 응답하는지, 소비 쪽은 스펙 예시로 동작하는지 각자 시험한다(Build and Test). 2절 '노출 금지 필드'는 응답 스키마 시험으로 막는다.

## 11. 열린 질문

| 계약 | 질문 | 막는 유닛 |
|---|---|---|
| C7 | GPT-Live 실제 오디오 형식(샘플링 · 프레임)이 J27과 다르면 변환 위치(VoiceRelay)와 지연 측정 | U1 · U3 |
| C14 | 판정 기준 본문 · 프롬프트 · 대기 문구 · 금지 표현 사전 v1 내용(D 담당 문서) | U3 · U5 · U6 |
| C1 | 약관 본문 · 정책 버전 1.0 문장(OQ7, A 담당 문서) | U2 |
| C5 | PDF 라이브러리 · 한글 글꼴 확정(OQ6 → 3.3 · 3.4) | U6 |
| C5 · C14 | 리포트 · 결과 문장 생성 모델과 60초 안 생성 측정(활동 20건, NFR8) | U6 |
| C6 | VAPID 키 보관 위치(환경 변수) · 푸시 전달 시험 기기 | U7 |
| C7 · C8 | 승인 문구 음성 파일을 무엇으로 만드나(사전 녹음 · TTS), 상황 대본(SCENARIO_SCRIPT) 음성을 GPT-Live가 대본 그대로 말하게 할지 TTS로 만들지 — 기기 계약(OUTPUT_BEGIN · PLAY_APPROVED_CONTENT)은 어느 쪽이든 같다 | U2 · U5 |
| C7 | 기기 로컬 끼어들기 감지가 자기 재생음(에코)에 속지 않는지 — 크롬 에코 제거가 Web Audio 재생에도 듣는지 POC로 확인. 안 되면 서버 감지(GPT-Live 발화 시작 이벤트)로 바꾸고 0.5초를 다시 잰다 | U1 · U3 |
| C7 | 진행 중 「끄기」(close 1000 DEVICE_OFF)를 바로 끝낼지(ABORTED · DISCONNECT) 40초 끊김 규칙을 따를지 | U4 |
| C7 | 끊김 때 기기에 미리 받아 둔 고정 안내를 틀지(FR-E11 '결정 필요') — 화면 설계에 없어 이번 계약에서 뺐다 | U3 · U4 |
| T | 11/2 측정 뒤 시간값 · 지연 목표 조정(NFR5 · NFR7) | 모든 유닛 |

## 12. 준하 제안(가정)

사람 지시(2026-10-09)에 따라 팀장(준하)의 대리가 `docs/cloud_handoff.md` 2절 규칙으로 정한 값이다. 질문 · 선택지 · 까닭은 `contract-design-questions.md`에 있다.

| 번호 | 준하 제안(가정) | 근거 | 확정 단계 |
|---|---|---|---|
| J20 | 계약은 다섯 묶음(보호자 REST · 아이 기기 REST + WSS · 내부 HTTP · 이벤트 6종 · 앱 안 포트) + 한 곳에 모은 시간 계약 + AI 입출력 뼈대(형식만) | Q1 · 점검표 3절 | Code Generation(`contracts/` 파일) |
| J21 | 보호자 인증 = `POST /api/auth/google {code}` 서버 교환 → Spring Session JDBC 쿠키 `MUJOONG_SESSION`(HttpOnly · Secure · SameSite=Lax · 비활성 30일 갱신형) + 상태 변경 요청의 `X-CSRF-TOKEN`. 남의 자원은 404 | Q2 · 개발 명세 S-01 · ADR-008 · A1 · NFR9 | NFR Design(3.3) |
| J22 | 등록 코드 = 8자(2~9와 I · L · O를 뺀 A~Z, 31글자) · 24시간 · 2초 확인. 기기 토큰은 만료 없음 · 해시만 저장. 같은 기기의 새 연결이 옛 연결을 대체(4001) | Q3 · B4 · OQ13 | Functional Design(U1) |
| J23 | 12 진행 조회 = `GET /api/sessions/{id}?sinceSeq=n` 증분 + `nextSeq`. 확정 전 조각은 같은 seq로 다시 온다 | Q4 · A2 · 점검표 9 | Functional Design(U4) |
| J24 | 오류 봉투 `{code, message, traceId}`. 시작 거절 코드 5종은 stories AC3.1.6 · mockups 9-12 이름(개발 명세의 SESSION_EXISTS · DEVICE_OFFLINE 대신) | Q5 · 규칙 1 | Functional Design(U4 · U5) |
| J25 | 경로 버전 없음 · 더하기만 안전 · 깨는 변경은 제공 · 소비 담당이 같은 날 양쪽 머지 · 이벤트는 `schemaVersion` | Q6 · org.md 트렁크 기반 | Delivery Planning |
| J26 | 내부 HTTP = Docker 내부망 + `X-Internal-Token`(사용자 권한 아님), Nginx가 `/internal/**` 차단. 시간 제한 시작 3초 · 끝내기 2초 · 진행 조회 1초 · 토큰 확인 1초 · 상태 보고 1초 · 다음 목표 상황 15초. 이벤트 재시도 1~30초 간격 20회 | Q7 · ADR-009 · ADR-010 · ADR-006 | NFR Design(3.3) |
| J27 | 기기 음성 = PCM16LE · 모노 · 24kHz, 바이너리 프레임(종류 1바이트 + 순번 4바이트 + 본문), 입력 20ms. 제어 = JSON 봉투(type · seq · sentAt · opId · sessionId · payload). heartbeat 5초 | Q8 · ADR-004 B안 · NFR5(다섯 문서에 값 없음) | NFR Design(3.3) · 11/2 측정 |
| J28 | 연습 단계 6개 · 반응 3종 · 판정 라벨 4개 + NOT_PERFORMED · 판정 출력 JSON 4필드 | Q9 · FR4.2 · D-05 흐름 | Functional Design(U5) · D 담당 AI 입출력 문서 |
| J29 | 기기 비밀은 HttpOnly 쿠키로만 다룬다(`MUJOONG_DEVICE_BOOTSTRAP` 24시간 · `MUJOONG_DEVICE` 400일 갱신형). WSS는 Handshake에서 쿠키 + 허용 Origin을 확인하고 URL · HELLO에는 토큰을 넣지 않는다. claim은 첫 연결 전까지 다시 부를 수 있고 부를 때마다 새 토큰. 남용 제한: 코드 발급 IP당 1시간 20회, 05a 실패 보호자당 10분 10회 → 429 | Q10 · ADR-008(장기 인증을 JS 저장소에 두지 않음) · D0 "브라우저 데이터 삭제 → 새 코드" · NFR9 | NFR Design(3.3) |
| J30 | WSS 메시지는 ADR protocol 후보 이름을 그대로 쓰고 빈 이름만 새로 붙인다(HELLO · HELLO_OK · START_SESSION · OUTPUT_BEGIN · OUTPUT_END · PLAYBACK_MARK · DEVICE_STATE · HEARTBEAT_ACK · ERROR). 승인 문구 음성은 개인정보 없는 정적 파일(audioUrl)로 START_SESSION 때 미리 받고, 개인 정보가 들 수 있는 상황 대본은 WSS 음성 묶음(source SCENARIO_SCRIPT)으로만 보낸다. T19 '말하는 중'은 기기의 실제 재생으로, 나머지 표시는 SESSION_STATE로 | Q11 · ADR protocol 표 · D-19 상태 · AC6.5.1 | Functional Design(U3 · U5) |
| J31 | 끼어들기는 기기 로컬 음성 활동 감지(200ms)로 바로 멈춘다(뜻 판정 아님). 끊기면 기기가 재생 · 대기열을 버리고 1 · 2 · 4초 뒤 5초 간격으로 재접속, HELLO resume으로 40초 안이면 이어가되 끊기기 전 출력은 잇지 않는다. 대기 중에는 음성을 보내지 않는다. 4001이면 자동 재접속 안 함. 대기열 상한 20초. 「끄기」는 close 1000 DEVICE_OFF | Q12 · TS-N04 · FR-E11 · D-19 분기 | NFR Design(3.3) · POC(에코 제거 확인) |
