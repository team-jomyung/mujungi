## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-14T02:03:38Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/rules.md > BR8.3 (머리 행이 없을 때 jobStatus) · functional-spec.md > 3.3 마지막 행, WF1, WF6 | BR8.3은 머리 행이 없고 세션이 종료 상태면 jobStatus를 REQUESTED로 낸다. 그런데 머리 행은 ActivityRecorded(활동 기록 저장) 또는 WF6 누락 점검(`core_activity_record`에 있는 세션만 읽음)으로만 생긴다. 로컬 FAILED(`ActivityCopyRepository` 로컬 FAILED, failure_code 비움)처럼 기록이 끝내 오지 않는 종료 세션은 머리 행이 영영 없다. 이 경우 17은 "기록을 정리하고 있어요"로 2초 폴링을 끝없이 한다(BR11.2). 3.3 표는 이 경우를 `NONE`(정리 줄 없음)이라고 적어 BR8.3과 어긋난다. 기존 17은 FAILED 사유 문구를 이미 그린다(`screens-u4a.test.tsx:164`). | BR8.3의 "종료 상태면 REQUESTED" 기본값을 고친다. 활동 기록(`core_activity_record`)이 있는 종료 세션만 REQUESTED로 내고, 기록이 없는 FAILED · 로컬 FAILED · 기록 수신이 영영 오지 않을 세션은 NONE을 내도록 구분한다. 구분 기준(기록 행 유무 또는 종료 후 경과 시간 상한)을 정하고, 무한 폴링이 안 생긴다는 경계 시험을 8절 목록에 더한다. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/rules.md > BR2.2 · BR2.1 · BR14.1, functional-spec.md > WF1 5단계 | 재시작 FAILED의 정리 대상 판정이 `core_activity.failure_code = SERVER_RESTART`에 달려 있다. 그 값은 SessionStateChanged가 `applyStateChange`로 사본에 반영해야 채워진다. 반면 ActivityRecorded는 별도 Outbox 이벤트인 SessionRecordCaptured 처리에서 나온다. 두 이벤트의 Core 도착 순서와 재시도 순서는 보장되지 않는다. 기록이 먼저 오면 사본이 아직 FAILED/SERVER_RESTART가 아니라 eligibility가 NOT_ELIGIBLE로 굳는다(BR2.1은 머리 행이 있으면 아무것도 하지 않음, BR2.3은 머리 행 없는 세션만 봄). 한편 BR14.1의 26 노출 판정은 조회 때마다 다시 계산되므로 나중에 사본이 갱신되면 26에는 보이는데 27은 정리 없는 NONE이 되는 불일치가 생긴다. | 판정 근거를 도착 순서에 의존하지 않게 바꾼다. 예: 기록 자체의 finalStatus=FAILED + termination 정보로 판정하거나, 사본이 아직 종료 상태가 아니면 접수를 미루는 규칙, 또는 누락 점검이 NOT_ELIGIBLE이면서 FAILED 기록인 머리 행을 재평가하는 규칙을 둔다. 순서가 뒤바뀐 경우의 시험을 8절에 더한다. | Resolved (reviewer) |
| R-03 | Major | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/rules.md > BR6.4 violation, functional-spec.md > WF2 afterCommit 후크 · 8절 "후보 등록 실패" 행 | 후보 등록 실패의 복구를 "누락은 다시 시도로 복구"라고 적었다. 그러나 이때 작업은 이미 DONE이고 BR2.4는 FAILED일 때만 다시 시도를 받는다(그 외 409 JOB_NOT_FAILED). 복구 경로가 없다. 후크는 메모리 후크라 커밋 직후 프로세스가 죽어도 후보가 영구히 사라진다(Outbox도 없음, WF6 점검도 후보를 보지 않음). 후보 누락은 FR6.1 완료 기준(후보가 등록됨)을 조용히 어긴다. | 후보 등록을 복구 가능하게 설계한다. 예: 결과 행에 `candidates_registered` 표시를 두고 WF6 점검이 DONE인데 미등록인 결과를 다시 등록한다(U5a 중복 키로 멱등). 또는 후보 등록을 같은 트랜잭션의 Outbox로 옮긴다. 아니면 후보 영구 누락을 명시적 수용 위험으로 적고 BR6.4의 "다시 시도로 복구" 문장을 지운다. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/rules.md > BR1.7 logic · BR1.8 · BR9.3 · BR10.3 · BR9.4, functional-spec.md > 3.2 | REPORT 작업 상태가 Report 상태로 옮겨지는 규칙이 SESSION_RESULT의 BR1.11 같은 "같은 트랜잭션 전이" 규칙 없이 흩어져 있다. 시도 3회 소진 · 기동 점검 · 마감 확인 · 임대 만료로 작업이 FAILED가 될 때 Report를 FAILED로 바꾸는 주체가 BR9.4(마감)에만 적혀 있다. 또 GENERATING 리포트를 버리면 작업은 쓰기 확인에서 지고 끝나지만, 그때 작업 행의 상태는 정해져 있지 않다(RUNNING이 lease까지 남음). | BR1.11과 같은 REPORT용 규칙을 더해 작업 FAILED/DONE 전이와 Report 전이를 한 트랜잭션에 묶는다. BR1.8 확인에 진 REPORT 작업의 종료 상태(예: 버려짐 사유로 FAILED)를 정한다. | Resolved (reviewer) |
| R-05 | Minor | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/rules.md > BR1.7 logic (`job-retry-backoff[attempt_count]`) | 설정 값은 [5s, 15s] 두 칸인데 색인이 `[attempt_count]`다. 첫 실패 직후 attempt_count=1이면 0 기반으로 읽을 때 15초가 되고, 둘째 실패(attempt_count=2)는 범위 밖이다. 10.3의 시험 C(첫 실패 뒤 5초 간격)와도 맞지 않는다. | 색인을 `[attempt_count - 1]`로 고치고(1 기반 문장이면 그렇게 명시) 경계 시험에 "1회 실패 → 5초, 2회 실패 → 15초"를 더한다. | Resolved (reviewer) |
| R-06 | Minor | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/rules.md > BR9.1 logic (startedAt), entities.md > ReportActivity.activity_date | 28 미리보기와 ReportActivity는 startedAt의 날짜로 기간을 거른다. 그러나 U4a의 26 줄 시각은 `listed_at`(started_at, 없으면 사본 요청 시각, U4a-J8)이다. startedAt이 null인 활동(예: 시작 직전에 끝난 ABORTED)은 26에는 보이고 28에는 빠지거나 `activity_date` NOT NULL 위반이 난다. | BR9.1 · ReportActivity.activity_date의 기준을 `listed_at`(또는 같은 U4a-J8 규칙)으로 맞추고, startedAt null 경계 시험을 더한다. | Resolved (reviewer) |
| R-07 | Minor | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/functional-spec.md > 2절 `ResultDeletionParticipant` 행 · rules.md > BR13.1 applies_to ("새 표 9개") | 새 표는 ResultJob, ResultJobAttempt, SessionResultState, ActivityResult, StructuredExperience, SceneJudgment, Report, ReportActivity, ReportSection, ReportCitation 10개이고 BR13.1 삭제 순서에도 10개가 나열되는데 문서는 "9개"라고 적었다. | 표 개수를 10개로 바로잡거나 빠진 표가 없는지 확인한다. 아울러 `DeletionParticipant` 상수 주석(쓰는 값 목록)에 70을 더한다는 변경도 5.x에 적는다. | Resolved (reviewer) |
| R-08 | Minor | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/rules.md > BR1.9 · BR1.10, functional-spec.md > WF7 | BR1.9는 기동 때 lease_until과 상관없이 RUNNING 전부를 FAILED로 굳힌다. 선점 방식(SKIP LOCKED)은 인스턴스가 둘 이상인 배포를 전제로 하는데(Outbox · 삭제 지휘와 같은 방식, FD-Q1), 한 인스턴스의 재기동이 다른 인스턴스에서 정상 실행 중인 작업을 FAILED로 만들 수 있다. 임대 만료(BR1.10) 규칙과도 역할이 겹친다. | 단일 인스턴스 가정을 명시하거나, 기동 점검 대상을 lease_until이 지난 RUNNING으로 좁히는 대안을 trade-off와 함께 적는다(요구사항 G5와 어긋나면 편차로 적는다). | Resolved (reviewer) |
| R-09 | Minor | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/rules.md > BR1.3 · functional-spec.md > WF3 마지막 단락 · 5.6 | SESSION_RESULT 시도 시한은 30초인데 AI 호출이 최대 4번(호출당 최대 10초)이라 정상이지만 느린 공급자에서도 시도가 TIMEOUT으로 3회 모두 실패할 수 있다. 호출 시한 10초와 시도 시한 30초가 서로 맞는지 근거가 없다. 또 `ResultTextGateway`가 기존 `mujoong.text-ai.request-timeout`(3초)과 `retry.max-retries`(1)를 재사용하는지, 별도 `result.*`만 쓰는지 정해져 있지 않다. | 시도 시한과 호출 수의 관계(예: 호출 4번 합계 상한)를 적고, ResultTextGateway가 U5a 재시도 설정을 상속하는지 여부를 정한다. | Resolved (reviewer) |
| R-10 | Minor | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/functional-spec.md > 2절 `JobRunnerLoop`, rules.md > BR1.2 | SESSION_RESULT와 REPORT가 같은 2개 스레드를 나눠 쓴다. 느린 SESSION_RESULT 두 건이 스레드를 모두 쥐면 REPORT는 REQUESTED로 기다리며 60초 절대 마감(BR9.4)이 흐른다. 설계는 이를 FAILED로 두지만, NFR1이 요구하는 "활동 20건 60초 안 READY"의 시험 A에는 이 경합이 들어 있지 않다. | REPORT 전용 스레드 1개 확보 또는 우선순위 선점 중 하나를 정하거나, 경합 시 FAILED가 될 수 있음을 명시 수용하고 경합 경계 시험을 더한다. | Resolved (reviewer) |
| R-11 | Minor | aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/rules.md > BR2.1 · BR2.3 | ResultIntake와 ResultReconciler가 같은 세션을 동시에 접수하면 SessionResultState 머리 행 삽입이 동시에 일어난다. BR2.1은 "이미 있으면 끝"이라고 하지만 유일 제약 충돌(동시 삽입)을 어떻게 처리하는지 BR1.4처럼 적혀 있지 않다. | 머리 행 삽입을 `ON CONFLICT DO NOTHING` 같은 동시 접수 안전 규칙으로 적고, 경계 시험 "동시 접수 2개 → 1건"에 머리 행도 포함한다. | Resolved (reviewer) |
| R-12 | Minor | rules.md BR2.3 ① | 누락 점검이 머리 행 없는 기록을 recorded_at 순 100건씩 읽는데, UNDECIDED로 미룬 건은 머리 행이 안 생겨 계속 앞줄에 남는다. 사본이 끝내 FAILED로 바뀌지 않는 건이 100건 이상 쌓이면 뒤의 새 세션이 굶을 수 있다. 가능성은 낮다. | 코드 생성 때 UNDECIDED가 일정 시간(예: 1시간)을 넘기면 NOT_ELIGIBLE 머리 행으로 굳히거나 이미 본 건을 건너뛰는 방식으로 막고, 그 시험을 더한다. | New |

> R-01 Reviewer note: 수정 확인. 기록이 없을 때는 사본 상태로 내고, 기록이 오지 않는 세션(DECLINED · 시작 실패 · 로컬 FAILED)은 NONE이라 무한 폴링이 사라졌다. 3.3 표와 시험 항목(445행)도 맞는다.; Required action: 없음

> R-02 Reviewer note: 수정 확인. UNDECIDED(사본이 아직 FAILED 아님)를 두고 머리 행 없이 미룬 뒤 누락 점검이 다시 보므로 NOT_ELIGIBLE로 굳지 않는다. 26 · 27 · 정리 상태가 같은 판정 함수를 쓰고 접수 순서 시험(522행)도 있다.; Required action: 없음

> R-03 Reviewer note: 수정 확인. pending_candidates_json을 결과와 같은 트랜잭션에 쓰고 candidates_registered_at이 빈 DONE을 누락 점검이 다시 등록한다. U5a 중복 키로 중복도 막힌다.; Required action: 없음

> R-04 Reviewer note: 수정 확인. 작업과 리포트 상태가 한 트랜잭션에서 함께 바뀌고 버려진 리포트의 작업은 FAILED(REPORT_DISCARDED)로 끝난다. traceability 대상과 규칙 78개가 모두 맞는다.; Required action: 없음

> R-05 Reviewer note: 수정 확인. backoff[attempt_count − 1]로 5초 · 15초가 맞다.; Required action: 없음

> R-06 Reviewer note: 수정 확인. 26과 같은 listed_at(startedAt, 없으면 요청 시각) 기준으로 맞췄다.; Required action: 없음

> R-07 Reviewer note: 수정 확인. 새 표 10개로 맞췄고 DeletionParticipant 상수 · 주석 변경도 적혔다.; Required action: 없음

> R-08 Reviewer note: 단일 인스턴스 전제와 다중 인스턴스 때 바꿀 점을 가정 U6-K로 명시했다. 설계 수준에서 받아들일 만하다.; Required action: 없음

> R-09 Reviewer note: 수정 확인. 호출 시한 = min(10초, lease 잔여), 게이트웨이 재시도를 상속하지 않는다고 정했다.; Required action: 없음

> R-10 Reviewer note: 수정 확인. REPORT 전용 스레드를 두었고 경합 시험 D가 있다.; Required action: 없음

> R-11 Reviewer note: 수정 확인. INSERT ... ON CONFLICT DO NOTHING이고 1행일 때만 제출한다.; Required action: 없음

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| traceability.json 파싱 + BR 대조 | PASS: rules.md의 BR 78개가 모두 대상에 있고 없는 대상도 없다(BR1.12 포함) | 정합 |

### Summary

이전 Major 3건과 Minor 8건이 모두 규칙 · 전이표 · 시험 항목에 반영되어 닫혔고, 개발자가 추측 없이 구현할 수 있다. 남은 것은 UNDECIDED 건이 누락 점검 앞줄을 채울 수 있다는 Minor 1건이다.
