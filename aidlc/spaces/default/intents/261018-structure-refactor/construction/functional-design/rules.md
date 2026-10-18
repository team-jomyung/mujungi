# 구조 · 이름 리팩토링 — 규칙 (rules)

> 작업 기록 `261018-structure-refactor` · Functional Design · 입력: 승인된 `requirements.md`, 질문 답(Q1 ~ Q5, 가정 8개), 팀 확인 `docs/refactor-structure-before-after.md`. 이 기록은 새 업무 규칙을 만들지 않는다. 구조를 바꾸는 **작업 규칙**만 정한다. 규칙 ID `BRx.y`는 `traceability.json`과 하위 단계(Code Generation 계획)가 그대로 부른다.

```yaml
rules:
  # ---- BR1 이동 · 이름 · 위치 ----
  - id: BR1.1
    statement: 파일 이동 · 이름 변경 · 클래스 추출과 로직 변경을 한 커밋에 섞지 않는다.
    category: constraint
    applies_to: 모든 단계의 커밋
    trigger: 커밋을 만들 때
    logic: IF 커밋이 파일을 옮기거나 이름을 바꾸거나 클래스에서 구역을 추출하면 THEN 그 커밋의 내용 변경은 (a) import · 경로 · 이름 참조 갱신, (b) 추출한 코드의 잘라 붙이기와 호출 지점을 새 객체 호출로 바꾸는 치환, (c) 그에 필요한 접근 범위 조정으로 한정한다. 동작을 바꾸는 편집은 별도 커밋이다. 이동 · 추출 커밋은 그 이름을 부르는 참조 갱신을 같은 커밋에 포함한다(BR4.1) — 참조를 나중 커밋으로 미루지 않는다.
    violation: 커밋을 나누어 다시 만든다. 한 커밋에서 이동과 로직 변경이 함께 보이면 리뷰에서 반려한다.
    source: NFR6, FR2.1
  - id: BR1.2
    statement: 파일 전체를 옮길 때는 git이 이름 변경으로 추적하도록 `git mv`로 한다.
    category: constraint
    applies_to: 프런트 · 백엔드 파일 전체 이동(클래스에서 구역을 떼어 내는 추출에는 적용하지 않는다 — 그 규칙은 BR1.1)
    trigger: 파일 위치를 바꿀 때
    logic: IF 파일 전체를 옮기면 THEN `git mv`를 쓰고 같은 커밋에서 파일 내용을 거의 바꾸지 않는다(이력 유사도 유지). 클래스 추출은 새 파일을 만드는 일이므로 `git mv`가 아니라 BR1.1의 추출 커밋 규칙을 따른다.
    violation: 이동을 삭제 + 신규로 만든 커밋은 다시 만든다.
    source: FR2.1, NFR6
  - id: BR1.3
    statement: 파일 · 폴더 · 클래스 이름에 화면 번호, 유닛 번호, 결정 ID를 쓰지 않고 기능 이름을 쓴다.
    category: policy
    applies_to: 프런트 화면 · 컴포넌트 · 훅 · 문구 파일, 백엔드 협력 객체와 패키지, 시험 폴더 · 범위 파일
    trigger: 새 이름을 정할 때
    logic: IF 이름 후보에 `Login01` 같은 화면 번호, `u5b` 같은 유닛 번호, `J123` 같은 결정 ID가 있으면 THEN 기능 이름으로 바꾼다. 화면 컴포넌트는 `<기능>Screen`, 상태 표시는 `<기능>View`, 훅은 `use<기능>Screen`, 요청은 기능 폴더의 `api.ts`, 문구는 `copy.ts`다.
    violation: 합격 기준 AC-1 검사가 실패한다.
    source: FR2.1, FR2.2, FR2.3, FR6.1, FR6.2
  - id: BR1.4
    statement: 코드의 위치는 쓰는 범위로 정한다.
    category: policy
    applies_to: 프런트 `parent` · `device` · `shared`
    trigger: 파일의 위치를 정할 때
    logic: IF 한 기능만 쓰면 THEN `features/<기능>/`에 둔다. IF 보호자 쪽 여러 기능이 쓰면 THEN `parent/common/{api,components,hooks,push}`에 둔다. IF 보호자와 기기가 함께 쓰면 THEN `shared`에 둔다. 요청은 기능 전용이면 그 기능의 `api.ts`, 공통 클라이언트 · CSRF · 쿼리 키 · 타입은 `parent/common/api`다.
    violation: 기준에 맞지 않는 위치는 리뷰에서 옮기게 한다.
    source: FR3.2, FR3.3, Q4 (편차 FD-D1)
  - id: BR1.5
    statement: 프런트 영역 경계 import 규칙을 유지한다.
    category: constraint
    applies_to: `/parent` · `/device` · `shared`
    trigger: import를 바꿀 때
    logic: IF 파일을 옮기면 THEN `parent` ↔ `device` 서로 import하지 않고, `shared`는 둘 중 어느 쪽도 import하지 않는다. ESLint 경계 규칙과 그 예외 파일 경로를 옮긴 위치에 맞춘다.
    violation: `eslint .`와 경계 시험(`eslint-boundary.test`)이 실패한다.
    source: NFR4, FR2.4 (R-F4)
  - id: BR1.6
    statement: 화면 설계 번호와 파일의 대응은 문서 하나에 둔다.
    category: policy
    applies_to: `docs/screen-map.md`
    trigger: 화면 파일 이름을 바꿀 때
    logic: IF 화면 파일의 이름에서 번호를 지우면 THEN 화면 설계 v1.5j의 번호 → 새 파일 경로 표를 `docs/screen-map.md`에 둔다. 최종 구조 설명도 `docs/`에 남긴다.
    violation: 대응표 없이는 단계 1을 닫지 않는다.
    source: FR1.2, FR8.1

  # ---- BR2 프런트 화면 책임 ----
  - id: BR2.1
    statement: 대상 화면 9개는 요청 · 오류 변환 · 폴링 · 화면 이동을 `use<기능>Screen` 훅이 맡고 화면 컴포넌트는 그리기와 입력 연결만 한다.
    category: constraint
    applies_to: `Live`, `Home`, `Done`, `Device`, `Child`, `Consent`, `StopExpressions`, `Free`, `DeviceRegister` 화면
    trigger: 단계 2에서 화면을 분리할 때
    logic: IF 화면 컴포넌트가 `router.*`, `fetch…`/`endSession` 같은 요청 함수, `catch`에서 오류를 분기하면 THEN 그 호출을 훅으로 옮긴다. 화면은 훅이 돌려준 값과 핸들러만 쓴다. 나머지 화면은 이동과 이름 변경만 한다.
    violation: 합격 기준 AC-3 검사가 실패한다.
    source: FR3.1
  - id: BR2.2
    statement: 오류 → 문구 변환은 기존 `shared/errors/codeToCopy` 한 길만 쓴다.
    category: constraint
    applies_to: 모든 프런트 화면 · 훅
    trigger: 오류를 화면 문구로 바꿀 때
    logic: IF 오류 code를 문구로 바꾸면 THEN `codeToCopy` + 문구 모듈을 쓰고 화면별 새 변환을 만들지 않는다.
    violation: 리뷰에서 반려한다.
    source: FR3.4
  - id: BR2.3
    statement: 기능의 `api.ts`는 서버 호출만 하고 화면 이동 · 문구 선택 · 상태 보관을 하지 않는다.
    category: constraint
    applies_to: 모든 기능 폴더의 `api.ts`와 `parent/common/api`
    trigger: 요청 코드를 옮길 때
    logic: IF 요청 코드에 이동 · 문구 · 컴포넌트 상태가 섞여 있으면 THEN 그 부분은 훅으로 옮기고 `api.ts`에는 요청과 응답 변환만 남긴다. 연습 요청 `parent/practice/{api,startApi}.ts`는 `features/practice/api.ts` 하나로 합친다.
    violation: 리뷰에서 반려한다.
    source: FR3.2

  # ---- BR3 Realtime 실행기 ----
  - id: BR3.1
    statement: 실행기에서 분리한 협력 객체는 모두 세션 실행기 스레드에서만 호출한다.
    category: constraint
    applies_to: `EndingFlow`, `SessionClocks`, `IdleClock`, `JoinFlow`, 흡수한 `JudgmentCoordinator`
    trigger: 협력 객체를 만들거나 호출할 때
    logic: IF 협력 객체를 만들면 THEN 새 스레드 · 공유 실행기 · 동기화 잠금을 만들지 않는다. 한 세션을 한 스레드로 처리하는 메일박스 모델을 그대로 둔다.
    violation: 스레드 모델 위반은 병합 불가이며 특성화 시험이 실패한다.
    source: NFR5, FR4.1
  - id: BR3.2
    statement: 구역 사이에서 공유하는 필드는 `SessionContext` 하나가 소유한다.
    category: constraint
    applies_to: `SessionExecutor` 머리 619줄(필드 · 생성자 · 내부 타입)과 끝의 "전이 보조" 약 282줄
    trigger: 단계 3에서 구역을 나눌 때
    logic: IF 두 구역 이상이 같은 필드를 읽고 쓰면 THEN 그 필드를 `SessionContext`로 옮기고 협력 객체는 생성자로 받는다. 한 구역만 쓰는 필드는 그 협력 객체로 옮긴다. 배정되지 않은 머리 · 전이 보조 구역은 단계 0.2의 필드 목록에서 소유자를 정한다. `SessionContext`는 **단계 3.0에서 첫 협력 객체보다 먼저** 만든다(필드와 접근자만 이동, 로직 변경 없음). 첫 협력 객체(`EndingFlow`)는 이미 있는 `SessionContext`를 받아 만든다.
    violation: 구역 사이를 정적 변수 · 숨은 참조로 공유하면 반려한다.
    source: FR4.1, FR4.4
  - id: BR3.3
    statement: 분리한 구역의 코드는 `SessionExecutor`에 남기지 않고, 실행기 상단에 처리 순서 요약(분배 표)을 둔다.
    category: constraint
    applies_to: `SessionExecutor.java`
    trigger: 단계 3.1 ~ 3.5가 끝날 때
    logic: IF 협력 객체로 옮기는 구역(끝내기 · 시계 · 공백 시계 · 참여 확인 · 판정)이 끝나면 THEN 그 구역의 코드가 `SessionExecutor`에 남지 않고, 각 분배는 협력 객체 한 번 호출로 끝난다. 실행기 상단에 처리 순서 요약(분배 표)을 둔다. 줄 수 상한은 AC-4의 숫자로 판정한다(3.4 뒤 3,100줄 이하). 게이트(약 274줄)와 연습 확장점(약 1,454줄)은 BR3.4 · BR3.5에 따라 이번 합격 기준에서 실행기에 남을 수 있다. "남는 것은 루프 · 입구 · 분배 · 재전달 · 예외뿐"이라는 최종 상태는 3.7(연습 확장점 분리)까지 한 경우의 목표일 뿐 이번 합격 기준이 아니다.
    violation: 합격 기준 AC-4 검사(줄 수 상한)가 실패한다.
    source: FR4.3
  - id: BR3.4
    statement: 구역은 정해진 순서로 한 협력 객체당 커밋 묶음 하나씩 나눈다.
    category: policy
    applies_to: 단계 3
    trigger: 구역을 나눌 때
    logic: IF 단계 3이 시작되면 THEN 먼저 `SessionContext`를 만들고(3.0) 이어서 끝내기(`EndingFlow`) → 시계(`SessionClocks`) → 공백 시계 · 기술 일시정지(`IdleClock`) → 참여 확인(`JoinFlow`)과 판정 턴 흡수(`JudgmentCoordinator`) 순서로 나눈다(협력 객체 다섯). 게이트 구역은 `GuardrailCoordinator`와의 경계만 설계서에 적고 옮기는 범위는 그 단계에서 정한다.
    violation: 순서를 바꾸려면 설계서를 먼저 고친다.
    source: FR4.2, FR4.5, Q2
  - id: BR3.5
    statement: 연습 확장점 구역(약 1,454줄)은 앞 결과를 평가한 뒤 마지막 단계에서 나눌지 정한다.
    category: policy
    applies_to: 연습 확장점 구역
    trigger: 단계 3의 협력 객체 다섯(3.1 ~ 3.4)과 3.5 정리가 끝났을 때
    logic: IF 앞 협력 객체 분리 뒤 `verify.sh`와 기존 시험이 통과하고 사람이 계속하자고 하면 THEN 연습 확장점 분리를 별도 설계 결정으로 연다. 아니면 이 기록에서 하지 않는다.
    violation: 결정 없이 이 구역을 옮기면 반려한다.
    source: FR4.6, Q2
  - id: BR3.6
    statement: 새로 나뉜 클래스에도 시간 · 로그 · 오류 처리 관례를 그대로 적용한다.
    category: constraint
    applies_to: 새 협력 객체와 설정 레코드
    trigger: 클래스를 만들 때
    logic: IF 새 클래스가 시각을 쓰면 THEN `MujoongClock` 주입으로만 읽고 숫자 시간 값을 코드에 쓰지 않는다(`TimeLiteralScanTest` 대상). 오류 기록은 `EventLog`로 한다.
    violation: `TimeLiteralScanTest` · ArchUnit 시험이 실패한다.
    source: NFR1, NFR4 (R-F6, R-F7)

  # ---- BR4 이름에 묶인 참조 ----
  - id: BR4.1
    statement: 이름이나 위치를 바꾼 같은 커밋에서 그 이름을 문자열로 부르는 곳을 모두 고친다.
    category: constraint
    applies_to: ArchUnit 규칙의 패키지 문자열, `SingleInstanceMarkerTest`의 `REALTIME_SHARE`(SI-2 · SI-9), `TimeLiteralScanTest` 허용 목록, `ConfigKeyContractTest`, 커버리지 범위 파일, ESLint glob · 예외 파일, `vitest.config.ts`, `scripts/verify/a-checks.sh`, `contracts/` · `docs/`의 경로, 이름으로 읽는 자원 파일(`backend/core-api/src/main/resources/textai/u5a-forbidden-patterns.txt`, `backend/realtime-activity/src/main/resources/practice/u5b-independent.yml`, `…/practice/u5b-runtime.yml`, `contracts/u14-compatibility.md`), 이동 대상 파일을 import하는 프런트 시험(`frontend/tests/u*`의 47개 파일)
    trigger: 파일 · 패키지 · 클래스를 옮기거나 이름을 바꿀 때
    logic: IF 이름을 바꾸면 THEN 코드 스캔의 "이름 · 경로를 문자열로 부르는 곳" 표(단계 0 점검표)를 기준으로 해당 참조를 같은 커밋에서 고친다. 점검표에 없는 참조가 시험으로 드러나면 점검표에 더한다.
    violation: 해당 시험 · 빌드 · CI가 실패하며 그 커밋은 닫지 않는다.
    source: FR1.3, FR2.4, FR4.7, FR5.1
  - id: BR4.2
    statement: 커버리지 범위 파일은 이름만 바꾸고 가리키는 대상 집합은 그대로 둔다.
    category: constraint
    applies_to: `backend/coverage-scope/*.txt` 18개, `frontend/coverage-scope/*.json` 8개
    trigger: 범위 파일 이름을 바꾸거나 대상 경로가 이동할 때
    logic: IF 범위 파일 이름이 바뀌면 THEN 대상 파일 목록의 의미를 유지하고 이동한 경로로만 고친다. 빈 목록 · 없는 파일이면 측정이 실패하는 규칙(J137)은 유지한다. 줄 80% 하한은 낮추지 않는다.
    violation: 범위 측정 실패 또는 하한 미달.
    source: FR6.2, NFR3
  - id: BR4.3
    statement: Flyway 마이그레이션 파일명과 이미 `main`에 있는 마이그레이션 내용은 바꾸지 않는다.
    category: constraint
    applies_to: `backend/**/db/migration/**`
    trigger: 유닛 번호가 든 이름을 바꿀 때
    logic: IF 유닛 번호 이름 변경 범위(Q2 "전부")에 마이그레이션 파일(`V7__u12_….sql`)이 걸리면 THEN 제외한다. 적용 이력과 묶여 있어 바꾸면 안 된다.
    violation: 마이그레이션 검증 · 배포가 깨진다. 병합 불가.
    source: NFR1 (R-M2)
  - id: BR4.4
    statement: CI 작업 이름 · 인자 · 경로를 바꾸면 `ci-gate`의 구성과 같은 커밋에서 맞춘다.
    category: constraint
    applies_to: `.github/workflows/ci.yml`의 `u5b-scope` 같은 작업, `-PincludeTags`, `-PcoverageScope`, `hashFiles`, `ci-gate`의 `needs`
    trigger: 시험 태그 · 범위 파일 · 시험 폴더 이름을 바꿀 때
    logic: IF 이름이 바뀌면 THEN 작업 이름, 시험 선택 인자, `hashFiles` 경로, `ci-gate` `needs` 목록을 같은 커밋에서 고친다. 바뀐 작업 이름과 `ci-gate` 구성을 문서에 남긴다(원격 `main`에 지금은 브랜치 보호가 없다). `hashFiles(...) != ''` 조건 단계는 파일 이름이 틀리면 오류 없이 건너뛰어지므로, 이름 변경 뒤 첫 CI 실행에서 그 단계가 건너뛰기가 아니라 성공으로 끝났는지 실행 기록에서 확인한다(합격 기준 AC-9).
    violation: CI 작업이 실행되지 않거나 `ci-gate`가 잘못 통과한다.
    source: FR6.3, FR6.4
  - id: BR4.5
    statement: Java 클래스 이름에서 유닛 번호를 뺄 때 같은 패키지에서 이름이 겹치면 번호 자리에 기능 이름을 넣어 구분하고 충돌 목록을 남긴다.
    category: constraint
    applies_to: 이름에 `U<번호>`가 든 Java 파일 103개(`U<번호>` 접두 70개, 이름 중간에 번호가 든 것 33개, 그중 운영 클래스 1개 `U2PracticeAccountAdapter`), 패키지 이름
    trigger: 새 이름이 같은 패키지의 기존 클래스 또는 같이 바뀌는 클래스와 같아질 때
    logic: IF `U<번호>` 토큰을 뺀 이름이 같은 폴더의 기존 클래스나 같이 바뀌는 다른 클래스와 겹치면(현재 20건: 예 `U14ResultApiIT`와 `ResultApiIT`, `U2Fixtures`와 `U8Fixtures`, `SeedU4aReferenceSqlIT`와 `SeedU8ReferenceSqlIT`, `SessionInternalControllerU3Test`와 `…U4bTest`) THEN 번호 토큰 자리에 유닛 번호 → 기능 이름 대응표(functional-spec.md 3.1절)의 파스칼 케이스 기능 이름을 넣는다(예 `LiveContentFixtures`, `ParentAccountFixtures`). 겹치지 않는 이름은 토큰만 뺀다. 충돌 목록은 단계 5.3의 첫 커밋에서 스크립트로 만들어 설계 부록으로 남긴다. 같은 클래스를 덮어쓰지 않는다.
    violation: 시험이 사라지거나 덮이면 반려한다(단계 전후 시험 수 대조로 확인).
    source: FR5.1, FR6.1, Q2
  - id: BR4.6
    statement: 설정 레코드를 쪼개도 설정 키 이름과 바인딩 prefix는 그대로다.
    category: constraint
    applies_to: `RealtimeProperties`(570줄), `CoreProperties`(589줄)
    trigger: 단계 4에서 설정 레코드를 기능별 파일로 나눌 때
    logic: IF 중첩 record를 별도 파일로 옮기면 THEN `@ConfigurationProperties` prefix와 키 이름을 바꾸지 않고 `ConfigKeyContractTest`가 그대로 통과해야 한다.
    violation: 설정 키 대조 시험이 실패한다.
    source: FR5.2, NFR1
  - id: BR4.8
    statement: 유닛 번호 → 기능 이름은 대응표 하나를 따른다.
    category: policy
    applies_to: 범위 파일, 시험 태그, 프런트 · tools 시험 폴더, CI 작업 · 인자, Java 클래스, 자원 파일
    trigger: 유닛 번호가 든 이름을 바꿀 때
    logic: IF 유닛 번호가 든 이름을 바꾸면 THEN functional-spec.md 3.1절의 대응표(예 `u5b` → `practice-legacy`, `u12` → `practice-flow`)로만 정한다. 표에 없는 번호가 나오면 표에 먼저 더한다. 횡단 묶음(`u1`, `u5b-core`)도 표의 행을 따른다.
    violation: 표와 다른 이름은 반려한다.
    source: FR6.1, FR6.2, FR6.3
  - id: BR4.9
    statement: 프런트 범위 시험은 폴더가 아니라 시험 파일 목록으로 고르고, 옮기기 전과 같은 시험이 도는지 시험 수로 확인한다.
    category: constraint
    applies_to: 프런트 CI의 `u5b`(→ `practice-legacy`)와 `u7`(→ `web-push`) 범위, `frontend/coverage-scope/*.json`
    trigger: 단계 5.1에서 `tests/u*` 폴더를 소스 구조 폴더로 옮길 때
    logic: IF 범위 시험이 `tests/u5b`·`tests/u7` 폴더 전체였다면 THEN 옮긴 뒤에는 범위마다 시험 파일 경로 목록 `frontend/coverage-scope/<이름>.tests`(한 줄에 하나)를 두고 CI가 `vitest run`에 그 목록을 넘긴다(폴더나 glob 추측 금지). 목록은 옮기기 전 폴더의 시험 파일을 그대로 옮긴 위치로만 적는다. 옮긴 뒤 같은 범위를 돌려 시험 파일 수와 시험 수가 옮기기 전과 같아야 한다(`practice-legacy` 3개 파일 32개 시험, `web-push` 6개 파일 89개 시험, `main` 570a625 기준). 목록이 비었거나 없는 경로가 있으면 CI 단계는 실패한다(J137과 같은 방식).
    violation: 빈 실행이거나 시험 수가 다르면 단계 5.1을 닫지 않는다.
    source: FR6.1, FR6.2, FR6.3, NFR3 (검토 R-11)
  - id: BR4.7
    statement: `docs/`의 이력 문서(`docs/units/*` 등)의 옛 경로는 고치지 않고 새 문서에서 대응표로 안내한다.
    category: policy
    applies_to: `docs/units/*` 이력 문서
    trigger: 경로가 바뀔 때
    logic: IF 이력 문서가 옛 경로를 가리키면 THEN 본문을 고치지 않고 `docs/screen-map.md`와 구조 문서의 이전 → 이후 대응표로 안내한다. 코드에서 읽는 문서(`contracts/` 등 시험이 대조하는 것)는 BR4.1대로 고친다.
    violation: 해당 없음(정책).
    source: FR8.2 (요구사항 OQ2 기본값, 팀이 이력 보존으로 승인)

  # ---- BR5 검증 ----
  - id: BR5.1
    statement: 단계(커밋 묶음)가 끝날 때마다 바꾼 쪽 검사와 `verify.sh`를 돌린다.
    category: constraint
    applies_to: 모든 단계
    trigger: 단계의 마지막 커밋 뒤
    logic: IF 단계가 끝나면 THEN 바꾼 쪽 검사(백엔드 `./gradlew clean check -Pmujoong.test.db=docker`, 프런트 `eslint` · `vitest --coverage` · `next build`, tools `vitest`)와 `bash scripts/verify.sh`가 모두 통과해야 다음 단계로 간다.
    violation: 통과할 때까지 다음 단계로 가지 않는다. 실패 원인을 이 기록의 변경으로 단정하지 않고 이전 `main`에서도 같은지 먼저 본다(최근 `main`의 `skeleton` 실패 이력이 있다).
    source: NFR2, NFR1, Q3
  - id: BR5.2
    statement: 기존 시험의 단언 · 커버리지 하한 · 계약 대조 시험 · ArchUnit 시험은 낮추거나 끄거나 건너뛰지 않는다.
    category: constraint
    applies_to: 모든 시험
    trigger: 이름 변경으로 시험이 깨질 때
    logic: IF 시험이 깨지면 THEN 이름 · 경로 · 패키지 참조만 고친다. 단언을 바꾸거나 `@Disabled` · `it.skip` · `-PexcludeTags`로 건너뛰지 않는다. 시험 수는 단계 전후에 같아야 한다(이름 변경 제외).
    violation: 반려한다.
    source: NFR2, NFR3 (R-F2)
  - id: BR5.3
    statement: 실행기를 나누기 전에 처리 순서 7구간마다 현재 동작을 고정하는 시험이 있는지 확인하고 없으면 더한다.
    category: constraint
    applies_to: 단계 0 · 단계 3
    trigger: 단계 3을 시작하기 전
    logic: IF 7구간(메시지 수신 · 참여 확인 · 판정 · 연습 확장점 · 게이트 · 끝내기 · 시계) 중 고정 시험이 없는 구간이 있으면 THEN 그 구간의 현재 동작을 고정하는 시험을 더한다. 이미 있는 시험(`SessionExecutor`를 직접 부르는 약 30파일)이 덮으면 구간 ↔ 시험 대응표만 만든다. 새 시험은 이 구간 고정 시험에 한한다.
    violation: 고정 시험 없이 단계 3을 시작하지 않는다.
    source: FR1.1, NFR2
  - id: BR5.4
    statement: 단계마다 기계로 확인하는 합격 기준을 통과해야 단계를 닫는다.
    category: constraint
    applies_to: 모든 단계
    trigger: 단계를 닫을 때
    logic: IF 단계가 끝나면 THEN functional-spec.md의 합격 기준 표(AC-1 ~ AC-9)에서 그 단계 몫을 명령으로 확인한다. 열린 목록("등")은 확정 목록 또는 grep · glob 조건으로 정한다.
    violation: 합격 기준 미달이면 단계를 닫지 않는다.
    source: FR1.1, FR3.1, FR4.3 (검토 R-03)
  - id: BR5.5
    statement: 커버리지 하한(모듈 전체 줄 80%, U5b · U7 범위 80%)을 유지한다.
    category: constraint
    applies_to: 백엔드 JaCoCo, 프런트 Vitest v8
    trigger: 새 클래스가 생기거나 이동할 때
    logic: IF 새 협력 객체의 커버리지가 모자라면 THEN 시험을 더하거나 차이를 보고한다. 하한 설정은 바꾸지 않는다.
    violation: CI와 `verify.sh`가 실패한다.
    source: NFR3
  - id: BR5.6
    statement: 시험 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기를 구분한다.
    category: policy
    applies_to: Build and Test 보고
    trigger: 결과를 적을 때
    logic: IF 결과를 적으면 THEN 이 기록의 시험은 기존 자동 시험과 `verify.sh`(가짜 공급자)뿐임을 밝히고 대역 성공을 실제 성공으로 표시하지 않는다.
    violation: 반려한다.
    source: NFR8 (R-M11)
  - id: BR5.7
    statement: 새 파일 · 문서 · 시험에 비밀 값과 전사 원문을 쓰지 않는다.
    category: constraint
    applies_to: 모든 산출물
    trigger: 파일을 쓸 때
    logic: IF 파일을 쓰면 THEN 비밀 값 · 실제 도메인 · 전사 원문을 쓰지 않는다. 비밀 스캔(`a-secrets.sh`)이 통과해야 한다.
    violation: 비밀 스캔이 실패한다.
    source: NFR9 (R-F3, R-F14)

  # ---- BR6 주석과 팀 규칙 문서 ----
  - id: BR6.1
    statement: 주석 정리는 주석 규칙 개정이 학습 절차로 기록된 뒤에만 한다.
    category: constraint
    applies_to: 단계 6
    trigger: 단계 6을 시작할 때
    logic: IF `team.md` 주석 규칙 개정이 §13 학습 절차로 기록되었으면 THEN 단계 6을 실행한다. 아니면 단계 6을 건너뛰고 주석은 옮기기만 한다.
    violation: 기록 전에 주석을 지우면 반려한다.
    source: FR7.2 (검토 R-02)
  - id: BR6.2
    statement: 주석 규칙 개정을 사람이 승인하지 않으면 단계 6은 하지 않고 이유를 기록한다.
    category: policy
    applies_to: 단계 6
    trigger: 학습 절차에서 개정이 거절될 때
    logic: IF 개정이 거절되면 THEN 단계 6을 생략하고 Build and Test 요약에 "단계 6 생략"을 적는다. 다른 단계의 합격에는 영향이 없다.
    violation: 해당 없음.
    source: FR7.1, FR7.2
  - id: BR6.3
    statement: `team.md` 규칙 4개는 학습 절차(§13)로만 고친다.
    category: constraint
    applies_to: "프런트 화면 파일은 번호를 붙인다", "`tests/<유닛>/` 폴더와 유닛 태그", "코드 주석은 결정 ID를 인용", 커버리지 범위 파일 이름 규칙
    trigger: 해당 변경이 병합될 때
    logic: IF 변경이 병합 직전이면 THEN 네 규칙을 한 번에 학습 절차 선택지로 올린다. 규칙 파일을 직접 편집하지 않는다.
    violation: 직접 편집은 반려한다.
    source: FR7.1 (검토 R-01)
  - id: BR6.4
    statement: 이번 작업에서 건드린 파일의 주석만 정리한다.
    category: constraint
    applies_to: 단계 6
    trigger: 주석을 정리할 때
    logic: IF 이 기록의 커밋이 건드린 파일이면 THEN "무엇을 왜"만 남기고 결정 ID는 필요할 때 한 줄 문서 링크로 줄인다. 건드리지 않은 파일은 일괄 정리하지 않는다.
    violation: 건드리지 않은 파일의 주석 대량 수정은 반려한다.
    source: FR7.2

  # ---- BR7 브랜치 · 롤백 ----
  - id: BR7.1
    statement: 작업은 통합 브랜치 `claude/refactor-structure`에서만 하고 `main`에 직접 올리지 않는다.
    category: constraint
    applies_to: git 작업
    trigger: 커밋 · push 할 때
    logic: IF push하면 THEN `claude/refactor-structure`에만 한다. 끝에 PR 하나를 merge commit으로 합치고 `ci-gate`를 통과시킨다.
    violation: `main` 직접 push는 금지한다.
    source: NFR6, NFR7, Q1
  - id: BR7.2
    statement: 단계가 끝날 때마다 원격 `main`을 브랜치로 merge해 따라잡는다.
    category: policy
    applies_to: 통합 브랜치
    trigger: 각 단계 시작 전
    logic: IF 원격 `main`이 앞서 있으면 THEN `main`을 브랜치로 merge하고 충돌을 푼 뒤 `verify.sh`를 돌린다. 리베이스로 이력을 다시 쓰지 않는다.
    violation: 따라잡지 않은 채 큰 이동을 이어 가지 않는다.
    source: NFR6, 위험(통합 브랜치 충돌)
  - id: BR7.3
    statement: 롤백 기준점은 원격 `snapshot/main-pre-refactor-20261018`(570a625)과 PR 하나 되돌리기다.
    category: constraint
    applies_to: 롤백
    trigger: 문제가 생겨 되돌릴 때
    logic: IF 문제가 생기면 THEN 병합 전에는 `main`을 건드린 것이 없고, 병합 뒤에는 merge commit PR 하나를 되돌린다. 그보다 큰 문제는 snapshot 브랜치 기준으로 복원한다.
    violation: snapshot 브랜치를 삭제하거나 덮어쓰지 않는다.
    source: NFR7
```

## 규칙 요약

| 묶음 | 규칙 | 한 줄 요약 |
|---|---|---|
| BR1 이동 · 이름 · 위치 | BR1.1 ~ BR1.6 | 이동과 내용 분리, `git mv`, 기능 이름, 쓰는 범위로 위치 결정, 경계 유지, 화면 대응 문서 |
| BR2 프런트 화면 책임 | BR2.1 ~ BR2.3 | 대상 화면 9개 훅 분리, 오류 문구 한 길, `api.ts`는 서버 호출만 |
| BR3 Realtime 실행기 | BR3.1 ~ BR3.6 | 실행기 스레드 모델, `SessionContext`, 분배만 하는 실행기, 나누는 순서, 연습 확장점은 마지막 결정, 시간 · 로그 관례 |
| BR4 이름에 묶인 참조 | BR4.1 ~ BR4.9 | 같은 커밋에서 참조 갱신, 범위 파일 의미 유지, Flyway 불변, CI 동기화(건너뛰기 확인 포함), 이름 충돌, 설정 키 불변, 이력 문서 보존, 유닛 번호 → 기능 이름 대응표, 프런트 범위 시험은 파일 목록 + 시험 수 대조 |
| BR5 검증 | BR5.1 ~ BR5.7 | 단계마다 `verify.sh`, 단언 불변, 특성화 시험, 합격 기준, 커버리지, 증거 구분, 비밀 금지 |
| BR6 주석 · 규칙 문서 | BR6.1 ~ BR6.4 | 주석 규칙 기록 뒤에만 정리, 거절 시 생략, `team.md`는 학습 절차로만, 건드린 파일만 |
| BR7 브랜치 · 롤백 | BR7.1 ~ BR7.3 | 통합 브랜치 + PR 하나, 단계마다 `main` 따라잡기, 롤백 기준점 |
