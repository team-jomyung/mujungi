# 계약 편차 목록 (deviations.md)

> 승인된 `contract-summary.md`(2.8)는 고치지 않는다. 구현이 승인 계약보다 넓어지거나 구체화되는 곳을 여기에 한 행씩 적고,
> 각 스펙 파일에는 `x-deviation: ["(a)"]`(YAML · JSON) 또는 `[편차 (a)]`(Markdown)로 표시한다(사람 결정 5 · J59 · J112 · J130).
> (a) ~ (t)는 `docs/cloud_run_report.md` 2.1절, (u) ~ (y)는 3.5 계획 J112(사람 확인)에서 왔다.
> 모든 항목은 **더하기만 안전**(계약 10절 · J25)이다: 필드 삭제 · 이름 변경 · 필수화가 없고, 받는 쪽은 모르는 필드 · enum · code를
> 무시하거나 '알 수 없음'으로 처리한다. `ContractFilesTest`가 (a) ~ (y) 각각이 이 표에 한 행, 다른 계약 파일에 한 번 이상 표시돼 있음을 단언한다.
> (z)는 3.6 수정 라운드의 독립 리뷰 F1(J285 ~ J288)에서 왔고 `ContractFilesTest`가 같은 방식으로 단언한다.
> (z) · (aa)와 U4b 표의 (U4b-a)는 U11이 실호출 관측 형식으로 바꿔 **(U11-a)로 대체**됐다. 행은 지우지 않고 대체 표시만 붙였다(`ContractFilesTest`가 표시 위치를 계속 대조한다).
> (aa) ~ (ac)는 U3 음성 대화(`docs/units/u3/design.md` 4절 · U3-J21)에서 왔다. 한 글자 기호가 (z)에서 다 차서 두 글자 기호를 쓰고 `ContractFilesTest`가 같은 방식으로 단언한다.

## 호환 분류

- **새 응답**: 기존 경로에 새 상태 코드 · 오류 code를 더함. 소비자는 모르는 code를 일반 오류로 처리한다.
- **새 선택 필드**: 기존 본문에 선택 필드를 더함. 소비자는 모르는 필드를 무시한다.
- **새 경로**: 기존 경로를 바꾸지 않고 경로를 더함.
- **새 값**: 기존 enum에 값을 더함.
- **규칙 구체화**: 메시지 · 본문 모양은 그대로이고 계약 문장의 판정 기준을 좁히거나 구체화함.
- **해석 명시**: 계약 문장의 뜻을 명시함(코드 · 모양 변화 없음).

## 편차 표

| 기호 | 내용 | 출처 | 제공 | 소비자 | 호환 분류 | 표시 위치 |
|---|---|---|---|---|---|---|
| (a) | `POST /api/device/bootstrap` 409 `ALREADY_REGISTERED` — bootstrap 행이 이미 REGISTERED(claim 전)면 기기 웹을 claim으로 보낸다 | J59 · BR1.3 | Core DeviceRegistry | DeviceWeb | 새 응답 | device-api.yaml · errors.md |
| (b) | `POST /api/device/bootstrap/claim` 401 `BOOTSTRAP_NOT_FOUND` — 쿠키 없음 · 모르는 비밀 | J59 · BR3.1 | Core DeviceRegistry | DeviceWeb | 새 응답 | device-api.yaml · errors.md |
| (c) | `POST /api/device/bootstrap` 503 `CODE_ISSUE_FAILED` — 코드 추첨 5회 모두 겹침 | J59 · BR1.2 | Core DeviceRegistry | DeviceWeb | 새 응답 | device-api.yaml · errors.md · timing.md |
| (d) | 400 `VALIDATION_FAILED`(register · activities/free). 등록 코드는 정규화 뒤 영숫자 8자가 아니면 400, 발급 알파벳 밖 글자는 404 `CODE_NOT_FOUND` | J59 · BR2.1 · BR6.4 | Core | ParentWeb | 새 응답 | parent-api.yaml · errors.md |
| (e) | 403 `CSRF_TOKEN_INVALID` — 상태 변경 요청의 CSRF 토큰 · Origin 불일치 | J59 · BR8.8 · SD-6 | Core | ParentWeb | 새 응답 | parent-api.yaml · errors.md |
| (f) | 내부 끝내기 reason `CORE_ABANDONED` — Core가 포기한 세션의 정리 요청(requestId = sessionId), 응답 202 `{}` | J54 · J59 · BR6.7 | Realtime SessionLifecycle | Core ActivityCoordinator | 새 값 | internal-api.yaml |
| (g) | 계약 6절 순서 문장 `since` → `occurredAt` — 기기 이벤트는 봉투 occurredAt(Inbox occurred_at)으로 최신만 반영한다 | J59 · BR4.1 | Realtime | Core DeviceRegistry | 규칙 구체화 | events.asyncapi.yaml |
| (h) | `session.device-ack-timeout`(20초)을 PROVIDER_UNAVAILABLE에도 적용 — ACK · DEVICE_READY는 왔는데 `session.started`가 없을 때 | J59 · BR6.12 ② | Realtime | Core(사본) · ParentWeb(12) | 규칙 구체화 | timing.md · device-ws.asyncapi.yaml |
| (i) | `PUT /internal/devices/{id}/connection`의 `state` · `reportedAt` 필수화, 누락은 400 `INVALID_ENVELOPE` | J59 · BR4.1 | Core DeviceRegistry | Realtime DeviceGateway | 새 응답(보내는 쪽은 처음부터 두 필드를 보냄) | internal-api.yaml · errors.md |
| (j) | verify-token 결과를 캐시하지 않음 — 연결마다 Core에 확인(계약 5절 '결과는 Realtime이 캐시' 대체) | J59 · BR3.4 | Core DeviceRegistry | Realtime DeviceGateway | 규칙 구체화 | internal-api.yaml · device-ws.asyncapi.yaml |
| (k) | `bootstrap.rate-limit`은 같은 쿠키의 반복 요청을 세지 않음(코드 발급 남용만 막음) | J59 · BR1.6 | Core | DeviceWeb | 규칙 구체화 | device-api.yaml · errors.md · timing.md |
| (l) | 기기 연결 대체 시점을 '인증'이 아니라 HELLO_OK로 구체화 — HELLO_OK 때 옛 연결 4001 | J59 · BR3.7 | Realtime DeviceGateway | DeviceWeb | 규칙 구체화 | device-ws.asyncapi.yaml |
| (m) | register에서 같은 보호자의 재시도는 409 대신 첫 성공과 같은 201 | J59 · BR2.1 ② | Core DeviceRegistry | ParentWeb | 규칙 구체화 | parent-api.yaml |
| (n) | 503 `SERVICE_NOT_READY` + `Retry-After` — 앱 NOT_READY · DRAINING. 새 WSS는 업그레이드 뒤 1013 | reliability-design ## 5 · RD-16 · J124 | Core · Realtime | ParentWeb · DeviceWeb · 상대 앱 Outbox | 새 응답 | parent-api.yaml · device-api.yaml · internal-api.yaml · device-ws.asyncapi.yaml · errors.md · timing.md |
| (o) | 500 `INTERNAL_ERROR` — 처리하지 못한 오류의 봉투(스택 · 예외 메시지 없음) | 3.3 · SD-11 | Core · Realtime | 모든 소비자 | 새 응답 | parent-api.yaml · device-api.yaml · internal-api.yaml · errors.md |
| (p) | 이벤트 봉투 선택 필드 `traceId`(32자 소문자 16진수), Outbox/Inbox 네 표에 `trace_id` 열 | 3.3 · OD-7 · A3 | Core · Realtime | Core · Realtime | 새 선택 필드 | events.asyncapi.yaml · internal-api.yaml |
| (q) | DeviceStatusChanged 선택 필드 `guardianId`(Inbox 행 guardian_id 원천) | 3.3 · A4 | Realtime DeviceGateway | Core DeviceRegistry | 새 선택 필드 | events.asyncapi.yaml |
| (r) | REST 본문 64KB 초과는 413 대신 400 `VALIDATION_FAILED`(앱 · Nginx `error_page 413 =400`) | 3.3 · SD-3 · SD-11 | Core · Nginx | ParentWeb · DeviceWeb | 새 응답 | parent-api.yaml · device-api.yaml · errors.md · timing.md |
| (s) | WSS 규정 외 프레임은 ERROR 대신 4400 닫기(범위 J123: 크기 초과 · 965바이트가 아닌 0x01 · 서버가 받은 0x02 · HELLO 형식 오류 · 방향 위반). READY 뒤 모르는 type · 틀린 payload는 ERROR 유지 | 3.3 · J123 | Realtime DeviceGateway | DeviceWeb | 규칙 구체화 | device-ws.asyncapi.yaml · timing.md |
| (t) | 공개 `GET /api/health` → 200 `{"status":"UP"}`(외부 프로브용, 요청마다 DB 미호출) | 3.3 · OD-11 | Core | 외부 프로브 · Nginx | 새 경로 | parent-api.yaml |
| (u) | `GET /api/session/csrf` → `{token, headerName}`, no-store, 익명 401 | J75 · SD-6 · J112 | Core | ParentWeb | 새 경로 | parent-api.yaml |
| (v) | Realtime `POST /internal/sessions` 409 `SESSION_NOT_ACTIVE` — Core가 취소한 sessionId의 늦은 시작(10분 묘비) | BR6.6 · J112 | Realtime SessionLifecycle | Core ActivityCoordinator | 새 응답 | internal-api.yaml · errors.md · timing.md |
| (w) | Realtime이 모르는 sessionId의 `GUARDIAN_STOP` 끝내기 → 409 `SESSION_NOT_ACTIVE`(묘비 없음, 새 code 없음). Core는 그대로 409, 12는 상태를 다시 읽는다 | V-12 · J111 · J112 | Realtime SessionLifecycle | Core ActivityCoordinator · ParentWeb | 규칙 구체화 | internal-api.yaml · parent-api.yaml · errors.md |
| (x) | 계약 8절 C13 `owner_role: core_app`은 '쓰는 역할'로 읽고 표 · 스키마 소유는 `mujoong_owner`(앱 역할은 DDL 없음) | SD-13 · J112 | U1 실행 골격(DB 초기화) | 모든 유닛 | 해석 명시 | README.md · infra/postgres/init/01-roles.sql |
| (y) | Nginx가 만든 502 · 504 JSON 봉투의 code `UPSTREAM_UNAVAILABLE`(상태 코드는 유지, 앱이 낸 503은 통과) | R4-17 · CD-2 · J112 | Nginx | ParentWeb · DeviceWeb · 외부 프로브 | 새 응답 | errors.md |
| (z) | **(U11-a)로 대체** — 공급자 `session.output.done`(responseId)을 그 응답의 전사 완료 신호로 쓴다 — 출력 게이트는 한 응답의 첫 조각부터 이 신호까지를 모두 담은 구간만 전사-음성 대응 근거로 보고, 신호가 없는 구간 · 앞부분이 다른 구간으로 떨어져 나간 구간 · 신호 뒤 조각이 붙은 구간 · 정규화 뒤 빈 전사를 GATE_EMPTY로 폐기한다(fail-closed, 실공급자 어댑터는 완결된 응답에만 이 신호를 보낸다). 한 구간에는 한 응답의 조각만 담고 다른 응답의 조각이 오면 열린 구간을 그 자리에서 확정해 게이트에 넣는다(BR7.3 확정 조건에 응답 경계를 더함). `responseId` 없는 출력 조각은 버린다 | 3.6 리뷰 F1 · SD-17 3단계 · BR7.4 (가) · J285 ~ J288 | 공급자(가짜 공급자 · 11/2 실공급자 어댑터, V-2) | Realtime OutputGate | 규칙 구체화 | ai-io.md |
| (aa) | **(U11-a)로 대체** — 공급자 선 형식(ai-io.md 3절)에 서버 → 공급자 type 둘 — `session.response.cancel {responseId?}`(끼어들기 · 아이 종료 의도 때 만들던 · 재생하던 응답 취소)와 `session.instructions.correct {text}`(게이트 폐기 뒤 응답당 한 번 지시 교정, 서버 고정 문장) — 과 공급자 → 서버 `session.input_transcript.done`의 선택 필드 `confidence`(0 ~ 1, 불확실 표시 근거)를 더함. 모르는 type · 필드는 무시된다 | U3 design 4절 · U3-R5 · R7 · R10 · U3-J11 · J12 · J17 | Realtime VoiceRelay · 공급자(가짜 공급자, 실공급자 어댑터 V-2) | 공급자 · Realtime OutputGate · ConversationLog | 새 값 · 새 선택 필드 | ai-io.md |
| (ab) | 다시 붙이기 구체화 — HELLO_OK 뒤 같은 기기의 진행 중 세션이 있으면 새 연결을 그 세션에 묶고 HELLO_OK.session(sessionId · view · resumed)을 채운다. HELLO resume.sessionId가 그 세션이면 resumed true(START_SESSION 재전송 없음), 아니면 resumed false로 답하고 같은 approvedAudio로 START_SESSION을 다시 보낸다(CLOSING이면 START_SESSION 대신 CLOSE_TRANSPORT). ACK를 못 받은 opId 명령은 같은 opId로 새 연결에 다시 보내되 STOP_OUTPUT은 빼고 PLAY_APPROVED_CONTENT는 가장 최근 하나만 보내며, 기기는 같은 opId를 두 번 실행하지 않는다. 끊기기 전 출력은 잇지 않는다(새 playbackId). 그 사이 세션이 끝났으면 그 연결에 STANDBY(이어간 기기에는 CLOSE_TRANSPORT도)를 보내고 기기는 지금 세션이 아닌 세션의 STANDBY를 무시한다 | U3 design 4절 · U3-R3 · R4 · U3-J5 · J7 · J8 · AC6.2.10 | Realtime DeviceGateway · SessionExecutor | DeviceWeb | 규칙 구체화 | device-ws.asyncapi.yaml |
| (ac) | 계약 7절 예약 키 `device.unstable-disconnects`("5분 안 2회")를 Duration 하나에 담을 수 없어 `mujoong.timing.device.unstable-disconnect-window`(5m, timing.md 1절)와 `mujoong.device.unstable-disconnect-count`(2, 4절) 두 키로 나눔. 연결 보고 · DeviceStatusChanged의 `recentDisconnects`는 이 창 안 끊김 횟수다. 예약 키 `device.reconnect-backoff` · `device.playback-mark-interval` · `device.unstable-rtt` · `gate.barge-in-stop` · `gate.local-voice-min`은 값 그대로 1절로 옮김 | U3 design 4절 · U3-R1 · U3-J3 | Realtime DeviceGateway · DeviceWeb | Core DeviceRegistry · 운영 설정 | 규칙 구체화 | timing.md |

## U2 편차

> U2(보호자 계정)가 승인 계약 3절 · 2절에 더하거나 구체화한 곳이다. 기호는 `(U2-<글자>)`이고 U1의 (a) ~ (z)와 섞이지 않게 따로 센다.
> YAML 스펙은 `x-u2-deviation: ["(U2-a)"]`, Markdown은 `[U2 편차 (U2-a)]`로 표시한다(U1 `ContractFilesTest`의 (a) ~ (z) 대조와 겹치지 않는다).
> 모두 더하기만 안전하거나 아직 소비자가 없던 경로의 해석이다. 근거 가정 번호는 `docs/units/u2/design.md` 8절.

| 기호 | 내용 | 출처 | 제공 | 소비자 | 호환 분류 | 표시 위치 |
|---|---|---|---|---|---|---|
| (U2-a) | 새 code `GOOGLE_AUTH_FAILED`(401) — `POST /api/auth/google`의 '401 구글 교환 실패'에 code 이름을 붙임 | 계약 3절 /auth/google · AC1.1.4 | Core GuardianIdentity | ParentWeb | 새 응답 | parent-api.yaml · errors.md |
| (U2-b) | `GET /api/policies/current`는 보호자 세션이 있어야 한다(계약은 `security: []`). 02 · 02-1은 로그인 뒤에만 열린다 | U2-J6 | Core ConsentPolicy | ParentWeb | 규칙 구체화(아직 소비자 없던 경로) | parent-api.yaml |
| (U2-c) | 약관 응답 항목 모양 `{type, title, sections[{heading, body}]}`(계약 설명의 collect · purpose … 고정 필드 대신, 항목마다 소제목이 다름) | U2-J7 · 문구표 02-1 | Core ConsentPolicy | ParentWeb | 해석 명시 | parent-api.yaml |
| (U2-d) | 계정 삭제가 접수된 보호자의 구글 로그인은 200 Me{deletionStatus: IN_PROGRESS}이고 세션 쿠키를 만들지 않는다 | U2-J5 · AC1.6.4 | Core GuardianIdentity | ParentWeb | 해석 명시 | parent-api.yaml |
| (U2-e) | `POST /api/auth/google`은 형식 오류 · redirectUri 불일치 · Origin 불일치 · 구글 설정 없음도 400이 아니라 401 GOOGLE_AUTH_FAILED다(로그인 실패는 한 가지로만 알린다) | U2-J1 · AC1.1.4 | Core GuardianIdentity | ParentWeb | 규칙 구체화 | parent-api.yaml |
| (U2-f) | `PUT /api/consents`의 policyVersion이 현재 정책 버전이 아니면 400 VALIDATION_FAILED(02는 약관을 다시 읽는다) | R6 | Core ConsentPolicy | ParentWeb | 규칙 구체화 | parent-api.yaml |
| (U2-g) | 동의 상태 OUTDATED = 현재 버전 4항목 GIVEN은 없고, 예전 정책 버전 하나에서 네 항목의 항목별 최신 행이 모두 GIVEN(그 버전의 철회가 뒤에 쌓였거나 일부 항목만 있으면 NOT_GIVEN, U2 리뷰 7). 화면은 NOT_GIVEN과 같은 02 재동의이고 앱 시작(04)에서도 02로 보낸다(U2 리뷰 6) | U2-J8 | Core ConsentPolicy | ParentWeb | 해석 명시 | parent-api.yaml |
| (U2-h) | `DELETE /api/me` 202 본문 `{deletionRequestId}`, 같은 보호자의 재요청은 같은 id, 응답에 세션 쿠키 삭제(Max-Age=0) | AC1.6.1 · AC1.6.7 | Core AccountDeletion | ParentWeb | 해석 명시 | parent-api.yaml |
| (U2-i) | `PUT /api/child/stop-expressions` 본문 `{items[{text}]}`(순서 = sortOrder), 기본 표현과 같은 표현도 중복으로 400. 공백은 유니코드 White_Space 속성, 글자 수는 NFC 뒤 코드 포인트(화면과 같은 규칙, U2 리뷰 8) | R9 · U2-J11 | Core ChildProfile | ParentWeb | 해석 명시 | parent-api.yaml |
| (U2-j) | 로그 사건 표(OD-2) 더하기: `SECURITY_AUTH_FAILED`의 `kind`에 구글 로그인 실패 까닭 6종(`GOOGLE_NOT_CONFIGURED` · `GOOGLE_BAD_REQUEST` · `GOOGLE_REDIRECT_MISMATCH` · `GOOGLE_EXCHANGE_FAILED` · `GOOGLE_KEYS_UNAVAILABLE` · `GOOGLE_TOKEN_INVALID`)을 더하고, `POST /api/auth/google`의 Origin 불일치는 `SECURITY_ORIGIN_DENIED`(channel REST · statusCode 401)로 남긴다. attrs 키는 그대로이고 값 집합만 늘었다. 응답은 그대로 401 GOOGLE_AUTH_FAILED 하나 | U2 리뷰 4 · OD-2 · OD-13 | Core GuardianIdentity | 운영(로그 · 지표 `auth_failed` · `origin_denied`) | 더하기(새 열거값) | contract-model AttrKey.KIND |
| (U2-k) | U1 `POST /api/dev/login`(dev 전용)은 시드 보호자의 계정 삭제가 접수됐으면 403 ACCOUNT_DELETION_IN_PROGRESS이고 세션을 만들지 않는다 | U2 2차 리뷰 2 · AC1.6.4 | Core GuardianIdentity(시험 로그인) | dev 화면 · 시험 | 새 응답(dev 전용) | parent-api.yaml |

## U4a 편차

> U4a(활동 세션 Core 쪽)가 승인 계약 3절에 더하거나 구체화한 곳이다. 기호는 `(U4a-<글자>)`이고 YAML 스펙은 `x-u4a-deviation: ["(U4a-a)"]`로
> 표시한다(U1 · U2 기호와 섞이지 않는다). 모두 더하기만 안전하거나 아직 소비자가 없던 경로의 해석이다. 근거 가정 번호는 `docs/units/u4a/design.md` 8절.
> 세 경로(`/sessions/current` · `/records` · `/records/{sessionId}`)는 계약 3절 그대로 parent-api.yaml에 더했다(편차 아님).

| 기호 | 내용 | 출처 | 제공 | 소비자 | 호환 분류 | 표시 위치 |
|---|---|---|---|---|---|---|
| (U4a-a) | `GET /api/sessions/current`는 늘 `{session: SessionSummary \| null}`로 감싼다(계약 설명 'SessionSummary 또는 {session: null}'의 두 모양을 하나로) | U4a-J4 · AC1.4.13 · AC1.7.4 | Core ActivityCoordinator | ParentWeb(04) · U7 알림 진입 | 해석 명시(아직 소비자 없던 경로) | parent-api.yaml |
| (U4a-b) | `GET /api/records?date=` 줄에 `goalCount`(연습 목표 수, 자유대화 0) · `interrupted`(DISCONNECT 구간이 있음)를 더함 — 26 줄 글 "연습 · 목표 n개" · "중간에 끊김"(AC5.1.2)에 필요 | U4a-J10 · AC5.1.2 | Core ActivityRecord | ParentWeb(26, U6) | 새 선택 필드 | parent-api.yaml |

## U4b 편차

> U4b(활동 세션 Realtime 쪽 — 참여 확인 · 끝내기 · 정산 · 계정 삭제)가 승인 계약 3 · 6 · 9절에 더하거나 구체화한 곳이다. 기호는 `(U4b-<글자>)`이고
> YAML 스펙은 `x-u4b-deviation: ["(U4b-a)"]`, Markdown은 `[U4b 편차 (U4b-a)]`로 표시한다(U1 · U2 · U4a 기호와 섞이지 않는다). `ContractFilesTest`는
> 한 · 두 글자 기호만 단언하므로 유닛 접두 기호는 사람이 맞춘다(U4b-J23). 모두 더하기만 안전하거나 아직 소비자가 없던 경로의 해석이다. 근거 가정
> 번호는 `docs/units/u4b/design.md` 8 · 10절.

| 기호 | 내용 | 출처 | 제공 | 소비자 | 호환 분류 | 표시 위치 |
|---|---|---|---|---|---|---|
| (U4b-a) | **(U11-a)로 대체** — 공급자 선 형식(ai-io.md 3절)에 서버 → 공급자 type `session.response.create`(필드 없음)를 더함 — 참여 확인(WAITING_CHILD → ACTIVE) 뒤 첫 응답을 한 번 만들게 한다. 참여 전에는 응답을 만들지 않는다. 연습 확장점의 공급자 지시는 새 type 없이 기존 `session.instructions.correct {text}`를 서버 검증 문장(`PracticeInstruction`)으로 쓴다. 모르는 type은 무시된다 | U4b-J3 · BR2.1 · BR10.4 | Realtime VoiceRelay · 공급자(가짜 공급자는 받은 사실만 기록) | 공급자 | 새 값 | ai-io.md |
| (U4b-b) | 내부 끝내기 응답표 구체화 — WAITING_CHILD도 끝낼 수 있는 상태다(GUARDIAN_STOP 202 {status: CLOSING}, CORE_ABANDONED는 실행기 없으면 FAILED + 202 {}). reason `CONSENT_WITHDRAWN`: 시작 단계(PREPARING · DEVICE_ACK)는 FAILED(endReason CONSENT_WITHDRAWN, failureCode 없음) + 202 {}, WAITING_CHILD · ACTIVE는 CLOSING(endReason CONSENT_WITHDRAWN) + 202 {status: CLOSING}, CLOSING은 202 {status: CLOSING}(사유는 처음 것), 종료 · 모르는 id는 409 SESSION_NOT_ACTIVE | U4b-J9 · BR1.5 · BR1.9 · FR1.4 | Realtime SessionLifecycle | Core ActivityCoordinator · ConsentPolicy | 규칙 구체화 · 새 값 | internal-api.yaml |
| (U4b-c) | SessionStateChanged는 CLOSING에서 endReason(마무리 사유)을 싣는다(ENDED는 그대로, ENDED_UNCONFIRMED는 UNCONFIRMED). 연습 마지막 목표 끝은 endReason CHILD_STOP이고 세부 사유 `PRACTICE_COMPLETED`는 세션 행 end_detail에만 남긴다. SessionRecordCaptured.record 모양을 계약 6절 SessionRecord(utterances · gaps · recordScope · practice · audio)로 정하고, snapshotHash는 키 정렬 · 공백 없는 JSON에서 null 값의 키를 남겨 JSON null로 쓴 바이트의 sha256이다. DeletionCompleted.counts는 sessions · transcripts · audioFiles · practiceRecords(없는 참가자는 0) | U4b-J7 · J16 · J17 · BR1.1 · BR8.4 · BR9.4 | Realtime SessionLifecycle · RecordCapture · 삭제 처리기 | Core ActivityRecord · AccountDeletion · U7 | 규칙 구체화 | events.asyncapi.yaml |
| (U4b-d) | SessionRecord.practice는 객체 또는 null이다. 계약 6절 "DECLINED면 빈 배열"은 utterances · audio에만 적용하고, practice는 연습 확장점이 값을 주지 않으면(자유대화 · DECLINED · ACTIVE 전 종료 · U5b 전 PRACTICE) null이다 | FD-Q4 · BR8.3 | Realtime RecordCapture | Core ActivityRecord · U6 | 해석 명시 | events.asyncapi.yaml |

## U5a 추가 계약 (2026-10-12)

| 기호 | 변경 | 근거/영향 |
|---|---|---|
| U5a-a | C4 요청 recommendationId/isFavorite 명시, GoalSelection 출처·횟수 nullable 메타 추가, 중복 DELETE 시한 불연장 | 승인 C4의 생략된 본문 구체화. 알려지지 않은 메타를 0으로 꾸미지 않음. 기존 필드/경로 호환 |
| U5a-b | C10 논리 키/claim/응답 재전송, 기존 성공/내용 실패 보존, 미연결·생성 중 503, 소유/활성 확인 오류 | U5a-J5/J18. Realtime이 상황 id로 중복 재생을 막아야 함. 기존 요청/성공 필드 유지 |
| U5a-c | 소비자 소유 포트(계정은 U2 부품 어댑터), 실제 근거/세션 없는 호출 실패, 노출 기록 대조 | U2/U4/U6 소유 기능은 구현하지 않음 |
| U5a-d | 공급자 포트·최소 DTO·독립 금지 검사·기술 재시도·버전 기록, 보수적 구조/지문 검사 | U5a-J7/J16/J17. 공급자 미연결은 비활성; 의미 검증 완료로 주장하지 않음 |

## U6 편차

| 기호 | 변경 | 승인 근거 · 영향 |
|---|---|---|
| (U6-a) | 미확인 종료 제외는 종료 전 세션 제외로 해석. ENDED_UNCONFIRMED · ABORTED도 정리하되 FULL 불가 | 요구사항 Q1 A |
| (U6-b) | SERVER_RESTART FAILED이고 발화가 있으면 정리 · 26 · 27 · 28 노출. records enum FAILED 추가 | Q2 B · FR15, U4a-J9와 AC5.1.8 · AC5.3.5 일부 변경 |
| (U6-c) | 27에서 08 배경 원문 표시 제외 | Q4 B · AC5.2.2 일부 변경 |
| (U6-d) | 최초 포함 총 3회 시도, 간격 5 · 15초 | C2 |
| (U6-e) | 리포트 60초는 접수부터 서버 절대 마감, 시도 횟수보다 우선 | Q3 A · AC5.3.11 |
| (U6-f) | ai-io 설정 mujoong.ai.*를 mujoong.text-ai.result.*로 통일 | C1 |
| (U6-g) | CHILD_TRANSCRIPT를 CHILD_RECALL로 통일. GUARDIAN_INPUT 인용은 quote 없음 | C7 |
| (U6-h) | 17 · 27 정리 중, 28 만드는 중에도 2초 폴링, 끝나면 중단 | C18 · 원 A2 확장 |
| (U6-i) | 팁 선택 title 필드와 nullable core_support_item.title 추가 | C14 · S-27 |
| (U6-j) | AC3.6.12 정리 끝 READY는 작업 상태 DONE | C19 |
| (U6-k) | PDF는 메모리 렌더라 서버 파일과 삭제 단계 없음 | Q6 A · FR10.3 |
| (U6-l) | 자유대화 결과 문장은 저장 · API · 리포트만, 27 미표시 | Q5 A |

## U7 웹 푸시 계약

| 기호 | 변경 | 근거/영향 |
|---|---|---|
| (U7-a) | 세션 인증 GET `/push-subscription/vapid-public-key` → `{enabled,publicKey}`. 미설정은 false/null, 응답 no-store. private key · subject는 반환하지 않는다. | C6의 프런트 구독 준비 정보 보완 · U7-J5 |
| (U7-b) | 다른 보호자가 소유한 endpoint의 PUT은 409 `PUSH_SUBSCRIPTION_CONFLICT`, 고정 문구는 errors.md. 기존 보호자 소유를 변경하지 않는다. | C6의 endpoint 유일성과 소유권 · U7-J4 |
| (U7-c) | DELETE `/push-subscription` 본문은 `{endpoint}`. 현재 보호자의 그 기기만 해제하며 알 수 없는/타인 주소도 204다. | 사용자 확인(현재 기기만) · U7-J3 |
| (U7-d) | 푸시 클릭은 `/sessions/{id}`로 해당 세션을 재조회한다. 진행 중 12, CLOSING/종료 17, 조회 실패/UNKNOWN은 04 미확인이다. | C6의 이전 current-only 문구를 U4a review-1 4절과 U7 지시서가 정정 · AC1.7.4 |

서버 payload는 `{type,activityType,sessionId,sentAt}`만 보낸다. type은 `VOICE_PROBLEM`, `DEVICE_STATUS_UNKNOWN`, `ENDED_BY_CONNECTION`, `ACTIVITY_COMPLETED`이고 기존 이벤트 계약은 수정하지 않는다. 네 문구의 정본은 `frontend/src/shared/copy/ko.ts`다.

## U5b 통합 편차

| 기호 | 내용 | 근거 |
|---|---|---|
| (U5b-a) | POST /api/activities/practice의 준비를 REQUESTED 전에 영속화한다. 202에 goalCount를 포함하며 준비 중 SERVICE_NOT_READY는 같은 키 재시도다. 확정 실패 뒤 새 시작만 새 키를 쓴다. | U5b design 9절 I2/I5, 기존 C8/C10 및 18 |
| (U5b-b) | 실제 연습 확장은 최초 자유 응답을 막고 타입 있는 speak와 PLAYBACK_FACT를 사용한다. 상황/도움의 승인 발화와 실제 전사가 일치해야 기기로 보내며 STARTED/COMPLETED/실패를 구분한다. 기존 NoOp 확장 동작은 유지한다. | I1, FR5, 실제 노출만 기록 |
| (U5b-c) | 새 상황 생성 실패는 기존 원본을 보존한 SKIPPED(SCENARIO_FAILED/TIME_OVER) 후 다음 목표다. 판정 중 종료는 원래 시한까지 대기한다. 종료 스냅숏 실패는 원본에서 재무장하며 판정 지연 복구는 12초 성공과 구분한다. | 사용자 결정 I7/I8 |
| (U5b-d) | INTERNAL_HTTP target에 PRACTICE_SCENARIO를 추가하고 C10에 별도 연결 풀을 사용한다. URL은 기존 Core 내부 주소에 고정한다. | C10, 토큰 확인/상태 보고와 지연 격리 |
| (U5b-e) | 판정 전체 12초·최종 저장 예산 2초 및 후속 반응 20초를 U5b 타입 설정으로 등록한다. 2초는 부하 검증 전 초깃값이다. | 기존 Q6, 인수 가정 I4/I9, timing.md U5b 절 |

## U8 편차

> U8(계약 · 버전 리소스, 작업 기록 261015-doc-sync-code-update)이 계약 요약 C-DS2 · C-DS4 ~ C-DS9 · C-DS13 · C-DS14를 계약 파일로 옮기며
> 더하거나 구체화한 곳이다. 기호는 `(U8-<글자>)`이고 YAML 스펙은 `x-u8-deviation: ["(U8-a)"]`, Markdown은 `[U8 편차 (U8-a)]`로 표시한다
> (U1 `ContractFilesTest`의 한 · 두 글자 기호와 섞이지 않는다). 모두 더하기만 안전하다 — 기존 필드 · enum · 키의 이름과 뜻은 그대로다.
> 근거는 U8 Functional Design `functional-spec.md` 8절 · `rules.md` BR1 ~ BR7 · NFR Design `security-design.md` SD1 ~ SD7.

| 기호 | 내용 | 출처 | 제공 | 소비자 | 호환 분류 | 표시 위치 |
|---|---|---|---|---|---|---|
| (U8-a) | Luna · 제브 구조화 입출력 스키마 9종(`ai-judgment-request` · `ai-judgment-output` · `ai-scenario-output` · `ai-scenario-review-output` · `ai-goal-candidate-output` · `ai-rejudgment-output` · `ai-seed-output` · `guardrail-request` · `guardrail-result`)과 ai-io.md 10절 공통 규칙(금지 필드 · R-F15 · 목적별 재시도 분류 · 원문 일치와 제브 생략 · 비밀 이름). 장면 출력에 `partnerRole`을 받지 않고(7절 U5a-J22), `guardrail-result.categories`는 C §3.1의 7개 범주 이름 배열로 구체화했으며, `ai-rejudgment-output.timing`은 functional이 아니면 null이다 | C-DS4 ~ C-DS8 · BR5.1 ~ BR5.5 · SD1 · SD2 · SD6 | U8 계약(U9 · U10 · U11 · U13 · U14 · U15가 구현) | U9 ~ U15 | 새 스키마 · 규칙 구체화 | ai-io.md · schemas/ai-*.schema.json · schemas/guardrail-*.schema.json |
| (U8-b) | 세션 시작 요청 선택 속성 `approvedSpeech`(`{code: {contentVersionId, text}}`, 음성 파일 없는 승인 발화). 없으면 빈 것으로 본다. 한 code는 `approvedContent` · `approvedSpeech` 중 하나에만 있고, Realtime이 겹친 code를 받으면 `approvedSpeech` 쪽을 버리고 `APP_ERROR`(`errorKind` `APPROVED_SPEECH_DUPLICATE_CODE`)만 남긴다. 배포는 같은 릴리스, 따로면 Realtime 먼저 | BR1.2 · BR1.3 · NFR6.1 · SD7 | Core ActivityCoordinator(`StartActivityService`) | Realtime `StartSessionHandler` · `LiveContentRegistry` | 새 선택 필드 | internal-api.yaml · schemas/internal-session-start-request.schema.json · ai-io.md |
| (U8-c) | timing.md 13절 새 시간 키 7개(`judgment.live-budget` · `judgment.response-ready` · `guardrail.jev-budget` · `conversation.no-response-wait` · `conversation.focus-max` · `conversation.goal-active-limit` · `knowledge.graph-budget`). 화면이 읽지 않는 서버 키라 `frontend/src/shared/timing/timing-defaults.json`에 넣지 않는다 — 서버 키에서 R-M5 '세 곳'은 timing.md · 소유 앱 `application.yml` · 설정 레코드로 읽는다(기존 `result.*` · `text-ai.*`와 같은 처리). 계약 요약 C-DS13의 `practice.*` 세 키는 U5b 이름 공간과 섞이지 않게 `conversation.*`로, `result.retry-delays`는 기존 10절 키와 겹쳐 만들지 않는다 | BR3.1 · BR3.2 · functional-spec 8.2 | U8 계약 | U11 · U12 · U13 · U15 | 새 키 · 해석 명시 | timing.md |
| (U8-d) | `SessionRecordCaptured.record.practice` 새 연습 흐름 형식 `practice-record-v2.schema.json`(`flowVersion` 필수). `flowVersion`이 없으면 U5b 형식이고, 형식 구분에 `snapshotVersion`을 쓰지 않는다(계약 요약 C-DS2의 'snapshotVersion을 올려 구분'을 대체). 봉투 `schemaVersion`은 그대로 | BR6.1 · NFR6.2 · Contract Design 검토 R-03 | U12(채움) · U11(발행) | U14 | 새 선택 필드 · 해석 명시 | events.asyncapi.yaml · schemas/practice-record-v2.schema.json · ai-io.md |
| (U8-e) | 승인 문구 `content_type` 새 값 `APPROVED_SPEECH`(음성 파일 없이 GPT-Live 지시 + 원문 일치로 재생). 004 시드가 `HELP_OFFER` 부모 행을 이 값으로 바꾸고 v1 DRAFT를 RETIRED, v2 APPROVED를 넣으며 `D_REST_ACK_START` · `D_REST_ACK` · `COACH_SWITCH` · `PEER_RETURN`을 더한다. `D_REPROMPT`는 정본에 고정 원문이 없어 넣지 않는다 | BR1.1 · Q1-A · 설계 검토 R-01 | U8 시드(`infra/seed/004-u8-reference.sql`) | Core ApprovedContentCatalog · U11 · U12 | 새 값 | ai-io.md · infra/seed/004-u8-reference.sql |

## U9 편차

> U9(Luna 호출부, 작업 기록 261015-doc-sync-code-update)가 요구사항 FR1.1 · R-F15 · R-F1과 다르게 정한 곳이다. 기호는 `(U9-<글자>)`이고
> Markdown은 `[U9 편차 (U9-a)]`로 표시한다. 근거는 U9 Functional Design `functional-spec.md` 9절 · `rules.md` BR1.7 · BR9.2 · BR9.3과
> 질문지 Q1-A · Q2-B · Q4 추가 요구다. 기존 필드 · 키 · 오류 코드의 이름과 뜻은 바꾸지 않는다.

| 기호 | 내용 | 출처 | 제공 | 소비자 | 호환 분류 | 표시 위치 |
|---|---|---|---|---|---|---|
| (U9-a) | FR1.1의 `openai-java` SDK 대신 JDK `HttpClient` + `JsonCodec`으로 같은 Responses API(`POST /v1/responses`)를 직접 부른다. Jackson 2 · OkHttp · Kotlin 런타임을 들이지 않아 J139(Jackson은 `JsonCodec` 한 곳)와 바깥 호출 관례를 지키고 새 의존성 점검 대상이 없다 | Q1-A · J139 | core `CoreLunaClient` · realtime `RealtimeLunaClient` | U10 · U11 · U14 · U15 | 구현 방식 변경(외부 계약 같음) | ai-io.md 10.10 |
| (U9-b) | R-F15(약관 · 보호자 동의 범위 확인 전 실제 아동 데이터 전송 금지)를 코드 스위치로 판정하지 않고 `infra/RUNBOOK.md` 단계 4의 확인 기록 표(확인자 · 날짜 · 약관 판 · 환경)로 지킨다. 키(`OPENAI_API_KEY`)가 있으면 실제 어댑터가 켜지므로 기록 없이 키를 넣지 않는 절차가 유일한 방어선이다 | Q2-B · BR1.7 · SD10 | 운영 절차(RUNBOOK) | demo 운영자 | 판정 방법 변경 | ai-io.md 10.2 · infra/RUNBOOK.md |
| (U9-c) | 사람이 키를 넣고 직접 돌리는 실호출 시험 `./gradlew lunaLiveTest`(태그 `luna-live`)를 둔다. R-F1(시험 · CI · 검증에서 실공급자 금지)의 글자와 다르지만, 기본 `test` · `check` · CI · `verify.sh`에서 언제나 빠지고(빌드 규칙 고정 제외 + 루트 `verifyLunaLiveIsolation`), 합성 자료만 쓰며, 결과는 '실제 API · 합성 자료' 증거로 따로 보고한다. 키가 없으면 `SKIPPED_NO_KEY` | Q4 추가 요구 · BR9.2 · BR9.3 | `backend/build-logic` `mujoong.java-conventions` · 두 앱 `*LunaLiveTest` | 사람(개발 PC) | 새 선택 작업 | ai-io.md 10.10 |

## U11 편차

> U11(판정 턴 기반, 작업 기록 261016-u11-judgment-turn) PR1(선 형식 정비 · 키 통일)이 승인 계약 9절과 앞 유닛 계약에 더하거나 바꾼 곳이다. 번호 체계: **계약 편차는 `(U11-<글자>)`**,
> 설계 편차는 `U11-D<n>`(`docs/units/u11/design.md`)이다. Markdown은 `[U11 편차 (U11-a)]`로 표시한다(`ContractFilesTest`의 한 · 두 글자 기호와 섞이지 않는다).
> `(U11-b)`는 판정 시한 값이 바뀌는 PR2에서 적었다. (U11-a)는 PR1 안에서 실호출 3회 결과로 한 번 고쳐 썼다(중간 가정 `response.create` · 끊김 허가 · `auto-response-start` 폐기).

| 기호 | 내용 | 출처 | 제공 | 소비자 | 호환 분류 | 표시 위치 |
|---|---|---|---|---|---|---|
| (U11-a) | 공급자 선 형식(ai-io.md 3절)을 실호출 3회(2026-10-16 ~ 17)로 관측한 형식으로 바꾼다. 세션 시작은 중첩 `session{model, instructions, audio{format{type: "audio/pcm", rate}}}`(24kHz 수락 확인, `childContext` · `delegation` 필드 없음, 아이 정보는 지시문 문장 안). **서버가 보내는 type은 `session.start` · `session.input_audio.append` · `session.instructions.append` · `session.close` 네 가지뿐이다** — `response.create`(Responses 위임 세션 전용, client 위임 세션은 거절) · `session.response.cancel` · `session.instructions.correct` · `session.update`는 보내지 않는다. `session.instructions.append`는 `{type, event_id, delegation_id: null, content}`이고 `delegation_id`는 값이 null이어도 키가 반드시 있어야 하며(없으면 `missing_required_parameter`), 옛 본문 필드 `text`는 없다. 공급자 → 서버는 `session.started{session{id}}` · `session.output_audio.delta{delta}`(응답 id · 시간 정보 없음) · `session.output_transcript.delta` · `session.input_transcript.delta`(`start_ms` · `end_ms` · `delta` · `event_id`, 출력 전사가 음성보다 앞서 도착) · `session.instructions.appended{event_id, client_event_id, start_ms, end_ms}` · `session.usage.updated{usage{seconds}}` · `error{error{type, code, message, param}}` · `session.closed`이고 **입력 전사 완료 · 말 끝 · 응답 끝 · 응답 id · 전사 신뢰도는 없다**. 모델은 아이 말이 끝나기 전에 자동으로 응답을 시작하고 끌 수 없다. 추가한 지시는 이후 응답에도 남고 지울 수 없으며(지속형 지시는 2회 뒤에도 유지, "이번 한 번만" 지시는 그 한 번만 적용, 표본 1), 지시는 말하는 중이면 낡은 말을 1.1 ~ 1.8초 더 이어 놓고 새 말이 쉼 없이 뒤따른다 — 그래서 모든 서버 발화는 지시 하나(`append`)로 시작하고 모든 지시문이 "이번 한 번만 …, 그 뒤에는 평소처럼 …"으로 범위를 닫으며 짧은 맞장구로 시작한다. 응답 id 대신 서버 출력 세대로 조각을 묶고 버린다: 지시를 보낸 세대는 확인 사건 `end_ms` 뒤 처음 오는 전사 `start_ms`를 새 말의 시작으로 보고 오디오 시계(누적 바이트 / 48 ms)가 시작 + `new-speech-margin`에 닿는 첫 음성 조각부터 받으며(확인 누락 · 새 말 누락은 사유 코드로 남기고 닫는다), 지시 없이 올린 세대(끼어들기 · 종료 의도 · 연습 폐기 · 게이트 폐기 · 보류 해제)는 `segment-quiet` 끊김을 쓴다. 서버 발화는 세션 직렬 큐 하나가 `append` 순서를 지킨다(끊김 허가 · 송신 직전 대조 · 중단 사건은 없다). 입력 전사는 서버가 조각을 묶어 `input-transcript-quiet` 동안 조용하면 확정 전사로 흘린다. 옛 (z) · (aa) · (U4b-a)를 대체한다. 새 시간 키 `mujoong.timing.provider.input-transcript-quiet` · `mujoong.timing.provider.new-speech-margin`(timing.md 14절, 임시값) | U11 FR12 · BR11 · 설계 W7 ~ W9 · 설계 개정(design-addendum-wire-format A1 ~ A10) · 실호출 3회 증거(real-call-evidence) | Realtime VoiceRelay · 공급자(가짜 공급자 셋은 같은 형식) | 공급자 · Realtime OutputGate | 규칙 변경(실호출 형식으로 대체) | ai-io.md · timing.md |
| (U11-b) | 진행 중 판정 한 번의 시한 `mujoong.timing.judgment.live-budget`을 **2s → 4s**로 늘린다(원 AC1.8.2 · NFR1의 2초를 바꾼다). 판정 턴을 연 시각부터 시계가 정확히 4.000초에 닿으면(같은 틱에 결과가 먼저 편지함에 들어왔으면 그 결과가 이긴다) 재시도 없이 판정 보류(TIMEOUT)이고 3.999초 안에 온 결과는 확정이다. 사람 결정(2026-10-16, 요구사항 Q10): 실호출로 잰 Luna 지연 약 2.6 · 2.9초가 2초 시한 밖이라 판정이 늘 시간 초과가 되기 때문이다. 말 끝부터 응답 준비 시계 `response-ready`(10초)는 그대로이고 판정 4초를 포함한다. 같은 키의 값은 realtime `application.yml` · 설정 레코드(`RealtimeProperties.JudgmentTiming`) · `timing.md` 13절 세 곳에서 함께 바뀌었다(R-M5) | U11 FR1.4 · BR1.4 · 요구사항 Q10 · 실호출 지연 관측 | Realtime 판정 턴(`JudgeClient`) | Realtime(Luna 호출부 `RealtimeLunaClient`가 시한 = min(마감, live-budget)로 읽는다) | 값 변경(시간 키 값만, 모양 · 이름은 그대로) | timing.md |
| (U11-c) | GPT-Live 키 환경 변수를 Luna 판정 키 `OPENAI_API_KEY` 하나로 통일한다(옛 이름 `PROVIDER_API_KEY`는 비밀 이름 검사 목록 · 운영 코드 · 설정 · 시험에서 없애고, 롤백 가능한 동안 Parameter Store에 같은 값으로 함께 두는 절차만 `infra/RUNBOOK.md`에 남긴다). realtime은 이 키를 demo 프로필에서만 필수로 보고, 키가 비면 연결 요청에 `Authorization` 헤더를 싣지 않는다. realtime Luna는 키 있음 AND 스위치 `mujoong.luna.enabled`(기본 false)일 때만 켜지고 `LUNA_STARTUP lunaState`에 `DISABLED_SWITCH_OFF`를 더한다. core-api Luna는 지금처럼 키만으로 켜진다 | U11 FR13 · BR12.1 ~ BR12.5 · R-F15 · R-M9 | Realtime 기동 점검 · LunaConfiguration · 배포 비밀 목록 | 운영 · 배포 | 새 값 · 이름 변경 | ai-io.md |

## U14 편차

| 기호 | 내용 | 근거 | 호환 분류 |
|---|---|---|---|
| (U14-a) | GOAL_CANDIDATES의 NONE 후보에 선택적 내부 structureSignature를 더한다. U15의 다섯 구조 값을 동일 후보 응답에서 받고 검증하며, 누락/잘못된 추상화/원문·이름 검증 실패는 NONE을 유지한다. 임의 상수 서명이나 추가 추상화 AI 호출을 만들지 않는다. 결과 커밋 후 candidate/version 멱등 키로 생성하고 새 불변 버전으로 게시한다. | 승인 U14 Functional Design D2·FR2.3, C-DS10, U15 StructureSignature | strict 스키마의 추가 출력 속성을 이전 소비자는 거절하므로 생산/소비를 같은 릴리스에서 갱신한다. 보호자 공개 속성이 아니다. |
| (U14-b) | 새 결과의 장면 번호는 양의 가변 정수이며 timing을 제공한다. 생성 후보는 reasonText 대신 displayEvidence를 공개하고 recommendationId는 불변 후보 버전이다. legacy 후보/장면 응답은 기존 모양을 유지한다. 프런트 API 파서는 displayEvidence를 기존 화면 모델의 reasonText로 변환한다. | 승인 U14 D1·D3·D4·D6, FR3.1·FR4.1 | strict 응답 소비자는 새 속성과 후보의 대체 필드를 거절할 수 있으므로 schema·API 생산부·프런트 파서를 같은 릴리스로 갱신한다. 기존 27개 화면의 모델과 칩은 유지한다. |
