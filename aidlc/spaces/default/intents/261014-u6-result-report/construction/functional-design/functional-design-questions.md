# Functional Design 질문 — U6 결과와 리포트

> 요구사항(`inception/requirements-analysis/requirements.md`)과 그 단계의 답 Q1 ~ Q11 · 가정 27건은 다시 묻지 않는다.
> 아래는 요구사항의 「열린 질문」과 요구사항 검토 지적(R-02 · R-03 · R-09) 중 설계로 정해야 하는 것만 모았다.
> 각 질문의 `[Answer]:` 뒤에 고른 글자를 적는다(X면 내용도 함께).

## Q1. 결과 작업 실행기(JobRunner)를 어떻게 돌릴까

결과 작업(SESSION_RESULT · REPORT)은 자동 3회 시도, 재시작 때 RUNNING → FAILED, 대상당 동시 1개를 지켜야 한다(FR1.1).
Core에는 이미 같은 일을 하는 방식이 두 군데 있다. 계정 삭제 지휘(`AccountDeletionScheduler` + `DeletionCommander`)와 Outbox 발송이다. 둘 다 DB 행을 `FOR UPDATE SKIP LOCKED`로 선점하고, `SmartLifecycle` 주기 실행기가 고정 지연으로 돈다.

A. 같은 방식을 쓴다. 작업 · 시도를 DB 표에 두고, 주기 실행기가 실행할 때가 된 REQUESTED 작업을 SKIP LOCKED로 선점해 RUNNING으로 바꾼 뒤 처리기를 부른다. 실패하면 다음 시도 시각(5초 · 15초 뒤)을 적어 REQUESTED로 되돌린다. AI 호출은 트랜잭션 밖에서 한다. 작업 실행 스레드는 `limits.background-db-concurrency`(3) 안에서 2개로 둔다. (추천)
B. ActivityRecorded를 받으면 바로 메모리 실행기(스레드 풀)에 넣고, DB에는 상태만 적는다. 재시작 복구는 기동 점검으로만 한다.
C. Spring `@Async` + `@Retryable`로 처리한다(재시도 간격 · 상한을 애너테이션에 둠).
X. Other (please specify)

[Answer]: A

## Q2. 정본에 값이 없던 새 시간 값

요구사항 FR16.2가 새 시간 키를 더하기로 했지만 값이 정본에 없다. 모두 `contracts/timing.md` · `application.yml` · CoreProperties에 함께 넣는다.

A. 다음 값으로 한다. (추천)
   - `result.job-tick`(실행기 주기): 1초
   - `result.attempt-timeout`(SESSION_RESULT 시도 1회 시한, AI 호출 여러 번 포함): 30초
   - `result.reconcile-interval`(ActivityRecorded 누락 점검 주기, FR2.3): 5분, 기동 때 1회 더
   - `report.unsaved-retention`(저장 안 된 READY · FAILED 리포트 정리, FR10.2): 24시간
   - 리포트 시도 시한은 따로 두지 않고 `report.ready-target`(60초)의 남은 예산으로 나눈다(Q3 A, FR9.3).
   - 결과 · 리포트 AI 호출 1회 시한: `text-ai.result.request-timeout` 10초(U5a 상황 생성 3초와 별도)
B. A와 같되 시도 시한을 짧게(15초), AI 호출 시한을 5초로 한다.
C. A와 같되 저장 안 된 리포트를 1시간 뒤 정리한다.
X. Other (please specify)

[Answer]: A

## Q3. 미실시 칩 사유 사상표 초안 (U5b와 맞출 안건)

U5b가 채울 `record.practice` 값을 27의 미실시 사유(시간 종료 · 상황 준비 실패 · 활동 끝 · 반응 없음, ai-io 6.2의 TIME_OVER · SCENARIO_FAILED · ACTIVITY_ENDED · NO_RESPONSE)로 바꾸는 표가 없다(요구사항 FR4.2 · 열린 질문).
이 표는 U6 설계에 초안으로 적고, U5b 담당과 맞춘 결과로 고친다.

A. 다음 초안으로 한다. (추천)
   - 목표 status `NOT_REACHED`(종료로 그 목표에 못 감) → 장면 둘 다 ACTIVITY_ENDED
   - 목표 status `SKIPPED` + skipReason이 상황 생성 실패 → SCENARIO_FAILED
   - 반응 `responseType` 있음 · label `NOT_PERFORMED` + notPerformedReason이 시간 종료 → TIME_OVER, 무응답(20초) → NO_RESPONSE, 그 밖 → ACTIVITY_ENDED
   - 장면 2(NEW_SCENARIO)가 노출되지 않음 → ACTIVITY_ENDED(ai-io 6.2 "출력에 없는 장면 = null")
   - 알 수 없는 값은 ACTIVITY_ENDED로 두고 매핑 로그(코드값만)를 남긴다.
B. 사상표를 두지 않고 notPerformedReason 문자열을 그대로 재판정 입력으로 넘겨 AI가 사유를 고르게 한다.
C. U5b와 맞출 때까지 미실시 칩은 사유 없이 "미실시"만 보인다.
X. Other (please specify)

[Answer]: A

## Q4. 리포트 AI 입력의 인용 후보 상한 N (요구사항 검토 R-03)

리포트 AI 항목(2 · 5 · 6 · 10)은 서버가 인용 후보를 골라 보낸다(FR9.4). 「전체」 범위에서 활동이 많으면 입력이 커져 60초 마감과 AI 입력 한도를 넘을 수 있다.

A. 항목마다 인용 후보 최대 30개. 고르는 순서는 최근 활동부터, 활동당 최대 5개(내용 있는 발화 우선)다. 넘친 활동은 집계 항목에만 들어간다. (추천)
B. 항목마다 최대 60개, 활동당 제한 없음.
C. 리포트 전체에서 최대 30개를 한 번 고르고 네 항목이 나눠 쓴다.
X. Other (please specify)

[Answer]: A

## Q5. 정리 상태가 다른 활동을 리포트 항목에 넣는 방식 (요구사항 검토 R-09)

28 미리보기는 정리 상태와 무관하게 끝난 활동을 모두 보이기로 했다(가정 G9). 그런데 활동마다 결과 작업 상태가 다를 수 있다: 정리 대상 아님(NONE) · 정리 중(REQUESTED · RUNNING) · 정리 실패(FAILED) · 정리 끝(DONE).

A. 항목 종류별로 나눈다. (추천)
   - 활동 기록만 쓰는 집계 항목(1 기간 · 활동 수, 4 · 7 · 8 등)과 전사 인용: 모든 고른 활동을 넣는다.
   - 결과 값을 쓰는 항목(3 · 9 칩, 5 구조화 출처 문장, 결과 문장): 정리 끝(DONE) 활동만 넣는다. 그 밖 활동은 그 항목에서 빠지고, 남은 것이 없으면 "자료 부족"이다.
   - 리포트 생성은 정리 중인 활동을 기다리지 않는다.
B. 정리 끝(DONE)인 활동만 리포트에 넣을 수 있다. 28에서 그 밖 활동은 체크할 수 없게 표시한다(미리보기에 "정리 중"/"정리 실패" 표시).
C. 고른 활동 중 정리 중인 것이 있으면 리포트 작업이 그 활동의 정리를 기다린다(60초 안에서).
X. Other (please specify)

[Answer]: A

## Q6. PDF 한글 글꼴 범위

PDF는 OpenHTMLtoPDF + Noto Sans KR(OFL)로 정했다(요구사항 Q6 A). 글꼴을 그대로 넣으면 core-api 이미지가 커지고, 너무 줄이면 전사 · 아이 이름의 드문 글자가 □로 나온다.

A. 현대 한글 음절 11,172자 전체 + 기본 라틴 · 숫자 · 문장 부호로 서브셋한다(Regular · Bold 두 벌, 합계 수 MB 예상). 정확한 크기는 Code Generation에서 재어 design.md에 적는다. (추천)
B. KS X 1001 완성형 2,350자로 서브셋한다(가장 작음). 없는 글자는 대체 문자로 나온다.
C. 서브셋하지 않고 원본 글꼴을 넣는다.
X. Other (please specify)

[Answer]: A

## Q7. 재시작 FAILED 활동의 26 · 27 표시 (요구사항 Q2 B, FR15)

진행 중 서버 재시작으로 끝난 활동을 26 · 27에 보이기로 했다. 17은 이미 이 활동을 '기기 응답 없이 끝냈어요'로 보인다(U4a-J16).

A. 26 줄 글은 연결 끊김과 같은 "중간에 끊김" 표기를 쓴다. 27 끝난 이유 칸 첫 줄은 17과 같은 문구('기기 응답 없이 끝냈어요')이고, 종료 확인은 "확인 안 됨"으로 둔다. 새 문구를 만들지 않는다. (추천)
B. 26 · 27 모두 새 문구 "서버 문제로 끝났어요"를 만든다(임시 문구로 금지 표현 검사 대상).
C. 26에는 보이지 않고 17의 「오늘 기록 보기」로 들어간 27에서만 보인다.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Q1 A: 결과 작업 실행기는 계정 삭제 · Outbox와 같은 방식으로 돌린다. 작업 · 시도를 DB 표에 두고, 주기 실행기가 SKIP LOCKED로 선점한다. 실패하면 다음 시도 시각을 적어 REQUESTED로 되돌린다. AI 호출은 트랜잭션 밖에서 하고, 실행 스레드는 2개다.
- Q2 A: 새 시간 값은 실행기 주기 1초, SESSION_RESULT 시도 시한 30초, 누락 점검 5분(기동 때 1회 더), 저장 안 된 리포트 정리 24시간, 결과 · 리포트 AI 호출 1회 시한 10초다. 리포트 시도는 60초 마감의 남은 예산으로 나눈다.
- Q3 A: 미실시 칩 사유 사상표 초안(NOT_REACHED → 활동 끝, SKIPPED + 상황 실패 → 상황 준비 실패, NOT_PERFORMED + 시간 → 시간 종료 / + 무응답 → 반응 없음, 장면 2 미노출 → 활동 끝, 모르는 값 → 활동 끝 + 코드값 로그)을 설계에 두고, U5b와 맞출 안건으로 넘긴다.
- Q4 A: 리포트 AI 항목마다 인용 후보는 최대 30개다. 최근 활동부터 활동당 최대 5개(내용 있는 발화 우선)를 고르고, 넘친 활동은 집계 항목에만 넣는다.
- Q5 A: 집계 항목과 전사 인용은 고른 활동 모두를 쓴다. 결과 값 항목(3 · 9 칩, 5 출처 문장, 결과 문장)은 정리 끝 활동만 쓰고, 남는 것이 없으면 자료 부족이다. 리포트는 정리 중 활동을 기다리지 않는다.
- Q6 A: PDF 글꼴은 Noto Sans KR을 현대 한글 11,172자 + 기본 라틴 · 숫자 · 문장 부호로 서브셋한다(Regular · Bold). 크기는 Code Generation에서 재어 design.md에 적는다.
- Q7 A: 재시작 FAILED 활동은 26에서 "중간에 끊김", 27 끝난 이유 칸에서 17과 같은 '기기 응답 없이 끝냈어요'와 종료 확인 "확인 안 됨"으로 보인다. 새 문구는 만들지 않는다.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
