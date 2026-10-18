# U12 새 연습 흐름 — 엔터티 모델

> 작업 기록 `261017-u12-practice-flow` · Functional Design · 2026-10-18 · 질문 답과 요약 확인 반영. 기술 중립 설계다. 클래스 · 표 이름은 Code Generation이 정한다(아래 이름은 안내용 별칭).
> 이 작업 기록은 유닛 나누기를 건너뛰었으므로(refactor) 기존 코드 구조(realtime-activity)를 사실상의 도메인 설계로 본다.

```yaml
entities:
  - name: PracticeStartPackage
    description: 시작 패키지의 practice 조각을 읽은 값. 세션당 하나, 읽기 전용. realtime이 원문을 보존하고 확장이 해석한다.
    attributes:
      - {name: goalSelectionId, type: uuid, required: true, constraints: "U14 goalRef로 그대로 쓴다"}
      - {name: goals, type: list<GoalSpec>, required: true, min: 1}
      - {name: firstScenario, type: ScenarioScript, required: false, constraints: "첫 목표 전용. 없으면 레거시 분기 또는 시작 실패 처리"}
      - {name: scriptVersion, type: string, required: false, allowed: ["scenario-script.v1"], constraints: "있으면 새 흐름, 없으면 레거시"}
    relationships:
      - {to: GoalSpec, cardinality: "1..*", direction: owns}
  - name: GoalSpec
    description: 연습 목표 한 개. 시작 패키지에는 표시 이름과 판정 기준 문자열 하나만 온다.
    attributes:
      - {name: goalIndex, type: int, required: true, min: 0}
      - {name: goalName, type: string, required: true}
      - {name: judgmentCriteria, type: string, required: true, constraints: "판정 기준 coreFunction의 출처(열린 질문 2의 결정)"}
    relationships:
      - {to: ScenarioScript, cardinality: "1..1", direction: "uses (첫 목표는 패키지, 나머지는 내부 호출로 받음)"}
  - name: ScenarioScript
    description: U10 대본. 새 읽기 타입이 소비하며 레거시 PracticeScenarioClient.Scenario와 별개다.
    attributes:
      - {name: scenarioId, type: uuid, required: true, constraints: "scenarioRef로 쓴다"}
      - {name: scriptVersion, type: string, required: true}
      - {name: scenes, type: list<Scene>, required: true, min: 1}
      - {name: selfReview, type: object, required: false, constraints: "누락 시 빈 값으로 정규화"}
      - {name: generation, type: object, required: false, constraints: "누락 시 빈 값으로 정규화"}
    relationships:
      - {to: Scene, cardinality: "1..*", direction: owns}
  - name: Scene
    description: 대본 안 장면. 원 장면(BASE)과 변형(VARIANT)이 있다.
    attributes:
      - {name: sceneKind, type: enum, required: true, allowed: [BASE, VARIANT]}
      - {name: situationText, type: string, required: true, constraints: "승인 원문. 로그 · 기록에 남기지 않는다"}
      - {name: opportunityLine, type: string, required: true, constraints: "기회 문장"}
      - {name: reexplainLine, type: string, required: true, constraints: "상황 다시 설명"}
    relationships: []
  - name: SceneRun
    description: 장면을 실제로 시작해 진행한 한 번의 실행. 기록 V7 표의 한 행이다.
    attributes:
      - {name: sceneRunId, type: uuid, required: true, unique: true, constraints: "realtime이 만든다"}
      - {name: sessionId, type: uuid, required: true}
      - {name: goalRef, type: uuid, required: true, constraints: "goalSelectionId"}
      - {name: goalIndex, type: int, required: true}
      - {name: scenarioRef, type: uuid, required: true}
      - {name: sceneKind, type: enum, required: true, allowed: [BASE, VARIANT]}
      - {name: startedAt, type: instant, required: true, constraints: "장면을 실제로 시작할 때(AC1.1.1 뒤)만 기록. 시작 전 미참여 · 미실시는 행 없음"}
      - {name: endedAt, type: instant, required: false}
      - {name: liveTiming, type: enum, required: false, allowed: [IMMEDIATE, AFTER_REASK, AFTER_HELP, NONE, ABSTAINED]}
      - {name: variantSkipReason, type: enum, required: false, allowed: [NO_VARIANT, DECLINED, TIME_LIMIT, FOCUS_LIMIT, END_OR_REST]}
      - {name: retryCount, type: int, required: true, default: 0, min: 0}
      - {name: reexplainCount, type: int, required: true, default: 0, min: 0}
    relationships:
      - {to: PracticeFact, cardinality: "1..*", direction: owns}
      - {to: HelpRecord, cardinality: "0..*", direction: owns}
  - name: PracticeFact
    description: 장면에서 일어난 사실 하나. 계약 15종만 쓴다(원문 없음).
    attributes:
      - {name: kind, type: enum, required: true, allowed: [REASK_RESOLVED, SITUATION_EXPLAINED, FOCUS_HELP, EXPRESSION_EXAMPLE, HELP_ACCEPTED, HELP_DECLINED, COACH_SWITCH, COACH_RETURN, ORIGINAL_SCENE_RETURN, VARIANT_PLAYED, VARIANT_SKIPPED, END_REQUEST, TIMEOUT, TRANSCRIPT_FAILED, PLAYBACK_FAILED]}
      - {name: at, type: instant, required: true}
    relationships: []
  - name: HelpRecord
    description: 도움 · 역할 전환 기록 한 건. 분류 코드와 길이만 저장한다(원문 금지, U12-D2).
    attributes:
      - {name: kind, type: enum, required: true, allowed: [ROLE_SWITCH, ROLE_RETURN, FOCUS_HELP, EXPRESSION_EXAMPLE, SITUATION_EXPLAIN]}
      - {name: role, type: enum, required: true, allowed: [PEER, COACH]}
      - {name: exampleGiven, type: boolean, required: true, default: false}
      - {name: consentAnswer, type: enum, required: false, allowed: [ACCEPT, DECLINE, UNCLEAR, REQUESTED]}
      - {name: contentChars, type: int, required: true, min: 0, constraints: "도움 발화 길이만. 내용 없음"}
      - {name: at, type: instant, required: true}
    relationships: []
  - name: FlowState
    description: 세션 메모리 안의 연습 흐름 상태(영속하지 않음). 상태 기계 협력 객체가 소유하고 실행부는 모른다.
    attributes:
      - {name: phase, type: enum, required: true, allowed: [SCENE_INTRO, OPPORTUNITY, REASK, HELP_OFFER, FOCUS, RETRY, SCENE_WRAP, VARIANT_OFFER, NEXT_GOAL, CLOSING], constraints: "참여 확인 단계는 실행부 소유라 없다. 변형 장면은 sceneKind VARIANT로 같은 단계를 다시 돈다"}
      - {name: role, type: enum, required: true, allowed: [PEER, COACH], default: PEER}
      - {name: goalIndex, type: int, required: true}
      - {name: goalActiveStartedAt, type: instant, required: true, constraints: "목표 ACTIVE 10분 계산 기준"}
      - {name: focusStartedAt, type: instant, required: false}
      - {name: retryCount, type: int, required: true, default: 0}
      - {name: reexplainCount, type: int, required: true, default: 0}
      - {name: noResponseGuided, type: boolean, required: true, default: false, constraints: "단계가 바뀔 때마다 거짓으로 되돌림"}
      - {name: unreadableGuided, type: boolean, required: true, default: false, constraints: "UNREADABLE · 보류 재안내 1회 표지, 단계가 바뀔 때 되돌림"}
      - {name: handledSlotIds, type: set<uuid>, required: true, constraints: "이미 종료 사건을 처리했거나 확장이 cancelSpeech로 스스로 닫은 슬롯 correlationId(중복 · 늦은 종료 사건 무시)"}
      - {name: focusLimitReached, type: boolean, required: true, default: false, constraints: "focus-max 시계 만료 표지, 한도 판단 기준(Instant 차이로 판단하지 않는다)"}
      - {name: goalLimitReached, type: boolean, required: true, default: false, constraints: "goal-limit 시계 만료 표지"}
      - {name: judgmentOpen, type: boolean, required: true, default: false, constraints: "열린 판정 중에는 no-response 시계를 걸지 않는다"}
    relationships:
      - {to: SceneRun, cardinality: "0..1", direction: "현재 장면 실행"}
  - name: GeneratedUtteranceSlot
    description: 확장이 허용한 GPT-Live 역할 발화 한 건의 실행부 쪽 표지. 승인 발화 칸(pendingPracticeSpeech)에 PracticeSpeech의 생성형 변형(generated=참)으로 들어간다. text는 서버가 만든 지시문 템플릿이며 아이 발화 · 모델 출력 원문이 아니고 원문 일치(matches)는 언제나 거짓이다.
    attributes:
      - {name: correlationId, type: uuid, required: true}
      - {name: partnerRole, type: enum, required: true, allowed: [PEER, COACH], constraints: "제브 모드 PRACTICE_PEER / PRACTICE_COACH로 대응"}
      - {name: generated, type: boolean, required: true, default: true}
      - {name: scenarioId, type: uuid, required: true, constraints: "현재 장면의 scenarioRef(PracticeSpeech 필수 성분)"}
      - {name: outputKind, type: enum, required: true, allowed: [SCENARIO, HELP_QUESTION, CUE], constraints: "재생 증거 표지. 친구 발화 · 변형 제안 · 복귀 알림=SCENARIO, 도움 제안=HELP_QUESTION, 코치 도움 · 전환 알림=CUE"}
      - {name: instructionText, type: string, required: true, constraints: "지시문 템플릿 문장, 저장 · 로그 금지, 확장은 길이만 본다"}
    relationships: []
```

## 요약

- **영속하는 것**은 `SceneRun` · `PracticeFact` · `HelpRecord` 셋이고 V7의 새 표 두 개에 담긴다(장면 실행+사실 한 표, 도움 · 역할 전환 한 표). 둘 다 보호자 삭제에 참여하고 정산 복구가 `practice` 값을 다시 만든다.
- **메모리에만 있는 것**은 `FlowState`와 `GeneratedUtteranceSlot`이다. 세션이 끝나면 사라지며 정산 때 `SceneRun` 행에서 기록을 다시 만든다.
- 도움 · 전사 · 대본 원문은 어떤 엔터티에도 영속 칸이 없다(R-F14, U12-D2). 원문은 확장 메모리 안 판정 · 제브 호출 동안만 존재한다.
- 레거시 V4 표 5개는 건드리지 않는다. 새 표는 레거시 표를 참조하지 않는다.
