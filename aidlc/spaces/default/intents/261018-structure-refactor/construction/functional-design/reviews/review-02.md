## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-18T08:57:00Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Critical | aidlc/spaces/default/intents/261018-structure-refactor/construction/functional-design/functional-spec.md > 5절 AC-2 및 rules.md BR4.7 | AC-2 명령(`git ls-files ｜ grep -E '(^｜/)(u[0-9]+[ab]?(-core)?)(.｜/)'`)을 실제 main(570a625)에서 돌리면 178건이 나온다. 그중 53건이 `docs/units/u*` 이력 문서인데 BR4.7은 이 문서를 고치지 않는다고 정했으므로 기대값 0은 규칙을 지키면 영원히 달성되지 않는다. 반대로 같은 정규식은 `backend/core-api/src/main/resources/textai/u5a-forbidden-patterns.txt`, `realtime-activity/src/main/resources/practice/u5b-independent.yml`, `u5b-runtime.yml`, `contracts/u14-compatibility.md`(모두 하이픈 · 밑줄 뒤 이름)를 잡지 못하고, Java 클래스 이름(`U4aActivityApiIT` 등)과 `@Tag("u..")` 값도 경로 검사가 아니라 보지 못한다. 합격 기준이 어떤 이름이 이동 대상인지 정의하지 못한다. | AC-2를 대상 확정 목록(프런트 tests/u*, tools/tests/u*, 범위 파일 26개, 위 리소스 3개, contracts 1개)과 `docs/units` 제외 조건으로 다시 쓰고, Java 클래스 이름과 태그는 별도 AC(예: 파일 이름 `U[0-9]` 패턴 0건, `@Tag("u` 0건)로 추가한다. 리소스 3개와 contracts 파일의 처리(이동 또는 유지)를 BR에 명시한다. | Resolved (reviewer) |
| R-02 | Critical | functional-spec.md > 단계 3 순서 3.1 ~ 3.5 및 rules.md BR3.2 · BR3.4 | BR3.2는 구역 사이 공유 필드를 `SessionContext`가 소유하고 협력 객체가 생성자로 받는다고 정했다. 그런데 `SessionContext`를 만드는 작업이 어느 순서에도 없고, 머리 619줄 · 전이 보조의 소유자 배정은 맨 마지막 3.5(정리)에 있다. 첫 분리 3.1 `EndingFlow`(504줄)는 공유 필드를 받을 컨텍스트 없이 만들어질 수 없다. 순서가 스스로 순환한다(협력 객체는 컨텍스트가 필요하고, 컨텍스트는 협력 객체 분리 뒤에 배정). 단계 0.2의 필드 소유자 표가 이를 푼다고 해도 `SessionContext` 클래스를 언제 만들고 어떤 커밋에서 필드를 옮기는지 단계가 없다. | 단계 3에 선행 항목 3.0(`SessionContext` 신설과 0.2 표의 공유 필드 이전, 동작 불변)을 더하고, 3.5는 남은 머리 · 전이 보조의 배정으로 좁힌다. ENT-004와 다이어그램의 컨텍스트 생성 시점을 맞춘다. | Resolved (reviewer) |
| R-03 | Major | functional-spec.md > 5절 AC-4 및 rules.md BR3.3 | 줄 범위로 계산하면 3.1 ~ 3.5가 끝나도 `SessionExecutor`에는 4,361줄 중 끝내기 504, 시계 184, 공백 시계 252, 참여 확인 78, 판정 260을 뺀 약 3,083줄이 남는다. 연습 확장점 1,454줄과 게이트 274줄은 3.6 · 3.7이 조건부라서 실행기에 그대로 있어, BR3.3의 "남는 것은 실행 루프 · 외부 입구 · 메시지 분배 · 재전달 · 최상위 예외뿐"과 "각 분배는 협력 객체 한 번 호출"이 단계 3 종료 시점에 성립하지 않는다. AC-4의 기대값도 "빠진 값 이하"로 순환 정의이고 숫자가 없어 기계로 확인할 수 없다. | AC-4에 줄 수 상한을 숫자로 못 박거나(예: 연습 확장점 · 게이트를 포함한 잔여 줄 수 + 여유), BR3.3을 "연습 확장점 · 게이트 본문은 3.6 · 3.7 결정 전까지 실행기에 남는다"로 정정한다. 상한은 0.2 표 직후가 아니라 이 설계서에서 정해 둔다. | Resolved (reviewer) |
| R-04 | Major | functional-spec.md > 단계 5.3 · 7절 및 rules.md BR4.5 | Java 이름 변경 규모와 충돌 수가 문서와 다르다. 실제로 이름에 `U<번호>`가 든 Java 파일은 103개(접두형 70개 + `SessionExecutorU3Test`처럼 안쪽에 든 33개)이고, 그중 `backend/core-api/src/main/java/.../practice/U2PracticeAccountAdapter.java`는 운영 코드라 "시험 클래스 63개"에 들지 않는다. 번호만 지운 새 이름이 기존 파일과 같은 패키지에서 겹치는 경우가 접두형 4개(`ResultApiIT`, `ResultRetryIT`, `DevSeedRunnerIT`, `RealtimeStartupChecksTest`) + 안쪽형 12개(`SessionExecutorTest`, `ProviderClientIT` 등) + 둘 다 번호만 다른 쌍(core.support의 `U2Fixtures` 대 `U8Fixtures`, core.db의 `SeedU4aReferenceSqlIT` 대 `SeedU8ReferenceSqlIT`)이다. 특히 후자는 접미어 규칙의 예시("의미를 드러내는 접미어")만으로는 어떤 이름을 쓸지 정해지지 않는다. BR4.5의 예시 `ActivityApiIT가 이미 있음`도 실제 충돌 목록과 맞는지 검증되지 않았다. "표식 파일 약 134개"는 근거가 없다(`@Tag("db")` 출현 수가 134, `@Tag("u..")` 파일은 450개, 표식 어노테이션 파일은 56개). | 5.3의 대상 수를 실제 목록(103개, 운영 클래스 포함 여부 결정)으로 바로잡고, 충돌 목록을 단계 0에서 만들어 새 이름을 설계서에 확정해 둔다. 운영 클래스 `U2PracticeAccountAdapter` 처리를 정한다. "표식 파일 134개"의 정체를 확인해 정정한다. | Resolved (reviewer) |
| R-05 | Major | functional-spec.md > 단계 5 및 rules.md BR1.3 · BR4.2 · BR4.4 | 유닛 번호 → 기능 이름 대응표가 어디에도 없다. 고쳐야 할 이름이 범위 파일 26개(u1 ~ u15, u4a, u4b, u5a, u5b, u5b-core), 시험 태그 `u1` ~ `u15`(450개 파일), 프런트 `tests/u1` ~ `tests/u14`, `tools/tests/u*`, Java 클래스 103개, CI 작업 `u5b-scope`/아티팩트 이름인데, `u1`(142개 태그가 걸린 기반 유닛)이나 `u8`의 새 이름이 무엇인지 설계서에 없다. 새 이름을 단계 5 코드 생성자가 임의로 정하면 CI의 `-PincludeTags` · `-PcoverageScope` · `hashFiles` 인자(ci.yml 157 · 161 · 175 줄 등)가 설계 근거 없이 바뀐다. 특히 `hashFiles('backend/coverage-scope/u7.txt') != ''` 단계는 파일 이름이 틀리면 오류 없이 건너뛰어져 ci-gate가 조용히 통과한다. | 유닛 번호별 기능 이름 대응표(범위 파일 · 태그 · 시험 폴더 · 클래스 접두)를 설계서에 추가하고, `hashFiles` 조건이 건너뛰기로 이어지지 않는지 확인하는 점검(예: 이름 변경 뒤 해당 CI 단계가 실제로 실행되는 로그 확인)을 5.4 종료 조건에 넣는다. | Resolved (reviewer) |
| R-06 | Major | functional-spec.md > 3절 이동 매핑 · 단계 1.2 · 1.4 | 매핑이 현재 파일을 모두 덮지 않는다. (a) `parent/api/{account,activity,push,reports,results}.ts` 5개는 `account.ts`만 2.2에서 언급되고 나머지 4개의 목적지가 없다(매핑 행은 `apiClient · csrf · queries · types`만). (b) CSS 5개(`parent.css`, `account.css`, `activity.css`, `result.css`, `practice/practice.css`)는 `src/app/parent/layout.tsx`와 `Practice18.tsx`가 import하는데 목적지가 없다. (c) 컴포넌트 16개 중 "여러 기능이 쓰는 것은 common, 한 기능만이면 기능 폴더"라고만 쓰고 개별 목록이 없다. 실제로 `ChipGroup`, `DeviceCard`, `EndReasonCard`, `SceneChip`, `SettingsSheet`, `StatusLine`, `NoticeDialog`, `BottomSheet`는 import 한 곳뿐이라 이동 위치가 규칙 해석에 따라 갈리고, `PushSettingsRow`는 import가 0건으로 보인다. (d) 단계 1에서 화면 경로가 바뀌면 `frontend/tests/u*/parent` 아래 시험 약 40개의 import가 깨지는데 1.4의 갱신 목록에 시험 파일이 없다(시험 폴더 이동은 5.1이지만 import 갱신은 단계 1 종료 조건 `vitest --coverage` 통과에 필요). (e) 5.1이 말하는 `tests/setup.ts` · `tests/support`는 실제로 `tests/u1/setup.ts` · `tests/u1/support`이고 `vitest.config.ts` 46줄이 `tests/u1/setup.ts`를 가리킨다. | 매핑 표를 파일 단위 확정 목록으로 완성한다(api 4개, CSS 5개, 컴포넌트 16개, `PushSettingsRow`). 1.4에 시험 파일 import 갱신을 명시하고 5.1의 경로를 실제 이름으로 바로잡는다. | Resolved (reviewer) |
| R-07 | Major | rules.md > BR1.1 · BR1.2 · BR4.1 및 functional-spec.md > 단계 3 머리말(② 이동 커밋 한 개 + 참조 갱신 커밋 한 개) | 규칙끼리 충돌한다. BR4.1은 이름 · 위치를 바꾼 같은 커밋에서 문자열 참조를 모두 고치고 못 고치면 "그 커밋은 닫지 않는다"고 하는데, 단계 3 ②는 이동 커밋과 참조 갱신 커밋을 분리한다. 중간 이동 커밋은 `REALTIME_SHARE` · ArchUnit · `TimeLiteralScanTest`가 깨진 채라 어느 시점에도 BR5.1의 단계별 검사를 통과할 수 없다. 또 BR1.2는 이동을 `git mv`로 하라고 하는데 4,361줄 클래스에서 구역을 새 클래스로 꺼내는 것은 `git mv`가 아니라 분리(split)이며 이력 유사도 규칙이 의미가 없다. BR1.1의 "내용 변경은 참조 갱신으로 한정"과 협력 객체 생성자 · 위임 호출 추가도 어느 커밋 종류에 속하는지 정해지지 않았다. | 단계 3은 "클래스 추출(동작 불변 위임 추가, 참조 갱신 포함)을 한 커밋으로 컴파일 · 시험 통과 상태로 닫는다"로 BR4.1에 맞추고, BR1.1 · BR1.2는 파일 이동 · 이름 변경에만 적용된다고 범위를 좁힌다. | Resolved (reviewer) |
| R-08 | Minor | functional-spec.md > 5절 AC-1 | AC-1의 "TextArea200 등 글자 수 한도 예외 목록 확정"은 불필요하다. 정규식 `[A-Za-z][0-9]{2}[a-z]?.tsx$`는 `TextArea200.tsx`를 잡지 못한다(숫자 3자리). 또한 `D0View.tsx` · `T19View.tsx`는 정규식에 걸리지 않아 이름 변경이 합격 기준으로 확인되지 않는다. 열린 "등"도 BR5.4가 금지한 표현이다. | 예외 문구를 삭제하고 `device/components` 이름 변경(D0View · T19View)을 검사에 추가한다. | Resolved (reviewer) |
| R-09 | Minor | functional-spec.md > 단계 0.1 · rules.md BR5.3 와 requirements.md FR1.1 | FR1.1의 고정 대상 처리 순서는 메시지 수신 · 참여 확인 · 판정 · 연습 확장점 · 게이트 · 끝내기인데, BR5.3은 6구간에서 연습 확장점을 빼고 시계를 넣었다. 가장 큰 구역(1,454줄)이 특성화 시험 확인 없이 남고, 시계는 FR1.1에 없다. 요구사항과 달라지는 이유가 편차 표에 없다. | BR5.3을 FR1.1과 맞추거나 편차(FD-D5)로 기록한다. 연습 확장점 구역의 시험 대응도 0.1에 포함한다. | Resolved (reviewer) |
| R-10 | Minor | traceability.json > coverage 및 reverse | AC-1 ~ AC-7 · FD-D1 ~ FD-D4가 추적 표에 없다(BR5.4 한 줄만 reverse에 N/A). FR3.2는 요구사항 문장이 `shared/api`인데 대응 BR1.4는 `parent/common/api`로 정정(FD-D1)해 다른 뜻으로 OK 처리되어 있다. | FR3.2 행에 FD-D1 편차를 표시하고, AC와 FD 항목을 reverse 또는 coverage에 연결한다. | Resolved (reviewer) |
| R-11 | Major | functional-spec.md > 단계 5.1 · 5.4 및 .github/workflows/ci.yml 245 · 268줄 | 프런트 CI의 범위 시험은 폴더 경로로 고른다(vitest run tests/u5b, tests/u7). 5.1은 tests/u* 폴더를 소스 구조 폴더로 옮기고 3.1절은 유닛 이름 폴더를 두지 않는다고 했다. 그러면 u5b · u7 범위가 어떤 시험을 도는지 새 명령이 정의되지 않았다. 폴더가 없어지면 CI가 막는 두 범위(팀 규칙상 CI가 실제로 막는 유일한 유닛 범위)가 빈 실행이 되거나 다른 시험 집합을 재게 된다. 범위 파일 대상 집합 불변(BR4.2)을 시험 선택 쪽에서 지킬 방법이 설계에 없다. | 5.4와 BR4.4에 프런트 u5b · u7 범위의 새 vitest 선택 방식(예 시험 파일 목록 또는 명시 glob)과 이전 시험 집합과 같음을 확인하는 점검(실행 시험 수 대조)을 적는다. 또는 두 범위에 한해 기능 이름 폴더를 유지한다. | New |
| R-12 | Minor | functional-spec.md > 단계 3.5 종료 조건 · 5.4 종료 조건 | (a) AC-4가 3.5 뒤 상한을 단계 0.2 이후로 미뤄 3.5 종료를 기계로 확인할 수 없다. (b) 5.4 종료 조건은 hashFiles 단계가 새 파일 이름에서 실제로 실행되는지(건너뛰기 아님) 확인하는 항목이 없다. AC-2 ③은 옛 이름 잔존만 잡고 새 이름 오타는 잡지 못한다. (c) u5bCopy.ts 같은 유닛 번호 소스 이름은 1.5에서 바꾸지만 AC-1 · AC-2 어느 명령도 이를 검사하지 않는다. | 3.5 상한은 0.2 완료 시 AC-4에 숫자를 적는 것을 단계 0 종료 조건에 넣는다. 5.4 종료에 해당 CI 단계가 실행된 로그 확인을 더한다. AC-1에 유닛 번호가 든 프런트 소스 이름 검사를 더한다. | New |

> R-01 Reviewer note: 다시 쓴 AC-2는 docs · aidlc를 검사 범위에서 빼고 경로 ① 230 · 태그 ② 450 · CI ③ 17줄로 나눴다. 세 명령을 main에서 돌려 230 · 450 · 17이 그대로 나왔다. 230의 내역은 backend 120(자원 3개 포함), 범위 파일 26, frontend/tests 87, tools/tests 12, contracts 1로 모두 이동 대상이다. Java 클래스 103개는 5.3에, 자원 4개와 contracts 파일은 5.3에 명시됐다.; Required action: 없음

> R-02 Reviewer note: 단계 3.0(SessionContext 도입, 필드와 접근자만 이동)이 첫 협력 객체 3.1보다 앞에 생겼고 BR3.2가 같은 순서를 명시한다. 3.5는 남은 머리 · 전이 보조의 배정으로 좁혀졌다. 순환이 풀렸다.; Required action: 없음

> R-03 Reviewer note: AC-4가 3.4 뒤 3,100줄 이하로 숫자가 정해졌고(계산값 3,083줄과 맞음), BR3.3이 게이트 · 연습 확장점은 실행기에 남을 수 있다고 정정했다. 시작 전 값 4,361줄과 표식 5개는 실제 명령으로 확인했다. 3.5 뒤 상한만 단계 0.2 이후로 미뤄져 있어 아래 R-12에서 다룬다.; Required action: 없음

> R-04 Reviewer note: 대상 수가 Java 103개(접두 70 + 중간 33, 운영 클래스 U2PracticeAccountAdapter 포함)로 정정됐고 실제 git ls-files 결과 103과 같다. 충돌 20건, 파스칼 케이스 대응표(3.1절), 표식 134개 정정이 문서에 들어 있다.; Required action: 없음

> R-05 Reviewer note: 범위 파일 · 태그 · 폴더 · CI · 클래스용 대응표(u1 ~ u15, u4a, u4b, u5a, u5b, u5b-core)가 생겼고 BR4.8이 정본으로 지정한다. 5.4에 hashFiles 경로를 새 이름에 정확히 맞추라는 문구도 있다. 건너뛰기를 확인하는 점검은 종료 조건에 없어 R-11에서 다룬다.; Required action: 없음

> R-06 Reviewer note: api 5개의 export 단위 목적지(3.a), 컴포넌트 16개 개별 목적지(3.b), CSS 5개(3.c), 시험 47개 import 갱신(1.4), tests/u1/setup.ts 실제 경로(5.1)가 모두 채워졌다. 컴포넌트 import 그래프를 실제와 대조하니 PushSettingsRow는 import 0건이 맞고 SettingsSheet가 상대 경로로 BottomSheet를 쓰는 것도 맞다. 3.b의 쓰는 쪽 열에 사소한 표기 오차(LiveRegion은 live가 아니라 records · reports)가 있으나 목적지는 같다.; Required action: 없음

> R-07 Reviewer note: 단계 3이 추출과 참조 갱신을 커밋 한 개로 닫는 것으로 바뀌었고 BR1.1이 (a) 참조 갱신 (b) 잘라 붙이기와 호출 치환 (c) 접근 범위 조정을 추출 커밋의 허용 범위로 정의한다. BR1.2는 파일 전체 이동에만 적용된다고 좁혀졌다. BR4.1과 충돌이 사라졌다.; Required action: 없음

> R-08 Reviewer note: TextArea200 예외 문구가 삭제됐고 정규식에 D0View · T19View가 들어갔다. 명령을 돌려 17(화면 15 + 2)이 나왔다.; Required action: 없음

> R-09 Reviewer note: BR5.3과 0.1이 7구간(연습 확장점 포함)으로 바뀌었고 편차 FD-D5가 FR1.1과 NFR2 관계를 적었다.; Required action: 없음

> R-10 Reviewer note: AC-1 ~ AC-7과 FD-D1 ~ FD-D6이 추적 파일에 들어갔고 FR3.2 행이 FD-D1을 표시한다. 모든 OK 대상 BR(37개 규칙)이 rules.md에 존재하며 누락 · 미참조 규칙이 없음을 스크립트로 확인했다.; Required action: 없음

### Validation Tool Results

- AC-1 명령: main 기준 17 (기재값과 일치).
- AC-2 ① 경로 · 파일 이름: 230, ② 태그 파일 450, ③ ci.yml 17줄 (모두 기재값과 일치). 230의 디렉터리별 내역은 위 R-01 칸에 기록했다.
- AC-4 명령: SessionExecutor 4,361줄, 표식 5개 (기재값과 일치). 4,361 − 504 − 184 − 252 − 78 − 260 = 3,083이므로 3,100줄 상한은 맞다.
- Java 이름에 U 번호가 든 파일: 103개 (기재값과 일치, 운영 클래스 1개 포함).
- 범위 파일: backend 18, frontend 8, 합계 26 (기재값과 일치).
- 프런트 컴포넌트 · api 파일은 import 그래프와 대조했고 3.a · 3.b의 목적지에는 모순이 없다.
- traceability.json: 규칙 37개 전부가 참조되고, 존재하지 않는 BR 참조는 없다.
- 이름 충돌 20건의 개별 목록은 이번 검토에서 재현하지 않았다(단계 5.3 첫 커밋에서 스크립트로 만들도록 설계됨).

### Summary

이전 지적 R-01 ~ R-10은 모두 현재 산출물에서 해소됐다. 수치 기준선과 새 대응표 · 이동 표는 실제 저장소와 맞고, 규칙 간 충돌과 SessionContext 순환도 정리됐다. 남은 주요 문제는 R-11 하나로, 프런트 u5b · u7 범위 시험의 선택이 폴더 이름 변경과 함께 정의되지 않은 점이다. Major가 1건(허용 2건 이하)이고 Critical이 없어 READY로 판정하되, R-11은 승인 전 보강을 권한다.
