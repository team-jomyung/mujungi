## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261016-u15-knowledge-graph/construction/u15-knowledge-graph/functional-design/functional-spec.md > 생성 상태와 처리 순서 6 및 entities.md > GenerationOperation | 유효 duplicate_of는 성공 원장을 만들지 않고 기존 ID를 반환하지만 그 결과를 저장할 작업 상태가 없다. COMMITTED는 성공 원장과 결합한다고 정의되고 NONE은 재전달 시 NONE을 반환하므로 같은 후보의 두 번째 요청이 첫 번째와 같은 기존 ID를 반환할 경로가 정의되지 않는다. 예약 한도를 중복 결과가 계속 차지하는지도 불명확하다. | 중복 성공의 저장 상태와 반환 참조를 정의하고 재전달·대상 삭제/비활성화 시 반환 및 활동당 두 자리 계산에서의 취급을 명시한다. 새 씨앗 성공 원장을 만들지 않는 조건을 유지하며 중복 응답 후 재호출 시험을 추가한다. | New |
| R-02 | Major | aidlc/spaces/default/intents/261016-u15-knowledge-graph/construction/u15-knowledge-graph/functional-design/functional-spec.md > 생성 상태와 처리 순서 8~9 및 삭제·동시성·복구 | PROJECTING을 먼저 내구적으로 확정하고 같은 보호자 직렬화 안에서 외부 쓰기와 성공 원장을 확정한다고 하지만 실제 GuardianWriteLock은 트랜잭션 한정 PostgreSQL 행 잠금이다. 먼저 커밋하면 잠금이 풀리고, 커밋하지 않으면 실패 뒤 복구할 PROJECTING이 내구적이지 않다. 그래프 성공 직후 프로세스 종료/DB 실패 시 남는 PROJECTING의 회수 주체와 조건도 없으며 만료 처리는 RESERVED만 정해져 있다. 이 틈에서 삭제와 지연 쓰기를 어떻게 배제하는지 구현자가 추측해야 한다. | 예약·PROJECTING 사전 커밋·외부 쓰기·성공 확정·삭제 각각의 트랜잭션 경계와 잠금 재획득 후 재검사 조건을 정한다. PROJECTING 복구용 구조 필드를 엔티티에 추가하고, 중단된 쓰기의 종료 확인/회수 기준과 중복 복구 실행 방식을 정의한다. 그래프 커밋 직후 프로세스 중단 및 두 인스턴스의 삭제/복구 경쟁 시험을 명시한다. | New |
| R-03 | Major | aidlc/spaces/default/intents/261016-u15-knowledge-graph/construction/u15-knowledge-graph/functional-design/functional-spec.md > 생성 상태와 처리 순서 4 및 contracts/knowledge-graph.md > 구성과 호환 | 출력 계약을 코드 생성에서 확장한다고 미루었으며 새 구조 필드의 정확한 JSON 이름·형식, 새 생성/중복/NONE 분기의 필수·null·상호배타 규칙이 없다. 현재 ai-seed-output.schema.json은 skillName과 invariantSentence만 허용하고 additionalProperties=false이므로 명시한 duplicate_of와 none_reason 응답을 거절한다. 기존 최소 필드를 유지한다는 문장만으로 중복/NONE에서 두 문자열을 어떻게 채울지 결정할 수 없다. | 이번 설계에 세 분기의 구체적 출력 JSON 모양과 유효/무효 예시를 정의한다. 기존 최소 응답의 호환 정책, 새 생성의 전체 필수 구조, 중복/NONE의 nullable 필드 및 분기 충돌 처리, seed-generation.v2와 스키마 배포의 동시 변경 조건을 명시한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Python 읽기 전용 추적 대조 | PASS | 현재 구체 FR 27개의 대상 BR가 모두 존재한다. 이는 규칙 구현 가능성까지 입증하지 않는다. |
| 현재 출력 JSON 스키마 확인 | 두 문자열 속성만 허용, additionalProperties=false | 새 분기의 선 형식 확정이 필요하다. |
| GuardianWriteLock·DeletionCommander·DeletionParticipant 대조 | 보호자 잠금은 트랜잭션 한정, 삭제 지휘는 참가자를 다시 호출 | 내구적 복구 기록과 트랜잭션 간 직렬화, 반복 삭제를 구체적으로 설계해야 한다. |

### Summary

아동 경계·동의·삭제 실패와 빈 조회의 구분 및 요구사항 연결은 포함돼 있다. 다만 중복 결과의 멱등 처리, 두 저장소의 커밋/복구 경계, 생성 출력의 구체적 계약을 확정해야 추측 없이 구현할 수 있다.
