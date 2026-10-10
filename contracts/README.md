# contracts — 무중 계약 정본

승인된 계약 요약(`aidlc/spaces/default/intents/261008-mujoong-service/inception/contract-design/contract-summary.md`, 2.8)을
코드와 시험이 읽는 형식으로 옮긴 정본이다(J20 · G8). U1(3.5 Step 7)이 만들었고 U1이 쓰는 경로 · 메시지 · 이벤트만 담는다.
미룬 경로(U2 ~ U7)는 소유 유닛이 더하기만 한다. 승인 계약 본문은 고치지 않고, 구현이 넓어지거나 구체화되는 곳은
`deviations.md`에 한 행씩 적고 각 파일에 표시한다(사람 결정 5 · J112 · J130).

## 파일

| 파일 | 내용 | 계약 절 |
|---|---|---|
| `parent-api.yaml` | 보호자 REST(OpenAPI 3.0.3): 시험 로그인 · CSRF 토큰 · 공개 헬스 · 04 홈 · 05 기기 보기 · 05a 등록 · 08 시작 · 12 진행 조회 · P1 끝내기 | 3절 · 편차 (d)(e)(m)(n)(o)(r)(t)(u)(w) |
| `device-api.yaml` | 아이 기기 REST(OpenAPI 3.0.3): bootstrap · status · claim · me | 4.1절 · 편차 (a)(b)(c)(k)(n)(o)(r) |
| `device-ws.asyncapi.yaml` | 기기 WSS(AsyncAPI 2.6.0): 제어 메시지 17종 · 바이너리 음성 · 닫기 코드 | 4.2절 · 편차 (h)(j)(l)(n)(s) |
| `internal-api.yaml` | 앱 사이 내부 HTTP(OpenAPI 3.0.3): C8 시작 · 끝내기 · 진행 조회, C9 토큰 확인 · 상태 보고, 이벤트 수신 | 5절 · 편차 (f)(i)(j)(n)(o)(p)(v)(w) |
| `events.asyncapi.yaml` | 영속 이벤트 6종(AsyncAPI 2.6.0) | 6절 · 편차 (g)(p)(q) |
| `schemas/*.schema.json` | 위 문서가 `$ref`로 가리키는 본문 스키마(JSON Schema 2020-12). **본문 형식의 정본** | 2 ~ 6절 |
| `errors.md` | 오류 code · HTTP · 고정 message | 2절 · BR8.6 · 편차 (y) |
| `timing.md` | 시간 계약 키(7절)와 7절 밖 U1 시간 · 메시징 · 정리 · 내부 HTTP 키 | 7절 · BR8.11 |
| `ports.md` | 포트 · 내부 호출 이름 · 기본 주소 키 | 8절 · J109 |
| `ai-io.md` | AI 입출력 뼈대(9절), 공급자 연결 · 선 형식(J110), 시드 사전 형식, 승인 음성 | 9절 |
| `deviations.md` | 편차 (a) ~ (y): 출처 · 제공 · 소비자 · 호환 분류 · 표시 위치 | 10절 |

## 소유 · 변경 규칙 (계약 10절 · J25)

- 계약 1절의 '소유' 유닛 담당이 스펙을 고치고 소비 유닛 담당은 리뷰어다. U1이 만든 파일이라도 각 경로 · 메시지의 소유는 계약 1절을 따른다
  (예: C8 세션 경로의 소유는 U4, U1은 스켈레톤 슬라이스).
- **더하기만 안전**: 새 필드 · 새 enum 값 · 새 오류 code · 새 경로는 바로 머지한다. 받는 쪽은 모르는 필드를 무시하고 모르는 enum 값 · code는
  '알 수 없음'으로 처리한다(오류로 끊지 않는다). 백엔드는 `kr.mujoong.contract.json.JsonCodec`이, 프런트는 각 파서가 이 규칙을 지킨다.
- **깨는 변경**(필드 삭제 · 이름 변경 · 의미 변경 · 필수화)은 제공 · 소비 담당이 함께 고치고 같은 날 양쪽 코드를 머지한다. 경로 버전은 두지 않는다.
  이벤트는 `schemaVersion`을 올린다.
- 승인 계약과 다르게 넓히거나 구체화하면 `deviations.md`에 한 행을 더하고 스펙에 `x-deviation`(YAML · JSON) 또는 `[편차 (기호)]`(Markdown)로 표시한다.
- 스펙 파일에는 비밀 값 · 실제 도메인을 쓰지 않는다. 호스트는 `SERVICE_HOST` 자리표시자(기본 `localhost:8443`, J98 · J108)이고 예시 UUID는
  `00000000-0000-4000-8000-…` 꼴의 합성 값이다.

## 공유 DB 스키마 해석 (C13) [편차 (x)]

계약 8절 `database.schemas.core.owner_role: core_app`(realtime도 같은 꼴)은 **그 스키마에 쓰는 앱 역할**로 읽는다. 표 · 시퀀스 · 스키마의
소유자는 마이그레이션 역할 `mujoong_owner`이고, 앱 역할 `core_app` · `realtime_app`은 자기 스키마에 DML(SELECT · INSERT · UPDATE · DELETE)과
시퀀스 사용만 가진다. DDL · TRUNCATE는 없고 상대 스키마에는 읽기 · 쓰기가 모두 없다(BR8.1 · NFR9.7 · SD-13). 이력 표는 `flyway` 스키마의
`core_schema_history` · `realtime_schema_history`이고 앱은 자기 이력 표의 SELECT만 받는다(J116). 역할 · 권한을 만드는 곳은
`infra/postgres/init/01-roles.sql`이다.

## 스키마 규약

- 방언은 JSON Schema 2020-12이고 `$id`는 `https://mujoong.invalid/contracts/schemas/<파일>` 꼴이다. `.invalid`는 예약 최상위 도메인이라
  네트워크로 풀리지 않는다. 검증기는 이 접두를 `contracts/schemas/` 디렉터리로 바꿔 읽는다(파일 사이 `$ref`는 상대 경로).
- OpenAPI 3.0.3 · AsyncAPI 2.6.0 문서는 본문을 직접 적지 않고 이 스키마 파일을 `$ref`로 가리킨다. 3.0 전용 도구가 2020-12 키워드
  (`$defs` · `unevaluatedProperties` · 타입 배열)에 경고를 낼 수 있으나 정본은 스키마 파일이다(3.1 전환은 사람이 정함).
- 응답 · 보내는 쪽 본문 스키마는 `additionalProperties: false`로 노출 금지 필드(판정값 · 해시 · 08 원문, 계약 2절)가 새지 않음을 시험이 확인한다.
  이것은 **보내는 쪽 형식**이고, 받는 쪽은 여전히 모르는 필드를 무시한다.
- 시각은 ISO 8601 UTC(끝 `Z`), id는 UUID 문자열, traceId는 32자 소문자 16진수다(BR8.10 · OD-7).

## 시험이 확인하는 것

- `backend/contract-model/src/test/java/kr/mujoong/contract/ContractFilesTest.java`(계획 Step 13): 모든 YAML · JSON 파싱, 스키마의 2020-12 메타
  검증, 문서 예시를 가리킨 스키마로 검증, `$ref` 대상 존재, (a) ~ (y) 표시, `errors.md` ↔ `ErrorCode` 대조(V-5).
- 계약 대역 서버 `ContractStubServer`(Step 28)가 `contracts/schemas`로 앱 사이 요청을 검사하고, `ContractConformanceIT`(Step 34)가 Core 응답이
  스키마에 맞는지 본다. `ConfigKeyContractTest`(Step 36)가 `timing.md`의 키 · 기본값을 `application.yml` · 설정 레코드와 대조한다.

## 구현 대조 (U1 3.5 Step 52)

U1 코드 생성을 마친 뒤 계약 파일과 구현을 다시 맞춰 보았다(계약 12절 · V-5 · BR8.11). 아래 항목은 시험이 계속 강제하므로, 다음 유닛이
계약이나 구현 한쪽만 고치면 그 시험이 실패한다.

| 대조 | 계약 쪽 | 구현 쪽 | 결과 | 계속 강제하는 시험 |
|---|---|---|---|---|
| 경로 · 메서드 | `parent-api.yaml` 9 · `device-api.yaml` 4 · `internal-api.yaml` 6 | Core · Realtime 컨트롤러 매핑 19 | 일치(빠진 경로 · 남는 경로 0) | `ContractConformanceIT` · `RealtimeInternalApiIT` · 각 `*ApiIT` |
| 오류 code | `errors.md` 29 | `ErrorCode` 29 · 프런트 `TEMP_COPY.errors` 29 · Nginx 봉투 3(`RATE_LIMITED` · `VALIDATION_FAILED` · `UPSTREAM_UNAVAILABLE`) | 일치. `UPSTREAM_UNAVAILABLE`은 Nginx만 낸다(편차 (y)) | `ContractFilesTest` · `ErrorCodeTest` · 프런트 `copy.test.ts` · `infra-validate` |
| WSS 닫기 코드 | `device-ws.asyncapi.yaml` `x-close-codes` 9 | `CloseCode` 9 · 프런트 `closeCodes.ts` 9 | 일치(1000 · 1001 · 1006 · 1011 · 1013 · 4001 · 4400 · 4401 · 4408) | `ContractFilesTest` · `WsEnvelopeCodecTest` · `DeviceGatewayIT` · `ws-protocol.test.ts` |
| WSS 메시지 | 제어 17 + 바이너리 2(0x01 · 0x02) | `WsMessageType` 17(+ 모르는 값 `UNRECOGNIZED`) · 프런트 `messages.ts` · `wsEnvelope.ts` | 일치 | `WsEnvelopeCodecTest` · `ws-protocol.test.ts` |
| 시간 키 · 기본값 | `timing.md` | 두 앱 `application.yml` · 설정 레코드 · 프런트 `timing-defaults.json` | 일치 | 두 앱 `ConfigKeyContractTest` · 프런트 `timing.test.ts` |
| 포트 · 내부 주소 키 | `ports.md` | 두 앱 `application.yml`(`server.port` · 관리 9090 · `mujoong.internal-http.*`) · Compose | 일치 | 두 앱 `ConfigKeyContractTest` · `infra-validate` |
| 편차 | `deviations.md` (a) ~ (y) 25행 | 각 스펙 파일의 `x-deviation` · `[편차 (기호)]` | 25개 모두 표와 스펙에 있음 | `ContractFilesTest` |

### U1 구현 범위 메모 (편차 아님)

아래는 계약을 넓히지 않고 U1이 아직 다루지 않는 값을 거절하거나, 계약이 비워 둔 세부를 정한 것이다. 받는 쪽 동작이 계약 안이므로
`deviations.md`에 기호를 더하지 않았다. 뒤 유닛이 해당 기능을 더할 때 여기 줄을 지운다(사람 확인 후보는 U1 `code-summary.md`의 J 표).

| 경로 · 메시지 | U1 동작 | 이유 · 넘김 |
|---|---|---|
| RT `POST /internal/sessions` `activityType: PRACTICE` | 400 `VALIDATION_FAILED` | U1에 연습 프로토콜이 없다. U5가 받는다. Core U1은 PRACTICE를 보내지 않는다 |
| RT `POST /internal/sessions/{id}/end` `reason: CONSENT_WITHDRAWN` | 400 `VALIDATION_FAILED` | 동의 철회 전이는 U4 몫이다. U1 Core는 보내지 않는다 |
| 두 앱의 405(맞는 경로 · 다른 메서드) | 404 `NOT_FOUND` | 계약에 405 code가 없다. 경로 존재를 드러내지 않는다(BR8.5) |
| Core 보호자 경로의 UUID가 아닌 `{sessionId}` | 404 `NOT_FOUND` | 없는 자원과 같은 응답(BR8.5) |
| RT 내부 경로의 UUID가 아닌 `{sessionId}` | 400 `VALIDATION_FAILED` | 내부 호출의 형식 오류로 본다(보내는 쪽은 Core뿐) |
| Core `PUT /internal/devices/{deviceId}/connection`의 UUID가 아닌 `deviceId` | 400 `INVALID_ENVELOPE` | 보고 형식 오류로 본다(편차 (i)와 같은 code) |
| RT 내부 경로 DB 장애 · 끝내기 명령 응답 시간 초과 | 503 `SERVICE_NOT_READY` + `Retry-After` | 계약의 503 code가 하나뿐이다(편차 (n)) |
| 내부 본문 2MB 초과 | `/internal/events` 400 `INVALID_ENVELOPE`, 그 밖 내부 경로 400 `VALIDATION_FAILED` | BR3.9 · BR5.4 |
| 같은 이름 쿠키가 둘 이상 | 쿠키가 없는 것으로 본다(401) | 쿠키 고정(SD-5) 공격면을 줄인다 |
| 계정 삭제 접수 403 `ACCOUNT_DELETION_IN_PROGRESS` | 홈 · 기기 보기 · 끝내기에만 건다 | 삭제 지휘는 U2. U1에는 접수 경로가 없다 |
| CSRF 면제 2번(구글 교환) | 경로 없음 | U2 경로. 나머지 면제 4경로 밖 변경 요청은 CSRF 필수(BR8.8) |
| springdoc(`/v3/api-docs`) | 404(맞는 보안 체인 없음) | 계약 정본은 이 디렉터리다. dev 문서가 필요하면 체인을 따로 정한다 |
