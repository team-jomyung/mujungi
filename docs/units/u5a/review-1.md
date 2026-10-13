# U5a 조정자 리뷰 1차 (2026-10-12) — 반영 전

브랜치 `unit/u5a-practice-core`(Codex 구현, d25124e) 기준이다. 고칠 것 9건과 main을 합치면 생기는 문제 2건(A · B)이다. 2026-10-13 현재 아직 반영하지 않았다.

## 상황
- U5a 브랜치는 U2가 main에 들어오기 전(b9040da)에서 갈라졌다. 그래서 U2 코드 없이 임시 JDBC 어댑터로 버텼다.
- 병합 순서는 U4a(core V3) → U5a(core V4)다. Flyway `outOfOrder`가 꺼져 있어 V4가 먼저 들어가면 V3가 거절된다. V4 번호는 그대로 둔다.
- 순서: U4a(`unit/u4a-activity-core`)가 main에 합쳐진 뒤 U5a 브랜치에서 `git merge main` → 아래 A · B와 1 ~ 9를 고친다.

## main을 합칠 때
충돌은 FlywayConfIT · CoreSchemaIT의 표 개수, ConfigKeyContractTest, ErrorCode 근처에서 난다. 합친 뒤에 생기는 문제는 두 가지다.

- **A. 보호자 잠금 단일화**
  - U2의 아키텍처 시험(U2SingleSourceTest)은 보호자 행 잠금(core_guardian FOR UPDATE · FOR SHARE)을 GuardianWriteLock 한 곳에서만 하게 막는다.
  - JdbcPracticeAccountAdapter의 `select status from core.core_guardian ... for update`가 여기에 걸린다.
  - 임시 어댑터를 U2의 실제 부품으로 바꾼다. 잠금은 GuardianWriteLock의 인스턴스 메서드(lockActive · statusForUpdate)로, 아이 정보는 ChildProfileService의 find · get으로 한다(U4a에서 GuardianWriteLock 호출이 인스턴스 메서드로 통일됐다).
- **B. 계정 삭제 순서**
  - U2의 ChildProfileDeletionParticipant가 @Order(ORDER_CHILD_PROFILE = 300)에서 core_child를 지운다. 그런데 PracticeDeletionParticipant에는 @Order가 없어 맨 마지막에 돈다.
  - core_goal_recommendation · core_practice_goal_selection이 core_child를 ON DELETE RESTRICT로 물고 있다. 그래서 연습 기록이 있는 보호자는 계정 삭제가 아이 단계에서 FK 오류로 멈춘다.
  - DeletionParticipant에 ORDER_PRACTICE를 두고 붙인다. 값은 300보다 작고, 이미 쓰는 90(U4a 활동 기록) · 100 · 200 · 250과 겹치지 않게 한다(예: 150).
  - 목표 선택과 상황이 있는 보호자의 계정 삭제가 DeletionCompleted까지 가는 IT를 같이 둔다.

## 리뷰 9건 (파일:줄은 d25124e 기준)
1. **[교착]** PragmaticsKnowledge.pinVersion(52행) → KnowledgeJdbcRepository.lockVersion
   - 여러 보호자가 함께 쓰는 core_behavior_definition 행을 FOR UPDATE로 잡고, markPinned가 부를 때마다 UPDATE한다.
   - 다른 보호자들의 후보 등록(CandidateRegistry.register)과 C10 상황 생성이 같은 정의 행 앞에서 줄을 선다. 보호자 잠금과 순서가 엇갈리면 40P01 교착 → 500이 난다.
   - 고칠 방향: 정의 행은 FOR SHARE(또는 FOR KEY SHARE)로 읽고, used_at은 null일 때만 한 번 바꾼다(update … where used_at is null).
2. **[계약 밖 500]** ScenarioService 83행 근처
   - pinVersion이 던지는 IllegalArgumentException(없는 버전 · 초안 · 미완성 · 핀 실패)이 그대로 올라가 C10이 계약에 없는 500을 낸다.
   - 고칠 방향: CandidateRegistry처럼 VALIDATION_FAILED(맞으면 NOT_FOUND)로 바꾼다.
3. **[조용히 깨짐]** PracticeAccountPort
   - @FunctionalInterface인데 requireChild · lockForDeletion · childrenForDeletion은 SERVICE_NOT_READY를 던지는 default다. 람다로 바꿔 끼우면 삭제와 후보 등록이 조용히 503이 된다.
   - 고칠 방향: A에서 어댑터를 바꿀 때 추상 메서드로 만들거나 포트를 나눈다.
4. **[빈 상태가 404]** JdbcPracticeAccountAdapter.requireActive
   - 아이가 없으면 NOT_FOUND를 던져, 아이를 아직 등록하지 않은 보호자의 GET /api/goal-selections가 404다. 화면 설계(C.S-18.18)는 빈 상태를 보여 줘야 한다.
   - 고칠 방향: 목록 조회는 guardian_id만으로 읽고, 아이가 없으면 빈 목록을 돌려준다.
5. **[설정 규칙 위반]** 설정 키를 따로 읽음
   - u5a-practice.properties · u5a-ai.properties를 @PropertySource로 따로 읽고, ConfigKeyContractTest를 그 파일까지 보게 넓혔다.
   - U1 규칙(contracts/timing.md)은 시간 · 한도 키를 application.yml 한 곳에 두고 CoreProperties(또는 같은 방식의 타입)로 묶는 것이다.
   - 고칠 방향: 키를 application.yml로 옮기고, 시험은 application.yml 하나만 보게 되돌린다.
6. **[키가 실패로 굳음]** ScenarioService 115행 근처
   - DuplicateKeyException(다른 요청과의 경합)이나 이미 본 내용 재생성으로 2회를 다 쓰면 요청 키가 FAILED로 굳는다. 같은 키로 다시 요청해도 계속 422가 나온다.
   - 고칠 방향: 내용 검증 실패만 FAILED로 굳히고, 경합 · 기술 실패는 키를 풀어 다시 시도할 수 있게 한다.
7. **[멀쩡한 상황 거절]** TextAiGateway 92 · 97행
   - 모델이 준 partnerRoleText를 길이와 금지어로 검사하는데, 저장할 때는 ScenarioContentPolicy.PARTNER_ROLE 고정 문구로 바꿔 넣는다(ScenarioRepository 89행). 쓰지도 않는 값 때문에 정상 상황이 거절될 수 있다.
   - 고칠 방향: 고정 문구를 쓸 거면 검사(와 프롬프트 요구)에서 빼고, 모델 값을 쓸 거면 저장을 바꾼다. 어느 쪽이든 design.md에 이유 한 줄을 적는다.
8. **[잠금을 쥔 채 외부 확인]** CandidateRegistry 47행
   - CandidateEvidencePort.verifies를 보호자 FOR UPDATE와 정의 잠금을 쥔 트랜잭션 안에서 부른다. design.md의 '잠금을 쥔 채 외부 호출 금지'에 어긋난다.
   - 고칠 방향: 근거 확인을 트랜잭션 전에 하고, 트랜잭션 안에서는 그 결과만 쓴다.
9. **[정리]**
   - ScenarioContentPolicy.fingerprint의 trigger 인자는 쓰이지 않는다(지문이 장면만 본다). 의도면 인자를 빼고, 아니면 넣는다.
   - PragmaticsKnowledge.markUsed · requireActive와 KnowledgeJdbcRepository.markUsed(markPinned와 같은 SQL)는 부르는 곳이 없으니 지운다.

## 방법
- 건마다 먼저 실패하는 시험을 두고 고친다. 기존 단언과 커버리지 하한은 낮추지 않는다.
- 지적이 틀렸다고 보면 고치지 말고, 근거를 docs/units/u5a/report.md "리뷰 반영" 절에 남긴다.
- 끝나면 아래를 돌리고 report.md에 표로 남긴 뒤 `fix(u5a): …`로 커밋한다. 그다음 main으로 PR을 열어 CI가 초록인 것을 확인한다.
  - U5a 시험(-PcoverageScope=u5a는 :core-api 작업에만)
  - core-api 전체
  - 프런트 tsc · eslint · vitest(tests/u5a, tests/u1, tests/u2)
- V4가 기존 표에 제약을 더하면 CI 마이그레이션 검사에 걸린다. 이전 앱이 깨지지 않는 근거를 적어 `infra/migration-approvals.txt`에 한 줄을 더한다.
