# U6 전체 요구사항 추적 확인

## 범위와 판정

zero-Unit 작업이다. requirements.md에 정의된 세부 FR과 NFR을 전수 열거했다. 스토리 단계는 실행되지 않아 별도 stories.md의 AC는 없다. FR1–FR16은 묶음 머리로서 하위 행으로 추적하며 원래 기록 ID는 출처다. 코드 추적표의 OK와 실제 파일 존재를 검사했다. 제품 시험 통과와 구조 추적은 별도다.

## Coverage

| ID | Owning Stage | Target | Verdict |
|---|---|---|---|
| FR1.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/JobRunnerLoop.java` | PASS |
| FR1.2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultRetryService.java` | PASS |
| FR2.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultIntake.java` | PASS |
| FR2.2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultIntake.java` | PASS |
| FR2.3 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultIntake.java` | PASS |
| FR3.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java` | PASS |
| FR4.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java` | PASS |
| FR4.2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/NotDoneReasonMapper.java` | PASS |
| FR4.3 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/HelpSummarizer.java` | PASS |
| FR5.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/check/OutputChecker.java` | PASS |
| FR5.2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultLevelCalculator.java` | PASS |
| FR6.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/CandidateScreen.java` | PASS |
| FR6.2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultEvidenceAdapter.java` | PASS |
| FR7.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/TipMatcher.java` | PASS |
| FR8.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java` | PASS |
| FR8.2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java` | PASS |
| FR8.3 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java` | PASS |
| FR8.4 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java` | PASS |
| FR9.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/report/ReportService.java` | PASS |
| FR9.2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/report/ReportService.java` | PASS |
| FR9.3 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/report/ReportHandler.java` | PASS |
| FR9.4 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/report/ReportHandler.java` | PASS |
| FR10.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/report/ReportService.java` | PASS |
| FR10.2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/report/ReportService.java` | PASS |
| FR10.3 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/report/pdf/ReportPdfRenderer.java` | PASS |
| FR11.1 | code-generation | `frontend/src/parent/screens/Done17.tsx` | PASS |
| FR11.2 | code-generation | `frontend/src/parent/screens/Records26.tsx` | PASS |
| FR11.3 | code-generation | `frontend/src/parent/screens/RecordDetail27.tsx` | PASS |
| FR12.1 | code-generation | `frontend/src/parent/screens/ReportRange28.tsx` | PASS |
| FR12.2 | code-generation | `frontend/src/parent/screens/Report30.tsx` | PASS |
| FR12.3 | code-generation | `frontend/src/parent/components/LiveRegion.tsx` | PASS |
| FR13.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioLookup.java` | PASS |
| FR13.2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/knowledge/BehaviorDefinitionLookup.java` | PASS |
| FR13.3 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/practice/CandidateRegistry.java` | PASS |
| FR14.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultDeletionParticipant.java` | PASS |
| FR15.1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/record/RecordEligibility.java` | PASS |
| FR16.1 | code-generation | `contracts/parent-api.yaml` | PASS |
| FR16.2 | code-generation | `contracts/timing.md` | PASS |
| FR16.3 | code-generation | `backend/core-api/src/main/resources/forbidden/result-v1.json` | PASS |
| FR16.4 | code-generation | `docs/units/u6/design.md` | PASS |
| NFR1 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/report/ReportHandler.java` | PASS |
| NFR2 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java` | PASS |
| NFR3 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/check/OutputChecker.java` | PASS |
| NFR4 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java` | PASS |
| NFR5 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java` | PASS |
| NFR6 | code-generation | `backend/core-api/src/test/java/kr/mujoong/core/arch/TimeLiteralScanTest.java` | PASS |
| NFR7 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/JobRunner.java` | PASS |
| NFR8 | code-generation | `backend/coverage-scope/u6.txt` | PASS |
| NFR9 | code-generation | `docs/units/u6/report.md` | PASS |
| NFR10 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java` | PASS |
| NFR11 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/result/ResultDeletionParticipant.java` | PASS |
| NFR12 | code-generation | `backend/core-api/src/main/java/kr/mujoong/core/report/pdf/ReportPdfRenderer.java` | PASS |

## 결과와 미추적

PASS: 52개 상세 FR/NFR의 OK 대상 파일이 모두 존재한다. 미추적 요소0. NFR9 실제 통과 여부는 test-results.md의 실행 결과를 따른다.

## Sources

inception/requirements-analysis/requirements.md; construction/code-generation/traceability.json.
