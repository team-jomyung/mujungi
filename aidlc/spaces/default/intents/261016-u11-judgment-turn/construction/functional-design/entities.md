# U11 판정 턴 기반 — 엔티티

> 근거: `functional-design-questions.md`(첫 판 Q1-C · Q1-1-A · Q2-A · Q3-A와 가정 1 ~ 13, 범위 확장 개정 Q4-A · Q5-A · Q6-B · Q6-1-A와 가정 1 ~ 13), `../../inception/requirements-analysis/requirements.md`(2026-10-17 재승인 판, FR1 ~ FR13, NFR1 ~ NFR9), 실호출 보고서(2026-10-16T08:39Z, 수신 사건 모양), `contracts/schemas/ai-judgment-request.schema.json` · `ai-judgment-output.schema.json`, 판 리소스 `judgment/option-sets-v1.json`(판 `judgment-v1`), 코드 지식 베이스 U11-R1 ~ R13.
> 이 문서는 데이터 모양만 적는다. 흐름과 상태 전이는 `functional-spec.md`, 판단 규칙은 `rules.md`가 정본이다. 아래 YAML이 엔티티의 정본이다.

```yaml
entities:
  - name: JudgmentTurn
    description: >-
      판정 한 번의 수명. 실행기 스레드만 읽고 쓰는 메모리 객체이며 저장하지 않는다(판정이 끝나면 JudgmentLiveRecord로 남는다).
      세션당 동시에 하나만 열린다.
    owner: realtime judgment 협력 객체(실행기 스레드 전용)
    attributes:
      - { name: turnId, type: uuid, required: true, unique: true, notes: "늦은 결과 · 전사를 거르는 차례 id(BR5.2)" }
      - { name: sessionId, type: uuid, required: true }
      - { name: point, type: enum, required: true, allowed: [J1, J2, J3, J4, J5, J6] }
      - { name: trigger, type: enum, required: true, allowed: [JOIN_UNCLEAR, STOP_CONFIRM_CANDIDATE, EXTENSION_REQUEST], notes: "J1은 JOIN_UNCLEAR, J6은 STOP_CONFIRM_CANDIDATE, J2 ~ J5는 EXTENSION_REQUEST" }
      - { name: owner, type: enum, required: true, allowed: [EXECUTOR, EXTENSION], notes: "다음 행동을 실행부가 정하는지(J1 · J6) 확장이 정하는지(J2 ~ J5)" }
      - { name: state, type: enum, required: true, allowed: [AWAITING_RESULT, INSTRUCTING, CLOSED], default: AWAITING_RESULT }
      - { name: speechEndAt, type: instant, required: true, notes: "아이 말 끝(관측된 공급자 말 끝 사건, 없으면 확정 전사 시각, BR11.9). 10초 시계 기준(BR4.1)" }
      - { name: openedAt, type: instant, required: true }
      - { name: inputTranscriptSeq, type: integer, required: true, min: 0, notes: "판정 입력이 된 아이 확정 전사의 대화 기록 순번(원문은 담지 않음)" }
      - { name: outputGenerationAtOpen, type: integer, required: true, notes: "판정 턴을 열며 올린 출력 세대 번호(BR3.1 · BR11.5)" }
      - { name: heldOutputTranscript, type: text, required: false, max: 2000, notes: "판정 턴 중 받은 GPT-Live 출력 전사 조각(음성은 버림, BR3.1 · 편차 U11-D2). 2000자를 넘으면 자른다. 대화 기록에는 쓰지 않는다" }
      - { name: liveDeadline, type: instant, required: true, notes: "openedAt + timing.judgment.live-budget(4s, 요구사항 Q10 편차). 이 시각에 닿으면 보류(BR1.4)" }
      - { name: readyDeadline, type: instant, required: true, notes: "speechEndAt + timing.judgment.response-ready(10s, 판정 4초 포함). speechEndAt은 판정 대상 전사에 대응하는 말 끝만 쓴다(BR11.9)" }
      - { name: openedInStatus, type: enum, required: true, allowed: [WAITING_CHILD, ACTIVE], notes: "이 상태를 벗어나면 SESSION_EXITED로 닫는다(BR5.4)" }
      - { name: instructionDeadline, type: instant, required: false, notes: "INSTRUCTING 진입 시각 + response-ready. 끊김 대기와 지시 응답 대기를 합쳐 잰다. 진입 때 readyDeadline · liveDeadline 시계는 푼다(BR4.1 · BR4.5)" }
      - { name: instructedGeneration, type: integer, required: false, notes: "지시를 보낸 세대 번호. InstructionSent 처리 뒤 첫 조각부터 이 세대의 지시 응답이다(BR3.7). 응답 id는 없다" }
      - { name: deferredJoinTick, type: boolean, required: false, default: false, notes: "J1 중 JOIN_WINDOW 만기가 왔는지. 닫힌 뒤 tick을 부른다(BR7.1)" }
      - { name: outcome, type: ref, required: false, references: JudgeOutcome, notes: "결과가 정해지면 채운다" }
    constraints:
      - "세션당 열린(state != CLOSED) 판정 턴은 최대 1개"
      - "turnId가 지금 열린 턴과 다른 결과 · 전사는 버린다(BR5.2)"

  - name: JudgeRequest
    description: 공급자로 보내는 판정 요청(값 객체). contracts/schemas/ai-judgment-request.schema.json 모양을 그대로 따른다.
    attributes:
      - { name: judgmentPoint, type: enum, required: true, allowed: [J1, J2, J3, J4, J5, J6] }
      - { name: optionSetVersion, type: string, required: true, notes: "세션에 고정된 판(judgment-v1)" }
      - { name: options, type: list<string>, required: true, notes: "그 지점의 선택지 코드, 판 리소스 순서 그대로" }
      - { name: childUtterance, type: text, required: true, max: 2000, notes: "이번 판정에 필요한 확정 전사만. 아이 이름은 '아이'로 치환" }
      - { name: recentTurns, type: "list<{speaker: CHILD|PARTNER|COACH, text}>", required: false, max_items: 3 }
      - { name: criteria, type: object, required: false, notes: "J2 · J3 · J5만 객체, J1 · J4 · J6은 null" }
    constraints:
      - "아동 · 보호자 · 세션 · 기기 식별자, 키, 원음, 관심사, 보호자 입력 원문 없음(U9 입력 정리가 다시 확인)"

  - name: JudgeOutcome
    description: 판정 한 번의 결과(값 객체). 확정 · 보류 · 장애 취소 · 중단 중 하나다.
    attributes:
      - { name: kind, type: enum, required: true, allowed: [CONFIRMED, ABSTAINED, FAULT_CANCELLED, STOPPED, SUPERSEDED, SESSION_EXITED] }
      - { name: firstOption, type: string, required: false }
      - { name: firstProbability, type: decimal, required: false, min: 0, max: 1 }
      - { name: secondOption, type: string, required: false }
      - { name: secondProbability, type: decimal, required: false, min: 0, max: 1 }
      - name: abstainReason
        type: enum
        required: false
        allowed: [LOW_CONFIDENCE, LOW_MARGIN, TIMEOUT, PROVIDER_ERROR, SCHEMA_INVALID, OPTION_OUT_OF_SET, DUPLICATE_OPTION, PROBABILITY_OUT_OF_RANGE, NO_PROVIDER, UNREADABLE_INPUT, RESPONSE_NOT_READY]
        notes: "kind = ABSTAINED일 때만"
      - { name: latency, type: duration, required: true }
      - { name: model, type: string, required: false, notes: "공급자가 돌려준 모델 이름. 공급자를 부르지 않았으면 비움" }
      - { name: promptVersion, type: string, required: true }
    constraints:
      - "CONFIRMED이면 first · second가 모두 있고 abstainReason은 없다"
      - "ABSTAINED이면 abstainReason이 있다. 확률이 있어도 확정으로 쓰지 않는다"
      - "FAULT_CANCELLED · STOPPED · SUPERSEDED · SESSION_EXITED는 확률 · 사유를 쓰지 않는다"

  - name: JudgmentLiveRecord
    description: 판정마다 1건 남는 진행용 판정 기록. realtime 스키마 표 realtime_judgment_live(realtime V5, 표 이름은 V1 ~ V4의 realtime_ 접두 관례)에 저장한다. 요구사항의 'judgment_live'는 이 표를 가리킨다. 보호자 화면 · 리포트 · 기록 스냅숏에 쓰지 않는다.
    owner: realtime judgment 저장소
    attributes:
      - { name: id, type: uuid, required: true, unique: true, notes: "= JudgmentTurn.turnId. 같은 id 덮어쓰기로 재시도 멱등" }
      - { name: guardianId, type: uuid, required: true, notes: "삭제 참여자 기준(V4 관례)" }
      - { name: sessionId, type: uuid, required: true, references: realtime session }
      - { name: judgmentPoint, type: enum, required: true, allowed: [J1, J2, J3, J4, J5, J6] }
      - { name: trigger, type: enum, required: true, allowed: [JOIN_UNCLEAR, STOP_CONFIRM_CANDIDATE, EXTENSION_REQUEST] }
      - { name: goalRef, type: uuid, required: false, notes: "연습이면 확장이 준 목표 참조" }
      - { name: sceneRef, type: uuid, required: false }
      - { name: inputTranscriptSeq, type: integer, required: true }
      - { name: optionSetVersion, type: string, required: true }
      - { name: promptVersion, type: string, required: true }
      - { name: model, type: string, required: false }
      - { name: outcome, type: enum, required: true, allowed: [CONFIRMED, ABSTAINED, FAULT_CANCELLED, STOPPED, SUPERSEDED, SESSION_EXITED] }
      - { name: abstainReason, type: enum, required: false, notes: "JudgeOutcome.abstainReason 값" }
      - { name: turnOpened, type: boolean, required: true, notes: "false면 공급자 없음(BR1.6)으로 턴을 열지 않고 남긴 기록" }
      - { name: firstOption, type: string, required: false }
      - { name: firstProbability, type: decimal, required: false, min: 0, max: 1 }
      - { name: secondOption, type: string, required: false }
      - { name: secondProbability, type: decimal, required: false, min: 0, max: 1 }
      - { name: latencyMillis, type: integer, required: true, min: 0 }
      - { name: nextAction, type: enum, required: true, allowed: [NONE, START_JOIN, DECLINE_JOIN, REPROMPT_JOIN, CLOSE, ASK_STOP_CONFIRM, RESUME, HOLD_REASK, REST_ACK_AND_RETURN, REST_ACK_ONLY, DELEGATED_TO_EXTENSION] }
      - { name: heldOutputTranscript, type: text, required: false, max: 2000 }
      - { name: createdAt, type: instant, required: true, notes: "MujoongClock으로 읽는다(R-F6)" }
    constraints:
      - "outcome = CONFIRMED ⇔ firstOption · secondOption이 있음"
      - "outcome = ABSTAINED ⇔ abstainReason이 있음"
      - "guardian_id 색인, session_id 외래 키"
    relationships:
      - { to: realtime session, cardinality: "N:1", direction: "JudgmentLiveRecord → session" }

  - name: NextActionInstruction
    description: 판정 뒤 실행부가 GPT-Live에 넣는 다음 행동 지시(값 객체). 서버 템플릿에서만 만든다.
    attributes:
      - { name: kind, type: enum, required: true, allowed: [HOLD_REASK, REST_ACK_AND_RETURN, REST_ACK_ONLY] }
      - { name: text, type: text, required: true, max: 500, notes: "지금 PracticeInstruction과 같은 한도 · 제어 문자 금지" }
    constraints:
      - "판정 라벨(선택지 코드) · 확률 · 판정 기준 문자열을 담지 않는다(BR3.5)"
      - "아이 발화 원문을 담지 않는다"

  - name: JudgmentSettings
    description: 판정 설정(값 객체). mujoong.judgment.* 와 mujoong.timing.judgment.* 에서 읽는다.
    attributes:
      - { name: minConfidence, type: decimal, required: true, default: 0.6, min: 0, max: 1, notes: "mujoong.judgment.min-confidence. 시간 값 아님" }
      - { name: minMargin, type: decimal, required: true, default: 0.15, min: 0, max: 1, notes: "mujoong.judgment.min-margin" }
      - { name: liveBudget, type: duration, required: true, default: 4s, notes: "mujoong.timing.judgment.live-budget(U8 키, 값은 요구사항 Q10에 따라 2s → 4s. 시간 값 세 곳 함께 고침)" }
      - { name: responseReady, type: duration, required: true, default: 10s, notes: "mujoong.timing.judgment.response-ready(U8)" }

  - name: OutputGeneration
    description: >-
      서버가 GPT-Live 출력 조각을 묶는 세대(응답 id 대체, BR11.5 ~ BR11.7). 실행기 스레드 전용 메모리 객체이며 저장하지 않는다.
      세션당 하나이고 번호는 올리기만 한다.
    owner: realtime 출력 세대 협력 객체(실행기 스레드 전용)
    attributes:
      - { name: generation, type: integer, required: true, min: 0, default: 0 }
      - { name: phase, type: enum, required: true, allowed: [RECEIVING, DISCARDING, GAP_CONFIRMED], default: RECEIVING, notes: "DISCARDING은 세대를 올린 뒤 끊김을 기다리는 중, GAP_CONFIRMED는 끊김을 확인하고 다음 조각(또는 송신 처리)을 기다리는 중" }
      - { name: bumpReason, type: enum, required: false, allowed: [JUDGMENT_TURN, SERVER_SEND, BARGE_IN, STOP_INTENT, PRACTICE_DISCARD, GATE_CORRECTION, HOLD_RELEASED], notes: "마지막으로 세대를 올린 까닭(사유 코드 기록용)" }
      - { name: lastDeltaAt, type: instant, required: false, notes: "마지막 출력 조각(음성 · 전사) 시각. 끊김 판단에 쓴다" }
      - { name: bumpedAt, type: instant, required: false, notes: "마지막으로 세대를 올린 시각. 끊김 기준 ①에 쓴다" }
      - { name: deltaSinceSpeechEnd, type: boolean, required: true, default: false, notes: "이번 아이 말 끝 이후 출력 조각이 한 번이라도 왔는지. 거짓이면 끊김 기준 ②에 자동 응답 시작 대기를 더한다(BR11.7)" }
      - { name: permitSeq, type: integer, required: true, min: 0, default: 0, notes: "끊김 허가 번호. 허가 뒤 송신 전 조각이 오면 올려 허가를 무효로 한다. 실행기가 volatile 값으로 직렬 작업에 내보낸다" }
      - { name: pendingSend, type: boolean, required: true, default: false, notes: "끊김 뒤 보낼 지시 · 응답 요청이 직렬 경로에 걸려 있는지" }
      - { name: sendProcessed, type: boolean, required: true, default: false, notes: "직렬 경로의 송신 완료(InstructionSent)를 실행기가 처리했는지" }
      - { name: gapWaitDeadline, type: instant, required: false, notes: "끊김 대기 상한(판정 턴은 instructionDeadline, 그 밖은 올린 시각 + response-ready). 넘으면 송신하지 않음(OUTPUT_GAP_NOT_OBSERVED)" }
      - { name: correctedThisGeneration, type: boolean, required: true, default: false, notes: "게이트 교정 지시를 이 세대에 이미 보냈는지(교정은 세대당 한 번)" }
    constraints:
      - "DISCARDING · GAP_CONFIRMED에서 온 조각은 송신 처리 전이면 버린다(BR3.4 · BR3.7). GAP_CONFIRMED에서 송신 전 조각이 오면 DISCARDING으로 돌아가고 permitSeq를 올린다"
      - "outputHeld()가 참이면 단계와 관계없이 버리고 lastDeltaAt만 갱신한다(BR11.13)"
      - "SEGMENT_QUIET 만기가 DISCARDING에서 오면 GAP_CONFIRMED, RECEIVING에서 오면 열린 구간을 '완전'으로 봉인(BR11.6)"
      - "세대가 바뀌면 열린 구간은 '불완전'으로 봉인해 버린다"

  - name: ProviderSessionStart
    description: 공급자 세션 시작 메시지(값 객체, BR11.1). 팩토리 한 곳에서만 만든다.
    attributes:
      - { name: type, type: string, required: true, allowed: ["session.start"] }
      - { name: session.model, type: string, required: true, notes: "지금 상수 gpt-live-1" }
      - { name: session.instructions, type: text, required: true, notes: "서버 템플릿. 아이 이름 별칭 · 나이 · 관심사를 문장으로 담는다(childContext 필드 없음)" }
      - { name: session.audio.format.type, type: string, required: true, allowed: ["audio/pcm"] }
      - { name: session.audio.format.rate, type: integer, required: true, allowed: [24000, 16000], notes: "TTS 실호출에서 24000 수락이면 24000, 아니면 16000 + 서버 변환(BR11.8)" }
    constraints:
      - "delegation 필드를 넣지 않는다"
      - "출처: 실호출 관측(2026-10-16, 16kHz 수락 확인)"

  - name: ProviderInboundEvent
    description: 공급자에서 받아 해석하는 사건 종류(값 객체, BR11.3). 응답 id 필드는 없다.
    attributes:
      - name: kind
        type: enum
        required: true
        allowed: [STARTED, OUTPUT_AUDIO_DELTA, USAGE_UPDATED, ERROR, CLOSED, OBSERVED_OTHER]
        notes: "OBSERVED_OTHER는 TTS 실호출에서 관측된 전사 · 말 끝 · 응답 경계 사건을 관측된 이름으로 더할 자리. 관측 전에는 비어 있다"
      - { name: audio, type: bytes, required: false, notes: "OUTPUT_AUDIO_DELTA의 delta(base64)를 디코드한 값" }
      - { name: errorCode, type: string, required: false, notes: "ERROR의 error.code. message 원문은 남기지 않는다(R-F14)" }
      - { name: usageSeconds, type: integer, required: false, notes: "USAGE_UPDATED의 usage.seconds" }

  - name: LunaActivation
    description: realtime Luna 실제 어댑터가 켜지는지(값 객체, BR12.3). core-api는 이 값과 무관하게 키만 본다.
    attributes:
      - { name: keyPresent, type: boolean, required: true, notes: "env OPENAI_API_KEY가 비어 있지 않고 공백뿐이 아님" }
      - { name: enabled, type: boolean, required: true, default: false, notes: "mujoong.luna.enabled. 시간 값 아님" }
      - { name: state, type: enum, required: true, allowed: [ENABLED, DISABLED_NO_KEY, DISABLED_SWITCH_OFF], notes: "LUNA_STARTUP lunaState 기록 값. 키가 없으면 스위치와 관계없이 DISABLED_NO_KEY" }

  - name: JudgmentExtensionEvent
    description: 확장점으로 넘기는 판정 관련 사건. 기존 레코드 PracticeSessionExtension.ExtensionEvent(Kind kind, String name, UUID correlationId, Object value)를 그대로 쓰고 Kind 값만 더한다.
    attributes:
      - name: kind
        type: enum
        required: true
        allowed: [JUDGMENT_RESULT, JUDGMENT_ABSTAINED, HELD_OUTPUT_TRANSCRIPT, GUARDRAIL_BLOCKED, REST_REQUESTED]
        notes: "GUARDRAIL_BLOCKED는 자리만(U13이 내보냄)"
      - { name: name, type: string, required: true, notes: "판정 사건은 \"judgment\", 휴식 신호는 \"rest\"" }
      - { name: correlationId, type: uuid, required: true, notes: "requestJudgment 때 확장이 준 UUID. 실행부가 연 턴의 휴식 신호면 turnId" }
      - { name: value, type: object, required: false, notes: "결과: {point, firstOption, secondOption}(확률 없음) / 보류: {point, abstainReason}(장애면 FAULT) / 전사: {text}" }

  - name: JudgmentRequestCapability
    description: 확장이 부르는 판정 요청 능력 PracticeOutput.requestJudgment의 인자(값 객체).
    attributes:
      - { name: point, type: enum, required: true, allowed: [J2, J3, J4, J5], notes: "J1 · J6은 실행부만 연다" }
      - { name: transcriptSeq, type: integer, required: true, notes: "판정 대상 아이 확정 전사의 순번. 실행부가 세션 메모리의 최근 확정 전사(최대 4개)에서 원문을 찾는다" }
      - { name: correlationId, type: uuid, required: true }
      - { name: criteria, type: object, required: false, notes: "{coreFunction, judgmentCriteria}. J2 · J3 · J5만, 확장이 목표에서 만든 값 그대로" }
    constraints:
      - "반환은 boolean(받음/거절). ACTIVE가 아니거나 판정 턴이 이미 열려 있거나 transcriptSeq가 최근 전사에 없으면 거절"
```

## 요약

- **메모리에만 있는 것**: `JudgmentTurn`(판정 한 번의 수명) · `OutputGeneration`(응답 id를 대신하는 출력 세대)은 실행기 스레드 전용 객체이고, `JudgeRequest` · `JudgeOutcome` · `NextActionInstruction` · `JudgmentSettings` · `LunaActivation`은 값 객체다.
- **공급자 선 형식**: `ProviderSessionStart`(중첩 모양 세션 시작) · `ProviderInboundEvent`(해석하는 사건 종류). 실호출에서 관측된 것만 담고, 전사 · 말 끝 · 응답 경계 사건은 TTS 실호출 뒤 더한다.
- **저장하는 것**: `JudgmentLiveRecord` 하나. realtime V5 표 `judgment_live`이고 판정마다 1건, 보호자 쪽에 나가지 않는다. 선 형식 정비(`OutputGeneration` · `ProviderSessionStart` · `ProviderInboundEvent`)와 `LunaActivation`은 저장하는 것이 없다.
- **확장점으로 나가는 것**: `JudgmentExtensionEvent` 다섯 종류. 확장에는 확률을 주지 않고 선택지 코드만 준다(지시문에 확률이 섞일 길을 없앤다).
- 원래 요구사항 FR6.4의 `D_REST_ACK` 재생은 Q1-C에 따라 `NextActionInstruction`의 `REST_ACK_AND_RETURN` · `REST_ACK_ONLY` 지시로 대신한다(편차 U11-D1, `functional-spec.md` 8절).
