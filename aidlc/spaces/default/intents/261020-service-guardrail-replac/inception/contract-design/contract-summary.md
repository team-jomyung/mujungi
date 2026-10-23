# 무중 서비스 가드레일 경계 계약

## Sources

- [U1] ../units-generation/unit-of-work.md: U16 service, U17 ui의 소유와 완료 기준. 공개 계약·Java 모델·서버 투영은 U16, 프런트 소비/파싱은 U17.
- [U2] ../units-generation/unit-of-work-dependency.md: U17 → U16 단방향 의존, HTTP/WS 통합.
- [D1] ../domain-design/components.md: PolicyBundle/SessionConversation/NoticeDeliveryEvidence/OutputCandidateBinding의 소유. 상태 권위는 기존 actor 하나.
- [R1] ../requirements-analysis/requirements.md: FR1–FR7, NFR1–NFR6. 세부 추적은 아래 Coverage.
- [Q1] contract-design-questions.md Q1-A: 기존 주소/메시지를 유지하며 서버·화면 함께 갱신. 수정 전 소비자의 동시 호환을 약속하지 않는다.
- [C1] contracts/{parent-api,internal-api}.yaml 및 schemas/{live-view,internal-session-view,common}.schema.json: 현재 주소·상태·접근/오류·순번·사본 경계.
- [C2] contracts/device-ws.asyncapi.yaml, schemas/{ws-envelope,ws-messages}.schema.json: 현재 WSS 봉투·인증·명령·실제 재생 표식·중복/재접속 규칙.
- [C3] contracts/ai-io.md, timing.md 및 schemas/{ai-judgment-request,ai-judgment-output,guardrail-request,guardrail-result}.schema.json: 기존 공급자·판정·검사 형식/한도/마감.
- [CODE1] realtime session/{SessionViewQuery,JoinFlow,EndingFlow,SessionContext,SessionExecutor}.java 및 voice/ProviderRequestFactory.java: 현재 조회/안내/실행·송신 사실.
- [CODE2] core activity/SessionViewService.java, frontend parent/common/api/{types,live}.ts 및 device/ws/deviceSession.ts: 사본 fallback·진행 파싱·기기 재생 소비.
- [UX1] ../refined-mockups/interaction-spec.md: RP-1–RP-4의 사유/실제 인용/실제 인사 표시.

## Contract Overview

이 문서는 구현할 목표 계약이다. 현재 코드에 이미 적용됐다는 뜻이 아니다. 기존 전체 계약을 복제하지 않고 영향을 받는 경계의 형식과 불변 조건을 고정한다. 각 spec의 외부 상대 `$ref`는 `contracts/`를 기준으로 해석한다. 아래 C1/C2의 `GuardrailPresentation` 정의는 이 문서의 공통 스키마이며 구현 때 `contracts/schemas/guardrail-presentation.schema.json`으로 옮긴다. 나머지 외부 `$ref`는 현재 정본이다. 적용 시 기존 엄격한 스키마에 필드를 직접 선언하며, 닫힌 스키마를 allOf로 단순 확장하지 않는다.

| # | Provider Unit | Consumer | Mechanism | Owner |
|---|---|---|---|---|
| C1 | U16, 기존 Core 보호자 API | U17 / External: 인증된 보호자 웹 | 기존 REST 진행 조회 + 공통 JSON Schema | U16 |
| C2 | U16, 기존 Realtime 조회 | U16 Core 중계, 결과를 소비하는 U17 | 기존 내부 HTTP 진행 조회 + 같은 공통 스키마 | U16 |
| C3 | U16 Realtime DeviceGateway | U17 / External: 인증된 기기 웹, 양방향 | 기존 WSS·AsyncAPI, wire 변경 없음 | U16, 소비 구현 U17 |
| C4 | External: 기존 GPT-Live 공급자 | U16 ProviderClient, 양방향 | 기존 공급자 세션 JSON/음성, 4개 송신 type 유지 | 공급자 wire는 외부, 앱 허용 프로파일 U16 |
| C5 | External: 기존 판정 공급자 | U16의 J1/J6 호출 경계 | 기존 요청/결과 JSON Schema·고정 판정 템플릿 | 공급자 wire는 외부, 앱 어댑터/판정 정책 U16 |
| C6 | External: 기존 JEV 공급자 | U16 OutputGuard | 기존 검사 어댑터 JSON Schema | 공급자 wire는 외부, 앱 어댑터/연결 U16 |

C4–C6은 새 외부 API가 아니라 기존 경계를 유지한다는 명시적 계약이다. 새 HTTP 공급자 주소·새 모델·분류 범주를 만들지 않는다. 정책 적재/순수 판단/세션 효과는 U16 내부 책임이며 U17에 그 실행 권한을 내보내지 않는다.

## Shared Schema — GuardrailPresentation

보호자 조회에 선택 속성 `guardrail`을 추가한다. 새 서버는 이 속성을 항상 내보내되 사실을 확인할 수 없으면 null로 한다. 이전 응답의 속성 없음은 새 소비자에게 null과 같다. null/UNKNOWN은 성공이나 거절·무응답·기기 정지의 증거가 아니다. 정책 본문/해시/판정 원문/새 전사 원문은 이 객체에 없다.

```yaml
$schema: https://json-schema.org/draft/2020-12/schema
$id: https://mujoong.invalid/contracts/schemas/guardrail-presentation.schema.json
title: GuardrailPresentation
anyOf:
  - type: 'null'
  - $ref: '#/$defs/Facts'
$defs:
  Facts:
    type: object
    additionalProperties: false
    required: [schemaVersion, notice, participation, goodbye, confirmedStopBubbleSeq]
    properties:
      schemaVersion: {const: 1}
      notice:
        type: string
        enum: [UNKNOWN, PENDING, COMPLETED, FAILED]
      participation:
        type: string
        enum: [UNKNOWN, PENDING, ACCEPTED, REFUSED, NO_RESPONSE]
      goodbye:
        type: string
        enum: [UNKNOWN, NONE, PENDING, PLAYING, COMPLETED, STOPPED, FAILED]
      confirmedStopBubbleSeq:
        anyOf:
          - {type: integer, minimum: 1}
          - {type: 'null'}
```

| 속성 | 생산/검증 의미 | 표시 제한 |
|---|---|---|
| notice | PENDING은 현재 유효한 승인 안내 요청을 기다림. COMPLETED는 C3의 현재 실제 완료 확인. FAILED는 자원/전송/재생/확인 시한 실패. 없거나 이전 세션 자료는 UNKNOWN | COMPLETED도 현재 참여 수락을 대신하지 않음. 일반 AI 출력의 권한은 서버가 결정 |
| participation | 안내 확인 뒤 현재 의사를 확인하는 동안 PENDING. 실제 수락 ACCEPTED, 명확한 거절 REFUSED, 안내 이후 기존 참여 대기/재안내 모두 무응답으로 만료한 경우만 NO_RESPONSE | DECLINED만으로 REFUSED/NO_RESPONSE를 추정하지 않음. 기술 실패는 failureCode/기존 상태로 표현하고 UNKNOWN 또는 확인된 기존 의사만 유지 |
| goodbye | NONE은 인사를 하지 않기로 확정(즉시 음소거 포함). 요청만 됐으면 PENDING, 현재 승인 인사 STARTED가 확인된 동안만 PLAYING, 실제 완료/중단/실패는 각각의 값 | CLOSING만으로 PLAYING을 만들지 않음. PLAYING도 조회 시점의 관측이며 보장된 미래 재생이 아님. stale/fallback/단절·종료 상태에서 재생 중 줄 숨김 |
| confirmedStopBubbleSeq | 현재 CHILD_STOP의 확정 전체 종료 의사를 담은 기존 공개 허용 CHILD 전사의 seq. 해당 행은 final=true, uncertain=false이며 현재 세션 소유, 접근 가능해야 함 | UI가 병합해 가진 같은 세션 items에서 정확히 해당 행을 찾을 때만 기존 text 인용. 없거나 잘못된 화자/불확실/삭제된 행이면 생략. 기본 등록 표현 대입 금지 |

`confirmedStopBubbleSeq`는 새 원문 복사/로그가 아니다. 참여 전 거절 원문은 기존처럼 삭제/비영속이며 이 참조에 넣지 않는다. 명확한 종료가 전사 확정/보존으로 연결되지 않았으면 null이다. U17은 인용을 위해 새 전사 조회/자동 호출 루프를 만들 필요가 없고, 현재 items에 없으면 생략한다. 세션 전환/재로그인 때 이전 items를 참조하지 않는다.

새 객체의 schemaVersion이 지원 밖이거나 구조가 잘못되면 새 소비자는 그 객체 전체를 null로 취급하고 기존 상태/권한 처리를 유지한다. 알려진 객체의 미래 enum은 해당 값 UNKNOWN으로 낮춘다. 소비자는 알 수 없는 필드를 실행/표시 근거로 사용하지 않는다. 송신 검증은 선언한 스키마를 엄격하게 적용한다. 알 수 없는 type을 무시하는 규칙은 WSS의 UNKNOWN_TYPE 오류 계약을 대체하지 않는다.

## C1 — 보호자 진행 조회

기존 URL·인증/소유/삭제 차단·no-store·2초 진행 폴링을 유지한다. 선택 필드 하나를 기존 LiveView 속성에 선언한다. 아래 참조의 `live-view.schema.json`은 이 변경을 직접 반영한 목표 정본이다. 형식 오류/음수 sinceSeq는 기존 VALIDATION_FAILED이고 실패 시 새 facts를 지어내지 않는다.

```yaml
openapi: 3.1.0
info: {title: Service guardrail parent view profile, version: '1.1'}
servers: [{url: /api}]
security: [{session: []}]
paths:
  /sessions/{sessionId}:
    get:
      operationId: getSessionLiveView
      parameters:
        - {name: sessionId, in: path, required: true, schema: {type: string, format: uuid}}
        - {name: sinceSeq, in: query, required: false, schema: {type: integer, minimum: 0, default: 0}}
      responses:
        '200':
          description: 기존 LiveView에 아래 x-schema-change 반영
          headers:
            Cache-Control: {schema: {const: no-store}}
          content:
            application/json:
              schema: {$ref: schemas/live-view.schema.json}
        '400': {$ref: '#/components/responses/Error'}
        '401': {$ref: '#/components/responses/Error'}
        '403': {$ref: '#/components/responses/Error'}
        '404': {$ref: '#/components/responses/Error'}
        '500': {$ref: '#/components/responses/Error'}
        '503': {$ref: '#/components/responses/Error'}
components:
  securitySchemes:
    session: {type: apiKey, in: cookie, name: MUJOONG_SESSION}
  responses:
    Error:
      description: 기존 오류 코드/접근 규칙 유지
      content:
        application/json:
          schema: {$ref: schemas/error-envelope.schema.json}
x-schema-change:
  file: schemas/live-view.schema.json
  operation: add-optional-property
  property: guardrail
  schema: {$ref: schemas/guardrail-presentation.schema.json}
  preserve: [required, SessionSummaryFields, items, nextSeq, unevaluatedProperties]
```

기존 `stale`은 30초 의미를 유지하므로 일회성 내부 조회 실패라도 false일 수 있다. 따라서 **Core 사본 fallback은 항상 guardrail=null**로 보낸다. stale=false여도 예전 PLAYING을 재사용하지 않는다. UI 병합은 이번 응답의 null/없음을 이전 guardrail로 보충하지 않는다. 이전 응답이 더 늦게 도착하면 기존 요청 취소/세션 및 순서 확인으로 버린다. 동일 statusVersion이어도 재생 사실은 바뀔 수 있으므로 statusVersion 동일만으로 이번 facts 갱신을 무시하지 않는다. 서로 다른 요청 응답을 섞어 상태/사실을 합성하지 않는다.

## C2 — Realtime 내부 진행 조회와 사실 수명

```yaml
openapi: 3.1.0
info: {title: Service guardrail internal view profile, version: '1.1'}
servers: [{url: 'http://realtime:8081'}]
security: [{internal: []}]
paths:
  /internal/sessions/{sessionId}/view:
    get:
      operationId: getSessionViewInternal
      parameters:
        - {name: sessionId, in: path, required: true, schema: {type: string, format: uuid}}
        - {name: sinceSeq, in: query, required: false, schema: {type: integer, minimum: 0, default: 0}}
      responses:
        '200':
          description: 기존 내부 LiveView에 guardrail 선택 속성 선언
          content:
            application/json:
              schema: {$ref: schemas/internal-session-view.schema.json}
        '400': {$ref: '#/components/responses/Error'}
        '401': {$ref: '#/components/responses/Error'}
        '404': {$ref: '#/components/responses/Error'}
        '500': {$ref: '#/components/responses/Error'}
        '503': {$ref: '#/components/responses/Error'}
components:
  securitySchemes:
    internal: {type: apiKey, in: header, name: X-Internal-Token}
  responses:
    Error:
      description: 기존 내부 API 인증/오류 정책, 새 외부 노출 없음
      content:
        application/json:
          schema: {$ref: schemas/error-envelope.schema.json}
x-schema-change:
  file: schemas/internal-session-view.schema.json
  operation: add-optional-property
  property: guardrail
  schema: {$ref: schemas/guardrail-presentation.schema.json}
  preserve: [required, SessionSummaryFields, items, nextSeq, unevaluatedProperties]
```

인증은 정본 internal-api.yaml의 X-Internal-Token 헤더 방식이다. 새로운 자격 증명/인증 수단을 도입하지 않는다. Core는 기존 소유 확인 이후에만 이 API를 부르며 Realtime DB를 직접 읽지 않는다. 내부 호출 상한 1초·연결 500ms·재시도/차단기 없음, 실패 시 C1 사본 fallback을 유지한다.

| 사실 수명 | 계약 |
|---|---|
| 살아 있는 actor | actor가 쓰는 불변 projection을 읽는다. 사실과 세션 상태가 모순되면 안전한 null/UNKNOWN을 반환하고 ACTIVE를 만들어내지 않는다. 단순 조회가 상태를 바꾸지 않는다 |
| 참여/종료 전이 | notice 완료/참여 disposition/확정 종료 참조 등 확인한 최소 메타데이터를 기존 Realtime 세션/이벤트 보존 경계에 남겨 terminal 조회에서도 REFUSED와 NO_RESPONSE를 구분한다. 전이와 사실의 일치가 필요하며 물리 필드/트랜잭션 구현은 U16 Functional Design에서 확정 |
| actor 제거·서버 재시작 | 보존된 확정 사실만 복구한다. 재생 중이었던 사실은 현재 PLAYING으로 복구하지 않으며 UNKNOWN으로 낮춘다. 이전 세션/옛 정책/옛 재생으로 참여를 복구하지 않는다. 기존 재시작 실패 정리 의미 유지 |
| 사실 기록 실패/레거시 세션 | 확인한 metadata가 없으면 guardrail=null 또는 해당 UNKNOWN. 기존 endReason/status만으로 거절·무응답·인사 재생·인용을 만들지 않는다 |
| Core fallback/계정 삭제/보존 만료 | Core는 새 facts 사본을 별도로 영속화하지 않는다. 내부 조회 실패는 guardrail=null. 기존 삭제·접근 차단·보존과 함께 Realtime 메타데이터/참조를 처리한다. 새 장기 보관·원문 복제 없음 |

정확한 물리 저장 위치가 미정인 것은 의미 계약을 미정으로 남긴 것이 아니다. U16은 위 수명을 구현하고 U17은 자료 부재를 정상적인 미확인으로 소비한다. 안내 완료 증거와 정책/프롬프트 버전·해시의 운영 식별은 서버 내부에만 남긴다.

## C3 — 기기 재생·안내 증거

```yaml
asyncapi: 2.6.0
info: {title: Service guardrail device playback profile, version: '1.1'}
channels:
  /ws/device:
    publish:
      description: 기기에서 서버로, 기존 봉투/인증/방향 검증 유지
      message:
        oneOf:
          - {$ref: 'device-ws.asyncapi.yaml#/components/messages/ACK'}
          - {$ref: 'device-ws.asyncapi.yaml#/components/messages/PLAYBACK_MARK'}
          - {$ref: 'device-ws.asyncapi.yaml#/components/messages/VOICE_SESSION_CLOSED'}
    subscribe:
      description: 서버에서 기기로, 기존 메시지의 의미 보강
      message:
        oneOf:
          - {$ref: 'device-ws.asyncapi.yaml#/components/messages/START_SESSION'}
          - {$ref: 'device-ws.asyncapi.yaml#/components/messages/PLAY_APPROVED_CONTENT'}
          - {$ref: 'device-ws.asyncapi.yaml#/components/messages/STOP_OUTPUT'}
          - {$ref: 'device-ws.asyncapi.yaml#/components/messages/CLOSE_TRANSPORT'}
x-wire-change: none
x-envelope-schema: schemas/ws-envelope.schema.json
x-payload-schema: schemas/ws-messages.schema.json
```

이 profile은 관련 메시지의 부분집합이며 HELLO/DEVICE_READY/음성/재접속 등의 나머지 현재 계약도 그대로 유효하다. URL `/ws/device`, 쿠키/Origin/연결별 인증, 봉투의 sessionId/opId/seq, 24kHz 음성 프레임을 유지한다. 서버가 추가한 내부 정책 ID를 WSS payload에 넣지 않는다.

| 순서/조건 | 계약 동작 |
|---|---|
| 안내 선택 | 서버가 현재 자유대화 PolicyBundle의 noticeTextHash와 승인 콘텐츠 버전/텍스트의 일치를 검증한다. 기존 승인 콘텐츠 조회/START_SESSION.approvedAudio에 실린 현재 자원만 사용한다. 서버 내부에 sessionId/playbackId/contentVersionId/정책·프롬프트 식별 연결을 만든 뒤 PLAY_APPROVED_CONTENT 발행 |
| 실제 완료 | 현재 인증된 기기의 현재 연결/세션에 속하고, 무효화되지 않은 예상 playbackId에 대한 유효한 PLAYBACK_MARK(COMPLETED)를 서버 수신 순서로 처리한다. 기기 observedAt은 참고값이며 마감 판정/정책 권위를 갖지 않는다. 전송 ACK·prefetch·말풍선·STARTED·시험 톤/무음으로 완료하지 않음 |
| 참여 | 실제 안내 완료 후 현재 참여 확인 창에서 들어온 새 발화/현재 판정 결과만 수락 가능. 안내 중 발화/그 판정 결과는 버린다. 완료 뒤 참여 질문을 해야 하면 기존 승인 콘텐츠 경로를 사용하며 일반 AI 대화를 먼저 열지 않는다 |
| 중복/순서 역전 | 같은 seq/opId를 중복 실행하지 않음. 완료 사건 중복은 no-op. 중단/실패/기한 초과/새 재생으로 무효화된 ID의 늦은 COMPLETED는 no-op. 존재하지 않는 ID나 다른 세션/기기의 보고는 상태/시계를 바꾸지 않음 |
| 재접속 | 기존 1·2·4초/이후 5초와 40초 연결 상한, 동일 opId ACK 재송신 의미 유지. 중단한 안내 재생의 완료를 되살리지 않음. 재생을 다시 허용할 때는 새 playbackId와 현재 기대 연결을 사용하고 기존 일반 출력은 잇지 않음. 세션이 종료됐으면 재시작하지 않음 |
| 실패/만료 | 승인 자원 누락/버전 불일치/송신 실패/FAILED/확인 만료는 notice FAILED, 일반 대화 보류 후 기존 기술 실패/정리. 기존 DEVICE_NO_ACK 또는 PROVIDER_UNAVAILABLE 등 실제 원인에 맞는 failureCode를 사용한다. 실패를 DECLINED/NO_RESPONSE로 번역하지 않음 |
| 명확한 참여 거절 | REFUSED를 보존하고 기존 DECLINED 정리. 만류/대안 재권유 없음. 기다림 만료는 확인된 안내 이후 기존 20초·재안내 20초를 모두 적용했을 때 NO_RESPONSE |
| 종료 | 현재 목적에 허용된 승인 인사를 최대 한 번. 즉시 음소거는 NONE과 출력 무효화. PLAYING은 해당 인사의 현재 STARTED 이후이며 COMPLETED/STOPPED/FAILED/연결 단절/시한 만료에서 해제. VOICE_SESSION_CLOSED의 현재 출력/마이크 정리 확인 전 confirmed=true를 만들지 않음 |

완료 전에 반드시 STARTED 보고를 받아야 한다는 새 wire 요구는 추가하지 않는다. 기존 기기가 정당한 COMPLETED를 보내는 경계를 서버 수명/현재 ID로 검증한다. 기기는 실제 오디오 종료 이후에만 COMPLETED를 보고하고 stop/실패/끊김에서는 해당 표식으로 구분한다. 클라이언트 완료 보고가 실제 아이의 청취·이해까지 증명한다고 주장하지 않는다.

안내 재생 시한은 기존 Settings.playbackExpiry에 대응하는 mujoong.timing.device.output-queue-max(20s)다. 참여20초는 실제 참여 질문 재생 완료 이후 시작하며 재생·기술 일시정지 제외 의미를 보존한다. 새 고정 시간값을 만들지 않는다. 승인 안내 자원은 기존 재생 상한과 필수 사실을 모두 만족해야 한다. 길이가 넘거나 자원이 준비되지 않으면 완료로 처리하지 않고 U16 Functional Design에서 실제 콘텐츠 구성을 해결한다. 안내 재생 확인 실패를 onJoinPromptPlayed로 보내던 현재 분기를 제거할 책임은 U16이다.

## C4 — 기존 GPT-Live 송신 프로파일

아래는 앱이 송신 가능한 JSON의 공유 스키마다. 공급자 응답 전체를 새로 정의하지 않는다. 내부 모드/정책 버전·해시는 요청 필드가 아니며, 세션의 고정 정책으로 instructions를 선택하는 내부 인자만 늘릴 수 있다.

```yaml
$schema: https://json-schema.org/draft/2020-12/schema
title: Existing GPT Live outbound profile
oneOf:
  - type: object
    additionalProperties: false
    required: [type, session]
    properties:
      type: {const: session.start}
      session:
        type: object
        additionalProperties: false
        required: [model, instructions, audio]
        properties:
          model: {const: gpt-live-1}
          instructions: {type: string, minLength: 1}
          audio:
            type: object
            additionalProperties: false
            required: [format]
            properties:
              format:
                type: object
                additionalProperties: false
                required: [type, rate]
                properties:
                  type: {const: audio/pcm}
                  rate: {const: 24000}
  - type: object
    additionalProperties: false
    required: [type, audio]
    properties:
      type: {const: session.input_audio.append}
      audio: {type: string, contentEncoding: base64}
  - type: object
    additionalProperties: false
    required: [type, event_id, delegation_id, content]
    properties:
      type: {const: session.instructions.append}
      event_id: {type: string, minLength: 1}
      delegation_id: {type: 'null'}
      content: {type: string, minLength: 1}
  - type: object
    additionalProperties: false
    required: [type]
    properties:
      type: {const: session.close}
```

이 스키마는 서비스 표준24kHz의 기존 앱 송신 계약을 나타낸다. 시험 도구가 명시적으로 다른 표본율을 선택하는 기존 기능을 삭제하지 않는다.

自由대화 초기 instructions는 service-0.1 적용본+최소 이름/나이/관심사 데이터만. 이름10자, 관심사10개×20자, 나이6–12의 기존 상한/이스케이프 유지. 후속 지시는 기존 ACK/직렬 송신/마감 안에서 범위를 닫으며 기존 자유대화 append 500토큰 한도와 검증을 유지한다. 기본 역할 전체를 append에 반복 전송하지 않는다. 기존 연습의 검증된 PracticeInstruction 전용 길이/역할/발화 경로는 바꾸지 않는다. 응답 취소/correct/response.create는 허용되지 않는다. 출력 무효화는 서버 현재 세대와 기기 큐 정리로 한다.

## C5/C6 — 판정·검사 경계 유지

```yaml
$schema: https://json-schema.org/draft/2020-12/schema
title: Existing judgment boundary profile
type: object
additionalProperties: false
required: [request, result]
properties:
  request: {$ref: schemas/ai-judgment-request.schema.json}
  result: {$ref: schemas/ai-judgment-output.schema.json}
```

위 request/result 묶음은 교환 양쪽을 대조하는 검증 fixture 형식이며 실제 공급자에게 wrapper를 보내지 않는다. J1/J6의 기존 요청 필드/선택지/criteria=null, 이름 치환과 최근 턴 상한을 유지한다. 판정 질문 텍스트는 기존 서버 고정 판 자원에서 가져와 J1은 현재 참여, J6는 등록/비등록의 명확한 전체 종료와 인용/화제 거절/모호함을 구분하도록 갱신한다. 새 옵션·새 호출 루프·식별자 전달 없이 실제 어댑터가 조립한 요청/고정 질문을 시험한다. User Stories R-01은 이 시험을 U16 Functional Design/Code Generation/Build and Test에 인계하며 여기서 해결로 처리하지 않는다. 잘못된 응답/선택지 밖/기존4초예산 초과는 기존 보류, 자동 재시도 NONE이다.

```yaml
$schema: https://json-schema.org/draft/2020-12/schema
title: Existing output guard adapter profile
type: object
additionalProperties: false
required: [request, result]
properties:
  request: {$ref: schemas/guardrail-request.schema.json}
  result: {$ref: schemas/guardrail-result.schema.json}
```

이것도 검증 fixture 묶음이다. JEV의 실제 model/state/questions 및 choice/probabilities wire는 ai-io.md §10.12–13과 기존 어댑터 그대로다. `guardrail-request.policyVersion`은 기존 **JEV 정책 버전**이며 새 서비스 프롬프트 버전으로 치환하지 않는다. PolicyBundle의 식별과 JEV 판을 내부에서 구분한다. 7개 범주·확률/동률·ALLOW/BLOCK/REVIEW 검증·기존 호출 마감/큐/재시도 정책을 유지한다. 새 정책의 모든 동작을 JEV가 감지한다고 주장하지 않는다. 후보의 session/mode/generation/목적·음성 대응/정책 연결은 내부에 남기고, ALLOW라도 actor의 현재 상태 재확인 없이는 재생하지 않는다.

## Contract Ownership and Versioning

- C1/C2/공통 스키마/C3 정본과 Java 계약·Core adapter 수정 소유는 U16. U17이 계약 파일을 따로 변경하지 않는다. U17은 프런트 타입/파싱·copy·기기 소비와 자신의 시험을 변경한다.
- 문서 profile 1.1은 이 변경 묶음의 식별이며 `/v2`나 새 wire 버전 협상을 도입하지 않는다. `guardrail.schemaVersion=1`은 새 표시 객체의 형식 버전이고 서비스 프롬프트/JEV/승인 콘텐츠 버전과 서로 다르다.
- 관련 계약 JSON Schema·OpenAPI/AsyncAPI·ai-io/편차 설명과 대조 시험을 같은 변경에서 갱신한다. 엄격한 DTO/스키마는 새 속성을 선언하고, 새 소비자는 unknown/없음/null을 안전하게 처리한다. 필드 추가가 구버전 strict validator에도 안전하다고 가정하지 않는다.
- Q1-A에 따라 서버·화면을 함께 갱신한다. 새 서버+새 UI, 이전 서버 응답+새 UI의 안전 fallback을 대역으로 시험한다. 이전 UI+새 서버는 보장 범위가 아니며 실제 출시 때 별도 호환 확인/동시 전환 계획이 필요하다. 이번 실제 배포는 없다.
- 기존 상태/오류/enum의 삭제·의미 변경은 이 문서에서 허용하지 않는다. 불가피한 breaking 변경이나 새 권한/보존 정책이 발견되면 소유자가 변경 범위/소비 영향과 함께 사람에게 제시한다.

## Coverage and Contract Tests

| 상위 항목 | 계약과 필요한 검증 |
|---|---|
| FR1/NFR5 | C4의 실제 초기 요청/정책 고정·앱 자원/원본·적용본 해시 및 빈 자원 실패. 정책 식별/해시는 서버 내부, 공개 payload 추가 금지 |
| FR2/NFR2 | C3의 누락/오류/만료·조기 발화·현재 완료/참여·중복/지난 결과 검증, C1/C2의 notice/participation 정상·미확인 값 |
| FR3 | C4의 실제 초기·후속 지시 및 범위 닫기/화제 거절/휴식/정정. 정책 상태 상세는 U16 Functional Design |
| FR4 | C3 즉시 중단/늦은 표식·정지 확인, C1/C2 goodbye와 종료 참조의 정확한/없는/불확실 자료, C5 실제 로컬/J6 요청 정책 시험 |
| FR5/NFR1 | C1/C2 접근/가시성·참조 소실/구버전·사본 fallback, 원문/해시/판정/비밀 추가 노출 금지. 사실 안내 문구의 현행 기능 대조는 U16 |
| FR6 | 기존 시간/저장/삭제 보존 회귀. actor 제거·재시작 후 확정 사실/unknown 및 Core fallback 확인 |
| FR7/NFR6 | C4–C6 기존 whitelist·JEV 판/범주·연습 분리·마감·재시도 유지. 새 자동 AI 호출0 |
| NFR3/NFR4 | 대역 계약·요청/서버 상태·파싱/화면 시험과 기능 범위80%/전체 회귀. 실호출/실기기/실아동 성공으로 혼동 금지 |

U16은 형식 검증과 사실 생산/현재성·정리 후 조회 시험을, U17은 응답 조합/없는·미지 enum·stale·null 덮어쓰기/인용 가능한 전사/인사 줄 소비 시험을 소유한다. 최종 통합은 실제 대역 서버→Core→UI 및 기존 기기 WS 대역을 연결한다. 문서만 있는 fixture를 구현 성공으로 보고하지 않는다.

## Assumptions & Open Questions

| Contract | Question | Blocks |
|---|---|---|
| C3 | 제공 패키지에 실제 안내 음성 파일 없음. 기존 승인 자원 갱신 경로·텍스트/음성 버전 일치·실제 자원 확보 방법을 U16 Functional Design에서 확정 | U16 안내 기능 완성/실기기 성공 증거. 계약 의미는 확정, 자원 부재 시 실패 보류 |
| C2 | 기존 Realtime 세션/이벤트에 최소 확정 사실을 남길 물리 필드·transaction·migration 방식은 U16 상세 설계에서 결정 | U16의 구현. U17의 스키마/unknown 소비는 고정 |
| C4–C6 | 실제 모델 행동 품질/실제 아이 청취는 자동 대역으로 보장할 수 없음 | 별도 허가/증거가 필요한 후속 평가. 이번 계약 자동 합격과 구분 |

위 항목은 역할/형식/실패 의미의 미해결이 아니다. 실제 자원/물리 구현과 별도 평가의 책임을 명시한다. 새 저장 정책·외부 공급자·새 권한을 도입할 필요가 생기면 현재 승인 범위를 자동 확장하지 않는다.
