# Code Structure

> 기준: 커밋 570a625. 줄 수 · 개수는 `wc` · `grep` 값이고 정규식 기반 개수는 근사다. 컴포넌트 책임은 `component-inventory.md`, 품질 판단과 위험은 `code-quality-assessment.md`가 정본이다.

## 최상위 구성

| 경로 | 내용 | 규모 |
|---|---|---|
| `backend/` | Gradle 멀티 프로젝트(`contract-model`, `core-api`, `realtime-activity`, `test-support`, `build-logic`, `coverage-scope/`) | 운영 파일: contract-model 55, core-api 342, realtime-activity 261 |
| `frontend/` | Next.js 앱 한 개(`src/app`, `src/parent`, `src/device`, `src/shared`, `tests/`, `coverage-scope/`) | `src` 134파일 11,437줄 |
| `tools/` | Node ESM 도구와 시험 | 시험 12파일 126개 |
| `contracts/` | OpenAPI · AsyncAPI · 계약 문서 | `component-inventory.md`의 contracts 항목 |
| `infra/`, `scripts/`, `ci/`, `docs/` | 배포 구성, 검증 · 실키 기동 스크립트, 보안 게이트, 문서 | |

## 백엔드 패키지 구성

- 기능 패키지 + 접미어: `*Service` · `*Repository` · `*Port`(기능 사이 경계) · `*Adapter`(구현) · `*Configuration` · `*Participant`. 컨트롤러는 기능 패키지 아래 `web`.
- core-api: `core.{account, activity, catalog, child, common, config, consent, device, health, identity, internalhttp, knowledge, luna, messaging, notification, observability, …}`.
- realtime-activity 패키지별 파일 수: `session` 37, `practice` 29(레거시 U5b), `practiceflow` 21(새 흐름 U12), `gateway` 21, `guardrail` 20, `judgment` 20, `messaging` 18, `luna` 15, `log` 9, `voice` 9, `startup` 8, `gate` 7, `web` 6, `dialog` 6.
- 마이그레이션: `V<n>__u<번호>_<이름>.sql`(유닛 번호가 파일명에 있고 적용 이력과 묶여 있어 바꾸면 안 된다, R-M2). core V1~V10, realtime V1~V7.

## 큰 파일과 책임 구역

### SessionExecutor.java (4,361줄, 메서드 선언 약 276, 결정 ID 약 315회)

구역은 `// ---- 제목` 표식 기준이다.

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

- 제안서 3-1의 구역 줄 수와 일치한다. 제안서에 배정이 없는 것은 머리 619줄(필드 · 생성자)과 끝의 `전이 보조` 282줄이다. 구역 사이 공유 필드 때문에 분리 경계 조정이 필요하다.
- `SessionExecutor`를 직접 부르는 시험은 약 30파일이다(`SessionHarness`, `SessionExecutorTest` 계열, `Judgment*ExecutorTest`, `PracticeExtensionU4bTest`, `GuardrailCoordinatorTest` 등).

### 그 밖의 큰 파일

| 파일 | 줄 수 | 구조 메모 |
|---|---|---|
| `realtime/practiceflow/PracticeConversation.java` | 862 | 구역 표식 없음, 내부 record는 `ScriptResult` 하나 |
| `realtime/session/GuardrailCoordinator.java` | 759 | 구역 표식 없음, 내부 타입 `ReplayableInstruction` · `Env`(인터페이스) · `Ticket` · `Queued` |
| `realtime FileAudioRecorder` | 619 | |
| `core/config/CoreProperties.java` | 589 | 중첩 record 약 30개 |
| `realtime/config/RealtimeProperties.java` | 570 | 중첩 record 약 25개(`Timing`+하위 8, `Outbox`, `Inbox`, `Retention`, `Cleanup`, `Session`, `Device`, `InternalHttp`, `Provider`, `Health`, `Limits`, `Judgment`, `PracticeFlow`, `Gate`, `Recording`, `Transcript`, `Security`, `Metrics`, `Observability`) |
| `test-support MujoongPostgres.java` | 971 | 시험 지원 |
| `GptLiveProbe`(시험 소스, 실호출 도구) | 2,072 | |
| `PracticeConversationTest` | 966 | |

별도 소형 설정: `PracticeProperties` 28, `GuardrailProperties` 38, `LunaProperties`(realtime 74 · core 93), `TextAiProperties` 39. 설정 키는 `application.yml`(realtime 236줄, core 269줄) · `contracts/timing.md` · `ConfigKeyContractTest`가 묶는다.

## 프런트 구성

- `src/app/`: 라우트 폴더 23개(`parent/**/page.tsx` 17개 안팎이 화면을 import).
- `src/parent/`: `screens` 17, `components` 16, `api` 9, `practice` 10, `hooks` 4, `activity` 4, `push` 3.
- `src/device/`: `audio` 6, `components` 3, `machine`, `ws` 2, `mic`, `power`, `runtime` 2, `api`.
- `src/shared/`: `copy` 6, `timing` 4, `protocol` 4, `toast` 3, `errors` 1, `security`, `styles`.

### 화면의 요청 · 오류 · 이동 위치

| 화면 | 줄 수 | 이동 호출 | 요청 호출 | 오류 처리 호출 |
|---|---|---|---|---|
| `Live12` | 274 | 9 | 9 | 8 |
| `Home04` | 237 | 10 | 4 | 0 |
| `Done17` | 138 | 6 | 6 | 4 |
| `Device05` | 239 | 4 | 3 | 2 |
| `Child03` | 292 | 2 | 5 | 3 |
| `Consent02` | 211 | 2 | 8 | 5 |
| `StopExpressions01` | 256 | 2 | 5 | 3 |
| `Free08` | 222 | 2 | 0 | 4 |
| `Register05a` | 150 | 3 | 2 | 5 |

- 이동 호출은 `router.push|replace|back` 정규식 근사값이다(제안서의 "Home04 11~12곳"과 약간 다름).
- `Live12`는 `useQuery` · `useQueryClient`, `fetchLiveView` · `endSession` · `fetchHome` · `queryKeys`, `ApiError`, `useLivePolling`, `PARENT_ROUTES` · `goToLogin`, `messageForErrorCode`를 한 파일에서 쓴다. 404 · 409 `SESSION_NOT_ACTIVE` · 401 분기와 `router.replace`가 같은 `catch` 안에 있다(150~175줄 부근).
- 이미 있는 한 길: 요청 계층 `parent/api/{account,activity,apiClient,csrf,push,queries,reports,results,types}.ts`(`apiClient` 165, `queries` 139, `types` 391줄), 오류 → 문구 `shared/errors/codeToCopy.ts`(65줄), 이동 상수 `parent/navigation.ts`의 `PARENT_ROUTES`. 훅은 `parent/hooks/` 4개뿐이고 화면 훅(`useXxxScreen`)은 없다.
- 갈라진 곳: 연습 요청은 `parent/practice/{api,startApi}.ts`, 문구는 `shared/copy/*`(6) · `parent/practice/copy.ts` · `parent/practice/u5bCopy.ts`.

## 이름 규칙 현황

- 프런트 화면: `parent/screens/` 17개 중 번호 접미 14개(`Login01`, `Consent02`, `Child03`, `Home04`, `Device05`, `Register05a`, `Free08`, `Live12`, `Done17`, `Practice18`, `Records26`, `RecordDetail27`, `ReportRange28`, `Report30`), `StopExpressions01`은 SET-01, 번호 없는 것은 `DevLogin` · `LoginCallback`. 기기: `device/components/{T19View, D0View, DebugLog}`. `parent/components/TextArea200.tsx`의 숫자는 글자 수 한도다.
- 화면 이름이 코드에서 쓰이는 횟수(주석 · import 포함): Home04 45, Device05 30, Live12 28, Child03 27, Done17 23, Login01 · Free08 · Consent02 각 18, Practice18 · StopExpressions01 각 16. 시험 29파일이 `screens/`를 경로로 부른다.
- 유닛 번호 이름: `parent/practice/u5bCopy.ts`(import처 `Live12`, `PracticeStartAction`, `PracticeHeader`, `practiceProgress`, `tests/u5b/practice-progress.test.tsx`), `frontend/tests/{u1,u2,u3,u4a,u5a,u5b,u6,u7,u14}`, `tools/tests/{u1,u3,u4a,u4b,u11}`, `backend/coverage-scope/*.txt` 18개, `frontend/coverage-scope/*.json` 8개. 유닛 표식 파일이 `backend` · `frontend` · `tools`에 약 134개, 이름에 `U<번호>`가 든 Java 클래스 63개(예 `U4aActivityApiIT`, `SessionStopU4bTest`, `U8StartContentPinTest`).
- 시험 공용 준비: `frontend/tests/u1/setup.ts`(`vitest.config.ts`가 문자열로 부름), `tests/u1/support`(9파일, 다른 유닛 시험이 공유).
- 팀 규칙: 프런트 화면 파일은 화면 설계 번호를 이름 끝에 붙인다. 코드 주석은 결정 ID를 인용한다(둘 다 `team.md`). 이를 바꾸는 일은 학습 절차를 거친다.

## 이름 · 경로를 문자열로 부르는 곳(이름을 바꾸면 깨지는 곳)

| 위치 | 문자열 참조 | 영향 |
|---|---|---|
| `PracticeFlowArchTest` | `"..practiceflow.."`, `"kr.mujoong.realtime.practice.."`, `"kr.mujoong.realtime.session."`, `PracticeSessionExtension` 접두, `"kr.mujoong.realtime.judgment.JudgmentPoint"`, `PracticeConversation.class` | `practice` · `practiceflow` 이름을 바꾸면 규칙이 비거나 틀림(`allowEmptyShould(false)` · "15개 초과" 단언이 막아 줌) |
| `RealtimeArchTest` 외 | `kr.mujoong.realtime.{voice, gate, gateway, messaging, common.db}`, `LunaArchTest`(`..luna..` · `..guardrail..`), `U8LiveContentArchTest`(`kr.mujoong.realtime.content..`), `CoreArchTest`(`kr.mujoong.core`) | 패키지 이동 시 같이 수정 |
| `SingleInstanceMarkerTest` | `REALTIME_SHARE`가 `SessionExecutor.class`를 SI-2 · SI-9에 직접 넣음 | 클래스를 나누면 표식을 받는 클래스가 바뀌므로 표도 같이 |
| `TimeLiteralScanTest`(realtime 291줄 18건, core 301줄 20건) | 허용 목록이 파일 경로 + 소스 조각 + 사유 문자열. "항목이 아직 줄에 걸리는가" · "파일이 있는가" 단언 | 허용 파일: `runtime/PeriodicJobs`, `messaging/RealtimeInboxDispatcher`, `common/db/DbRetry`, `voice/{InputRelay, ProviderClient}`, `session/{EndRequestHandler, StartSessionHandler, SessionMailbox}`, `startup/DrainingLifecycle`, `luna/LunaTransport`, `guardrail/JevGuardrailClient`, `contract-model time/DelayQueueTimerDriver`. `SessionExecutor`는 목록에 없음. 새 분리 클래스는 검사 대상 |
| `ConfigKeyContractTest`(588줄) | realtime `application.yml` · `application-test.yml`, `contracts/timing.md` · `ports.md`를 경로로 읽음 | 설정 레코드를 쪼개도 키 · 바인딩 prefix가 같아야 통과 |
| `backend/coverage-scope/*.txt` | `backend/<모듈>/src/main/java/kr/mujoong/...` 전체 경로 한 줄씩(`u5b.txt` 32줄은 `realtime/practice/*`) | `practice` 이동 시 CI `u5b-scope` 실패. 범위 측정은 없는 파일이면 실패(J137) |
| `frontend/coverage-scope/{u5b,u7}.json` | `u5b`: `parent/practice/{startApi,u5bCopy,practiceStart,PracticeStartAction,practiceProgress,PracticeHeader}`, `u7`: `parent/screens/Home04.tsx`, `components/SettingsSheet.tsx` 등 | 화면 · 연습 파일 이동 시 같이 |
| `.github/workflows/ci.yml` | `u5b-scope` 작업이 `-PincludeTags=u5b -PcoverageScope=u5b\|u5b-core\|u7`, `MUJOONG_COVERAGE_SCOPE: u5b`, `vitest run tests/u5b`, `tests/u7`, `hashFiles('backend/coverage-scope/u7.txt')`, `hashFiles('frontend/coverage-scope/u7.json')`, `ci-gate`의 `needs` 작업 이름 목록 | 작업 이름 변경 자유도는 브랜치 보호의 필수 검사 이름이 `ci-gate`뿐인지 확인 필요(미확인) |
| `frontend/vitest.config.ts` · `scripts/verify/a-checks.sh` · `tools/vitest.config.mjs` | `tests/u1/setup.ts`, 76줄 주석 `tests/u1 · tests/u3 …`, `tests/u…` | 시험 폴더 이름 변경 시 |
| `frontend/eslint.config.mjs` | `src/parent/**`, `src/device/**`, `src/shared/**`, `src/app/{parent,device}/**` glob, 예외 파일 `src/parent/push/browserPush.ts` · `src/device/mic/micPermission.ts` | 예외 파일을 옮기면 규칙이 풀림. 경계 시험은 `tests/u1/lint/eslint-boundary.test.ts` |
| `infra/bin/render-env` | 70줄 주석에 `frontend/src/app/parent/login/callback` | 주석만 |
| `contracts/deviations.md` | 52행 `SessionExecutor` 문구 1곳 | 문서 문구만. 오류 코드 · 설정 키 · 시간 값 계약은 이름과 무관 |
| `docs/` | `frontend/src` · `backend/.../src/main` 경로 문서 18개, 화면 파일 이름 문서 3개, `SessionExecutor` 문서 `docs/units/{u3,u4b,u5b,u11,u12,u13}/*` 15개 안팎 | 이력 문서라 함께 고칠지는 별도 결정 |
| Flyway 파일명 | `V7__u5b_...` 등 | 바꾸지 않는다(R-M2) |
