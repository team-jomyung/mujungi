# U10 업무 규칙

## Sources

`../../inception/requirements-analysis/requirements.md`가 상위 정본이다. `entities.md`는 데이터 모양, `functional-spec.md`는 실행 순서, `functional-design-questions.md` Q1-A는 관심사 전달 결정을 소유한다.

## Rule model

```yaml
rules:
  - id: BR1.1
    statement: 생성과 검토를 다른 요청으로 수행한다.
    category: policy
    applies_to: ScenarioScript
    trigger: 새 대본 생성
    logic: IF 생성 출력이 구조적으로 유효 THEN 그 출력과 고정 목표로 별도 검토를 요청한다.
    violation: 검토 없는 대본을 발급하지 않는다.
    source: [FR1.1]
  - id: BR1.2
    statement: APPROVE만 승인 후보이고 서버 검사는 별도다.
    category: validation
    applies_to: ScenarioReview
    trigger: 검토 완료
    logic: IF verdict가 APPROVE이고 reasons가 비었으며 메타데이터가 유효 THEN 서버 검사로 진행한다.
    violation: 누락·REJECT·UNCERTAIN·스키마 실패·시간 초과·사유 불일치는 미승인이다.
    source: [FR1.2, FR2.1]
  - id: BR1.3
    statement: 나이 적합성과 의미상 반복을 자체 검토한다.
    category: policy
    applies_to: ScenarioReview
    trigger: 생성과 검토 입력 작성
    logic: IF 생성 요청 THEN 나이와 같은 아이의 실제 노출 최근 3건 이력을 최초 마감 안에 읽어 생성·검토에 전달한다. 정상 빈 이력도 검토하며 미노출·다른 아이는 제외한다.
    violation: 이력 조회 실패·상한 초과를 빈 이력으로 대체하지 않으며 AGE_MISMATCH와 REPETITION 반려를 발급하지 않는다.
    source: [FR1.3]
  - id: BR2.1
    statement: 내용 재생성은 전체 요청에서 한 번만 허용한다.
    category: constraint
    applies_to: GenerationBudget
    trigger: 구조·내용·중복·변형 검사 실패
    logic: IF 내용 수정 미사용이고 마감 내 시도 창이 남음 THEN 한 번 사용하고 생성·별도 검토를 다시 수행한다.
    violation: 미승인 대본은 SCENARIO_GEN_FAILED로 끝낸다.
    source: [FR2.2, FR6.2, NFR1]
  - id: BR2.2
    statement: 기존 선점과 발급 직전 권한 확인을 유지한다.
    category: authorization
    applies_to: ScenarioScript
    trigger: 선점·재사용·저장·노출
    logic: IF 세션·아이·보호자·목표·동의·삭제 상태·lease가 모두 현재 요청과 일치 THEN 재사용 또는 저장한다.
    violation: 기존 권한·세션·선점 오류로 거절하고 늦은 결과를 버린다.
    source: [FR2.3, NFR3]
  - id: BR2.3
    statement: 서버는 모델 승인과 독립적으로 장면을 검증한다.
    category: validation
    applies_to: ScenarioScene
    trigger: 검토 APPROVE 후
    logic: IF 키·종류·길이·필수 장면·목표·관계·기회·금지 표현·지문 검사가 모두 유효 THEN 발급 후보가 된다.
    violation: 내용 재생성 또는 최종 실패로 처리한다.
    source: [FR2.1, FR2.3]
  - id: BR3.1
    statement: 새 장면 필드는 선택 확장이고 기존 필수 응답은 첫 BASE에서 만든다.
    category: policy
    applies_to: ScenarioScript
    trigger: 대본 저장·응답·첫 패키지 전달
    logic: IF 새 대본 THEN 첫 BASE를 기존 필드로 투영하고 선택 확장도 전달한다. 과거 행은 기존 모양으로 읽는다.
    violation: 잘못된 BASE나 유실된 전달을 성공으로 처리하지 않는다.
    source: [FR3.1, FR3.2]
  - id: BR3.2
    statement: 상대 역할은 서버가 고정하고 모델에 받지 않는다.
    category: policy
    applies_to: ScenarioScene
    trigger: 요청 스키마·응답 작성
    logic: IF 장면을 발급 THEN 서버 고정 역할을 붙인다.
    violation: 모델의 임의 역할 지시는 승인된 역할로 사용하지 않는다.
    source: [FR3.2]
  - id: BR3.3
    statement: 새 필드의 저장은 기존 자료·쓰기를 보존한다.
    category: constraint
    applies_to: ScenarioScript
    trigger: 저장소 확장
    logic: IF V10 적용 THEN 선택 필드만 더하고 기존 행과 구버전 쓰기를 계속 허용한다.
    violation: 호환 검증 실패면 출시하지 않는다.
    source: [FR3.3]
  - id: BR4.1
    statement: 담은 당시 목표를 준비에서 발급까지 고정한다.
    category: policy
    applies_to: FrozenGoalContext
    trigger: 연습 준비
    logic: IF 해당 아이의 활성 선택 THEN 종류별 실제 버전·표현·4요소·skillRef를 읽고 준비 원본에 보존한다.
    violation: 없는 선택·다른 아이·철회·삭제는 거절한다.
    source: [FR4.1, FR4.2]
  - id: BR4.2
    statement: 기존 행동 목표와 새 후보 목표를 구분해 연결한다.
    category: policy
    applies_to: FrozenGoalContext
    trigger: 목표 준비·기준 전달
    logic: IF LEGACY_BEHAVIOR THEN 기존 행동 정의를 고정한다. IF CANDIDATE THEN 후보 스냅샷을 쓰고 행동 카탈로그 조회나 자동 담기를 하지 않는다.
    violation: 후보를 임의의 기존 행동으로 바꾸지 않는다.
    source: [FR4.2]
  - id: BR5.1
    statement: 그래프 조회 전체는 같은 2초 창 안에서만 수행한다.
    category: constraint
    applies_to: GenerationBudget
    trigger: 구조 맥락·변형 계획 조회
    logic: IF skillRef가 있고 그래프가 유효 THEN 아동별 맥락과 계획을 합계 2초 안에 얻는다. 전체 15초에도 포함한다.
    violation: 꺼짐·빈 값·NONE·오류·초과면 기본 경로와 graphContextUsed=false다.
    source: [FR5.1, FR5.2, NFR1]
  - id: BR5.2
    statement: 그래프 장애를 개인화 성공으로 표시하지 않는다.
    category: policy
    applies_to: GenerationMetadata
    trigger: 생성 입력 확정
    logic: IF 유효 맥락이 실제 입력에 사용됨 THEN true와 버전을 기록한다. ELSE false와 null을 기록한다.
    violation: 빈 맥락의 개인화 표시를 거절한다. 다음 요청의 재조회는 허용한다.
    source: [FR5.2]
  - id: BR6.1
    statement: 변형은 기본 기술과 불변·보존 조건을 유지해야 한다.
    category: validation
    applies_to: ScenarioScene
    trigger: 구조 맥락이 있는 BASE·VARIANT 검사
    logic: IF 동일 기술·불변 문장·보존 조건·허용 거리·바뀐 한 요소가 계획과 일치 THEN 유효하다.
    violation: BASE 실패는 전체 재생성, VARIANT 실패는 허용된 한 번의 변형 보정 또는 제외다.
    source: [FR6.1, FR6.2]
  - id: BR6.2
    statement: 변형을 빼고 발급하는 BASE도 별도 검토 승인이 있어야 한다.
    category: validation
    applies_to: ScenarioScript
    trigger: 변형 실패 후 기본만 진행
    logic: IF 유효 BASE를 확정할 수 있고 남은 마감 안에서 BASE-only 별도 검토가 APPROVE THEN 그 BASE만 서버 검사 후 발급한다.
    violation: 반려·검토 실패·마감이면 BASE도 발급하지 않는다.
    source: [FR1.2, FR6.2, NFR1]
  - id: BR7.1
    statement: 관심사 허용은 상황 생성·자체 검토의 정리된 입력에만 한정한다.
    category: policy
    applies_to: ScenarioReview
    trigger: 공급자 입력 정리
    logic: IF 상황 생성·검토 목적이고 고정 허용 필드의 관심사 목록 THEN 권한 확인된 아동 프로필의 내부 이름 별칭으로 목표·관심사·원본·이력·검토 입력을 정리하고 연락처·식별자를 제거해 크기를 제한한다.
    violation: 별칭의 소유 관계·유효 이름을 얻지 못하면 공급자 호출 전에 실패한다. 다른 목적의 interests 금지는 유지하고 금지 입력은 전송하지 않는다.
    source: [FR7.1, FR7.3]
  - id: BR7.2
    statement: 원문·비밀·식별자는 응답·로그·불필요한 공급자 입력에 내보내지 않는다.
    category: authorization
    applies_to: ScenarioScript
    trigger: 요청·저장·응답·관측
    logic: IF 외부 출력 THEN 허용 구조·사유 코드·판·시간·개수만 사용하고 이름은 아이로 치환한다. 제브를 호출하지 않는다.
    violation: 잔여 금지 입력·출력은 거절하고 본문 없는 오류로 끝낸다.
    source: [FR7.1, FR7.2, NFR4, NFR6]
  - id: BR8.1
    statement: 기술 재시도는 U10 요청 전체에서 한 번이며 호출 안쪽과 중첩하지 않는다.
    category: constraint
    applies_to: GenerationBudget
    trigger: 일시 공급자 실패
    logic: IF 재시도 슬롯이 있고 남은 마감이 대기와 다음 시도를 허용 THEN 같은 입력을 한 번만 다시 요청한다.
    violation: 인증·거절·스키마·내용 실패를 기술 재시도로 반복하지 않는다.
    source: [FR2.2, NFR1]
```

## Rules summary

| 묶음 | 규칙 | 역할 |
|---|---|---|
| 승인 | BR1.1~BR1.3 | 별도 요청·APPROVE·나이·의미 반복 |
| 검사·경합 | BR2.1~BR2.3 | 재생성 1회·권한/lease·독립 검사 |
| 대본 | BR3.1~BR3.3 | 선택 확장·첫 BASE·고정 역할·기존 자료 |
| 목표 | BR4.1~BR4.2 | 담은 버전·종류별 고정 |
| 그래프 | BR5.1~BR5.2 | 합계 2초·기본 경로·정확한 표시 |
| 변형 | BR6.1~BR6.2 | 보존 판정·BASE-only 별도 승인 |
| 정보 경계 | BR7.1~BR7.2 | 관심사 제한 허용·최소 정보·제브 미호출 |
| 기술 실패 | BR8.1 | 전체 요청의 단일 기술 재시도 |
