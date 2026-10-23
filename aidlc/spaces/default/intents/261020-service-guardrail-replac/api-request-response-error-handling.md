# API 요청·응답·오류 처리 구조

확인일: 2026-10-21. 현재 작업 트리의 코드를 읽어 정리했다. 진행 중인 U16 변경을 포함한 코드 구조 설명이며, 원격 배포 상태나 모든 경로의 실행 성공을 인증하는 문서는 아니다.

## 전체 구조

성공 응답은 API별 DTO를 반환하고, REST 오류 응답은 공통 `{code, message, traceId}` 형식을 사용한다. 클라이언트의 오류 표현은 보호자 웹·기기 웹·서버 내부 호출마다 다르다.

| 통신 경계 | 요청·응답 방식 | 오류 처리 방식 |
|---|---|---|
| 보호자 웹 → Core | 공통 `ApiClient`의 JSON 요청, 세션 쿠키·CSRF, 응답별 파서 | `ApiError` 예외로 HTTP·네트워크·시간 초과·취소·응답 형식 오류 구분 |
| 기기 웹 → Core | 전용 `DeviceApi`, 동일 요청 진행 중 결과 공유 | `{kind: "registered"}`, `{kind: "failed"}` 등 판별 가능한 결과 타입 반환 |
| Core ↔ Realtime | 각 앱의 `InternalHttpClient`와 업무별 클라이언트, 내부 토큰 인증 | HTTP 응답과 통신 실패를 구분하고 업무 결과 타입·공개 오류로 번역 |
| 기기 ↔ Realtime | WebSocket 메시지와 음성 프레임, 세션·순번·재생 ID 연결 | ACK·재생 표식·연결 종료 코드·세션 실패 상태로 처리 |

일반적인 보호자 요청의 흐름은 다음과 같다.

```text
화면/기능 API
  → ApiClient: 쿠키·CSRF·시간 제한·JSON 요청
  → 서버 필터: 추적 ID·준비 상태·인증·입력 제한
  → Controller → Service → Repository 또는 내부 API
  → 성공: API별 DTO / 오류: 공통 오류 JSON
  → 프런트 응답 파서 또는 ApiError
  → 화면 문구·이동·재시도
```

## 보호자 웹의 요청·응답

[`apiClient.ts`](../../../../../frontend/src/parent/common/api/apiClient.ts)의 `ApiRequest<T>`는 메서드·경로·본문·응답 파서·CSRF 필요 여부·취소 신호를 받는다.

- 같은 origin으로 요청하고 `credentials: "same-origin"`을 사용한다. 세션 쿠키 값은 JavaScript에서 직접 읽지 않는다.
- `Accept: application/json`을 보내고 본문이 있으면 `Content-Type: application/json`으로 JSON 직렬화한다.
- `cache: "no-store"`를 사용한다.
- `AbortController`로 설정된 요청 시간 제한과 호출자 취소를 처리한다. 기본 규약은 보호자 요청 10초이며 실제 값은 클라이언트 구성에서 전달한다.
- 성공 응답은 기능별 `parse` 함수로 확인한다. 잘못된 JSON이나 `ResponseShapeError`는 `invalidResponse`로 변환한다.
- 비정상 HTTP 상태는 오류 봉투의 `code`와 `traceId`를 읽는다. 본문을 읽을 수 없어도 HTTP 상태와 헤더의 추적 ID를 활용한다.

성공 응답 전체를 `{success, data}` 같은 하나의 봉투로 감싸는 구조는 아니다. DTO·목록·명령 수락 응답 등 각 API 계약이 성공 본문과 HTTP 상태를 정한다. 서버 공통 도우미의 `ApiResponses.json(status, body)`도 전달받은 본문을 JSON으로 반환한다.

## REST 오류 계약

공통 오류 형식은 [`ErrorEnvelope.java`](../../../../../backend/contract-model/src/main/java/kr/mujoong/contract/error/ErrorEnvelope.java)에서 정의한다.

```json
{
  "code": "VALIDATION_FAILED",
  "message": "요청 형식이 올바르지 않아요.",
  "traceId": "0123456789abcdef0123456789abcdef"
}
```

위 추적 ID는 형식 설명용 합성 값이다.

| 필드 | 규칙 |
|---|---|
| `code` | 프로그램이 분기하는 안정된 오류 식별자 |
| `message` | 코드별 고정 문장. 입력값·전사·예외 메시지·스택을 포함하지 않음 |
| `traceId` | 응답 헤더 `X-Trace-Id`와 같은 요청 추적 ID |

오류 코드·허용 HTTP 상태·고정 문구·노출 대상은 [`ErrorCode.java`](../../../../../backend/contract-model/src/main/java/kr/mujoong/contract/error/ErrorCode.java)와 [`contracts/errors.md`](../../../../../contracts/errors.md)로 관리한다. `ContractFilesTest`가 코드와 문서를 대조한다.

### 대표 HTTP 상태

| 상황 | 대표 HTTP 상태·코드 |
|---|---|
| 잘못된 JSON·본문·매개변수 | `400 VALIDATION_FAILED` |
| 내부 이벤트 등의 잘못된 봉투 | `400 INVALID_ENVELOPE` |
| 로그인·인증 필요 | `401 UNAUTHENTICATED` 등 경계별 코드 |
| 동의·접근·CSRF 거절 | `403`과 해당 업무 코드 |
| 없는 대상·경로·지원하지 않는 메서드 | 현재 구현은 `404 NOT_FOUND` 등 |
| 진행 중 세션·멱등 키 충돌·업무 상태 충돌 | `409 SESSION_IN_PROGRESS`, `IDEMPOTENCY_CONFLICT` 등 |
| 준비되지 않음·실시간 연결 불가 | `503 SERVICE_NOT_READY`, `REALTIME_UNAVAILABLE` |
| 예상하지 못한 서버 예외 | `500 INTERNAL_ERROR` |
| 프록시 upstream 오류 | `502/504 UPSTREAM_UNAVAILABLE` |

구체적인 코드는 API별 계약과 `ErrorCode`의 허용 상태를 따른다. 표는 모든 상태를 나열한 목록이 아니다.

## 서버 예외 처리

### Core

업무 서비스는 [`CoreApiException.of(ErrorCode)`](../../../../../backend/core-api/src/main/java/kr/mujoong/core/common/error/CoreApiException.java)로 명시적인 업무 거절을 전달한다. 예외는 오류 코드만 담으며 입력값·토큰·전사·스택을 응답으로 전달하는 용도로 사용하지 않는다.

[`ApiExceptionHandler.java`](../../../../../backend/core-api/src/main/java/kr/mujoong/core/web/ApiExceptionHandler.java)의 전역 `@RestControllerAdvice`가 다음을 처리한다.

- 업무 예외 → 해당 오류 코드의 HTTP 상태와 고정 오류 본문.
- JSON·본문·파라미터 형식 오류 → 경로별 입력 오류 코드.
- 없는 경로·지원하지 않는 메서드 → `404`.
- 예상하지 못한 예외 → `500 INTERNAL_ERROR`와 허용 메타데이터만 포함한 `APP_ERROR` 기록.
- `SERVICE_NOT_READY` → 준비 상태의 `Retry-After` 헤더 추가.

필터·인증 진입점·예외 처리기는 [`ApiResponses.java`](../../../../../backend/core-api/src/main/java/kr/mujoong/core/web/ApiResponses.java)를 통해 같은 오류 봉투를 쓴다. `/internal/`에는 내부 코드를 유지하고, 외부 응답은 `ErrorCode.forGuardian()`으로 내부 전용 코드를 번역한다. 오류 응답은 `Cache-Control: no-store`를 사용한다.

컨트롤러 밖 보안 필터·서블릿 오류도 별도 응답 경로를 갖는다. 따라서 `@RestControllerAdvice` 하나만 모든 오류를 처리하는 것은 아니지만, 해당 경로도 공통 오류 형식에 맞춘다.

### Realtime

Realtime도 자체 [`ApiExceptionHandler.java`](../../../../../backend/realtime-activity/src/main/java/kr/mujoong/realtime/web/ApiExceptionHandler.java)를 가진다. 공통 `ErrorCode`·`ErrorEnvelope`·`JsonCodec`을 사용하면서 내부 본문 오류·DB/트랜잭션 장애·종료 시간 초과·예상하지 못한 예외를 구분한다. 컨트롤러의 업무 거절은 `errorEntity` 경로로 같은 봉투를 반환할 수 있다.

### 인증·추적·로그

- Core의 [`SecurityConfig.java`](../../../../../backend/core-api/src/main/java/kr/mujoong/core/security/SecurityConfig.java)는 내부 API·기기 API·보호자 API 등의 보안 체인을 구분한다. 보호자 세션 쿠키, Origin·CSRF, 기기 쿠키, 내부 토큰 등 경계별 검사를 적용한다.
- [`TraceIdFilter.java`](../../../../../backend/core-api/src/main/java/kr/mujoong/core/web/TraceIdFilter.java)는 요청 추적 ID를 정하고 요청 속성·MDC·응답 헤더에 연결한다.
- 오류 응답에 내부 예외 상세를 노출하지 않는다. 일반 오류 로그도 예외 종류 등 허용 항목을 사용하며 메시지·원인 사슬에 포함될 수 있는 개인정보나 비밀을 그대로 기록하지 않는다.

## 프런트의 오류 분기

보호자 웹의 `ApiError`는 다음 정보를 가진다.

| 속성 | 내용 |
|---|---|
| `kind` | `http`, `network`, `timeout`, `aborted`, `invalidResponse` |
| `status` | 응답이 있으면 HTTP 상태, 없으면 `null` |
| `code` | 알려진 오류 코드 또는 `UNKNOWN` |
| `traceId` | 오류 본문 또는 응답 헤더에서 받은 값 |

`isUnauthenticated`는 `401`을 판별한다. `isDeliveryUnknown`은 네트워크 실패·시간 초과처럼 요청이 서버에 반영됐는지 확실하지 않은 경우를 구분한다.

화면은 서버 `message`를 그대로 출력하는 대신 [`codeToCopy.ts`](../../../../../frontend/src/shared/errors/codeToCopy.ts)의 `messageForErrorCode`로 고정 화면 문구를 선택한다. 모르는 코드는 `UNKNOWN`과 일반 오류 문구로 처리한다. 화면별 훅은 이를 바탕으로 로그인 이동·재시도·토스트·폴링 중단 등을 결정한다.

기기 웹의 [`deviceApi.ts`](../../../../../frontend/src/device/api/deviceApi.ts)는 별도 방식이다. HTTP 상태·필수 필드를 확인해 `registered`, `unregistered`, `pending`, `expired`, `failed` 등의 결과를 반환하고, 네트워크·형식 오류는 주로 `failed`로 축약한다. 동일 작업의 중복 진행을 막는 single-flight도 적용한다. 보호자 공통 클라이언트의 취소·시간 제한·`ApiError` 구조가 그대로 공유되지는 않는다.

## 내부 API와 재시도

Core와 Realtime은 각각 HTTP 전송 구현을 가진다.

- [Core InternalHttpClient](../../../../../backend/core-api/src/main/java/kr/mujoong/core/internalhttp/InternalHttpClient.java)
- [Realtime InternalHttpClient](../../../../../backend/realtime-activity/src/main/java/kr/mujoong/realtime/internalhttp/InternalHttpClient.java)

전송 계층은 응답 상태·본문과 통신 실패를 구분하고, 업무별 클라이언트가 이를 해석한다. 모든 요청을 일괄 재시도하는 정책은 아니다.

[`RealtimeSessionClient.java`](../../../../../backend/core-api/src/main/java/kr/mujoong/core/activity/RealtimeSessionClient.java)의 세션 호출 규칙은 다음과 같다.

| 호출 | 응답 시간 제한 | 재시도 | 차단기 |
|---|---|---|---|
| 세션 시작 | 기존 설정 3초 | 시간 초과일 때 같은 세션 ID·본문으로 설정 횟수만 재시도(기본 1회) | 사용 |
| 종료·취소 | 기존 설정 2초 | 없음 | 없음 |
| 진행 조회 | 기존 설정 1초 | 없음 | 없음 |

보호자 `ApiClient`는 `403 CSRF_TOKEN_INVALID`일 때 토큰을 갱신하고 같은 요청 본문으로 한 번 재전송한다. 일반 네트워크 실패·시간 초과의 재시도는 기능별 판단이며, 상태 변경 요청은 같은 멱등 키·요청 ID를 유지해야 한다.

조회 실패는 항상 화면 전체의 HTTP 실패로만 드러나지는 않는다. [`SessionViewService.java`](../../../../../backend/core-api/src/main/java/kr/mujoong/core/activity/SessionViewService.java)는 내부 조회 불가 시 기존 세션 사본과 관측 상태로 fallback할 수 있다. 진행 중 U16에서는 이 fallback의 새 `guardrail` 사실은 `stale` 값과 무관하게 `null`로 보내 과거 재생 사실을 재사용하지 않도록 한다.

## JSON·계약·WebSocket

공통 [`JsonCodec.java`](../../../../../backend/contract-model/src/main/java/kr/mujoong/contract/json/JsonCodec.java)는 서버 JSON 읽기·쓰기를 관리한다. 시각은 UTC ISO 8601로 직렬화하며 기본적으로 `null` 필드를 생략한다. 계약상 필수 nullable 필드는 명시적인 포함 설정을 사용한다. 모르는 JSON 속성을 무시하는 읽기 규칙이 있으므로, DTO 파싱만으로 모든 업무 값·스키마 검증이 끝났다고 보지는 않는다. 각 경계의 검증과 계약 대조 시험이 함께 적용된다.

WebSocket은 REST 오류 봉투와 다른 통신이다. [`DeviceWebSocketHandler.java`](../../../../../backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/DeviceWebSocketHandler.java)와 [`WsFrameGuard.java`](../../../../../backend/realtime-activity/src/main/java/kr/mujoong/realtime/gateway/WsFrameGuard.java)가 인증된 연결의 메시지·봉투·음성 프레임을 검사하고, 현재 세션으로 전달한다. 프로토콜 오류·인증 실패·서비스 장애는 경계에 따라 연결 종료 코드나 세션 실패·정리로 처리한다. ACK 수신과 실제 재생 완료 표식도 별개다.

## 현재 공통화 수준

| 항목 | 현재 상태 |
|---|---|
| REST 오류 형식·코드 | 공통 계약 사용 |
| 성공 응답 | API별 DTO·상태 사용 |
| 서버 JSON 코덱 | 공통 `contract-model` 구현 사용 |
| 서버 예외 처리 | 앱별 처리기, 공통 오류 계약 사용 |
| 보호자 웹 JSON 호출 | 공통 `ApiClient` 사용 |
| 기기 웹 호출 | 전용 클라이언트·결과 타입 사용 |
| 내부 HTTP | 앱별 전송 구현과 업무별 해석·재시도 |
| PDF·승인 오디오 취득 | 별도 `fetch` 경로 존재 |
| WebSocket | 전용 메시지·연결·세션 오류 처리 |

따라서 오류 계약은 공유하지만, 예외 타입·결과 타입·재시도·응답 검증은 경계별 구현이다. 이 문서는 현황 설명이며 공통화 변경이나 API 재설계를 수행하지 않는다.
