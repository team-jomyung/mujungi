# U14 V9 기존 제약 변경 검토

## 검토 대상과 시험

파일: backend/core-api/src/main/resources/db/migration/core/V9__u14_goal_result.sql. 기존 V1~V8은 수정하지 않았다. 새 후보/버전/선택 표와 nullable 장면 확장 열을 추가한다. 아래 문장은 기존 표의 허용 집합을 확대하며 구 앱이 쓰던 값을 모두 계속 허용한다. 구 앱은 새 목표와 AFTER_REASK를 해석하지 못하므로 새 데이터 작성 뒤 구 앱 롤백의 읽기 호환까지 보장하지 않는다.

U14MigrationIT PostgreSQL 9개 통과, 실패/오류/skip 0. 실제 migration 적용, 네 제약의 정의·legacy FK 보존·nullable 새 열·활성 후보 유일 index·4회 허용과5회 거절을 검증했다. 별도 PostgreSQL에 Flyway target8을 적용하고 기존 job/attempt/result/scene/recommendation/selection을 만든 뒤 V9로 올려 모든 기존 행·열 값을 비교했다. 이후 기존 값/열만 사용하는 재시도·판정·선택 UPDATE도 성공했다. infra-validate --only migrations는 아래 8문장에 대해 실제 사람 확인이 없어 실패했다. 승인자 이름을 임의 기입하지 않았다.

## 승인 검토 문장

| 번호 | 검사기 해시 | SQL/호환 근거 |
|---|---|---|
| 6 | 4729cbbfec9b | core_result_job_attempt_count_check 제거. 같은 트랜잭션의7번에서0~4로 다시 정의한다. 원래0~3은 부분집합이다. |
| 7 | 028351315be9 | attempt_count BETWEEN 0 AND 4. 기존 앱의0~3 쓰기/행 모두 유효하다. |
| 8 | 127f5af60b59 | core_result_job_attempt_attempt_no_check 제거.9번에서1~4로 다시 정의한다. |
| 9 | 5227df94d846 | attempt_no BETWEEN 1 AND 4. 기존 앱의1~3은 계속 유효하다. |
| 10 | 59e95e8ebc3b | core_scene_judgment_scene_no_check 제거.11번에서양수로 다시 정의한다. |
| 11 | d25ac0dc3b5e | scene_no>0. 기존 앱이 쓰는1/2는 계속 유효하고 데이터 삭제/재번호 없음. |
| 12 | ba94e2165a0a | core_scene_judgment_timing_check 제거.13번에서원래값+AFTER_REASK로 다시 정의한다. |
| 13 | c09101542692 | timing IN ('IMMEDIATE','AFTER_REASK','AFTER_HELP'). 기존 IMMEDIATE/AFTER_HELP/NULL은 모두 계속 허용. |

2026-10-17 채팅 사용자가 위8문장을 명시 승인했다. 부모가 실제 답변을 AI-DLC에 기록하고 infra/migration-approvals.txt에 승인자 '채팅 사용자'와 날짜·8개 해시·호환 근거를 기입했다. 이후 실제 infra-validate --only migrations PASS(rc0)를 확인했다. 위 실패는 승인 전 이력이며 최종 승인 후 검사는 통과했다. SQL이 바뀌면 번호/해시를 다시 계산하고 재검토한다.
