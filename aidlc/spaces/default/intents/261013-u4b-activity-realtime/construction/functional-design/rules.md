# U4b 업무 규칙 — 활동 세션 Realtime 쪽

> Functional Design(3.1). 아래 YAML이 정본이다. 출처 표기는 다음과 같다.
> - `FRn.m` · `NFRn`: 이 작업 기록의 `requirements.md` ID
> - `U4b-Rn` · `U4b-Jn`: `docs/units/u4b/design.md`의 규칙 · 가정
> - `FD-Qn`: 이 단계 질문의 답

```yaml
rules:
  # BR1 전이 · 사유 · 끝내기
  - id: BR1.1
    statement: 세션 전이는 전이표(functional-spec 3.1)에 있는 (사건, 출발 상태) 쌍만 성립한다.
    category: constraint
    applies_to: ActivitySession.status
    trigger: 전이 사건 도착
    logic: IF (사건, 현재 상태)가 표에 있으면 THEN 표의 도착 상태로 전이한다
    violation: 상태를 바꾸지 않고 SessionEvent TRANSITION_IGNORED{사건, 상태}를 남긴다
    source: [FR1.1, U4b-R1]
  - id: BR1.2
    statement: 한 전이는 승자 하나만 이기고, status_version + 1과 SessionStateChanged를 같은 트랜잭션에 쓴다.
    category: constraint
    applies_to: ActivitySession
    trigger: 전이 성립
    logic: IF 조건부 갱신이 1행을 바꾸면 THEN 같은 트랜잭션에 상태 사건 Outbox를 쓴다. 0행이면 다른 전이가 먼저 이긴 것이다
    violation: 진 쪽은 부수 효과(기기 명령 · 시계)를 실행하지 않는다
    source: [FR1.1, NFR3]
  - id: BR1.3
    statement: 종료 사유는 전이 사건이 정하며, CLOSING에 들어갈 때 마무리 사유를 end_reason에 쓴다.
    category: policy
    applies_to: ActivitySession.end_reason, end_detail
    trigger: CLOSING · DECLINED · ABORTED · FAILED로 가는 전이
    logic: "사건 → 사유: 보호자 끝내기 → GUARDIAN_STOP; 종료 의도 확정 → CHILD_STOP; 연습 마지막 목표 끝 → CHILD_STOP과 end_detail PRACTICE_COMPLETED; 공백 6분 → SILENCE; 동의 철회 → CONSENT_WITHDRAWN; 참여 거절 → DECLINED; 끊김 40초 · 공급자 끊김 → DISCONNECT; 시작 실패 · Core 취소 · 재시작 → FAILED(시작 단계 동의 철회만 CONSENT_WITHDRAWN)"
    violation: 이 표에 없는 사건으로는 종료 전이를 성립시키지 않는다(BR1.1의 TRANSITION_IGNORED)
    source: [FR1.2, U4b-R1, U4b-J7, FD-Q6]
  - id: BR1.4
    statement: ENDED는 CLOSING의 사유를 유지하고, ENDED_UNCONFIRMED는 사유를 UNCONFIRMED로 덮고 처음 사유를 end_detail에 둔다.
    category: policy
    applies_to: ActivitySession.end_reason, end_detail
    trigger: CLOSING에서 끝나는 전이
    logic: IF 음성 세션 닫힘 THEN ENDED, 사유 · end_detail 유지. IF 30초 시한 THEN ENDED_UNCONFIRMED, end_reason=UNCONFIRMED, end_detail=처음 사유(PRACTICE_COMPLETED가 있었으면 'CHILD_STOP/PRACTICE_COMPLETED')
    violation: 없음(전이표가 강제)
    source: [FR1.2, U4b-J7]
  - id: BR1.5
    statement: 끝내기 요청은 사유 × 상태별 응답표(functional-spec WF12)대로 답한다.
    category: policy
    applies_to: 내부 끝내기 요청
    trigger: Core의 끝내기 요청
    logic: "GUARDIAN_STOP: 시작 단계 409, WAITING_CHILD · ACTIVE 202 {CLOSING}, CLOSING 202 {CLOSING}, 종료 · 모르는 세션 409. CONSENT_WITHDRAWN: 시작 단계 202 {} + FAILED(CONSENT_WITHDRAWN), WAITING_CHILD · ACTIVE 202 {CLOSING}, CLOSING 202 {CLOSING}(사유는 처음 것), 종료 · 모르는 세션 409. CORE_ABANDONED: BR1.9"
    violation: 응답표 밖 사유 값은 400 VALIDATION_FAILED
    source: [FR1.3, FR1.4, U4b-R8, U4b-J9]
  - id: BR1.6
    statement: 같은 세션의 끝내기 요청은 requestId와 관계없이 멱등이다.
    category: constraint
    applies_to: 내부 끝내기 요청
    trigger: 두 번째 이후 끝내기 요청
    logic: IF 이미 CLOSING이거나 종료면 THEN 전이 · 기기 종료 명령을 다시 내지 않고 응답표의 응답만 돌려준다
    violation: 없음
    source: [FR1.3, NFR3]
  - id: BR1.7
    statement: 동의 철회를 받은 뒤에는 아이 전사와 원음 프레임을 저장하지 않는다.
    category: policy
    applies_to: Transcript, 원음 기록
    trigger: termination_requested_at 이후 도착한 아이 전사 · 원음
    logic: IF 철회 끝내기를 받았고 도착 시각 > termination_requested_at THEN 버린다
    violation: 없음(버린 건수만 SessionEvent에 남긴다)
    source: [FR1.4, U4b-R9]
  - id: BR1.8
    statement: 서버 재시작 · 고아 점검 · 실행기 최상위 예외는 PREPARING부터 CLOSING까지의 세션을 FAILED로 닫는다.
    category: policy
    applies_to: ActivitySession
    trigger: 서버 기동 점검 · 고아 점검 · 실행기 최상위 예외
    logic: IF 상태가 PREPARING · DEVICE_ACK · WAITING_CHILD · ACTIVE · CLOSING THEN FAILED, end_detail SERVER_RESTART. 닫은 세션마다 정산(BR8.1)
    violation: 없음
    source: [FR1.5, U4b-R10]
  - id: BR1.9
    statement: Core 취소는 PREPARING부터 ACTIVE까지의 세션을 FAILED로 닫고, CLOSING · 종료 · 모르는 세션은 상태를 바꾸지 않는다.
    category: policy
    applies_to: ActivitySession
    trigger: CORE_ABANDONED 끝내기 요청
    logic: "PREPARING · DEVICE_ACK · WAITING_CHILD · ACTIVE → FAILED + 202 {}; CLOSING → 202 {}(무동작); 종료 → 202 {}; 모르는 세션 → 묘비 + 202 {}. FAILED로 닫은 세션은 정산(BR8.1)"
    violation: 없음
    source: [FR1.5, U4b-R8]

  # BR2 참여 확인
  - id: BR2.1
    statement: WAITING_CHILD에 들어가면 입력 중계를 열고(원음 기록 없음) 활동 종류에 맞는 시작 안내를 보낸다.
    category: policy
    applies_to: 참여 확인 흐름
    trigger: 기기 준비(OK)와 공급자 session.started가 모두 도착
    logic: THEN WAITING_CHILD로 전이, 입력 중계 시작, INTRO_FREE_TALK 또는 INTRO_PRACTICE 재생(안내 1번째)
    violation: 시작 안내가 패키지에 없으면 BR3.3
    source: [FR2.1, U4b-R2, U4b-J2]
  - id: BR2.2
    statement: 참여 창은 안내 재생 완료 보고를 서버가 받은 시각부터 열고, 처음 창은 join-wait, 안내 뒤 창은 join-wait-after-reprompt를 쓴다.
    category: calculation
    applies_to: 참여 창
    trigger: 안내 재생의 COMPLETED · STOPPED · FAILED 보고
    logic: 창 끝 = 보고 수신 시각 + (처음 안내면 join-wait, 아니면 join-wait-after-reprompt) + 그 사이 기술 일시정지 시간. 경계는 창 끝 시각 포함(그 시각의 대답은 만료로 본다)
    violation: 없음
    source: [FR2.1, FR2.2, U4b-J4, U4b-J5]
  - id: BR2.3
    statement: 참여 창 안의 아이 확정 전사를 네 가지(참여 · 거부 · 주제 거부 · 불명확)로 분류하며, 판정 기준은 되살린 참여 판정기의 기준을 정본으로 한다.
    category: validation
    applies_to: 참여 분류
    trigger: 창 안 아이 확정 전사
    logic: "종료 판정기와 같은 정규화 뒤 이 순서로 본다. 주제 거부 낱말(예: '이거 말고' · '다른 거') 포함 → 주제 거부. 거부 낱말(예: '싫어' · '안 할래') 포함 → 거부. 짧은 수락어와 정확히 같거나 '요'만 붙음(예: '응' · '네') → 참여. 수락 표현(예: '할래' · '좋아') 포함 → 참여. 그 밖과 빈 전사 → 불명확. 낱말 목록의 정본은 커밋 5354a32 JoinTracker의 TOPIC_WORDS · REFUSE_WORDS · ACCEPT_EXACT · ACCEPT_CUES다"
    violation: 없음
    source: [FR2.1, FR2.2, U4b-R2]
  - id: BR2.4
    statement: 참여 결정은 분류 · 사용한 안내 · 남은 안내 수로 정하며, 안내는 처음 · 재안내(또는 되묻기) · 대안의 최대 3번이다.
    category: policy
    applies_to: 참여 확인 흐름
    trigger: 분류 결과 또는 창 만료
    logic: "결정표: 참여 → ACTIVE. 거부 · 주제 거부 → 대안을 아직 안 썼고 안내가 3번 미만이면 DECLINE_ALTERNATIVE 재생(대안 사용), 아니면 DECLINED. 불명확 → 재안내 · 되묻기를 아직 안 썼고 안내가 3번 미만이면 RE_PROMPT 재생(재안내 사용), 아니면 안내 없이 같은 창을 계속 기다림. 창 만료(무응답) → 재안내를 아직 안 썼고 안내가 3번 미만이면 RE_PROMPT 재생(재안내 사용), 아니면 DECLINED. 그래서 '무응답 2회'는 재안내 뒤 다시 만료, '거부 2회'는 대안 뒤 다시 거부를 뜻한다"
    violation: 없음
    source: [FR2.1, U4b-R2, U4b-J4]
  - id: BR2.5
    statement: 참여 전 아이 전사는 분류에만 쓰고 저장하지 않는다.
    category: policy
    applies_to: Transcript
    trigger: WAITING_CHILD 중 아이 확정 전사
    logic: THEN 분류에만 넘기고 전사 행을 쓰지 않는다
    violation: 없음
    source: [FR2.4, U4b-J1]
  - id: BR2.6
    statement: 참여 전과 종료 확인 대기 동안 시작된 공급자 응답은 취소하고, 발언권을 돌려줄 때 응답 생성 요청을 한 번 보낸다.
    category: policy
    applies_to: 공급자 연결
    trigger: WAITING_CHILD · 확인 대기 중 공급자 응답 시작 / ACTIVE 진입 · 확인 뒤 계속
    logic: 응답 시작 → session.response.cancel, 출력은 기기로 보내지 않음. 발언권 반환 → session.response.create 1회
    violation: 없음
    source: [FR2.3, NFR5, U4b-R3, U4b-J3]
  - id: BR2.7
    statement: 대화 지시문에 '참여가 확인되면 관심사나 일상 질문 하나로 시작한다'를 더한다.
    category: policy
    applies_to: 공급자 세션 지시문
    trigger: 공급자 세션 시작
    logic: THEN 기존 지시문 끝에 그 문장을 더한다
    violation: 없음
    source: [FR2.3, U4b-R3]

  # BR3 승인 문구
  - id: BR3.1
    statement: 기기에 보낸 승인 문구는 보낸 순간 AI 전사 한 행으로 남기고, 대기 문구는 남기지 않는다.
    category: policy
    applies_to: Transcript
    trigger: PLAY_APPROVED_CONTENT 송신
    logic: IF 코드가 WAITING_FILLER가 아니면 THEN 전사 행(AI, FINAL, approved_for_display true, 문구 원문, started_at=송신 시각)
    violation: 없음
    source: [FR2.4, U4b-R14]
  - id: BR3.2
    statement: DECLINED 세션은 그 세션의 전사 행을 모두 지운다.
    category: policy
    applies_to: Transcript
    trigger: DECLINED 전이
    logic: THEN 전이 트랜잭션에서 그 세션의 전사 행을 삭제한다. 그 뒤 재생하는 마무리 인사도 행을 남기지 않는다
    violation: 없음
    source: [FR2.4, U4b-J1]
  - id: BR3.3
    statement: 시작 패키지에 없는 승인 문구는 재생만 건너뛰고 시계 · 횟수는 그대로 진행한다.
    category: policy
    applies_to: 승인 문구 재생
    trigger: CLOSING · WAITING_FILLER 외 코드의 재생 요청
    logic: IF 패키지에 코드가 없으면 THEN 곧바로 재생 완료로 보고, SessionEvent APPROVED_CONTENT_MISSING{code}를 세션당 코드별 1회 남긴다
    violation: 없음
    source: [FR2.4, U4b-R15, U4b-J18]

  # BR4 종료 의도 · 마무리
  - id: BR4.1
    statement: 기기 재생 중 들어온 아이 전사는 종료 의도를 판정하지 않는다.
    category: constraint
    applies_to: 종료 의도 판정
    trigger: ACTIVE 중 아이 확정 전사
    logic: IF 기기 재생 중 THEN 판정하지 않는다
    violation: 없음
    source: [FR3.1, U4b-R4]
  - id: BR4.2
    statement: 종료 의도 STOP이면 곧바로 CLOSING(CHILD_STOP)으로 간다. 출력만 막고 유지하는 경로는 없다.
    category: policy
    applies_to: ActivitySession
    trigger: ACTIVE 중 판정 STOP
    logic: THEN 종료 의도 확정 사건으로 CLOSING(CHILD_STOP)
    violation: 없음
    source: [FR3.1, U4b-R4, FD-Q3]
  - id: BR4.3
    statement: 종료 의도 CONFIRM이면 확인 질문을 재생하고 출력을 막은 뒤 확인 창을 연다.
    category: policy
    applies_to: 확인 질문 흐름
    trigger: ACTIVE 중 판정 CONFIRM
    logic: THEN STOP_CONFIRM 재생, 공급자 출력 차단, 재생 완료부터 stop-confirm-wait 창
    violation: 패키지에 STOP_CONFIRM이 없으면 BR3.3(곧바로 창을 연다)
    source: [FR3.1, U4b-R4]
  - id: BR4.4
    statement: 확인 창 안의 첫 확정 전사가 STOP이거나 참여 분류 '참여'면 CHILD_STOP으로 확정하고, 그 밖이거나 대답이 없으면 대화를 잇는다.
    category: policy
    applies_to: 확인 질문 흐름
    trigger: 확인 창 안 첫 확정 전사 또는 창 만료
    logic: STOP · 참여 → CLOSING(CHILD_STOP). 그 밖 · 만료 → 차단 해제, session.response.create 1회
    violation: 없음
    source: [FR3.1, U4b-J6, FD-Q3]
  - id: BR4.5
    statement: 종료가 확정된 뒤에는 그 전에 만들어진 응답을 재생하지 않는다.
    category: constraint
    applies_to: 출력 게이트
    trigger: CLOSING 진입
    logic: THEN 대기 중 출력 버퍼를 버린다
    violation: 없음
    source: [FR3.1]
  - id: BR4.6
    statement: 마무리 사유와 관계없이 STOP_OUTPUT → CLOSING 문구 1회 → 재생 표시 뒤 CLOSE_TRANSPORT(사유) → 확인이면 ENDED, 30초면 ENDED_UNCONFIRMED 순서로 끝낸다.
    category: policy
    applies_to: 마무리 흐름
    trigger: CLOSING 진입
    logic: "STOP_OUTPUT 사유: GUARDIAN_STOP · CHILD_STOP(연습 마지막 목표 포함) · SILENCE는 같은 값, CONSENT_WITHDRAWN은 BEFORE_CLOSING. CLOSE_TRANSPORT 사유: end_reason 값 그대로(GUARDIAN_STOP · CHILD_STOP · SILENCE · CONSENT_WITHDRAWN)"
    violation: CLOSING 문구 재생 실패도 CLOSE_TRANSPORT로 진행한다
    source: [FR3.2, U4b-R5, FD-Q6]
  - id: BR4.7
    statement: DECLINED는 먼저 종료 상태로 바꾼 뒤 같은 인사 순서를 closing-confirm 상한 안에서 돌리고 CLOSE_TRANSPORT(DECLINED)로 끝내며, 정산은 이 인사 순서가 끝난 뒤 시작한다.
    category: policy
    applies_to: 마무리 흐름
    trigger: DECLINED 전이
    logic: THEN CLOSING 문구 1회 → 그 재생 표시 → CLOSE_TRANSPORT(DECLINED) → 정산(BR8.1). 상한을 넘기면 CLOSE_TRANSPORT를 바로 보내고 정산. 같은 기기에 새 세션이 오면 남은 인사를 버리고 정산
    violation: 없음
    source: [FR3.2, U4b-J8]

  # BR5 공백 시계
  - id: BR5.1
    statement: 공백은 ACTIVE에서만 재고, 기준은 ACTIVE 진입 또는 아이의 빈 아닌 확정 전사다.
    category: calculation
    applies_to: 공백 시계
    trigger: ACTIVE 진입 · 아이의 빈 아닌 확정 전사
    logic: 진입 · 빈 아닌 전사 → 누적 0으로 리셋, 경고 해제. 빈 전사는 리셋하지 않는다
    violation: 없음
    source: [FR4.1, U4b-R6]
  - id: BR5.2
    statement: 기기 재생 중 · 서버 출력 대기 · 기술 일시정지 · 확인 질문 대기 동안은 공백을 세지 않고 리셋하지도 않는다.
    category: calculation
    applies_to: 공백 시계
    trigger: 멈춤 조건 시작 · 끝
    logic: 멈춤 조건이 하나라도 참이면 누적을 멈추고, 모두 거짓이 되면 이어서 센다
    violation: 없음
    source: [FR4.1, U4b-J10]
  - id: BR5.3
    statement: 누적 4분이면 공백 경고를 켜고 SILENCE_WARNING을 한 번 재생한다. 안내 재생은 시계를 리셋하지 않는다.
    category: policy
    applies_to: 공백 시계
    trigger: 누적 = silence-warning
    logic: THEN silenceWarning{silentSince, endsAt}, 패키지에 있으면 SILENCE_WARNING 1회
    violation: 없음
    source: [FR4.1, U4b-J11]
  - id: BR5.4
    statement: 누적 6분이면 CLOSING(SILENCE)으로 간다.
    category: policy
    applies_to: ActivitySession
    trigger: 누적 = silence-end
    logic: THEN 공백 종료 사건으로 CLOSING(SILENCE)
    violation: 없음
    source: [FR4.1, U4b-R6]

  # BR6 끊김 · 기술 일시정지
  - id: BR6.1
    statement: WAITING_CHILD · ACTIVE에서 연결이 닫히거나 gap-display 동안 기기 메시지가 없으면 기술 일시정지를 켜고 끊김 구간을 연다.
    category: policy
    applies_to: TechnicalPause, SessionGap
    trigger: 연결 닫힘 · 마지막 메시지 후 gap-display 경과
    logic: THEN technicalPause{DEVICE, since=마지막 메시지 시각}, DISCONNECT 구간 열기(U3와 같은 행)
    violation: 없음
    source: [FR5.1, U4b-R7, U4b-J13]
  - id: BR6.2
    statement: 기기 메시지가 다시 오거나 다시 묶이면 끊김 구간을 닫고 일시정지를 끈다.
    category: policy
    applies_to: TechnicalPause, SessionGap
    trigger: 기기 메시지 · 재연결
    logic: THEN 구간 to=수신 시각, technicalPause=null
    violation: 없음
    source: [FR5.1, U4b-R7]
  - id: BR6.3
    statement: 마지막 기기 메시지 뒤 40초면 WAITING_CHILD · ACTIVE 세션은 ABORTED(DISCONNECT)이고 구간은 열린 채 남는다.
    category: policy
    applies_to: ActivitySession
    trigger: 끊김 시한
    logic: IF 상태가 WAITING_CHILD 또는 ACTIVE THEN ABORTED(DISCONNECT). CLOSING이면 끊김 전이 없음
    violation: 없음
    source: [FR5.1, U4b-R7]
  - id: BR6.4
    statement: ENDED_UNCONFIRMED는 CLOSING 진입부터 ended_at까지를 UNCONFIRMED 구간으로 남긴다.
    category: policy
    applies_to: SessionGap
    trigger: CLOSING_TIMEOUT 전이
    logic: THEN UNCONFIRMED 구간(from=CLOSING 진입, to=ended_at)
    violation: 없음
    source: [FR5.1, U4b-J14]

  # BR7 진행 조회 · 상태 사건
  - id: BR7.1
    statement: 진행 조회는 실행기가 바꿔 쓴 불변 값의 technicalPause · silenceWarning을 읽기만 하고, 종료 세션은 둘 다 null이다.
    category: constraint
    applies_to: 내부 진행 조회
    trigger: 조회 요청
    logic: THEN 최신 불변 값을 읽어 응답. gaps는 DB에서 읽고 UNCONFIRMED 구간을 포함
    violation: 없음
    source: [FR6.1, U4b-R13]
  - id: BR7.2
    statement: SessionStateChanged는 CLOSING에도 endReason을 싣고, 전이 시점에 일시정지 중이면 technicalPause를 싣는다.
    category: policy
    applies_to: SessionStateChanged
    trigger: 전이 성립
    logic: THEN 페이로드에 endReason(있으면) · technicalPause(있으면)
    violation: 없음
    source: [FR1.2, FR6.1, 설계 7절]

  # BR8 정산 · 결과 스냅숏
  - id: BR8.1
    statement: 정산은 정해진 순서로 진행하고, 마지막에 스냅숏 행과 SessionRecordCaptured를 한 트랜잭션에 쓴다.
    category: policy
    applies_to: ResultInputSnapshot
    trigger: 종료 상태 전이(DECLINED는 BR4.7의 인사 순서가 끝난 뒤)
    logic: "1) 공급자 close. 2) 연결이 닫히거나 record-settle가 지날 때까지 대기(정산 창). 3) PRACTICE면 확장점 정산 시점 호출(BR10.6). 4) 전사 기록기 flush. 5) PRACTICE면 확장점에서 practice 값 받기. 6) 스냅숏 조립. 7) 스냅숏 + Outbox 한 트랜잭션"
    violation: 쓰기 실패는 재시도. 서버가 재시작하면 기동 점검이 FAILED 세션을 닫고 다시 정산한다
    source: [FR7.1, U4b-R11, U4b-J15, FD-Q1]
  - id: BR8.2
    statement: record는 utterances · gaps · recordScope · practice · audio로 이루어진다.
    category: calculation
    applies_to: SessionRecord
    trigger: 스냅숏 조립
    logic: utterances는 deleted_at이 빈 확정 아이 전사 + 통과한 AI 말 + 승인 문구, sequence 순, 각 항목 seq · speaker · text · uncertain(=review_needed) · startedAt · endedAt. recordScope.from=started_at(없으면 created_at), to=ended_at. audio는 deleted_at이 빈 원음 메타, 경로는 디렉터리 기준 상대 경로
    violation: 없음
    source: [FR7.1, U4b-J17]
  - id: BR8.3
    statement: DECLINED와 ACTIVE 전에 끝난 세션은 utterances · audio가 빈 목록이고, practice는 객체 또는 null만 쓴다.
    category: policy
    applies_to: SessionRecord
    trigger: 스냅숏 조립
    logic: practice = 연습 확장점이 준 객체, 주지 않으면 null(자유대화 · DECLINED · ACTIVE 전 종료 · U5b 전 PRACTICE)
    violation: 없음
    source: [FR7.1, FD-Q4]
  - id: BR8.4
    statement: snapshotHash는 record를 키 정렬 · 공백 없는 JSON으로 직렬화한 UTF-8 바이트의 sha256이다.
    category: calculation
    applies_to: ResultInputSnapshot.snapshot_hash
    trigger: 스냅숏 조립
    logic: "'sha256:' + hex(sha256(canonical_json(record)))"
    violation: 없음
    source: [FR7.1, U4b-J16]
  - id: BR8.5
    statement: 결과 스냅숏과 SessionRecordCaptured는 세션당 한 번만 만든다.
    category: constraint
    applies_to: ResultInputSnapshot
    trigger: 정산 완료
    logic: IF 그 세션의 스냅숏이 이미 있으면 THEN 다시 쓰지 않는다
    violation: 없음(멱등)
    source: [FR7.1, NFR3]
  - id: BR8.6
    statement: 정산 창 안에 도착한 아이 확정 전사는 발화 시작 시각이 ended_at보다 이르면 저장하고 아니면 버린다.
    category: policy
    applies_to: Transcript
    trigger: 정산 창 안 늦은 전사
    logic: IF utterance.started_at < ended_at THEN 저장 ELSE 버림
    violation: 없음
    source: [FR7.2, U4b-R12]
  - id: BR8.7
    statement: 정산이 끝난 세션에 온 전사 · 기기 메시지 · heartbeat · 중복 ACK · 확장점 사건은 전이 0회이고 기록하지 않는다.
    category: constraint
    applies_to: ActivitySession
    trigger: 정산 뒤 사건
    logic: THEN 무시한다
    violation: 없음
    source: [FR7.2, NFR3]
  - id: BR8.8
    statement: SessionRecordCaptured 페이로드는 계약 스키마의 필드를 세션 행과 스냅숏에서 채운다.
    category: calculation
    applies_to: SessionRecordCaptured
    trigger: 스냅숏 쓰기
    logic: "sessionId=세션 id; childId=세션 child_id; activityType=세션 activity_type; finalStatus · endReason · confirmed=스냅숏; startedAt · endedAt=세션; termination{requestedAt=termination_requested_at, outputStoppedAt=output_stopped_at, transportClosedAt=transport_closed_at, providerClosedAt=provider_closed_at}(없는 값은 null); snapshotId · snapshotVersion · snapshotHash · record=스냅숏"
    violation: 계약 스키마 검증에 실패하면 쓰지 않고 오류로 올린다(시험에서 잡는다)
    source: [FR7.1, NFR8, 계약 6절]

  # BR9 계정 삭제
  - id: BR9.1
    statement: DeletionRequested를 받으면 먼저 그 보호자의 비종료 세션 실행기를 DB 전이 없이 강제 정지한다.
    category: policy
    applies_to: 삭제 처리기
    trigger: DeletionRequested
    logic: THEN 입력 중계 · 원음 기록 · 타이머 · 공급자 닫기, 연결이 살아 있으면 CLOSE_TRANSPORT(CONSENT_WITHDRAWN). 이벤트 · 스냅숏 없음. 상한 deletion-stop-wait. 강제 정지가 끝난 뒤 참가자를 부르므로 음성 참가자의 진행 세션 차단은 이미 멈춘 세션에 대해 무동작이 된다
    violation: 상한을 넘기면 예외로 Inbox 재시도
    source: [FR8.1, U4b-R16, U4b-J19]
  - id: BR9.2
    statement: Realtime 삭제 참가자는 공통 계약을 따르고 순서 값이 작은 것부터 부른다.
    category: policy
    applies_to: DeletionParticipant
    trigger: 강제 정지 뒤
    logic: 음성 100 → 연습 150(U5b, 등록된 빈이 없으면 건너뜀) → 세션 200 → 메시징 900. 반환은 기존과 같은 '이름 → 건수' 지도
    violation: 참가자 예외는 처리 전체를 실패로 돌려 Inbox 재시도
    source: [FR8.1, FD-Q5]
  - id: BR9.3
    statement: 음성 참가자(100)는 기존대로 전사 원문 비우기 · 원음 파일 삭제 · 끊김 구간 · 끊김 사건을 맡고, 세션 참가자(200)는 남은 행을 FK 순서로 실제 삭제한다.
    category: policy
    applies_to: 세션 삭제 참가자
    trigger: 참가자 호출
    logic: "100(기존): 전사 text 비우기 + deleted_at, 원음 파일 삭제 + 메타 deleted_at, 끊김 구간 · 끊김 사건 행 삭제. 200(신설): 그 보호자 세션의 스냅숏 → 세션 사건 → 종료 표현 → 전사 행 → 원음 메타 행 → 세션 행 순 실제 삭제"
    violation: 없음
    source: [FR8.1, NFR9]
  - id: BR9.4
    statement: 마지막 트랜잭션에 DeletionCompleted{counts}를 쓰고, 같은 deletionRequestId로 이미 썼으면 다시 쓰지 않는다.
    category: constraint
    applies_to: DeletionCompleted
    trigger: 참가자 모두 성공
    logic: "counts.sessions=200이 지운 세션 행 수; counts.transcripts=100이 비운 전사 행 수; counts.audioFiles=100이 지운 원음 파일 수; counts.practiceRecords=150이 돌려준 건수(없으면 0). 재시도로 0건이 돌아온 참가자는 0을 더한다"
    violation: 없음(멱등)
    source: [FR8.1, U4b-J20]
  - id: BR9.5
    statement: DeletionCompleted 행이 발행되면 메시징 참가자를 한 번 더 부르고, 파일 삭제가 남으면 Inbox 재시도에 맡긴다.
    category: policy
    applies_to: 삭제 처리기
    trigger: DeletionCompleted PUBLISHED · 파일 삭제 실패
    logic: 발행 → 메시징 정리 1회. 파일 실패 → 예외
    violation: 놓치면 하루 정리 작업이 지운다
    source: [FR8.1, U4b-R16]

  # BR10 연습 확장점
  - id: BR10.1
    statement: 연습 확장점은 ACTIVE 진입 · 아이 확정 전사 · 확장점 사건 · 종료 직후 정산 · 스냅숏 직전 practice 값의 다섯 들어오는 시점을 갖는다.
    category: policy
    applies_to: 연습 확장점
    trigger: 각 시점
    logic: PRACTICE 세션이면 실행기가 실행기 스레드에서 확장점을 부른다. 자유대화는 부르지 않는다
    violation: 확장점 예외는 SessionEvent PRACTICE_EXTENSION_FAILED에 남기고 자유대화처럼 계속한다(정산 건너뜀 · practice null)
    source: [FR9.1, FD-Q1]
  - id: BR10.2
    statement: 확장점의 기본 구현은 아무것도 하지 않고, 그 때문에 PRACTICE의 ACTIVE는 자유대화처럼 동작한다.
    category: policy
    applies_to: 연습 확장점
    trigger: U5b 구현이 없을 때
    logic: 모든 시점이 '처리 안 함'과 practice 값 null을 돌려준다
    violation: 없음
    source: [FR9.1, U4b-R17]
  - id: BR10.3
    statement: 확장점은 목표 끝 신호를 돌려줄 수 있고, 실행기는 다음 목표면 계속, 마지막 목표면 CLOSING(CHILD_STOP, end_detail PRACTICE_COMPLETED)으로 간다.
    category: policy
    applies_to: 연습 확장점
    trigger: 확장점이 돌려준 신호
    logic: NONE → 무동작. NEXT_GOAL → 계속. LAST_GOAL → 연습 마지막 목표 끝 사건으로 CLOSING(BR1.3)
    violation: ACTIVE가 아닐 때 온 신호는 무시하고 SessionEvent에 남긴다
    source: [FR9.1, FD-Q6]
  - id: BR10.4
    statement: 실행기는 확장점에 정해진 출력 능력만 준다.
    category: policy
    applies_to: 연습 확장점
    trigger: 확장점 호출
    logic: "능력: 승인 문구 재생 요청(패키지 코드, 재생 완료가 확장점 사건으로 돌아옴), 공급자 지시문 추가 · 응답 생성 요청, 공급자 응답 취소 · 출력 차단/해제, 이름 붙은 확장점 시계 예약 · 취소(만료가 확장점 사건으로 돌아옴, 기술 일시정지 동안 멈춤), 비동기 작업 결과를 확장점 사건으로 편지함에 넣기. 상태 전이 · DB 쓰기 · 기기 원시 명령은 능력에 없다"
    violation: 능력 밖 요청은 거절하고 SessionEvent에 남긴다
    source: [FR9.1, FD-Q1]
  - id: BR10.5
    statement: 확장점 사건(재생 완료 · 확장점 시계 만료 · 비동기 결과)은 실행기 편지함으로 들어와 실행기 스레드에서 확장점의 사건 시점으로 전달되고, 그 반환 신호는 BR10.3대로 처리한다.
    category: policy
    applies_to: 연습 확장점
    trigger: 확장점 사건 도착
    logic: IF 세션이 ACTIVE THEN 확장점 사건 시점 호출 → 신호 처리. IF 종료 전이 뒤 정산 창이면 BR10.6. IF 정산 뒤면 BR8.7(무시)
    violation: 없음
    source: [FR9.1, FD-Q1]
  - id: BR10.6
    statement: 종료 전이 뒤 정산 창 안에 온 확장점 사건은 확장점에 전달하되 신호는 무시하고, 확장점 정산 시점은 정산 창이 끝난 뒤 한 번 부른다.
    category: policy
    applies_to: 연습 확장점
    trigger: 종료 전이 뒤 정산 창
    logic: 정산 창(record-settle) 안 확장점 사건 → 전달(진행 중 판정 결과를 반영할 기회), 신호 무시. 정산 창이 끝나면 확장점 정산 시점 1회(남은 목표 NOT_REACHED 정리) → practice 값. 정산 창보다 늦게 끝난 판정은 반영되지 않는다(확장점이 uncertain 등으로 정리)
    violation: 없음
    source: [FR9.1, FR7.2, FD-Q1]

  # BR11 시간 값
  - id: BR11.1
    statement: 모든 시간 값은 계약 timing 문서가 정본이고 설정으로 주입하며, 운영 코드에 시간 숫자 상수를 두지 않는다.
    category: constraint
    applies_to: 시간 규칙 전체
    trigger: 시계 예약
    logic: "예약 표에서 1절로 옮기는 5개: mujoong.timing.session.join-wait · mujoong.timing.session.join-wait-after-reprompt · mujoong.timing.session.silence-warning · mujoong.timing.session.silence-end · mujoong.timing.device.gap-display. 새로 넣는 3개: mujoong.timing.session.stop-confirm-wait(3절) · mujoong.timing.session.record-settle(3절) · mujoong.session.deletion-stop-wait(4절, timing 접두 아님). 옮긴 키를 '비소유'로 단언하던 설정 대조 시험의 기대값도 함께 바꾼다"
    violation: 설정 대조 시험 · 시간 상수 검사 실패
    source: [FR10.1, NFR1]

  # BR12 개인정보
  - id: BR12.1
    statement: 로그 · 오류 메시지 · Outbox 페이로드 · SessionEvent payload에 전사 원문과 원음 경로를 넣지 않으며, 결과 스냅숏 record만 예외다.
    category: constraint
    applies_to: 로그 · 사건 · 이벤트 전체
    trigger: 기록 · 발행
    logic: 사건 payload는 코드 · 시각 · 횟수 · 분류 이름만. SessionRecordCaptured.record는 계약 6절대로 원문을 담는다
    violation: 로그 · 사건 검사 시험 실패
    source: [NFR4, U4b-J22]
```

## 규칙 요약

| 묶음 | 규칙 | 핵심 |
|---|---|---|
| BR1 전이 · 사유 · 끝내기 | BR1.1 ~ BR1.9 | 전이표만 성립, 승자 하나, 사건이 사유를 정함(연습 완료는 CHILD_STOP + PRACTICE_COMPLETED), 끝내기 응답표 · 멱등, 동의 철회 뒤 저장 차단, 재시작은 CLOSING까지 · Core 취소는 ACTIVE까지 |
| BR2 참여 확인 | BR2.1 ~ BR2.7 | 시작 안내 → 재생 완료부터 창 → 네 가지 분류(정본 낱말 목록) → 결정표(대안 1회 · 재안내 1회 · 최대 3번), 참여 전 전사 미저장, 공급자 응답 취소 · 생성 1회, 지시문 추가 |
| BR3 승인 문구 | BR3.1 ~ BR3.3 | 보낸 문구는 말풍선 행, DECLINED면 전사 삭제, 빠진 문구는 건너뛰고 시계는 진행 |
| BR4 종료 의도 · 마무리 | BR4.1 ~ BR4.7 | 재생 중 판정 안 함, STOP → 곧바로 CHILD_STOP, CONFIRM → 확인 질문, 공통 마무리 순서와 사유 매핑, DECLINED 인사 뒤 정산 |
| BR5 공백 | BR5.1 ~ BR5.4 | ACTIVE에서만, 빈 아닌 전사로 리셋, 멈춤 조건, 4분 경고 1회, 6분 SILENCE |
| BR6 끊김 | BR6.1 ~ BR6.4 | 10초 점선 · 구간, 복구 시 닫기, 40초 ABORTED(WAITING_CHILD 포함), UNCONFIRMED 구간 |
| BR7 조회 · 사건 | BR7.1 ~ BR7.2 | 불변 값 읽기, CLOSING endReason · technicalPause 싣기 |
| BR8 정산 · 스냅숏 | BR8.1 ~ BR8.8 | 정산 순서, record 구성(삭제 표시 행 제외), practice는 객체/null, 정규화 해시, 세션당 1회, 늦은 사건, 이벤트 페이로드 출처 |
| BR9 삭제 | BR9.1 ~ BR9.5 | 강제 정지 먼저, 순서 값 참가자, 100 · 200 책임 나눔, counts 합산, 발행 뒤 정리 |
| BR10 연습 확장점 | BR10.1 ~ BR10.6 | 다섯 들어오는 시점, 기본 무동작, 목표 끝 신호, 출력 능력, 확장점 사건, 정산 창 처리 |
| BR11 시간 값 | BR11.1 | 정본 한 곳 · 설정 주입 · 키 목록과 시험 갱신 |
| BR12 개인정보 | BR12.1 | 로그 · 사건 · 이벤트에 원문 없음 |
