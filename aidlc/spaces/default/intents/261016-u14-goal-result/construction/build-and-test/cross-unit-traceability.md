# U14 최종 요구사항 추적

## 입력과 판정

이 기록 requirements.md의 FR12개/NFR5개를 stage-level code-generation/traceability.json과 대조한다. User Stories 단계는 실행하지 않았으므로 새 AC 집합을 만들지 않는다. 원 스토리 AC는 코드 생성의 58행에 별도 추적되며 AC4.6.2/3의 상황 생성·진행 판정은 U10/U11/U12 소유로 범위가 구분돼 있다. 이 기록은 zero-Unit이며 stage-level 소유다.

## 요구별 대상

| ID | Status | Owning Stage | Target |
|---|---|---|---|
| FR1.1 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/GoalCandidateRepository.java |
| FR1.2 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/PinnedSavedGoalReader.java |
| FR2.1 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/GoalCandidateGateway.java |
| FR2.2 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/GoalCandidateValidator.java |
| FR2.3 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/CandidateSeedDelivery.java |
| FR3.1 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/result/SceneReportMapper.java |
| FR3.2 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/result/PracticeRecordV2.java |
| FR3.3 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/result/RejudgmentGateway.java |
| FR3.4 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/config/CoreProperties.java |
| FR3.5 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/report/ReportAssembler.java |
| FR4.1 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java |
| FR4.2 | OK | Code Generation | backend/core-api/src/main/java/kr/mujoong/core/practicegoal/PracticeGoalRepository.java |
| NFR1 | OK | Code Generation | backend/coverage-scope/u14.txt |
| NFR2 | OK | Code Generation | backend/core-api/src/test/java/kr/mujoong/core/practicegoal/GoalCandidateGatewayTest.java |
| NFR3 | OK | Code Generation | backend/core-api/src/test/java/kr/mujoong/core/practicegoal/U14EmptyCandidateReplayIT.java |
| NFR4 | OK | Code Generation | docs/units/u14/migration-review.md |
| NFR5 | OK | Code Generation | backend/core-api/src/test/java/kr/mujoong/core/result/U14TimingContractTest.java |

## 최종 확인

requirements의 고유 FR/NFR 17개 모두 OK 행으로 연결되며 누락0, 전체58행 중 OK 대상의 없는 경로0으로 PASS다. 실제 파일 존재 확인을 수행했다. 추적 성공은 해당 동작 시험 통과를 대신하지 않는다.

