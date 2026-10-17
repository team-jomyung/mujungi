# U11 판정 턴 기반 — 업무 규칙

> 근거: `functional-design-questions.md`(첫 판 Q1-C · Q1-1-A · Q2-A · Q3-A와 가정 1 ~ 13, 범위 확장 개정 Q4-A · Q5-A · Q6-B · Q6-1-A와 가정 1 ~ 13), `../../inception/requirements-analysis/requirements.md`(2026-10-17 재승인 판, FR1 ~ FR13, NFR1 ~ NFR9), `../../inception/requirements-analysis/requirements-analysis-questions.md`(Q1 ~ Q12), 원래 기록 스토리 US1.7 · US1.8 · US1.10 · US2.5, 실호출 보고서(2026-10-16T08:39Z).
> 시험 · 문서 정비 요구(FR11)와 PR 나누기 같은 작업 방식 요구는 업무 규칙을 만들지 않고 `traceability.json`에 N/A로 추적한다(project.md 교정). 다만 실제 공급자 호출 · 비밀 · 병합 순서처럼 어기면 위험한 것은 규칙으로 둔다(BR9 · BR12).
> 범위 확장으로 GPT-Live 응답 id가 없어졌다. 아래에서 '세대'는 서버가 출력 구간마다 붙이는 번호(BR11.5), '끊김'은 출력 조각이 `mujoong.gate.segment-quiet`(800ms) 동안 오지 않은 상태다.

```yaml
rules:
  # BR1 진행 중 판정 호출
  - id: BR1.1
    statement: 진행 중 판정은 새 포트 JudgeClient로만 부르고, 실제 구현은 U9 RealtimeLunaClient(목적 JUDGMENT_LIVE)를 쓴다. 기존 EvaluationProvider와 섞지 않는다.
    category: policy
    applies_to: JudgeClient
    trigger: 판정 턴이 열릴 때
    logic: JudgeRequest를 만들어 JudgeClient.judge를 비동기로 부른다
    violation: EvaluationProvider로 판정하거나 판정 경로가 둘이면 결함
    source: FR1.1, 원 FR3.1
  - id: BR1.2
    statement: 선택지는 세션 시작 때 고정된 판정 판(judgment-v1)의 그 지점 선택지 코드를 판 리소스 순서 그대로 쓴다.
    category: constraint
    applies_to: JudgeRequest.options
    trigger: 요청 만들 때
    logic: options = LiveContentRegistry.plan(session).judgmentSet의 point 선택지 코드
    violation: 세션 중 판이 바뀌어 다른 선택지가 나가면 결함
    source: FR1.2, 원 FR3.2
  - id: BR1.3
    statement: 1순위 확률이 하한(min-confidence, 기본 0.6)보다 작거나, 1순위와 2순위 확률 차이가 최소 차이(min-margin, 기본 0.15)보다 작으면 판정 보류다. 경계값(같음)은 확정 쪽이다.
    category: calculation
    applies_to: JudgeOutcome
    trigger: 공급자 결과가 형식 검사를 통과한 뒤
    logic: IF first.p < minConfidence THEN ABSTAINED(LOW_CONFIDENCE) ELSE IF first.p - second.p < minMargin THEN ABSTAINED(LOW_MARGIN) ELSE CONFIRMED
    violation: 보류 결과를 확정 행동으로 쓰면 결함(NFR2)
    source: FR1.3, AC1.8.1, 원 FR3.3
  - id: BR1.4
    statement: 판정 한 번의 시한은 live-budget(4s, 요구사항 Q10 편차)이고, 시계가 시한에 닿으면(정확히 4.000초 포함) 보류다. 시간 초과 · 공급자 오류 · 스키마 오류는 재시도 없이 판정 보류다. 결과와 만기가 같은 틱이면 실행기 편지함에 먼저 들어온 쪽이 이긴다.
    category: policy
    applies_to: JudgeClient
    trigger: 요청 뒤
    logic: IF 경과 >= liveBudget THEN ABSTAINED(TIMEOUT) · 요청 취소; IF 공급자 실패 THEN ABSTAINED(PROVIDER_ERROR); IF 스키마 실패 THEN ABSTAINED(SCHEMA_INVALID); 어느 경우도 다시 부르지 않음
    violation: 같은 판정에 공급자 호출이 2번 이상이거나, 4.000초에 만기된 시계 뒤의 결과로 행동이 바뀌면 결함
    source: FR1.4, AC1.8.2(편차 4초), 원 FR3.4, 요구사항 검토 R-04
  - id: BR1.5
    statement: 공급자 결과의 선택지 코드가 요청 선택지 밖이거나, 1 · 2순위가 같은 코드이거나, 확률이 0 ~ 1 밖이면 판정 보류다.
    category: validation
    applies_to: JudgeOutcome
    trigger: 공급자 결과를 받은 직후
    logic: 각각 ABSTAINED(OPTION_OUT_OF_SET) · ABSTAINED(DUPLICATE_OPTION) · ABSTAINED(PROBABILITY_OUT_OF_RANGE)
    violation: 이런 결과로 행동이 바뀌면 결함
    source: FR1.5, ai-io.md 10.3
  - id: BR1.6
    statement: realtime Luna 호출부 빈이 없으면(키 없음 또는 스위치 꺼짐, BR12.3) JudgeClient는 공급자를 부르지 않는 보류 판정기다. 이때 실행부는 판정 턴을 열지 않고(차례 id · 세대 올림 · THINKING 없음) 곧바로 보류 줄의 기존 흐름으로 가며, 판정 기록 ABSTAINED(NO_PROVIDER) 1건만 별도 표에 비동기로 남긴다. 앱은 정상 기동한다.
    category: policy
    applies_to: JudgeClient · 판정 턴 열기
    trigger: 앱 기동 · 판정 턴을 열려 할 때
    logic: IF JudgeClient.available() = false THEN 턴을 열지 않고 W1 · W2의 보류 줄 실행 + 기록 1건
    violation: 공급자가 없는데 기존 view · 세션 사건 순서가 바뀌거나 기동이 거부되면 결함
    source: FR1.6, U11-R6, Q6-1-A
  - id: BR1.7
    statement: 판정 요청에는 이번 판정에 필요한 확정 전사와 최근 대화 최대 3턴만 싣고, 아이 이름 별칭 표를 넘겨 '아이'로 치환하게 한다.
    category: constraint
    applies_to: JudgeRequest
    trigger: 요청 만들 때
    logic: childUtterance = 판정 대상 확정 전사; recentTurns ≤ 3; aliases = 세션의 아이 이름 표
    violation: 식별자 · 원음 · 보호자 입력 원문이 실리면 결함(R-F14 · R-F15)
    source: NFR3, 원 NFR3
  - id: BR1.8
    statement: 공급자 결과는 다른 스레드에서 끝나므로 turnId와 함께 실행기 편지함으로만 넘기고, 실행기 스레드에서만 판정 턴 상태를 바꾼다.
    category: constraint
    applies_to: JudgmentTurn
    trigger: 공급자 결과 도착
    logic: 완료 콜백 → 일반 편지함 put(JudgmentArrived{turnId, outcome}). 안전 편지함(putSafety)을 쓰지 않아 앞서 도착한 출력 조각보다 먼저 처리되지 않게 한다
    violation: 실행기 밖에서 상태를 바꾸거나 결과가 앞선 출력 조각을 앞지르면 결함(경쟁)
    source: FR5.2, U11-R12

  # BR2 진행용 판정 기록
  - id: BR2.1
    statement: 판정 턴이 닫힐 때마다(확정 · 보류 · 장애 취소 · 중단) judgment_live에 1건을 남긴다.
    category: policy
    applies_to: JudgmentLiveRecord
    trigger: 판정 턴 닫힘
    logic: id = turnId인 행을 쓴다(같은 id 덮어쓰기)
    violation: 판정 턴 수와 행 수가 다르면 결함
    source: FR2.1, AC1.8.6, 원 FR3.6
  - id: BR2.2
    statement: 판정 기록 값은 보호자 API · 리포트 · SessionRecordCaptured · 대화 기록 스냅숏에 싣지 않는다.
    category: authorization
    applies_to: JudgmentLiveRecord
    trigger: 모든 외부 응답 · 이벤트
    logic: 판정 기록을 읽는 외부 경로를 두지 않는다
    violation: 보호자 쪽에 판정 결과 · 확률이 나오면 결함
    source: FR2.1, FR10.1
  - id: BR2.3
    statement: 판정 기록 표는 realtime V5로 더하고, 이 때문에 깨지는 최신 판 · 표 목록 단언(RealtimeSchemaIT · PracticeMigrationRegistrationTest · PracticeMigrationIT)은 새 사실로만 갱신한다.
    category: constraint
    applies_to: 마이그레이션
    trigger: Code Generation
    logic: 판 "5" · 표 judgment_live 추가로 단언을 바꾸고, "4 이상" 같은 느슨한 단언으로 바꾸지 않는다
    violation: 다른 단언을 바꾸거나 느슨하게 하면 결함(R-F2)
    source: FR2.2, Q2-A(요구사항), 첫 판 가정 7
  - id: BR2.4
    statement: 계정 삭제 때 판정 기록은 보호자 기준 삭제 참여자(순서 180, 연습 150 뒤 · 세션 200 앞)가 지운다.
    category: policy
    applies_to: JudgmentLiveRecord
    trigger: DeletionRequested
    logic: DELETE realtime_judgment_live WHERE guardian_id = :guardianId (DbRetry · 한 트랜잭션)
    violation: 세션 행 삭제가 외래 키로 막히거나 행이 남으면 결함
    source: FR2.3, 첫 판 가정 9
  - id: BR2.5
    statement: 판정 기록 쓰기는 실행기를 기다리게 하지 않는다. 비동기로 쓰고, 실패하면 앱 오류(사유 코드만)를 남긴 뒤 다음 틱에 같은 id로 다시 쓴다.
    category: policy
    applies_to: JudgmentLiveRecord
    trigger: 판정 턴 닫힘
    logic: 기록 칸에 넣고 플러시 · 일시 실패면 되돌려 재시도. 외래 키 · 검사 제약 위반(세션이 이미 지워짐 등) · 삭제 차단된 세션은 재시도하지 않고 버린다(사유 코드만)
    violation: 실행기 스레드에서 DB를 기다리거나 지워진 세션 기록을 끝없이 재시도하면 결함
    source: NFR8, 첫 판 가정 9, U11-R13

  # BR3 판정 턴 출력 보류
  - id: BR3.1
    statement: 판정 턴을 열면 출력 세대를 올리고, 판정 턴 동안 온 GPT-Live 출력 조각은 음성을 버리고 전사 조각만 판정 턴에 모아 판정 기록에 남긴다. 공급자에 취소를 요청하지 않는다(취소 메시지가 없다).
    category: policy
    applies_to: JudgmentTurn.heldOutputTranscript
    trigger: 판정 턴 중 출력 조각 도착
    logic: IF 열린 판정 턴 THEN 음성 조각 버림; 출력 전사 조각은 heldOutputTranscript에 덧붙임(2000자 한도, 넘으면 자름)
    violation: 판정 턴 중 음성이 기기로 가면 결함(NFR2)
    source: FR3.1, AC1.10.1, 범위 확장 가정 5(첫 판 Q2-A 개정, 편차 U11-D2)
  - id: BR3.2
    statement: 판정 턴 중 버린 출력은 대화 기록(realtime_transcript) · 보호자 중계 · 문장 버퍼에 들어가지 않는다.
    category: constraint
    applies_to: TranscriptWriter · SegmentBuffer
    trigger: 판정 턴 중 출력 조각 도착
    logic: 판정 턴 갈래를 문장 버퍼 · 대화 기록기 앞에서 가른다
    violation: 대화 기록에 버린 출력이 남으면 결함
    source: FR3.1, AC1.10.1, U11-R3
  - id: BR3.3
    statement: 서버가 공급자에 보내는 지시 · 응답 요청(판정 뒤 지시, 되묻기, 휴식 지시, 게이트 교정, 연습 발화, 참여 뒤 첫 응답, 확인 뒤 이어 가기)은 모두 한 직렬 송신 경로를 쓴다. 순서는 세대 올림 → 끊김 확인(BR11.7) → session.instructions.append(지시가 있을 때만) → 세대 재확인 → response.create다.
    category: constraint
    applies_to: 공급자 송신 경로
    trigger: 실행부가 지시 · 응답 요청을 정했을 때
    logic: 직렬 큐 작업 하나로 순서대로 보낸다. 끊김 허가(SendPermit)를 받고, 송신 직전마다 실행기의 현재 세대 · 허가 번호와 대조해 어긋나면 append · create를 보내지 않고 SendAborted를 낸다. 허가 칸을 기다리는 동안 상한 만기 · 세션 종료 · 장애가 오면 실행기가 칸을 거부로 채워 작업이 송신 없이 끝나게 한다. InstructionSent('보내는 중' 표시)는 create보다 먼저 편지함에 넣는다
    violation: create가 append보다 먼저 나가거나, 남은 자동 응답이 흐르는 중에 지시가 나가거나, 허가 칸을 기다리던 작업이 큐를 영영 쥐고 있으면 결함
    source: FR3.2, FR12.3, Q5-A, 첫 판 가정 11, U11-R2
  - id: BR3.4
    statement: 지시를 넣은 뒤에는 그 지시 뒤 세대의 응답만 게이트 검사로 넘긴다. 이전 세대 조각은 버린다.
    category: constraint
    applies_to: 출력 경로
    trigger: 출력 조각 도착
    logic: IF 조각을 받을 때의 세대 상태가 DISCARDING이거나 봉인 세대 != 현재 세대 THEN 버림
    violation: 지시 전 응답이 들리면 결함
    source: FR3.2, AC1.10.2, FR12.5
  - id: BR3.7
    statement: 지시 응답은 '송신 표시(InstructionSent, create보다 먼저 편지함에 들어감)를 실행기가 처리한 뒤 처음 온 조각'부터다. 송신 전에 끊김을 확인했으므로 그 뒤 첫 조각은 남은 자동 응답이 아니라 지시에 따른 새 응답으로 본다. InstructionSent 처리 전에 온 조각은 버린다.
    category: constraint
    applies_to: JudgmentTurn · OutputGeneration
    trigger: 판정 턴 중 출력 조각
    logic: 직렬 송신 작업이 create를 보내기 직전에 일반 편지함에 InstructionSent{turnId, generation}를 넣는다. IF InstructionSent 미처리 THEN 버림 ELSE 현재 세대를 RECEIVING으로 열고 이 조각부터 지시 응답 구간으로 받는다
    violation: 지시 전 자동 응답 조각이 지시 응답으로 통과하면 결함. 지시 응답 첫 조각을 잘못 버리는 쪽은 안전 쪽 실패로 보고 BR4.5로 회복한다
    source: FR3.2, AC1.10.2, Q5-A
  - id: BR3.5
    statement: 다음 행동 지시문에는 선택지 코드 · 확률 · 판정 기준 · 아이 발화 원문을 넣지 않는다. 지시문은 서버 템플릿에서만 만든다.
    category: validation
    applies_to: NextActionInstruction.text
    trigger: 지시 만들 때
    logic: 템플릿 지시 종류(HOLD_REASK · REST_ACK_AND_RETURN · REST_ACK_ONLY)에서 고른 고정 문장만 쓴다
    violation: 지시문에 선택지 코드 · 숫자 확률이 있으면 결함
    source: FR3.3, 원 FR3.7
  - id: BR3.6
    statement: 판정 턴이 열려 있는 동안 기기 화면은 '생각 중'을 유지한다. 판정 턴 중에는 버린 출력 · 대기 문구 · 재생 끝 때문에 '듣는 중'으로 되돌리지 않는다.
    category: policy
    applies_to: 기기 보기
    trigger: 판정 턴 열림 · 출력 폐기 · 재생 끝
    logic: ACTIVE에서 판정 턴을 열 때 THINKING; WAITING_CHILD(J1)에서는 지금처럼 보기를 바꾸지 않는다. listeningAgain 호출처에서 판정 턴이 열려 있으면 건너뛴다. 판정 턴이 닫힐 때 ACTIVE이고 출력 · 재생이 없으면 listeningAgain으로 LISTENING을 되돌린다(지시 응답이 시작돼 닫힌 경우는 출력 경로가 보기를 맡는다)
    violation: 판정 턴 중 LISTENING이 가거나, 판정 턴이 닫힌 뒤에도 THINKING에 머물면 결함
    source: FR3.4, AC1.10.4, U11-R11, 첫 판 가정 12

  # BR4 응답 준비 · 판정 보류 때 진행
  - id: BR4.1
    statement: 10초 응답 준비 시계는 판정 턴의 판정 단계(AWAITING_RESULT)에만 건다. 기준은 아이 말 끝(BR11.9)이고 판정 4초를 포함한다. 판정이 끝나 지시 단계(INSTRUCTING)로 가면 이 시계와 4초 시계를 푼다. 지시 단계는 BR4.5의 시계만 쓴다.
    category: policy
    applies_to: JudgmentTurn.readyDeadline
    trigger: 판정 턴 열림 · INSTRUCTING 진입
    logic: speechEndAt은 판정 대상 전사에 대응하는 말 끝 시각(그 전사보다 앞선 낡은 말 끝은 쓰지 않음). IF readyDeadline까지 판정이 아직이면 ABSTAINED(RESPONSE_NOT_READY)로 닫고 실행부 소유 턴은 보류 줄 처리, 확장 소유 턴은 JUDGMENT_ABSTAINED 사건. 이 시계가 4초 시계보다 먼저 만기되는 것은 턴이 말 끝보다 6초 넘게 늦게 열린 경우뿐이다. 말 끝부터 지시 응답 첫 소리까지는 최악 약 14초다(편차 U11-D3)
    violation: 판정 없는 일반 대화 턴에 이 시계가 걸리면 결함(첫 판 Q3-A)
    source: FR4.1, AC1.8.4, 첫 판 Q3-A, 범위 확장 가정 11
  - id: BR4.2
    statement: 실행부가 다음 행동을 정하는 판정 턴에서 결과가 보류이면, 교정 행동 없이 HOLD_REASK(짧게 다시 말해 달라고 하거나 맞장구) 지시를 넣는다. 단, J1 · J6 보류는 지금의 재안내 · 확인 질문 흐름을 쓴다(BR6.3 · BR7.1).
    category: policy
    applies_to: NextActionInstruction
    trigger: 판정 보류(실행부 소유 턴, 10초 초과)
    logic: kind = HOLD_REASK
    violation: 보류 뒤 교정 행동('일부 전달' 등)이 나가면 결함
    source: FR4.2, AC1.8.3, Q4-A(요구사항)
  - id: BR4.3
    statement: 판정 입력 전사가 비었거나, 전사 신뢰도가 낮다고 표시됐거나(uncertain), 음성 수신이 끊겼으면 공급자를 부르지 않고 판정 보류(UNREADABLE_INPUT)로 처리한다.
    category: validation
    applies_to: JudgeRequest
    trigger: 판정 턴을 열기 전
    logic: IF 전사 공백 OR uncertain OR 수신 끊김 THEN ABSTAINED(UNREADABLE_INPUT). 공급자가 신뢰도를 주지 않으면 uncertain은 거짓으로 본다
    violation: 판독 불가 입력을 '기능 미충족'으로 판정하면 결함
    source: FR4.3, AC1.8.5
  - id: BR4.4
    statement: 보류 · 10초 초과로 되묻기 지시를 넣을 때 세대를 올리고 끊김을 확인한 뒤 보내, 늦게 온 원래 응답이 되묻기와 겹쳐 들리지 않게 한다.
    category: constraint
    applies_to: 출력 경로
    trigger: HOLD_REASK 지시
    logic: BR3.3 순서를 그대로 쓴다
    violation: 되묻기와 원래 응답이 함께 들리면 결함
    source: FR4.1, 첫 판 가정 6, Q5-A
  - id: BR4.5
    statement: 지시를 내려는 판정 턴(INSTRUCTING)은 진입부터 지시 송신 전 끊김 대기와 지시 뒤 응답 대기를 합쳐 response-ready(10s) 안에 지시 응답이 시작되지 않으면 닫는다. 직렬 작업이 송신 직전 대조에 실패해 세대가 바뀐 채 끝나면(SendAborted) 기다리지 않고 바로 닫는다. 지시를 다시 내지 않고 사유 코드(끊김을 못 봤으면 OUTPUT_GAP_NOT_OBSERVED, 대조 실패면 SEND_ABORTED, 지시 뒤 응답이 없으면 INSTRUCTED_RESPONSE_MISSING)를 남기며, ACTIVE면 LISTENING을 되돌린다.
    category: policy
    applies_to: JudgmentTurn
    trigger: 지시 응답 시한 만기
    logic: instructionDeadline = INSTRUCTING 진입 시각 + response-ready; 만기 THEN CLOSED + 보기 복원 + 시계 모두 해제. 끊김을 못 본 채 만기되면 지시를 보내지 않는다
    violation: 판정 턴이 닫히지 않아 THINKING에 계속 머물거나, 끊김 없이 지시가 나가면 결함
    source: FR4.1, AC1.10.4, Q5-A

  # BR5 장애 취소 · 늦은 결과
  - id: BR5.1
    statement: 판정 턴 중 공급자 끊김 · 기술적 일시 정지가 나면 그 판정을 FAULT_CANCELLED로 닫고 결과를 쓰지 않는다. 회복은 지금 흐름에 맡긴다. J1이면 참여 창이 그대로 이어지고(endPause의 join.shift), J6이면 확인 질문 없이 '종료 아님'처럼 연습 시계만 되돌린다(응답 요청은 하지 않음). 확장 소유 턴이면 JUDGMENT_ABSTAINED{reason FAULT} 사건을 넘긴다.
    category: policy
    applies_to: JudgmentTurn
    trigger: providerLost · abortProviderLost · startPause
    logic: 열린 턴을 FAULT_CANCELLED로 닫고 Luna 요청 취소 · 기록 1건(nextAction NONE)
    violation: 장애 뒤 판정 결과로 출력이 풀리면 결함
    source: FR5.1, AC1.10.3, 원 FR5.4
  - id: BR5.2
    statement: turnId가 열린 턴과 다르거나 턴이 이미 닫힌 뒤 도착한 판정 결과 · 출력 전사는 버린다.
    category: validation
    applies_to: JudgmentTurn
    trigger: 결과 · 전사 도착
    logic: IF turnId != 열린 턴 OR 턴 CLOSED OR 세션 상태가 턴을 열 때와 다름 THEN 버림(사유 코드만 기록)
    violation: 낡은 결과가 행동을 바꾸면 결함
    source: FR5.2, AC1.10.3
  - id: BR5.3
    statement: 실행부 소유 판정 턴이 결과를 기다리는 동안 새 확정 전사가 오면 STOP은 BR6.2로 처리하고, 그 밖의 전사는 이전 판정을 낡은 것으로 본다. 열린 턴을 SUPERSEDED로 닫고(요청 취소) 새 전사를 처음부터 지금 흐름(JoinTracker · judgeStopIntent)에 넣는다. 확장 소유 턴 중의 새 전사는 지금처럼 확장에 전달하고 턴은 그대로 둔다.
    category: policy
    applies_to: JudgmentTurn
    trigger: 판정 턴 중 아이 확정 전사
    logic: owner = EXECUTOR → SUPERSEDED + 새 전사 재처리; owner = EXTENSION → 확장 전달
    violation: 열린 턴이 둘이 되거나 낡은 판정 결과가 새 말 뒤에 적용되면 결함
    source: FR5.2, 첫 판 검토 R-04
  - id: BR5.4
    statement: 세션이 판정 턴을 열 때의 상태(WAITING_CHILD · ACTIVE)를 벗어나는 모든 전이(CLOSING 진입 · 끝내기 요청 · 동의 철회 · 삭제 중단 · 참여 거절 · 서버 종료)에서 열린 판정 턴을 SESSION_EXITED로 닫고 요청을 취소한다.
    category: policy
    applies_to: JudgmentTurn
    trigger: 세션 상태 전이
    logic: 전이 직후 열린 턴이 있으면 SESSION_EXITED · 기록 1건(nextAction NONE)
    violation: 종료 뒤 늦은 결과가 지시 · 참여 시작을 시도하면 결함
    source: FR5.1, FR5.2, 첫 판 검토 R-05

  # BR6 종료 · 휴식
  - id: BR6.1
    statement: 기본 · 등록 종료 표현과 일치하면(StopIntentJudge STOP) 판정 턴을 열지 않고 지금처럼 바로 종료한다.
    category: policy
    applies_to: 종료 처리
    trigger: 아이 확정 전사
    logic: IF Verdict = STOP THEN 판정 없이 CHILD_STOP_INTENT(basis 그대로) → beginClosing
    violation: 등록 표현에 Luna가 불리면 결함
    source: FR6.1, AC1.7.1, AC1.7.4, 첫 판 가정 2
  - id: BR6.2
    statement: 판정 턴 중에 등록 종료 표현이 나오면 열린 판정을 STOPPED로 닫고 결과를 기다리지 않고 종료한다.
    category: policy
    applies_to: JudgmentTurn
    trigger: 판정 턴 중 아이 확정 전사가 STOP
    logic: 열린 턴 STOPPED · 공급자 요청 취소 → 종료 처리(BR6.1)
    violation: 판정 결과를 기다린 뒤 종료하면 결함
    source: FR6.2, AC1.7.2
  - id: BR6.3
    statement: 종료 판정이 CONFIRM(등록 표현 밖 종료 · 휴식 뜻 후보)이면 StopConfirmFlow를 ASKING으로 넣기 전에 J6 판정 턴을 연다. J6 동안 확인 흐름은 IDLE이다. 결과 END_CLEAR는 종료, REST는 휴식(BR6.4), NOT_END는 이어 가기, UNCLEAR · 보류는 그때 stopConfirm.onJudgment(CONFIRM)를 불러 지금의 확인 질문 흐름(STOP_CONFIRM_ASKED → 재생)으로 간다. STOP_CONFIRM_ASKED · RESOLVED 사건은 확인 질문 흐름에 들어간 경우에만 낸다.
    category: policy
    applies_to: 종료 처리 · StopConfirmFlow
    trigger: StopIntentJudge CONFIRM
    logic: J6 턴 → 결과별 분기(functional-spec W2 사건 표); 공급자 없음이면 BR1.6에 따라 턴 없이 곧바로 지금 흐름
    violation: CONFIRM에서 판정 없이 바로 종료하거나, 보류를 종료로 쓰면 결함
    source: FR6.3, AC1.7.3, 첫 판 가정 1 · 3
  - id: BR6.4
    statement: 휴식 요청(J6 · J1 결과 REST)이면 승인 문구 재생 대신 일반 지시를 넣는다. 출력권 확장이 없으면 REST_ACK_AND_RETURN(받아 주는 말 + 하던 이야기로 돌아가 마지막 질문을 짧게 다시 묻기), 출력권 확장이 있으면 REST_ACK_ONLY(받아 주는 말만)와 REST_REQUESTED 사건을 확장점에 넘긴다. '쉬는 중' 기기 상태는 만들지 않고 공백 시계는 그대로 둔다.
    category: policy
    applies_to: NextActionInstruction
    trigger: 결과 REST
    logic: ACTIVE에서만 적용. IF practice.controlsOutput() THEN REST_ACK_ONLY + REST_REQUESTED ELSE REST_ACK_AND_RETURN. 참여 확인(WAITING_CHILD)의 J1 REST는 응답을 만들 수 없으므로(편차 U4b-a의 대체 편차가 같은 뜻을 유지) BR7.1에 따라 재안내로 보낸다
    violation: 출력권 확장 세션에서 실행부가 다시 묻기까지 지시하면 결함(U5b 순서 침범)
    source: FR6.4, AC1.7.6(편차 U11-D1), 첫 판 Q1-C · Q1-1-A, Q5-B · Q5-1-A(요구사항)
  - id: BR6.5
    statement: J6 '종료 명확'으로 종료하면 세션 사건 CHILD_STOP_INTENT의 basis를 LUNA_J6으로 남긴다. 등록 표현 일치는 지금 값(SNAPSHOT · CLEAR_PATTERN) 그대로다.
    category: policy
    applies_to: 세션 사건
    trigger: J6 END_CLEAR 종료
    logic: putEvent(CHILD_STOP_INTENT, {basis: LUNA_J6})
    violation: 두 경로의 basis가 같으면 결함
    source: FR6.5, AC1.7.5, 첫 판 가정 5

  # BR7 자유대화 판정
  - id: BR7.1
    statement: 참여 확인 중 JoinTracker가 UNCLEAR로 분류한 답에만 J1 판정 턴을 연다. 결과를 JoinTracker의 답(Reply)으로 바꿔 join.reply(답, 원래 확정 시각)에 넣고 그 동작을 지금 흐름대로 실행한다. PARTICIPATE → ACCEPT, DECLINE → REFUSE, REST · UNCLEAR · UNREADABLE · 보류 → UNCLEAR(지금의 재안내 · 대기 규칙). J1 동안 참여 창 시계 만기는 미뤘다가 턴이 닫힌 뒤 처리한다.
    category: policy
    applies_to: 참여 확인
    trigger: WAITING_CHILD 중 JoinTracker UNCLEAR
    logic: 처음 분류(UNCLEAR)는 join.reply에 넣지 않고 보류한 채 J1을 연다. 결과가 오면 바뀐 답 하나만 join.reply에 넣는다(재안내 횟수는 한 번만 셈). JOIN_WINDOW 만기가 열린 J1 중에 오면 표시만 하고 닫힌 뒤 tick을 부른다. 입력 신뢰도는 InputTranscriptDone.confidence로 BR4.3 uncertain을 계산한다
    violation: 명확한 ACCEPT · REFUSE에 Luna가 불리면 결함
    source: FR7.1, AC2.5.1, 첫 판 가정 4
  - id: BR7.2
    statement: 자유대화에서 실행부가 여는 판정 턴은 J1 · J6뿐이다.
    category: constraint
    applies_to: 자유대화
    trigger: 모든 판정 턴 열기
    logic: IF activityType = FREE_TALK AND point NOT IN (J1, J6) THEN 열지 않음
    violation: 자유대화에서 다른 지점 판정이 열리면 결함
    source: FR7.1, 원 FR4.8, AC2.5.1

  # BR8 연습 확장점
  - id: BR8.1
    statement: PracticeOutput에 requestJudgment · announceRoleSwitch · setThinking을 기본 구현(false 반환)으로 더해 기존 구현이 고치지 않고 돈다.
    category: constraint
    applies_to: PracticeOutput
    trigger: Code Generation
    logic: default 메서드로 더한다
    violation: 기존 확장 · 시험 대역을 고쳐야 컴파일되면 결함
    source: FR8.1, C-DS3
  - id: BR8.2
    statement: requestJudgment는 ACTIVE에서만 받아 EXTENSION_REQUEST 판정 턴을 열고, 결과는 JUDGMENT_RESULT(선택지 코드만) · JUDGMENT_ABSTAINED(사유만) 사건으로 확장점에 돌려준다. 다음 행동 지시는 확장이 정한다.
    category: policy
    applies_to: 확장점
    trigger: requestJudgment 호출
    logic: requestJudgment(JudgmentPoint point, long transcriptSeq, UUID correlationId, Map criteria) → boolean. childUtterance · recentTurns는 실행부가 세션 메모리의 최근 확정 전사(최대 4개)에서 transcriptSeq로 찾고, criteria는 확장이 준 객체 그대로(J2 · J3 · J5만). 사건은 ExtensionEvent(kind, name "judgment", correlationId, value). 확장 소유 턴(owner = EXTENSION)은 실행부가 HOLD_REASK를 넣지 않는다
    violation: 확장에 확률이 넘어가거나 실행부가 확장 대신 지시하면 결함
    source: FR8.1, FR8.2, 첫 판 가정 10
  - id: BR8.3
    statement: 확장 소유 판정 턴에서 모은 출력 전사는 HELD_OUTPUT_TRANSCRIPT 사건으로도 넘긴다.
    category: policy
    applies_to: 확장점
    trigger: 확장 소유 턴 닫힘
    logic: heldOutputTranscript가 비어 있지 않으면 사건 1건
    violation: 전사가 대화 기록 쪽으로 새면 결함
    source: FR8.2, C-DS3
  - id: BR8.4
    statement: 확장점은 실행부를 부르지 않고, 상태 전이 · DB 쓰기 · 기기 원시 명령 · 판정 라벨 · 확률 지시 능력을 받지 않는다. CLOSING · 정산 창의 능력 호출은 거절하고 PRACTICE_EXTENSION_FAILED{reason NOT_ACTIVE}로 남긴다.
    category: authorization
    applies_to: 확장점
    trigger: 능력 호출
    logic: 지금 practiceActive 검사를 새 능력에도 쓴다
    violation: 정산 창에서 판정 턴이 열리면 결함
    source: FR8.3, ADR-005

  # BR9 GPT-Live 실호출 확인
  - id: BR9.1
    statement: GPT-Live 실호출 확인 작업(gptLiveTest)은 기본 test · check · CI · verify.sh에서 언제나 빠지고, 사람이 키를 넣고 직접 돌린다. 키가 없으면 '건너뜀'이며 통과로 보지 않는다. 선 형식 정비 뒤에는 운영 형식(app) 하나만 시도하고 kit 비교 모드는 지운다.
    category: policy
    applies_to: 실호출 작업
    trigger: Code Generation · 사람 실행
    logic: 별도 태그 · 별도 작업, 빌드 규칙으로 check 의존에서 빼고 루트 격리 검사가 막는다
    violation: CI에서 실공급자가 불리면 결함(R-F1)
    source: FR9.1, FR12.7, 범위 확장 가정 12
  - id: BR9.2
    statement: 합성 말소리(TTS) 실호출에서 출력 전사 사건이 오지 않으면 구현을 멈추고 사람과 다시 정한다. 이 설계 산출물은 버리지 않고 재설계 입력으로 둔다.
    category: policy
    applies_to: 진행 결정
    trigger: TTS 실호출 결과
    logic: IF 출력 전사 사건 없음 THEN 중단 · 사람에게 묻기. 응답 끝 · 응답 경계 · 말 끝 · 입력 전사 완료 · 24kHz · append 누적은 관측 결과에 맞춰 BR11.6 · BR11.7 · BR11.8 · BR11.9 · BR11.4의 정해진 갈래를 고른다
    violation: 출력 전사 없이 게이트 구현을 이어 가면 결함(게이트 전제 붕괴)
    source: FR9.2, FR12.7, 요구사항 Q9-A
  - id: BR9.3
    statement: 선 형식 정비 PR(PR1)은 TTS 실호출 결과(출력 · 입력 전사, 말 끝, 응답 끝 · 경계, 24kHz 수락, append 누적 여부)가 단위 보고서에 [실호출-합성]으로 남은 뒤에만 main에 합친다. 판정 턴 PR(PR2)은 PR1 위에서 연다.
    category: policy
    applies_to: 병합 순서
    trigger: PR1 병합 전
    logic: IF 단위 보고서에 TTS 실호출 결과표 없음 THEN PR1 병합 안 함
    violation: 실제 형식이 확인되지 않은 정비가 main에 들어가면 결함
    source: FR12.7, 요구사항 Q12-A, 요구사항 검토 R-03, 범위 확장 가정 12

  # BR10 기록 이벤트
  - id: BR10.1
    statement: SessionRecordCaptured 발행 경로와 필드는 바꾸지 않는다. 판정 · 보류 출력 기록은 이 이벤트에 싣지 않는다.
    category: constraint
    applies_to: SessionRecordCaptured
    trigger: 정산
    logic: 지금 RecordCaptureService 그대로
    violation: 이벤트에 판정 값이 실리면 결함
    source: FR10.1, 편차 U8-d

  # BR11 GPT-Live 선 형식
  - id: BR11.1
    statement: 세션 시작은 session.start{session{model, instructions, audio{format{type "audio/pcm", rate}}}} 중첩 모양으로 보낸다. delegation은 넣지 않는다. 아이 정보(이름 별칭 · 나이 · 관심사)는 따로 필드를 두지 않고 서버 템플릿으로 instructions 문장 안에 넣는다. session.started를 받아야 연결 성공이고, 종료는 session.close → session.closed다.
    category: constraint
    applies_to: ProviderRequestFactory
    trigger: 공급자 연결
    logic: 요청은 팩토리 한 곳에서만 만든다(SD-20). session.started 시한은 지금 연결 시한 그대로
    violation: 평평한 모양이나 childContext 필드가 나가면 결함(실제 공급자가 invalid_json으로 거절)
    source: FR12.2, 범위 확장 가정 1
  - id: BR11.2
    statement: 공급자로 나가는 type은 실제 지원 목록 15종 안에서 이 앱이 쓰는 다섯 가지(session.start · session.input_audio.append · session.instructions.append · response.create · session.close)뿐이다. session.instructions.correct · session.response.create · session.response.cancel은 보내지 않는다.
    category: constraint
    applies_to: ProviderRequestFactory · ProviderClient
    trigger: 모든 송신
    logic: 팩토리가 만드는 레코드 종류를 다섯으로 줄이고, 시험이 송신 type 집합을 단언한다
    violation: 목록 밖 type이 나가면 결함(공급자 error invalid_value)
    source: FR12.3
  - id: BR11.3
    statement: 받는 사건은 session.started · session.output_audio.delta{delta} · session.usage.updated · error{error{type, code, param}} · session.closed와, TTS 실호출에서 관측된 전사 · 말 끝 · 응답 경계 사건만 해석한다. 출력 음성은 delta에서 읽고, 응답 id를 기대하지 않는다. error는 Error(code)로, usage.updated는 사용량 초만 지표로 남긴다. 모르는 type은 지금처럼 버리고 type 이름만 개수로 센다.
    category: validation
    applies_to: ProviderClient.Reader
    trigger: 공급자 사건 수신
    logic: session.output.done · session.error · responseId 필드를 해석기와 내부 레코드에서 뺀다. 관측되지 않은 사건 이름은 코드에 넣지 않는다
    violation: 출력 음성이 0바이트로 해석되거나 error 사건이 무시되면 결함
    source: FR12.4, FR12.1, 범위 확장 가정 2
  - id: BR11.4
    statement: 지시 주입은 session.instructions.append, 응답 생성은 response.create다. 지금 instructions.correct를 쓰던 세 경로(게이트 교정, 연습 발화 speak, 판정 뒤 지시)와 response.create만 쓰던 경로(참여 뒤 첫 응답, 확인 뒤 이어 가기, 연습 requestResponse)를 모두 BR3.3 직렬 경로로 보낸다. append가 세션 지시에 쌓이면(TTS 실호출로 확인) 지시문 끝에 "이 지시는 다음 응답 한 번에만 적용한다"는 템플릿 문장을 붙인다.
    category: constraint
    applies_to: 공급자 송신 경로
    trigger: 지시 · 응답 요청
    logic: append(text) → create; 쌓임 확인 시 단발 문장 부가. 그래도 이전 지시가 다음 응답에 남는 것이 관측되면 BR9.2처럼 멈추고 사람과 다시 정한다
    violation: correct · 옛 create type이 나가거나, 지시가 직렬 경로 밖에서 나가면 결함
    source: FR12.3, 범위 확장 가정 3, 편차 (aa) · (U4b-a)의 대체
  - id: BR11.5
    statement: 서버는 출력 세대 번호를 하나 두고, 세대마다 상태(RECEIVING · DISCARDING)를 둔다. 판정 턴 열기 · 지시 · 응답 요청 송신 · 끼어들기 · 종료 의도 · 연습 발화 폐기 · 게이트 폐기 뒤 교정은 세대를 올리고 DISCARDING으로 둔다. 공급자에 취소를 요청하지 않고, 이미 기기로 보낸 재생은 지금처럼 기기 쪽 정지 명령으로 멈춘다. cancelledResponses · correctedResponses · playbackResponses의 열쇠는 응답 id 대신 세대 번호다.
    category: constraint
    applies_to: OutputGeneration
    trigger: 위 사건들
    logic: generation += 1; phase = DISCARDING; 열린 구간은 '불완전'으로 봉인해 버림; 교정은 세대당 한 번
    violation: session.response.cancel이 나가거나, 버린 세대의 조각이 기기로 가면 결함
    source: FR12.5, 요구사항 Q8-A, 범위 확장 가정 4
  - id: BR11.6
    statement: 출력 구간은 RECEIVING 세대의 첫 조각부터 받고, 조각(음성 · 전사 어느 쪽이든)이 segment-quiet(800ms) 동안 끊기면 '완전'으로 봉인해 게이트에 넣는다. 세대가 바뀌어 끊긴 구간은 '불완전'으로 버린다. TTS 실호출에서 응답 끝 사건이 관측되면 그 사건을 끊김보다 우선해 봉인한다.
    category: policy
    applies_to: SegmentBuffer · OutputGate
    trigger: 출력 조각 도착 · SEGMENT_QUIET 만기 · 세대 변경
    logic: Sealed.complete = fromStart AND (quietSealed OR endEventSealed) AND 세대 그대로. 대화 기록의 AI 행 확정은 봉인 시각에 하고, 지연 기록의 '공급자 출력 끝'은 구간의 마지막 조각 시각(lastDeltaAt)을 쓰며 봉인 대기는 '게이트 대기'로 따로 남긴다. 끊김 기준 시간은 기존 mujoong.gate.segment-quiet이고, 새 시간 키는 자동 응답 시작 대기(BR11.7의 mujoong.timing.provider.auto-response-start) 하나다
    violation: 응답 id가 없다는 이유로 모든 구간이 GATE_EMPTY가 되거나, 세대가 바뀐 구간이 통과하면 결함
    source: FR12.5, Q4-A, 편차 (z)의 대체
  - id: BR11.7
    statement: 세대를 올린 뒤(DISCARDING)에는 조각을 버리다가 '끊김 확인'이 되면 GAP_CONFIRMED로 간다. 끊김 확인은 ① 마지막 조각 시각(출력 범위 밖 · 보류 중 버린 조각 포함)과 세대 올린 시각 중 늦은 쪽에서 segment-quiet가 지났고, ② 이번 아이 말 끝 이후 조각이 한 번이라도 왔거나, 한 번도 오지 않았으면 말 끝 + 자동 응답 시작 대기(mujoong.timing.provider.auto-response-start)가 지났을 때다. 서버가 보내는 지시 · 응답 요청은 끊김 확인 뒤 허가(SendPermit{generation, permitSeq})를 받아야 나가고, 직렬 작업은 송신 직전마다 실행기가 내보내는 현재 세대 · 허가 번호와 대조한다. 허가 뒤 송신 전에 조각이 오면 실행기가 허가 번호를 올려 허가를 무효로 하고 다시 기다린다. 송신을 실행기가 처리한 뒤 처음 온 조각부터 새 세대 RECEIVING으로 받는다. 송신이 없는 세대 올림(끼어들기 등)은 끊김 확인 뒤 처음 온 조각부터 RECEIVING이다. TTS 실호출에서 응답 경계 사건이 관측되면 그 사건으로 바꾼다.
    category: constraint
    applies_to: OutputGeneration · 공급자 직렬 송신 경로
    trigger: DISCARDING 중 조각 도착 · SEGMENT_QUIET · AUTO_RESPONSE_START 만기 · 송신 직전 · 송신 완료
    logic: phase DISCARDING → (끊김 ①②) → GAP_CONFIRMED + 허가 → (송신 전 조각이면 DISCARDING + 허가 무효) → (송신 있으면 InstructionSent 처리 뒤 / 없으면 바로) 다음 조각에서 RECEIVING. 대조 실패면 작업은 SendAborted를 편지함에 넣고 끝난다. 끊김 대기 상한은 BR4.5(판정 턴) 또는 response-ready(그 밖의 송신)이고, 넘으면 송신하지 않고 사유 코드 OUTPUT_GAP_NOT_OBSERVED를 남긴다. 자동 응답 시작 대기 값은 W7 관측(말 끝 → 첫 조각 P95 + 여유) 뒤 시간 값 세 곳(R-M5)에 함께 정한다
    violation: 남은 자동 응답 조각 또는 아직 시작 전이던 자동 응답이 새 응답으로 통과하거나, 끊김 없이 지시가 나가면 결함
    source: FR12.5, Q5-A, 설계 검토 R-01 · R-04
  - id: BR11.13
    statement: outputHeld()가 참인 동안(WAITING_CHILD · 종료 확인 진행 중 · 연습 보류) 온 출력 조각은 세대 단계와 관계없이 모두 버리고(판정 턴이면 전사만 모음) lastDeltaAt과 SEGMENT_QUIET만 갱신한다. 구간을 열지 않는다. 보류가 참에서 거짓으로 바뀌는 순간(참여 START · 종료 확인 해소 · 연습 보류 해제) 세대를 올린다(HOLD_RELEASED).
    category: constraint
    applies_to: 출력 경로 · OutputGeneration
    trigger: 보류 중 조각 도착 · 보류 해제
    logic: 지금 refuseHeldResponse의 조각별 거부(응답 id 취소)를 이 규칙으로 대신한다. 보류 해제 뒤에는 BR11.7 끊김 확인을 지나야 조각을 받는다
    violation: 보류 중이나 보류 해제 직후 자동 응답 조각이 기기로 가면 결함(NFR2, project.md 교정 "outputHeld 조건을 먼저 확인")
    source: FR12.5, NFR2, 설계 검토 R-02
  - id: BR11.8
    statement: 공급자 표본율은 TTS 실호출에서 24kHz 수락을 먼저 본다. 받아들여지면 변환 없이 지금 기기 계약(PCM16 24kHz, 20ms = 960바이트)을 그대로 중계한다. 받아들여지지 않으면 ProviderClient 송수신 바로 앞뒤 한 곳에서만 24k ↔ 16k 변환을 하고, 기기 프레임 · 녹음 · 구간 길이 계산은 24kHz 그대로 둔다.
    category: constraint
    applies_to: ProviderClient
    trigger: 음성 송수신
    logic: 표본율은 설정 한 곳(팩토리 상수)에서만 정한다. 변환 경로를 쓰면 공급자 쪽 SHA 일치 단언을 '변환 결과 길이 · 표본 수' 단언으로 바꾸고 J131 편차를 다음 빈 (U11-?) 번호로 적는다
    violation: 기기 쪽 형식이 바뀌거나 변환이 두 곳 이상에서 일어나면 결함
    source: FR12.6, 요구사항 Q7-A, 범위 확장 가정 7
  - id: BR11.9
    statement: 말 끝 · 입력 전사 완료는 TTS 실호출에서 관측된 공급자 사건을 쓴다. 입력 전사 완료 사건이 없으면 서버가 입력 전사 조각이 정해진 시간 동안 끊긴 것으로 확정 전사를 만들고, 그 시간 키의 이름 · 값은 관측 뒤 정해 시간 값 세 곳(R-M5)을 같은 커밋에서 고친다. 지연 기록의 말 끝 출처는 지금 있는 INPUT_TRANSCRIPT_FINAL · LAST_INPUT_FRAME을 쓴다.
    category: policy
    applies_to: ProviderClient · SessionExecutor 입력 경로
    trigger: 아이 말 수신
    logic: IF 공급자 완료 사건 있음 THEN 지금 흐름 ELSE 서버 확정(새 시간 키). 확정 전사는 지금 InputTranscriptDone과 같은 내부 레코드로 넣어 참여 확인 · 종료 의도 · 연습 흐름을 바꾸지 않는다
    violation: 말 끝 신호가 없다는 이유로 참여 확인 · 종료 판정이 영영 시작되지 않으면 결함
    source: FR12.7, 요구사항 Q9-A, 범위 확장 가정 6
  - id: BR11.10
    statement: 가짜 공급자 두 곳(tools/fake-provider, test-support FakeProviderWsServer)은 실제 형식을 내보낸다. 응답 id · output.done을 빼고 음성을 delta에 담으며, 응답 경계는 조각 사이 무음으로 흉내 내고, 지원 목록 15종 밖 type에는 error{error{type invalid_request_error, code invalid_value, param type}}를 돌려준다. 인메모리 대역 FakeProviderPort는 내부 레코드 변경을 따라간다.
    category: constraint
    applies_to: 가짜 공급자
    trigger: 시험 · 검증
    logic: 두 가짜 공급자가 같은 형식 시험 묶음을 통과하고, TTS 실호출에서 사건이 더 관측되면 같은 커밋에서 가짜 공급자에도 더한다
    violation: 가짜 공급자가 실제에 없는 사건을 보내 시험만 통과하면 결함(R-M11)
    source: FR12.8, 범위 확장 가정 8
  - id: BR11.11
    statement: 선 형식 때문에 깨지는 기존 시험 단언은 같은 행동(순서 · 횟수 · 버림)을 세대 · 새 type으로 다시 단언한다. 느슨하게 하거나 지우거나 건너뛰게 하지 않는다. 응답 id 자체를 시험하던 시험은 세대 시험으로 바꾼다. 커버리지 하한 · 계약 대조 시험 · ArchUnit 규칙은 고치지 않는다. 바뀐 단언 목록(파일 · 전 · 후)을 코드 생성 요약에 남긴다.
    category: constraint
    applies_to: 기존 시험
    trigger: Code Generation
    logic: 단언 갱신마다 대응하는 행동 단언이 남아 있는지 확인한다
    violation: 단언 수가 줄거나 행동 단언이 사라지면 결함(R-F2)
    source: FR12.9, 범위 확장 가정 9
  - id: BR11.12
    statement: contracts/ai-io.md 2 · 3 · 8절을 실제 형식으로 고치고, 편차 (z) · (aa) · (U4b-a)는 지우지 않고 "(U11-a…)로 대체"로 표시한다. 계약 편차는 (U11-a)부터, 설계 편차는 U11-Dn으로 따로 매긴다. 계약 표의 메시지 · 필드마다 출처(실호출 · ADR · 기술명세)를 적고, 관측되지 않은 필드는 '미확인'으로 둔다.
    category: constraint
    applies_to: contracts/
    trigger: 선 형식 변경 커밋
    logic: 코드 변경과 같은 커밋에서 계약을 고친다(R-M10)
    violation: 계약과 코드 형식이 다르거나 대체 관계를 따라갈 수 없으면 결함
    source: FR12.10, FR12.1, 범위 확장 가정 10, 요구사항 검토 R-05

  # BR12 공급자 키 · Luna 스위치
  - id: BR12.1
    statement: GPT-Live 키는 OPENAI_API_KEY 하나로 받는다. PROVIDER_API_KEY는 운영 코드 · 설정 · 시험 · 검사 목록에서 없앤다. 옛 이름은 RUNBOOK 이름 바꾸기 절차, contracts/deviations.md의 대체 표시, 지난 작업 기록 · 단위 보고서에만 남는다.
    category: constraint
    applies_to: 비밀 이름
    trigger: 키 통일 커밋
    logic: 저장소 검색에서 위 예외 밖 PROVIDER_API_KEY가 0건
    violation: 예외 밖에 옛 이름이 남거나 값이 어디든 적히면 결함(R-F3)
    source: FR13.1, 요구사항 Q11-B, 요구사항 검토 R-02
  - id: BR12.2
    statement: realtime 기동 점검은 GPT-Live 키를 demo 프로필에서만 필수로 본다. CI · 검증 · 로컬은 키 없이 가짜 공급자에 연결하고 자리값을 넣지 않는다. 키가 비어 있으면 연결 요청에 Authorization 헤더를 싣지 않는다. demo 공급자 주소 규칙(ProviderHosts)은 그대로다.
    category: policy
    applies_to: RealtimeStartupChecks · ProviderClient · infra/ci/gen-env.sh
    trigger: 기동 · 연결
    logic: IF profile = demo AND 키 비어 있음 THEN 기동 거부 ELSE 계속; IF 키 비어 있음 THEN 헤더 없음
    violation: CI · 검증 env에 키 자리값이 남거나, demo가 키 없이 뜨면 결함
    source: FR13.1, Q6-B
  - id: BR12.3
    statement: realtime의 Luna 실제 어댑터는 키가 비어 있지 않고 mujoong.luna.enabled가 true일 때만 켜진다(기본 false). 기동 기록 LUNA_STARTUP lunaState는 ENABLED · DISABLED_NO_KEY · DISABLED_SWITCH_OFF 중 하나다. core-api의 Luna는 지금처럼 키만으로 켜진다(동작 그대로).
    category: policy
    applies_to: realtime LunaConfiguration · LunaStartup
    trigger: 앱 기동
    logic: realtime 조건 = keyPresent AND enabled. 꺼져 있으면 JudgeClient는 보류 판정기(BR1.6). 스위치는 시간 값이 아니라 R-M5 대상이 아니고, contracts/ai-io.md 10절을 같은 작업에서 고친다(R-M10)
    violation: 스위치가 꺼졌는데 realtime이 Luna를 부르거나, core 동작이 바뀌면 결함
    source: FR13.1, Q6-B, Q6-1-A, 요구사항 검토 R-01
  - id: BR12.4
    statement: infra/RUNBOOK.md 단계 4의 R-F15 확인 대상을 'Luna 판정(core · realtime) · GPT-Live 음성 중계'로 넓힌다. demo에서 OPENAI_API_KEY를 넣거나 realtime mujoong.luna.enabled를 true로 바꾸기 전에 확인 기록을 남긴다. Parameter Store는 PR1 배포 전에 /mujoong/demo/OPENAI_API_KEY에 GPT-Live 키가 들어 있는지 확인하고, PR1 이전 이미지로 롤백할 수 있는 동안(롤백 대상인 마지막 result=OK SHA가 PR1 이전일 수 있는 동안)은 /mujoong/demo/PROVIDER_API_KEY를 같은 값으로 함께 두었다가, 롤백 대상이 PR1 이후 SHA뿐이게 된 뒤에 지운다는 순서를 적는다.
    category: policy
    applies_to: infra/RUNBOOK.md
    trigger: 키 통일 커밋
    logic: 이번 작업은 절차만 적고 실제 Parameter Store를 바꾸지 않는다(배포 범위 밖)
    violation: R-F15 확인 없이 실제 아동 음성 · 전사가 공급자로 갈 수 있는 절차가 남으면 결함
    source: FR13.2, Q6-B, 요구사항 검토 R-01 · R-02, R-F15
  - id: BR12.5
    statement: 키 통일은 같은 커밋에서 비밀 이름 검사 목록 전부(ci.yml 번들 검사, infra/ci/image-checks.sh, infra/bin/infra-validate · render-env · log-hygiene-check, scripts/verify/, infra/ci/gen-env.sh, README.md, infra/RUNBOOK.md Parameter Store 항목)와 관련 인프라 시험 · 불변식 표본을 고친다.
    category: constraint
    applies_to: 비밀 이름 검사 목록
    trigger: 키 통일 커밋
    logic: infra-validate · 이미지 점검 · 비밀 스캔이 통과해야 한다
    violation: 목록 중 하나라도 옛 이름으로 남으면 결함(R-M9)
    source: FR13.2, R-M9
```

## 규칙 요약

| ID | 한 줄 요약 | 분류 | 출처 |
|---|---|---|---|
| BR1.1 | 판정은 `JudgeClient` → U9 Luna로만 | policy | FR1.1 |
| BR1.2 | 선택지는 세션에 고정된 판 순서 그대로 | constraint | FR1.2 |
| BR1.3 | 0.6 · 0.15 보류 기준, 경계값은 확정 | calculation | FR1.3 · AC1.8.1 |
| BR1.4 | 4초 시한(4.000초 만기는 보류) · 오류는 재시도 없이 보류 | policy | FR1.4 · AC1.8.2 |
| BR1.5 | 선택지 밖 · 중복 · 확률 이상은 보류 | validation | FR1.5 |
| BR1.6 | Luna가 없으면(키 · 스위치) 턴을 열지 않고 지금 흐름 + 기록 1건 | policy | FR1.6 |
| BR1.7 | 최소 입력 · 아이 이름 치환 | constraint | NFR3 |
| BR1.8 | 결과는 일반 편지함으로만 | constraint | FR5.2 |
| BR2.1 | 판정 턴마다 기록 1건 | policy | FR2.1 · AC1.8.6 |
| BR2.2 | 판정 기록은 보호자 쪽에 안 나감 | authorization | FR2.1 |
| BR2.3 | V5 · 판 단언은 새 사실로만 갱신 | constraint | FR2.2 |
| BR2.4 | 삭제 순서 180, 보호자 기준 | policy | FR2.3 |
| BR2.5 | 기록 쓰기는 비동기 · 재시도 멱등 | policy | NFR8 |
| BR3.1 | 판정 턴은 세대 올림, 음성 버리고 전사만 모음(취소 요청 없음) | policy | FR3.1 |
| BR3.2 | 버린 출력은 대화 기록 · 중계 · 버퍼에 안 들어감 | constraint | FR3.1 |
| BR3.3 | 모든 서버 송신은 한 직렬 경로: 올림 → 끊김 → append → create | constraint | FR3.2 · FR12.3 |
| BR3.4 | 지시 뒤 세대 응답만 게이트로 | constraint | FR3.2 · AC1.10.2 |
| BR3.5 | 지시문에 라벨 · 확률 · 기준 · 원문 없음 | validation | FR3.3 |
| BR3.6 | 판정 턴 중 '생각 중' 유지, 닫히면 '듣는 중' 복원 | policy | FR3.4 · AC1.10.4 |
| BR3.7 | 지시 응답 = 송신 처리 뒤 첫 조각 | constraint | FR3.2 · Q5-A |
| BR4.1 | 10초 시계는 판정 단계만, 말 끝부터(4초 포함), 지시 단계로 가면 풂 | policy | FR4.1 · Q3-A |
| BR4.2 | 보류면 되묻기 · 맞장구 지시(J1 · J6은 기존 흐름) | policy | FR4.2 · AC1.8.3 |
| BR4.3 | 판독 불가 입력은 공급자 없이 보류 | validation | FR4.3 · AC1.8.5 |
| BR4.4 | 되묻기 때 세대 올림 · 끊김 뒤 송신 | constraint | FR4.1 |
| BR4.5 | 끊김 대기 + 지시 응답 대기 합쳐 10초, 넘으면 닫고 복원 | policy | FR4.1 · AC1.10.4 |
| BR5.1 | 장애면 FAULT_CANCELLED, 회복은 지금 흐름 | policy | FR5.1 · AC1.10.3 |
| BR5.2 | 차례 id · 상태가 다르거나 닫힌 뒤 온 결과는 버림 | validation | FR5.2 |
| BR5.3 | 판정 중 새 전사는 이전 판정을 대체(SUPERSEDED) | policy | FR5.2 |
| BR5.4 | 세션 상태를 벗어나는 모든 전이에서 턴 닫음 | policy | FR5.1 · FR5.2 |
| BR6.1 | 등록 종료 표현은 판정 없이 즉시 종료 | policy | FR6.1 · AC1.7.1 |
| BR6.2 | 판정 턴 중 종료 표현은 판정 중단 · 종료 | policy | FR6.2 · AC1.7.2 |
| BR6.3 | CONFIRM이면 J6, 불명확 · 보류일 때만 확인 질문 | policy | FR6.3 · AC1.7.3 |
| BR6.4 | ACTIVE 휴식은 일반 지시(출력권 확장이면 받아 주는 말만 + 신호) | policy | FR6.4 |
| BR6.5 | J6 종료 근거 `LUNA_J6` | policy | FR6.5 · AC1.7.5 |
| BR7.1 | J1은 `JoinTracker` UNCLEAR일 때만, 결과를 답으로 바꿔 한 번 | policy | FR7.1 |
| BR7.2 | 자유대화 판정은 J1 · J6뿐 | constraint | FR7.1 · AC2.5.1 |
| BR8.1 | 확장점 능력은 기본 구현으로 더함 | constraint | FR8.1 |
| BR8.2 | `requestJudgment` 결과는 코드만, 지시는 확장 | policy | FR8.1 · FR8.2 |
| BR8.3 | 확장 소유 턴의 모은 전사는 사건으로도 | policy | FR8.2 |
| BR8.4 | 한 방향 · 금지 능력 · 정산 창 거절 | authorization | FR8.3 |
| BR9.1 | 실호출은 격리 작업, 운영 형식만, 키 없으면 건너뜀 | policy | FR9.1 |
| BR9.2 | TTS 실호출에 출력 전사가 없으면 중단 | policy | FR9.2 · FR12.7 |
| BR9.3 | PR1은 TTS 실호출 결과가 보고서에 남은 뒤 병합 | policy | FR12.7 · R-03 |
| BR10.1 | 기록 이벤트는 그대로 | constraint | FR10.1 |
| BR11.1 | 세션 시작은 중첩 모양, 아이 정보는 instructions 문장으로 | constraint | FR12.2 |
| BR11.2 | 송신 type은 다섯 가지뿐(correct · 옛 create · cancel 없음) | constraint | FR12.3 |
| BR11.3 | 해석기는 delta · error · usage.updated, 관측된 사건만 | validation | FR12.4 |
| BR11.4 | 지시 = append → create, 일곱 경로 모두 직렬, 누적이면 단발 문장 | constraint | FR12.3 |
| BR11.5 | 출력 세대 · DISCARDING, 취소는 세대로 대체 | constraint | FR12.5 · Q8-A |
| BR11.6 | 구간 완전 = 첫 조각부터 800ms 끊김까지 | policy | FR12.5 · Q4-A |
| BR11.7 | 끊김 = 800ms 조용 + 자동 응답 끝 · 시작 대기, 허가 대조 뒤 송신 → 다음 조각부터 새 응답 | constraint | FR12.5 · Q5-A |
| BR11.8 | 24kHz 먼저, 안 되면 `ProviderClient` 한 곳에서 변환 | constraint | FR12.6 |
| BR11.9 | 말 끝 · 입력 전사는 관측 사건, 없으면 서버 확정 | policy | FR12.7 |
| BR11.10 | 가짜 공급자 두 곳 실제 형식 · 목록 밖 type은 오류 | constraint | FR12.8 |
| BR11.11 | 형식 단언은 행동을 남긴 채 새 형식으로 | constraint | FR12.9 |
| BR11.12 | `ai-io.md` 개정 · 편차 대체 표시 · 번호 체계 | constraint | FR12.10 |
| BR11.13 | 출력 보류 중 조각은 모두 버림, 보류 해제 때 세대 올림 | constraint | FR12.5 · NFR2 |
| BR12.1 | 키는 `OPENAI_API_KEY` 하나, 옛 이름은 정한 예외에만 | constraint | FR13.1 |
| BR12.2 | GPT-Live 키는 demo만 필수, CI 자리값 없음 | policy | FR13.1 · Q6-B |
| BR12.3 | realtime Luna = 키 + 스위치(기본 꺼짐), core 그대로 | policy | FR13.1 · Q6-1-A |
| BR12.4 | R-F15 확인 대상 확대 · Parameter Store 순서(롤백 가능한 동안 두 이름 유지) | policy | FR13.2 · R-F15 |
| BR12.5 | 비밀 이름 검사 목록 전부 같은 커밋 | constraint | FR13.2 · R-M9 |
