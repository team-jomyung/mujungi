## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-19T03:42:37Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/memory/team.md > Testing Posture("시험 이름 · 태그" 줄, 커버리지 하한의 "CI가 실제로 막는 범위는 U5b · U7뿐" 줄), Code Style("프런트 화면 파일은 화면 설계 번호를 이름 끝에", "코드 주석은 결정 ID(J · BR · U*-J · 편차)를 인용하고"); aidlc/spaces/default/memory/project.md > Corrections("새 단위를 만들면 … 시험 태그 · 커버리지 범위 파일 이름도 같은 번호를 써") | 학습 절차가 새 규칙 4줄을 덧붙였지만 대체된 옛 줄은 지워지지 않아 같은 규칙 묶음에 모순된 두 줄이 함께 로드된다. 옛 줄은 `tests/<유닛>/`, `@Tag("u1")~("u7")`, `Login01.tsx`, 결정 ID 인용을 요구하고 새 줄은 이를 금지한다. 실제 저장소는 새 줄 쪽이다(`git ls-files`로 AC-1/AC-2 패턴 0건, `@Tag("u…")` 파일 0개, `frontend/coverage-scope/*.tests`, `backend/coverage-scope/practice-legacy.txt` 등 기능 이름). 이 때문에 앞으로의 Code Generation 에이전트가 어느 줄을 따를지 모호하다. 커버리지 줄의 "U5b · U7"은 이제 존재하지 않는 이름이다(실제 CI 대상은 `practice-legacy` · `web-push`). | 승인 화면에서 옛 줄 4~5개를 지우거나 "대체됨"으로 표시하도록 사람에게 알린다. 커버리지 줄의 범위 이름도 `practice-legacy` · `web-push`로 고친다. project.md의 유닛 번호 동기화 교정도 같은 방식으로 정리한다. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261018-structure-refactor/construction/code-generation/source-manifest.json > writes | 변경 경로 1,229개 중 manifest에 없는 응용 쪽 경로가 있다: `README.md`, `contracts/goal-result-compatibility.md`, `contracts/ports.md`, `contracts/timing.md`, `contracts/u14-compatibility.md`(삭제). 나머지(backend/frontend/tools/scripts/infra/.github)는 영역 단위로 덮인다. 특히 `contracts/`는 R-M10에 따라 CI 전 작업이 돌게 하는 경로다. | `contracts/`와 `README.md`를 writes에 더한다(문서 `docs/`는 traceability에서 인용하므로 함께 적는 편이 낫다). | New |
| R-03 | Minor | frontend/tests/parent/features/done/done-17.test.tsx:97, frontend/tests/parent/features/live/live-12.test.tsx:190, frontend/coverage-scope/activity-session.json:2, frontend/coverage-scope/parent-account.json:2, backend/core-api/src/test/java/kr/mujoong/core/child/ChildRulesTest.java:92 | 옮겨서 없어진 경로를 가리키는 주석/`$comment`가 남았다(`tests/u4a/parent/...`, `tests/u2/parent/stop-expressions.test.tsx`, `docs/units/u4a/design.md`). 시험 파일 이름도 `done-17`, `live-12`처럼 화면 번호를 달고 있다(AC-1 정규식은 `.test.tsx`라 걸리지 않음). 동작 영향은 없다. | 죽은 경로 참조를 새 경로로 고치거나 지운다. 시험 파일 이름의 화면 번호는 유지할지 사람이 정한다. | New |
| R-04 | Minor | infra/seed/002-u2-reference.sql, 003-u4a-reference.sql, 004-u8-reference.sql; source-manifest.json > writes(`infra/seed/003-u4a-reference.sql`, `004-u8-reference.sql`) | 유닛 번호가 든 시드 파일 이름이 그대로이고 AC-2의 경로 정규식(세그먼트 시작 `u<번호>`)에 걸리지 않아 통과한다. 이름을 유지한 근거가 plan · code-summary · 점검표 어디에도 없고(내용만 수정됨), 설계 BR1.3의 의도(유닛 번호 제거)와 일관되지 않다. 파일명을 읽는 코드가 있어 유지했다면 그 사실이 기록돼야 한다. | 유지 사유(예: 파일명을 읽는 스크립트·Flyway성 순서)를 code-summary에 적거나 이름을 바꾼다. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261018-structure-refactor/construction/code-generation/baseline.md:68; code-generation-plan.md 단계 5 확인 줄 | baseline 2절 결론은 "새 시험 파일 0개"인데, 커밋 1698931이 `SessionClocksCharacterizationTest`(4개 시험)를 추가했고(baseline.md:176에서 정정됨), traceability FR1.1도 이 파일을 인용한다. 시험 수는 기준선 realtime 1862 → 1866으로 4개 늘었으나 plan은 "시험 수 동일(… realtime 1866)"이라 적어 AC-5 문구("시험 수가 같음")와 어긋나 보인다. 새 시험은 BR5.3이 허용하는 특성화 시험이므로 위반은 아니다. | baseline 2절 결론에 정정 문구를 달고, plan의 시험 수 설명을 "기준선 + 특성화 4개"로 바로잡는다. | New |
| R-06 | Minor | frontend/src, backend/*/src/main 주석 정리 결과(예: 설정 yml 주석 "3.6 수정 , Core와 같은 키", 들여쓰기가 사라진 `# SPRING_DATASOURCE_URL …` 이어짐 줄) | 결정 ID를 지운 자리에 빈 괄호 조각·고아 구두점이 남은 주석이 있다. `git diff -U0`로 확인한 결과 코드 줄 변경은 0건(주석·빈 줄만)이라 동작 중립은 성립한다. 가독성 문제만 있다. | 리뷰에서 훑어 고아 구두점과 들여쓰기를 정리한다(code-summary가 이미 요청한 항목). | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| AC-1 (`git ls-files frontend/src` 화면번호/유닛번호 이름, `parent/screens`) | 0 / 0 / 폴더 없음 | 통과 |
| AC-2 ① 경로 · 이름 / ② `@Tag("u…")` 파일 / ③ `ci.yml` 줄 | 0 / 0 / 0 | 통과. 단 시드 파일 3개는 정규식 밖(R-04) |
| AC-3 대상 9개 `*Screen.tsx` (Live·Home·Done·Consent·Device·DeviceRegister·Child·StopExpressions·FreeTalk) | 모두 0건 | 통과. 대상 밖 화면(Dev/LoginCallback/Practice/Records/Report*)은 범위 밖이라 0이 아님 |
| AC-4 `SessionExecutor.java` | 3,090줄(상한 3,100), 제목 표식 0개 | 통과 |
| AC-7 결정 ID 정규식(변경된 `frontend/src` · `backend/*/src/main` 210개 파일) | 10줄 / 5개 파일, 전부 코드(`JudgmentPoint.J1~J6` 열거값, 오류 사유 문자열) | code-summary의 540 → 10과 일치 |
| 작업 트리 diff(132개 파일) 코드 줄 변경 검사 | `//`·`*`·`/*`·`#`·빈 줄 이외의 변경 0건 | Step 24는 주석 전용으로 확인됨 |
| AC-8 범위 목록 | `practice-legacy.tests` 3개, `web-push.tests` 6개(기존 3·6개 파일과 일치), 존재하지 않는 경로 검사 코드 추가됨 | 통과(시험 수 32/89는 직접 재실행하지 않음) |
| 품질 목표 | `@Disabled`·`skip`·`todo` 추가 0건, 삭제된 시험 파일 1개는 `U10ScenarioValidationTest` → `ScenarioValidationTest` 이름 변경, ArchUnit 변경은 패키지·이름 문자열뿐, 임계값 하향 없음 | 약화 없음 |
| traceability 대상 파일 존재 | 확인한 15개 모두 존재 | OK |
| 직접 재실행하지 않은 것 | AC-9(CI 단계 기록), `verify.sh`, 전체 시험 | 호출자의 통과 보고를 신뢰 |

### Summary

코드 수준은 건전하다: 작업 트리 변경은 주석 전용이고, AC-1 ~ AC-4 · AC-7 · AC-8이 명령으로 재현되며 품질 목표 약화나 시험 건너뛰기가 없다. 가장 큰 우려는 `team.md`에 대체된 옛 규칙이 지워지지 않아 모순된 규칙이 함께 로드된다는 점(R-01)이고, 나머지는 manifest 범위, 죽은 경로 주석, 시드 파일 이름 같은 마이너 정리다.
