# U10 분석 기준과 연결 확인

## 브랜치와 병합 순서

- U10 브랜치는 지시서의 미병합 의존 예외대로 U14 종료 기록 커밋 `eb34c68bf24e4520881b7ffebe1070564554dfc9`에서 생성했다.
- 사용자가 U15 병합 → U14 상태 재확인 및 병합 → U10 개발을 명시적으로 요청했다.
- U15 PR #26은 머지 커밋 `eced132d0272435789a2390b29450afe25ed6a75`로 병합됐다. main CI 실행 `38992134867`은 skeleton 및 ci-gate를 포함해 최종 SUCCESS다.
- U14 PR #27은 U15 병합 후 CLEAN / MERGEABLE과 최신 커밋의 ci-gate SUCCESS를 다시 확인한 뒤, 머지 커밋 `48a132b8ebe5a276e9a5d26ddbf35353ddf9fac6`으로 병합했다. U14 병합 후 main CI 실행 `38992958040`은 최종 SUCCESS이며 backend·frontend·images·u5b-scope·skeleton·ci-gate를 포함한 실행 검사가 모두 통과했다. deploy/plan은 SUCCESS이고 실제 deploy/deploy는 SKIPPED다.
- U10 브랜치는 origin/main을 fast-forward로 반영했다. 소스 파일 변경 없이 HEAD만 `48a132b8ebe5a276e9a5d26ddbf35353ddf9fac6`으로 이동했고 기존 미커밋 작업은 보존했다.
- U10 제품 코드는 아직 수정하지 않았다. 코드 분석 결과는 `developer-scan.md`에 있다.

## 집중 분석의 범위

이번 분석은 U10 관련 38개 파일을 읽었다. 기존 문서 본문은 보존했으나, 아래 U14 영역은 이번에 다시 깊게 확인하지 않았으므로 얕은 참조 범위로 남겼다. U14 구현이나 검증 결과가 취소됐다는 뜻은 아니다. 범위 검사 원문은 다음과 같다.

```text
NARROWER: the incoming scope no longer claims verified deep coverage for:
  - backend/core-api/src/main/java/kr/mujoong/core/practice/CandidateRegistry.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/PracticeGoalRepository.java
  - backend/core-api/src/main/java/kr/mujoong/core/practice/PracticeDeletionParticipant.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/ResultAiSchema.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/ResultData.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/JobRunner.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/ResultQueryService.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/SceneChipMapper.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/SceneJudgmentRepository.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/CandidateScreen.java
  - backend/core-api/src/main/java/kr/mujoong/core/result/ResultDeletionParticipant.java
  - backend/core-api/src/main/java/kr/mujoong/core/report/ReportAssembler.java
  - backend/core-api/src/main/java/kr/mujoong/core/knowledge/SeedGenerationService.java
  - backend/core-api/src/main/java/kr/mujoong/core/knowledge/StructureSignature.java
  - backend/core-api/src/main/resources/db/migration/core/V5__u6_results_report.sql
  - contracts/schemas/ai-goal-candidate-output.schema.json
  - contracts/schemas/ai-rejudgment-output.schema.json
  - contracts/schemas/practice-record-v2.schema.json
  - contracts/schemas/session-result-response.schema.json
  - frontend/src/parent/api/results.ts
  - frontend/src/shared/timing/timing-defaults.json
  - infra/migration-approvals.txt
  components: u14.practice-goal, u14.result-pipeline, u14.report-projection, u14.knowledge-integration, u14.contract-compatibility
```

## 다음 단계의 확인 사항

- 새 목표 준비 입구와 첫 장면 전달까지 포함한다.
- 기술 재시도와 내용 재생성의 소유권을 하나로 정하고 전체 15초를 지킨다.
- 그래프 맥락과 변형 계획 조회의 합계 마감을 정한다.
- 관심사 전달 여부는 지시서대로 Functional Design에서 사람에게 확인한다.
- U5a 기존 오류 단언을 유지하면서 U10 최종 생성 실패 규칙을 정한다.
- 자동 대역 시험, 실제 API 합성 자료 측정, 실제 기기 검증을 혼동하지 않는다.
