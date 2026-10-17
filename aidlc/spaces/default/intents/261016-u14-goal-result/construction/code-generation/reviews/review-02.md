## Review

**Reviewer:** aidlc-architecture-reviewer-agent
**Verdict:** READY
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java:109; backend/core-api/src/main/java/kr/mujoong/core/result/ResultReconciler.java:45 | 새 결과의 후보가 빈 목록이면 두 경로 모두 deliver를 생략하고 등록 완료를 표시한다. 이전 후보를 무효화하는 유일한 invalidatePrevious는 deliver 안에 있으므로, 후보가 있던 결과를 후보 없는 결과로 재생성하면 이전 후보가 ACTIVE로 남는다. 결과 조회는 source result 필터로 숨기지만 GeneratedGoalService.add:28~31은 현재 결과를 검사하지 않아 이전 화면의 핸들로 담기가 성공한다. functional-spec D1은 출처 결과가 유효한 동안만 이전 핸들을 허용한다. | 빈 현대 후보 목록도 현재 결과 확인과 이전 후보 무효화를 수행하도록 즉시 전달과 재생 경로를 고친다. 후보 있는 결과→빈 결과 재생성 후 이전 핸들 담기 거절, 기존 선택 스냅샷 보존을 통합 시험한다. | Resolved (reviewer) |
| R-02 | Major | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/GoalCandidateGateway.java:30~36; backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java:166 | 연습 결과의 publicScenes는 저장된 goalSelectionId UUID를 넣고, 새 gateway는 scenes를 그대로 보존하여 공급자에 전달한다. LunaInputSanitizer는 이 키를 제거하지 않으며 UUID 참조화도 하지 않는다(해당 클래스 설명과 FORBIDDEN_KEYS). CoreLunaClient 역시 같은 sanitizer만 적용하므로 실제 저장 식별자가 전송된다. functional-spec D2.1의 서버 내부 키와 공급자 payload 분리 및 기존 ResultTextGateway의 참조화 경계가 새 호출에서 빠졌다. | 후보 입력에서 필요 없는 저장 목표 식별자를 제거하거나 호출별 임시 참조로 치환한다. 실제 연습 scenes 입력을 포함한 공급자 요청 검증으로 저장 UUID가 모든 깊이에서 사라지는지 확인한다. | Resolved (reviewer) |
| R-03 | Major | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/GoalCandidateGateway.java:32; backend/core-api/src/main/java/kr/mujoong/core/practicegoal/GoalCandidateValidator.java:28 | 근거 정본을 ResultData.childQuotes:16으로 만들지만 이 함수는 uncertain=true인 CHILD 발화도 포함한다. 따라서 유효한 다른 발화가 있어 결과 작업이 진행되는 기록에서, 후보가 불확실 발화만 정확히 인용해도 seq/quote 검사와 분류를 통과할 수 있다. functional-spec D2.3은 불확실 자료를 후보 근거에서 제외하도록 명시한다. 현재 단위 시험은 이 경계를 검증하지 않는다. | 현대 후보의 근거 허용 목록에서 uncertain 발화를 제외한다. 동일 기록에 유효 발화와 불확실 발화가 섞인 경우 불확실 발화만 근거인 후보는 제외되고 유효 발화 근거 후보는 유지되는 시험을 추가한다. | Resolved (reviewer) |

> R-01 Reviewer note: SessionResultHandler와 ResultReconciler는 빈 현대 후보도 deliver한다. CandidateSeedDelivery는 현재 DONE 결과·활성 보호자/아동·원본 기록을 확인하고 이전 후보를 무효화하며, 빈 정리는 동의 철회 뒤에도 AI 호출 없이 실행한다. GeneratedGoalService.add는 보호자 잠금 안에서 sourceIsCurrent를 추가 확인해 callback 전 구간도 막는다. U14EmptyCandidateReplayIT, CandidateVersionSelectionIT, U14ResultPipelineIT의 새 시험이 이전 핸들 거절·기존 스냅샷 보존·늦은 재생 거절·철회 후 정리를 확인한다.

> R-02 Reviewer note: GoalCandidateGateway는 정리된 입력의 모든 객체/배열 깊이와 문자열 내 UUID를 호출별 임시 참조로 치환한다. 같은 호출에서 관계를 유지하고 호출 간 참조를 바꾼다. 요청 capture 시험은 실제 연습 장면 모양 및 중첩 식별자의 원 UUID 부재를 확인한다. U15 KnowledgeSeed가 허용하는 seed:S숫자 / seed:X숫자 / seed:G네자리이상은 UUID 형식이 아니므로 이 치환에 걸리지 않으며 seed:S1 후보의 허용 목록 검증도 시험한다.

> R-03 Reviewer note: GoalCandidateGateway는 uncertain=true 전사를 공급자 입력과 childQuotes 근거 정본을 만들기 전에 제외한다. 혼합 전사 시험은 불확실 발화 근거 후보를 버리면서 확실한 발화 근거 후보를 유지하고, 불확실 발화만 있는 시험은 후보를 남기지 않는다. legacy ResultData의 의미는 변경하지 않았다.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Repair differential c096283→d5d4d4f and prior source/contract inspection | All three reported gaps repaired; no new concrete blocker found | Checked empty/nonempty delivery, consent/deletion/current-result guards, callback gap, immutable selection snapshots, nested UUID handling and seed catalog preservation. |
| Required-sections / traceability native sensor invocation | Attempted both; unavailable under Node because the shared framework imports bun: modules, and Bun is absent | This is a reviewer runtime limitation, not a product failure. No successful native sensor result is claimed. |
| Independent read-only artifact validation | Plan 7 H2 headings; test instructions 6; summary 4; traceability 58 rows, zero missing targets, zero invalid statuses or uncovered declared IDs | Required document shape and declared target existence checks pass. |
| Differential production-path coverage check | 26 changed production Java paths; 26 scope entries; exact set match | Repair did not omit a production Java path from the U14 scope. |
| Saved repair verification | Related regression 214 passing; U14 161 tests / 19 classes, zero failures/errors/skips; 837/926 lines = 90.39%, rc0 | New boundary tests and relevant regression evidence cover the repaired code with the existing 80% floor retained. No duplicate execution counts are summed. |
| Baseline and later-stage evidence separation | Full backend 3135 with 3 LOCAL skips and frontend 994 / 98.55% explicitly remain c096283 evidence; repair report identifies full backend/compose/PR CI as pending | The completed Code Generation artifacts do not claim later Build and Test checks succeeded on the repair commit. |

### Summary

독립 검토에서 지적한 세 경계가 구현과 회귀 시험으로 보완되었으며, 보완 코드에서 새로 확인된 차단 문제는 없다. Code Generation 검토는 READY이며, 새 커밋의 전체 backend·compose·PR CI 검증은 기록된 다음 Build and Test 단계에서 수행한다.
