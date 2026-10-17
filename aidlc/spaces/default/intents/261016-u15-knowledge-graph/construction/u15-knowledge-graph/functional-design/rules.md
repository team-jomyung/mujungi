# U15 업무 규칙

## Sources

현재 기록의 inception/units-generation/unit-of-work.md · unit-of-work-story-map.md와 inception/requirements-analysis/requirements.md를 따른다. 기존 261015-doc-sync-code-update/inception/domain-design/components.md · decisions.md 및 contract-design/contract-summary.md(C-DS7·C-DS11)를 참조한다. 현재 기록의 domain-design/components.md와 stories.md는 생성되지 않았다. docs/units/u15/brief.md, 기술명세서 v0.3 §6.4·6.5·13과 실제 contracts/ai-io.md·timing.md·schemas/ai-seed-output.schema.json를 대조했다.

## Rules model

```yaml
rules:
  - id: BR1.1
    statement: "아동 등록 시 그래프를 초기화하고 실패하면 첫 조회에서 재시도한다. 같은 아동 초기화를 반복해도 씨앗이 중복되지 않는다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR1.1의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR1.1
  - id: BR1.2
    statement: "seedSummaries, skillContext, variantPlan, writeGeneratedSeed, deleteChild 및 필요한 초기화·생성 씨앗 삭제 API에 childId를 필수로 둔다. 다른 아동의 같은 seedId를 조회·수정·삭제할 수 없어야 한다. 호출 경계에서 보호자 소유권을 확인한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR1.2의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR1.2
  - id: BR1.3
    statement: "기존 findRelatedBehaviorKeys는 공개 행동 카탈로그 전용으로 유지한다. PragmaticsKnowledge의 기존 쓰임과 U5a 시험을 보존한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR1.3의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR1.3
  - id: BR1.4
    statement: "Neo4j 구현과 같은 원천을 쓰는 메모리 대역을 제공한다. 기본 꺼짐 모드와 씨앗이 채워진 개발·시험 모드는 구분한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR1.4의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR1.4
  - id: BR1.5
    statement: "U15 서비스 경계는 아동별 조회·생성 시작 전에 소유권·동의·삭제 상태를 확인한다. 동의 철회 뒤에는 해당 아동의 자료나 씨앗을 AI 입력으로 반환하지 않는다. 외부 생성 응답 뒤 저장 직전에 상태를 다시 확인하여 철회 전 요청의 늦은 완료 및 철회 후 재요청이 새 씨앗을 저장하지 못하게 한다(원 AC5.2.4). 확인 불가능 시에도 허용하지 않는다."
    category: authorization
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR1.5의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR1.5
  - id: BR2.1
    statement: "social-comm-poc.zip의 지정 자료에서 기본 씨앗 14개(S1~S10, X1~X4)와 어휘를 적재한다. 원본 ZIP을 수정하지 않고 외부 말뭉치·평가셋·없는 graph_out은 사용하지 않는다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR2.1의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR2.1
  - id: BR2.2
    statement: "아동별 기본 씨앗 복사와 생성 씨앗을 분리한다. 기본 씨앗 판 갱신은 같은 ID의 POC 씨앗을 갱신하고 GENERATED 씨앗을 보존한다. 사용 중단은 enabled=false로 처리한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR2.2의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR2.2
  - id: BR2.3
    statement: "적재 버전과 출처를 기록한다. 누락·중복·허용 밖 값·손상 파일은 명확히 실패하고 완료 버전을 남기지 않는다. ZIP 경로 탈출을 허용하지 않고 중복 루트의 동일 자료를 두 번 적재하지 않는다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR2.3의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR2.3
  - id: BR2.4
    statement: "core V8에 generated_seed와 그래프 버전 표를 추가한다. 기존 마이그레이션은 바꾸지 않는다. 그래프 재구성은 아동 목록·기본 씨앗과 PostgreSQL 생성 씨앗으로 재현 가능해야 한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR2.4의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR2.4
  - id: BR2.5
    statement: "seedSummaries와 새 씨앗 생성의 seed_catalog에는 enabled=true인 씨앗만 포함한다. 비활성 씨앗은 ID와 과거 참조를 보존하되 새 생성 입력 목록에서 제외한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR2.5의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR2.5
  - id: BR3.1
    statement: "구조 서명 5필드, 불변 문장, 보존 조건, 아동용 기본 관계·자리와 허용 목록을 반환한다. 생성 씨앗은 씨앗 자체의 보존 조건을 쓴다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR3.1의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR3.1
  - id: BR3.2
    statement: "변형 계획은 한 요소만 바꾼다. 기본 SAME_CONTEXT는 화제·장소·등장인물 중 하나이며 같은 씨앗·관계·자리 3회 노출부터 NEAR로 관계 또는 자리 하나를 바꾼다. FAR는 사용하지 않는다. 최근 노출과 겹치지 않는 선택을 우선하며 선택 순서를 결정적으로 정한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR3.2의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR3.2
  - id: BR3.3
    statement: "LOW·recoverable=true를 유지하고 관계·자리는 기술명세서 §6.4의 아동용 허용 값만 사용한다. 실제 장면 보존 판정과 노출 자료 연결은 U10 책임이며 U15는 입력 계약과 규칙을 제공한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR3.3의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR3.3
  - id: BR4.1
    statement: "자유대화의 NONE 후보에 대한 생성·검사 서비스를 제공한다. 활동당 생성 상한 2개, 후보별 중복 실행 방지, 연습 활동 미생성을 보장한다. 결과 작업에서 호출하고 후보에 반환된 skillRef를 연결하는 작업은 U14가 맡는다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR4.1의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR4.1
  - id: BR4.2
    statement: "CoreLunaClient의 SEED_GENERATION과 계약 스키마를 사용한다. 전사 인용을 제외한 최소 구조화 입력만 보내며 원문 비교용 자료는 서버 검사에만 사용한다. 스키마 확장과 프롬프트 판을 함께 관리한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR4.2의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR4.2
  - id: BR4.3
    statement: "허용 값, 개인정보·고유명사·날짜, 활동 전사·배경 정보와 연속 5글자 이상 겹침, 금지 표현, 중복, 불변 문장 한 문장·60자 이내를 검사한다. 4/5글자와 60/61자 경계를 시험한다. 정규화와 글자 단위는 설계에서 명시한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR4.3의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR4.3
  - id: BR4.4
    statement: "duplicate_of는 해당 아동의 조회된 목록에 있는 ID만 허용한다. 유효한 중복은 새 노드 없이 기존 ID를 반환하고 none_reason은 NONE으로 남긴다. 목록 조회 실패 시 중복 확인이 불가능하므로 생성하지 않는다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR4.4의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR4.4
  - id: BR4.5
    statement: "내용 검사 실패는 최대 1회 재생성한다. U9 기술·스키마 재시도와 같은 요청 예산을 공유해 재시도가 곱해지지 않게 한다. 시한 만료·키 없음·최종 실패는 NONE을 반환하며 result_status를 변경하지 않는다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR4.5의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR4.5
  - id: BR4.6
    statement: "생성 씨앗의 출처·검사 결과·모델·프롬프트 판은 PostgreSQL에 저장한다. Neo4j 쓰기에 실패하면 성공한 generated_seed로 남기지 않는다. 부분 실패·중복 요청·늦은 응답을 복구 가능하게 설계한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR4.6의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR4.6
  - id: BR4.7
    statement: "씨앗 생성만 독립적으로 끌 수 있어야 한다. 이 설정이 꺼지면 그래프 조회는 유지하되 외부 생성 호출과 생성 씨앗 쓰기는 발생하지 않는다. 설정 키·기본값은 기술명세서 §13과 대조하여 설계에 명시한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR4.7의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR4.7
  - id: BR5.1
    statement: "그래프에는 아동 범위 식별자와 검증된 씨앗 구조 필드만 저장한다. 전사·보호자 입력 원문, 세션·후보 출처는 저장하지 않는다. 생성 씨앗은 다른 아동 및 공통 기본 씨앗으로 전파하지 않는다."
    category: authorization
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR5.1의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "삭제 실패는 재시도로 전달하고 완료로 표시하지 않는다."
    source: FR5.1
  - id: BR5.2
    statement: "아동·계정 삭제는 해당 아동 그래프와 생성 씨앗을 삭제하고 공통 기본 씨앗·어휘는 보존한다. 기록 삭제는 그 기록에서 만들어진 생성 씨앗만 삭제한다(기술명세서 §6.5). 다른 기록·아동의 데이터는 보존한다."
    category: authorization
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR5.2의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "삭제 실패는 재시도로 전달하고 완료로 표시하지 않는다."
    source: FR5.2
  - id: BR5.3
    statement: "삭제를 기존 DeletionParticipant 체계에 연결한다. 삭제가 실패하면 RETRY/기존 실패 재시도로 전달하며 빈 조회 결과로 삼키지 않는다. 반복 삭제는 멱등이어야 한다."
    category: authorization
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR5.3의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "삭제 실패는 재시도로 전달하고 완료로 표시하지 않는다."
    source: FR5.3
  - id: BR5.4
    statement: "생성과 삭제가 경쟁하면 삭제가 완료된 뒤 생성 결과가 되살아나지 않아야 한다. 삭제된 씨앗을 참조하는 목표는 NONE으로 처리할 수 있도록 U14와 경계를 명시한다."
    category: authorization
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR5.4의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "삭제 실패는 재시도로 전달하고 완료로 표시하지 않는다."
    source: FR5.4
  - id: BR5.5
    statement: "그래프가 꺼져 있고 해당 환경에서 아동 그래프를 만든 적이 없음을 확인할 수 있으면 삭제 참여자는 NOT_APPLICABLE로 종료한다. 이전 생성 이력이 있거나 잔존 여부를 확인할 수 없으면 완료로 표시하지 않고 RETRY로 남긴다(원 AC5.2.3). 생성 이력 보존 위치와 확인 방식은 설계에서 정하며, 꺼짐 설정만으로 기존 데이터가 없다고 추정하지 않는다."
    category: authorization
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR5.5의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "삭제 실패는 재시도로 전달하고 완료로 표시하지 않는다."
    source: FR5.5
  - id: BR6.1
    statement: "그래프 꺼짐·조회 장애·graph-budget 2초 초과·skillRef NONE은 빈 목록/맥락/계획을 반환한다. 이 응답을 개인화 완료로 표시하지 않는다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR6.1의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR6.1
  - id: BR6.2
    statement: "기동에 실제 AI 키를 요구하지 않는다. Neo4j가 끊겨도 기존 core 기본 경로는 사용 가능해야 하며 복구 후 새 조회는 회복한다. 타임아웃 후 늦은 작업이 결과를 덮어쓰지 않아야 한다."
    category: constraint
    applies_to: U15 KnowledgeGraph
    trigger: 해당 조회·초기화·생성·삭제 요청
    logic: "IF FR6.2의 조건이 성립하면 THEN 위 statement를 적용한다. 세부 처리 순서와 실패 복구는 functional-spec.md를 따른다."
    violation: "조회 실패는 빈 결과, 생성 실패는 NONE, 적재 실패는 완료 판 미기록으로 구분한다. 권한 오류는 기존 오류 계약을 따른다."
    source: FR6.2
```

## 규칙 요약

| 그룹 | 규칙 | 책임 |
|---|---|---|
| 아동 경계 | BR1.1~BR1.5 | 초기화·호환·격리·동의 |
| 기본 자료 | BR2.1~BR2.5 | 14개 적재·버전·활성 목록 |
| 변형 | BR3.1~BR3.3 | 구조 보존·한 요소·아동 맥락 |
| 생성 | BR4.1~BR4.7 | 한도·개인정보·재시도·중복·중지 |
| 삭제 | BR5.1~BR5.5 | 출처 분리·멱등·늦은 쓰기 차단 |
| 장애 | BR6.1~BR6.2 | 빈 결과·키 없는 기동·복구 |

