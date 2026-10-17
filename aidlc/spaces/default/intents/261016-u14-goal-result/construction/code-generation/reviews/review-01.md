## Review

**Reviewer:** aidlc-architecture-reviewer-agent
**Verdict:** NOT-READY
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java:109; backend/core-api/src/main/java/kr/mujoong/core/result/ResultReconciler.java:45 | 새 결과의 후보가 빈 목록이면 두 경로 모두 deliver를 생략하고 등록 완료를 표시한다. 이전 후보를 무효화하는 유일한 invalidatePrevious는 deliver 안에 있으므로, 후보가 있던 결과를 후보 없는 결과로 재생성하면 이전 후보가 ACTIVE로 남는다. 결과 조회는 source result 필터로 숨기지만 GeneratedGoalService.add:28~31은 현재 결과를 검사하지 않아 이전 화면의 핸들로 담기가 성공한다. functional-spec D1은 출처 결과가 유효한 동안만 이전 핸들을 허용한다. | 빈 현대 후보 목록도 현재 결과 확인과 이전 후보 무효화를 수행하도록 즉시 전달과 재생 경로를 고친다. 후보 있는 결과→빈 결과 재생성 후 이전 핸들 담기 거절, 기존 선택 스냅샷 보존을 통합 시험한다. | New |
| R-02 | Major | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/GoalCandidateGateway.java:30~36; backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java:166 | 연습 결과의 publicScenes는 저장된 goalSelectionId UUID를 넣고, 새 gateway는 scenes를 그대로 보존하여 공급자에 전달한다. LunaInputSanitizer는 이 키를 제거하지 않으며 UUID 참조화도 하지 않는다(해당 클래스 설명과 FORBIDDEN_KEYS). CoreLunaClient 역시 같은 sanitizer만 적용하므로 실제 저장 식별자가 전송된다. functional-spec D2.1의 서버 내부 키와 공급자 payload 분리 및 기존 ResultTextGateway의 참조화 경계가 새 호출에서 빠졌다. | 후보 입력에서 필요 없는 저장 목표 식별자를 제거하거나 호출별 임시 참조로 치환한다. 실제 연습 scenes 입력을 포함한 공급자 요청 검증으로 저장 UUID가 모든 깊이에서 사라지는지 확인한다. | New |
| R-03 | Major | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/GoalCandidateGateway.java:32; backend/core-api/src/main/java/kr/mujoong/core/practicegoal/GoalCandidateValidator.java:28 | 근거 정본을 ResultData.childQuotes:16으로 만들지만 이 함수는 uncertain=true인 CHILD 발화도 포함한다. 따라서 유효한 다른 발화가 있어 결과 작업이 진행되는 기록에서, 후보가 불확실 발화만 정확히 인용해도 seq/quote 검사와 분류를 통과할 수 있다. functional-spec D2.3은 불확실 자료를 후보 근거에서 제외하도록 명시한다. 현재 단위 시험은 이 경계를 검증하지 않는다. | 현대 후보의 근거 허용 목록에서 uncertain 발화를 제외한다. 동일 기록에 유효 발화와 불확실 발화가 섞인 경우 불확실 발화만 근거인 후보는 제외되고 유효 발화 근거 후보는 유지되는 시험을 추가한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Differential source and contract inspection | Three concrete behavior gaps above | Passing existing suites do not refute these untested branches. |
| Trace target existence check | 58 rows; 0 missing targets | Targets resolve; this does not establish semantic coverage. |
| Five common DB/startup baseline test diffs | Exact table/history/index expectations updated to V9; privilege/checksum/rejection assertions retained; previous V8 rejection added | No assertion weakening found in the stated narrow baseline exception. |
| Existing verification evidence | Backend final rc0 and U14 147 passing cases; frontend 994 passing cases recorded | Broad regression evidence is retained. Compose and PR CI remain later Build and Test work and are not reported as completed. |
| Framework CLI availability | aidlc executable unavailable; direct TypeScript entry failed on Windows bun: module loading | Native sensor execution and engine timestamp could not be obtained in this reviewer environment. No timestamp was invented. |

### Summary

불변 버전 선택, 커밋 후 씨앗 연결, 원시 timing과 표시 칩 분리는 구현되어 있다. 그러나 오래된 후보 선택 허용, 공급자 입력 식별자 노출, 불확실 발화 근거 허용의 세 가지 Major 문제를 고친 뒤 다시 검토해야 한다.
