# U5b 업무 규칙

## Sources

정본은 `../../inception/requirements-analysis/requirements.md`와 `functional-design-questions.md`의 확인된 Q1~Q5다. 기존 인셉션의 FR·NFR·AC ID를 유지한다. 데이터 형태는 entities.md, 순서와 전이는 functional-spec.md가 정본이다. 아래 YAML은 업무 규칙의 정본이다. `proposed_integration`은 시험 계약의 제안이며 상대 담당자 합의를 뜻하지 않는다.

## Business Rules

```yaml
rules:
  - id: BR1.1
    statement: 유효한 보호자가 소유한 선택 목표만 준비하며 시작 조건은 다시 확인한다
    category: authorization
    applies_to: StartPreparation
    trigger: 준비 요청과 실제 시작 인계
    logic: IF 계정이 활성이고 아이와 필수 동의가 유효하며 선택이 비어 있지 않고 중복/타 계정/삭제 목표가 없으면 THEN 준비를 허용한다; 실제 시작 직전 동의와 기기 안정 및 진행 세션을 다시 확인한다
    violation: 기존 인증/동의/소유권/검증/기기/충돌 오류를 반환하고 상대 서비스를 시작하지 않는다
    source: FR4.1, NFR9, AC4.1.3, AC4.1.10
  - id: BR1.2
    statement: 고정 가중치로 비복원 추첨한 순서를 한 번만 저장한다
    category: calculation
    applies_to: FrozenGoal
    trigger: 준비 선점 승자의 목표 고정
    logic: IF 선택 수가 N이고 담은 순 순위가 r이면 THEN 가중치는 즐겨찾기 배수 2 또는 1에 N-r+1을 곱한다; 매 단계 남은 목표의 양수 가중치 비율로 하나를 뽑아 제거하고 전체 순서를 저장한다
    violation: 빈 목록/중복/잘못된 순위는 검증 실패이며 저장된 순서를 재추첨하지 않는다
    source: FR4.1, AC4.1.4, AC4.2.9, Q4
  - id: BR1.3
    statement: 같은 보호자와 멱등 키는 같은 준비 원본과 세션을 재사용한다
    category: constraint
    applies_to: StartPreparation
    trigger: 동시 요청 또는 재시도
    logic: IF 같은 키와 동일 요청 해시이면 THEN FrozenGoal의 추첨 입력/결과와 기존 sessionId/첫 상황을 사용한다; 선점 세대의 승자만 결과를 저장하고 하나의 인계만 허용한다; C8 기존 필드로 복사하고 Realtime은 재추첨하지 않는다
    violation: 다른 요청 해시는 IDEMPOTENCY_CONFLICT; 처리 중인 요청은 새 세션을 만들지 않는다; 처리 중 응답 형식은 I5 합의 전 보류
    source: FR4.1, FR4.2, NFR4, AC4.1.10, Q5
  - id: BR1.4
    statement: 첫 상황은 활성 세션용 C10과 분리된 준비 경계에서 생성한다
    category: policy
    applies_to: StartPreparation
    trigger: 첫 상황 준비
    logic: IF 고정 준비 문맥의 소유권/정의 버전/동의가 유효하면 THEN 기존 생성/내용 검증 로직을 공유하되 C10의 ACTIVE 검사를 우회하지 않는다; 전체 15초 안에서 처음 생성과 내용 재생성 1회를 수행하고 두 번 생성 실패는 422 SCENARIO_GEN_FAILED로 기록한다
    violation: 준비 실패에서 REQUESTED/활성 세션/실제 노출을 만들지 않는다; 인증·동의·일시 미연결 오류를 내용 실패로 위장하지 않는다
    source: FR4.2, FR7.5, AC4.2.1, Q5
  - id: BR1.5
    statement: 첫 상황 준비 후에만 REQUESTED 시작 경로에 인계한다
    category: constraint
    applies_to: StartPreparation
    trigger: READY 준비 원본의 인계
    logic: IF 첫 상황과 목표 순서가 영속 고정되고 재확인 조건이 통과하면 THEN 동일 sessionId로 기존 시작 경로에 인계한다; REQUESTED 10초 시계는 실제 REQUESTED 진입부터 적용한다
    violation: 새 준비 API/문맥 포트/Core V7 이후 저장 변경은 I5의 U4a 합의 전 확정 통합 계약으로 쓰지 않는다
    source: FR4.1, AC4.1.3, AC4.2.1, Q5
  - id: BR1.6
    statement: 진행 목표의 원본은 시작 스냅숏이며 현재 선택 목록이 아니다
    category: constraint
    applies_to: SessionGoal
    trigger: 시작 후 목록 또는 정의 변경
    logic: IF 세션이 시작됐으면 THEN 목표 이름/버전/판정 기준/순서는 불변이고 삭제/별 변경으로 바꾸지 않는다
    violation: 고정 원본을 확인할 수 없으면 닫힌 오류로 처리하며 최신 목록이나 상대 DB 직접 조회로 대체하지 않는다
    source: FR4.1, FR4.2, NFR3, AC4.1.12, AC4.2.9
  - id: BR2.1
    statement: 참여 수락 후에만 상황 안내와 첫 반응 기회를 시작한다
    category: policy
    applies_to: PracticeProtocol
    trigger: U4b ACTIVE 진입
    logic: IF 참여 수락 후 ACTIVE이면 THEN 첫 상황 안내와 역할 및 승인 트리거를 제공한다; FIRST_RESPONSE 기회는 실제 안내/트리거 재생 완료 증거 뒤에만 연다
    violation: 안내 누락/실패/불명확한 완료를 성공으로 보지 않는다; 안전한 최초 출력과 재생 증거는 I1 합의 전 대역에서만 검증한다
    source: FR4.2, FR5.5, AC4.2.3, AC6.5.1, AC6.5.2, Q1
  - id: BR2.2
    statement: 반응 기회는 한 번 닫히며 무반응에는 판정 호출이 없다
    category: constraint
    applies_to: PracticeResponse
    trigger: 아이 확정 전사 또는 대기 만료
    logic: IF 실재 장면의 열린 기회에서 유효한 아이 반응을 받으면 THEN 해당 종류로 한 번 저장한다; 무반응 만료면 utteranceSeq null과 NOT_PERFORMED/NO_RESPONSE를 한 번 저장하고 평가자를 호출하지 않는다
    violation: 불확실 전사와 기술 재생 실패를 아이 무반응으로 확정하지 않는다; 같은 세대의 중복 사건을 재집계하지 않는다
    source: FR4.2, FR4.3, FR7.6, AC4.2.3, AC4.2.5, AC4.2.7
  - id: BR2.3
    statement: 도움은 제안 조건과 아이 동의 뒤에만 제공한다
    category: policy
    applies_to: CueDelivery
    trigger: 첫 반응 판정
    logic: IF 첫 반응이 partial 또는 none이면 THEN HELP_OFFER로 동의를 구한다; ACCEPTED일 때만 Cue를 요청한다; DECLINED/NO_RESPONSE이면 Cue 없이 새 상황을 준비한다; uncertain이면 보류하고 Cue 없이 새 상황으로 간다
    violation: 동의 질문 10초 만료를 NO_RESPONSE로 기록한다; NOT_PERFORMED의 도움 제안은 상위 B14 가정으로 남기고 정식 AI 계약 전 확정하지 않는다
    source: FR4.2, FR5.5, AC4.2.4, AC6.5.3
  - id: BR2.4
    statement: 도움 후와 새 상황 반응은 각각 실제 재생 완료부터 20초를 설계한다
    category: policy
    applies_to: PracticeProtocol
    trigger: Cue 또는 새 상황 트리거의 실제 재생 완료
    logic: IF 실제 재생 완료가 확인되면 THEN 해당 서버 수신 시각부터 별도 20초 기회를 제안한다; 도움 후 무반응 뒤 새 상황 준비, 새 상황 무반응 뒤 목표 정산과 다음 목표/마무리로 연결한다
    violation: 첫 반응 시한을 임의 확대 적용한 것으로 쓰지 않는다; 재생 증거/일시정지/종료 경합은 I1/I3 합의 전 운영 통합으로 확정하지 않는다
    source: FR4.2, NFR6, AC4.2.5, AC6.5.4, AC6.5.5, Q2
    admission: proposed_integration
  - id: BR2.5
    statement: 새 상황 생성 실패는 기존 기록 보존과 내부 실패 사실만 확정한다
    category: constraint
    applies_to: PracticeAttempt
    trigger: NEW_SCENARIO 생성 실패 또는 15초 만료
    logic: IF 새 상황을 만들지 못하면 THEN ORIGINAL 반응/판정/동의/도움/노출을 보존하고 준비 실패/시간 초과를 내부에 구분한다; 가짜 scenarioId와 장면 연결 행을 만들지 않는다
    violation: 목표 최종 상태와 스냅숏 표현 및 후속 전이는 I4 합의 대기이며 DONE/skipReason 또는 SKIPPED를 임의 채택하지 않는다
    source: FR4.2, FR4.3, FR7.5, AC4.2.1, AC4.2.5, Q3
  - id: BR2.6
    statement: 다음 목표 상황 실패와 마지막 목표 완료를 구분한다
    category: policy
    applies_to: SessionGoal
    trigger: 정상 목표 완료 또는 다음 목표 ORIGINAL 준비
    logic: IF 다음 목표의 처음 상황이 두 번 실패하거나 15초를 넘기면 THEN 그 목표를 미실시로 정산하고 다음 목표를 준비한다; 정상 마지막 목표 완료면 LAST_GOAL로 U4b에 마무리를 요청한다
    violation: Q3의 같은 목표 새 상황 실패에 이 규칙을 자동 적용하지 않는다; 종료 상태를 U5b가 직접 변경하지 않는다
    source: FR4.2, AC4.2.1, AC4.2.6, AC6.5.6
  - id: BR3.1
    statement: 판정은 한 반응과 고정 기준에 귀속하며 최대 두 번 호출한다
    category: validation
    applies_to: Evaluation
    trigger: 실재 반응의 진행 판정
    logic: IF 실재 반응과 기준이 있으면 THEN PENDING 반응을 먼저 영속 저장하고 수락부터 최종 원자적 커밋까지 전체 12초 안에 처리한다; 최종 저장 예산 R을 선예약하며 호출은 최대 5초와 재시도 최대 1회이고 남은 예산에 따라 제한/생략한다; 유효 세대/실행 소유권과 종료 전 조건의 승자만 최종 Evaluation과 동일 반응 label 및 FINALIZED를 함께 저장한다; 최종 실패/시간 초과는 기존 반응 보존과 uncertain/reviewNeeded이며 시한·횟수는 재초기화하지 않는다
    violation: 무반응은 판정하지 않는다; 대기/종료를 uncertain으로 대체하지 않는다; 중복 및 이전 시도/늦은 세대의 결과를 반영하지 않는다; 최초/최종 저장 실패는 성공으로 꾸미지 않고 I2로 처리한다
    source: FR7.6, NFR6, AC4.2.3, AC4.2.7, Q6
  - id: BR3.2
    statement: 진행 판정과 기준은 내부에만 보존한다
    category: authorization
    applies_to: Evaluation
    trigger: 보호자 조회 또는 로그 기록
    logic: IF 조회/로그 목적이면 THEN 판단 라벨/근거/확률/점수/기준과 공급자 본문을 제외한다; 보호자 practice에는 목표 번호/수/이름/단계만 제공한다
    violation: DTO 허용 목록 밖의 필드는 거절하고 원문을 로그에 내지 않는다
    source: FR4.3, NFR1, NFR3, NFR9, AC4.2.8
  - id: BR4.1
    statement: 첫 반응과 새 상황에는 Cue/정답/시범을 출력하지 않는다
    category: policy
    applies_to: StageOutputPolicy
    trigger: 모든 공급자 출력 구간의 승인과 송신 직전
    logic: IF 출력 세대/단계/동의/역할/금지 표현/완결 대응이 모두 유효하면 THEN 승인한다; FIRST_RESPONSE와 NEW_SCENARIO_RESPONSE에서 Cue·정답·시범은 차단한다
    violation: 검증 실패 구간을 폐기하고 기존 취소/대기 안내를 적용한다; 미승인 출력을 말풍선에도 보내지 않는다
    source: FR5.5, FR7.8, NFR1, NFR3, AC6.5.2, AC6.5.5
  - id: BR4.2
    statement: Cue 브리프와 대화 역할에 판정 지식을 전달하지 않는다
    category: constraint
    applies_to: VoiceRelay
    trigger: 상황 또는 Cue 요청
    logic: IF Cue 요청이면 THEN situationSummary/partnerUtterance/focus만 보낸다; 정답 문장/판정 기준/보호자 원문/이전 판정은 보내지 않는다; 실제 재생 시작까지 5초를 넘기면 미제공으로 기록하고 새 상황을 준비한다
    violation: 늦은 Cue는 취소하고 재생하지 않는다; 요청 수락을 실제 제공으로 기록하지 않는다
    source: FR5.5, NFR3, NFR6, AC6.5.3, AC4.2.10
  - id: BR4.3
    statement: 단서를 추가하지 않는 반복과 속도 조절은 Cue가 아니다
    category: policy
    applies_to: PracticeProtocol
    trigger: 반복/속도/설명 요청
    logic: IF 제공 내용에 새 목표 단서가 없으면 THEN 같은 내용 반복이나 속도 조절이며 Cue를 늘리지 않는다; 단서를 추가하면 동의와 1회 제한 및 해당 장면 기록을 적용한다
    violation: 요청 문구만 보고 분류하지 않는다; 미확정 의미 분류는 허용 출력을 제한하고 정식 AI 내용 승인으로 주장하지 않는다
    source: FR5.6, AC6.5.7
  - id: BR4.4
    statement: 새 상황은 같은 목표와 관계 및 기회 조건을 유지하고 소재만 다르다
    category: validation
    applies_to: Scenario
    trigger: NEW_SCENARIO 요청 및 응답
    logic: IF 같은 목표의 ORIGINAL을 previousScenarioId로 보내면 THEN Core는 소유권/정의 버전/관계/기회 및 기존 내용 재사용을 검사한다; 검증된 새 상황만 저장한다
    violation: 다른 아이/목표/세션 참조를 거절하고 내용 실패는 C10 계약대로 처리한다
    source: FR7.5, FR5.5, AC6.5.5, AC6.5.8
  - id: BR5.1
    statement: 반응과 도움은 실재 상황에 연결되고 별도로 저장된다
    category: constraint
    applies_to: PracticeRecord
    trigger: 저장 또는 스냅숏 투영
    logic: IF FIRST/AFTER_CUE/NEW_SCENARIO 반응과 도움이 존재하면 THEN 각 scenarioId가 같은 목표의 실제 장면에 속하는지 확인하고 독립적으로 투영한다; 첫 반응을 덮어쓰지 않는다
    violation: 참조가 없는 반응/도움을 내보내지 않는다; 미실시를 성공이나 독립 추가 기회로 바꾸지 않는다
    source: FR4.3, AC4.2.5, AC4.2.7
  - id: BR5.2
    statement: 실제 노출만 기록 뒤 한 번 반영한다
    category: policy
    applies_to: ExposureReconciliation
    trigger: ActivityRecorded 수신
    logic: IF 실제 재생 증거가 있는 scenarioId/exposedAt가 권위 있는 기록에 있으면 THEN Core는 소유권/세션/미래 시각을 검사하고 ISSUED를 EXPOSED로 멱등 전환한다
    violation: 발급만 된 상황/재생 생략/실패/불명확한 완료는 노출로 집계하지 않는다; 재생 증거는 I1 합의 대기
    source: FR7.5, AC6.5.8, NFR9
  - id: BR5.3
    statement: 중간 종료에서는 남은 목표를 정산한 뒤 결과 입력을 제공한다
    category: policy
    applies_to: PracticeProtocol
    trigger: U4b 종료 정산 호출
    logic: IF CHILD_STOP/SILENCE/DISCONNECT 또는 다른 종료가 확정되면 THEN 도달한 원본을 보존하고 남은 목표는 NOT_REACHED로 정산하며 더 이상 출력/새 기회를 만들지 않는다; 정산 뒤 practiceRecord를 제공한다
    violation: 실제 세션 종료는 U4b가 소유한다; 종료 승자 뒤 판정 대기는 TERMINATED_PENDING으로 보존하고 결과 저장을 차단한다; 이 반응의 외부 표현과 정산 창은 I2 합의 전 보류한다; Q3 미합의 표현은 확정 데이터로 내보내지 않는다
    source: FR5.7, FR7.1, FR4.3, NFR4
  - id: BR5.4
    statement: 자기 연습 데이터는 세션 행보다 먼저 멱등 삭제한다
    category: authorization
    applies_to: PracticeDeletion
    trigger: 보호자 삭제 요청
    logic: IF U4b 삭제 지휘가 연습 순서 150을 호출하면 THEN 자기 판정/반응/도움/시도/목표를 참조 역순으로 삭제한다; 재호출과 빈 결과도 성공이다
    violation: 실패는 caller에게 전달해 트랜잭션 재처리하며 삭제 완료를 위장하지 않는다; Core 준비 원본은 Core 연습 삭제에 포함하도록 U4a 합의한다
    source: FR1.6, NFR12
  - id: BR6.1
    statement: 화면은 선택과 요청 상태를 구분하며 요청 실패로 기기 상태를 바꾸지 않는다
    category: policy
    applies_to: ParentPracticeUI
    trigger: 선택/시작/조회
    logic: IF 0개 선택 또는 전송 중이면 THEN 시작을 막는다; 수락 후 화면 12로 이동하고 거절은 화면 18에 사유와 선택을 보존한다; 조회 실패는 마지막 확인값과 연결 상태를 구분한다
    violation: 요청 시간 초과에서 새 멱등 키로 자동 시작하지 않는다; 준비 중 HTTP 응답/재시도 표시 계약은 I5 확정 전 분리한다
    source: FR4.1, FR4.2, NFR4, AC4.1.3, AC4.1.10, AC4.2.8
  - id: BR6.2
    statement: 진행 머리는 서버가 확인한 현재 목표와 단계만 보여 준다
    category: validation
    applies_to: PracticeProgress
    trigger: 진행 조회 응답
    logic: IF goalIndex/goalCount/goalName/stage가 유효하면 THEN 현재 n/N과 단계를 폴링 1주기 안에 표시한다; 순번은 1부터이고 index는 count를 넘지 않는다
    violation: 알 수 없는 값이나 null을 임의 단계/0회/성공으로 바꾸지 않는다; 판정/상황 본문/보호자 원문은 표시하지 않는다
    source: FR4.2, NFR3, AC4.2.2, AC4.2.8
  - id: BR6.3
    statement: 실제 통합 합의가 없는 경로는 독립 시험만 완료로 기록한다
    category: constraint
    applies_to: IntegrationAdmission
    trigger: 설계 승인 또는 코드 계획
    logic: IF I1~I5 또는 상위 B14의 정책이 미합의이면 THEN 담당 경로는 Deferred로 추적하고 시험 대역/독립 부품과 실제 연결의 완료 기준을 구분한다
    violation: 대역을 운영 빈/성공 fallback으로 사용하지 않는다; API나 상대 기록을 임의 변경하지 않는다
    source: NFR3, NFR4, Q1, Q3, Q5
```

## 규칙 요약

| 묶음 | 규칙 | 책임 |
|---|---|---|
| 시작 | BR1.1~BR1.6 | 소유권·추첨·멱등·첫 상황·REQUESTED 인계·불변 원본 |
| 단계 | BR2.1~BR2.6 | 참여 뒤 진행·반응·동의·시한·실패 보존·목표 이동 |
| 판정 | BR3.1~BR3.2 | 5초/재시도·불확실·정보 분리 |
| 음성 | BR4.1~BR4.4 | 단계 게이트·Cue 최소 입력·반복·새 상황 검증 |
| 기록 | BR5.1~BR5.4 | 장면 귀속·실노출·정산·삭제 |
| 화면/통합 | BR6.1~BR6.3 | 표시·중복 제출·미합의 경로 구분 |

## 합의와 정책 상태

Q4의 가중식은 이번 작업에서 확인된 로컬 규칙이다. Q2의 20초는 U5b 설계 제안으로 확인됐으며 U4b의 재생/시계 계약 합의를 뜻하지 않는다. Q3의 실패 후 목표 상태·투영·후속 전이는 결정하지 않는다. B14 무반응 뒤 도움 제안은 상위 가정이며 이번 질문으로 새로 확정하지 않았다. 해당 경로는 functional-spec.md의 I1~I5와 열린 정책 목록에서 운영 통합 전 조건으로 추적한다.
