## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-14T05:05:29Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Critical | backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/PracticeProtocol.java > schedule() 74~80행, prepare() 180~198행, nextGoal() 213~215행 | 예약 작업은 현재 목표의 generation만 비교하지만 각 목표의 세대는 다시 0부터 시작한다. 이전 목표의 타이머가 남아 있으면 다음 목표의 같은 세대를 유효하다고 판단한다. 실제 컴파일된 부품으로 첫 목표 기회를 t=0, 두 번째 목표 첫 기회를 t=2에 연 뒤 t=20으로 진행했을 때 두 번째 목표가 18초 만에 HELP_OFFER로 이동하고 무응답을 저장했다. 정상 만료는 t=22다. prepare()의 비동기 완료도 목표·시도 ID 없이 같은 세대와 현재 PREPARING만 검사하므로 이전 목표의 늦은 생성 결과를 다음 목표에 적용할 수 있다. 이는 승인된 독립 구현의 기회 귀속·20초 시한을 깨뜨리며 실제 U4b 통합 보류와 별개의 결함이다. | 모든 타이머와 생성 완료를 예약 당시 sessionGoalId·attemptId 및 단계/기회 식별자·generation에 묶고 현재 소유 작업과 일치할 때만 반영한다. 전환 시 이전 예약을 취소하더라도 이미 전달된 콜백의 소유권 검사는 유지한다. 두 목표 이상을 빠르게 완료하는 Clock 시험에서 이전 20초 타이머가 다음 기회를 닫지 않는지 검증하고, 다음 목표 준비 실패 후 그다음 목표 준비 중 도착한 이전 생성 결과·만료도 무시하는 회귀 시험을 추가한다. | Resolved (reviewer) |
| R-02 | Minor | aidlc/spaces/default/intents/261013-u5b-practice/construction/code-generation/traceability.json > upstream_ids 및 coverage | 요구사항 본문에 남아 있는 NFR5·NFR7·NFR8이 추적표에서 모두 빠져 있다. 센서는 missing_from_upstream_ids에 이 세 ID를 보고한다. 실제 성능 미검증과 리포트 범위 제외를 요구사항이 설명하고 있지만 코드 추적표에는 그 구분이 남지 않아 후속 작업에서 누락과 의도된 보류를 구별하기 어렵다. | NFR5·NFR7은 실제 공급자·부하 검증이 남은 Deferred 근거를, NFR8은 이번 U5b 범위에서 제외된 N/A 근거를 명시해 추적한다. 이 수정으로 후속 통합을 완료 처리하거나 이번 독립 구현 범위를 확대하지 않는다. | Resolved (reviewer) |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Vitest — tests/u5b/practice-progress.test.tsx | 직접 재실행 PASS, 1파일 8건 | 정확한 제목 문자열, 내부 정보 미노출, 여섯 단계 표시, 잘못된 입력 및 조회 실패 처리와 접근성 속성이 유지된다. |
| ESLint — PracticeHeader.tsx·u5bCopy.ts | 직접 실행 종료 0, 출력 없음 | 제목 조합을 문구 모듈로 옮겨 기존 JSX 문구 규칙을 충족한다. 규칙을 완화하는 코드가 필요하지 않다. |
| sensor-required-sections — code-generation-plan.md | PASS, H2 13개 | 승인된 수정 범위와 후속 통합 경계가 유지된다. |
| sensor-required-sections — unit-test-instructions.md | PASS, H2 5개 | 재검증 명령과 기존 시험·커버리지 기준이 명시돼 있다. |
| sensor-required-sections — code-summary.md | PASS, H2 6개 | 수정 후 프런트 결과와 이전 백엔드 실행 증거가 구분된다. |
| sensor-traceability — traceability.json | PASS, findings_count=0 | 누락 ID와 잘못된 대상이 없고 실제 통합 Deferred가 유지된다. |
| coverage-summary.json 및 coverage-scope/u5b.json 대조 | 기존 운영 파일 5개, 줄 60/60=100% | 문구 함수도 측정 범위에 포함된다. 80% 기준을 충족하며 파일 제외로 수치를 높이지 않았다. |
| 실행 기록과 코드 요약 대조 | U5b 16건·U4a/U5a 116건, lint·typecheck·production build 통과 기록 일치 | 전체 프런트 검증은 전달된 실행 기록에 근거한다. 이번 검토에서 직접 재실행한 범위는 위 진행 시험 8건과 변경 운영 파일의 lint이며 전체 빌드를 반복했다고 주장하지 않는다. |

### Summary

승인된 BT-F01 범위에서 제목 조합이 U5B_COPY.heading으로 이동했고 기존 문구·공백·구분자와 표시 동작이 보존됐다. 새로운 지적은 없으며 실제 통합·운영 성능·품질 미검증 항목은 별도 승인 및 후속 검증 대상으로 유지된다.
