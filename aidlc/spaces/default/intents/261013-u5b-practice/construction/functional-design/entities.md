# U5b 데이터 모델

## Sources

기준은 `../../inception/requirements-analysis/requirements.md`, 같은 폴더의 `functional-design-questions.md` Q1~Q6, 재사용 정본 `261008-mujoong-service/inception/domain-design/components.md`의 엔터티 소유 및 `contract-design/contract-summary.md` 6·8·9절이다. Core 시작 준비는 Q5의 새 설계이며 기존 통합 계약과 구분한다. 데이터 형태의 정본은 아래 YAML이고 업무 순서는 functional-spec.md다.

## Entity Model

```yaml
model_version: 1
entities:
  - name: StartPreparation
    owner: CorePracticeStart
    description: 실제 세션 시작 전 멱등 키에 고정한 준비 원본; ACTIVE 세션이 아님
    attributes:
      - {name: preparationId, type: UUID, required: true, unique: true}
      - {name: guardianId, type: UUID, required: true, references: Guardian}
      - {name: childId, type: UUID, required: true, references: Child}
      - {name: idempotencyKey, type: UUID, required: true}
      - {name: requestHash, type: Digest, required: true}
      - {name: sessionId, type: UUID, required: true, unique: true}
      - {name: goals, type: ListOfFrozenGoal, required: true, min: 1}
      - {name: state, type: Enum, required: true, allowed_values: [PREPARING, READY, FAILED, HANDED_OFF], default: PREPARING}
      - {name: firstScenarioId, type: UUID, required: false, references: Scenario}
      - {name: failureCode, type: ErrorCode, required: false}
      - {name: generation, type: Integer, required: true, min: 1}
      - {name: leaseUntil, type: Instant, required: true}
      - {name: createdAt, type: Instant, required: true}
    constraints:
      - 보호자와 멱등 키의 조합은 유일하다
      - 같은 키의 다른 requestHash는 거절한다
      - goals와 sessionId는 첫 선점 승자가 고정하며 재시도에서 변경하지 않는다
      - READY에는 저장된 firstScenarioId가 필수이고 FAILED에는 failureCode가 필수다
      - HANDED_OFF는 같은 sessionId의 실제 REQUESTED 수락과 연결된다
    relationships:
      - {target: Scenario, cardinality: one-to-zero-or-one, direction: owns_reference, note: 준비한 첫 상황이며 외부 저장소의 외래 키가 아님}
  - name: SessionGoal
    owner: PracticeProtocol
    description: 시작 시 고정한 목표와 진행 상태
    attributes:
      - {name: id, type: UUID, required: true, unique: true}
      - {name: sessionId, type: UUID, required: true, references: ActivitySession}
      - {name: guardianId, type: UUID, required: true, references: Guardian}
      - {name: goalSelectionId, type: UUID, required: true, references: GoalSelection}
      - {name: order, type: Integer, required: true, min: 1}
      - {name: displayName, type: Text, required: true}
      - {name: behaviorKey, type: Text, required: true}
      - {name: behaviorVersion, type: Integer, required: true, min: 1}
      - {name: judgmentCriteriaSnapshot, type: ProtectedText, required: true}
      - {name: status, type: Enum, required: true, allowed_values: [PENDING, RUNNING, DONE, SKIPPED, NOT_REACHED]}
      - {name: currentStage, type: Enum, required: false, allowed_values: [SCENARIO_INTRO, FIRST_RESPONSE, HELP_OFFER, CUE, AFTER_CUE_RESPONSE, NEW_SCENARIO_RESPONSE]}
      - {name: generation, type: Integer, required: true, min: 0}
      - {name: skipReason, type: Reason, required: false}
    constraints:
      - 세션 안의 order와 goalSelectionId는 중복되지 않는다
      - 목표 이름과 정의 버전 및 기준과 순서는 시작 후 불변이다
      - PENDING과 RUNNING은 내부 상태이고 외부 상태는 계약 6절의 값만 사용한다
      - 새 상황 생성 실패 후 최종 status/skipReason은 Q3 합의 대기이며 임의 값으로 변환하지 않는다
    relationships:
      - {target: PracticeAttempt, cardinality: one-to-many, direction: owns}
  - name: PracticeAttempt
    owner: PracticeProtocol
    description: 같은 목표의 원 상황 또는 새 상황 준비와 실행
    attributes:
      - {name: id, type: UUID, required: true, unique: true}
      - {name: sessionGoalId, type: UUID, required: true, references: SessionGoal}
      - {name: attemptType, type: Enum, required: true, allowed_values: [ORIGINAL, NEW_SCENARIO]}
      - {name: state, type: Enum, required: true, allowed_values: [PREPARING, ISSUED, EXPOSED, COMPLETED, PREPARATION_FAILED]}
      - {name: scenarioId, type: UUID, required: false, references: Scenario}
      - {name: previousScenarioId, type: UUID, required: false, references: Scenario}
      - {name: exposedAt, type: Instant, required: false}
      - {name: preparationFailure, type: Enum, required: false, allowed_values: [GENERATION_FAILED, TIMEOUT, UNAVAILABLE, VALIDATION_FAILED]}
    constraints:
      - 목표별 attemptType은 유일하며 최대 두 종류다
      - ISSUED/EXPOSED/COMPLETED에는 실재 scenarioId가 필수다
      - PREPARATION_FAILED에 가짜 scenarioId나 exposedAt을 넣지 않는다
      - NEW_SCENARIO는 같은 목표의 ORIGINAL 상황만 참조한다
    relationships:
      - {target: PracticeResponse, cardinality: one-to-many, direction: owns}
      - {target: CueDelivery, cardinality: one-to-zero-or-one, direction: owns}
  - name: PracticeResponse
    owner: PracticeProtocol
    description: 첫 반응과 도움 후 및 새 상황의 반응 기회이며 무반응도 별도 사실로 남김
    attributes:
      - {name: id, type: UUID, required: true, unique: true}
      - {name: attemptId, type: UUID, required: true, references: PracticeAttempt}
      - {name: scenarioId, type: UUID, required: true, references: Scenario}
      - {name: responseType, type: Enum, required: true, allowed_values: [FIRST, AFTER_CUE, NEW_SCENARIO]}
      - {name: utteranceSeq, type: Integer, required: false, min: 1}
      - {name: evaluationState, type: Enum, required: true, allowed_values: [PENDING, FINALIZED, TERMINATED_PENDING]}
      - {name: evaluationGeneration, type: Integer, required: true, min: 1}
      - {name: acceptedAt, type: Instant, required: true}
      - {name: evaluationRequestedAt, type: Instant, required: false}
      - {name: evaluationWork, type: EvaluationWork, required: false}
      - {name: label, type: Enum, required: false, allowed_values: [functional, partial, none, uncertain, NOT_PERFORMED]}
      - {name: notPerformedReason, type: Reason, required: false}
      - {name: opportunityOpenedAt, type: Instant, required: false}
      - {name: closedAt, type: Instant, required: false}
    constraints:
      - attemptId와 responseType의 조합은 유일하다
      - FIRST/AFTER_CUE는 ORIGINAL에만 속하고 NEW_SCENARIO는 새 상황에만 속한다
      - NOT_PERFORMED는 utteranceSeq가 null이고 사유가 필수이며 평가자를 호출하지 않는다
      - PENDING은 utteranceSeq가 필수이고 label과 closedAt 및 최종 Evaluation은 없으며 uncertain으로 대체하지 않는다
      - 실제 반응의 PENDING에는 EvaluationWork가 필수다; acceptedAt부터 전체 12초의 같은 예산 원본을 중복/재시작에서도 재사용한다
      - FINALIZED에는 label과 closedAt이 필수다; NOT_PERFORMED 이외에는 같은 label의 최종 Evaluation이 정확히 하나 존재한다
      - TERMINATED_PENDING은 종료 전에 확보한 반응을 보존하되 label과 closedAt 및 최종 Evaluation이 없다; 외부 표현은 I2 합의 전 보류한다
      - 기회 식별자와 전사 순번을 최초 수락 시 고정한다; 중복 전사나 판정 재시도로 새 반응 행을 만들지 않는다
      - 최종 Evaluation 생성과 반응 label/closedAt 및 FINALIZED 전이는 동일한 조건부 원자적 저장 경계다
      - 종료와 최종 갱신은 같은 세션 정산 경계에서 직렬화한다; 종료 승자는 evaluationGeneration을 폐기하고 늦은 결과의 저장을 차단한다
      - 다른 반응을 덮어쓰지 않고 기회를 두 번 집계하지 않는다
    relationships:
      - {target: Evaluation, cardinality: one-to-zero-or-one, direction: evaluation_reference}
  - name: CueDelivery
    owner: PracticeProtocol
    description: 동의와 실제 제공한 단일 도움
    attributes:
      - {name: id, type: UUID, required: true, unique: true}
      - {name: attemptId, type: UUID, required: true, unique: true, references: PracticeAttempt}
      - {name: scenarioId, type: UUID, required: true, references: Scenario}
      - {name: consentResponse, type: Enum, required: true, allowed_values: [ACCEPTED, DECLINED, NO_RESPONSE]}
      - {name: deliveryState, type: Enum, required: true, allowed_values: [NOT_GIVEN, REQUESTED, STARTED, COMPLETED, FAILED]}
      - {name: deliveredText, type: ProtectedText, required: false}
      - {name: givenAt, type: Instant, required: false}
      - {name: containsAnswerSentence, type: Boolean, required: false}
    constraints:
      - 새 상황이나 동의 전에 제공하지 않는다
      - REQUESTED를 제공 성공으로 세지 않으며 givenAt에는 실제 재생 시작 증거가 필요하다
      - 거부와 무응답 및 미제공은 givenAt과 deliveredText를 null로 유지한다
  - name: Evaluation
    owner: ResponseEvaluator
    description: 한 반응에 최대 두 번 호출한 최종 진행 판정
    attributes:
      - {name: id, type: UUID, required: true, unique: true}
      - {name: responseId, type: UUID, required: true, unique: true, references: PracticeResponse}
      - {name: label, type: Enum, required: true, allowed_values: [functional, partial, none, uncertain]}
      - {name: reason, type: ProtectedText, required: false}
      - {name: evidenceUtteranceSeq, type: Integer, required: false, min: 1}
      - {name: reviewNeeded, type: Boolean, required: true}
      - {name: attempts, type: Integer, required: true, min: 0, max: 2}
      - {name: modelVersion, type: Text, required: false}
      - {name: promptVersion, type: Text, required: false}
    constraints:
      - 증거 순번은 해당 반응의 utteranceSeq와 일치한다
      - 무반응에는 판정 행을 만들지 않는다
      - attempts는 실제 시작한 공급자 호출 수다; 큐에서 전체 예산이 소진되어 호출 없이 uncertain 확정이면 0이며 무반응과는 다르다
      - responseId의 최종 행은 하나이며 연결된 FINALIZED 반응의 label과 항상 같다; 개별 호출 결과를 최종 행으로 먼저 저장하지 않는다
      - 판정 본문과 이유 및 기준을 보호자 응답이나 로그에 내보내지 않는다
value_objects:
  - name: EvaluationWork
    owner: PracticeProtocol
    description: 반응 소유의 영속 내부 작업 문맥이며 외부 계약으로 전송하지 않음
    attributes:
      - {name: budgetVersion, type: Text, required: true}
      - {name: totalBudget, type: Duration, required: true, fixed_value: 12s}
      - {name: deadlineAt, type: Instant, required: true}
      - {name: finalizationReserve, type: Duration, required: true}
      - {name: attemptsStarted, type: Integer, required: true, min: 0, max: 2}
      - {name: activeAttemptToken, type: UUID, required: false}
      - {name: activeAttemptDeadlineAt, type: Instant, required: false}
      - {name: finalizationClaimed, type: Boolean, required: true, default: false}
    constraints:
      - deadlineAt는 부모 반응 acceptedAt 더하기 totalBudget이며 최초 저장 뒤 바꾸지 않는다
      - finalizationReserve는 양수이며 전체 예산보다 작다; 저장 및 최종 처리 지연을 포함한 검증된 기술 예산으로 설정하고 미검증 기본 숫자를 임의 확정하지 않는다
      - attemptsStarted와 activeAttemptToken의 변경은 반응의 evaluationGeneration 및 PENDING 조건으로 선점한다; 재시도로 횟수나 deadlineAt를 초기화하지 않는다
      - finalizationClaimed의 승자만 최종 원자적 저장을 시도한다; 이전 공급자 토큰은 무효화한다
      - FINALIZED 또는 TERMINATED_PENDING 뒤에는 새 공급자 호출을 선점할 수 없다
  - name: FrozenGoal
    owner: CorePracticeStart
    description: 준비 선점 때 선택과 정의를 복사하고 한 번의 추첨 결과를 붙인 불변 값
    attributes:
      - {name: goalSelectionId, type: UUID, required: true, references: GoalSelection}
      - {name: displayName, type: Text, required: true}
      - {name: behaviorKey, type: Text, required: true}
      - {name: behaviorVersion, type: Integer, required: true, min: 1}
      - {name: judgmentCriteriaSnapshot, type: ProtectedText, required: true}
      - {name: savedSortOrder, type: Integer, required: true}
      - {name: selectionRank, type: Integer, required: true, min: 1}
      - {name: selectedCount, type: Integer, required: true, min: 1}
      - {name: favoriteAtPreparation, type: Boolean, required: true}
      - {name: favoriteMultiplier, type: Integer, required: true, allowed_values: [1, 2]}
      - {name: weight, type: Integer, required: true, min: 1}
      - {name: order, type: Integer, required: true, min: 1}
    constraints:
      - 같은 preparationId 안에서 goalSelectionId와 selectionRank 및 order는 각각 유일하다
      - selectedCount는 goals 크기 N과 같고 selectionRank와 order는 각각 1부터 N까지 빠짐없는 순열이다
      - selectionRank는 고정한 서버 sortOrder의 오름차순 순위이며 즐겨찾기별 화면 정렬 순위와 다르다; 같은 sortOrder의 순위는 서버 저장 순서의 기존 동률 규칙을 재사용하며 규칙이 없으면 I5에서 확인하고 임의 결정하지 않는다
      - favoriteMultiplier는 favoriteAtPreparation이 참이면 2이고 아니면 1이다; weight는 favoriteMultiplier 곱하기 selectedCount-selectionRank+1이다
      - savedSortOrder/selectionRank/favoriteAtPreparation/favoriteMultiplier/weight와 selectedCount는 추첨 입력이고 order는 비복원 추첨 결과다
      - 최초 준비 원본 커밋 때 모든 필드를 함께 고정한다; 재시도와 임대 세대 변경 및 선택/별/정의 변경으로 수정하거나 재추첨하지 않는다
      - 소유 보호자와 아이는 부모 StartPreparation에서 상속하며 다른 소유자의 선택을 섞지 않는다
      - 정의와 기준은 behaviorKey/behaviorVersion에 대응하는 승인 원본이며 빈 기준을 허용하지 않는다
type_aliases:
  - {name: ListOfFrozenGoal, element_type: FrozenGoal, description: StartPreparation이 소유하는 불변 목록이며 order 오름차순으로 인계}
external_entities:
  - {name: ActivitySession, owner: U4SessionLifecycle}
  - {name: Guardian, owner: CoreIdentity}
  - {name: Child, owner: CoreChild}
  - {name: GoalSelection, owner: CorePracticeGoal}
  - {name: Scenario, owner: CoreScenarioService}
```

## 데이터 소유와 저장 경계

StartPreparation은 새로운 Core 논리 엔터티다. 저장할 경우 Core V7 이후의 추가 변경과 U4a 합의 항목이며 기존 core_activity를 활성 준비 상태로 위장하지 않는다. 나머지 다섯 엔터티는 Realtime V4의 연습 데이터다. Guardian·Child·GoalSelection·Scenario와의 교차 서비스 참조는 논리 ID이며 상대 저장소를 직접 조회하지 않는다.

FrozenGoal은 StartPreparation 소유 값 객체다. 추첨 입력은 Core 준비 원본에서만 보존한다. 기존 시작 패키지에 없는 순위·가중치·즐겨찾기 필드를 Realtime로 임의 추가하지 않는다. SessionGoal은 받은 목표 정의·기준·최종 order를 고정하며 추첨을 다시 하지 않는다.

EvaluationWork는 Realtime 반응 소유의 내부 저장 문맥이다. 논리 모델과 경합 규칙은 이 설계에서 정의하고 구체 V4 저장 구조는 코드 계획으로 구체화한다. 12초는 Q6의 이번 U5b 기준이며 실제 공급자·부하 시험에 따른 후속 변경은 예산 버전으로 관리한다. 이미 수락한 반응의 deadlineAt는 설정 변경으로 연장하지 않는다.

## 시작 데이터 대응

| Core 준비 원본 | 기존 시작 패키지 | Realtime 원본 | 계약 상태 |
|---|---|---|---|
| StartPreparation.sessionId | C8 시작 요청의 sessionId | SessionGoal.sessionId | 같은 식별자; 준비 문맥에서 패키지 작성자로 전달할 내부 포트 변경은 I5 |
| StartPreparation.guardianId/childId | C8 guardianId/childId | 세션 소유 문맥 및 SessionGoal.guardianId | 목표별 별도 소유자를 넣지 않음 |
| FrozenGoal.goalSelectionId/displayName/behaviorKey/behaviorVersion/order | practice.goals의 같은 이름 필드 | SessionGoal의 같은 이름 필드 | C8 기존 필드; 값 변경 없이 복사 |
| FrozenGoal.judgmentCriteriaSnapshot | practice.goals[].judgmentCriteria | SessionGoal.judgmentCriteriaSnapshot | 내부 명칭만 다름; 보호자 조회/로그/대화 역할에 제외 |
| StartPreparation.firstScenarioId가 가리키는 저장 상황 | 현재 PracticePackage.firstScenario → C8 첫 order 목표의 firstScenario | 첫 SessionGoal의 ORIGINAL PracticeAttempt.scenarioId | 실제 저장 상황만 사용; 내부 패키지와 C8 중첩 위치 변환을 명시적으로 수행 |
| FrozenGoal의 순위·별·배수·가중치·선택 수 입력 | 전송하지 않음 | 재계산·추첨하지 않음 | Core 준비 저장의 신규 필드이며 I5/U4a 합의 항목 |
| preparationId/idempotencyKey/requestHash/generation/leaseUntil | 기존 필드를 임의 추가하지 않음 | 같은 sessionId 수락 결과 재사용 | Core 준비 저장·멱등 인계 포트는 I5/U4a 합의 항목 |

동일 보호자·키·요청 해시의 인계는 같은 sessionId와 order별 동일 목표 값 및 동일 첫 상황을 보낸다. Realtime 수락 재전달은 기존 세션 원본과 대조해 동일하면 재사용하고 다르면 충돌로 거절해야 하며, 이 대조 능력의 실제 연결은 I5 계약 시험으로 확인한다. 두 번째 SessionGoal 묶음을 만들지 않는다. 최초 SessionGoal.id는 Realtime에서 부여하고 재전달은 세션·goalSelectionId의 기존 행을 사용한다. C8 외부 필드 변경이 필요해지는 구현은 I5 합의 전 통합하지 않는다.

SessionGoal의 DONE은 목표 기회 처리 완료이며 성공 판정과 다르다. Q3의 새 상황 준비 실패에서는 최종 상태/외부 표현을 결정하지 않는다. 내부 PracticeAttempt의 PREPARATION_FAILED는 실패 사실만 보존한다.

## 스냅숏 투영

`record.practice.goals`는 순서대로 고정 목표를 투영한다. 실제 상황만 scenarios에 넣고 반응과 도움의 scenarioId는 해당 목표의 scenarios에 존재해야 한다. 최종 판정은 FINALIZED 반응의 진행 라벨에만 연결하며 이유·기준·내부 준비/작업 상태는 보호자 조회에 나가지 않는다. PENDING/TERMINATED_PENDING 반응의 최종 스냅숏 표현은 I2/U4b/U6 합의 전 보류하고 최종 label을 임의 생성하지 않는다. 생성 실패한 새 상황을 표현할 최종 계약은 Q3에 따라 보류한다. 미합의 상태를 계약 밖 JSON 필드나 임의 SKIPPED로 내보내지 않는다.
