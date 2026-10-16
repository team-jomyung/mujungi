# U9 Luna 호출부 — 엔티티

> U9는 DB 표를 만들지 않는다. 아래는 호출부가 다루는 설정 · 요청 · 결과 · 기록의 논리 모델이다. 두 앱(core · realtime)이 같은 모양을 각자 갖고, 코드는 나누지 않는다(ADR-004 · R-F5).
> 근거: `functional-design-questions.md`(Q1-A · Q2-B · Q3-A · Q4-A + 실호출 요구, 가정 1 ~ 12 · 3-1 수락), `contracts/ai-io.md` 10절, U8 `functional-spec.md` W3.

```yaml
entities:
  - name: LunaSettings
    description: 앱 기동 때 한 번 정해지는 Luna 호출 설정. 앱마다 하나.
    attributes:
      - { name: apiKeyPresent, type: boolean, required: true, constraints: "env OPENAI_API_KEY가 비어 있지 않고 공백뿐이 아니면 true. 값 자체는 이 엔티티에 담지 않는다" }
      - { name: enabled, type: boolean, required: true, constraints: "apiKeyPresent와 같다(Q2-B). R-F15 확인은 RUNBOOK 절차" }
      - { name: model, type: string, required: true, default: gpt-6-luna, constraints: "core = env OPENAI_ANALYSIS_MODEL, realtime = env OPENAI_JUDGE_MODEL. 비면 기본값" }
      - { name: baseUrl, type: uri, required: true, default: "https://api.openai.com/v1", constraints: "demo · prod 프로필: https 스킴 + 허용 호스트(api.openai.com)만. test 프로필: 루프백만" }
      - { name: store, type: boolean, required: true, allowed: [false], constraints: "공급자 저장 끔. 바꿀 수 없다" }
      - { name: purposeOptions, type: map<LunaPurpose, PurposeOptions>, required: true }
    relationships:
      - { to: PurposeOptions, cardinality: "1:N", direction: owns }

  - name: PurposeOptions
    description: 목적별 출력 한도 · 추론 강도. 시간 값이 아니다.
    attributes:
      - { name: maxOutputTokens, type: integer, required: true, default: 4000, min: 1, constraints: "조원 하네스 시작 값. 완료 보장이 아니다" }
      - { name: reasoningEffort, type: enum, required: false, allowed: [none, minimal, low, medium, high], default: medium }

  - name: LunaPurpose
    description: 호출 목적. 재시도 분류 · 시한 키 · 스키마를 정한다(ai-io.md 10.3).
    attributes:
      - { name: code, type: enum, required: true, unique: true, allowed: [JUDGMENT_LIVE, SCENARIO_GENERATION, SCENARIO_REVIEW, SEED_GENERATION, RESULT_TEXT, GOAL_CANDIDATES, REJUDGMENT] }
      - { name: app, type: enum, required: true, allowed: [CORE, REALTIME], constraints: "JUDGMENT_LIVE만 REALTIME" }
      - { name: retryClass, type: enum, required: true, allowed: [NONE, REQUEST_BOUNDED, JOB_ATTEMPT] }
      - { name: outputSchema, type: string, required: false, constraints: "contracts/schemas/ai-*-output.schema.json 파일 이름. 기존 인터페이스 경로(LEGACY_TEXT_AI · LEGACY_RESULT)는 비어 있고 JSON 객체 출력만 요청" }
      - { name: requestSchema, type: string, required: false, constraints: "JUDGMENT_LIVE = ai-judgment-request.schema.json" }
    allowed_rows:
      - { code: JUDGMENT_LIVE, app: REALTIME, retryClass: NONE, outputSchema: ai-judgment-output.schema.json, requestSchema: ai-judgment-request.schema.json }
      - { code: SCENARIO_GENERATION, app: CORE, retryClass: REQUEST_BOUNDED, outputSchema: "ai-scenario-output.schema.json (U10 전환 뒤). 지금 TextAiProvider 경로는 비어 있음" }
      - { code: SCENARIO_REVIEW, app: CORE, retryClass: REQUEST_BOUNDED, outputSchema: ai-scenario-review-output.schema.json }
      - { code: SEED_GENERATION, app: CORE, retryClass: REQUEST_BOUNDED, outputSchema: ai-seed-output.schema.json }
      - { code: RESULT_TEXT, app: CORE, retryClass: JOB_ATTEMPT, outputSchema: "비어 있음(기존 ResultAiSchema가 검증)" }
      - { code: GOAL_CANDIDATES, app: CORE, retryClass: JOB_ATTEMPT, outputSchema: "ai-goal-candidate-output.schema.json (U14 전환 뒤)" }
      - { code: REJUDGMENT, app: CORE, retryClass: JOB_ATTEMPT, outputSchema: "ai-rejudgment-output.schema.json (U14 전환 뒤)" }

  - name: LunaRequest
    description: 호출자가 호출부에 넘기는 요청 한 건. toString은 [redacted].
    attributes:
      - { name: purpose, type: LunaPurpose, required: true }
      - { name: promptVersion, type: string, required: true, constraints: "호출자가 정한 판. 스키마 판과 함께 올린다(Contract Q2-A)" }
      - { name: instructions, type: text, required: true, constraints: "버전 파일에서 읽은 시스템 지시. 입력 데이터와 섞지 않는다(C §9 데이터/지시 경계)" }
      - { name: input, type: json-object, required: true, constraints: "보내기 전 SanitizedInput으로 바뀐다" }
      - { name: aliases, type: map<string,string>, required: false, constraints: "아이 표시 이름 → '아이'. realtime은 비어 있음" }
      - { name: deadline, type: instant, required: true, constraints: "호출자 마감. MujoongClock 기준" }

  - name: SanitizedInput
    description: 공급자로 실제 나가는 입력. 금지 키 제거 · 이름 치환 · 연락처 가림 뒤의 값. UUID 참조화는 호출자(ResultTextGateway)가 이미 한 상태로 들어온다(검토 R-01).
    attributes:
      - { name: payload, type: json-object, required: true }
      - { name: sentFieldNames, type: list<string>, required: true, constraints: "payload 최상위 키 이름. 감사 로그에 쓴다" }

  - name: OutputSchemaPair
    description: 출력 스키마가 있는 목적마다 기동 때 한 번 만드는 스키마 짝(검토 R-02).
    attributes:
      - { name: contractSchema, type: json-schema, required: true, constraints: "contracts/schemas/ai-*-output.schema.json 원본. 응답 검증에 쓴다" }
      - { name: requestSchema, type: json-schema, required: true, constraints: "원본에서 공급자 허용 키워드만 남긴 사본. 요청 text.format에 싣는다" }
      - { name: strict, type: boolean, required: true, constraints: "requestSchema의 모든 객체가 additionalProperties false · 모든 속성 required면 true" }

  - name: LunaResult
    description: 성공 결과. toString은 [redacted].
    attributes:
      - { name: outputJson, type: json, required: true, constraints: "스키마 검증(있을 때)을 통과한 응답 본문" }
      - { name: responseModel, type: string, required: true, constraints: "공급자 응답의 model 값. 비면 실패" }
      - { name: promptVersion, type: string, required: true, constraints: "요청 때 보낸 값" }
      - { name: latency, type: duration, required: true, constraints: "마지막 성공 시도의 지연" }
      - { name: attemptsUsed, type: integer, required: true, min: 1, max: 3 }
      - { name: schemaRetried, type: boolean, required: true, constraints: "스키마 불일치 재요청을 썼는지. U10이 내용 재생성 예산에 센다" }

  - name: LunaFailure
    description: 실패 결과. 본문 · 원문을 담지 않는다.
    attributes:
      - { name: reason, type: enum, required: true, allowed: [AUTH_FAILED, RATE_LIMITED, SERVER_ERROR, REQUEST_REJECTED, TRANSPORT_ERROR, TIMEOUT, EMPTY_OUTPUT, LENGTH_EXCEEDED, REFUSED, SCHEMA_INVALID, FORBIDDEN_INPUT, MODEL_MISSING] }
      - { name: transient, type: boolean, required: true, constraints: "TRANSPORT_ERROR · RATE_LIMITED · SERVER_ERROR · EMPTY_OUTPUT만 true" }
      - { name: attemptsUsed, type: integer, required: true, min: 0, max: 3 }

  - name: LunaCallAudit
    description: 시도마다 한 줄 남기는 감사 기록(EventLog LUNA_CALL). 본문 · 이름 · 전사 없음.
    attributes:
      - { name: purpose, type: LunaPurpose, required: true }
      - { name: model, type: string, required: true, constraints: "요청 모델. 응답 모델이 다르면 응답 모델도" }
      - { name: promptVersion, type: string, required: true }
      - { name: fieldNames, type: list<string>, required: true, constraints: "SanitizedInput.sentFieldNames" }
      - { name: outcome, type: enum, required: true, allowed: [OK, AUTH_FAILED, RATE_LIMITED, SERVER_ERROR, REQUEST_REJECTED, TRANSPORT_ERROR, TIMEOUT, EMPTY_OUTPUT, LENGTH_EXCEEDED, REFUSED, SCHEMA_INVALID, FORBIDDEN_INPUT, MODEL_MISSING] }
      - { name: latencyMs, type: integer, required: true, min: 0 }
      - { name: attempt, type: integer, required: true, min: 1, max: 3 }

  - name: LiveTestRun
    description: 키를 넣고 사람이 돌리는 실호출 시험 한 번(가정 3-1). 기본 빌드 · CI에서 돌지 않는다.
    attributes:
      - { name: purposeCases, type: list<LunaPurpose>, required: true, constraints: "목적마다 합성 자료 1건 이상" }
      - { name: outcome, type: enum, required: true, allowed: [PASSED, FAILED, SKIPPED_NO_KEY] }
      - { name: evidenceKind, type: enum, required: true, allowed: [REAL_API_SYNTHETIC], constraints: "R-M11: 대역 · 합성 · 실제 API · 실기기 증거를 나눔" }
```

## 요약

- **설정(`LunaSettings` · `PurposeOptions`):** 키가 있으면 켜진다(Q2-B). 모델 · 주소 · 출력 한도 · 추론 강도는 설정값이고 시간 값이 아니다. 공급자 저장(`store`)은 언제나 끈다.
- **목적(`LunaPurpose`):** 일곱 목적이 재시도 분류 · 스키마를 정한다. 기존 인터페이스(`TextAiProvider` · `ResultTextProvider`)를 거치는 목적은 지금 검증(`TextAiGateway` · `ResultAiSchema`)을 그대로 쓰고, 새 스키마 파일은 각 단위(U10 · U11 · U14 · U15)가 전환할 때 붙는다.
- **스키마 짝(`OutputSchemaPair`):** 요청에는 공급자가 받는 키워드만 남긴 사본을, 응답 검증에는 원본 계약 스키마를 쓴다.
- **요청 · 결과(`LunaRequest` · `SanitizedInput` · `LunaResult` · `LunaFailure`):** 보내기 전 정리된 입력만 나가고, 결과에는 응답 모델 · 프롬프트 판 · 지연 · 쓴 시도 수가 붙는다. 실패는 사유 코드만 갖는다.
- **기록(`LunaCallAudit` · `LiveTestRun`):** 시도마다 감사 한 줄, 실호출 시험은 '실제 API · 합성 자료' 증거로 따로 남는다.
