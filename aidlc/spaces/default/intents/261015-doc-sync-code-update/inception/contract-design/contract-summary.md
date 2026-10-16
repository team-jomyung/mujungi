# 계약 요약 — 문서 기반 코드 갱신

> 근거: `../units-generation/unit-of-work.md` · `../units-generation/unit-of-work-dependency.md`(단위 U8~U15, 통합 지점 7곳), `../domain-design/components.md`(엔티티 · 간선), `../domain-design/decisions.md`(ADR-004 · 005 · 006 · 007), `../requirements-analysis/requirements.md`(FR1~FR12 · NFR1~NFR7), 질문 · 답 `contract-design-questions.md`(Q1-A · Q2-A · 가정 1~8 수락).
> 계약 정본 규칙: `contracts/README.md`(제공 단위 소유 · 소비 단위 검토, 더하기만 안전, 바뀌면 `deviations.md` 한 행).
> 이 문서는 무엇을 어떤 모양으로 주고받는지를 정한다. 아래 YAML · 스키마 블록은 계약의 뼈대이고, 실제 파일(`contracts/…`)은 각 소유 단위의 Code Generation에서 이 뼈대대로 쓴다. 필드 형식 · 제약의 세부는 Functional Design이 채우되, 이 문서의 필드 이름 · 의미 · 실패 규칙을 바꾸려면 이 문서와 `deviations.md`를 함께 고친다.

## 1. 계약 목록

| # | Provider Unit | Consumer | Mechanism | Owner |
|---|---|---|---|---|
| C-DS1 | U10 상황 생성 | U12 새 연습 흐름 | 내부 HTTP `POST /internal/practice/scenarios`(동기, 기존 경로에 필드 더하기) | U10 |
| C-DS2 | U11 판정 턴 기반(발행) · U12(사실 제공) | U14 목표 · 결과 | 영속 이벤트 `SessionRecordCaptured`(Outbox/Inbox, `record.practice` 더하기) | U11 (필드 모양은 U8 공통 계약) |
| C-DS3 | U11 판정 턴 기반 | U12 새 연습 흐름(구현) | 앱 안 Java 포트 `PracticeSessionExtension` · `PracticeOutput` 확장(Q1-A) | U11 |
| C-DS4 | U9 Luna 호출부(realtime) | U11 판정 턴 기반 | 앱 안 포트 `JudgeClient` + 외부 API 구조화 출력 | U11(포트) · U8(스키마) |
| C-DS5 | U9 Luna 호출부(core) | U10 상황 생성 | 외부 API 구조화 출력(생성 · 자체 검토) | U8(스키마) · U10(프롬프트) |
| C-DS6 | U9 Luna 호출부(core) | U14 목표 · 결과 | 외부 API 구조화 출력(목표 후보 · 기록용 재판정 · 결과 문장) | U8(스키마) · U14(프롬프트) |
| C-DS7 | U9 Luna 호출부(core) | U15 지식 그래프 | 외부 API 구조화 출력(새 씨앗) | U8(스키마) · U15(프롬프트) |
| C-DS8 | External: 제브(Jev) API | U13 제브 게이트 | 외부 HTTPS(동기) + 앱 안 포트 `GuardrailClient`, 내부 형식 고정 · 어댑터 매핑 | U8(내부 스키마) · U13(어댑터) |
| C-DS9 | U8 계약 · 버전 리소스 | U11 · U12 · U13 | 공유 자원 스키마(버전 리소스 파일) + 앱 안 포트 `ApprovedContentCatalog` | U8 |
| C-DS10 | U14 목표 · 결과 | U10 상황 생성 | 앱 안 Java 포트 `SavedGoalReader`(core) | U14 |
| C-DS11 | U15 지식 그래프 | U10 · U14 | 앱 안 Java 포트 `KnowledgeGraphLookup` 확장(core) | U15 |
| C-DS12 | U14 목표 · 결과 | External: 보호자 웹(`/parent`) | 보호자 REST(`parent-api.yaml`, 기존 경로에 값 · 필드 더하기) | U14 |
| C-DS13 | U8 계약 · 버전 리소스 | 전 단위 · 프런트 | 설정 계약: `mujoong.timing.*` 세 곳(R-M5) | U8 |
| C-DS14 | U8 계약 · 버전 리소스 | 전 단위 · CI | 오류 코드(`ErrorCode` ↔ `errors.md`) · 비밀 이름 목록(R-M6 · R-M9) | U8 |
| C-DS15 | U11 판정 턴 기반 | External: GPT-Live | 외부 WebSocket(서버 중계, 지금 선 형식 유지, ADR-007) | U11 |

- 새로 여는 공개 API는 없다(가정 5). 외부 경계 C-DS4~C-DS8 · C-DS15는 우리가 **소비하는** 공급자 경계다. 공급자 형식은 어댑터 안에서만 내부 형식으로 바꾼다.
- 삭제 이벤트(`DeletionRequested` · `DeletionCompleted`)는 계약을 바꾸지 않는다. 새 개인 자료를 가진 단위가 기존 삭제 참여자를 넓힐 뿐이다. 다만 `DeletionCompleted.counts`에 새 항목을 더할지는 Functional Design에서 정한다(열린 질문 6).

## 2. 계약별 스펙

### C-DS1 대본 조회 (U10 → U12)

기존 `contracts/internal-api.yaml`의 `/internal/practice/scenarios`와 `schemas/practice-scenario.schema.json`에 **선택 필드를 더한다**. 기존 필수 필드와 `kind`(ORIGINAL · NEW_SCENARIO)는 그대로 둬 레거시 U5b 경로가 계속 읽는다. 새 흐름의 대본은 `scriptVersion`이 있는 응답으로 구분한다.

```yaml
# contracts/schemas/practice-scenario.schema.json 에 더하는 속성 (모두 선택, additionalProperties: true 유지)
properties:
  scriptVersion: { type: string, description: "새 흐름 대본 판. 없으면 레거시 U5b 대본" }
  scenes:
    type: array
    minItems: 1
    items:
      type: object
      required: [sceneKey, sceneKind, sceneDescription, partnerRole, opportunitySentence, reexplanation, relation, place, topic]
      properties:
        sceneKey: { type: string }
        sceneKind: { enum: [BASE, VARIANT] }
        sceneDescription: { type: string }
        partnerRole: { type: string }
        opportunitySentence: { type: string }
        reexplanation: { type: string, description: "상황 다시 설명(FR4.3)" }
        focusPoints: { type: array, items: { type: string } }
        expressionExamples: { type: array, items: { type: string }, description: "코치 역할 · 동의 뒤에만 쓴다(FR4.4)" }
        relation: { type: string }
        place: { type: string }
        topic: { type: string }
  selfReview:
    type: object
    required: [verdict, promptVersion]
    properties:
      verdict: { const: APPROVE, description: "APPROVE만 발급된다(FR2.2)" }
      promptVersion: { type: string }
  generation:
    type: object
    properties:
      model: { type: string }
      promptVersion: { type: string }
      graphContextUsed: { type: boolean, description: "빈 그래프 응답이면 false(FR9.5)" }
      kgVersion: { type: [string, "null"] }
```

- **실패:** 생성 · 검토 · 서버 검사가 15초 안에 승인 대본을 못 만들면 기존 `SCENARIO_GEN_FAILED` 처리로 끝난다(FR2.3). 반려된 대본은 응답에 싣지 않는다.
- **시간:** 기존 `mujoong.timing.practice.scenario-request-timeout`(15초)을 따른다. 늘리지 않는다(FR2.5).
- **금지:** 응답에 Luna 원문 응답 · 자체 검토 사유 원문 · 아동 식별자 외 개인 정보를 싣지 않는다.

### C-DS2 기록 캡처 이벤트 (U11 발행 · U12 사실 제공 → U14)

`SessionRecordCaptured`의 봉투 · 필수 필드는 그대로다. 발행은 지금처럼 세션 실행부(U11)가 한다. U12는 기존 확장점 `practiceRecord` 시점에 아래 `record.practice` 값을 넘긴다(가정 3). 새 형식은 `snapshotVersion`을 올려 구분한다(FR7.5). 봉투 `schemaVersion`은 올리지 않는다(더하기만).

```yaml
# AsyncAPI 2.6.0 components.messages.SessionRecordCaptured.payload.record.practice (새 형식, snapshotVersion >= 2)
practice:
  type: object
  required: [flowVersion, scenes]
  properties:
    flowVersion: { const: "practice-conversation-v1" }
    scenes:
      type: array
      description: "실제로 SCENE_INTRO를 재생한 장면만(US4.2)"
      items:
        type: object
        required: [sceneRunId, goalRef, sceneKind, startedAt]
        properties:
          sceneRunId: { type: string, format: uuid }
          goalRef: { type: string, format: uuid }
          scenarioRef: { type: string, format: uuid }
          sceneKind: { enum: [BASE, VARIANT] }
          startedAt: { type: string, format: date-time }
          liveOutcome:
            type: object
            description: "진행 중 판정 요약. 라벨 · 확률 원값은 싣지 않는다(FR3.6 · FR3.7)"
            properties:
              timing: { enum: [IMMEDIATE, AFTER_REASK, AFTER_HELP, NONE, ABSTAINED] }
          facts:
            type: array
            description: "FR7.3 기록 사실"
            items:
              enum: [REASK_RESOLVED, SITUATION_EXPLAINED, FOCUS_HELP, EXPRESSION_EXAMPLE, HELP_ACCEPTED, HELP_DECLINED,
                     COACH_SWITCH, COACH_RETURN, ORIGINAL_SCENE_RETURN, VARIANT_PLAYED, VARIANT_SKIPPED,
                     END_REQUEST, TIMEOUT, TRANSCRIPT_FAILED, PLAYBACK_FAILED]
          variantSkipReason: { type: [string, "null"], enum: [NO_VARIANT, DECLINED, TIME_LIMIT, FOCUS_LIMIT, END_OR_REST, null] }
```

- **받는 쪽(U14):** `snapshotVersion`이 1이면 지금 U5b 형식으로 읽는다(US4.3). 모르는 `facts` 값은 무시한다.
- **노출:** 이 이벤트는 core 안에서만 쓴다. 보호자 화면 · 리포트에는 `liveOutcome`을 직접 보이지 않는다. `differs_from_live` 판단에만 쓴다(FR7.4, Domain Design 지적 R-07 해소).
- **전달 뒤:** 지금처럼 대화 원문을 담은 payload는 전달 뒤 비운다.

### C-DS3 연습 확장점 (U11 ↔ U12, Q1-A)

지금 `PracticeSessionExtension`의 능력 호출 모델을 유지한다. 포트 · 능력 · 신호 타입은 U11이 소유하고 U12는 구현만 한다. 빌드 의존은 U12 → U11 한 방향이다(Units Generation 지적 R-01 해소). ADR-005의 '다음 행동 지시를 돌려준다'는 '정해진 능력만 호출하고 상태 변화는 신호로만 알린다'로 읽는다.

```yaml
# 공유 스키마(앱 안 Java 포트) — realtime kr.mujoong.realtime.session
PracticeSessionExtension:
  callbacks: [onActive, onChildUtterance, onExtensionEvent, onSettle, practiceRecord]   # 그대로
  returns: Signal { NONE, NEXT_GOAL, LAST_GOAL }                                         # 그대로
PracticeOutput:
  existing: [playApproved, instructProvider, requestResponse, cancelResponse, holdOutput, schedule, cancel, post, speak, cancelSpeech]
  added:
    requestJudgment: { args: [judgmentPoint, transcriptRef, criteriaRef], result: "비동기 결과는 onExtensionEvent로" }
    announceRoleSwitch: { args: [toRole], note: "승인 문구 코드로 재생 또는 지시. 제브가 막으면 역할을 바꾸지 않음(AC1.5.6)" }
    setThinking: { args: [on], note: "보낼 응답이 없을 때 기기 '생각 중' 유지(AC1.10.4)" }
  forbidden: [상태 전이 직접 호출, DB 쓰기, 기기 원시 명령, 제브 우회 송신, 판정 라벨 · 확률을 지시문에 넣기]
ExtensionEvent:
  added: [JUDGMENT_RESULT, JUDGMENT_ABSTAINED, HELD_OUTPUT_TRANSCRIPT, GUARDRAIL_BLOCKED]
```

- **CLOSING · 정산 창:** 지금처럼 능력 호출을 거절하고 `PRACTICE_EXTENSION_FAILED{reason: NOT_ACTIVE}`로 남긴다.
- **버린 출력 전사:** 판정 턴에 게이트가 버린 출력 전사는 `HELD_OUTPUT_TRANSCRIPT` 사건으로 U12에 전달된다. U11의 `LiveJudgment`가 `judgment_live`에 쓴다(Domain Design 지적 R-04 해소).
- **레거시:** 기존 U5b 확장 구현은 이 포트를 그대로 쓰고, 더한 능력은 기본 구현(`false` 반환)으로 둬 깨지지 않게 한다.

### C-DS4 진행 중 판정 (U9 realtime 호출부 → U11)

```yaml
# 앱 안 포트
JudgeClient:
  judge: { args: [JudgeRequest], returns: "CompletionStage<JudgeResult>", timeout: "mujoong.timing.judgment.live-budget (2s)", retry: none }
# contracts/schemas/ai-judgment-request.schema.json (공급자로 보내는 필드 — 최소 필드만, NFR3)
JudgeRequest:
  required: [judgmentPoint, optionSetVersion, options, childUtterance, criteria]
  properties:
    judgmentPoint: { enum: [J1, J2, J3, J4, J5, J6] }
    optionSetVersion: { type: string }
    options: { type: array, items: { type: string } }
    childUtterance: { type: string, description: "이번 판정에 필요한 전사만. 아이 이름은 '아이'로 치환" }
    recentTurns: { type: array, maxItems: 3, items: { type: object, properties: { speaker: { enum: [CHILD, PARTNER, COACH] }, text: { type: string } } } }
    criteria: { type: object, description: "담은 목표 4요소에서 만든 판정 기준(AC4.6.3). J1 · J4 · J6은 null" }
# contracts/schemas/ai-judgment-output.schema.json (구조화 출력)
JudgeResult:
  required: [first, second]
  properties:
    first: { type: object, required: [option, probability], properties: { option: { type: string }, probability: { type: number, minimum: 0, maximum: 1 } } }
    second: { type: object, required: [option, probability], properties: { option: { type: string }, probability: { type: number, minimum: 0, maximum: 1 } } }
```

- **보류:** 1순위 확률 < 하한, 두 순위 차이 < 최소 차이, 시간 초과, 형식 오류, 판독 불가 입력이면 보류다. 하한 · 차이는 설정값(`mujoong.judgment.min-confidence` · `mujoong.judgment.min-margin`)이고 시간 값이 아니다(FR3.3).
- **금지 필드:** 아동 · 보호자 · 세션 식별자, 키, 원음, 보호자 입력 원문.

### C-DS5 상황 생성 · 자체 검토 (U9 core 호출부 → U10)

```yaml
# contracts/schemas/ai-scenario-output.schema.json — 생성 응답(구조화 출력). 필드는 C-DS1 scenes와 같은 모양
ScenarioOutput:
  required: [scenes]
  properties:
    scenes: { $ref: "practice-scenario.schema.json#/properties/scenes" }
# contracts/schemas/ai-scenario-review-output.schema.json — 생성과 분리된 별도 요청의 응답(FR2.1)
ScenarioReview:
  required: [verdict, reasons]
  properties:
    verdict: { enum: [APPROVE, REJECT, UNCERTAIN] }
    reasons:
      type: array
      items: { enum: [ANSWER_INCLUDED, EARLY_CUE, PRESSURE, AGE_MISMATCH, GOAL_MISMATCH, REPETITION, OTHER_APPROVED_REASON] }
```

- **실패:** 스키마 불일치는 1회 다시 요청, 그래도 실패하면 작업 실패. `REJECT` · `UNCERTAIN` · 누락 · 시간 초과는 승인이 아니다. `APPROVE`여도 서버 검사 실패면 막는다(FR2.2).
- **금지:** 생성물 · 검토 결과는 제브로 보내지 않는다(FR2.6). 입력에는 담은 목표 4요소 · 관심사 · (있으면) 구조 맥락만 싣는다.
- 승인 사유 목록의 최종 값은 U10 Functional Design에서 정하고 이 스키마에 더한다.

### C-DS6 목표 후보 · 재판정 · 결과 문장 (U9 core 호출부 → U14)

```yaml
# contracts/schemas/ai-goal-candidate-output.schema.json — ai-io.md 6.8(U6 초안)을 대체
GoalCandidateOutput:
  required: [candidates]
  properties:
    candidates:
      type: array
      maxItems: 3                     # AC4.5.7 기본값, 설정 mujoong.goal.max-candidates
      items:
        required: [displayName, targetExpression, fourElements, skillRef, reasonText, evidence, displayEvidence]
        properties:
          displayName: { type: string, maxLength: 12 }
          targetExpression: { type: string }
          fourElements: { type: object, required: [situation, partner, intent, expression] }
          skillRef: { type: string, description: "씨앗 요약 목록의 id 또는 NONE(FR8.2)" }
          reasonText: { type: string }
          evidence: { type: array, minItems: 1, items: { required: [utteranceSeq, quote], properties: { utteranceSeq: { type: integer }, quote: { type: string } } } }
          displayEvidence: { type: string, maxLength: 40 }
# contracts/schemas/ai-rejudgment-output.schema.json — ai-io.md 6.2를 새 시점에 맞춤(FR7.4)
RejudgmentOutput:
  properties:
    scenes: { type: array, items: { required: [sceneRunId, value, timing], properties: { value: { enum: [functional, partial, none, uncertain] }, timing: { enum: [IMMEDIATE, AFTER_REASK, AFTER_HELP] } } } }
```

- 4요소 속성 이름(`situation` · `partner` · `intent` · `expression`)은 B §9.6 표현을 영어 이름으로 옮긴 제안이다. U14 Functional Design에서 확정한다(열린 질문 2).
- 결과 문장은 기존 `ai-io.md` 6.3 · `ResultAiSchema` 형식을 유지한다. 2차 검사는 `classify` · `OutputChecker`이고 제브로 보내지 않는다(FR7.7).
- 재시도 정책은 열린 질문 1에서 정한다(조원 하네스: 자동 재시도 없음 대 FR1.2: 스키마 1회 재요청).

### C-DS7 새 씨앗 (U9 core 호출부 → U15)

```yaml
# contracts/schemas/ai-seed-output.schema.json
SeedOutput:
  required: [skillName, invariantSentence]
  properties:
    skillName: { type: string }
    invariantSentence: { type: string, description: "길이 한도는 A §6.4. 아동 전사와 5글자 이상 겹치면 서버가 버린다" }
```

- **실패:** 검사 실패면 1회 다시, 또 실패하면 `skill_ref=NONE` 유지. 결과 상태는 바꾸지 않는다(FR8.3).

### C-DS8 제브 가드레일 (External Jev → U13)

내부 형식은 고정하고, 공급자 실제 응답은 어댑터에서만 바꾼다(FR6.2). 공급자 실제 필드는 실호출로 확정한다(요구사항 열린 항목 3).

```yaml
# 앱 안 포트
GuardrailClient:
  check: { args: [GuardrailRequest], returns: "CompletionStage<GuardrailResult>", timeout: "mujoong.timing.guardrail.jev-budget (800ms)", retry: none }
# contracts/schemas/guardrail-request.schema.json (공급자로 보내는 필드)
GuardrailRequest:
  required: [source, mode, candidate, policyVersion]
  properties:
    source: { const: GPT_LIVE_GENERATED }
    mode: { enum: [FREE_TALK, PRACTICE_PEER, PRACTICE_COACH] }
    candidate: { type: string }
    recentContext: { type: array, maxItems: 3, items: { type: object, properties: { speaker: { type: string }, text: { type: string } } }, description: "익명화. 지시문은 데이터로만 다룬다(FR6.7)" }
    policyVersion: { type: string }
# contracts/schemas/guardrail-result.schema.json (어댑터가 만드는 내부 형식)
GuardrailResult:
  required: [decision, categories, reasonCode, policyVersion]
  properties:
    decision: { enum: [ALLOW, BLOCK, REVIEW] }
    categories: { type: object, description: "7개 분류(C §3.1). 이름은 C를 그대로 따른다" }
    policyFlags: { type: array, items: { type: string } }
    reasonCode: { type: string }
    policyVersion: { type: string }
```

- **실패는 막힘:** `BLOCK` · `REVIEW` · 800ms 초과 · 연결 실패 · 잘못된 JSON · 정책 버전 불일치면 송신하지 않는다. 실검사 모드에서 자동 `ALLOW`는 없다(FR6.4).
- **주소 · 키:** 주소와 고정 모델 버전은 설정 기본값(`jev-latest` 금지), 키는 `JEV_API_KEY`. 실제 값은 저장소에 쓰지 않는다(R-F3).
- **기록:** `guardrail_event`에 이유 코드 · 정책 버전 · 지연 · 판정만 남긴다(FR6.8).

### C-DS9 승인 콘텐츠 · 버전 리소스 (U8 → U11 · U12 · U13)

```yaml
# 공유 자원 스키마 — realtime 자원 approved-content/*.yml 각 파일의 머리
ContentResource:
  required: [resourceKey, kind, version, source]
  properties:
    resourceKey: { type: string }
    kind: { enum: [JUDGMENT_OPTION_SET, GUARDRAIL_PROMPT, GUARDRAIL_CATEGORIES, APPROVED_PHRASE] }
    version: { type: string }
    source: { type: string, description: "근거 문서 절(A §7.3 · C §3.3 · B §8.5 등)" }
    text: { type: [string, "null"] }
    options: { type: [array, "null"], items: { type: string } }
# 앱 안 포트
ApprovedContentCatalog:
  pinForSession: { args: [sessionId], returns: SessionContentPlan, note: "세션 시작 때 한 번. 세션 동안 바뀌지 않는다" }
  registerSessionScript: { args: [sessionId, scenarioRef, scriptVersion, sceneTexts], note: "U12가 대본을 받은 직후 등록(Domain Design 지적 R-03 해소)" }
  matchApproved: { args: [sessionId, outputText], returns: "MatchResult{matched, source, version}", rule: "공백 제거 뒤 완전 일치만(AC2.2.1)" }
SessionContentPlan:
  required: [sessionId, judgmentSetVersion, guardrailPolicyVersion, approvedPhraseVersion]
  properties:
    scriptVersion: { type: [string, "null"], description: "대본 등록 때 채워진다" }
```

- **출처 확인:** `matchApproved`가 출처 · 원문을 확인하지 못하면 제브 생략을 허용하지 않는다(AC2.2.3).

### C-DS10 담은 목표 읽기 (U14 → U10, core 앱 안)

```yaml
SavedGoalReader:
  find: { args: [childId, selectionId], returns: "SavedGoalView | empty" }
SavedGoalView:
  required: [selectionId, childId, kind]
  properties:
    kind: { enum: [CANDIDATE, LEGACY_BEHAVIOR] }
    candidateRef: { type: [string, "null"] }
    candidateVersion: { type: [integer, "null"] }
    targetExpression: { type: [string, "null"] }
    fourElements: { type: [object, "null"] }
    skillRef: { type: [string, "null"] }
    legacyBehaviorKey: { type: [string, "null"] }
    legacyBehaviorVersion: { type: [integer, "null"] }
```

- 과거 행동 정의 기반 목표는 `LEGACY_BEHAVIOR`로 계속 읽힌다(AC4.6.4).

### C-DS11 지식 그래프 조회 (U15 → U10 · U14, core 앱 안)

```yaml
KnowledgeGraphLookup:   # 기존 인터페이스에 더한다
  seedSummaries: { args: [childId], returns: "List<SeedSummary>", timeout: "mujoong.timing.knowledge.graph-budget (2s)" }
  skillContext: { args: [childId, skillRef], returns: "SkillContext | empty" }
  variantPlan: { args: [childId, skillRef, baseScene], returns: "VariantPlan | empty" }
  writeGeneratedSeed: { args: [childId, SeedDraft], returns: seedId }
  deleteChild: { args: [childId], returns: "DeletionOutcome{DONE, NOT_APPLICABLE, RETRY}" }
rules:
  - "모든 질의는 childId로 범위 한정(FR9.1)"
  - "꺼짐 · 장애 · 2초 초과 · skillRef=NONE 이면 empty. 빈 응답을 개인화 완료로 표시하지 않는다(FR9.5)"
  - "원문 전사 · 보호자 입력 원문은 쓰지 않는다(FR9.3)"
```

### C-DS12 보호자 REST (U14 → 보호자 웹)

기존 `parent-api.yaml`의 결과 · 목표 후보 경로에 **값 · 선택 필드만 더한다**. 경로는 늘리지 않는다.

```yaml
# parent-api.yaml 더하기
SceneResult.timing:
  enum_add: [AFTER_REASK]          # API · 리포트 집계에 그대로(ADR-003)
SceneResult.chip:
  note: "27 표시용 칩은 서버가 AFTER_REASK → DONE_IMMEDIATE로 접어 보낸다. 칩 enum은 바뀌지 않는다(Q3-A)"
GoalCandidateView:
  properties_add:
    displayEvidence: { type: string, description: "27 '근거 · …' 문구(AC4.5.6). 기존 근거 필드가 있으면 그 이름을 따른다" }
  internal_only: [fourElements, skillRef, reasonText]   # 보호자 응답에 싣지 않는다(AC4.5.5)
```

- 받는 쪽(프런트)은 모르는 `timing` 값을 '알 수 없음'으로 처리한다(README 규칙). 27이 쓰는 칩 값은 바뀌지 않으므로 27 화면은 그대로다.
- 리포트 집계는 `timing`을 쓰고 칩을 쓰지 않게 고친다. 지금 칩 매퍼가 저장 · 27 · 리포트에 함께 쓰이는 문제(Domain Design 지적 R-01)는 U14 Functional Design에서 27 전용 매핑으로 나눈다.

### C-DS13 시간 값 (U8 → 전 단위)

`mujoong.timing.*` · `contracts/timing.md` · `frontend/src/shared/timing/timing-defaults.json`에 같은 커밋으로 더한다(R-M5). 키 이름은 아래를 정본으로 하고, 값은 설정으로 바꿀 수 있다(NFR1).

| 키 | 기본값 | 쓰는 단위 | 근거 |
|---|---|---|---|
| `mujoong.timing.judgment.live-budget` | `2s` | U11 | FR3.4 |
| `mujoong.timing.judgment.response-ready` | `10s` | U11 | FR5.3 |
| `mujoong.timing.guardrail.jev-budget` | `800ms` | U13 | FR6.2 |
| `mujoong.timing.knowledge.graph-budget` | `2s` | U15 | FR9.5 |
| `mujoong.timing.practice.no-response-wait` | `10s` | U12 | AC1.9.1 |
| `mujoong.timing.practice.focus-max` | `90s` | U12 | AC1.5.4 |
| `mujoong.timing.practice.goal-active-limit` | `10m` | U12 | FR4.5 |
| `mujoong.timing.result.retry-delays` | `5s, 15s, 30s` | U14 | FR7.6 |

- 기존 U5b 키(`first-response-wait` · `judge-budget` 등)는 레거시 경로용으로 남긴다.
- 집중 연습 다시 해 보기 횟수(2회) · 후보 수(3개) · 글 길이는 시간 값이 아니므로 각 기능 설정 네임스페이스에 둔다.

### C-DS14 오류 코드 · 비밀 이름 (U8 → 전 단위 · CI)

```yaml
secrets_add: [OPENAI_API_KEY, JEV_API_KEY, NEO4J_URI, NEO4J_USERNAME, NEO4J_PASSWORD]
secrets_rule: "같은 커밋에서 비밀 이름 검사 목록 전부에 넣는다(R-M9). PROVIDER_API_KEY → OPENAI_API_KEY 통일은 FR12.2에서 정한다"
error_codes_add: []   # 새 HTTP 오류 code는 지금 없다. 생기면 ErrorCode + errors.md 같은 커밋(R-M6)
startup_failure: "demo · prod 제브 실검사 꺼짐 · 키 없음은 HTTP 오류가 아니라 기동 실패 + 로그 사유(US2.3)"
```

### C-DS15 GPT-Live 지시 주입 (U11 → External GPT-Live)

```yaml
# contracts/ai-io.md 3절 선 형식(J110 가정)을 그대로 쓴다
instruction_injection:
  - { type: session.instructions.correct, body: "다음 행동 지시. 판정 라벨 · 확률 · 기준 금지(FR3.7)" }
  - { type: session.response.create }
status: "FR12.2 실호출 전까지 바꾸지 않는다. 실제 형식은 조원 하네스(POST /v1/live/sessions · /attach)를 참고해 어댑터 안에서만 매핑(ADR-007)"
```

## 3. 계약 소유 · 변경 규칙

- **소유:** 표의 Owner 단위 담당이 스펙을 고치고, 소비 단위 담당이 검토한다. U8 공통 계약(시간 값 · 오류 코드 · 비밀 이름 · AI 입출력 스키마 · 버전 리소스 형식)은 U8이 먼저 만들고, 각 단위는 더하기만 한다.
- **더하기만 안전:** 새 필드 · 새 enum 값 · 새 사건 종류는 바로 머지한다. 받는 쪽은 모르는 필드를 무시하고 모르는 값은 '알 수 없음'으로 처리한다. 경로 버전은 두지 않는다.
- **깨는 변경:** 필드 삭제 · 이름 변경 · 의미 변경 · 필수화는 제공 · 소비 담당이 함께 고치고 같은 날 양쪽을 머지한다. 이벤트는 `snapshotVersion`(기록 형식) 또는 봉투 `schemaVersion`(봉투 자체)을 올린다.
- **표시:** 바뀌는 곳마다 `contracts/deviations.md`에 한 행을 더하고 스펙에 `x-deviation` 또는 `[편차 (기호)]`로 표시한다. `ContractFilesTest`가 강제한다.
- **AI 스키마 정본(Q2-A):** Luna · 제브 구조화 입출력은 `contracts/schemas/ai-*.schema.json` · `guardrail-*.schema.json`이 정본이다. 코드는 같은 파일로 공급자에 구조화 출력을 요청하고, `ai-io.md`는 규칙 · 금지 필드 · 실패 처리만 적는다. 프롬프트 판(`promptVersion`)과 스키마 판은 함께 올린다.
- **계약 대조 시험:** 계약을 고치려고 대조 시험을 고치거나 건너뛰지 않는다(R-F2). 설계 문서 변경이 계약에 닿으면 같은 작업에서 `contracts/`를 고친다(R-M10).

## 4. 열린 질문

| Contract | Question | Blocks |
|---|---|---|
| C-DS6 · C-DS5 | Luna 재시도: 조원 하네스(자동 재시도 없음, 길이 초과 · 빈 응답은 실패) 대 FR1.2(스키마 불일치 1회 재요청) | U9 · U10 · U14 |
| C-DS6 | 4요소 속성 이름(`situation` · `partner` · `intent` · `expression`)과 B §9.6 대응 확정 | U14 |
| C-DS5 | 자체 검토 승인 사유 목록 최종 값(J7 항목 포함) | U10 |
| C-DS8 | 제브 실제 응답 필드 · 7개 분류 이름(계정 승인 뒤 API 문서, 실호출) | U13 |
| C-DS15 | GPT-Live 서버 쪽 전송 가능 여부와 실제 선 형식(FR12.2) | U11 |
| C-DS2 | `DeletionCompleted.counts`에 판정 · 가드레일 · 그래프 항목을 더할지 | U11 · U13 · U15 |
| C-DS12 | 보호자 목표 후보 응답의 기존 근거 필드 이름과 `displayEvidence`의 관계 | U14 |

## Sources

- `../units-generation/unit-of-work.md`, `../units-generation/unit-of-work-dependency.md`
- `../domain-design/components.md`, `../domain-design/decisions.md`
- `../requirements-analysis/requirements.md`, `../user-stories/stories.md`
- `contract-design-questions.md`(Q1-A · Q2-A · 가정 1~8)
- 계약 정본: `contracts/README.md` · `ai-io.md`(3 · 6.2 · 6.8절) · `events.asyncapi.yaml` · `internal-api.yaml` · `timing.md` · `schemas/practice-scenario.schema.json` · `schemas/event-payloads.schema.json`
- 코드: `realtime/session/PracticeSessionExtension.java`(콜백 5개 · `PracticeOutput` 능력 · `Signal`)
- 조원 하네스 `aidlc/spaces/default/knowledge/documents/MUJUNG_SERVICE_HANDOFF_SPRING_2026-10-15.zip`(`config/luna_analysis_settings.json`, `spring/…/OpenAiLiveGateway.java`)

## Assumptions & Open Questions

- 위 스펙 블록의 속성 이름 · enum 값 중 정본(A · B · C)에 이름이 없는 것은 이 단계의 제안이다. 각 소유 단위의 Functional Design이 확정하되, 바꾸면 이 문서와 `deviations.md`를 함께 고친다.
- 열린 질문은 4절 표에 있다. 어느 것도 단위 경계나 의존 방향을 바꾸지 않는다.
