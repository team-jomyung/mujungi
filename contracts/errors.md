# 오류 코드 (errors.md)

> 계약 2절 오류 봉투 · BR8.6 코드 목록 + 3.3 · 3.5가 더한 코드. 소유: U1(B). 모든 REST 오류는
> `{code, message, traceId}` 봉투이고(`schemas/error-envelope.schema.json`) 화면 문구는 **code로 고른다**.
> message는 code마다 아래 표의 고정 문장 하나뿐이다 — 전사 원문 · 개인정보 · 입력값 · 예외 메시지 · 스택을 넣지 않는다(BR8.6 · SD-11).
> message 문장은 화면 문구표에 없는 임시 문구라 `frontend/src/shared/copy/ko.ts`의 `TEMP_COPY`와 함께 code-summary에 목록을 남긴다(J118).
> 코드 구현은 `backend/contract-model/src/main/java/kr/mujoong/contract/error/ErrorCode.java`이고, 이 표와 enum이 같은지는
> `ContractFilesTest`가 대조한다(code · HTTP · message).

## 표기

- **HTTP**: 응답 상태 코드. 둘 이상이면 쉼표로 적는다.
- **노출**: `보호자`(ParentWeb 응답) · `기기`(DeviceWeb 응답) · `내부`(앱 사이 응답, 보호자 · 기기 응답에 내보내지 않고 번역) · `Nginx`(Nginx가 만든 봉투).
- 내부 코드는 보호자 응답에 그대로 내보내지 않는다: `DEVICE_NOT_CONNECTED` → `DEVICE_NOT_STABLE`(BR6.6 · BR8.6).
- 받는 쪽은 모르는 code를 일반 오류로 처리한다(더하기만 안전, 계약 10절).

## 코드 표

| code | HTTP | message | 노출 | 쓰는 곳 | 근거 |
|---|---|---|---|---|---|
| `UNAUTHENTICATED` | 401 | 로그인이 필요해요. | 보호자 · 내부 | 보호자 세션 없음 · 만료(C-GRD), 내부 토큰 없음 · 틀림 · 여러 개(C-INT) | BR8.5 · BR3.9 · J124 |
| `NOT_FOUND` | 404 | 찾을 수 없어요. | 보호자 · 내부 | 없는 자원 · 남의 자원(존재를 드러내지 않음), 꺼진 시험 로그인, Realtime이 모르는 세션의 view | BR8.5 · BR8.3 · BR6.15 |
| `VALIDATION_FAILED` | 400 | 요청 형식이 올바르지 않아요. | 보호자 · 기기 · 내부 | 형식 오류 · JSON 아닌 본문 · 64KB 초과(413 대신) · 등록 코드 정규화 뒤 영숫자 8자 아님 · 08 서술 칸 200자 초과 | BR2.1 · BR6.4 · [편차 (d)] · [편차 (r)] |
| `CONSENT_MISSING` | 403 | 동의가 필요해요. | 보호자 | 현재 정책 4항목 동의 없음 | BR6.1 ① |
| `ACCOUNT_DELETION_IN_PROGRESS` | 403 | 계정 삭제가 진행 중이에요. | 보호자 · 내부 | 계정 삭제가 접수된 보호자 · C10의 생성/저장 전 계정 삭제 확인 | 계약 2절 · BR2.2 · C10 |
| `CSRF_TOKEN_INVALID` | 403 | 보안 확인이 만료됐어요. 다시 시도해 주세요. | 보호자 | 상태 변경 요청의 X-CSRF-TOKEN 불일치 · Origin 불일치 | BR8.8 · SD-6 · [편차 (e)] |
| `SESSION_IN_PROGRESS` | 409 | 이미 진행 중인 활동이 있어요. | 보호자 · 내부 | 보호자당 종료 아닌 세션 하나(Core 1차 · Realtime 2차 방어) | BR6.1 ② · BR6.3 · BR6.6 |
| `ALREADY_SELECTED` | 409 | 이미 담은 목표예요. | 보호자 | 활성 선택의 중복 담기 | C4 · AC4.3.5 |
| `RESTORE_EXPIRED` | 410 | 되돌릴 수 있는 시간이 지났어요. | 보호자 | 삭제 복구 시한 경과 | C4 · AC4.1.11 |
| `SCENARIO_GEN_FAILED` | 422 | 연습 상황을 준비하지 못했어요. | 보호자 · 내부 | 자동 확인 · 재생성 두 번 실패 | C10 · AC4.2.1 |
| `IDEMPOTENCY_CONFLICT` | 409 | 같은 요청 번호로 다른 내용이 왔어요. | 보호자 | 같은 idempotencyKey에 다른 본문 | BR6.2 |
| `SESSION_NOT_ACTIVE` | 409 | 지금은 끝낼 수 있는 상태가 아니에요. | 보호자 · 내부 | 끝낼 수 없는 상태의 끝내기, 취소된 sessionId의 늦은 시작(10분 묘비), Realtime이 모르는 세션의 GUARDIAN_STOP | BR6.9 · [편차 (v)] · [편차 (w)] |
| `DEVICE_NOT_STABLE` | 423 | 기기 연결이 안정되지 않았어요. | 보호자 | 기기 판정이 안정이 아님(불안정 · 끊김 · 응답 없음 · 확인 불가 · 없음), Realtime 423의 번역 | BR6.1 ③ · BR4.4 · BR6.6 |
| `REALTIME_UNAVAILABLE` | 503 | 지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요. | 보호자 | Realtime 호출 실패 · 시간 초과(시작은 로컬 FAILED), 끝내기가 Realtime에 닿지 않음 | BR6.7 · BR6.9 |
| `CODE_NOT_FOUND` | 404 | 없는 코드예요. | 보호자 | 없는 코드 · 만료 · EXPIRED · 발급 알파벳 밖 글자 | BR2.1 ③ · BR1.4 · [편차 (d)] |
| `CODE_ALREADY_REGISTERED` | 409 | 이미 등록된 코드예요. | 보호자 | 다른 보호자가 등록했거나 RELEASED된 코드 | BR2.1 ② · BR2.3 |
| `DEVICE_ALREADY_ACTIVE` | 409 | 이미 연결된 기기가 있어요. | 보호자 | 활성 기기가 안정 · 불안정이라 교체를 거절 | BR2.4 · J13 |
| `TOO_MANY_ATTEMPTS` | 429 | 잠시 뒤 다시 시도해 주세요. | 보호자 | 보호자당 등록 실패 10분 10회 초과 | BR2.6 |
| `RATE_LIMITED` | 429 | 잠시 뒤 다시 시도해 주세요. | 기기 · Nginx | 코드 발급 IP당 1시간 20회 초과(같은 쿠키 반복은 세지 않음), Nginx `limit_req` | BR1.6 · [편차 (k)] |
| `CODE_ISSUE_FAILED` | 503 | 잠시 뒤 다시 시도해 주세요. | 기기 | 코드 추첨 5회 모두 겹침 | BR1.2 · [편차 (c)] |
| `BOOTSTRAP_NOT_FOUND` | 401 | 등록 정보를 찾을 수 없어요. | 기기 | bootstrap 쿠키 없음 · 모르는 값(status · claim) | BR1.7 · BR3.1 · [편차 (b)] |
| `NOT_REGISTERED_YET` | 409 | 아직 등록되지 않았어요. | 기기 | claim이 PENDING 행에 옴 | BR3.1 |
| `BOOTSTRAP_CLOSED` | 410 | 이 등록은 이미 끝났어요. | 기기 | 만료 · 해제 행 또는 첫 연결 뒤의 claim | BR3.2 |
| `DEVICE_NOT_REGISTERED` | 401 | 등록되지 않은 기기예요. | 기기 | 기기 쿠키 없음 · 모르는 토큰 · RELEASED(/device/me) | BR3.5 |
| `ALREADY_REGISTERED` | 409 | 이미 등록된 기기예요. | 기기 | bootstrap 행이 이미 REGISTERED(claim 전) → claim으로 | BR1.3 · [편차 (a)] |
| `INVALID_ENVELOPE` | 400 | 요청 본문 형식이 올바르지 않아요. | 내부 | 이벤트 봉투 필수 필드 누락 · 형식 오류, 연결 보고의 state · reportedAt 누락 | BR5.4 · BR4.1 · [편차 (i)] |
| `EVENT_CONFLICT` | 409 | 같은 이벤트에 다른 내용이 왔어요. | 내부 | 같은 eventId에 다른 payload 해시 | BR5.5 |
| `INVALID_DEVICE_TOKEN` | 401 | 기기 인증에 실패했어요. | 내부 | verify-token: 미등록 · RELEASED · 모르는 토큰 | BR3.4 |
| `DEVICE_NOT_CONNECTED` | 423 | 기기가 연결되어 있지 않아요. | 내부 | Realtime 시작 명령: 기기 연결이 CONNECTED가 아님(Core가 DEVICE_NOT_STABLE로 번역) | BR6.6 ② |
| `SERVICE_NOT_READY` | 503 | 잠시 뒤 다시 시도해 주세요. | 보호자 · 기기 · 내부 | 앱 NOT_READY(DB 점검 연속 실패) · DRAINING(정상 종료 중). `Retry-After` 헤더(초) 동반 | RD-16 · J124 · [편차 (n)] |
| `INTERNAL_ERROR` | 500 | 잠시 문제가 생겼어요. 다시 시도해 주세요. | 보호자 · 기기 · 내부 | 처리하지 못한 서버 오류(스택 · 예외 메시지 없음) | SD-11 · [편차 (o)] |
| `UPSTREAM_UNAVAILABLE` | 502, 504 | 지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요. | Nginx | Nginx가 업스트림(앱 · web)에 닿지 못해 만든 502 · 504. 상태 코드는 그대로 두고 본문만 이 봉투로 바꾼다. 앱이 낸 503은 그대로 통과한다(`proxy_intercept_errors off`) | R4-17 · CD-2 · [편차 (y)] |
| `GOOGLE_AUTH_FAILED` | 401 | 로그인하지 못했어요. 다시 시도해 주세요. | 보호자 | `POST /api/auth/google`의 모든 실패(code 없음 · redirectUri 불일치 · Origin 불일치 · 구글 교환 실패 · id_token 검증 실패 · 구글 설정 없음). 세션 쿠키를 만들지 않는다 | U2 · AC1.1.4 · 편차 (U2-a) |
| `CHILD_NOT_REGISTERED` | 404 | 아이 정보가 아직 없어요. | 보호자 | 아이를 등록하지 않은 보호자의 `/api/child` 조회 · 수정 · 종료 표현 | 계약 3절 /child · U2 |
| `CHILD_EXISTS` | 409 | 이미 등록된 아이가 있어요. | 보호자 | 계정당 아이 하나 — 두 번째 `POST /api/child` | 계약 3절 /child · AC1.3.9 · U2 |

## 응답 헤더 규칙

- 앱이 내는 모든 응답에 `X-Trace-Id`가 있고 오류 봉투의 `traceId`와 같다. 바깥에서 온 값은 형식(`^[0-9a-f]{32}$`)이 맞을 때만 이어 쓰고 아니면 새로 만든다(OD-7).
- `SERVICE_NOT_READY`는 `Retry-After: <mujoong.health.db-check-interval 초>`를 함께 보낸다(J124).
- 인증이 필요한 응답과 `GET /api/session/csrf`는 `Cache-Control: no-store`다(SD-4).
- 앱 응답에 CORS 헤더 · `Server` 헤더 값이 없다(SD-4 · 3.4 표준 키).

## 기기 WSS 오류

WSS의 오류는 이 표가 아니라 닫기 코드(1000 · 1001 · 1011 · 1013 · 4001 · 4400 · 4401 · 4408)와 `ERROR` 메시지
(`UNKNOWN_TYPE` · `BAD_PAYLOAD`)로 전한다. 표와 적용 범위는 `device-ws.asyncapi.yaml`(편차 (s) · J123)에 있다.
