# U14 업무 규칙

## 규칙 정본

```yaml
rules:
  - id: BR1.1
    statement: "후보 버전을 선택 시 스냅샷으로 고정하며 자동 담기는 하지 않는다."
    category: policy
    applies_to: U14
    trigger: "선택 저장"
    logic: "IF 선택 저장 THEN 후보 현재 버전을 읽어 불변 스냅샷을 원자적으로 저장한다."
    violation: "타 아동/비활성 후보는 기존 오류 계약으로 거절한다."
    source: FR1.1
  - id: BR1.2
    statement: "담은 목표는 아동별 C-DS10으로 읽고 기존 행동 목표도 보존한다."
    category: policy
    applies_to: U14
    trigger: "목표 읽기"
    logic: "IF 목표 읽기 THEN CANDIDATE/LEGACY_BEHAVIOR를 구분하고 소유권과 생존을 확인한다."
    violation: "없거나 철회/삭제/타 아동이면 empty다."
    source: FR1.2
  - id: BR2.1
    statement: "후보는 유효한 아이 기록에서 생성하며 고정 행동 목록·연령을 쓰지 않는다."
    category: policy
    applies_to: U14
    trigger: "결과 후보 생성"
    logic: "IF 결과 후보 생성 THEN 최대3개,이름12자,화면 근거40자 이내로 검증한다."
    violation: "빈 결과는 후보 없음이다."
    source: FR2.1
  - id: BR2.2
    statement: "4요소·비식별화 근거 일치·skillRef 허용 목록과 기존 안전 검사를 만족해야 한다."
    category: policy
    applies_to: U14
    trigger: "후보 검증"
    logic: "IF 후보 검증 THEN 전사와 출처를 대조하고 U15 seedSummaries 또는 NONE을 확인한다."
    violation: "위반 후보를 제외한다."
    source: FR2.2
  - id: BR2.3
    statement: "씨앗 실패는 DONE을 바꾸지 않고 성공은 새 후보 버전으로만 연결한다."
    category: policy
    applies_to: U14
    trigger: "결과 저장 뒤 NONE 처리"
    logic: "IF 결과 저장 뒤 NONE 처리 THEN U15 구조 서명과 idempotency를 사용하고 현재 버전·동의를 다시 확인한다."
    violation: "실패/삭제/구버전은 NONE을 유지하거나 반영을 중단한다."
    source: FR2.3
  - id: BR3.1
    statement: "AFTER_REASK는 정본에 보존하고 27 칩에서만 접는다."
    category: policy
    applies_to: U14
    trigger: "저장·공개·리포트 투영"
    logic: "IF 저장·공개·리포트 투영 THEN 표시 표현8자 이내,안전하지 않으면 전달. 단순 재발화 확인은 시점을 바꾸지 않는다."
    violation: "내부 시점/표시를 혼동하지 않는다."
    source: FR3.1
  - id: BR3.2
    statement: "flowVersion으로 기록 형식을 나누고 실제 시작 장면만 처리한다."
    category: policy
    applies_to: U14
    trigger: "기록 읽기"
    logic: "IF 기록 읽기 THEN 새 형식은 양의 가변 sceneNo와 허용 사실을 사용하고 부재는 U5b로 읽는다."
    violation: "미지원 버전/중복 장면/잘못된 형식은 검증 오류다."
    source: FR3.2
  - id: BR3.3
    statement: "재판정 응답의 장면 집합은 입력과 같으며 차이는 내부로만 남긴다."
    category: policy
    applies_to: U14
    trigger: "REJUDGMENT 응답"
    logic: "IF REJUDGMENT 응답 THEN sceneRunId 일대일 대응과 value/timing을 검증하고 live timing과 비교한다."
    violation: "누락/중복/불명 장면은 SCHEMA_INVALID다."
    source: FR3.3
  - id: BR3.4
    statement: "작업은 총4회,추가 지연5·15·30초이며 내부 재시도는 없다."
    category: policy
    applies_to: U14
    trigger: "작업 실패"
    logic: "IF 작업 실패 THEN 기존 lease/attempt identity와 상태 전이로 다음 시도를 예약한다."
    violation: "최종 실패는 FAILED,씨앗 후처리는 독립이다."
    source: FR3.4
  - id: BR3.5
    statement: "혼합 리포트는 버전별 정본 시점으로 집계한다."
    category: policy
    applies_to: U14
    trigger: "리포트 생성"
    logic: "IF 리포트 생성 THEN 기존 결과는 원뜻을 유지하고 새 AFTER_REASK를 분리한다."
    violation: "chip에서 timing을 역추정하지 않는다."
    source: FR3.5
  - id: BR4.1
    statement: "새 후보 공개 DTO는 displayEvidence만 포함하고 내부 필드를 제외한다."
    category: policy
    applies_to: U14
    trigger: "보호자 결과 응답"
    logic: "IF 보호자 결과 응답 THEN 레거시 응답을 유지하고 새 형식은 최소 API 파서로 화면 모델에 맞춘다."
    violation: "엄격 변경을 숨기지 않고 계약 편차로 기록한다."
    source: FR4.1
  - id: BR4.2
    statement: "삭제/철회 경합에서 새 목표·결과 데이터가 되살아나지 않는다."
    category: policy
    applies_to: U14
    trigger: "삭제 및 외부 응답 저장"
    logic: "IF 삭제 및 외부 응답 저장 THEN RESULT70/PRACTICE150과 기존 잠금 순서를 지킨다."
    violation: "실패를 삭제 완료로 보고하지 않는다."
    source: FR4.2
```

## 규칙 요약

- BR1.1: 후보 버전을 선택 시 스냅샷으로 고정하며 자동 담기는 하지 않는다. (FR1.1)
- BR1.2: 담은 목표는 아동별 C-DS10으로 읽고 기존 행동 목표도 보존한다. (FR1.2)
- BR2.1: 후보는 유효한 아이 기록에서 생성하며 고정 행동 목록·연령을 쓰지 않는다. (FR2.1)
- BR2.2: 4요소·비식별화 근거 일치·skillRef 허용 목록과 기존 안전 검사를 만족해야 한다. (FR2.2)
- BR2.3: 씨앗 실패는 DONE을 바꾸지 않고 성공은 새 후보 버전으로만 연결한다. (FR2.3)
- BR3.1: AFTER_REASK는 정본에 보존하고 27 칩에서만 접는다. (FR3.1)
- BR3.2: flowVersion으로 기록 형식을 나누고 실제 시작 장면만 처리한다. (FR3.2)
- BR3.3: 재판정 응답의 장면 집합은 입력과 같으며 차이는 내부로만 남긴다. (FR3.3)
- BR3.4: 작업은 총4회,추가 지연5·15·30초이며 내부 재시도는 없다. (FR3.4)
- BR3.5: 혼합 리포트는 버전별 정본 시점으로 집계한다. (FR3.5)
- BR4.1: 새 후보 공개 DTO는 displayEvidence만 포함하고 내부 필드를 제외한다. (FR4.1)
- BR4.2: 삭제/철회 경합에서 새 목표·결과 데이터가 되살아나지 않는다. (FR4.2)

## Sources

이 기록 requirements.md, entities.md 및 functional-spec.md D1~D6를 함께 읽는다. 비기능 요구는 traceability.json에서 검증 단계로 별도 추적하며 업무 규칙을 새로 만들지 않는다.

