## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-14T04:24:55Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Critical | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java > schedule() 74~80행, prepare() 180~198행, nextGoal() 213~215행 | 예약 작업은 현재 목표의 generation만 비교하지만 각 목표의 세대는 다시 0부터 시작한다. 이전 목표의 타이머가 남아 있으면 다음 목표의 같은 세대를 유효하다고 판단한다. 실제 컴파일된 부품으로 첫 목표 기회를 t=0, 두 번째 목표 첫 기회를 t=2에 연 뒤 t=20으로 진행했을 때 두 번째 목표가 18초 만에 HELP_OFFER로 이동하고 무응답을 저장했다. 정상 만료는 t=22다. prepare()의 비동기 완료도 목표·시도 ID 없이 같은 세대와 현재 PREPARING만 검사하므로 이전 목표의 늦은 생성 결과를 다음 목표에 적용할 수 있다. 이는 승인된 독립 구현의 기회 귀속·20초 시한을 깨뜨리며 실제 U4b 통합 보류와 별개의 결함이다. | 모든 타이머와 생성 완료를 예약 당시 sessionGoalId·attemptId 및 단계/기회 식별자·generation에 묶고 현재 소유 작업과 일치할 때만 반영한다. 전환 시 이전 예약을 취소하더라도 이미 전달된 콜백의 소유권 검사는 유지한다. 두 목표 이상을 빠르게 완료하는 Clock 시험에서 이전 20초 타이머가 다음 기회를 닫지 않는지 검증하고, 다음 목표 준비 실패 후 그다음 목표 준비 중 도착한 이전 생성 결과·만료도 무시하는 회귀 시험을 추가한다. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261013-u5b-practice/construction/code-generation/traceability.json > upstream_ids 및 coverage | 요구사항 본문에 남아 있는 NFR5·NFR7·NFR8이 추적표에서 모두 빠져 있다. 센서는 missing_from_upstream_ids에 이 세 ID를 보고한다. 실제 성능 미검증과 리포트 범위 제외를 요구사항이 설명하고 있지만 코드 추적표에는 그 구분이 남지 않아 후속 작업에서 누락과 의도된 보류를 구별하기 어렵다. | NFR5·NFR7은 실제 공급자·부하 검증이 남은 Deferred 근거를, NFR8은 이번 U5b 범위에서 제외된 N/A 근거를 명시해 추적한다. 이 수정으로 후속 통합을 완료 처리하거나 이번 독립 구현 범위를 확대하지 않는다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| sensor-required-sections — code-generation-plan.md | PASS, H2 12개 | 계획의 필수 구조를 충족한다. |
| sensor-required-sections — unit-test-instructions.md | PASS, H2 4개 | 시험 지침의 필수 구조를 충족한다. |
| sensor-required-sections — code-summary.md | PASS, H2 6개 | 구현 요약의 필수 구조를 충족한다. |
| sensor-traceability — code-generation/traceability.json | FAIL, missing_from_upstream_ids: NFR5, NFR7, NFR8; gaps·orphans·invalid_targets 없음 | 위 추적 누락을 확인했다. Deferred 자체를 실제 구현 실패로 해석하지 않았다. |
| JShell 21 — 컴파일된 PracticeProtocol, MutableClock, ManualTimerDriver 및 메모리 저장 포트 대역 | 재현: t=2에서 goal=2, FIRST_RESPONSE, generation=2; t=20에서 goal=2, HELP_OFFER | 목표1의 첫 반응과 새 상황 반응을 각각 t=1·t=2에 정상 확정한 뒤 목표2 기회를 열었다. t=20의 이전 목표 타이머가 목표2를 조기 종료했다. 소스와 시험 파일은 수정하지 않았다. |
| 승인 계획·소스·실행 기록 대조 | 독립 부품·계약 대역 범위와 실제 통합 Deferred 구분 유지 | 보고된 U5b 99건 및 커버리지 수치는 실행 기록의 증거로 확인했다. 이번 검토에서 전체 Gradle/Vitest 묶음을 재실행한 결과로 주장하지 않는다. 기존 단계 시험은 다중 목표 간 잔존 타이머 충돌을 다루지 않는다. |

### 재현 조건

JShell의 클래스 경로는 backend/realtime-activity/build/classes/java/main, backend/contract-model/build/classes/java/main, backend/test-support/build/classes/java/main이었다. 실제 PracticeProtocol과 기존 가변 시계를 사용하고 저장 포트만 메모리 대역으로 제공했다. 목표 두 개의 시작 세대는 각각 0이며 첫 목표를 다음과 같이 진행했다.

1. t=0: ORIGINAL 재생 완료로 첫 반응 기회를 연다.
2. t=1: 첫 반응을 functional로 최종 저장하고 judged()를 전달한다. NEW_SCENARIO 생성·재생을 완료한다.
3. t=2: 새 상황 반응을 functional로 최종 저장하고 judged()를 전달한다. 다음 목표 ORIGINAL 생성·재생을 완료한다.
4. t=20: runDue에 해당하는 타이머 실행 후 두 번째 목표가 HELP_OFFER가 된다. 두 번째 기회의 자체 20초는 아직 만료되지 않았다.

수정 후에는 t=20에서 두 번째 목표의 FIRST_RESPONSE와 열린 기회가 유지되고 t=22에만 해당 기회가 만료되어야 한다. 공급자 결과나 외부 U4b 우선순위를 새로 정할 필요 없이 독립 부품에서 재현·수정 가능한 조건이다.

### Summary

독립 구현과 후속 실제 통합의 완료 경계는 유지되어 있다. 다만 목표를 넘나드는 타이머·비동기 작업의 소유권 검사가 불완전하여 정상 연습에서도 반응 기회를 조기 종료할 수 있으므로 수정과 다중 목표 경계 회귀 시험이 필요하다.
