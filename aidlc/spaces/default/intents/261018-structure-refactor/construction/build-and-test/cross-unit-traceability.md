# 교차 단위 최종 추적 — 구조 · 이름 리팩토링

> 이 기록은 유닛 나누기를 건너뛴 한 덩어리 작업이다. 그래서 유닛별 `traceability.json`이 아니라 단계 단위 `construction/code-generation/traceability.json` 하나를 읽었다. 사용자 스토리 단계는 실행하지 않아(refactor 범위) 스토리 AC 목록은 없다.

## 판정

**PASS** — `requirements.md`의 FR · NFR 45개 ID가 모두 추적표에 있고, 묶음 머리 8개(FR1 ~ FR8)를 뺀 37개가 `OK`이며 대상 파일이 모두 존재한다. 덮이지 않은 ID는 없다.

## ID별 추적

| 구분 | ID | 상태 | 소유 | 대상 파일(예) |
|---|---|---|---|---|
| 묶음 머리 | FR1 ~ FR8 | N/A (하위 행이 추적) | 단계 단위 | — |
| FR1 | FR1.1 · FR1.2 · FR1.3 | OK | code-generation | `SessionClocksCharacterizationTest.java` · `baseline.md` · `reference-checklist.md` |
| FR2 | FR2.1 ~ FR2.4 | OK | code-generation | `LoginScreen.tsx` · `FirstBootView.tsx` · `apiClient.ts` · `eslint.config.mjs` |
| FR3 | FR3.1 ~ FR3.4 | OK | code-generation | `useLiveScreen.ts` · `common/api/practice.ts` · `features/practice/copy.ts` · `codeToCopy.ts` |
| FR4 | FR4.1 ~ FR4.7 | OK | code-generation | `SessionContext.java` · `EndingFlow.java` · `JudgmentCoordinator.java` · `baseline.md` · `gate-boundary.md` · `SingleInstanceMarkerTest.java` |
| FR5 | FR5.1 · FR5.2 | OK | code-generation | `PracticeFlowArchTest.java` · `RealtimeSessionProperties.java` |
| FR6 | FR6.1 ~ FR6.4 | OK | code-generation | `frontend/vitest.config.ts` · `backend/coverage-scope/practice-flow.txt` · `.github/workflows/ci.yml` · `docs/ci-job-names.md` |
| FR7 | FR7.1 · FR7.2 | OK | code-generation | `aidlc/spaces/default/memory/team.md` · `SessionExecutor.java` |
| FR8 | FR8.1 · FR8.2 | OK | code-generation | `docs/screen-map.md` · `docs/refactor-structure-before-after.md` |
| NFR | NFR1 ~ NFR9 | OK | code-generation | 시험 · 경계 · 문서 파일(표 전체는 `code-generation/traceability.json`) |

## 덮이지 않은 요소

없음.

## 이 확인이 보증하지 않는 것

- 추적표는 "그 ID를 구현하거나 검증하는 파일이 있다"까지만 본다. 동작 증거는 `test-results.md`를 따른다.
- 합격 기준 AC-9(첫 CI 실행)은 추적표가 아니라 `build-and-test-summary.md`의 목표 표에서 `Unverified`다.
