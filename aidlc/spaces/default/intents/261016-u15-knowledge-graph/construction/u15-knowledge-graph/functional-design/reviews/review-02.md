## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261016-u15-knowledge-graph/construction/u15-knowledge-graph/functional-design/functional-spec.md > 생성 상태와 처리 순서 6 및 entities.md > GenerationOperation | 유효 duplicate_of는 성공 원장을 만들지 않고 기존 ID를 반환하지만 그 결과를 저장할 작업 상태가 없다. COMMITTED는 성공 원장과 결합한다고 정의되고 NONE은 재전달 시 NONE을 반환하므로 같은 후보의 두 번째 요청이 첫 번째와 같은 기존 ID를 반환할 경로가 정의되지 않는다. 예약 한도를 중복 결과가 계속 차지하는지도 불명확하다. | 중복 성공의 저장 상태와 반환 참조를 정의하고 재전달·대상 삭제/비활성화 시 반환 및 활동당 두 자리 계산에서의 취급을 명시한다. 새 씨앗 성공 원장을 만들지 않는 조건을 유지하며 중복 응답 후 재호출 시험을 추가한다. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261016-u15-knowledge-graph/construction/u15-knowledge-graph/functional-design/functional-spec.md > 생성 상태와 처리 순서 8~9 및 삭제·동시성·복구 | PROJECTING을 먼저 내구적으로 확정하고 같은 보호자 직렬화 안에서 외부 쓰기와 성공 원장을 확정한다고 하지만 실제 GuardianWriteLock은 트랜잭션 한정 PostgreSQL 행 잠금이다. 먼저 커밋하면 잠금이 풀리고, 커밋하지 않으면 실패 뒤 복구할 PROJECTING이 내구적이지 않다. 그래프 성공 직후 프로세스 종료/DB 실패 시 남는 PROJECTING의 회수 주체와 조건도 없으며 만료 처리는 RESERVED만 정해져 있다. 이 틈에서 삭제와 지연 쓰기를 어떻게 배제하는지 구현자가 추측해야 한다. | 예약·PROJECTING 사전 커밋·외부 쓰기·성공 확정·삭제 각각의 트랜잭션 경계와 잠금 재획득 후 재검사 조건을 정한다. PROJECTING 복구용 구조 필드를 엔티티에 추가하고, 중단된 쓰기의 종료 확인/회수 기준과 중복 복구 실행 방식을 정의한다. 그래프 커밋 직후 프로세스 중단 및 두 인스턴스의 삭제/복구 경쟁 시험을 명시한다. | Resolved (reviewer) |
| R-03 | Major | aidlc/spaces/default/intents/261016-u15-knowledge-graph/construction/u15-knowledge-graph/functional-design/functional-spec.md > 생성 상태와 처리 순서 4 및 contracts/knowledge-graph.md > 구성과 호환 | 출력 계약을 코드 생성에서 확장한다고 미루었으며 새 구조 필드의 정확한 JSON 이름·형식, 새 생성/중복/NONE 분기의 필수·null·상호배타 규칙이 없다. 현재 ai-seed-output.schema.json은 skillName과 invariantSentence만 허용하고 additionalProperties=false이므로 명시한 duplicate_of와 none_reason 응답을 거절한다. 기존 최소 필드를 유지한다는 문장만으로 중복/NONE에서 두 문자열을 어떻게 채울지 결정할 수 없다. | 이번 설계에 세 분기의 구체적 출력 JSON 모양과 유효/무효 예시를 정의한다. 기존 최소 응답의 호환 정책, 새 생성의 전체 필수 구조, 중복/NONE의 nullable 필드 및 분기 충돌 처리, seed-generation.v2와 스키마 배포의 동시 변경 조건을 명시한다. | Resolved (reviewer) |

> R-01 Reviewer note: REUSED/reusedSeedId의 내구적 결과, 동일 후보 재호출, 대상 비활성·삭제 시 NONE 및 생성 상한 제외가 정의됐다.

> R-02 Reviewer note: T1/T2/T3에서 보호자 잠금 해제와 재획득, 외부 쓰기 전 핸들·구조의 커밋, T3 재검사, 중단 후 원격 종료 확인과 보상 삭제가 명시됐다. 엔티티에도 핸들과 복구 구조가 추가됐다.

> R-03 Reviewer note: GENERATED/DUPLICATE/NONE 및 레거시 호환 분기의 정확한 속성·필수 필드·추가 필드 금지·길이·열거 값이 스키마와 계약에 정의됐다. 레거시 출력의 U15 성공 거절과 프롬프트 동시 배포도 명시됐다.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Python JSON 구조 대조 | PASS | 네 분기 모두 추가 필드 금지이며 각 분기의 모든 속성이 필수다. 구분자·중복/NONE 필드·GENERATED 60자 제한을 확인했다. |
| Python jsonschema 실행 시도 | 실행 불가: 모듈 미설치 | 전체 JSON Schema 검증을 실행했다고 주장하지 않는다. 기존 Java 스키마 검증 및 공급자 요청 변환 시험은 구현 검증에서 필요하다. |
| 상태·트랜잭션 문서 대조 | PASS | 세 기존 지적의 상태와 처리 경계가 산출물 사이에서 일치한다. 실제 Neo4j 종료 확인 및 장애 복구 동작은 구현·통합시험에서 확인할 대상이다. |

### Summary

기존 세 지적이 설계 수준에서 보완됐으며 새 지적은 없다. 이 판정은 기능 설계의 구현 가능성에 대한 것으로 실제 저장소 장애·동시성·공급자 동작의 검증 완료를 뜻하지 않는다.
