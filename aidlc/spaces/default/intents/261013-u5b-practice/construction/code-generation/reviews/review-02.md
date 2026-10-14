## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-14T04:34:00Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Critical | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java > schedule() 74~80행, prepare() 180~198행, nextGoal() 213~215행 | 예약 작업은 현재 목표의 generation만 비교하지만 각 목표의 세대는 다시 0부터 시작한다. 이전 목표의 타이머가 남아 있으면 다음 목표의 같은 세대를 유효하다고 판단한다. 실제 컴파일된 부품으로 첫 목표 기회를 t=0, 두 번째 목표 첫 기회를 t=2에 연 뒤 t=20으로 진행했을 때 두 번째 목표가 18초 만에 HELP_OFFER로 이동하고 무응답을 저장했다. 정상 만료는 t=22다. prepare()의 비동기 완료도 목표·시도 ID 없이 같은 세대와 현재 PREPARING만 검사하므로 이전 목표의 늦은 생성 결과를 다음 목표에 적용할 수 있다. 이는 승인된 독립 구현의 기회 귀속·20초 시한을 깨뜨리며 실제 U4b 통합 보류와 별개의 결함이다. | 모든 타이머와 생성 완료를 예약 당시 sessionGoalId·attemptId 및 단계/기회 식별자·generation에 묶고 현재 소유 작업과 일치할 때만 반영한다. 전환 시 이전 예약을 취소하더라도 이미 전달된 콜백의 소유권 검사는 유지한다. 두 목표 이상을 빠르게 완료하는 Clock 시험에서 이전 20초 타이머가 다음 기회를 닫지 않는지 검증하고, 다음 목표 준비 실패 후 그다음 목표 준비 중 도착한 이전 생성 결과·만료도 무시하는 회귀 시험을 추가한다. | Resolved (reviewer) |
| R-02 | Minor | aidlc/spaces/default/intents/261013-u5b-practice/construction/code-generation/traceability.json > upstream_ids 및 coverage | 요구사항 본문에 남아 있는 NFR5·NFR7·NFR8이 추적표에서 모두 빠져 있다. 센서는 missing_from_upstream_ids에 이 세 ID를 보고한다. 실제 성능 미검증과 리포트 범위 제외를 요구사항이 설명하고 있지만 코드 추적표에는 그 구분이 남지 않아 후속 작업에서 누락과 의도된 보류를 구별하기 어렵다. | NFR5·NFR7은 실제 공급자·부하 검증이 남은 Deferred 근거를, NFR8은 이번 U5b 범위에서 제외된 N/A 근거를 명시해 추적한다. 이 수정으로 후속 통합을 완료 처리하거나 이번 독립 구현 범위를 확대하지 않는다. | Resolved (reviewer) |

> R-01 Reviewer note: PracticeProtocol.java의 WorkOwner(75~82행)가 sessionGoalId·attemptId·stage·generation·attemptState를 함께 비교한다. schedule()과 prepare() 완료 처리 모두 예약 당시 소유권을 검사하므로 목표별 세대 재사용만으로 이전 작업이 통과하지 않는다. PracticeProtocolTest의 신규 세 사례는 t=20의 이전 목표 만료와 t=22의 현재 기회 만료, 실패한 목표의 잔존 준비 타이머, 시간 초과한 목표의 늦은 생성 결과를 각각 검증한다.

> R-02 Reviewer note: traceability.json의 upstream_ids와 coverage에 NFR5·NFR7·NFR8이 추가됐다. NFR5·NFR7은 실제 공급자·부하 검증이 남은 Deferred, NFR8은 U6 리포트 소유 범위의 N/A로 구분한다. 추적 센서를 재실행하여 누락과 잘못된 대상이 없음을 확인했다.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| sensor-required-sections — code-generation-plan.md | PASS, H2 12개 | 승인 계획 구조가 유지된다. |
| sensor-required-sections — unit-test-instructions.md | PASS, H2 4개 | 시험 지침 구조가 유지된다. |
| sensor-required-sections — code-summary.md | PASS, H2 6개 | 구현 결과와 미완료 통합 범위가 구분된다. |
| sensor-traceability — code-generation/traceability.json | PASS, findings_count=0 | 누락 ID·잘못된 항목·잘못된 대상·고아 항목이 없다. |
| 소유권 검사 및 Clock 회귀 소스 대조 | PASS | 목표와 시도 ID가 바뀐 타이머·콜백은 같은 세대여도 거절한다. 이미 발급된 시도의 PREPARING 완료 콜백도 다시 적용되지 않는다. 세 신규 시험이 앞선 재현 조건과 생성 경계 변형을 직접 검증한다. |
| 변경 후 실행 증거 대조 | Realtime U5b 74건, 546/553줄=98.734%; Realtime U1·U3 825건 통과 | 호출자가 확인한 실행 결과와 code-summary.md·docs/units/u5b/report.md가 일치한다. Core 12건·프런트 16건의 기존 증거와 합쳐 U5b 102건이며 줄 커버리지 80% 기준을 유지한다. 이번 재검토에서 Gradle 전체 묶음을 별도로 재실행한 결과로 주장하지 않는다. |

### Summary

앞선 두 지적은 모두 수정됐으며 승인된 독립 부품·계약 대역 시험 범위에서 새로운 차단 결함을 확인하지 못했다. 이 판정은 실제 U5b 통합 완료를 뜻하지 않는다. I1~I5 합의, U4b V3 병합·최신 소스/CI 확인 및 같은 Intent의 별도 승인 통합 계획은 계속 필요하다.
