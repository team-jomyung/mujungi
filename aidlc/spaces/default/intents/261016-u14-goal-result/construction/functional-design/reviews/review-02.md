## Review

**Reviewer:** aidlc-architecture-reviewer-agent
**Verdict:** READY
**Date:** 2026-10-16T14:57:27Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261016-u14-goal-result/construction/functional-design/functional-spec.md > D1 및 D2.7 | D2.7은 보호자가 관측한 후보 버전을 담는다고 정하지만 그 버전을 선택 요청까지 운반하는 방법이 없다. 실제 goal-selection-add-request.schema.json은 recommendationId만 요구하고 기존 PracticeGoalRepository는 그 ID로 추천을 다시 읽는다. 안정된 후보 ID에 currentVersion만 갱신하면 화면에서 NONE 버전을 본 뒤 씨앗 연결이 완료되고 담기를 누른 경우 다른 버전이 담긴다. | 새 후보의 recommendationId를 불변 CandidateVersion을 가리키는 공개 핸들로 정하거나 후보 버전을 선택 요청과 파서에 추가하는 방법 중 하나를 명시한다. 예전 화면의 핸들은 계속 관측한 버전을 고정하고, 중복 담기 판정은 안정된 후보 정체성으로 하며, 이전 버전 표시→새 버전 게시→이전 화면 담기 경합 시험을 둔다. | Resolved (reviewer) |

> R-01 Reviewer note: D1의 공개 recommendationId가 불변 후보 버전을 가리키도록 확정되어 기존 선택 요청 한 필드로 관측한 버전을 고정한다. 안정된 후보 정체성에 따른 활성 선택 중복 판정, 새 버전의 selected 투영, 삭제 후 새 버전 담기와 이전 선택 복원 충돌, 이전 화면 선택 경합 시험을 함께 명시했고 entities에도 불변 핸들과 활성 후보별 유일 조건이 반영됐다.

### Validation Tool Results

| Check | Result | Interpretation |
|---|---|---|
| 선택 요청과 불변 버전 연결 대조 | PASS | goal-selection-add-request.schema.json의 recommendationId UUID 하나를 그대로 사용하면서 CandidateVersion 불변 핸들로 정확한 버전을 찾을 수 있다. |
| 버전 게시·선택·복원 경합 설계 대조 | PASS | 새 버전 게시가 과거 핸들 또는 기존 활성 선택의 스냅샷을 바꾸지 않으며 안정된 후보 정체성으로 중복을 막는다. |
| 기존 설계 경계 재확인 | PASS | 수정된 D1과 entities는 D2의 씨앗 후처리, C-DS10, 레거시 선택 및 공개 DTO 경계를 유지한다. 실행 소스 변경이 없는 설계 검토이며 런타임 회귀 시험 성공을 주장하지 않는다. |

### Summary

선택 요청에서 관측 버전을 보존하는 방법이 구체화되어 지적한 경합 공백이 해소됐다. 현재 검토 범위에서 추가 우려는 없으며 구현과 계약·경합 시험으로 진행할 수 있다.
