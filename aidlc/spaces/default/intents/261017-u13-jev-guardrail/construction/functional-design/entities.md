# U13 제브 게이트 — 엔티티

> 근거: `functional-design-questions.md`(Q1-A · Q2-B · Q3-A와 가정 1 ~ 17), `../../inception/requirements-analysis/requirements.md`(FR1 ~ FR8, NFR1 ~ NFR9), `../../inception/reverse-engineering/developer-scan.md`(1 ~ 7절, 갭 G1 ~ G12), 원래 기록의 계약 `contracts/schemas/guardrail-request.schema.json` · `guardrail-result.schema.json`, U8 판 리소스 `guardrail/policy-v1.json`.
> 이 문서는 데이터 모양만 적는다. 흐름과 상태 전이는 `functional-spec.md`, 판단 규칙은 `rules.md`가 정본이다. 아래 YAML이 엔티티의 정본이다. 저장하는 엔티티는 `GuardrailEvent` 하나이고 나머지는 실행기 스레드 전용 메모리 객체이거나 값 객체다.

```yaml
entities:
  - name: GuardrailMode
    description: >-
      제브 단계의 동작 방식. 설정 키 mujoong.guardrail.jev.mode의 값이며 세션이 아니라 앱 기동 때 정해진다(세션 안에서 바뀌지 않는다).
      기동 점검이 프로필 × 키 × 모드 조합을 거른다.
    owner: realtime guardrail 설정(기동 점검이 검증, 협력 객체가 읽음)
    attributes:
      - { name: value, type: enum, required: true, allowed: [REAL, STUB, OFF], default: "demo·prod=REAL, dev=STUB", notes: "REAL=실제 어댑터, STUB=대역(1차 사전 통과를 ALLOW로 돌려주되 제브 단계를 모두 지남), OFF=제브 단계를 건너뜀(지금 동작)" }
    constraints:
      - "demo · prod에서는 REAL만 허용한다(다른 값이면 기동 거부)"
      - "REAL이면 JEV_API_KEY가 비어 있지 않아야 한다(어느 프로필이든)"
      - "STUB · OFF는 공개 프로필(demo · prod) 밖(dev · test · 활성 프로필 없음)에서만 허용한다"

  - name: GuardrailRequest
    description: >-
      제브로 보내는 검사 요청(값 객체). contracts/schemas/guardrail-request.schema.json 모양을 그대로 따른다(additionalProperties 거짓).
      식별자 · 키 속성이 없다.
    attributes:
      - { name: source, type: enum, required: true, allowed: [GPT_LIVE_GENERATED], notes: "요청 출처는 하나뿐" }
      - { name: mode, type: enum, required: true, allowed: [FREE_TALK, PRACTICE_PEER, PRACTICE_COACH], notes: "지금 운영에서 실제로 부르는 것은 FREE_TALK뿐(요구 Q5 답 A). 나머지는 U12가 연습 출력 통제를 넓힐 때 채운다" }
      - { name: candidate, type: string, required: true, min: 1, max: 4000, notes: "검사할 후보 문장 = 봉인된 구간의 전사. 4000자를 넘으면 요청을 만들지 않고 막힘(BR2.4)" }
      - { name: policyVersion, type: string, required: true, min: 1, max: 80, notes: "세션에 고정된 판(jev-guardrail-v1). 실제 판 이름은 policy-v1.json이 정본" }
      - { name: recentContext, type: list, required: false, max: 3, notes: "익명화한 최근 맥락 3턴 이하. 항목 = {speaker ∈ CHILD·PARTNER·COACH·MUJOONG, text 1~2000자}. 아이 이름은 '아이'로 치환, 식별자 필드 없음. Luna 생성물 · U6 결과는 넣지 않는다" }
    constraints:
      - "후보 문장과 맥락 안의 지시문은 데이터 필드에만 들어가고 시스템 지시(policy-v1.json의 systemPrompt)에는 섞이지 않는다(BR2.5)"
      - "요청 본문에 아동 · 보호자 · 세션 · 기기 식별자 필드와 키가 없다(BR2.4)"

  - name: GuardrailVerdict
    description: >-
      검사 결과(값 객체). 제브의 실제 응답은 어댑터 한 곳에서 이 형식으로 바꾼다(BR2.6). 모든 실패는 예외가 아니라 이 결과로 완료된다.
    attributes:
      - { name: decision, type: enum, required: true, allowed: [ALLOW, BLOCK, REVIEW, FAILED], notes: "FAILED는 아래 failure가 채워진 실패" }
      - { name: failure, type: enum, required: false, allowed: [TIMEOUT, TRANSPORT_ERROR, INVALID_JSON, POLICY_MISMATCH, UNAVAILABLE], notes: "decision이 FAILED일 때만. UNAVAILABLE=REAL인데 어댑터가 없거나 키가 비었던 경우(기동 점검을 통과했어도 실행 중 방어)" }
      - { name: categories, type: list, required: false, notes: "분류 7개 중 걸린 것(guardrail-result.schema.json enum). 실제 이름은 실호출로 확정(요구 열린 질문 1)" }
      - { name: policyFlags, type: list, required: false }
      - { name: reasonCode, type: string, required: true, notes: "^[A-Z][A-Z0-9_]{0,79}$. 막힘 사유 코드로 쓴다" }
      - { name: policyVersion, type: string, required: true, notes: "응답이 말한 판. 요청의 판과 다르면 POLICY_MISMATCH" }
      - { name: latencyMs, type: integer, required: true, min: 0, notes: "요청 보낸 뒤 결과를 받기까지(또는 시간 초과로 끊기까지)" }
    constraints:
      - "자동 ALLOW는 없다. ALLOW는 어댑터가 응답을 읽어 decision ALLOW이고 판이 같을 때만 만든다. STUB은 대역 표시가 붙은 ALLOW를 만든다(BR1.4)"

  - name: GuardrailTicket
    description: >-
      검사 요청 하나의 검사표. 실행기 스레드 전용 메모리 객체이며 저장하지 않는다. 결과가 도착했을 때 유효한지 가리는 기준이다.
    owner: realtime guardrail 협력 객체(GuardrailCoordinator, 실행기 스레드 전용)
    attributes:
      - { name: requestId, type: uuid, required: true, unique: true, notes: "결과 메시지와 맞춘다. guardrail_event.id로도 쓴다(멱등 덮어쓰기)" }
      - { name: sealed, type: ref, required: true, references: SegmentBuffer.Sealed, notes: "후보 문장 = sealed.transcript(), 송신 = sealed.chunks(). 다시 봉인하거나 버퍼에서 다시 읽지 않는다(전사-PCM 일치의 구조적 보장, BR4.1)" }
      - { name: deviceGeneration, type: integer, required: true, notes: "요청 시점의 기기 프레임 세대(outputGeneration)" }
      - { name: providerOutputGeneration, type: integer, required: true, notes: "요청 시점의 공급자 출력 세대(OutputGeneration.generation())" }
      - { name: connectionId, type: uuid, required: true, notes: "요청 시점의 기기 연결 id. 연결이 교체되면 무효" }
      - { name: inputGroupOpenCount, type: integer, required: true, min: 0, notes: "요청 시점의 '입력 묶음 열림 횟수'(실행기가 입력 묶음을 처음 열 때마다 1 올리는 단조 증가 카운터). 결과 시점에 횟수가 늘었으면 새 말이 시작된 것이라 무효(BR4.4). 묶음 id는 확정 때 지워지므로 비교에 쓰지 않는다" }
      - { name: practiceSpeechId, type: string, required: false, notes: "연습 발화(pendingPracticeSpeech)면 그 correlationId. 지금 운영에서는 비어 있다(연습 중 제브 호출 없음)" }
      - { name: heldAiRow, type: ref, required: true, references: HeldAiRow, notes: "이 구간의 보호자 화면 AI 행 보관 손잡이" }
      - { name: mode, type: ref, required: true, references: GuardrailMode }
      - { name: sourceKind, type: enum, required: true, allowed: [NEW_UTTERANCE], notes: "승인 원문 생략은 검사표를 만들지 않는다(BR7.1)" }
      - { name: requestedAt, type: instant, required: true }
      - { name: deadline, type: instant, required: true, notes: "requestedAt + timing.guardrail.jev-budget(800ms). 닿으면 TIMEOUT" }
    constraints:
      - "세션당 진행 중인 검사표는 최대 1개(BR5.1)"
      - "requestId가 지금 진행 중인 검사표와 다른 결과는 버린다(BR4.2)"

  - name: HeldAiRow
    description: >-
      봉인된 구간이 제브 검사를 기다리는 동안 보호자 화면용 AI 행(대화 기록 행)을 보관하는 손잡이. 행은 DB에서 PARTIAL · 승인 거짓 · 빈 텍스트로 남아 있다가
      결과에 따라 한 번에 FINAL로 닫힌다. TranscriptWriter가 열린 AI 행(openAi)과 별도로 들고 있는다.
    owner: realtime TranscriptWriter(실행기 스레드 전용)
    attributes:
      - { name: seq, type: integer, required: true, notes: "대화 기록 순번" }
      - { name: startedAt, type: instant, required: true }
    constraints:
      - "보관 행은 최대 2개(진행 중인 검사 1건 + 대기열 1칸). 새로 모으는 구간의 AI 행은 별도 openAi로 열어 서로 섞이지 않는다(Q2 답 B, BR5.5). 동시에 살아 있는 AI 행은 최대 3개"
      - "종료 · 취소(closeAll) 때 보관 행도 FINAL · 거짓으로 닫는다(fail-closed)"

  - name: GuardrailQueuedSegment
    description: >-
      검사 중 도착한 새 출력 조각이 모여 봉인된 구간 하나(대기열 1칸). 앞 검사가 끝날 때까지 보관한다.
    owner: realtime guardrail 협력 객체
    attributes:
      - { name: sealed, type: ref, required: true, references: SegmentBuffer.Sealed }
      - { name: sealedAt, type: instant, required: true }
      - { name: providerOutputGeneration, type: integer, required: true, notes: "봉인 때의 공급자 출력 세대. 꺼낼 때 달라졌으면 무효 · 폐기(BR5.4)" }
      - { name: heldAiRow, type: ref, required: true, references: HeldAiRow, notes: "대기 구간의 AI 행 보관 손잡이. 폐기되면 FINAL · 거짓으로 닫고, 꺼내 검사하면 검사표로 옮긴다" }
    constraints:
      - "대기열 길이 최대 1. 찼는데 또 구간이 봉인되면 새 구간은 폐기한다(사유 GUARDRAIL_QUEUE_FULL, BR5.3)"

  - name: GuardrailTurnState
    description: >-
      재지시 횟수를 세는 턴의 상태. 턴 = 아이의 확정 전사 하나에 대한 응답 한 덩어리의 수명이다.
    owner: realtime guardrail 협력 객체
    attributes:
      - { name: turnSeq, type: integer, required: true, notes: "아이 확정 전사가 들어올 때마다 오른다(대화 기록 순번과 별개로 협력 객체가 센다)" }
      - { name: retries, type: integer, required: true, min: 0, max: 1, default: 0, notes: "이 턴에서 보낸 재지시 수. 1이면 두 번째 막힘에서 턴을 포기한다" }
      - { name: lastServerInstruction, type: ref, required: false, references: ReplayableInstruction, notes: "이 턴의 응답을 낳은 서버 지시(U11 NextAction 템플릿). 없으면 자유 응답" }
    constraints:
      - "아이 확정 전사 · 기기 끼어들기 · 세션 종료로 새 턴이 시작되면 retries는 0으로 돌아간다(BR6.1)"

  - name: ReplayableInstruction
    description: 서버가 보낸 다음 행동 지시를 다시 보낼 수 있게 이름과 요청 만드는 방법을 보관한 값 객체(메모리). 템플릿 이름과 매개변수만 담고 전사 원문은 담지 않는다.
    attributes:
      - { name: template, type: enum, required: true, notes: "U11 InstructionTemplates.NextAction 또는 연습 지시 템플릿 이름" }
      - { name: bumpReason, type: enum, required: true, notes: "다시 보낼 때 쓰는 출력 세대 올림 사유" }
    constraints:
      - "보관 대상은 판정 뒤 다음 행동 지시와 응답 시작 지시(requestResponse) 두 호출 지점뿐이다. 승인 발화(연습 speak) · 연습 확장점 지시 · 게이트 교정 · 제브 재지시는 보관하지 않는다 — 원문 일치 검사 때문에 같은 지시를 다시 보내는 것이 의미가 달라진다(BR6.2, functional-spec W2b)"
      - "새 턴(아이 확정 전사), 다른 종류의 지시, 재지시로 한 번 쓰임 중 먼저 오는 때 지운다"

  - name: GuardrailBlockedEvent
    description: >-
      연습 확장점에 알리는 사건 값. U13이 정의와 단위 시험까지만 하고 운영에서 만드는 것은 U12다(BR6.5). 전사 원문을 담지 않는다.
    attributes:
      - { name: correlationId, type: string, required: true, notes: "연습 발화 correlationId" }
      - { name: mode, type: enum, required: true, allowed: [PRACTICE_PEER, PRACTICE_COACH] }
      - { name: reasonCode, type: string, required: true }
      - { name: retried, type: boolean, required: true, notes: "다시 지시를 한 번 한 뒤의 두 번째 막힘이면 참" }

  - name: GuardrailEvent
    description: >-
      검사 결과의 영구 기록(realtime V6 표 guardrail_event). 이유 코드 · 정책 버전 · 지연 · 판정만 담고 전사 원문은 담지 않는다(R-F14).
      id가 검사 요청 id라 같은 검사의 재시도 쓰기가 덮어쓰기가 된다.
    owner: realtime guardrail 기록기(비동기, 실행기가 DB를 기다리지 않음)
    attributes:
      - { name: id, type: uuid, required: true, unique: true, notes: "= 검사 요청 id(승인 원문 생략은 새 id)" }
      - { name: guardianId, type: uuid, required: true, notes: "보호자 삭제 참여자 기준" }
      - { name: sessionId, type: uuid, required: true, references: realtime_session, notes: "FK ON DELETE RESTRICT — 삭제 참여자가 세션 행(순서 200)보다 먼저 지운다" }
      - { name: mode, type: enum, required: true, allowed: [REAL, STUB], notes: "OFF는 제브 단계를 건너뛰므로 기록이 없다(BR1.5)" }
      - { name: sourceKind, type: enum, required: true, allowed: [NEW_UTTERANCE, APPROVED_VERBATIM_SKIPPED] }
      - { name: decision, type: enum, required: true, allowed: [ALLOW, BLOCK, REVIEW, TIMEOUT, ERROR, VOIDED, QUEUE_FULL], notes: "TIMEOUT=시간 초과, ERROR=전송 오류 · 잘못된 JSON · 정책 판 불일치 · 어댑터 없음, VOIDED=늦은 ALLOW 무효(원인은 reasonCode), QUEUE_FULL=대기열이 차 폐기" }
      - { name: reasonCode, type: string, required: true, max: 80 }
      - { name: policyVersion, type: string, required: true, max: 80 }
      - { name: latencyMs, type: integer, required: true, min: 0 }
      - { name: createdAt, type: instant, required: true, notes: "MujoongClock으로 읽은 값. DB 기본값 없음" }
    constraints:
      - "전사 원문 · 후보 문장 · 맥락을 담는 칸이 없다"
      - "decision · mode · sourceKind는 CHECK 제약으로 열거값에 고정한다"
      - "guardian_id 색인 하나(삭제 참여자 조회)"
```

## 사람이 읽는 요약

- **저장하는 것은 `GuardrailEvent` 하나**다. 검사표 · 보관 행 · 대기 구간 · 턴 상태는 모두 실행기 스레드만 읽고 쓰는 메모리 객체라 세션이 끝나면 사라진다.
- **검사표가 늦은 ALLOW를 가린다**: 기기 프레임 세대와 공급자 출력 세대(둘은 별개 번호), 기기 연결 id, 입력 묶음 id, 요청 id를 모두 담는다. 같은 `Sealed`를 쥐어 전사와 PCM이 같은 구간이라는 구조적 보장을 얻는다.
- **보호자 화면 AI 행**은 구간이 제브를 기다리는 동안 `HeldAiRow`로 보관해 두고, 새 구간은 별도 행으로 열어 서로 섞이지 않게 한다(Q2 답 B).
- **모드 값**은 기동 때 정해지며 세션 안에서 바뀌지 않는다. `OFF`는 기록이 없고 `STUB` · `REAL`만 `guardrail_event`를 남기며 `mode` 칸이 둘을 가른다.
