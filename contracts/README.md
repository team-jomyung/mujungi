# contracts — 무중 계약 정본

현재 앱 사이·클라이언트 계약의 진입점이다. 초기 U1 스켈레톤 이후 기능이 추가되었으므로 현재 파일 전체를 읽는다. 승인 원본 계약과의 차이는 [deviations.md](deviations.md), 세부 AI 입출력은 [ai-io.md](ai-io.md)에 기록한다. 아래 U1 수치와 미구현 범위는 과거 기록이며 현재 기능 범위가 아니다.

## 현재 계약 파일

| 파일 | 내용 |
| --- | --- |
| [parent-api.yaml](parent-api.yaml) | 보호자·활동·목표·결과·리포트 등 REST 경로 |
| [device-api.yaml](device-api.yaml) | 기기 등록·인증·상태 REST |
| [device-ws.asyncapi.yaml](device-ws.asyncapi.yaml) | 기기 WSS 제어·음성 메시지와 종료 코드 |
| [internal-api.yaml](internal-api.yaml) | Core–Realtime 내부 HTTP |
| [events.asyncapi.yaml](events.asyncapi.yaml) | 영속 이벤트와 본문 참조 |
| [schemas/](schemas/) | 본문 형식의 JSON Schema 정본 |
| [errors.md](errors.md), [timing.md](timing.md), [ports.md](ports.md) | 오류·시간·포트 계약 |
| [ai-io.md](ai-io.md) | 생성·판정·출력 검사·음성 입출력 |
| [practice-goal-list.md](practice-goal-list.md) | 즐겨찾기 상한·목표 목록 페이지 |
| [goal-result-compatibility.md](goal-result-compatibility.md) | 목표·결과 호환 규칙 |
| [deviations.md](deviations.md) | 승인 설계 이후 변경 근거 |

현재 DB 구조는 [마이그레이션 안내](../docs/erd/README.md)를 확인한다. API 목록·메시지 수·오류 수는 오래된 합계표 대신 해당 정본 파일과 구현 시험을 기준으로 확인한다.

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

## 과거 구현 대조 (U1 3.5 Step 52 · 보존)

아래 표는 U1 완료 당시 결과다. 현재 전체 API·오류·메시지 개수나 이번 최신화에서 재실행한 시험 결과가 아니다.

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
`deviations.md`에 기호를 더하지 않았다. 현재는 이후 구현과 구분하기 위해 당시 행을 보존한다(당시 사람 확인 후보는 U1 `code-summary.md`의 J 표).

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
