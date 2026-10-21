## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-21T06:27:31Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Critical | backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java > JoinEnv.participationWindowOpened (1372), onInputTranscriptQuiet (2194), groupInputDelta (2175) | 안내 전에 열린 전사 항목의 무효화가 조용 대기 시간에만 의존한다. 완료 시 기존 inputGroupId를 지우고 draining을 켜지만, inputTranscriptQuiet 뒤에는 draining을 끄고 옛 itemId를 기억하지 않는다. 따라서 `old` 항목의 `응` 조각 → 안내 COMPLETED → inputTranscriptQuiet 만료 → 같은 `old` 항목의 늦은 `응` 조각 → 다시 quiet 만료 순서는 소스 경로상 마지막 조각의 수신 시각을 새 입력 시작 시각으로 만들어 JoinFlow.currentInput을 통과한다. 아이가 안내 뒤 새 수락을 하지 않아도 ACTIVE 전이와 동의된 원음 기록 시작에 도달할 수 있어 FR2.3/BR2.4의 참여 경계를 지키지 못한다. ParticipationServiceGuardrailTest.earlyAcceptDoesNotBecomeParticipationWhenNoticeLaterCompletes는 quiet 만료 이전의 꼬리만 검사한다. 이 판단은 소스 추적 결과이며 이번 검토에서 동적 재현을 완료한 결과는 아니다. | 안내/참여 질문 완료 경계를 넘긴 이전 전사 항목의 귀속을 유지하여 같은 항목의 늦은 조각이 새 참여 입력으로 다시 열리지 않게 한다. 위 순서에서 WAITING_CHILD 유지·원음 기록 시작 0회를 확인하고, 별도 새 항목의 안내 후 수락만 ACTIVE로 전환되는 실행기 회귀 시험을 추가한다. | Resolved (reviewer) |

> R-01 Reviewer note: ParticipationInputBoundary가 안내 전 itemId 이력과 시간선 경계를 quiet 이후에도 보존한다. SessionExecutor는 공급자 reader의 atomic 입력 끝 watermark와 수신 PCM 시간선을 완료 경계에 고정하며, 근거 없는 null-ID 입력을 수락하지 않는다. ParticipationLateItemServiceGuardrailTest의 기존 두 회귀가 유지됐고 총8개가 조용 대기·재안내·미관측 지연·연결 교체·정상 새 입력을 검사한다. ProviderRequestServiceGuardrailIT는 실제 ProviderClient가 디코딩한 null itemId/start_ms/end_ms를 실행기까지 전달해 과거 꼬리 차단과 새 수락/원음 시작1회를 대조한다. 현재 전체 XML에서 각각8개와2개 통과를 확인했다.

> R-01 Earlier decision: Accepted risk

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| manifest 파일 내용 지문 재계산 | 216개 경로의 정렬된 경로+NUL+파일 SHA256+LF listing SHA256이 `7a6aaae11d59cf87f32f711dd1df70dbb20c3a48b93df8f592a8c1d71abf41c4`와 일치 | 최신 완료 보고서가 가리킨 동결 소스를 검토했다. |
| traceability 및 기능 범위 읽기 전용 검사 | upstream/coverage123행,120 OK/3 Deferred, OK 대상 누락0; 운영 scope39개·시험 목록150개가 각각 고유·실파일·manifest 포함 | 기존 분모를 보존한 확대와 대상 존재를 확인했다. NFR3/NFR3.2 전체 verify 및 NFR4.3 실제 음성은 미완료로 남아 있다. |
| 최신 JUnit/JaCoCo XML 집계 | contract-model330개·LINE1547/1570, Core1759개·LINE10134/10527, Realtime2029개·LINE14984/15986; 운영4118개 실패/오류/skip0 | 보고서의 최신 전체 수치 및 줄80% 하한 충족과 일치한다. 전체 시험을 다시 실행하지 않았다. |
| 참여 경계·실제 요청·RG1 구현/회귀 대조 | ParticipationLateItemServiceGuardrailTest8개, GreetingHandoffServiceGuardrailTest8개, ProviderRequestServiceGuardrailIT2개 현재 XML 통과 | 첫 요청에 현재 녹음 완료/참여 사실과 반복 금지가 전달된다. 중복 후보는 inspect·승인 원문 생략·JEV 전에 검사되고 실제 송신 직전 다시 검사된다. 음성/전사 구간을 함께 폐기하며 추가 교정/재생성 호출을 만들지 않는다. |
| RG1 재생 수명·정상 반례 대조 | 첫 프레임 송신과 현재 재생ID/세대/연결/마감 안 COMPLETED가 해제 조건; 가짜 완료·FAILED/STOPPED·만료·늦은 ALLOW·연결 교체 부정 사례 및 실질 답/명시적 정체·인사 요청/인용/PRACTICE 반례 유지 | 후보 수신이나 큐 진입만으로 제약을 해제하지 않는다. 검사 대상은 명시된 유한 표현이며 모든 의미적 재표현의 탐지나 실모델 항상 준수로 확대 해석하지 않았다. |
| Neo4j 초기화·일반 요청·설정/계약 대조 | prepareSchema 전체에 단일 초기화 deadline; 성공 종료 후 ready 개방; 실패 정리 전체에 별도 일반 budget; 일반 요청과 ChildKnowledgeService에 기존 graph-budget 전달 유지 | 기본 준비10초+실패 정리2초와 일반 HTTP/JDBC2초가 분리된다. 공유 budget을 변경하지 않으며 원래 오류/suppressed/interrupt를 보존한다. 단일 HTTP3문장의 digest/CAS 조건과 무쓰기 EXPLAIN을 유지한다. |
| 초기화 시험 및 실제 cold 실행 기록 대조 | GraphInitializationBudgetServiceGuardrailTest8개는 잔여 deadline·정확 만료·중단·정리 실패·일반10요청2초 및 localhost2.3초 준비 허용을 검사; 최신 전체 GraphWorkflowIT는 업무 초기화543ms/replace476ms/commit24ms 기록 | 합성 지연과 실제 Neo4j 관측이 구별되어 있다. 기본10초는 초기 구현값이며 사용자가 직접 숫자를 선택한 것으로 기록하지 않는다. |
| 기존 시험 diff 대조 | 첫 모델 인사 fixture를 정상 후속 문구로 바꾸면서 원음 byte/동의/출력 개수·승인행/시간 단언 유지 | 새 RG1 동작에 맞춘 자료 수정이며 기존 품질 하한을 낮춘 증거는 발견하지 않았다. 이전 실패/취소와 teardown FK 미재현의 한계도 기록에 남아 있다. |

### Summary

전달된 계약과 동결 소스 범위에서 새로운 차단 우려를 발견하지 않았다. 참여 입력 귀속, 첫 음성 송신 전 RG1 차단과 실제 재생 수명, 초기화 전용 deadline과 일반2초의 분리가 구현 및 현재 회귀 증거와 맞는다. 전체 verify.sh·U17 화면/브라우저 통합·실제 승인 음성은 후속이며, 운영 빈 음성 카탈로그의 fail-closed와 합성 자원 성공을 실제 서비스 음성 완성으로 취급하지 않았다.
