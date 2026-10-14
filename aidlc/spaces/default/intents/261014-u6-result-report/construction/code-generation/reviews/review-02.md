## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-14T07:00:27Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Critical | backend/core-api/src/main/java/kr/mujoong/core/report/ReportHandler.java > handle 30–39행 / load 60–69행 | 삭제를 접수한 보호자의 기존 리포트 작업이 AI로 전사를 보낼 수 있다. 운영 경로는 정상 POST /reports로 작업을 만든 뒤 AccountDeletionService.request가 DELETION_REQUESTED를 커밋하고, 삭제 참가자가 실행되기 전에 JobRunnerLoop가 그 작업을 선점·실행하는 순서다. 삭제 접수는 U6 작업을 동기 취소하지 않으며, 삭제 지휘는 별도 5초 주기에서 실행되고 진행 세션이 있으면 종료를 기다린다. claim·execute·ReportHandler.load에는 보호자 상태 검사가 없고 삭제 검사는 최종 commit에서만 실행되어 이미 전송한 전사를 보호하지 못한다. `contracts/ai-io.md` 6.1의 철회·삭제 자료 입력 제외 의무와 충돌한다. 별도 경계 재현에서도 철회 사유·정리 상태 NONE 기록은 공급자 요청에 인용 3개가 포함됐다. | 공급자 입력을 만들기 전에 현재 동의·삭제 상태를 확인하고, 철회된 활동의 전사·결과 문장·구조화 내용을 AI 입력에서 제외한다. 실제 생성된 REPORT 작업을 대기시킨 뒤 삭제 접수를 커밋하고 참가자 실행 전에 작업을 돌리는 회귀 시험에서 공급자 호출이 0건인지 확인한다. CONSENT_WITHDRAWN 기록 제외도 시험하며 AI 호출은 계속 DB 트랜잭션 밖에서 실행한다. | Resolved (reviewer) |
| R-02 | Major | backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java > sceneRows 116–124행 | 재판정 인용이 해당 장면의 반응인지 확인하지 않고 세션 전체 아이 발화와 일치하는지만 검사한다. 장면1의 FIRST는 seq1, 장면2의 NEW_SCENARIO는 seq2인데 AI가 장면1의 firstResponse로 seq2를 돌려주면 장면1도 `DONE_IMMEDIATE`로 저장하는 것을 재현했다. 다른 장면·목표의 실제 발화가 현재 장면의 바로 전달 근거로 채택되어 27·리포트 칩에 반영된다. `contracts/ai-io.md` 6.2는 장면마다 첫 반응과 도움 후 반응을 나누고, 도움 후 반응이 첫 반응을 덮지 않도록 정한다. | firstResponse와 afterHelpResponse의 발화 번호를 현재 목표·장면·반응 종류의 기록과 대조한 뒤 판정을 채택한다. 다른 장면·목표의 발화, 도움 후 발화를 IMMEDIATE 근거로 쓴 출력은 채택하지 않도록 처리하고, 정상 장면별 출력과 잘못 연결된 출력의 회귀 시험을 추가한다. | Resolved (reviewer) |
| R-03 | Major | backend/core-api/src/main/java/kr/mujoong/core/result/ResultAiSchema.java > validate judgment 13행 | 승인된 BR4.5와 functional-spec 8절은 timing이 없는 functional을 IMMEDIATE로 처리하도록 정했지만 스키마 검사는 그 출력을 SCHEMA_INVALID로 거절한다. 실제 validate 호출에서 `FUNCTIONAL_WITHOUT_TIMING=SCHEMA_INVALID`를 재현했다. 따라서 허용된 기본값 경로가 칩 계산에 도달하지 못하고 같은 출력이 반복되면 결과 작업 전체가 FAILED가 된다. | functional의 timing 생략·null을 허용하고 저장 전에 IMMEDIATE로 정규화한다. 게이트웨이부터 처리기·작업 완료까지 연결한 시험에서 timing 없는 functional이 DONE_IMMEDIATE로 저장되고 작업이 DONE인지 확인한다. 잘못된 timing 열거값 거절은 유지한다. | Resolved (reviewer) |
| R-04 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioLookup.java > get 두 오버로드 / backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java > judgmentGoals 108–110행 | BR12.1은 없거나 다른 보호자의 scenarioId를 조회 결과에서 빼고 상황 없이 재판정을 이어 가도록 정한다. 실제 두 get 메서드는 `ScenarioRepository.require`를 호출하며, 없는 ID는 NOT_FOUND, 소유자가 다른 ID도 NOT_FOUND를 던진다. `judgmentGoals`는 이 오류를 처리하지 않아 JobRunner에서 INTERNAL로 재시도하고 끝내 세션 전체를 FAILED로 만든다. 이는 U5b 후속 사상표 문제가 아니라 이번 U6이 만든 공개 읽기 포트의 명시된 누락 처리와 다른 동작이다. | 결과 처리용 상황 조회에서 없거나 소유자가 다른 항목을 안전하게 제외하고, 해당 장면은 상황 없이 입력을 조립하도록 반환형과 호출부를 맞춘다. 유효 ID와 누락·다른 보호자 ID를 섞어도 유효 항목은 남고 전체 결과 작업이 실패하지 않는 시험을 추가한다. | Resolved (reviewer) |
| R-05 | Critical | backend/core-api/src/main/java/kr/mujoong/core/result/ResultIntake.java > constructor 24행 / recorded 26–28행 | 기록 커밋 뒤 결과 접수가 원래 연결을 보유한 호출 스레드에서 REQUIRES_NEW 연결을 동기 요청한다. SessionRecordCapturedHandler.handle 97행은 Inbox 트랜잭션 안에서 ActivityRecorded를 발행한다. 실제 Spring 7.0.9 바이트코드의 processCommit은 AFTER_COMMIT/afterCompletion 콜백 뒤에 cleanupAfterCompletion을 호출하고 JpaTransactionManager는 그 정리에서 연결을 해제한다. 따라서 Inbox 두 스레드가 기록을 동시에 커밋하면 연결 2개를 각각 보유한 채 추가 연결을 기다릴 수 있다. 허용된 풀 크기는 1–10이고 현재 시험 풀은 2다. 현재 U4aActivityApiIT XML은 deletionParticipantsRemoveBackgroundRecordAndCopyOfThatGuardianOnly가 ActivityRecordDeletionParticipant:42에서 2초 뒤 total=2, active=2, idle=0, waiting=2로 실패하고, 같은 시각 두 기록 처리 trace에 CannotCreateTransactionException이 남은 사실을 확인한다. U6 접수가 공유 DB 풀을 고갈시켜 기존 삭제 경로까지 실패시키는 회귀이며 NFR9의 기존 시험 통과를 충족하지 못한다. 풀 10에서 항상 실패한다거나 당시 연결 소유자 스택을 확인했다는 주장은 하지 않는다. | 기록 트랜잭션의 연결 반환을 기다리는 동안 추가 연결을 요구하지 않도록 결과 접수를 분리한다. 예를 들어 커밋 후 별도 실행기에 접수를 넘기고 누락 점검을 복구 경로로 유지한다. 기존 풀·연결 시한·시험 단언을 유지한 채 동시 기록 2건에서 접수와 계정 삭제가 성공하는 회귀 시험을 추가하고 U4aActivityApiIT 및 전체 backend check를 통과시킨다. | New |
| R-06 | Minor | docs/units/u6/report.md > 검증 기록 / aidlc/spaces/default/intents/261014-u6-result-report/construction/code-generation/code-summary.md > 검증과 편차 | 구현 보고와 코드 요약은 최종 backend check가 실패 0으로 통과했다고 적지만 현재 독립 U4a 실행 XML은 11개 중 삭제 시험 1개 실패다. U6 212개·86.17% 수치도 수정 전 기록이며 호출자가 전달한 최신 실행 수치는 232개·86.73%다. 현재 제품 검증 상태를 이 문서만으로 판단하면 삭제 회귀를 놓친다. | 검증 기록을 현재 구현 기준으로 갱신한다. 이전 성공 기록은 이전 실행임을 표시하고 최신 실패 시험·원인·재검증 결과와 최신 U6 수치를 적는다. | New |

> R-01 Reviewer note: ReportHandler가 입력 읽기 전 현재 보호자·동의를 검사하고 ResultTextGateway가 공급자 제출 직전에 다시 검사한다. QuoteCandidatePicker와 ReportInput.done은 CONSENT_WITHDRAWN 활동을 인용·결과·구조화 입력에서 제외한다. ReportHandlerIT의 realCreatedReportIsNotSentAfterDeletionRequestBeforeParticipants는 실제 ReportService.create → AccountDeletionService.request → 참가자 실행 전 claim/execute 경로에서 공급자 0건을 검사하며, 철회 활동 NONE/DONE 및 후속 분류·최종 확정 차단 시험도 있다. AI 호출은 읽기 트랜잭션 종료 뒤 실행한다.

> R-02 Reviewer note: SessionResultHandler.sceneRows가 목표별 FIRST/NEW_SCENARIO 번호·원문을 대조하고 AFTER_CUE는 같은 scenario의 반응만 허용한다. anotherScenesActualQuoteCannotMakeFirstSceneImmediate, helpResponseCannotReplaceImmediateEvidence, anotherGoalsActualQuoteCannotSupportThisGoal 및 afterHelpMustBelongToTheSameScenario가 정상·오연결 경계를 검사한다.

> R-03 Reviewer note: ResultAiSchema의 judgment timing은 생략·null을 허용하고 알려지지 않은 열거값은 거절한다. sceneRows는 functional의 null timing을 IMMEDIATE로 정규화한다. missingOrNullTimingCompletesWithImmediate가 실제 게이트웨이·처리기·실행기를 거쳐 DONE 및 DONE_IMMEDIATE 저장을 검사하며 invalidTimingStillFailsSchema가 거절 경계를 유지한다.

> R-04 Reviewer note: ScenarioLookup 두 오버로드가 repository.find와 소유자 필터로 누락·타인 항목을 제외한다. 결과용 오버로드는 session도 검사하며 judgmentGoals는 Optional을 펼쳐 유효 항목만 전달한다. validMissingAndForeignScenariosStillCompleteTheJob가 혼합 입력에서 유효 상황만 남고 작업 DONE인 경로를 검사한다.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Stage definition inspection | 별도 실행형 검증 도구 지정 없음; required-sections와 traceability 센서 선언 | 지정되지 않은 검증 명령을 임의로 만들지 않았다. 아래 읽기 전용 구조 검사를 수행했다. |
| Required-sections read-only check | PASS: 계획 H2 8개, 시험 지시 H2 6개, 요약 H2 3개 | 선언된 문서 구조 하한을 충족한다. |
| Traceability read-only JSON/path check | PASS: coverage 215행, ID 중복 0, OK 대상 파일 누락 0, 상태 OK/N/A | 구조·대상 존재는 맞지만 NFR9 충족 여부는 실제 실패 증거로 판단해야 한다. |
| Prior-finding source/test inspection | R-01–R-04 수정과 해당 경계 회귀 시험 확인 | 수정 경로와 시험 단언을 독립 확인했다. 새 전체 시험 실행을 주장하지 않는다. |
| Current U4aActivityApiIT XML inspection | FAIL: 11 tests / 1 failure; 삭제 참가자 연결 획득 실패, pool active 2/2 | 현행 소스로 실행한 독립 시험의 보존된 증거다. 호출자는 전체 check에서도 같은 실패를 보고했다. |
| Cached Spring 7.0.9 javap inspection | AFTER_COMMIT/afterCompletion → cleanupAfterCompletion → JpaTransactionManager 연결 해제 순서 확인 | ResultIntake의 동기 REQUIRES_NEW가 원래 트랜잭션 연결 반환보다 앞서는 구조적 연결 의존성을 확인한다. |
| Latest U6/targeted execution evidence availability | 호출자 보고: 표적 57개 통과, U6 232개 통과·줄 86.73%; 이후 실행이 XML/JaCoCo를 덮어써 별도 보존 증거 없음 | 이 수치는 독립 검증 완료로 세지 않는다. 현재 보존된 삭제 회귀 실패를 성공한 U6 범위 시험으로 상쇄할 수 없다. |

### Summary

이전 네 지적은 구현과 회귀 시험에서 수정됐다. 그러나 결과 접수가 원래 기록 트랜잭션의 연결 반환을 막은 채 새 연결을 기다리는 구조로 기존 계정 삭제 시험이 실패하므로, 공유 풀 경합을 없애고 현행 회귀 검증을 통과시켜야 한다.
