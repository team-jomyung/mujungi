## Developer Code Scan Results

기준: 저장소 루트 `./`, 커밋 570a625(= 원격 main), 브랜치 `claude/refactor-structure`. 읽기 전용 전체 재스캔. 줄 수 · 개수는 `wc` · `grep`으로 센 값이다. 이 의도(구조 · 이름 정리, 동작 변경 없음)에 맞춰 이름 · 경로 문자열 참조와 안전망을 집중해서 적었다.

### Scan Coverage
- **Analyzed deeply**:
  - `docs/refactor-structure-proposal.md` (전체)
  - `backend/settings.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/build-logic/.../mujoong.java-conventions.gradle.kts` (커버리지 범위 · 태그 부분)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/SessionExecutor.java` (구역 표식 · 구역 경계 · 결정 ID 수)
  - `.../session/GuardrailCoordinator.java`, `.../practiceflow/PracticeConversation.java` (구조 표식, 크기)
  - `.../config/RealtimeProperties.java`, `backend/core-api/.../config/CoreProperties.java` (중첩 record 목록)
  - `backend/*/src/test/.../arch/*` (TimeLiteralScanTest, PracticeFlowArchTest, SingleInstanceMarkerTest, RealtimeArchTest, CoreArchTest, LunaArchTest, U8LiveContentArchTest: 문자열 참조 부분), `ConfigKeyContractTest`(경로 부분)
  - `backend/coverage-scope/{u5b,u7,u12}.txt`, `frontend/coverage-scope/{u5b,u7}.json`, `frontend/vitest.config.ts`, `frontend/eslint.config.mjs`, `frontend/package.json`
  - `frontend/src/parent/screens/*`(17개 줄 수 · 이동 · 요청 · 오류 호출 수), `Live12.tsx`(import와 요청 · 오류 · 이동 부분), `parent/navigation.ts`, `parent/practice/*`, `device/components/*`
  - `.github/workflows/ci.yml` (작업 목록, `u5b-scope`, 프런트 범위 단계, 경로 필터), `scripts/verify.sh` 머리 주석, `scripts/live/up.sh` 머리 주석
- **Skimmed only**:
  - `backend/core-api` 패키지 내부(디렉터리와 파일 수만), `backend/contract-model`, `backend/test-support`
  - 나머지 realtime 패키지(파일 수만): `gateway` 21 · `guardrail` 20 · `judgment` 20 · `luna` 15 · `messaging` 18 · `log` 9 · `voice` 9 등
  - `infra/`(compose · nginx · aws · terraform · bin · host · migrate), `ci/security-gate.py`, `scripts/verify/*.sh`, `scripts/live/{down,keys,jev-probe}.sh`
  - `tools/` 내부(`dev-edge` · `device-simulator` · `fake-provider` · `skeleton-e2e`), `contracts/*.md|yaml` 본문(이름 참조 grep만)
  - `docs/units/*` 본문, `.github/workflows/deploy.yml`, Flyway SQL 본문
  - 프런트 `src/app/**` 라우트 페이지 본문, `device/*`(machine · ws · audio · mic)
  - 시험 본문(개수와 이름 · 태그만 셈)

### Packages Found
- `backend/contract-model` — Gradle 모듈(라이브러리) — Java 21 — 두 앱이 공유하는 계약 · 오류(`ErrorCode`) · 시간(`MujoongClock`) · 타입. 운영 55 · 시험 18파일
- `backend/core-api` — Spring Boot 앱 — Java — Core 앱(계정 · 활동 · 기기 · 결과 · 리포트 · 알림 · 지식 · luna). 운영 342 · 시험 247파일(IT 90). 기능 패키지 `core.{account, activity, catalog, child, common, config, consent, device, health, identity, internalhttp, knowledge, luna, messaging, notification, observability, …}`
- `backend/realtime-activity` — Spring Boot 앱 — Java — Realtime 앱(세션 실행 · 음성 · 게이트 · 가드레일 · 판정 · 연습). 운영 261 · 시험 244파일(IT 52). 패키지별 파일 수: `session` 37, `practice` 29(레거시 U5b), `practiceflow` 21(새 흐름 U12), `gateway` 21, `guardrail` 20, `judgment` 20, `messaging` 18, `luna` 15, `log` 9, `voice` 9, `startup` 8, `gate` 7, `web` 6, `dialog` 6, 그 밖
- `backend/test-support` — 시험 전용 모듈 — Java — Testcontainers PostgreSQL · 가짜 공급자 서버(`MujoongPostgres.java` 971줄). 운영 jar에 들어가지 않음(`verifyBootJarHasNoTestClasses`)
- `backend/build-logic` — Gradle 포함 빌드 — Kotlin DSL — `mujoong.java-conventions`(Xlint, 태그 포함/제외, `-PcoverageScope`, JaCoCo)
- `frontend` — Next.js 앱 — TypeScript — `/parent`(보호자 웹) · `/device`(기기 웹) 한 앱. `src` 134파일 11,437줄: `app/` 23개 라우트 폴더, `parent/`(screens 17 · components 16 · api 9 · practice 10 · hooks 4 · activity 4 · push 3), `device/`(audio 6 · components 3 · machine · ws 2 · mic · power · runtime 2 · api), `shared/`(copy 6 · timing 4 · protocol 4 · toast 3 · errors 1 · security · styles)
- `tools` — Node ESM — JavaScript — 기기 시뮬레이터 · 가짜 공급자 · dev-edge · skeleton-e2e · 시드 도구. 시험 12파일 126개
- `infra` — 배포 구성 — Compose · Nginx · 셸 · AWS · Terraform 디렉터리 — 운영 / 검증 / dev / live 구성
- `scripts` — 셸 · Java — `verify.sh`(+`verify/*.sh`, `HostMigrate.java`), `cloud-setup.sh`, 새 `scripts/live/{up,down,keys,jev-probe}.sh`(실키 전체 기동, `infra/compose.live.yml`, 프로젝트 `mujoong-live`)
- `contracts` — 문서 · OpenAPI/AsyncAPI — `parent-api.yaml`, `device-api.yaml`, `internal-api.yaml`, `*.asyncapi.yaml`, `errors.md`, `timing.md`, `ports.md`, `ai-io.md`, `deviations.md`, `schemas/`
- `ci` — Python — `security-gate.py` + `security-exceptions.yml`

### Build System
- **Type**: Gradle 8.14(Kotlin DSL, 멀티 프로젝트 + `build-logic`) / npm(frontend · tools) / Bash(`verify.sh`)
- **Config Files**: `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`, `frontend/package.json` · `next.config.ts` · `tsconfig.json` · `vitest.config.ts` · `eslint.config.mjs`, `tools/package.json` · `vitest.config.mjs`, `.github/workflows/{ci,deploy}.yml`, `.editorconfig`
- **Build Dependencies**: `core-api`, `realtime-activity` → `contract-model`; 둘의 시험 → `test-support`. core-api ↔ realtime-activity는 서로 참조하지 않음(ArchUnit). 프런트는 별도 npm 패키지이고 백엔드와는 `contracts/`로만 만난다.

### APIs Discovered
- 보호자 REST — `contracts/parent-api.yaml` — core-api 컨트롤러(기능 패키지 `web`)
- 기기 REST + WebSocket — `contracts/device-api.yaml`, `device-ws.asyncapi.yaml` — realtime `gateway`
- 내부 HTTP — `contracts/internal-api.yaml` — 두 앱 사이(`internalhttp`)
- 이벤트(발신함 · 수신함) — `contracts/events.asyncapi.yaml` — `messaging`
- 외부 AI — `contracts/ai-io.md`: GPT-Live(realtime `voice`/`luna`), Luna, 제브 가드레일(`guardrail`), 지식 그래프. 시험 · CI는 가짜 공급자만 쓴다(R-F1).
- 정확한 엔드포인트 수는 이번 스캔에서 세지 않았다(yaml 본문 미정독).

### Frameworks & Libraries
- Spring Boot — 4.1.1 — 두 앱(webmvc · websocket · security · session-jdbc · data-jpa · jdbc · flyway · actuator · validation)
- Jackson 3(`tools.jackson`) — 3.1.7 BOM — JSON(J139 한 곳 규칙); Jackson 2 BOM 2.22.3은 보안 패치 덮어쓰기
- ArchUnit 1.5.1 · JaCoCo 0.8.14 · json-schema-validator 3.0.6 · Java-WebSocket 1.6.0 · springdoc 3.1.1(dev · test) · push2u 0.3.0
- Java 21, Flyway(core V1~V10, realtime V1~V7 존재)
- Next.js 16.4.0 · React 19.3.0 · @tanstack/react-query 5.104.0 · TypeScript 5.9.3 · Vitest 4.1.11(v8 커버리지) · Testing Library · jsdom 29 · ESLint 9.39.5 · Node >=22 <23

### Test Coverage
- **Test Directories**:
  - `backend/{contract-model,core-api,realtime-activity,test-support}/src/test`
  - `frontend/tests/{u1,u2,u3,u4a,u5a,u5b,u6,u7,u14}`(87파일 중 `*.test.*` 74개)
  - `tools/tests/{u1,u3,u4a,u4b,u11}`
- **개수**(어노테이션 · `it(` 기준 근사):
  - contract-model @Test 155, core-api 1,530(시험 247파일, IT 90), realtime-activity 1,511(시험 244파일, IT 52), test-support 28. 백엔드 합계 약 3,224
  - frontend 755개(74파일), tools 126개(12파일)
- **태그**: `@Tag` 사용 수 — u1 142, db 134, u5b 33, u11 28, u4b 24, u3 23, u6 21, u13 20, u10 20, u12 18, u8 17, u7 17, u9 16, u2 15, u5a 9, u14 9, u15 6, u4a 5, 실호출 제외 태그 `luna-live`/`luna-reach`/`jev-live`/`gpt-live`(빌드가 기본 제외를 단언함)
- **Test Frameworks**: JUnit 5 · AssertJ · Mockito · ArchUnit · Spring Boot Test · Testcontainers(`-Pmujoong.test.db=docker`) / Vitest + Testing Library / Vitest(tools)
- **Coverage Config**: 있음
  - 백엔드: 모듈 전체 JaCoCo 줄 80%(`contract-model` · `core-api` · `realtime-activity`), `-PcoverageScope=<이름>`이면 `backend/coverage-scope/<이름>.txt`(경로 목록)만 측정. 범위 파일 18개(u1~u15, u4a, u4b, u5a, u5b, u5b-core 포함), 줄 합계 898
  - 프런트: Vitest v8 줄 80%, `MUJOONG_COVERAGE_SCOPE=<이름>`이면 `frontend/coverage-scope/<이름>.json`의 `files[]`만 측정(8개: u1~u4a, u5a, u5b, u6, u7). 목록이 비었거나 없는 파일이 있으면 실행이 실패한다.
  - CI가 막는 범위는 u5b(백엔드 `u5b` · `u5b-core`, 프런트 `u5b`)와 u7뿐이다.

### Code Quality Indicators
- **Linting**: 프런트 ESLint flat config(`frontend/eslint.config.mjs`: `no-restricted-imports` + `import/no-restricted-paths`로 `/parent` ↔ `/device` ↔ `shared` 경계, `jsx-no-literals`, `getUserMedia` · `serviceWorker` 제한). 백엔드는 `-Xlint`만 있고 포매터 · 줄 길이 검사는 없다. Prettier 설정은 있으나 실행되지 않는다. 셸은 `infra-validate`의 shellcheck.
- **CI/CD**: `.github/workflows/ci.yml` 작업 14개 — `changes`, `backend`, `u5b-scope`, `backend-deps`, `frontend`, `frontend-deps`, `tools`, `secrets`, `infra-validate`, `images`, `skeleton`(main 푸시만), `ci-gate`(필수 검사), `deploy`. 경로 필터가 `^backend/`, `^frontend/`, `contracts/`, `.github/` 등을 본다. `deploy.yml` 별도.
- **Documentation**: `README.md`, `AGENTS.md`, `docs/units/<유닛>/{brief,design,report}.md`, `docs/change-history.md`, `docs/live-run.md`. Java 클래스 머리 Javadoc 풍부. 코드 주석이 결정 · 요구 ID를 인용하는 것이 팀 규칙이다.

### Technical Debt Signals
- 과밀 파일: `SessionExecutor.java` 4,361줄(두 번째로 큰 운영 파일 `PracticeConversation` 862줄의 5배), 메서드 선언 약 276, 안의 결정 ID(J · U · BR · FR · NFR · R-) 약 315회. 그 밖의 큰 운영 파일: `GuardrailCoordinator` 759, `FileAudioRecorder` 619, `CoreProperties` 589, `RealtimeProperties` 570. 큰 시험: `GptLiveProbe`(시험 소스 폴더, 실호출 도구) 2,072, `PracticeConversationTest` 966.
- 프런트 `src`에 결정 · 요구 ID 약 407회.
- `realtime.practice`(레거시 29파일)와 `realtime.practiceflow`(새 흐름 21파일) 이름 혼동. 실행부 → 흐름 한 방향만 허용(`EXECUTOR_KNOWS_NO_FLOW`).
- `CoreProperties.java:153`에서 `PracticeTiming(Duration a,Duration b)`처럼 서식이 압축된 곳이 있다(Java 포매터 없음).
- 프런트 문구 위치가 셋으로 갈라짐: `shared/copy/*`(6), `parent/practice/copy.ts`, `parent/practice/u5bCopy.ts`. 연습 요청도 `parent/api/*.ts`와 `parent/practice/{api,startApi}.ts`로 갈라짐.
- 문서 낡음: `team.md`는 다음 Flyway 번호를 core V8 · realtime V5라 적지만 실제로는 core V10, realtime V7까지 있다(다음은 V11 · V8). 이번 리팩토링과 직접 관련은 없다.
- `scripts/live/*`와 `infra/compose.live.yml`이 새로 생겼다(ops/live-run, 실키). 이름 정리 대상 코드 경로를 참조하지 않는다.

## 이번 의도(구조 · 이름 정리)용 수치

### (1) 큰 파일과 책임 구역
`SessionExecutor.java` 구역(`// ---- 제목` 표식 기준, 줄 범위):

| 구역 | 줄 범위 | 줄 수 |
|---|---|---|
| 머리(필드 · 생성자 · 내부 타입) | 1~619 | 619 |
| 실행 | 620~694 | 75 |
| 외부 입구 | 695~780 | 86 |
| 메시지 처리 | 781~1016 | 236 |
| 참여 확인(U4b L3) | 1017~1094 | 78 |
| U11 판정 턴(W1 · W2 · W4) | 1095~1354 | 260 |
| 연습 확장점(U4b BR10.1~BR10.6) | 1355~2808 | 1,454 |
| 공백 시계 · 기술 일시정지 · 진행 조회(U4b L4) | 2809~3060 | 252 |
| 게이트 | 3061~3334 | 274 |
| 기기 표식 · 끝내기 | 3335~3838 | 504 |
| 시계 | 3839~4022 | 184 |
| 재전달 · 최상위 예외 | 4023~4079 | 57 |
| 전이 보조 | 4080~4361 | 282 |

- 제안서의 구역 줄 수와 일치한다. 제안서에 없던 것은 머리 619줄(필드 · 생성자)과 끝의 `전이 보조` 282줄이며, 이 둘은 분리 대상에 아직 배정되지 않았다.
- `SessionExecutor`를 직접 부르는 시험은 약 30파일이다(`SessionHarness`, `SessionExecutorTest` 계열, `Judgment*ExecutorTest`, `PracticeExtensionU4bTest`, `GuardrailCoordinatorTest` 등). 분리하면 `SessionHarness`가 영향받는다.
- `GuardrailCoordinator` 759줄에는 구역 표식이 없고 내부 타입 `ReplayableInstruction` · `Env`(인터페이스) · `Ticket` · `Queued`가 있다. `PracticeConversation` 862줄에도 구역 표식이 없고 내부 record는 `ScriptResult` 하나다.
- 설정 클래스 구조: `RealtimeProperties` 570줄에 중첩 record 약 25개(`Timing`+하위 8, `Outbox`, `Inbox`, `Retention`, `Cleanup`, `Session`, `Device`, `InternalHttp`, `Provider`, `Health`, `Limits`, `Judgment`, `PracticeFlow`, `Gate`, `Recording`, `Transcript`, `Security`, `Metrics`, `Observability`). `CoreProperties` 589줄에 약 30개. 별도 소형 설정: `PracticeProperties` 28 · `GuardrailProperties` 38 · `LunaProperties`(realtime 74, core 93) · `TextAiProperties` 39. 설정 키 이름은 `contracts/timing.md` · `application.yml`(realtime 236줄, core 269줄)과 `ConfigKeyContractTest`가 묶고 있다.

### (2) 이름 규칙 현황
- 프런트 화면: `parent/screens/` 17개 중 번호 접미 14개(`Login01`, `Consent02`, `Child03`, `Home04`, `Device05`, `Register05a`, `Free08`, `Live12`, `Done17`, `Practice18`, `Records26`, `RecordDetail27`, `ReportRange28`, `Report30`, `StopExpressions01`은 SET-01), 번호 없는 것은 `DevLogin`, `LoginCallback`. 기기: `device/components/{T19View, D0View, DebugLog}`. `parent/components/TextArea200.tsx`(숫자는 글자 수 한도).
- 이 화면 이름이 코드에서 쓰이는 횟수: Home04 45, Device05 30, Live12 28, Child03 27, Done17 23, Login01 · Free08 · Consent02 각 18, Practice18 · StopExpressions01 각 16 등(주석과 import 포함). 라우트 페이지(`src/app/parent/**/page.tsx` 17개 안팎)가 화면을 import한다. 시험 중 29파일이 `screens/`를 경로로 부른다.
- 유닛 번호 이름: `parent/practice/u5bCopy.ts`(import처: `Live12`, `PracticeStartAction`, `PracticeHeader`, `practiceProgress`, `tests/u5b/practice-progress.test.tsx`), `frontend/tests/u1~u14`(폴더 9개: u1 · u2 · u3 · u4a · u5a · u5b · u6 · u7 · u14), `tools/tests/{u1,u3,u4a,u4b,u11}`, `backend/coverage-scope/*.txt` 18개, `frontend/coverage-scope/*.json` 8개. 이름에 유닛 표식을 가진 파일이 `backend`·`frontend`·`tools`에 약 134개, Java 클래스 중 `U<번호>`가 이름에 든 것 63개(예: `U4aActivityApiIT`, `SessionStopU4bTest`, `U8StartContentPinTest`).
- 시험 공용 준비 파일이 `frontend/tests/u1/setup.ts`다(`vitest.config.ts`가 문자열로 부름). `tests/u1/support`(9파일)는 다른 유닛 시험이 공유한다.
- 백엔드: 기능 패키지 + 접미어(`*Service` · `*Repository` · `*Port` · `*Adapter` · `*Configuration` · `*Participant`). 시험 `*Test`/`*IT`. 마이그레이션 `V<n>__u<번호>_<이름>.sql`(유닛 번호가 파일명에 있음).

### (3) 프런트 화면의 요청 · 오류 · 이동 위치
- 화면별 줄 수 / 이동 호출(`router.push|replace|back`) / 요청 호출 / 오류 처리 호출: `Live12` 274 / 9 / 9 / 8, `Home04` 237 / 10 / 4 / 0, `Done17` 138 / 6 / 6 / 4, `Device05` 239 / 4 / 3 / 2, `Child03` 292 / 2 / 5 / 3, `Consent02` 211 / 2 / 8 / 5, `StopExpressions01` 256 / 2 / 5 / 3, `Free08` 222 / 2 / 0 / 4, `Register05a` 150 / 3 / 2 / 5. (제안서의 "Home04 11~12곳"과 약간 다른데 정규식 차이다.)
- `Live12`는 `useQuery`/`useQueryClient`, `fetchLiveView` · `endSession` · `fetchHome` · `queryKeys`(`parent/api/queries.ts`), `ApiError`(`parent/api/apiClient.ts`), `useLivePolling`, `PARENT_ROUTES` · `goToLogin`(`parent/navigation.ts`), `messageForErrorCode`(`shared/errors/codeToCopy.ts`)를 한 파일에서 모두 쓴다. 404 · 409 `SESSION_NOT_ACTIVE` · 401 분기와 `router.replace`가 같은 `catch` 안에 섞여 있다(150~175줄 부근).
- 요청 계층: `parent/api/{account,activity,apiClient,csrf,push,queries,reports,results,types}.ts` 9개(`apiClient` 165줄, `queries` 139줄, `types` 391줄). 연습만 `parent/practice/{api,startApi}.ts`로 따로 있다. 오류 → 문구는 `shared/errors/codeToCopy.ts`(65줄) 한 곳이 이미 있다.
- 이동 상수: `parent/navigation.ts`의 `PARENT_ROUTES`(경로 문자열 한 곳). 헤더 주석이 화면 번호(01~05a · 08 · 12 · 17)를 라우트와 짝지어 적는다.
- 훅은 `parent/hooks/`에 4개(`useBackGuard`, `useLivePolling`, `useReportPolling`, `useResultStatusPolling`)뿐이고 `useXxxScreen` 형태의 화면 훅은 없다.

### (4) 이름 · 경로를 문자열로 부르는 곳(이름 바꾸면 깨지는 곳)
- **ArchUnit**(패키지 · 클래스 이름 문자열):
  - `PracticeFlowArchTest` — `"..practiceflow.."`, `"kr.mujoong.realtime.practice.."`, `"kr.mujoong.realtime.session."` 접두, `PracticeSessionExtension` 접두, `"kr.mujoong.realtime.judgment.JudgmentPoint"`, `PracticeConversation.class`. `practice`/`practiceflow` 이름을 바꾸면 이 규칙이 비거나 틀린다(`allowEmptyShould(false)` · "15개 초과" 단언이 막아 주기는 한다).
  - `RealtimeArchTest` — `kr.mujoong.realtime.{voice, gate, gateway, messaging, common.db}` 접두 문자열, `LunaArchTest`(`..luna..` · `..guardrail..`), `U8LiveContentArchTest`(`kr.mujoong.realtime.content..`), `CoreArchTest`(`kr.mujoong.core`).
  - `SingleInstanceMarkerTest` — `REALTIME_SHARE`가 `SessionExecutor.class`를 SI-2 · SI-9에 직접 넣는다. `SessionExecutor`에서 클래스를 나누면 표식 어노테이션(`SingleInstanceAssumption`)을 받는 클래스가 바뀌어 이 표가 같이 바뀌어야 한다.
- **`TimeLiteralScanTest`**(realtime 291줄 18건, core 301줄 20건): 허용 목록이 `파일 경로 + 소스 조각 + 사유`를 문자열로 갖고, "목록 항목이 실제 줄에 아직 걸리는가", "파일이 실제로 있는가" 두 단언이 있다. realtime 허용 파일: `runtime/PeriodicJobs`, `messaging/RealtimeInboxDispatcher`, `common/db/DbRetry`, `voice/{InputRelay, ProviderClient}`, `session/{EndRequestHandler, StartSessionHandler, SessionMailbox}`, `startup/DrainingLifecycle`, `luna/LunaTransport`, `guardrail/JevGuardrailClient`, `contract-model time/DelayQueueTimerDriver`. 즉 `SessionExecutor` 자체는 허용 목록에 없다(시간 값을 설정에서 읽는다). 하지만 분리해서 만든 새 클래스는 이 검사의 대상이 되며, 파일을 옮기면 위 경로가 깨진다.
- **`ConfigKeyContractTest`**(588줄): `backend/realtime-activity/src/main/resources/application.yml`과 `src/test/resources/application-test.yml`, `contracts/timing.md` · `contracts/ports.md` 표를 경로 문자열로 읽고 `RealtimeProperties` 바인딩과 대조한다. 설정 레코드를 쪼개도 키 이름이 같으면 통과해야 한다(바인딩 prefix가 같아야 함).
- **커버리지 범위 파일**: `backend/coverage-scope/*.txt`는 `backend/<모듈>/src/main/java/kr/mujoong/...` 전체 경로를 한 줄씩 적는다(`u5b.txt` 32줄은 `realtime/practice/*` 클래스들). `practice` 패키지나 클래스를 옮기면 u5b 범위가 깨져 CI `u5b-scope`가 실패한다. 프런트 `coverage-scope/u5b.json`은 `frontend/src/parent/practice/{startApi,u5bCopy,practiceStart,PracticeStartAction,practiceProgress,PracticeHeader}`를, `u7.json`은 `parent/screens/Home04.tsx`, `components/SettingsSheet.tsx` 등을 적는다. 범위 측정은 없는 파일이면 실패한다(J137).
- **`.github/workflows/ci.yml`**: `u5b-scope` 작업이 `-PincludeTags=u5b -PcoverageScope=u5b|u5b-core|u7` 이름을 쓴다. 프런트 단계가 `MUJOONG_COVERAGE_SCOPE: u5b`와 `vitest run tests/u5b`, `tests/u7`를 경로로 쓴다. `hashFiles('backend/coverage-scope/u7.txt')`, `hashFiles('frontend/coverage-scope/u7.json')`도 경로 문자열이다. `ci-gate`의 `needs`에 작업 이름 목록(`u5b-scope` 포함)이 있다. 작업 이름은 브랜치 보호의 필수 검사 이름이 `ci-gate`뿐인지(=작업 이름 변경이 자유로운지) 확인이 필요하다.
- **`tests/u1/setup.ts`** 경로를 `vitest.config.ts`가 부르고, `scripts/verify/a-checks.sh` 76줄은 `tests/u1 · tests/u3 …` 유닛별 폴더를 돌린다고 주석에 적고 `vitest run tests`로 전체를 돈다. `tools/vitest.config.mjs`도 `tests/u…`를 언급한다.
- **ESLint 경계**: `frontend/eslint.config.mjs`의 `src/parent/**`, `src/device/**`, `src/shared/**`, `src/app/{parent,device}/**` glob, `src/parent/push/browserPush.ts`와 `src/device/mic/micPermission.ts` 예외 파일 경로. `frontend/tests/u1/lint/eslint-boundary.test.ts`가 경계를 시험한다. `parent/features/<기능>/` 같은 새 폴더도 `src/parent/**` glob에 걸리지만 `browserPush.ts`/`micPermission.ts`를 옮기면 예외 규칙이 풀린다.
- **`infra/` · `scripts/`**: `infra/bin/render-env` 70줄 주석이 `frontend/src/app/parent/login/callback` 경로를 적는다(주석). 소스 경로를 코드로 부르는 인프라 스크립트는 이번에 찾지 못했다.
- **`contracts/*.md`**: 클래스 이름 참조는 `contracts/deviations.md`의 `SessionExecutor` 언급 1곳(52행, 문서 문구) 외에 찾지 못했다. 오류 코드 · 설정 키 · 시간 값 계약은 이름과 무관하게 유지된다.
- **`docs/`**: `frontend/src` · `backend/.../src/main` 경로를 쓰는 문서 18개, 화면 파일 이름(`Live12` 등)을 쓰는 문서 3개, `SessionExecutor`를 적는 문서 `docs/units/{u3,u4b,u5b,u11,u12,u13}/*` 15개 안팎과 `contracts/deviations.md`. 이력 문서라 이름 변경과 함께 고칠지는 별도 결정이다.
- **Flyway 파일명**의 유닛 번호(`V7__u5b_...`)는 적용 이력(체크섬)과 묶여 있어 이번 의도에서 바꾸면 안 된다(R-M2).

### (5) 기존 안전망
- 시험 규모: 백엔드 약 3,224(contract-model 155 · core-api 1,530 · realtime-activity 1,511 · test-support 28), 프런트 755, tools 126.
- ArchUnit: core 5파일(`CoreArchTest` 453, `TimeLiteralScanTest` 301, `SingleInstanceMarkerTest` 375, `LunaArchTest`, `U2SingleSourceTest`), realtime 5파일(`RealtimeArchTest` 525, `SingleInstanceMarkerTest` 462, `TimeLiteralScanTest` 291, `PracticeFlowArchTest` 124, `LunaArchTest`, `U8LiveContentArchTest`), contract-model `ContractModelArchTest` 305. 합계 12파일 3,163줄.
- 계약 대조: `ContractFilesTest`, `ErrorCodeTest`, `ConfigKeyContractTest`, `TimeLiteralScanTest`, `OpenApiContracts`, `frontend/tests/u1/shared/timing.test.ts`, `SeedDictionaryFileTest`.
- ESLint 영역 경계 + `tests/u1/lint/eslint-boundary.test.ts`.
- 모듈 전체 줄 커버리지 80%(백엔드 JaCoCo, 프런트 v8), CI가 막는 유닛 범위 u5b · u7.
- `scripts/verify.sh`: A1 backend(`clean check verifyBootJarHasNoTestClasses`) → A2 frontend(`eslint .` · `vitest run tests --coverage` · `next build`) → A3 tools → A4 비밀 스캔 → F 지문 → B 기동(compose 또는 host) → C 기기 시뮬레이터 한 턴(C1~C12) → P1 교차 스키마 → P2 로그 위생 → P3 비밀 값 → T 작업 트리 동일. CI `skeleton` 작업은 main 푸시 때만 돈다.
- 구멍: `SessionExecutor` 전용 특성화 시험은 `SessionExecutorTest`, `SessionExecutorU3Test`, `SessionExecutorReview{,2,3}U3Test`, `SessionExecutorFaultTest` 등으로 존재하나, 구역 사이 호출 순서(메시지 수신 → 참여 확인 → 판정 → 확장점 → 게이트 → 끝내기)를 고정하는 시험이 따로 있는지는 읽지 않았다. 프런트에는 화면 컴포넌트 시험이 `tests/u2~u7`에 흩어져 있고, `Live12`의 시험은 `tests/u3`가 아니라 `tests/u1/parent` 쪽일 수 있으며 확인하지 못했다. 이름이 바뀌는 1단계 이동에서는 import 경로(`@/…`와 상대 경로 223건 이상)가 컴파일 · `next build` · vitest로 잡힌다.

## Handoff Summary
- **Intent-relevant finding**: 구조 정리의 핵심 위험은 이름이 문자열로 박힌 곳이다. 확인된 곳은 다음과 같다.
  - `SessionExecutor.java` 4,361줄은 13개 구역(연습 확장점 1,454줄이 가장 큼)이고, `SingleInstanceMarkerTest`의 SI-2 · SI-9 표가 클래스를 직접 가리킨다.
  - `PracticeFlowArchTest`는 패키지 이름 `practice` · `practiceflow`를 문자열로 가지고, `backend/coverage-scope/u5b.txt` 32줄은 `realtime/practice/*` 전체 경로를 적는다. 프런트 `coverage-scope/{u5b,u7}.json`, `ci.yml`의 `u5b-scope` 작업과 `tests/u5b`, `tests/u7` 경로, `vitest.config.ts`의 `tests/u1/setup.ts`도 같다.
  - `TimeLiteralScanTest`(realtime 18건 · core 20건)는 허용 파일 경로를 문자열로 가진다.
  - 프런트 화면 번호 이름은 코드 · 주석에 수백 회, 시험 29파일에 경로로 들어 있고, `Live12`/`Home04`에는 요청 · 오류 · 이동 처리가 한 파일에 섞여 있다. 오류 → 문구 변환 한 길(`shared/errors/codeToCopy.ts`)과 `PARENT_ROUTES`는 이미 있다.
- **Risks / follow-up**:
  - 제안서 3-1의 구역 표는 실제 구역과 맞다. 다만 머리 619줄(필드 · 생성자)과 끝 `전이 보조` 282줄은 배정이 없고, 구역 사이 공유 필드 때문에 분리 경계 조정이 필요하다. 제안서 한계 절의 지적과 같다.
  - 클래스를 나누면 `SingleInstanceMarkerTest` 표, `TimeLiteralScanTest` 허용 목록(새 클래스가 시간 값을 쓰는 경우), `SessionHarness` 및 약 30개 시험의 생성 방식을 같은 PR에서 맞춰야 한다. R-F2에 따라 규칙을 풀거나 건너뛰면 안 된다.
  - `practice`/`practiceflow` 이름 변경은 `PracticeFlowArchTest`, `u5b.txt`, `u12.txt`, `u5b-core.txt` 등 범위 파일(18개 중 해당분), `RealtimeArchTest` 접두 문자열을 모두 건드린다. `u5b-scope` CI 작업 이름 · 범위 파일 이름 변경은 같은 PR에서 해야 한다.
  - 마이그레이션 파일 이름과 `contracts/` 계약 값(오류 코드 · 설정 키 · 시간 값)은 바꾸지 않는다(R-M2, 제안서 5절).
  - `team.md`의 "코드 주석은 결정 ID를 인용" 규칙을 바꾸는 일은 학습 절차를 거쳐야 하며, 이 스캔은 규칙 파일을 건드리지 않았다.
  - 스캔 한계: 엔드포인트 수, `core-api` 내부, `contracts` 본문, `docs` 본문, 인프라 스크립트 내부는 깊게 읽지 않았다. `Live12` 시험의 정확한 위치와 실행기 순서 특성화 시험의 유무는 미확인이다. 정규식 기반 개수(이동 호출 · 결정 ID)는 근사값이다.
