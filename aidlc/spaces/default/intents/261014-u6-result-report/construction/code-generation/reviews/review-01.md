## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-14T06:04:06Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Critical | backend/core-api/src/main/java/kr/mujoong/core/report/ReportHandler.java > handle 30–39행 / load 60–69행 | 삭제를 접수한 보호자의 기존 리포트 작업이 AI로 전사를 보낼 수 있다. 운영 경로는 정상 POST /reports로 작업을 만든 뒤 AccountDeletionService.request가 DELETION_REQUESTED를 커밋하고, 삭제 참가자가 실행되기 전에 JobRunnerLoop가 그 작업을 선점·실행하는 순서다. 삭제 접수는 U6 작업을 동기 취소하지 않으며, 삭제 지휘는 별도 5초 주기에서 실행되고 진행 세션이 있으면 종료를 기다린다. claim·execute·ReportHandler.load에는 보호자 상태 검사가 없고 삭제 검사는 최종 commit에서만 실행되어 이미 전송한 전사를 보호하지 못한다. `contracts/ai-io.md` 6.1의 철회·삭제 자료 입력 제외 의무와 충돌한다. 별도 경계 재현에서도 철회 사유·정리 상태 NONE 기록은 공급자 요청에 인용 3개가 포함됐다. | 공급자 입력을 만들기 전에 현재 동의·삭제 상태를 확인하고, 철회된 활동의 전사·결과 문장·구조화 내용을 AI 입력에서 제외한다. 실제 생성된 REPORT 작업을 대기시킨 뒤 삭제 접수를 커밋하고 참가자 실행 전에 작업을 돌리는 회귀 시험에서 공급자 호출이 0건인지 확인한다. CONSENT_WITHDRAWN 기록 제외도 시험하며 AI 호출은 계속 DB 트랜잭션 밖에서 실행한다. | New |
| R-02 | Major | backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java > sceneRows 116–124행 | 재판정 인용이 해당 장면의 반응인지 확인하지 않고 세션 전체 아이 발화와 일치하는지만 검사한다. 장면1의 FIRST는 seq1, 장면2의 NEW_SCENARIO는 seq2인데 AI가 장면1의 firstResponse로 seq2를 돌려주면 장면1도 `DONE_IMMEDIATE`로 저장하는 것을 재현했다. 다른 장면·목표의 실제 발화가 현재 장면의 바로 전달 근거로 채택되어 27·리포트 칩에 반영된다. `contracts/ai-io.md` 6.2는 장면마다 첫 반응과 도움 후 반응을 나누고, 도움 후 반응이 첫 반응을 덮지 않도록 정한다. | firstResponse와 afterHelpResponse의 발화 번호를 현재 목표·장면·반응 종류의 기록과 대조한 뒤 판정을 채택한다. 다른 장면·목표의 발화, 도움 후 발화를 IMMEDIATE 근거로 쓴 출력은 채택하지 않도록 처리하고, 정상 장면별 출력과 잘못 연결된 출력의 회귀 시험을 추가한다. | New |
| R-03 | Major | backend/core-api/src/main/java/kr/mujoong/core/result/ResultAiSchema.java > validate judgment 13행 | 승인된 BR4.5와 functional-spec 8절은 timing이 없는 functional을 IMMEDIATE로 처리하도록 정했지만 스키마 검사는 그 출력을 SCHEMA_INVALID로 거절한다. 실제 validate 호출에서 `FUNCTIONAL_WITHOUT_TIMING=SCHEMA_INVALID`를 재현했다. 따라서 허용된 기본값 경로가 칩 계산에 도달하지 못하고 같은 출력이 반복되면 결과 작업 전체가 FAILED가 된다. | functional의 timing 생략·null을 허용하고 저장 전에 IMMEDIATE로 정규화한다. 게이트웨이부터 처리기·작업 완료까지 연결한 시험에서 timing 없는 functional이 DONE_IMMEDIATE로 저장되고 작업이 DONE인지 확인한다. 잘못된 timing 열거값 거절은 유지한다. | New |
| R-04 | Major | backend/core-api/src/main/java/kr/mujoong/core/practice/ScenarioLookup.java > get 두 오버로드 / backend/core-api/src/main/java/kr/mujoong/core/result/SessionResultHandler.java > judgmentGoals 108–110행 | BR12.1은 없거나 다른 보호자의 scenarioId를 조회 결과에서 빼고 상황 없이 재판정을 이어 가도록 정한다. 실제 두 get 메서드는 `ScenarioRepository.require`를 호출하며, 없는 ID는 NOT_FOUND, 소유자가 다른 ID도 NOT_FOUND를 던진다. `judgmentGoals`는 이 오류를 처리하지 않아 JobRunner에서 INTERNAL로 재시도하고 끝내 세션 전체를 FAILED로 만든다. 이는 U5b 후속 사상표 문제가 아니라 이번 U6이 만든 공개 읽기 포트의 명시된 누락 처리와 다른 동작이다. | 결과 처리용 상황 조회에서 없거나 소유자가 다른 항목을 안전하게 제외하고, 해당 장면은 상황 없이 입력을 조립하도록 반환형과 호출부를 맞춘다. 유효 ID와 누락·다른 보호자 ID를 섞어도 유효 항목은 남고 전체 결과 작업이 실패하지 않는 시험을 추가한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| 단계 정의 확인 | 별도 이름을 지정한 검증 CLI 없음. required-sections·traceability 센서가 선언됨 | 산출물 구조와 추적 대상의 존재를 독립적으로 확인했다. |
| 산출물 구조·traceability JSON 검사 | PASS: 계획 H2 8개, 시험 지시 H2 6개, 요약 H2 3개. 추적 215행, OK 대상 누락 0개 | 구조·파일 존재 조건은 충족한다. 의미상 정확성은 아래 실행 경계 검토와 구분한다. |
| 기존 U6 JUnit XML·JaCoCo 증빙 | PASS: 현재 core XML 212개, failures 0, errors 0. 줄 1,171/1,359 = 86.17% | 보고서의 U6 수치와 일치한다. 기존 시험은 위 입력·증거 경계 문제를 발견하지 못했다. 전체 장시간 suite는 재실행하지 않았다. |
| 기존 REPORT·삭제·PDF 표적 XML | PASS: ReportHandlerIT 9개, 삭제 5개, PDF 6개, 새 JVM PDF 1개. REPORT A 11,213ms, D 4,123ms, PDF 270ms | 마감·전용 슬롯·늦은 쓰기·PDF 첫 렌더의 기존 검증은 확인했다. 제한 컨테이너 1,709ms·임시 파일 0개 수치는 docs/units/u6/report.md의 작성자 증빙이며 이번에 컨테이너를 다시 실행한 수치가 아니다. |
| Java 21 JShell 실제 컴파일 클래스 / ReportHandler·가짜 공급자·읽기 포트 대역 | FAIL: WITHDRAWN_RECORD_SENT=true, QUOTE_COUNT=3 | DB·운영 공급자를 사용하지 않고 철회 활동의 전사 전송을 직접 재현했다. 원본 소스·시험 파일은 수정하지 않았다. |
| 삭제 접수→기존 REPORT 실행의 운영 경로 대조 | FAIL: AccountDeletionService.request의 DELETION_REQUESTED 커밋 후에도 기존 작업 유지; 별도 AccountDeletionScheduler·DeletionCommander가 후속 정리 | 위 대역 재현은 자료 필터 경계 시험이며, 삭제 동시성 전체를 실행한 시험은 아니다. 실제 도달 가능성은 ReportService.create→삭제 접수→JobRunnerLoop.claim/execute→ReportHandler.load/call 경로와 최종 commit에만 있는 guardian 검사에서 확인했다. 삭제 후 새 API 요청이 가능하다고 주장하지 않는다. |
| Java 21 JShell 실제 sceneRows 호출 | FAIL: 장면1 FIRST seq1에 장면2 seq2 인용을 제공해도 장면1 DONE_IMMEDIATE | 인용의 글자 일치만으로는 장면별 근거 연결이 보장되지 않는다. |
| Java 21 JShell 실제 ResultAiSchema.validate 호출 | FAIL: timing 없는 functional → SCHEMA_INVALID | 승인된 IMMEDIATE 기본값 경로와 실제 검사 결과가 다르다. |
| ScenarioLookup·ScenarioRepository·judgmentGoals 호출 경로 대조 | FAIL: require(id) → NOT_FOUND → 처리기 예외 → JobRunner INTERNAL | 누락 항목 제외 대신 세션 전체 실패로 전파되는 경로를 소스에서 확인했다. |

### Summary

작업 선점·lease 세대 확인·리포트 멱등 생성·늦은 쓰기 차단·PDF 메모리 렌더링의 주요 구현과 기존 시험 증빙은 확인했다. 그러나 철회 자료의 AI 전송과 장면 근거·기본값·누락 상황 처리의 실제 불일치가 있어 NOT-READY다. 승인된 실제 공급자 UNAVAILABLE, U5b 통합, D 담당 초안 확인, Android 수동 검증은 새 결함으로 보고하지 않았다.
