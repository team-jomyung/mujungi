# U6 업무 규칙 — 결과와 리포트

> Functional Design(3.1) · 작업 기록 `261014-u6-result-report`. 아래 YAML이 정본이다.
>
> 입력: `inception/requirements-analysis/requirements.md`(FR1 ~ FR16, NFR1 ~ NFR12), 이 단계 질문 `functional-design-questions.md`(FD-Q1 ~ FD-Q7), `entities.md`, 계약 `contracts/ai-io.md` 6절, 원래 기록의 스토리 수용 기준(AC3.6.x · AC4.3.x · AC5.1.x ~ AC5.5.x)과 화면 문구(mockups §9).
>
> 출처 표기
> - `FRn.m` · `NFRn`: 이 작업 기록의 요구사항 ID
> - `FD-Qn`: 이 단계 질문의 답(모두 A)
> - `ACx.y.z`: 원래 기록 `261008-mujoong-service`의 수용 기준
> - `ai-io 6.x`: `contracts/ai-io.md` 6절
> - `C1 ~ C24` · `G1 ~ G18`: 요구사항 단계의 가정 번호
>
> 이 작업 기록은 units-generation · domain-design · contract-design을 건너뛰었다. 원래 기록의 컴포넌트 설계(ResultPipeline · Report)와 기존 코드 구조를 도메인 설계로 본다. FR16(계약 · 문서 · 시험 정비)은 작업 방식 요구사항이라 업무 규칙을 만들지 않는다(functional-spec 10절).

```yaml
rules:
  # ── BR1 결과 작업 실행기 (JobRunner) ─────────────────────────────────────
  - id: BR1.1
    statement: 결과 작업 상태는 functional-spec 3.1 전이표에 있는 (사건, 출발 상태) 쌍으로만 바뀐다.
    category: constraint
    applies_to: ResultJob.status
    trigger: 선점 · 시도 끝 · 기동 점검 · 마감 확인
    logic: IF (사건, 현재 상태)가 전이표에 있으면 THEN 표의 도착 상태로 바꾼다. 조건부 갱신(WHERE status = 출발 상태)으로 한 승자만 이긴다
    violation: 0행이 바뀌면 다른 쪽이 먼저 이긴 것이다. 진 쪽은 결과를 쓰지 않고 버린다
    source: [FR1.1, NFR7]
  - id: BR1.2
    statement: 실행기는 result.job-tick(1초)마다 실행할 때가 된 REQUESTED 작업을 SKIP LOCKED로 선점한다.
    category: policy
    applies_to: ResultJob
    trigger: 실행기 주기
    logic: IF status = REQUESTED AND next_attempt_at ≤ 지금 THEN 행을 FOR UPDATE SKIP LOCKED로 잡고 RUNNING · attempt_count + 1 · lease_until = 지금 + 시도 시한으로 바꾸고 ResultJobAttempt(attempt_no = attempt_count)를 같은 트랜잭션에 쓴다. 실행 스레드는 2개(result-workers)이고 그중 1개는 REPORT 전용이다 — 주기마다 REPORT를 먼저 선점하고, SESSION_RESULT는 동시에 최대 1개만 돈다. REPORT가 없으면 REPORT 전용 스레드도 SESSION_RESULT를 받지 않는다(60초 마감이 대기 중에 흐르지 않게)
    violation: 그 종류의 실행 스레드가 다 차 있으면 이번 주기에는 선점하지 않는다(다음 주기에 다시 본다)
    source: [FR1.1, FR9.3, NFR1, FD-Q1, FD-Q2]
  - id: BR1.3
    statement: 시도 시한은 SESSION_RESULT가 result.attempt-timeout(30초), REPORT가 deadline_at까지 남은 시간이다.
    category: constraint
    applies_to: ResultJob.lease_until
    trigger: 선점
    logic: SESSION_RESULT면 lease_until = 시작 + 30초. REPORT면 lease_until = deadline_at. 처리기 실행은 이 시각에 취소한다. AI 호출 한 번의 시한 = min(text-ai.result.request-timeout 10초, lease_until − 지금)이라 호출이 몇 번이든 시도 시한을 넘지 않는다(SESSION_RESULT는 많아야 4번이고, 앞 호출이 길면 뒤 호출 시한이 줄어 TIMEOUT으로 끝난다). 결과 · 리포트 AI 호출은 게이트웨이 안에서 재시도하지 않는다 — U5a의 text-ai.retry.*(1회 · 250ms)를 물려받지 않고, 재시도는 작업 단위(BR1.7)로만 한다
    violation: 시한을 넘긴 시도는 TIMEOUT 실패다(BR1.6)
    source: [FR1.1, FR9.3, FD-Q2]
  - id: BR1.4
    statement: 같은 대상(subject_id)에 REQUESTED · RUNNING 작업은 하나뿐이다.
    category: constraint
    applies_to: ResultJob
    trigger: submit
    logic: IF 같은 subject_id에 REQUESTED · RUNNING 작업이 있으면 THEN 새 행을 만들지 않고 그 작업을 돌려준다. 부분 유일 색인이 마지막 방어선이다
    violation: 색인 충돌은 이미 있는 작업을 다시 읽어 돌려준다(오류로 내지 않음)
    source: [FR1.1, NFR7, AC5.2.7]
  - id: BR1.5
    statement: submit은 같은 idempotency_key에 늘 같은 작업을 돌려준다.
    category: constraint
    applies_to: ResultJob.idempotency_key
    trigger: submit{jobType, subjectId, idempotencyKey}
    logic: IF 같은 키 행이 있으면 THEN 상태와 관계없이 그 작업 {jobId, status}를 돌려준다. 없으면 REQUESTED · next_attempt_at = 지금으로 만든다
    violation: 같은 키에 다른 jobType · subjectId면 내부 오류(IllegalStateException)로 실패한다(프로그래밍 오류)
    source: [FR1.1, NFR7]
  - id: BR1.6
    statement: 시도 실패는 공급자 오류 · 시간 초과 · 출력 스키마 실패 · 내부 오류뿐이다. 인용 불일치 · 금지 표현은 실패가 아니다.
    category: policy
    applies_to: ResultJobAttempt.outcome
    trigger: 처리기 실행 끝
    logic: "IF 공급자 UNAVAILABLE THEN PROVIDER_UNAVAILABLE; 공급자 예외 THEN PROVIDER_ERROR; 시도 시한 · AI 호출 시한 초과 THEN TIMEOUT; JSON 스키마 불일치 THEN SCHEMA_INVALID; 그 밖 예외 THEN INTERNAL. 인용 대조 · 금지 표현에 걸린 문장은 템플릿으로 바꾸고 시도는 계속한다(BR5.3 · BR9.6)"
    violation: 실패 사유는 코드값만 남기고 전사 · 08 원문 · AI 출력 본문을 로그 · 오류에 넣지 않는다(NFR4)
    source: [FR1.1, ai-io 6.1, NFR4]
  - id: BR1.7
    statement: 실패한 시도는 남은 시도와 시간이 있으면 간격을 두고 다시 하고, 아니면 FAILED로 끝낸다.
    category: policy
    applies_to: ResultJob
    trigger: 시도 실패
    logic: IF attempt_count < result.job-attempts(3) AND (SESSION_RESULT OR 지금 + 간격 < deadline_at) THEN REQUESTED · next_attempt_at = 지금 + result.job-retry-backoff[attempt_count − 1](attempt_count 1 → 5초, 2 → 15초; 목록은 0부터 센다) · last_error_code 기록. ELSE FAILED · finished_at · last_error_code
    violation: 없음(전이표가 강제)
    source: [FR1.1, FR9.3, C2]
  - id: BR1.8
    statement: AI 호출은 데이터베이스 트랜잭션 밖에서 하고, 결과는 짧은 트랜잭션 하나로 쓴다.
    category: constraint
    applies_to: 처리기(SESSION_RESULT · REPORT)
    trigger: 시도 실행
    logic: 읽기 트랜잭션으로 입력을 모아 닫고 → AI를 부르고 → 쓰기 트랜잭션에서 작업이 아직 RUNNING이고 attempt_count가 시작 때와 같은지 확인한 뒤 결과 행 · DONE 전이를 함께 쓴다
    violation: 확인에 실패하면(기동 점검 · 다른 시도가 이김 · 삭제 시작) 결과를 쓰지 않고 버린다
    source: [FR1.1, FD-Q1, FR6.2]
  - id: BR1.9
    statement: 앱이 기동하면 RUNNING 작업을 FAILED로 굳히고, REQUESTED 작업은 그대로 이어서 실행한다.
    category: policy
    applies_to: ResultJob
    trigger: 앱 기동(실행기 시작 전 한 번)
    logic: RUNNING → FAILED(last_error_code = SERVER_RESTART), 그 시도는 ABANDONED. REQUESTED는 바꾸지 않는다. 이 규칙은 core-api가 한 인스턴스로만 도는 지금 배포(compose 복제 1)를 전제한다. SKIP LOCKED는 한 인스턴스 안의 실행 스레드끼리 겹치지 않게 하는 용도다. 여러 인스턴스로 늘리면 기동 점검을 "lease_until이 지난 RUNNING만"으로 바꿔야 한다(가정 U6-K, functional-spec 11절)
    violation: 없음
    source: [FR1.1, G5]
  - id: BR1.10
    statement: lease_until을 지났는데 RUNNING인 작업은 그 시도를 TIMEOUT 실패로 처리한다.
    category: policy
    applies_to: ResultJob
    trigger: 실행기 주기
    logic: IF status = RUNNING AND lease_until < 지금 THEN 시도 outcome FAILED · TIMEOUT으로 BR1.7을 적용한다. REPORT는 deadline_at을 지났으므로 FAILED(DEADLINE_EXCEEDED)가 된다
    violation: 없음
    source: [FR1.1, FR9.3]
  - id: BR1.11
    statement: SESSION_RESULT 작업 상태가 바뀌는 트랜잭션은 SessionResultState.job_status도 같은 값으로 바꾼다.
    category: constraint
    applies_to: SessionResultState.job_status
    trigger: SESSION_RESULT 작업 전이
    logic: IF 바뀐 작업이 그 세션의 current_job_id면 THEN job_status = 작업 status, updated_at = 지금
    violation: current_job_id가 아닌 작업(이미 다른 차수로 넘어간 작업)의 전이는 머리 행을 바꾸지 않는다
    source: [FR8.2, AC3.6.12]
  - id: BR1.12
    statement: REPORT 작업 상태가 바뀌는 트랜잭션은 리포트 상태도 같은 트랜잭션에서 바꾸고, 버려진 리포트의 작업은 REPORT_DISCARDED로 끝낸다.
    category: constraint
    applies_to: Report.status, ResultJob(REPORT)
    trigger: REPORT 작업 DONE · FAILED 전이, 시도 시작
    logic: "DONE 전이 = 리포트가 GENERATING일 때만 READY(BR1.8 확인 안에서). FAILED 전이 = 리포트가 GENERATING이면 FAILED로 함께 바꾼다. 시도 시작 · 쓰기 확인에서 리포트가 DISCARDED면 작업을 FAILED(last_error_code = REPORT_DISCARDED)로 끝내고 다시 시도하지 않는다(리포트는 DISCARDED 그대로)"
    violation: 없음. 리포트와 작업의 끝 상태가 어긋난 채 남지 않는다
    source: [FR1.1, FR10.1, FR10.2]

  # ── BR2 결과 접수 · 정리 대상 · 다시 시도 ──────────────────────────────────
  - id: BR2.1
    statement: ActivityRecorded를 받으면 그 세션의 정리 대상 여부를 정하고, 대상이면 SESSION_RESULT를 하나 접수한다.
    category: policy
    applies_to: SessionResultState, ResultJob
    trigger: ActivityRecorded(커밋 뒤 앱 이벤트)
    logic: 새 트랜잭션에서 SessionResultState가 없으면 BR2.2로 판정한다. 판정이 UNDECIDED면 머리 행을 만들지 않고 끝낸다(누락 점검 BR2.3이 다시 본다). ELIGIBLE이면 머리 행을 INSERT … ON CONFLICT (session_id) DO NOTHING으로 넣고, 1행이 들어갔을 때만 run_ordinal = 1 · submit(SESSION_RESULT, sessionId, 'SR:' + sessionId + ':1', ACTIVITY_RECORDED) · current_job_id · job_status = REQUESTED. NOT_ELIGIBLE이면 같은 방식으로 job_status = NONE 머리 행만 넣는다
    violation: SessionResultState가 이미 있거나 삽입이 0행이면(이벤트 처리와 누락 점검이 겹침) 아무것도 하지 않는다(같은 세션 한 번만, 진 쪽은 작업을 만들지 않음)
    source: [FR2.1, NFR7]
  - id: BR2.2
    statement: 정리 대상은 종료 상태가 된 활동 기록 중 발화가 있는 것이다.
    category: validation
    applies_to: SessionResultState.eligibility
    trigger: BR2.1 · BR2.3
    logic: "판정 함수 하나(ResultEligibility)를 접수 · 누락 점검 · 정리 상태 응답(BR8.3) · 26 · 27 · 28 노출(BR14.1)이 함께 쓴다. ELIGIBLE = final_status ∈ {ENDED, ENDED_UNCONFIRMED, ABORTED} AND 전사 발화 ≥ 1, 또는 final_status = FAILED AND 전사 발화 ≥ 1 AND 활동 사본 core_activity.status = FAILED AND failure_code = SERVER_RESTART. UNDECIDED = final_status = FAILED AND 전사 발화 ≥ 1 AND 활동 사본이 아직 FAILED로 바뀌지 않음(SessionStateChanged가 SessionRecordCaptured보다 늦게 도착한 경우, 두 이벤트의 도착 순서는 보장되지 않는다). 그 밖(DECLINED, 발화 0건, 사본이 FAILED이고 failure_code가 SERVER_RESTART가 아님)은 NOT_ELIGIBLE. 사본의 상태와 failure_code는 같은 SessionStateChanged 반영에서 함께 바뀌므로, 사본이 FAILED면 failure_code는 이미 정해져 있다"
    violation: NOT_ELIGIBLE 세션에는 작업을 만들지 않고 jobStatus NONE을 낸다. UNDECIDED는 머리 행 없이 두고 판정을 미룬다
    source: [FR2.2, FR15.1, AC3.6.12]
  - id: BR2.3
    statement: 기동 때 한 번, 그 뒤 result.reconcile-interval(5분)마다 머리 행이 없는 활동 기록을 접수한다.
    category: policy
    applies_to: SessionResultState
    trigger: 기동 · 누락 점검 주기
    logic: "① core_activity_record에 있고 core_session_result_state에 없는 세션(UNDECIDED로 미룬 것 포함)을 recorded_at 순으로 최대 100건 읽어 각각 BR2.1과 같은 처리를 한다(trigger_type RECONCILE). ② 현재 결과 버전이 DONE인데 candidates_registered_at이 비어 있는 결과를 최대 100건 골라 후보 등록을 다시 한다(BR6.4). ③ 저장 안 된 리포트 정리(BR10.4)"
    violation: 점검이 실패하면 오류 코드만 로그로 남기고 다음 주기에 다시 본다
    source: [FR2.3, FR6.1, G6, FD-Q2]
  - id: BR2.4
    statement: 「다시 시도」는 현재 작업이 FAILED일 때만 새 차수 작업을 만든다.
    category: validation
    applies_to: SessionResultState, ResultJob
    trigger: POST /api/sessions/{id}/result/retry
    logic: IF job_status = FAILED THEN 조건부 갱신(WHERE job_status = 'FAILED')으로 run_ordinal + 1 · submit(SESSION_RESULT, sessionId, 'SR:' + sessionId + ':' + run_ordinal, RETRY) · current_job_id 교체 · job_status = REQUESTED 후 202 {REQUESTED}
    violation: job_status가 FAILED가 아니면(NONE · REQUESTED · RUNNING · DONE) 409 JOB_NOT_FAILED. 연타 둘째부터는 이미 REQUESTED라 409이고 작업은 1건이다
    source: [FR1.2, AC5.2.7, NFR7]
  - id: BR2.5
    statement: 결과는 버전으로 쌓고, 새 차수가 DONE이 될 때만 현재 버전을 바꾼다.
    category: constraint
    applies_to: ActivityResult, SessionResultState.current_result_id
    trigger: SESSION_RESULT 작업 DONE
    logic: 새 ActivityResult(version = 그 세션 최대 + 1)를 쓰고 current_result_id를 바꾸며 이전 현재 버전의 invalidated_at을 채운다. 원본 활동 기록은 읽기만 한다
    violation: 다시 시도가 FAILED로 끝나면 이전 현재 버전이 그대로 남는다(응답의 jobStatus는 FAILED)
    source: [FR1.2, C21]

  # ── BR3 경험 구조화 (자유대화) ─────────────────────────────────────────────
  - id: BR3.1
    statement: 경험 구조화는 자유대화이고 결과 수준이 INSUFFICIENT가 아닌 세션에서만 한다.
    category: policy
    applies_to: StructuredExperience
    trigger: SESSION_RESULT 처리(자유대화)
    logic: IF activity_type = FREE_TALK AND 사전 결과 수준(BR5.5 1단계) ≠ INSUFFICIENT THEN 구조화 AI를 한 번 부른다. ELSE 구조화 행을 쓰지 않는다
    violation: 없음
    source: [FR3.1, FR5.2]
  - id: BR3.2
    statement: 구조화 AI 입력에는 아이 이름 · id · 원음을 넣지 않고, 08 네 칸은 구조화에만 넣는다.
    category: constraint
    applies_to: 구조화 AI 요청
    trigger: 구조화 호출
    logic: 입력 = 전사(seq · speaker · text, 불확실 표시) + 08 네 칸(있으면) + 종료 사유 · 확보 범위. 아이는 "아이", 다른 사람은 별칭(친구 A). 보호자 · 아이 · 세션 id, 이메일, 원음 참조는 보내지 않는다
    violation: 가짜 공급자가 받은 요청에 이름 · id가 있으면 시험 실패(NFR4)
    source: [FR3.1, NFR4, ai-io 6.1]
  - id: BR3.3
    statement: 구조화 출력의 칸마다 출처를 GUARDIAN_INPUT 또는 CHILD_RECALL로 달고, CHILD_RECALL은 실제 아이 발화 번호를 가져야 한다.
    category: validation
    applies_to: StructuredExperience.content_json
    trigger: 구조화 출력 검사
    logic: IF source = CHILD_RECALL THEN utteranceSeq가 그 세션 전사의 아이(CHILD) 발화 번호여야 하고 text 안 인용은 저장 전사와 글자 단위로 같아야 한다. 맞지 않는 칸은 비운다. 의도 · 감정 칸은 스키마에 없다(추가 속성 금지)
    violation: 스키마 불일치는 SCHEMA_INVALID 시도 실패. 근거 불일치 칸은 비우고 계속한다
    source: [FR3.1, C7, AC5.2.9]

  # ── BR4 기록용 재판정 · 장면 칩 · 사상표 · 도움 ──────────────────────────────
  - id: BR4.1
    statement: 재판정은 practice가 객체이고 사전 결과 수준이 INSUFFICIENT가 아닌 연습 세션에서 온도 0으로 한 번 부른다.
    category: policy
    applies_to: SceneJudgment
    trigger: SESSION_RESULT 처리(연습)
    logic: "IF practice = null THEN 재판정 없이 result_level = INSUFFICIENT · 장면 행 없음 · DONE. IF 사전 수준 INSUFFICIENT THEN AI 없이 모든 장면을 BR4.4 사상표로 NOT_DONE 처리. ELSE 입력(전사 · 목표별 BehaviorDefinitionLookup 정의 · 장면별 ScenarioLookup 상황 · 진행 중 판정 · 도움)으로 ai-io 6.2 출력을 받는다"
    violation: practice가 빈 배열 · 다른 모양이면 SCHEMA_INVALID가 아니라 내부 입력 오류(INTERNAL)로 시도 실패한다
    source: [FR4.1, G3, ai-io 6.2]
  - id: BR4.2
    statement: 목표마다 장면 1(ORIGINAL) · 장면 2(NEW_SCENARIO) 두 행을 쓰고, 출력에 없는 장면은 미실시로 채운다.
    category: constraint
    applies_to: SceneJudgment
    trigger: 재판정 결과 저장
    logic: 목표 × {1, 2}마다 행을 쓴다. 출력에 그 장면이 없거나 장면이 노출되지 않았으면 value = null · not_done_reason = BR4.4 사상표 값
    violation: 출력에 없는 goalSelectionId · sceneNo는 버린다
    source: [FR4.1, ai-io 6.2]
  - id: BR4.3
    statement: 재판정 값이 진행 중 판정과 다르면 differs_from_live = true로 저장하고, 화면 · 리포트는 재판정 값만 쓴다.
    category: calculation
    applies_to: SceneJudgment.differs_from_live
    trigger: 재판정 결과 저장
    logic: live_label = 그 장면의 첫 반응(FIRST · NEW_SCENARIO) label. IF live_label ≠ value(NOT_PERFORMED ↔ null은 같다고 본다) THEN differs_from_live = true
    violation: 없음
    source: [FR4.1, AC5.2.12]
  - id: BR4.4
    statement: 미실시 장면의 사유는 사상표로 정한다(FD-Q3 초안, U5b와 맞출 안건).
    category: calculation
    applies_to: SceneJudgment.not_done_reason
    trigger: value = null인 장면
    logic: "IF 목표 status = NOT_REACHED THEN ACTIVITY_ENDED. IF 목표 status = SKIPPED AND skipReason이 상황 생성 실패 THEN SCENARIO_FAILED. IF 반응 label = NOT_PERFORMED AND notPerformedReason이 시간 종료 THEN TIME_OVER; 무응답(20초) THEN NO_RESPONSE; 그 밖 THEN ACTIVITY_ENDED. IF 장면이 노출되지 않음 THEN ACTIVITY_ENDED. AI 출력의 notDoneReason이 있으면 그 값을 쓰되 위 규칙과 다르면 위 규칙을 따른다"
    violation: 알 수 없는 status · skipReason · notPerformedReason은 ACTIVITY_ENDED로 두고 매핑 로그(코드값만)를 남긴다
    source: [FR4.2, FD-Q3]
  - id: BR4.5
    statement: 장면 칩은 재판정 값에서만 만든다(ai-io 6.2 칩 표).
    category: calculation
    applies_to: SceneJudgment.scene_chip
    trigger: 재판정 결과 저장
    logic: "functional + IMMEDIATE → DONE_IMMEDIATE(바로 전달); functional + AFTER_HELP → DONE_AFTER_HELP(도움 뒤 전달); partial · none → NEXT_TIME(다음 기회로); uncertain → ON_HOLD(보류); null → NOT_DONE(미실시 · {사유}: TIME_OVER 시간 종료 · SCENARIO_FAILED 상황 준비 실패 · ACTIVITY_ENDED 활동 끝 · NO_RESPONSE 반응 없음). functional인데 timing이 없으면 첫 반응에서 나온 것으로 보고 IMMEDIATE"
    violation: 칩 문구는 임시 문구로 금지 표현 사전 검사 대상이다(NFR3)
    source: [FR4.2, AC5.2.10, ai-io 6.2]
  - id: BR4.6
    statement: 목표마다 제공한 도움을 count · consent로 낸다.
    category: calculation
    applies_to: ActivityResult.help_json
    trigger: SESSION_RESULT 처리(연습, practice 객체)
    logic: count = 그 목표 cues 수. consent = 첫 cue(givenAt 가장 이른 것)의 consentResponse(ACCEPTED · DECLINED · NO_RESPONSE). cue가 없으면 count 0 · consent NONE
    violation: 없음
    source: [FR4.3, G4, AC5.2.10]

  # ── BR5 결과 문장 · 결과 수준 · 검사 ───────────────────────────────────────
  - id: BR5.1
    statement: 결과 문장은 연습 목표마다 1~2문장, 자유대화는 활동 하나에 1~2문장이며, 결과 수준이 INSUFFICIENT면 만들지 않는다.
    category: policy
    applies_to: ActivityResult.sentences_json
    trigger: SESSION_RESULT 처리
    logic: IF 최종 수준 = INSUFFICIENT THEN sentences = []. ELSE 결과 문장 AI(ai-io 6.3)를 한 번 부르고 목표마다(연습) · 활동에(자유대화) 앞 2문장까지만 쓴다. 자유대화 문장은 goalSelectionId 없이 저장 · 응답한다
    violation: 문장이 0개인 목표 · 활동은 템플릿 문장 하나로 채운다(BR5.3)
    source: [FR5.1, AC5.2.12]
  - id: BR5.2
    statement: AI가 만든 모든 문장은 스키마 → 인용 대조 → 금지 표현 2단계 순서로 검사한다.
    category: validation
    applies_to: 결과 문장 · 리포트 항목(2 · 5 · 6 · 10)
    trigger: AI 출력 받음
    logic: "① JSON 스키마(어긋나면 SCHEMA_INVALID 시도 실패). ② 인용: quote가 그 utteranceSeq 저장 전사와 앞뒤 공백만 무시하고 글자 단위로 같아야 한다. 다르면 그 인용을 버리고, 문장 안 인용 부호 내용이 어느 저장 발화와도 맞지 않으면 그 문장은 걸린 것으로 본다. ③ 금지 1차 = 금지 사전(appliesTo RESULT_TEXT · PDF) 부분 일치, 인용 부호 안의 전사는 뺀다. ④ 금지 2차 = 문장 전체를 텍스트 AI 분류 한 번(작업당 한 호출로 묶음)"
    violation: ②③④에 걸린 문장은 BR5.3 · BR9.6 템플릿으로 바꾼다. 2차 분류 호출 자체의 공급자 오류 · 시간 초과는 시도 실패다
    source: [FR5.1, FR9.4, FR16.3, NFR3, ai-io 6.1, ai-io 6.6]
  - id: BR5.3
    statement: 검사에 걸린 결과 문장은 고정 템플릿 2종으로 바꾼다.
    category: policy
    applies_to: ActivityResult.sentences_json
    trigger: BR5.2에 걸림 · 문장 0개
    logic: "연습 = '{목표 이름} 연습을 했어요. 장면 {n}개 중 {m}개를 진행했어요.'(n = 그 목표 장면 2, m = NOT_DONE이 아닌 장면 수). 자유대화 = '{n}분 동안 이야기를 나눴어요.'(n = 확보 범위 분, 올림 없이 버림, 최소 1). templated = true"
    violation: 템플릿 문장 자체가 금지 사전에 걸리면 시험 실패(NFR3 고정 문구 검사)
    source: [FR5.1, ai-io 6.3, AC5.2.12]
  - id: BR5.4
    statement: 결과 문장의 숫자는 시각과 장면 번호만 쓴다.
    category: validation
    applies_to: 결과 문장
    trigger: BR5.2 ②와 ③ 사이
    logic: IF 인용 부호 밖에 숫자가 있고 그것이 hh:mm 시각 · '장면 n' 꼴이 아니면 THEN 걸린 것으로 본다
    violation: 템플릿으로 바꾼다(BR5.3)
    source: [FR5.1, ai-io 6.3]
  - id: BR5.5
    statement: 결과 수준은 서버가 두 단계로 정한다 — AI 전에 유효 기록 여부, AI 뒤에 FULL 여부.
    category: calculation
    applies_to: ActivityResult.result_level
    trigger: SESSION_RESULT 처리
    logic: "1단계: 내용 있는 발화 = 아이 확정 발화이고 uncertain이 아니며 공백으로 나눈 어절이 3개 이상. 자유대화 유효 = 내용 있는 발화 ≥ 3. 연습 유효 = 첫 반응 장면(responseType FIRST · NEW_SCENARIO에 utteranceSeq 있음) ≥ 1. end_reason = CONSENT_WITHDRAWN이거나 유효하지 않으면 INSUFFICIENT. 2단계(유효할 때): 정상 마무리 = final_status ENDED AND confirmed AND end_reason ∈ {CHILD_STOP, GUARDIAN_STOP, SILENCE}. 자유대화 FULL = 정상 마무리 AND 구조화 칸 중 CHILD_RECALL 근거가 있는 칸 ≥ 1. 연습 FULL = 정상 마무리 AND 모든 목표에 첫 반응 장면 ≥ 1. 그 밖은 PARTIAL"
    violation: 없음. ENDED_UNCONFIRMED · ABORTED · 재시작 FAILED는 FULL이 될 수 없다
    source: [FR5.2, G2, ai-io 6.4]
  - id: BR5.6
    statement: 결과 수준이 INSUFFICIENT면 AI를 부르지 않고 바로 DONE으로 끝낸다.
    category: policy
    applies_to: SESSION_RESULT 처리
    trigger: 1단계 수준 INSUFFICIENT
    logic: 구조화 · 재판정 · 결과 문장 · 후보 AI를 부르지 않는다. 연습이면 장면 행을 BR4.4로 NOT_DONE 처리하고 도움(BR4.6) · 팁(BR7)은 서버 규칙대로 채운다. 그 뒤 DONE
    violation: 없음. 공급자가 UNAVAILABLE이어도 INSUFFICIENT 세션은 DONE이 된다
    source: [FR5.2, FR4.1]

  # ── BR6 목표 후보 ────────────────────────────────────────────────────────
  - id: BR6.1
    statement: 후보는 결과 수준이 INSUFFICIENT가 아닌 자유대화 · 연습 세션에서 후보 AI 한 번으로 만들고, 세션당 최대 3개다.
    category: policy
    applies_to: 목표 후보(U5a CandidateRegistry)
    trigger: SESSION_RESULT 처리 끝 무렵
    logic: IF 수준 ≠ INSUFFICIENT THEN 후보 AI(입력 = 전사 · 구조화 결과(자유대화) · 장면 칩(연습) · ACTIVE 정의 목록)를 부르고 BR6.2 · BR6.3을 통과한 것 중 앞 3개만 둔다
    violation: 4번째부터는 버린다(로그에 개수만)
    source: [FR6.1, AC4.3.4]
  - id: BR6.2
    statement: 후보의 근거는 그 세션 전사의 아이 회상 발화(utteranceSeq ≥ 1, kind CHILD_RECALL)만 등록하고, 근거가 없는 후보는 버린다.
    category: validation
    applies_to: 후보 evidence
    trigger: 후보 AI 출력 검사
    logic: 근거마다 utteranceSeq가 그 세션의 CHILD 발화이고 quote가 글자 단위로 같아야 남긴다. 보호자 입력 근거(GUARDIAN_INPUT)는 등록하지 않는다(이유 문장 안 출처 언급만 허용). 남은 근거가 0개면 후보를 버린다
    violation: 버린 후보 · 근거는 코드값(사유)만 로그로 남긴다
    source: [FR6.1, Q9 원 답, AC4.3.4]
  - id: BR6.3
    statement: 후보의 의미 검토는 서버 검사(근거 인용 대조 + 금지 2단계 + 정의 ACTIVE)로 한다.
    category: validation
    applies_to: 후보 reasonText · behaviorKey
    trigger: 후보 AI 출력 검사
    logic: reasonText가 BR5.2 ③④를 통과하고 behaviorKey의 정의가 ACTIVE면 남긴다. 감정 · 의도를 단정하는 이유 문장은 2차 분류의 EMOTION_CLAIM으로 걸린다
    violation: 걸린 후보는 버린다(템플릿으로 바꾸지 않음)
    source: [FR6.1, AC4.3.4]
  - id: BR6.4
    statement: 검사를 통과한 후보는 결과 버전과 같은 트랜잭션에 내부 칸으로 저장해 두고, 커밋 뒤에 등록하며, 실패하면 누락 점검이 다시 등록한다.
    category: constraint
    applies_to: ActivityResult.pending_candidates_json · candidates_registered_at, CandidateRegistry.register
    trigger: SESSION_RESULT 결과 저장 커밋 · 누락 점검
    logic: "결과 쓰기 트랜잭션에 pending_candidates_json(등록할 후보 목록, 0개면 빈 배열)을 함께 쓴다. 커밋 뒤 후크가 CandidateRegistry.register{sessionId, childId, candidates}를 부르고 성공하면 candidates_registered_at = 지금. 후크 실패 · 커밋 직후 프로세스 종료로 비어 있으면 누락 점검(BR2.3 ②)이 같은 목록으로 다시 등록한다. U5a 중복 키(세션 + 아이 + 정의 버전)로 합쳐지므로 다시 등록해도 후보가 두 번 생기지 않고, 다시 시도로 같은 후보가 다시 나오면 새 근거만 더해진다. 빈 목록은 바로 candidates_registered_at을 채운다"
    violation: 등록 실패는 오류 코드만 로그로 남기고 작업 상태는 DONE 그대로다. 등록이 끝나기 전까지 27은 후보를 등록된 것만 보인다(누락 점검 주기 5분 안에 채워짐)
    source: [FR6.1, G8, C21]
  - id: BR6.5
    statement: 근거 확인 어댑터는 근거 발화가 그 세션 활동 기록에 실제로 있는지 잠금 없이 확인한다.
    category: validation
    applies_to: CandidateEvidencePort(U6 어댑터)
    trigger: U5a 후보 등록 중 근거 확인
    logic: 활동 기록(core_activity_record)을 읽기 전용으로 읽어 sessionId가 같은 보호자 · 아이의 기록이고 utteranceSeq가 CHILD 발화로 있으면 확인. @ConditionalOnMissingBean 기본 빈을 같은 타입 빈으로 교체한다
    violation: 없는 발화 · 남의 세션 · 기록 없음은 확인 불가 → U5a가 그 근거를 거절한다
    source: [FR6.2]

  # ── BR7 생활 팁 매칭 ─────────────────────────────────────────────────────
  - id: BR7.1
    statement: 생활 팁은 승인된 Support Library 항목 중 behavior_key가 세션 결과 태그와 겹치는 것만 고른다.
    category: calculation
    applies_to: ActivityResult.tips_json, matching_log_json
    trigger: SESSION_RESULT 처리
    logic: 세션 결과 태그 = 연습 목표의 behaviorKey ∪ 이번 결과에서 남은 후보의 behaviorKey. 항목 status가 승인(ACTIVE)이고 behavior_key ∈ 태그면 매칭. 매칭 로그 {sessionTags, matchedItemIds, rejected[{supportItemId, reason}]}를 저장한다
    violation: 미승인 항목 · 검색 점수 · 매칭 로그는 응답에 내보내지 않는다
    source: [FR7.1, AC5.2.3, Q11 원 답]
  - id: BR7.2
    statement: 팁은 조건 · 한계와 함께 내고, 금지 사전에 걸린 팁은 보이지 않는다.
    category: policy
    applies_to: tips 응답
    trigger: 팁 매칭 뒤
    logic: 매칭된 항목의 title(있으면) · condition_text · content_text · limitation_text가 금지 사전 1차에 걸리면 그 항목을 빼고 rejected에 PROHIBITED로 남긴다(팁은 2차 AI 분류를 하지 않는다)
    violation: 남은 팁이 0개면 noTipReason = NEED_MORE_OBSERVATION(9-27)
    source: [FR7.1, ai-io 6.6]

  # ── BR8 결과 API 노출 · 접근 ───────────────────────────────────────────────
  - id: BR8.1
    statement: 결과 응답은 계약 3절 C5 모양만 내고, 장면 결과는 칩 5종으로만 낸다.
    category: constraint
    applies_to: GET /api/sessions/{id}/result
    trigger: 조회
    logic: "jobStatus · resultLevel(현재 버전 있으면) · sentences[{goalSelectionId?, text}] · practice?{goals[{goalSelectionId, displayName, order, scenes[{sceneNo, exposureType, sceneChip, chipText, notDoneReason?}], help{count, consent}}]} · tips[{supportItemId, title?, condition, content, limitation}] · noTipReason? · candidates[{recommendationId, displayName, reasonText, evidence[{utteranceSeq, kind}], selected, goalSelectionId?}]. 현재 버전이 없으면 jobStatus만 채우고 나머지는 빈 값"
    violation: 응답 스키마(additionalProperties false) 시험이 막는다
    source: [FR8.1, FR8.3, NFR2, NFR10]
  - id: BR8.2
    statement: 진행 중 판정 · 재판정 원값 · 근거 문장 · 구조화 원본 · 08 원문 · 상황 본문 · 판정 기준은 어떤 보호자 응답 · PDF에도 넣지 않는다.
    category: constraint
    applies_to: 모든 C5 응답 · PDF
    trigger: 응답 조립
    logic: 응답 DTO에 그 칸이 없다. SceneJudgment.value · timing · rationale · live_label · differs_from_live, StructuredExperience.content_json, matching_log_json은 조립 함수 입력에서 아예 읽지 않는다
    violation: 스키마 시험 · PDF 텍스트 검사가 막는다
    source: [FR8.3, NFR2, AC5.2.8]
  - id: BR8.3
    statement: 정리 상태 응답은 {sessionStatus, confirmed, jobStatus}이고, 17의 정리 끝은 jobStatus DONE이다.
    category: calculation
    applies_to: GET /api/sessions/{id}/result/status
    trigger: 조회
    logic: "sessionStatus · confirmed는 활동 사본(core_activity)에서, jobStatus는 SessionResultState에서 읽는다. 머리 행이 아직 없으면 판정 함수(BR2.2)로 낸다 — 활동 기록이 있고 ELIGIBLE · UNDECIDED면 REQUESTED(곧 접수됨), NOT_ELIGIBLE이면 NONE. 활동 기록이 아직 없으면 사본 상태로 낸다 — 사본이 ENDED · ENDED_UNCONFIRMED · ABORTED, 또는 FAILED + SERVER_RESTART면 REQUESTED(Realtime이 이 경우 늘 결과 스냅숏을 보낸다, U4b-R10 · R11), 그 밖(DECLINED · 시작 실패 FAILED · 로컬 FAILED처럼 기록이 오지 않는 세션)은 NONE"
    violation: 없음. 기록이 끝내 오지 않는 세션은 NONE이라 17 · 27이 끝없이 폴링하지 않는다
    source: [FR8.2, C19, AC3.6.12]
  - id: BR8.4
    statement: 남의 세션 · 리포트는 404, 계정 삭제 접수 중이면 403 ACCOUNT_DELETION_IN_PROGRESS, 오류는 공통 봉투로 낸다.
    category: authorization
    applies_to: C5 9개 경로
    trigger: 모든 요청
    logic: 세션 · 리포트의 guardian_id가 로그인 보호자와 다르거나 없으면 404 NOT_FOUND. 보호자가 삭제 접수 상태면 403. 쓰기 경로(retry · reports · saved · discard)는 CSRF를 검사한다
    violation: 위 오류 코드를 공통 오류 봉투로 낸다
    source: [FR8.4, NFR5]

  # ── BR9 리포트 생성 · 60초 · 항목 조립 ─────────────────────────────────────
  - id: BR9.1
    statement: 미리보기는 기간 안의 보이는 활동과 기록 첫날을 낸다.
    category: calculation
    applies_to: GET /api/reports/preview
    trigger: 28 범위 바뀜
    logic: "활동 = 그 보호자 · 현재 아이의 활동 기록 중 final_status ∈ {ENDED, ENDED_UNCONFIRMED, ABORTED} 또는 BR2.2 정리 대상인 재시작 FAILED이고 listed_at(줄 시각 = startedAt, 없으면 사본 요청 시각, U4a-J8 — 26과 같은 기준)의 Asia/Seoul 날짜가 from ~ to 안인 것(listed_at 순). firstRecordDate = 같은 조건의 활동 전체 중 첫날(없으면 null). 정리 상태와 관계없이 넣는다"
    violation: from > to · 미래 날짜 · 형식 오류는 400 VALIDATION_FAILED
    source: [FR9.1, FR15.1, G9, AC5.3.5, AC5.3.12]
  - id: BR9.2
    statement: 리포트 생성 요청은 같은 키에 첫 결과를, 다른 본문에 409를 돌려준다.
    category: validation
    applies_to: POST /api/reports
    trigger: 「활동 N개로 리포트 만들기」
    logic: "검사: sessionIds ≥ 1 · 중복 없음 · from ≤ to ≤ 오늘 · 모든 세션이 그 보호자 것이고 BR9.1 조건 안. 같은 (guardian, idempotencyKey) 행이 있으면 request_hash가 같을 때 그 {reportId, status}를 202로, 다르면 409 IDEMPOTENCY_CONFLICT. 새 요청이면 Report(GENERATING) · ReportActivity · REPORT 작업(key 'RP:' + reportId, deadline_at = 지금 + report.ready-target)을 한 트랜잭션으로 만든다"
    violation: 남의 세션 · 범위 밖 세션이 하나라도 있으면 요청 전체를 400 VALIDATION_FAILED로 거절한다(리포트 0건)
    source: [FR9.2, NFR7, AC5.3.13, AC5.5.5, G11]
  - id: BR9.3
    statement: 새 리포트 생성 요청이 들어오면 그 보호자의 저장 안 된 이전 리포트(GENERATING · READY · FAILED)는 버린다.
    category: policy
    applies_to: Report
    trigger: 새 키의 POST /api/reports 커밋
    logic: 같은 보호자의 status ∈ {GENERATING, READY, FAILED}인 다른 리포트를 BR10.3 버림으로 처리한다. 진행 중이던 REPORT 작업은 쓰기 단계 확인(BR1.8)에서 진다
    violation: 없음
    source: [FR9.2, G15]
  - id: BR9.4
    statement: report.ready-target(60초)은 접수부터 재는 서버 절대 마감이다.
    category: constraint
    applies_to: REPORT 작업
    trigger: 시도 시작 · 실패 · 마감 확인
    logic: 시도마다 AI 호출 시한 = min(text-ai.result.request-timeout 10초, deadline_at − 지금). deadline_at이 지나면 남은 시도와 관계없이 FAILED(DEADLINE_EXCEEDED). 다음 시도 시각이 deadline_at 뒤면 다시 하지 않고 FAILED
    violation: 리포트 status = FAILED가 되고 28은 실패 화면을 보인다
    source: [FR9.3, NFR1, AC5.3.11, Q3 원 답]
  - id: BR9.5
    statement: 1 · 3 · 4 · 7 · 8 · 9항목은 서버가 집계하고, 2 · 5 · 6 · 10항목은 AI 한 번 호출로 만든다.
    category: calculation
    applies_to: ReportSection
    trigger: REPORT 시도
    logic: "1 = 기간 · 자유대화 n회 · 연습 n회 · 총 n분(확보 범위 합, 분 버림). 3 = 목표별 장면 수 · 장면 칩 문구 · 도움 횟수. 4 = 이름 · 나이(ChildContext.get), 관심사 없음. 7 = 목표 이름 · 핵심 기능(BehaviorDefinitionLookup). 8 = 도움 횟수 · 동의 분포 · 예 1개(deliveredText 중 containsAnswerSentence = false인 첫 것). 9 = 같은 목표의 장면 칩을 날짜순 나열, 범위 안 그 목표 DONE 기록 < 2면 자료 부족, 향상 계산 없음. 2 · 5 · 6 · 10 = ai-io 6.5 출력 하나(sections 2 · 5 · 6 · 10), 10항목 앞에 고정 문장 'AI와의 연습 장면에서 본 기록이며 실제 친구 관계에서도 같은지는 알 수 없어요.'를 붙인다"
    violation: 항목 이름 · 순서는 고정이다(ai-io 6.5). 이름 · 나이는 AI에 보내지 않는다
    source: [FR9.4, AC5.5.1, AC5.5.5]
  - id: BR9.6
    statement: AI 항목이 검사에 걸리면 쓸 수 있는 인용을 날짜순으로 나열하고, 인용이 없으면 자료 부족으로 둔다.
    category: policy
    applies_to: ReportSection(2 · 5 · 6 · 10)
    trigger: BR5.2에 걸림 · AI가 missing = true
    logic: text = 그 항목 인용 후보 중 인용 대조를 통과한 것을 "{quote}" (m/d hh:mm) 꼴로 날짜순 나열, templated = true. 인용이 하나도 없으면 text = '자료 부족' · missing = true. 10항목은 고정 문장을 늘 남긴다
    violation: 없음
    source: [FR9.4, AC5.5.2, AC5.5.3, ai-io 6.5]
  - id: BR9.7
    statement: 리포트 AI 입력의 인용 후보는 항목마다 최대 30개, 활동당 최대 5개다.
    category: constraint
    applies_to: REPORT AI 요청
    trigger: REPORT 시도 입력 조립
    logic: 활동을 최근 날짜부터 돈다. 활동마다 내용 있는 아이 발화(BR5.5 정의)를 먼저, 그다음 나머지 아이 확정 발화를 seq 순으로 최대 5개 고른다. 항목 합계가 30개면 멈춘다. 넘친 활동은 집계 항목(1 · 3 · 8 · 9)에만 들어간다. 활동은 activityNo(요청 안 번호)로만 가리킨다
    violation: 없음
    source: [FR9.4, FD-Q4, G10, NFR4]
  - id: BR9.8
    statement: 결과 값을 쓰는 항목에는 정리 끝(DONE) 활동만 넣고, 활동 기록만 쓰는 항목과 전사 인용에는 고른 활동을 모두 넣는다.
    category: policy
    applies_to: ReportSection, ReportActivity.result_status_at_build
    trigger: REPORT 시도 입력 조립
    logic: "조립 때 활동마다 SessionResultState.job_status를 result_status_at_build에 적는다. 모두 넣음 = 1 · 4 · 7 · 8항목과 2 · 6항목 전사 인용 후보. DONE만 = 3 · 9항목(칩), 5항목(구조화 출처 문장), AI 입력의 결과 문장. 리포트는 정리 중 활동을 기다리지 않는다. 결과 값 항목에 남는 활동이 없으면 그 항목은 자료 부족"
    violation: 없음
    source: [FR9.4, FD-Q5]
  - id: BR9.9
    statement: 인용 수 · 리포트 수준을 서버가 계산한다.
    category: calculation
    applies_to: Report.citation_count, Report.level
    trigger: REPORT 결과 저장
    logic: citation_count = (sessionId, utteranceSeq) 중복을 뺀 TRANSCRIPT · CHILD_RECALL 인용 수. level = 고른 활동의 현재 결과 수준이 모두 INSUFFICIENT면 INSUFFICIENT, 하나라도 FULL이 아니면 PARTIAL, 모두 FULL이면 FULL(DONE 아닌 활동은 FULL이 아닌 것으로 본다)
    violation: 없음
    source: [FR9.4, G14, ai-io 6.5]
  - id: BR9.10
    statement: 5항목 줄마다 출처를 구조화 결과의 source에서 그대로 단다.
    category: policy
    applies_to: ReportSection 5 · ReportCitation
    trigger: REPORT 결과 저장
    logic: 보호자 입력 출처 줄은 quote 없이 source = GUARDIAN_INPUT 인용 행만 남긴다(08 원문을 옮기지 않음). 아이 회상 줄은 source = CHILD_RECALL과 utteranceSeq · quote(대조 통과)를 남긴다
    violation: 출처가 없는 5항목 줄은 버린다
    source: [FR9.4, C7, AC5.5.5]

  # ── BR10 리포트 확인 · 저장 · 버림 · PDF ───────────────────────────────────
  - id: BR10.1
    statement: 리포트 상태는 functional-spec 3.2 전이표로만 바뀌고, sections는 READY · SAVED에서만 낸다.
    category: constraint
    applies_to: Report.status, GET /api/reports/{id}
    trigger: 조회 · 전이
    logic: GENERATING · FAILED · DISCARDED 응답은 status · 기간 · 활동 수만 내고 sections를 빈 배열로 낸다
    violation: 표에 없는 전이 요청은 409(BR10.2 · BR10.3)
    source: [FR10.1]
  - id: BR10.2
    statement: 저장은 READY · SAVED에서만 받고, 멱등이며 첫 savedAt을 유지한다.
    category: validation
    applies_to: POST /api/reports/{id}/saved
    trigger: 앱이 PDF 받기에 성공한 뒤
    logic: IF READY THEN SAVED · saved_at = 지금 · 204. IF SAVED THEN 204(바꾸지 않음). ELSE 409 REPORT_NOT_READY
    violation: 저장 사실만 남기고 전달 · 수신 완료로 표시하지 않는다(AC5.4.8)
    source: [FR10.2, G13, AC5.4.8]
  - id: BR10.3
    statement: 버림은 하위 행(활동 · 항목 · 인용)을 지우고 머리 행만 DISCARDED 묘비로 남긴다.
    category: policy
    applies_to: Report, POST /api/reports/{id}/discard
    trigger: 「나중에」 · 저장 전 「←」 · BR9.3 · BR10.4
    logic: IF status ∈ {GENERATING, READY, FAILED} THEN 하위 행 삭제 · status = DISCARDED · discarded_at · 진행 중 REPORT 작업은 그대로 두되 쓰기 단계에서 진다(BR1.8) · 204. IF DISCARDED THEN 204. IF SAVED THEN 바꾸지 않고 204(계약 C5는 204만 둔다)
    violation: 저장된 리포트는 버리지 않는다
    source: [FR10.2, G12, AC5.4.6]
  - id: BR10.4
    statement: 저장 안 된 READY · FAILED 리포트는 report.unsaved-retention(24시간) 뒤 버림과 같이 정리한다.
    category: policy
    applies_to: Report
    trigger: 누락 점검 주기(result.reconcile-interval)에 함께 실행
    logic: status ∈ {READY, FAILED} AND 마지막 전이 시각(ready_at 또는 작업 finished_at, 없으면 created_at) + 24시간 < 지금 THEN BR10.3을 적용한다
    violation: 없음
    source: [FR10.2, FD-Q2]
  - id: BR10.5
    statement: 저장된 리포트와 메타는 계정 삭제까지 보존한다.
    category: policy
    applies_to: Report(SAVED)
    trigger: 정리 점검
    logic: SAVED 리포트는 정리 점검 대상이 아니다. 계정 삭제 참가자(BR13.1)만 지운다
    violation: 없음
    source: [FR10.2]
  - id: BR10.6
    statement: PDF는 READY · SAVED 리포트의 저장된 항목으로 요청 때마다 메모리 안에서 만들고, 서버에 파일을 남기지 않는다.
    category: constraint
    applies_to: GET /api/reports/{id}/pdf
    trigger: 「PDF 저장」 · 「파일 열기」 · 「공유하기」
    logic: IF status ∈ {READY, SAVED} THEN 저장된 ReportSection · ReportCitation · 머리 값으로 HTML 템플릿을 채워 OpenHTMLtoPDF로 바이트 배열에 렌더링한다. 글꼴은 클래스패스의 Noto Sans KR 서브셋(Regular · Bold)이다. 임시 파일 · 디스크 캐시를 쓰지 않는다
    violation: 그 밖 상태는 409 REPORT_NOT_READY. 렌더링 실패는 500 INTERNAL_ERROR(본문 · 아이 이름을 로그에 넣지 않음)
    source: [FR10.3, NFR12, FD-Q6]
  - id: BR10.7
    statement: PDF 내용은 30과 같은 머리 · 고지 · 10개 항목 · 매 쪽 바닥글이고, 파일명 · 캐시 머리를 고정한다.
    category: constraint
    applies_to: PDF 응답
    trigger: PDF 렌더링
    logic: "머리 '무중 상담용 리포트' / '{아이} · n세 · yyyy.mm.dd ~ mm.dd · 만든 날 mm.dd' / 고지. 바닥글 '무중 상담용 리포트 · {아이} · 쪽 n/N · 이 리포트는 진단 결과가 아니에요.'. 결측 항목 '자료 부족'. Content-Type application/pdf, Content-Disposition attachment; filename*=UTF-8''무중_상담용리포트_{아이}_mmdd-mmdd.pdf, Cache-Control no-store. 텍스트 PDF"
    violation: 관심사 · 진행 중 판정값 · 판정 라벨 · 점수 · 성공률 · 향상 여부가 텍스트에 있으면 시험 실패
    source: [FR10.3, AC5.5.4, AC5.5.6, AC5.4.3]

  # ── BR11 화면 17 · 26 · 27 · 28 · 30 ─────────────────────────────────────
  - id: BR11.1
    statement: 17은 정리 상태만 보이고, 정리 끝(DONE)에서만 「오늘 기록 보기」를 보인다.
    category: policy
    applies_to: 화면 17
    trigger: 17 표시 · 폴링
    logic: "종료 상태 + jobStatus REQUESTED · RUNNING = '기록을 정리하고 있어요'. DONE = 9-20 '기록 정리가 끝났어요' + 「오늘 기록 보기」 → 그 세션 27. FAILED = 9-21 + 「기록에서 다시 시도」 → 그 세션 27. NONE = 정리 줄 없이 「홈으로」만. 결과 · 해석 · 판정 · 결과 문장은 보이지 않는다"
    violation: 없음
    source: [FR11.1, AC3.6.2, AC3.6.3, AC3.6.4, AC3.6.8, AC3.6.12]
  - id: BR11.2
    statement: 정리 상태 폴링은 17 · 27이 정리 중일 때만 parent.poll-interval(2초)로 하고, 끝나면 멈춘다.
    category: policy
    applies_to: 화면 17 · 27
    trigger: 화면 표시
    logic: IF jobStatus ∈ {REQUESTED, RUNNING} THEN 2초마다 /result/status. DONE · FAILED · NONE이 오면 멈추고 27은 /result를 한 번 읽는다. 화면을 떠나면 멈춘다
    violation: 없음
    source: [FR11.1, FR11.3, C18, AC5.2.4]
  - id: BR11.3
    statement: 26은 달력 · 그날 목록을 U4a 기록 API로 그리고, DECLINED는 상세로 가지 않는다.
    category: policy
    applies_to: 화면 26
    trigger: 26 표시
    logic: "처음 선택 날 = 오늘(Asia/Seoul), 27에서 돌아오면 보던 날. 줄 글 'hh:mm  자유대화 · n분' / 'hh:mm  연습 · 목표 n개 · n분' / '… · 중간에 끊김'(끊김 · 재시작 FAILED). DECLINED = 9-24 한 줄(누를 수 없음, 점은 찍힘). 활동 없는 날 '활동이 없어요'. 기록 0건 9-23. 날 칸 44px 이상, 점은 'm월 d일 · 활동 n개'로 읽힘"
    violation: 없음
    source: [FR11.2, AC5.1.1, AC5.1.2, AC5.1.3, AC5.1.4, AC5.1.5, AC5.1.7, AC5.1.8, AC5.1.9, AC5.1.10, G16]
  - id: BR11.4
    statement: 27은 끝난 이유 칸 · 연습 목표 묶음 · 세 탭을 결과 상태에 따라 나눠 그린다.
    category: policy
    applies_to: 화면 27
    trigger: 27 표시
    logic: "기록 상세(U4a)가 404면 /result/status로 정리 중을 보인다. 정리 중 = 9-29 앞줄, 실패 = 9-29 뒷줄 + 「다시 시도」(jobStatus FAILED일 때만), DONE + INSUFFICIENT = 9-32 + 대화 기록 탭은 그대로. 연습 DONE = 목표마다 이름 · '장면 n · {chipText}' · 결과 문장 · '제공한 도움 · 도움 n회(동의함/거절함/답 없음)' (cue 0이면 '도움 없음'). 탭 '대화 기록 · 생활 팁 n · 연습 목표 n'. 자유대화 결과 문장 · 08 배경은 그리지 않는다"
    violation: 진행 중 판정 · 점수 · 확률을 그리지 않는다
    source: [FR11.3, AC5.2.1, AC5.2.4, AC5.2.6, AC5.2.10, AC5.2.12, FR5.1]
  - id: BR11.5
    statement: 27 끝난 이유 칸은 사유 · 종료 확인 여부와 시각 · 확보 범위 · 미확인 구간을 보인다.
    category: policy
    applies_to: 화면 27 CARD-END
    trigger: 27 표시
    logic: "첫 줄 '끝난 이유 · {AC3.6.9 사유 문구} · hh:mm'. 기술 문제(gaps 있음 · 미확인 종료 · ABORTED · 재시작 FAILED)면 둘째 줄 'hh:mm 종료 확인됨 · hh:mm ~ hh:mm 기록 확보 · hh:mm ~ hh:mm 미확인', 확인 안 됨이면 9-33. 재시작 FAILED는 사유 '기기 응답 없이 끝냈어요' + 9-33(FD-Q7)"
    violation: 기록 수신 공백을 침묵 · 거부 · 실패로 쓰지 않는다
    source: [FR11.3, FR15.1, AC5.2.11, AC5.2.6, FD-Q7]
  - id: BR11.6
    statement: 27 생활 팁 탭과 연습 목표 탭은 결과 응답의 tips · candidates로 그린다.
    category: policy
    applies_to: 화면 27 TAB-TIPS · TAB-GOALS
    trigger: 탭 표시
    logic: "팁 = 제목(title, 없으면 content 첫 문장) + 본문 + 9-26 두 줄('이럴 때 해 보세요 · {condition}' / '주의할 점 · {limitation}'), 0개면 9-27. 후보 = 이름 + 「담기」/「담김 ✓」 + '근거 · {reasonText} (hh:mm)'(hh:mm = 첫 근거 발화 시각), 0개면 9-28. 「담기」는 U5a POST /api/goal-selections, 「담김 ✓」 누름은 취소"
    violation: 담기 실패는 U5a 오류 사유 문구(§9-12)를 토스트로 보인다
    source: [FR11.3, AC4.3.1, AC4.3.2, AC4.3.3, AC4.3.5, AC5.2.3]
  - id: BR11.7
    statement: 28은 빠른 선택 4종과 달력 두 번 누르기로 기간을 고르고, 활동 0건이면 만들기를 끈다.
    category: policy
    applies_to: 화면 28
    trigger: 28 표시 · 범위 바뀜
    logic: "기본 = 최근 2주(오늘 포함 14일). 이번 달 = 1일 ~ 오늘. 전체 · 직접 = firstRecordDate ~ 오늘(없으면 오늘). 달력 첫 누름 = 그날만 + '시작 m/d · 끝나는 날을 누르세요', 둘째 = 두 날 사이(앞뒤 바뀌어도 됨), 셋째 = 새로 시작. 미래 날은 누를 수 없고 보조기기에 비활성으로 읽힌다. 활동 줄 체크 토글, 0건이면 「리포트 만들기」 꺼짐 + '이 기간엔 활동이 없어요' / '다른 기간을 골라 주세요.'"
    violation: 없음
    source: [FR12.1, AC5.3.1, AC5.3.2, AC5.3.3, AC5.3.4, AC5.3.5, AC5.3.6, AC5.3.12, AC5.3.14, C23]
  - id: BR11.8
    statement: 28 만드는 중은 2초 폴링하고, 60초가 지나거나 FAILED면 실패 화면, 취소는 앱만 기다리기를 멈춘다.
    category: policy
    applies_to: 화면 28 만드는 중
    trigger: 「활동 N개로 리포트 만들기」
    logic: "28에 들어올 때 만든 idempotencyKey 하나를 「만들기」 · 재탭에 같이 쓴다(작업 1건). 202 뒤 2초마다 GET /reports/{id}. READY → 30(reportId). FAILED 또는 요청부터 60초 → '리포트를 만들지 못했어요' / '잠시 뒤 다시 시도해 주세요. 고른 범위는 그대로예요.' + 「범위 다시」 · 「다시 시도」(새 키). 「취소」 · 「←」 = 폴링만 멈추고 범위 유지"
    violation: 없음
    source: [FR12.1, FR9.2, AC5.3.7, AC5.3.8, AC5.3.9, AC5.3.10, AC5.3.11, AC5.3.13]
  - id: BR11.9
    statement: 30은 고지 + 10개 항목을 보이고, 저장은 PDF 받기 성공 뒤에만 기록한다.
    category: policy
    applies_to: 화면 30
    trigger: 30 표시 · 「PDF 저장」
    logic: "머리 '← 상담용 리포트' · 'm/d ~ m/d · 활동 n개', 고지, 항목 카드 10개(인용 '\"{quote}\" (m/d hh:mm)', 5항목 줄 꼬리표 '보호자 입력' / '아이 회상', 결측 '자료 부족'). 「PDF 저장」 → 시트(기간 · 활동 · '항목 · 10개 (인용 n개 포함)' · '아이 정보 · 이름 · 나이만') → 「저장」 = GET /pdf 받기 → 성공이면 브라우저 저장 + POST /saved → 저장했어요. 실패면 9-30 토스트, 30 유지. 「취소」 = 30 유지"
    violation: 시트의 기간 · 활동 수 · 인용 수 · 아이 정보는 리포트 응답 값과 같아야 한다
    source: [FR12.2, AC5.4.1, AC5.4.2, AC5.4.3, AC5.4.7, AC5.4.8, AC5.5.1]
  - id: BR11.10
    statement: 30의 이동과 버림은 저장 전후로 나뉜다.
    category: policy
    applies_to: 화면 30
    trigger: 「←」 · 「나중에」 · 「기록으로」 · 「공유하기」 · 「파일 열기」
    logic: "저장 전 「←」 = POST /discard 뒤 28(범위 유지). 「나중에」 = POST /discard 뒤 26. 저장 뒤 「←」 · 「기록으로」 = 26. 「공유하기」 = Web Share(files) 지원 때만 보임, 같은 PDF 파일을 넘김. 「파일 열기」 = 같은 PDF를 새 탭으로. 둘 다 30 유지"
    violation: 버림 요청 실패는 화면 이동을 막지 않는다(서버 정리 점검 BR10.4가 남은 것을 지운다)
    source: [FR12.2, AC5.4.4, AC5.4.5, AC5.4.6]
  - id: BR11.11
    statement: 칩 · 상태 · 기간은 색만으로 구분하지 않고, 상태 변화는 화면 낭독기에 알린다.
    category: constraint
    applies_to: 화면 17 · 26 · 27 · 28 · 30
    trigger: 표시 · 상태 바뀜
    logic: 칩은 글자로, 기간 칠하기는 요약 줄 글로, 선택한 날은 머리 글로도 드러낸다. 정리 상태 · 만드는 중 · 실패 · 저장했어요는 aria-live polite 영역에 글로 낸다
    violation: 없음
    source: [FR12.3, AC5.3.14, AC5.1.10]

  # ── BR12 U5a 확장 (J17, 더하기만) ────────────────────────────────────────
  - id: BR12.1
    statement: ScenarioLookup.get은 상황 본문 · 상대 말 · 트리거 대본을 돌려주고 상대 역할 지시는 넘기지 않는다.
    category: constraint
    applies_to: PracticeGoal 공개 서비스 ScenarioLookup
    trigger: 재판정 입력 조립
    logic: "scenarioId마다 {scenarioId, goalSelectionId, behaviorKey, behaviorVersion, situation = scenario_text, partnerUtterance = trigger_script, triggerScript = trigger_script}. partner_role_text는 반환형에 없다. 다른 보호자의 scenarioId는 빼고 돌려준다"
    violation: 없는 id는 결과에서 빠진다(그 장면은 상황 없이 재판정 입력에 들어간다)
    source: [FR13.1, C24]
  - id: BR12.2
    statement: BehaviorDefinitionLookup.get은 고정된 옛 버전(RETIRED 포함)도 읽는다.
    category: constraint
    applies_to: PragmaticsKnowledge 공개 서비스 BehaviorDefinitionLookup
    trigger: 재판정 · 리포트 7항목
    logic: requireVersion(behaviorKey, behaviorVersion)로 {displayName, coreFunction, judgmentCriteria}를 돌려준다. judgmentCriteria는 재판정 AI 입력에만 쓰고 응답 · PDF에는 넣지 않는다
    violation: 없는 버전은 그 목표를 정의 없이 재판정 입력에 넣고 7항목에서 그 목표를 뺀다
    source: [FR13.2, FR8.3]
  - id: BR12.3
    statement: CandidateRegistry에 27용 읽기 메서드를 더하고, U6은 U5a 표를 직접 읽지 않는다.
    category: constraint
    applies_to: CandidateRegistry
    trigger: GET /result 조립
    logic: viewForResult(sessionId, guardianId) → [{recommendationId, displayName, reasonText, evidence[{utteranceSeq, kind}], selected, goalSelectionId?}]. 기존 viewForSession은 바꾸지 않는다
    violation: U5a 시험 단언 · 커버리지 하한은 그대로 통과해야 한다
    source: [FR13.3, FR8.1]

  # ── BR13 계정 삭제 참가자 ────────────────────────────────────────────────
  - id: BR13.1
    statement: ResultDeletionParticipant(ORDER_RESULT = 70)는 활동 기록(90)보다 먼저 그 보호자의 U6 행을 모두 지운다.
    category: policy
    applies_to: U6 새 표 10개
    trigger: 계정 삭제 지휘(DeletionCommander)
    logic: 하위부터 지운다 — ReportCitation · ReportSection · ReportActivity(그 보호자 리포트) → Report → SceneJudgment · StructuredExperience(그 보호자 세션) → ActivityResult → SessionResultState → ResultJobAttempt → ResultJob. 멱등이다(두 번 불러도 0건이면 성공). PDF 파일 단계는 없다
    violation: 실패하면 지휘가 다시 부른다(기존 계정 삭제 재시도 규칙)
    source: [FR14.1, NFR11]
  - id: BR13.2
    statement: 결과 · 리포트 쓰기 트랜잭션은 보호자가 삭제 중이면 쓰지 않고 작업을 멈춘다.
    category: constraint
    applies_to: SESSION_RESULT · REPORT 쓰기 단계, 접수(BR2.1)
    trigger: 쓰기 트랜잭션 시작
    logic: 보호자 행을 공유 잠금으로 읽어 삭제 접수 상태면 결과를 쓰지 않고 작업을 FAILED(ACCOUNT_DELETING)로 둔다. 삭제 참가자는 같은 보호자 행을 잠근 뒤 지우므로 늦게 끝난 작업이 남긴 행도 다음 참가자 실행에서 지운다
    violation: 없음
    source: [FR14.1, NFR11]

  # ── BR14 재시작 FAILED 세션 노출 ─────────────────────────────────────────
  - id: BR14.1
    statement: 진행 중 서버 재시작으로 끝난 FAILED 세션은 발화가 있으면 26 목록 · 27 상세 · 28 미리보기에 보인다.
    category: policy
    applies_to: U4a RecordQueryService · ActivityRecordRepository, U6 미리보기
    trigger: 26 · 27 · 28 조회
    logic: 보이는 조건에 BR2.2 판정 함수의 ELIGIBLE인 FAILED(사본 FAILED + SERVER_RESTART + 전사 발화 ≥ 1)를 더한다. 접수 · 정리 상태 응답과 같은 함수를 쓰므로 26에 보이는 재시작 FAILED는 27에서 늘 REQUESTED 이후 상태(NONE 아님)다. UNDECIDED는 아직 보이지 않는다. 27 상세 404 조건(U4a-J9)에서 이 경우만 뺀다. 그 밖 FAILED는 계속 보이지 않는다
    violation: 없음. 발화 없는 재시작 FAILED는 jobStatus NONE이고 26에 보이지 않는다
    source: [FR15.1, FR2.2, AC3.6.9]
  - id: BR14.2
    statement: 26 · 27 · 28은 재시작 FAILED를 새 문구 없이 기존 문구로 보인다.
    category: policy
    applies_to: 화면 26 · 27
    trigger: 재시작 FAILED 활동 표시
    logic: 26 줄 글은 연결 끊김과 같은 '· 중간에 끊김'. 27 끝난 이유 첫 줄은 17과 같은 '기기 응답 없이 끝냈어요', 둘째 줄 종료 확인은 9-33 '종료 확인 안 됨 · 마지막 확인 hh:mm'
    violation: 새 문구를 만들지 않는다
    source: [FR15.1, FD-Q7]
```

## 규칙 요약

| 묶음 | 규칙 | 요점 | 근거 |
|---|---|---|---|
| BR1 실행기 | BR1.1 ~ BR1.12 | DB 선점(1초 · 스레드 2, 1개는 REPORT 전용) · 리포트 상태 동반 전이 · 동시 1개 · 같은 키 같은 작업 · 3회(5 · 15초) · 재시작 RUNNING → FAILED · AI는 트랜잭션 밖 | FR1.1 · FD-Q1 · FD-Q2 |
| BR2 접수 | BR2.1 ~ BR2.5 | 이벤트 · 누락 점검(5분)으로 한 번 접수, 정리 대상 판정 함수 하나(UNDECIDED는 미룸), 후보 등록 재시도, 다시 시도는 FAILED만, 결과는 버전 | FR1.2 · FR2.x · FR15.1 |
| BR3 구조화 | BR3.1 ~ BR3.3 | 자유대화 · 유효 기록만, 이름 · id 안 보냄, 출처 두 종류 · 의도 감정 칸 없음 | FR3.1 |
| BR4 재판정 · 칩 | BR4.1 ~ BR4.6 | practice null → INSUFFICIENT, 장면 2개, 차이 표시, 사상표(FD-Q3), 칩 5종, 도움 count · consent | FR4.x · FD-Q3 |
| BR5 문장 · 수준 | BR5.1 ~ BR5.6 | 검사 순서 4단계, 템플릿 2종, 숫자는 시각 · 장면 번호, 수준 2단계 계산, INSUFFICIENT는 AI 없이 DONE | FR5.x · FR16.3 |
| BR6 후보 | BR6.1 ~ BR6.5 | 최대 3개, 근거는 아이 회상만, 서버 의미 검토, 결과 커밋 뒤 등록, 근거 확인 어댑터 | FR6.x |
| BR7 팁 | BR7.1 ~ BR7.2 | behavior_key 교집합, 금지 사전에 걸린 팁 숨김, 없으면 추가 관찰 필요 | FR7.1 |
| BR8 결과 API | BR8.1 ~ BR8.4 | 칩만 노출, 금지 필드 없음, 17 정리 끝 = DONE, 404 · 403 | FR8.x · NFR2 · NFR5 |
| BR9 리포트 생성 | BR9.1 ~ BR9.10 | 미리보기, 같은 키 · 409, 이전 저장 안 된 것 버림, 60초 절대 마감, 항목 조립, 인용 후보 30 · 활동당 5, DONE 활동만 결과 값 항목 | FR9.x · FD-Q4 · FD-Q5 |
| BR10 저장 · PDF | BR10.1 ~ BR10.7 | 멱등 저장, 버림 = 묘비, 24시간 정리, 메모리 PDF · 한글 서브셋, 머리 · 바닥글 · 파일명 | FR10.x · FD-Q6 |
| BR11 화면 | BR11.1 ~ BR11.11 | 17 · 26 · 27 · 28 · 30 표시 · 폴링 · 이동 · 접근성 | FR11.x · FR12.x |
| BR12 U5a 확장 | BR12.1 ~ BR12.3 | ScenarioLookup · BehaviorDefinitionLookup · 후보 읽기 메서드, 더하기만 | FR13.x |
| BR13 삭제 | BR13.1 ~ BR13.2 | 순서 70 · 멱등 · 삭제 중 쓰기 멈춤 | FR14.1 · NFR11 |
| BR14 재시작 FAILED | BR14.1 ~ BR14.2 | 발화 있으면 26 · 27 · 28에 노출, 기존 문구 재사용 | FR15.1 · FD-Q7 |

## Sources

- 요구사항: `inception/requirements-analysis/requirements.md` FR1 ~ FR15, NFR1 ~ NFR12(FR16은 functional-spec 10절에서 다룸)
- 이 단계 답: `functional-design-questions.md` FD-Q1 ~ FD-Q7(모두 A), 요약 확인 Looks correct
- 엔터티: `construction/functional-design/entities.md`
- 계약: `contracts/ai-io.md` 6.1 ~ 6.6, `contracts/timing.md` 2절, 원래 기록 `contract-design/contract-summary.md` 2절 · 3절 C5 · 8절
- 원래 기록 수용 기준: `261008-mujoong-service/inception/user-stories/stories.md` US3.6 · US4.3 · US5.1 ~ US5.5, 화면 `refined-mockups/mockups.md` S-17 · S-26 · S-27 · S-28 · S-30 · R-PDF · §9
- 기존 코드: `backend/core-api/.../record/ActivityRecordRepository.java`(LISTED_STATUSES), `RecordQueryService.java`(FAILED 404), `V3__u4a_activity_core.sql`(core_activity_context · core_activity_record)
