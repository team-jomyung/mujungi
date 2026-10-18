# 구조 · 이름 리팩토링 — Code Generation 계획

> 작업 기록 `261018-structure-refactor` · 범위 `refactor` · 깊이 Minimal · 시험 전략 Minimal · 통합 브랜치 `claude/refactor-structure`(원격 `main` 570a625 위, 롤백용 `snapshot/main-pre-refactor-20261018`).
> 입력: 승인된 Functional Design(`construction/functional-design/`의 `functional-spec.md` · `rules.md`(BR1.1 ~ BR7.3, BR4.9 포함) · `entities.md` · `traceability.json`), `requirements.md`(FR1 ~ FR8, NFR1 ~ NFR9), 코드 스캔 `aidlc/spaces/default/codekb/mujungi/`. 이 기록은 유닛 나누기를 건너뛰었으므로 `<code-generation-record>`는 `construction/code-generation/`이다.
> **이 계획은 동작을 바꾸지 않는다**(NFR1). 새 시험은 실행기 처리 순서 7구간의 특성화 시험뿐이다(BR5.3). 단계마다 바꾼 쪽 검사와 `bash scripts/verify.sh`를 통과해야 다음 단계로 간다(BR5.1).

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "team",
  "ordering": "시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.",
  "scope": "refactor",
  "test_strategy": "minimal",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: test-after\n- **Ordering**: 시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.\n- 계약에 닿는 변경도 같은 순서를 따른다(Q3-A). 다만 설계 문서 변경이 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고친다(`discovered-rules.md` R-M10).\n- 커버리지 하한(줄 기준만, 분기 · 함수 하한 없음):\n  - 모듈 전체 줄 80%를 CI가 막는다. 백엔드 JaCoCo는 `contract-model` · `core-api` · `realtime-activity`(`test-support`는 제외), 프런트 Vitest v8은 `src/**/*.{ts,tsx}`(`*.d.ts`만 제외).\n  - 유닛 범위 줄 80%는 범위 파일(`backend/coverage-scope/<유닛>.txt` · `frontend/coverage-scope/<유닛>.json`)이 있는 작업에 적용한다. **CI가 실제로 막는 범위는 U5b · U7뿐이다**(백엔드 `u5b` · `u5b-core` · `u7`, 프런트 `u5b` · `u7`). 나머지 범위 파일은 로컬 · 검토 근거다.\n  - `tools/`에는 커버리지 하한이 없다(시험 지원 코드, J107).\n  - 범위 측정은 빈 목록 · 없는 파일이면 실패한다(J137). 범위 파일을 새로 쓰면 이 방식을 그대로 따른다.\n  - `classic` 범위의 줄 80% 하한 · 병합 전 CI 실행과 같다.\n- 하한 · 기존 단언 · 대조 시험 · ArchUnit 시험은 시험을 통과시키려고 낮추거나 끄거나 건너뛰게 하지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.\n- 시험 종류:\n  - 단위: JUnit 5 · AssertJ · Mockito(백엔드), Vitest + Testing Library + jsdom(프런트), Vitest(tools).\n  - 통합: Spring Boot 통합 시험 + Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`), 상대 앱은 스텁.\n  - 계약 대조: `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `OpenApiContracts` · `frontend/tests/u1/shared/timing.test.ts` · `SeedDictionaryFileTest`가 `contracts/`와 코드를 대조한다.\n  - 아키텍처: ArchUnit(`core/arch` · `realtime/arch`), ESLint `/parent` ↔ `/device` ↔ `shared` import 경계.\n  - 끝-끝: `bash scripts/verify.sh` C안(기기 시뮬레이터 + 가짜 공급자).\n  - 인프라: `infra-validate`(Compose 불변식 · `nginx -t` · shellcheck · 마이그레이션 expand 검사), 이미지 점검, 관문 음성 대조.\n- 시험 이름 · 태그: 단위 `*Test`, 통합 `*IT`, 백엔드 유닛 태그 `@Tag(\"u1\")` ~ `@Tag(\"u7\")`와 `@Tag(\"db\")`, 프런트 · tools는 `tests/<유닛>/` 폴더.\n- 관문의 한계: PR의 `ci-gate`는 정적 · 단위 · 통합 · 범위 커버리지 · 보안 · 인프라 · 이미지까지 본다. 끝-끝 회귀(`skeleton`)는 `main` 병합 뒤에 처음 돈다. 그래서 이번 작업은 Build and Test의 전체 `verify.sh`로 병합 전 끝-끝을 확인한다.\n- 시험 · CI · 검증에서는 가짜 공급자만 쓴다(ADR-002).\n- 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공을 따로 적고, 대역 성공을 실공급자 성공으로 표시하지 않는다(`discovered-rules.md` R-M11, Q10-A).\n- 이번 작업 기록의 시험 전략은 Standard다(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험)."
    }
  ],
  "obligations": {
    "strategy": "minimal",
    "strategy_volume": [
      "One verifiable test per requirement at the narrowest effective level.",
      "At least one happy-path unit test per component.",
      "Unit tests are the default; a bugfix/security scope floor may require an integration or E2E regression when that is the narrowest level that reproduces the defect."
    ],
    "scope_floor": [
      "Keep the existing test suite green.",
      "This scope adds no extra new-test floor beyond the selected test strategy."
    ],
    "combination_rule": "Apply every selected-strategy obligation and every scope-floor obligation; neither replaces the other, and a targeted scope regression may add the narrowest necessary test type beyond the strategy default."
  },
  "plan_profile": {
    "methodology": "test-after",
    "runner_step": "Verify the existing test runner/configuration and record the exact unit-scoped command.",
    "runner_ready_before_first_test": true,
    "testable_layers": [
      "Data model / database behavior",
      "Repository / data access",
      "Business logic",
      "API / endpoint",
      "Frontend behavior"
    ],
    "steps": [
      "Project structure and production configuration skeleton.",
      "Verify the existing test runner/configuration and record the exact unit-scoped command.",
      "Data model / database behavior - implement.",
      "Data model / database behavior - write and run its tests after implementation.",
      "Repository / data access - implement.",
      "Repository / data access - write and run its tests after implementation.",
      "Business logic - implement.",
      "Business logic - write and run its tests after implementation.",
      "API / endpoint - implement.",
      "API / endpoint - write and run its tests after implementation.",
      "Frontend behavior - implement.",
      "Frontend behavior - write and run its tests after implementation.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:69cdc8de0ee7860435719c5b7436384ccfb54b8a244a541f432fb1c67e2e383c",
  "contract_sha256": "sha256:64f31e2da591ea3d39c1e9bdd2f801b49aace9d82ae576f8da5d381118e6599f"
}
```

## 계획 개요

- **데이터 · 저장소 · API 층은 해당 없음**: 이 작업은 DB · 저장소 · API 동작을 바꾸지 않는다(NFR1). Flyway 파일은 건드리지 않는다(BR4.3). 층 순서는 계약의 test-after에 맞춰 "프런트 이동(프런트 행동 층) → 프런트 흐름 훅(프런트 행동 층) → 실행기 분리(업무 로직 층) → 설정 · 이름 → 시험 · CI 이름"으로 두되, **층 하나를 구현하면 바로 그 층의 기존 시험과 특성화 시험을 돌린 뒤** 다음으로 간다(Step마다 "검증"에 적었다).
- **커밋 규칙**: 이동 · 추출 커밋에는 이름을 부르는 참조 갱신을 같은 커밋에 넣는다(BR1.1 · BR4.1). 로직 변경은 별도 커밋이다. 커밋 메시지는 `refactor(<범위>): <한국어 요약>` 형식이고 끝에 `Co-Authored-By` 줄을 붙인다. `git diff --cached --summary`로 mode change를 확인한다.
- **통합 브랜치 운영**: 단계가 끝날 때마다 원격 `main`을 브랜치로 merge해 따라잡고(BR7.2) 충돌을 푼 뒤 검증을 돌린다. `main`에는 직접 올리지 않는다(BR7.1).
- **시험 전략**: 새 시험은 Step 2의 실행기 구간 고정 시험뿐이다. 기존 시험은 단언을 바꾸지 않고, 이름 · 경로 · 패키지 참조만 갱신한다(BR5.2). 커버리지 하한은 낮추지 않는다(BR5.5).
- **검증 명령**(단계마다, `unit-test-instructions.md`의 명령): 백엔드 `cd backend && ./gradlew clean check -Pmujoong.test.db=docker`, 프런트 `cd frontend && npx eslint . && npx vitest run tests --coverage && npx next build`, tools `cd tools && npx vitest run tests`, 끝-끝 `bash scripts/verify.sh`. 도커가 필요한 명령은 `sg docker -c '…'`.
- **증거 구분**: 이 계획의 시험 · `verify.sh`는 모두 가짜 공급자 · 합성 대화 기준이다. 실제 API · 실제 기기 성공으로 표시하지 않는다(BR5.6).
- **의존**: 실행기 분리(Step 12 ~ 17)는 Step 2 ~ 3(특성화 시험, 필드 소유자 표)이 끝난 뒤에만 시작한다(BR5.3). 시험 · CI 이름(Step 20 ~ 23)은 프런트 이동(Step 5 ~ 8)과 실행기 분리 뒤에 한다.
- **이어받기**: 세션을 넘겨 맡길 때는 이 계획의 체크박스를 믿지 말고 커밋 기록 · 저장소 상태로 끝난 단계를 먼저 확인한다(project.md 교정).

## 구현 단계

### 단계 0 — 안전망 (설계 단계 0)

- [x] **Step 1. 시험 실행기 확인 · `main` 기준선** — `unit-test-instructions.md`의 명령이 실제로 도는지 확인하고, 원격 `main` 570a625 위에서 `gradlew check`와 `verify.sh`를 한 번 돌려 시작 전 결과를 `construction/code-generation/baseline.md`에 기록한다(`main` CI `skeleton` 실패 이력이 있어 변경과 섞이지 않게 한다). (BR5.1 · 설계 0.6)
- [x] **Step 2. 실행기 7구간 특성화 시험 확인 · 보강** — 메시지 수신 · 참여 확인 · 판정 · 연습 확장점 · 게이트 · 끝내기 · 시계 7구간마다 현재 동작을 고정하는 기존 시험을 찾아 구간 ↔ 시험 대응표(`baseline.md`)를 만든다. 고정 시험이 없는 구간에만 현재 동작을 고정하는 시험을 더한다(새 시험은 이것뿐). 시험은 `SessionHarness`를 쓰고 가짜 공급자만 쓴다. 검증: 새 시험 포함 `:realtime-activity:test --tests "kr.mujoong.realtime.session.*"` 통과. (BR5.3 · FR1.1 · 편차 FD-D5)
- [x] **Step 3. `SessionExecutor` 필드 소유자 표** — 머리 1~619줄과 끝의 "전이 보조" 4080~4361줄의 필드 · 내부 타입 목록을 만들고 각 필드의 소유자(`SessionContext` / `EndingFlow` / `SessionClocks` / `IdleClock` / `JoinFlow` / 남는 실행기)를 정해 `baseline.md`에 적는다. 표가 끝나면 설계서 `functional-spec.md` 5절 AC-4의 "3.5 뒤 줄 수 상한"에 숫자를 적는다(설계 0.2의 종료 조건; 이 파일은 설계 산출물이므로 숫자 기입은 계획 승인 때 사람에게 알리고 같은 커밋 기록에 남긴다). (BR3.2 · FR4.4)
- [x] **Step 4. 참조 점검표 · 화면 대응표 · 기준선** — (a) 코드 스캔 표를 단계별 체크리스트 `reference-checklist.md`로 옮긴다(이름으로 읽는 자원 4개, 이동 대상을 import하는 프런트 시험 47개 포함). (b) `docs/screen-map.md`를 만든다(화면 설계 v1.5j 번호 → 새 파일 경로). (c) AC-1 ~ AC-9의 시작 전 값, 지정 화면 9개의 요청 · 이동 · 오류 호출 수, 건드릴 파일의 결정 ID 주석 수를 `baseline.md`에 기록한다. 검증: 문서 변경뿐이라 비밀 스캔(`bash scripts/verify/a-secrets.sh`)만. (BR4.1 · BR1.6 · BR5.4 · FR1.2 · FR1.3)

### 단계 1 — 프런트 이름 · 폴더 (이동만)

- [x] **Step 5. 보호자 화면 17개 이동** — 설계 3절 매핑대로 `git mv`하고 `src/app/**/page.tsx` import를 같은 커밋에서 수정한다. 파일 이름은 `<기능>Screen.tsx`(`LoginScreen`, `ConsentScreen`, `ChildScreen`, `StopExpressionsScreen`, `HomeScreen`, `DeviceScreen`, `DeviceRegisterScreen`, `FreeTalkScreen`, `LiveScreen`, `DoneScreen`, `PracticeScreen`, `RecordsScreen`, `RecordDetailScreen`, `ReportRangeScreen`, `ReportScreen`, `DevLoginScreen`, `LoginCallbackScreen`). 내용 변경은 import 수정뿐. 검증: `npx eslint .`, `npx next build`. (BR1.1 · BR1.2 · BR1.3 · FR2.1)
- [x] **Step 6. 보호자 `api` · `components` · `hooks` · `activity` · `push` · css 이동** — 설계 3.a ~ 3.c 표대로 이동한다: `common/api/{apiClient,csrf,types}`, `common/api/{account,activity,results,queries}`(공통 몫만), `common/components`(BottomTabBar 등 6개), 한 기능만 쓰는 컴포넌트는 기능 폴더, `common/hooks/useBackGuard`, 기능별 폴링 훅 · `presentation.ts`, `common/push`, `common/styles`. `account.ts` · `queries.ts`는 이 단계에서 **파일 위치만** 정하고 함수 분할은 Step 11에서 한다(이동과 내용 변경 분리). `u5bCopy.ts`는 `features/practice/copy.ts`로 옮기되 `copy.ts`와의 병합은 Step 11. 검증: `npx eslint .`, `npx vitest run tests` 이전 단계 통과 상태 유지, `npx next build`. (BR1.4 · FR2.1 · FR2.3 · FR3.2)
- [x] **Step 7. 기기 화면 이동** — `D0View` → `device/features/first-boot/FirstBootView`, `T19View` → `device/features/status/StatusView`, `DebugLog` → `device/features/debug/DebugLog`. 인프라 폴더(`audio` `ws` `mic` `power` `runtime` `machine` `api`)와 `DeviceApp.tsx`는 그대로. 검증: `npx eslint .`, `npx next build`. (BR1.4 · FR2.2)
- [x] **Step 8. 프런트 참조 갱신 · 1단계 검증** — 이동 대상을 import하는 시험 47개의 import, `frontend/coverage-scope/*.json`의 경로(`u5b.json` · `u7.json` 포함), ESLint 경계 glob과 예외 파일 경로(`browserPush.ts`, `micPermission.ts`), `docs/` 경로(코드가 읽는 것)를 고친다. 이 갱신은 Step 5 ~ 7의 이동 커밋에 같이 들어가야 하므로(BR4.1), **이 단계는 이동 커밋들이 닫힌 뒤 누락된 참조를 찾는 점검**이다. 검증: 프런트 전체(`eslint` · `vitest --coverage` 80% · `next build`), `verify.sh`, AC-1 · AC-6. (BR4.1 · BR1.5 · FR2.4)
- [x] **단계 1 확인** — 위 검증 통과, `git diff --cached --summary` 모드 확인, 원격 `main` merge로 따라잡기, 커밋 묶음 완료.

### 단계 2 — 프런트 화면 흐름 훅 분리

- [x] **Step 9. 훅 분리 — `Live` · `Home` · `Done`** — `useLiveScreen` · `useHomeScreen` · `useDoneScreen`으로 요청 · 오류 분기 · 폴링 · `router.*`를 옮긴다. 화면 컴포넌트는 값과 핸들러만 쓴다. 오류 문구는 `codeToCopy`만. 검증: 이전에 있던 화면 시험(`live-12.test` · `home-04.test` · `done-17.test` 등) 무변경 통과, AC-3(`*Screen.tsx`에서 `router.` · `useRouter` · `catch` · `await` 0건). (BR2.1 · BR2.2 · FR3.1)
- [x] **Step 10. 훅 분리 — `Device` · `DeviceRegister` · `Child` · `StopExpressions` · `Consent` · `Free`** — 같은 방식. 검증: 해당 화면 시험 무변경 통과, AC-3. (BR2.1 · FR3.1)
- [x] **Step 11. 요청 · 문구 위치 정리** — `account.ts`를 설계 3.a 표대로 분할(`common/api/account` 로그인 · 계정, `features/consent/api` 동의, `features/child/api` 자녀 · 정지 표현), `queries.ts`의 기능별 함수를 `features/{home,device,free,live,login}/api.ts`로 분리하고 공통만 `common/api/queries.ts`에 남긴다. 연습 `api.ts` + `startApi.ts`를 `features/practice/api.ts` 하나로, `copy.ts` + `u5bCopy.ts`를 `features/practice/copy.ts` 하나로 합친다. 검증: 프런트 전체, 범위 `u5b`(3개 파일 32개 시험) · `u7`(6개 파일 89개 시험) 시험 수 동일, `verify.sh`. (BR2.3 · BR1.4 · FR3.2 · FR3.3 · 편차 FD-D6)
- [x] **단계 2 확인** — 프런트 전체 + `verify.sh` 통과, `main` 따라잡기, 커밋 묶음 완료.

### 단계 3 — Realtime 실행기 분리

- [x] **Step 12. `SessionContext` 도입 (3.0)** — Step 3의 표대로 공유 필드와 접근자만 `SessionContext`로 옮긴다(로직 변경 없음). 한 커밋. 검증: `:realtime-activity:clean :realtime-activity:check`, Step 2의 특성화 시험, `SingleInstanceMarkerTest`, `TimeLiteralScanTest`, ArchUnit. (BR3.1 · BR3.2 · FR4.1)
- [x] **Step 13. `EndingFlow` 추출 (3.1)** — "기기 표식 · 끝내기" 구역(3335~3838, 504줄)을 추출하고 `SessionExecutor` 호출 지점을 치환한다. `REALTIME_SHARE`(SI-2 · SI-9) · `TimeLiteralScanTest` 허용 목록 · ArchUnit · 범위 파일 · 문서 참조를 **같은 커밋**에서 고친다. 검증: 단계 12와 같음 + `verify.sh`. (BR1.1 · BR3.4 · BR4.1 · FR4.2 · FR4.7)
- [x] **Step 14. `SessionClocks` 추출 (3.2)** — "시계" 구역(3839~4022, 184줄). 시각은 `MujoongClock` 주입, 숫자 시간 값 금지. 검증: 같음. (BR3.6 · FR4.2)
- [ ] **Step 15. `IdleClock` 추출 (3.3)** — "공백 시계 · 기술 일시정지 · 진행 조회" 구역(2809~3060, 252줄). 검증: 같음. (FR4.2)
- [ ] **Step 16. `JoinFlow` 추출 · 판정 턴 흡수 (3.4)** — "참여 확인"(1017~1094, 78줄)을 `JoinFlow`로, "U11 판정 턴"(1095~1354, 260줄)을 기존 `JudgmentCoordinator`로 흡수한다. 검증: 같음. 이 단계 뒤 AC-4: `SessionExecutor` 3,100줄 이하, 분리한 구역 표식 0개. (BR3.3 · FR4.2 · FR4.3)
- [ ] **Step 17. 정리 (3.5) · 게이트 경계 (3.6) · 연습 확장점 판단 (3.7)** — Step 3 표대로 남은 머리 · 전이 보조를 `SessionContext` 또는 협력 객체로 배정하고 AC-4의 3.5 상한을 확인한다. 게이트는 `GuardrailCoordinator`와의 경계를 설계서에 적고 옮길 범위를 사람에게 묻는다. **연습 확장점 구역(약 1,454줄)은 이 단계에서 나눌지 사람에게 묻고 정해진 뒤에만 한다**(BR3.5). 검증: 같음 + `verify.sh`, 단계 3 확인. (FR4.4 · FR4.5 · FR4.6)
- [ ] **단계 3 확인** — 백엔드 전체 `check` + `verify.sh` 통과, 기존 시험 단언 무변경, 시험 수 동일, `main` 따라잡기.

### 단계 4 — 설정 레코드 · `practice` 이름

- [ ] **Step 18. 설정 레코드 분리** — `RealtimeProperties`(570줄) · `CoreProperties`(589줄)의 중첩 record를 기능별 파일로 나눈다. 키 이름 · 바인딩 prefix는 불변. 검증: `ConfigKeyContractTest`, `TimeLiteralScanTest`, 두 모듈 `check`. (BR4.6 · FR5.2)
- [ ] **Step 19. `practice/` → `practicelegacy`** — 운영 30파일과 같은 패키지 이름을 쓰는 시험, 이 패키지를 import하는 파일 13개, `PracticeFlowArchTest` 패키지 문자열, `backend/coverage-scope/u5b.txt`의 `realtime/practice/*` 경로, 계약 문서 경로를 같은 커밋에서 고친다. 검증: ArchUnit 규칙이 비지 않음, 두 모듈 `check`, `verify.sh`. (BR4.1 · FR5.1)
- [ ] **단계 4 확인** — 위 검증 통과, `main` 따라잡기.

### 단계 5 — 시험 · 범위 · CI 이름

- [ ] **Step 20. 프런트 시험 이동 · 범위 시험 목록 (5.1)** — `tests/u*`를 소스 구조 폴더(`tests/parent/features/…`, `tests/device/features/…`, `tests/shared/…`, `tests/lint/…`)로 옮기고 `tests/u1/setup.ts` → `tests/setup.ts`, `tests/u1/support` → `tests/support`로 옮기며 `vitest.config.ts` `setupFiles` · `a-checks.sh` 참조를 고친다. 프런트 범위 시험(`practice-legacy`, `web-push`)은 `frontend/coverage-scope/<이름>.tests`(한 줄에 시험 파일 하나)로 고르도록 `vitest.config.ts`가 목록을 읽거나 CI 단계가 목록 각 파일의 존재를 먼저 확인하게 한다(검토 R-13). `tools/tests/u*`도 3.1절 표의 이름으로. 검증: AC-8(`practice-legacy` 3개 파일 32개 시험, `web-push` 6개 파일 89개 시험), 목록 줄 수 = 실행 파일 수, 프런트 전체. (BR1.3 · BR4.1 · BR4.8 · BR4.9 · FR6.1)
- [ ] **Step 21. 범위 파일 이름 (5.2)** — `backend/coverage-scope/*.txt` 18개와 `frontend/coverage-scope/*.json` 8개를 설계 3.1절 표의 이름으로 바꾼다(대상 집합은 그대로, 줄 80% 하한 유지). 빈 목록 · 없는 파일이면 측정이 실패하는 규칙(J137) 유지. 검증: 범위 측정(`-PincludeTags=… -PcoverageScope=…`), 프런트 `MUJOONG_COVERAGE_SCOPE`. (BR4.2 · BR4.8 · FR6.2)
- [ ] **Step 22. Java 클래스 · 태그 · 자원 이름 (5.3)** — 첫 커밋에서 충돌 목록을 스크립트로 만든다(번호를 뺀 이름이 같은 폴더에서 겹치는 20건 확인). 이어서 `U<번호>`가 든 Java 파일 103개(운영 클래스 `U2PracticeAccountAdapter` 포함)의 이름에서 번호를 빼고, 겹치는 이름은 번호 자리에 파스칼 케이스 기능 이름을 넣는다(BR4.5). `@Tag("u…")` 450개 파일의 태그를 3.1절 표의 이름으로 바꾸고, 이름으로 읽는 자원 4개(`textai/u5a-forbidden-patterns.txt`, `practice/u5b-independent.yml`, `practice/u5b-runtime.yml`, `contracts/u14-compatibility.md`)와 그것을 읽는 코드 문자열을 같이 고친다. **마이그레이션 파일은 제외**(BR4.3). 시험 수가 단계 전과 같아야 한다. 검증: 두 모듈 `check`, AC-2. (BR4.5 · BR4.3 · BR4.8 · FR5.1 · FR6.1 · 편차 FD-D3)
- [ ] **Step 23. CI 이름 (5.4)** — `.github/workflows/ci.yml`의 작업 이름(`u5b-scope` 등) · `-PincludeTags` · `-PcoverageScope` · `MUJOONG_COVERAGE_SCOPE` · `vitest run` 인자(목록 파일) · `hashFiles` 경로 · `ci-gate` needs를 같은 커밋에서 고친다. 바뀐 이름과 `ci-gate` 구성을 `docs/`에 남긴다. 푸시 뒤 첫 CI 실행에서 `hashFiles` 조건 단계가 `success`인지 확인한다(AC-9). 검증: `infra/bin/infra-validate`(shellcheck · compose-config 등), AC-2 ③ · AC-9. (BR4.4 · FR6.3 · FR6.4)
- [ ] **단계 5 확인** — 프런트 · 백엔드 · tools 전체 + `verify.sh` 통과, 시험 수 동일, `main` 따라잡기.

### 단계 6 — 주석 정리 (조건부)

- [ ] **Step 24. `team.md` 규칙 학습 요청 · 주석 정리** — 병합 직전에 `team.md` 규칙 4개(화면 번호 접미 · `tests/<유닛>/`과 유닛 태그 · 주석 ID 인용 · 범위 파일 이름)를 학습 절차(§13)로 올려 사람이 승인하게 한다(직접 편집 금지, BR6.3). 승인되면 이 기록이 건드린 파일의 주석을 "무엇을 왜"만 남기고 결정 ID는 한 줄 문서 링크로 줄인다(BR6.1 · BR6.4). 거절되면 단계를 건너뛰고 `code-summary.md`에 이유를 적는다(BR6.2). 검증: 시험 · 린트 통과, AC-7(승인된 경우). (FR7.1 · FR7.2)

### 마무리

- [ ] **Step 25. 환경 · 문서 · 추적성** — `docs/screen-map.md` 최종본, `docs/refactor-structure-before-after.md`의 "이후" 구조를 실제 결과로 갱신, `docs/`에 CI 작업 이름 변경 기록, 비밀 스캔(`bash scripts/verify/a-secrets.sh`) 통과 확인, `code-summary.md` · `traceability.json` · `source-manifest.json`(이 기록이 만들거나 바꾸거나 지운 모든 응용 소스 경로, 이동은 옛 경로 삭제 + 새 경로) 작성. 전체 `bash scripts/verify.sh`는 Build and Test에서 한 번 더 돈다. (FR8.1 · FR8.2 · NFR8 · NFR9)

## 단계 ↔ 요구사항 · 설계 추적

| Step | 설계 단계 | 요구사항 | 규칙 | 합격 기준 |
|---|---|---|---|---|
| 1 | 0.6 | NFR2 · NFR7 | BR5.1 | AC-5 |
| 2 | 0.1 | FR1.1 | BR5.3 | — |
| 3 | 0.2 | FR4.4 | BR3.2 | AC-4 |
| 4 | 0.3 ~ 0.5 | FR1.2 · FR1.3 · FR8.1 | BR4.1 · BR1.6 · BR5.4 | AC-1 ~ AC-9 시작 전 값 |
| 5 ~ 8 | 1.1 ~ 1.5 | FR2.1 ~ FR2.4 | BR1.1 ~ BR1.5 · BR4.1 | AC-1 · AC-6 |
| 9 ~ 11 | 2.1 ~ 2.3 | FR3.1 ~ FR3.4 | BR2.1 ~ BR2.3 · BR1.4 | AC-3 |
| 12 ~ 17 | 3.0 ~ 3.7 | FR4.1 ~ FR4.7 | BR3.1 ~ BR3.6 · BR4.1 | AC-4 · AC-5 |
| 18 ~ 19 | 4.1 ~ 4.2 | FR5.1 · FR5.2 | BR4.6 · BR4.1 | AC-5 · AC-6 |
| 20 ~ 23 | 5.1 ~ 5.4 | FR6.1 ~ FR6.4 | BR4.2 ~ BR4.5 · BR4.8 · BR4.9 | AC-2 · AC-8 · AC-9 |
| 24 | 6.0 ~ 6.2 | FR7.1 · FR7.2 | BR6.1 ~ BR6.4 | AC-7 |
| 25 | — | FR8.1 · FR8.2 · NFR8 · NFR9 | BR4.7 · BR5.6 · BR5.7 | — |

모든 Step의 브랜치 · 롤백은 BR7.1 ~ BR7.3, 동작 불변은 NFR1 · BR4.3 · BR4.6, 경계 유지는 NFR4 · BR1.5를 따른다.

## 위험과 순서 근거

- `SessionExecutor`(4,361줄)는 가장 위험하다. 프런트(Step 5 ~ 11)로 방식을 먼저 익힌 뒤 실행기(Step 12 ~ 17)에 들어가고, 실행기 분리는 특성화 시험(Step 2)과 필드 소유자 표(Step 3)가 끝난 뒤에만 시작한다. 한 협력 객체당 커밋 하나, 끝날 때마다 `verify.sh`.
- 통합 브랜치 하나에 파일 이동 약 200개, Java 파일 103개 이름 변경, `@Tag` 450개 파일 수정이 모인다. 단계마다 `main`을 따라잡아 충돌을 작게 유지하고, 이동 · 추출 커밋과 로직 커밋을 나눠 리뷰한다.
- `main`의 최근 병합(#90, #92)에서 CI `skeleton`이 실패했다. Step 1에서 `main` 기준 결과를 기록해 이 기록의 변경과 섞이지 않게 한다.
- 이름 충돌 20건은 Step 22 첫 커밋의 스크립트 결과를 따른다. 시험 수가 줄거나 같은 클래스가 덮이면 되돌린다.
- 프런트 범위 시험 목록(`<이름>.tests`)은 이 기록이 새로 정한 형식이라 Step 20의 첫 커밋에서 vitest 설정 또는 CI 단계로 실제 동작을 확인한다(검토 R-13).
- 주석 정리(Step 24)는 사람 승인이 없으면 하지 않는다.
