# 구조 · 이름 리팩토링 — 참조 점검표 (Step 4-a)

> 작업 기록 `261018-structure-refactor` · 근거: 코드 스캔 `codekb/mujungi/code-structure.md`의 "이름 · 경로를 문자열로 부르는 곳" 표와 이 PC에서 `git grep` · `grep`으로 다시 센 값(원격 `main` 570a625 소스 기준).
> 이동 · 추출 · 이름 변경 커밋은 **이 표의 해당 단계 줄을 같은 커밋에서** 고친다(BR1.1 · BR4.1). 단계가 닫힐 때 아래 "확인 명령"으로 남은 옛 참조가 0인지 본다. 칸 앞의 `[ ]`는 해당 단계에서 체크한다.
> 스캔 표와 실측이 다른 값은 "실측"으로 표시했다.

## 1. 이름으로 읽는 자원 4개 (Step 22)

| 자원 | 읽는 쪽 | 읽는 방식 | 바꿀 이름(3.1절 표) |
|---|---|---|---|
| `backend/core-api/src/main/resources/textai/u5a-forbidden-patterns.txt` | `core-api/.../textai/ForbiddenExpressionPolicy.java:17` | `new ClassPathResource("textai/u5a-forbidden-patterns.txt")` | `practice-core` 계열 이름 |
| `backend/realtime-activity/src/main/resources/practice/u5b-independent.yml` | 시험 `realtime/practice/PracticeConfigurationTest.java:17` | `ClassPathResource`로 명시 바인딩 | `practice-legacy` 계열 |
| `backend/realtime-activity/src/main/resources/practice/u5b-runtime.yml` | `realtime/practice/PracticeRuntimeConfiguration.java:46` (`YamlPropertySourceLoader.load("u5b-runtime", …)`) · `application.yml:118`(주석) · `contracts/timing.md:202 · 224`(문서) | 클래스패스 로드 + 속성 원본 이름 문자열 | `practice-legacy` 계열 |
| `contracts/u14-compatibility.md` | **코드에서 이름 문자열로 읽는 곳 없음**(`git grep`은 `aidlc/` 밖에서 0건, `ContractFilesTest`는 `contracts/schemas` 디렉터리만 열거) | 문서 참조뿐 | `goal-result` 계열 (이름 변경 시 문서 링크만 확인) |

- [ ] 자원 파일 4개와 읽는 코드 문자열을 같은 커밋에서 바꿨다.
- [ ] `PracticeRuntimeConfiguration`의 속성 원본 이름 `"u5b-runtime"`을 바꿀 때 `contracts/timing.md`(202 · 224줄)와 `application.yml` 주석(118줄)을 함께 고쳤다.
- [ ] 확인: `git grep -nE 'u5a-forbidden-patterns|u5b-independent|u5b-runtime|u14-compatibility' -- ':!aidlc'`가 옛 이름 0건.

## 2. 단계별 체크리스트

### Step 5 ~ 8 — 프런트 이동 (이동 대상: `parent/{screens,components,api,practice,hooks,activity,push}`, `parent/{navigation,oauth,ParentProviders}`, `parent/*.css`, `device/components`)

- [ ] **`src/app` import 20곳** (이동하면 같은 커밋에서 고친다):
  - 화면 import 17곳: `parent/page.tsx`(Home04) · `login/page.tsx`(Login01) · `login/callback/page.tsx`(LoginCallback) · `dev-login/page.tsx`(DevLogin) · `consent/page.tsx` · `child/page.tsx` · `child/stop-expressions/page.tsx` · `device/page.tsx` · `device/register/page.tsx` · `free/page.tsx` · `practice/page.tsx` · `records/page.tsx` · `records/[sessionId]/page.tsx` · `reports/new/page.tsx` · `reports/[reportId]/page.tsx` · `sessions/[sessionId]/page.tsx`(Live12) · `sessions/[sessionId]/done/page.tsx`(Done17)
  - 그 밖: `parent/layout.tsx`(`@/parent/ParentProviders`) · `parent/login/callback/page.tsx` 와 `parent/login/google/route.ts`(`@/parent/oauth`) · `parent/push-worker/route.ts`(`@/parent/push/pushWorker`)
- [ ] **ESLint 경계** `frontend/eslint.config.mjs`: 영역 glob(`./src/parent` · `./src/device` · `./src/shared` · `./src/app/{parent,device}`, 11 · 12 · 65 · 78 · 84 · 90줄)과 **예외 파일** `src/parent/push/browserPush.ts`(96줄) · `src/device/mic/micPermission.ts`(108줄) 경로. `parent/push` → `parent/common/push` 이동 시 96줄 예외 경로를 같은 커밋에서 고친다(안 고치면 경계 규칙이 풀림, AC-6).
- [ ] **`frontend/coverage-scope/*.json` 8개**: 이동 대상 경로가 든 줄 수 — `u1` 23 · `u2` 10 · `u3` 1 · `u4a` 6 · `u5a` 6 · `u5b` 6 · `u6` 13 · `u7` 8. **CI가 막는 것은 `u5b` · `u7`뿐**이고 나머지는 로컬 검토 근거다. 모두 같은 커밋에서 고친다(없는 파일이면 측정이 실패한다, J137).
  - `u5b.json` 6개: `parent/practice/{startApi,u5bCopy,practiceStart,PracticeStartAction,practiceProgress,PracticeHeader}`
  - `u7.json` 10개 파일 목록(`parent/screens/Home04.tsx`, `components/SettingsSheet.tsx` 등)
- [ ] **`frontend/tests/**` 이동 대상 import 시험 50개** — 4절 목록. 이동 커밋에서 import(`vi.mock` 경로 문자열 포함)만 고친다(BR5.2).
- [ ] CSS import: `parent/screens/Practice18.tsx`(`practice.css`)와 `src/app/parent/layout.tsx`(`parent.css` 등) — 이동하는 `*.css` 경로를 같은 커밋에서 고친다.
- [ ] `docs/` 경로: **코드가 읽는 `docs/` 파일 0개**(실측: 운영 코드 · 시험 · 스크립트 · CI에서 `docs/`를 파일로 읽는 곳 없음, 주석 언급뿐). 화면 파일 이름이 든 이력 문서(`docs/units/{u3,u5a,u6}/*`, `u4a/review-1.md`)는 FD-D4대로 고치지 않고 `docs/screen-map.md`로 안내한다.
- [ ] `infra/bin/render-env:70` 주석의 `frontend/src/app/parent/login/callback` — 라우트 폴더는 이동 대상이 아니므로 변경 없음(확인만).
- [ ] 확인 명령: `git ls-files frontend/src | grep -E '[A-Za-z][0-9]{2}[a-z]?\.tsx$|/D0View|/T19View'` 0건(AC-1), `cd frontend && npx --no-install eslint . && npx --no-install next build`.

### Step 11 — 요청 · 문구 합치기

- [ ] `parent/api/account.ts` 분할(로그인 · 계정 / 동의 / 자녀 · 정지 표현)과 `queries.ts` 분할의 import 소비처(화면 · 훅 · 시험).
- [ ] `parent/practice/{api,startApi}.ts` → `features/practice/api.ts`, `{copy,u5bCopy}.ts` → `features/practice/copy.ts`: `coverage-scope/u5b.json`의 `startApi.ts` · `u5bCopy.ts` 항목을 합친 파일 이름으로 바꾸고, `u5b` 시험 3파일(32건)의 import.

### Step 12 ~ 17 — 실행기 분리 (백엔드 `realtime-activity`)

- [ ] **`SingleInstanceMarkerTest`(realtime)**: `REALTIME_SHARE`가 `SessionExecutor.class`를 SI-2(116줄)와 SI-9(123줄)에 직접 넣는다. `@SingleInstanceAssumption(si = {"SI-2","SI-9"})`를 가진 클래스가 새로 생기면(`SessionContext` 등) 표 · 시험 · 표식을 같은 커밋에서 맞춘다(`SingleInstanceMarkerTest`는 core-api에도 있으나 realtime만 해당).
- [ ] **`TimeLiteralScanTest`(realtime)**: 허용 목록에 `SessionExecutor`는 없다. **새 분리 클래스는 검사 대상**이라 숫자 시간 값이 든 줄이 옮겨지면 걸린다. 허용 목록(`session/{EndRequestHandler,StartSessionHandler,SessionMailbox}` 등 파일 경로 + 소스 조각 + 사유)은 "항목이 아직 줄에 걸리는가" · "파일이 있는가"를 단언하므로 그 파일을 옮기거나 줄을 바꾸면 같이 고친다.
- [ ] **ArchUnit**: `RealtimeArchTest`(`kr.mujoong.realtime.{voice,gate,gateway,messaging,common.db}`) · `LunaArchTest`(`..luna..` · `..guardrail..`) · `U8LiveContentArchTest`(`kr.mujoong.realtime.content..`) · `JudgmentPackageArchTest` · `GuardrailPackageArchTest` · `PracticeFlowArchTest`(`kr.mujoong.realtime.session.`) — `session` 패키지에 새 클래스를 더해도 규칙이 비지 않는지, `session → practice` 방향 규칙이 그대로인지 본다.
- [ ] **`backend/coverage-scope/*.txt`**: `SessionExecutor`가 든 범위 파일 6개 — `u1` · `u3` · `u4b` · `u11` · `u12` · `u13`. `SessionExecutor`는 남으므로 경로 변경은 없지만, 새 협력 객체 파일을 해당 범위에 넣을지는 Step 12에서 정한다(대상 집합을 줄이면 하한을 낮추는 것이 된다, BR5.5). CI가 막는 범위는 `u5b` · `u5b-core` · `u7`이고 `SessionExecutor`는 그 안에 없다.
- [ ] **문서 문구**: `contracts/deviations.md` 52줄의 `SessionExecutor` 문구 1곳(클래스 이름이 안 바뀌므로 변경 없음, 책임 이동 설명만 확인). `docs/units/{u3,u4b,u5b,u11,u12,u13}/*` 13개 파일의 `SessionExecutor` 언급은 이력 문서라 고치지 않는다(FD-D4).
- [ ] **외부에서 `SessionExecutor.X`로 읽는 이름**: 공개 내부 타입 `Settings`(운영 2 · 시험 4) · `Services`(운영 3 · 시험 3) · `Plan`(운영 3 · 시험 3) · `ApprovedItem`(1 · 1) · `LiveState`(운영 2 · 시험 2) · 승인 문구 코드 상수 6개(운영 1 ~ 3 · 시험 5 ~ 13), 패키지 전용 `EVENT_*` 상수(시험 1 ~ 6파일 · 운영 0). 소유자를 바꾸는 상수 · 타입은 이 참조를 같은 커밋에서 고친다.
- [ ] 확인 명령: `sg docker -c 'cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'`, 시험 수 1862건 유지.

### Step 18 ~ 19 — 설정 레코드 · `practice` → `practicelegacy`

- [ ] `ConfigKeyContractTest`(realtime 588줄, core): `application.yml` · `application-test.yml` · `contracts/timing.md` · `ports.md`를 경로로 읽는다. 설정 키 · 바인딩 prefix가 그대로여야 통과한다.
- [ ] **`practice` 패키지 이동** (실측): 운영 `practice/` Java **29개**, 이 패키지를 import하는 **패키지 밖** 파일 **17개** — 운영 4(`runtime/PracticeRecoveryRouter` · `session/PracticeSessionExtension` · `session/SessionExecutor` · `voice/PracticeSpeech`)와 시험 13(`arch/PracticeFlowArchTest` · `practiceflow/{PracticeConversationTest,PracticeFlowArchViolationSample,PracticeFlowRecordTest,PracticeFlowRouterTest,PracticeFlowTestKit,PracticeProgressMappingTest}` · `runtime/PracticeRecoveryRouterTest` · `session/{GenerationSendExecutorTest,GuardrailExecutorTest,PracticeGeneratedSpeechTest}` · `voice/{ProviderRequestFactoryWireTest,U8PracticeSpeechMatchTest}`). 같은 패키지 이름을 쓰는 시험 폴더 `src/test/.../practice/`는 31개 파일이다.
- [ ] `PracticeFlowArchTest` 패키지 문자열: `"kr.mujoong.realtime.practice.."` 등(44 · 85 · 117줄 안팎) — 규칙이 비면(`allowEmptyShould(false)` · "15개 초과" 단언) 시험이 막는다.
- [ ] **`backend/coverage-scope/u5b.txt` 32줄**: 모두 `backend/realtime-activity/src/main/java/kr/mujoong/realtime/practice/*` 전체 경로다. CI `u5b-scope`가 읽는다 — 패키지 이동과 같은 커밋에서 고치지 않으면 CI가 실패한다(J137).
- [ ] 계약 문서 경로: `contracts/timing.md`의 `practice/u5b-runtime.yml` 경로 문구.

### Step 20 ~ 21 — 시험 폴더 · 범위 파일 이름

- [ ] `frontend/vitest.config.ts`: 7줄 주석, **46줄 `setupFiles: ["tests/u1/setup.ts"]`**, 9 · 18 · 20 · 28 · 33줄의 `coverage-scope/<이름>.json` 읽기 오류 문구, 47줄 `include: ["tests/**/*.test.{ts,tsx}"]`.
- [ ] `scripts/verify/a-checks.sh` 76줄 주석(`tests/u1 · tests/u3 …`), `tools/vitest.config.mjs` 4줄 주석(`tests/u1/ · tests/u3/ …`).
- [ ] `frontend/tests/u1/{setup.ts,support/*}` 공유 준비 — 다른 유닛 시험이 `tests/u1/support`를 import한다(`parentHarness.tsx` 등). `tests/u2/support/accountFixtures.ts` 등.
- [ ] `tools/tests/{u1,u3,u4a,u4b,u11}` 폴더 이름과 시험이 부르는 상대 경로(`tools/tests/u1/scenario-plan.test.mjs`가 `backend/.../application.yml` 읽음 — 이동 대상 아님).
- [ ] 프런트 범위 시험 목록 `frontend/coverage-scope/<이름>.tests`: `practice-legacy` 3개 파일(32건) · `web-push` 6개 파일(89건). 목록 줄 수 = 실행 파일 수, 없는 경로 0(AC-8).
- [ ] 범위 파일 이름: `backend/coverage-scope/*.txt` **18개**(u1 u2 u3 u4a u4b u5a u5b u5b-core u6 u7 u8 u9 u10 u11 u12 u13 u14 u15)와 `frontend/coverage-scope/*.json` **8개**(u1 u2 u3 u4a u5a u5b u6 u7).

### Step 22 — Java 클래스 · 태그 이름

- [ ] 번호를 뺄 Java 파일 103개와 이름 충돌 20건의 목록은 이 단계 첫 커밋의 스크립트가 만든다(미리 만들지 않는다). 운영 클래스 `U2PracticeAccountAdapter` 1개 포함.
- [ ] `@Tag("u…")` 시험 파일 **450개**(실측). 태그 이름은 `-PincludeTags=` 값(`ci.yml`)과 같은 커밋에서 바꾼다.
- [ ] **마이그레이션 파일은 제외**(BR4.3, R-M2): `**/db/migration/**`와 `V<번호>__u…` 이름.
- [ ] 확인 명령: AC-2 ①②(`functional-spec.md` 5절), `백엔드 시험 수 3937건 유지`.

### Step 23 — CI 이름 (`.github/workflows/ci.yml`, 유닛 번호가 든 17줄 실측)

| 줄 | 내용 |
|---|---|
| 140 · 141 | 작업 `u5b-scope` 이름 · `name:` |
| 157 | `-PincludeTags=u5b -PcoverageScope=u5b` (realtime) |
| 161 | `-PincludeTags=u5b -PcoverageScope=u5b-core` (core) |
| 166 | 산출물 이름 `u5b-reports` |
| 175 · 177 · 179 · 182 | 백엔드 `u7` 범위: `hashFiles('backend/coverage-scope/u7.txt')` · `-PincludeTags=u7 -PcoverageScope=u7` · 산출물 `backend-u7-reports` |
| 244 · 245 | `MUJOONG_COVERAGE_SCOPE: u5b` · `vitest run tests/u5b --coverage` |
| 265 · 267 · 268 · 270 · 273 | 프런트 `u7` 범위: `hashFiles('frontend/coverage-scope/u7.json')` · `MUJOONG_COVERAGE_SCOPE: u7` · `vitest run tests/u7 --coverage` · 산출물 `frontend-u7-coverage` |
| 517 | `ci-gate`의 `needs: [changes, backend, u5b-scope, …]` |

- [ ] `hashFiles` 경로는 새 파일 이름과 **정확히** 같아야 한다(다르면 단계가 조용히 건너뛰어진다). 이름 변경 뒤 첫 CI 실행에서 `success`인지 확인한다(AC-9).
- [ ] 브랜치 보호의 필수 검사는 `ci-gate`뿐이라고 팀 규칙(Q6-A)에 적혀 있다. 작업 이름 변경의 자유도는 이 사실을 전제로 한다(스캔은 "미확인"이었음).
- [ ] 확인 명령: `bash infra/bin/infra-validate --only shellcheck,compose-config,secret-env-files`, AC-2 ③ 0줄.

### Step 24 — 주석 정리 (조건부)
- [ ] 건드린 파일 목록에 AC-7 정규식을 다시 돌려 `baseline.md` 4.2의 시작 전 값(2,463줄 · 3,743 출현)과 대조.

### Step 11e — 기능끼리 import 정리 (계획 외 추가, 사람 승인)
- [x] 두 기능 이상이 쓰는 파일 10건을 `parent/common/`(`api/` `activity/` `hooks/` `components/` `copy/`)로 옮기고 import(시험 포함)를 같은 커밋에서 고쳤다. 목록과 새 위치는 `cross-feature-imports.md`.
- [x] 범위 파일 `u1` `u2` `u4a` `u5a` `u5b` `u6`의 경로를 고쳤다(대상 집합 의미 유지, BR4.2). 잘라낸 `startPort.ts` · `common/copy/practice.ts`는 원래 그 파일이 들어 있던 `u5a` · `u5b`에 함께 넣었다.
- [x] `eslint.config.mjs`에 기능 ↔ 기능 import 금지(`import/no-restricted-paths` 구역 + 기능별 `no-restricted-imports` 패턴, 상대 경로 · `@/` 둘 다)를 더했다. 임시 위반 파일 2개(상대 경로 · `@/`)가 두 규칙에서 모두 오류를 내는 것을 확인하고 지웠다.
- [x] 확인: `features/` 안에서 다른 기능을 import하는 줄 0건.

## 3. Flyway · 문서 (바꾸지 않는 것)

- Flyway 파일명(`V<번호>__u…`)은 바꾸지 않는다(R-M2, BR4.3).
- `docs/units/**`, `docs/aidlc_drafts/**`, 이전 작업 기록(`aidlc/**/intents/**`)은 이력 문서라 고치지 않는다(FD-D4).

## 4. 이동 대상을 import하는 프런트 시험 파일 (실측 50개)

> 스캔 표와 설계 문서는 "47개"라고 적었다. 이 PC에서 `frontend/tests`의 `.ts` · `.tsx`를 읽어 따옴표 안 import · `vi.mock` 경로가 `parent/{screens,components,api,practice,hooks,activity,push}` · `device/components`를 가리키는 파일을 세면 **49개**이고, 이동하는 `parent/{navigation,oauth,ParentProviders}`까지 더하면 **50개**다. 차이(47 대 50)의 원인은 확인하지 않았다(스캔 때의 세는 방법이 다른 것으로 보이며, 점검표는 더 넓은 실측 50개를 쓴다). 표의 "대상" 열은 가리키는 이동 대상이다.

| 시험 폴더 | 파일 | 대상 |
|---|---|---|
| `tests/u1/device` | `d0-view.test.tsx` | device/components |
| | `no-service-worker.test.ts` | push |
| | `t19-view.test.tsx` | device/components |
| `tests/u1/lint` | `eslint-boundary.test.ts` | screens · api · push · navigation/oauth/providers |
| `tests/u1/parent` | `api-client.test.ts` · `api-types.test.ts` | api |
| | `code-input.test.tsx` | components |
| | `dev-login.test.tsx` · `done-17.test.tsx` · `free-08.test.tsx` · `register-05a.test.tsx` | screens |
| | `home-04.test.tsx` | screens · practice |
| | `live-12.test.tsx` | screens · api · components |
| | `p1-dialog.test.tsx` | screens · components |
| `tests/u1/shared` | `routes.test.tsx` | screens · practice |
| | `test-ids.test.tsx` | screens · device/components |
| `tests/u1/support` | `parentHarness.tsx` | api · navigation/oauth/providers |
| `tests/u14` | `results.test.ts` | api |
| `tests/u2/parent` | `account-api.test.ts` | screens · api |
| | `back-guard.test.tsx` | screens · hooks |
| | `child-03.test.tsx` · `consent-02.test.tsx` · `home-04-account.test.tsx` · `login-01.test.tsx` · `stop-expressions.test.tsx` | screens |
| | `device-05.test.tsx` | screens · api |
| `tests/u2/shared` | `login-routes.test.tsx` | screens · navigation/oauth/providers |
| | `u2-rules.test.tsx` | screens · components |
| `tests/u3/device` | `reconnect.test.tsx` | device/components |
| `tests/u4a/parent` | `activity-logic.test.ts` | api · activity |
| | `screens-u4a.test.tsx` | screens · api · components |
| `tests/u5a` | `api.test.ts` | api · practice |
| | `fixtures.ts` | practice |
| | `practice-18.test.tsx` | screens · practice |
| `tests/u5b` | `practice-progress.test.tsx` · `practice-start.test.tsx` | practice |
| | `practice-screens.test.tsx` | screens · api · practice |
| `tests/u6/parent` | `api-u6.test.ts` | api · components |
| | `done-17-result.test.tsx` · `record-27.test.tsx` · `records-26.test.tsx` · `report-28.test.tsx` · `report-30.test.tsx` | screens |
| | `hooks-u6.test.tsx` | hooks |
| | `routes-u6.test.tsx` | navigation/oauth/providers |
| `tests/u7/parent` | `browser-push.test.ts` | push |
| | `notification-entry.test.tsx` | screens |
| | `push-api.test.ts` | api |
| | `push-settings.test.tsx` · `settings-account.test.tsx` | components · push |

- 합계: u1 17 + u14 1 + u2 10 + u3 1 + u4a 2 + u5a 3 + u5b 3 + u6 8 + u7 5 = **50**. 프런트 시험은 전체 74파일 · 994건이고, 이 50개 밖의 시험 파일은 위 grep 기준으로 이동 대상 경로를 import하지 않는다.
- 위 목록은 `tests/u5b`(3) · `tests/u7`(`browser-push` `notification-entry` `push-api` `push-settings` `settings-account` 5 + 목록에 있지만 이동 대상을 import하지 않는 `push-worker.test.ts`)의 범위 시험 목록(AC-8)과 겹친다.
- 확인 명령(단계 닫을 때): `cd frontend && grep -rlE "['\"][^'\"]*(parent/(screens|components|api|practice|hooks|activity|push)|device/components)(/|['\"])" tests`가 옛 경로 0건.
