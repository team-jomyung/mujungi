# 계약 편차 목록 (deviations.md)

> 승인된 `contract-summary.md`(2.8)는 고치지 않는다. 구현이 승인 계약보다 넓어지거나 구체화되는 곳을 여기에 한 행씩 적고,
> 각 스펙 파일에는 `x-deviation: ["(a)"]`(YAML · JSON) 또는 `[편차 (a)]`(Markdown)로 표시한다(사람 결정 5 · J59 · J112 · J130).
> (a) ~ (t)는 `docs/cloud_run_report.md` 2.1절, (u) ~ (y)는 3.5 계획 J112(사람 확인)에서 왔다.
> 모든 항목은 **더하기만 안전**(계약 10절 · J25)이다: 필드 삭제 · 이름 변경 · 필수화가 없고, 받는 쪽은 모르는 필드 · enum · code를
> 무시하거나 '알 수 없음'으로 처리한다. `ContractFilesTest`가 (a) ~ (y) 각각이 이 표에 한 행, 다른 계약 파일에 한 번 이상 표시돼 있음을 단언한다.
> (z)는 3.6 수정 라운드의 독립 리뷰 F1(J285 ~ J288)에서 왔고 `ContractFilesTest`가 같은 방식으로 단언한다.

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
| (z) | 공급자 `session.output.done`(responseId)을 그 응답의 전사 완료 신호로 쓴다 — 출력 게이트는 한 응답의 첫 조각부터 이 신호까지를 모두 담은 구간만 전사-음성 대응 근거로 보고, 신호가 없는 구간 · 앞부분이 다른 구간으로 떨어져 나간 구간 · 신호 뒤 조각이 붙은 구간 · 정규화 뒤 빈 전사를 GATE_EMPTY로 폐기한다(fail-closed, 실공급자 어댑터는 완결된 응답에만 이 신호를 보낸다). 한 구간에는 한 응답의 조각만 담고 다른 응답의 조각이 오면 열린 구간을 그 자리에서 확정해 게이트에 넣는다(BR7.3 확정 조건에 응답 경계를 더함). `responseId` 없는 출력 조각은 버린다 | 3.6 리뷰 F1 · SD-17 3단계 · BR7.4 (가) · J285 ~ J288 | 공급자(가짜 공급자 · 11/2 실공급자 어댑터, V-2) | Realtime OutputGate | 규칙 구체화 | ai-io.md |

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
