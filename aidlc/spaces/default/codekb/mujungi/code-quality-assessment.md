# Code Quality Assessment

> 기준: 커밋 570a625. 개수는 어노테이션 · `it(` · 정규식 기반 근사다. 파일별 구역 · 줄 수 · 문자열 참조 표는 `code-structure.md`가 정본이고 여기서는 판단과 위험만 적는다.

## 시험 규모와 커버리지

| 대상 | 시험 | 비고 |
|---|---|---|
| contract-model | @Test 155 | 시험 18파일 |
| core-api | 1,530 | 247파일, IT 90 |
| realtime-activity | 1,511 | 244파일, IT 52 |
| test-support | 28 | |
| 백엔드 합계 | 약 3,224 | |
| frontend | 755 | 74파일(`tests/*` 87파일 중 `*.test.*`) |
| tools | 126 | 12파일 |

- 커버리지: 백엔드 모듈 전체 JaCoCo 줄 80%(`contract-model` · `core-api` · `realtime-activity`), 프런트 Vitest v8 줄 80%. `-PcoverageScope=<이름>` / `MUJOONG_COVERAGE_SCOPE=<이름>`이면 범위 파일(`backend/coverage-scope/*.txt` 18개 줄 합계 898, `frontend/coverage-scope/*.json` 8개)만 잰다. 목록이 비었거나 없는 파일이 있으면 실패한다(J137). **CI가 막는 범위는 u5b(백엔드 `u5b` · `u5b-core`, 프런트 `u5b`)와 u7뿐이다.**
- 태그(`@Tag` 사용 수): u1 142, db 134, u5b 33, u11 28, u4b 24, u3 23, u6 21, u13 20, u10 20, u12 18, u8 17, u7 17, u9 16, u2 15, u5a 9, u14 9, u15 6, u4a 5. 실호출 제외 태그 `luna-live` · `luna-reach` · `jev-live` · `gpt-live`는 빌드가 기본 제외를 단언한다.

## 린트 · 서식

- 프런트: ESLint flat config(영역 경계, `jsx-no-literals`, `getUserMedia` · `serviceWorker` 제한). CI가 `eslint .` 실패로 막는다.
- 백엔드: `-Xlint`만(`-Werror` 없음). **Java 포매터 · 줄 길이 검사 없음.** Prettier는 설정만 있고 실행되지 않으며 `.prettierignore`가 `backend/`를 뺀다. `CoreProperties.java:153`의 `PracticeTiming(Duration a,Duration b)`처럼 압축된 서식이 있다. 팀 규칙은 이번에 손대는 파일만 읽기 좋게 정리하고 `printWidth 100`을 강제 기준으로 쓰지 않는 것이다.
- 셸: `infra-validate`의 shellcheck.

## CI/CD

`ci.yml` 작업 14개: `changes`, `backend`, `u5b-scope`, `backend-deps`, `frontend`, `frontend-deps`, `tools`, `secrets`, `infra-validate`, `images`, `skeleton`(main 푸시만), `ci-gate`(필수 검사), `deploy`. 경로 필터가 `^backend/`, `^frontend/`, `contracts/`, `.github/` 등을 본다. `deploy.yml`은 별도다. `docs/` · `aidlc/` · 루트 Markdown만 바뀐 PR은 전 작업이 건너뛰어진 채 `ci-gate`가 통과한다.

## 문서

`README.md`, `AGENTS.md`, `docs/units/<유닛>/{brief,design,report}.md`, `docs/change-history.md`, `docs/live-run.md`. Java 클래스 머리 Javadoc이 풍부하다. 코드 주석이 결정 · 요구 ID를 인용하는 것이 팀 규칙이다.

## Technical Debt (리팩토링 관련)

| ID | 신호 | 근거 |
|---|---|---|
| Q1 | `SessionExecutor.java` 책임 과밀 | 4,361줄(둘째 큰 운영 파일 `PracticeConversation` 862줄의 5배), 메서드 선언 약 276, 결정 ID 약 315회. 구역별 분포는 `code-structure.md` |
| Q2 | 다른 큰 파일 | `GuardrailCoordinator` 759, `FileAudioRecorder` 619, `CoreProperties` 589, `RealtimeProperties` 570. 시험 `GptLiveProbe` 2,072, `PracticeConversationTest` 966 |
| Q3 | 이름 혼동 | `realtime.practice`(레거시 29파일) vs `realtime.practiceflow`(새 흐름 21파일) |
| Q4 | 프런트 번호 중심 이름과 누적 주석 | 화면 14개 번호 접미, `u5bCopy.ts`, 결정 · 요구 ID 약 407회(`src`) |
| Q5 | 화면에 요청 · 오류 · 이동 혼재 | `Live12` · `Home04` 등(`code-structure.md` 표). 오류 → 문구 한 길과 `PARENT_ROUTES`는 이미 있음 |
| Q6 | 분리 기준 불일치 | 연습 요청 `parent/practice/{api,startApi}.ts`, 문구 3곳으로 갈라짐 |
| Q7 | 문서 낡음(이번 의도와 무관) | `team.md`는 다음 Flyway 번호를 core V8 · realtime V5로 적지만 실제는 core V10, realtime V7까지 있다(다음 V11 · V8) |

## 안전망

- ArchUnit 12파일 3,163줄: core `CoreArchTest` 453, `TimeLiteralScanTest` 301, `SingleInstanceMarkerTest` 375, `LunaArchTest`, `U2SingleSourceTest` / realtime `RealtimeArchTest` 525, `SingleInstanceMarkerTest` 462, `TimeLiteralScanTest` 291, `PracticeFlowArchTest` 124, `LunaArchTest`, `U8LiveContentArchTest` / contract-model `ContractModelArchTest` 305.
- 계약 대조: `ContractFilesTest`, `ErrorCodeTest`, `ConfigKeyContractTest`, `TimeLiteralScanTest`, `OpenApiContracts`, `frontend/tests/u1/shared/timing.test.ts`, `SeedDictionaryFileTest`.
- 프런트 경계: ESLint + `tests/u1/lint/eslint-boundary.test.ts`. 이름 바꾸는 이동의 import 경로(`@/…`와 상대 경로 223건 이상)는 컴파일 · `next build` · vitest가 잡는다.
- 끝-끝: `scripts/verify.sh` — A1 backend(`clean check verifyBootJarHasNoTestClasses`) → A2 frontend(`eslint .` · `vitest run tests --coverage` · `next build`) → A3 tools → A4 비밀 스캔 → F 지문 → B 기동 → C 기기 시뮬레이터 한 턴(C1~C12) → P1 교차 스키마 → P2 로그 위생 → P3 비밀 값 → T 작업 트리 동일. CI `skeleton` 작업은 main 푸시 때만 돈다.
- 위 시험의 낮춤 · 끄기 · 건너뛰기 금지(R-F2). 분리하려면 시험 쪽(`SingleInstanceMarkerTest` 표, `TimeLiteralScanTest` 허용 목록, `SessionHarness` 및 `SessionExecutor`를 부르는 약 30개 시험)을 같은 PR에서 맞춘다.

## 구멍과 미확인 사항

- `SessionExecutor` 특성화 시험은 `SessionExecutorTest`, `SessionExecutorU3Test`, `SessionExecutorReview{,2,3}U3Test`, `SessionExecutorFaultTest` 등으로 있으나, 구역 사이 호출 순서(메시지 수신 → 참여 확인 → 판정 → 확장점 → 게이트 → 끝내기)를 고정하는 시험이 따로 있는지는 읽지 않았다.
- `Live12` 시험의 정확한 위치(`tests/u3` 아님, `tests/u1/parent` 쪽일 수 있음)는 확인하지 못했다.
- 브랜치 보호의 필수 검사가 `ci-gate`뿐인지(= `u5b-scope` 등 CI 작업 이름 변경이 자유로운지)는 확인하지 못했다.
- 스캔 한계: 엔드포인트 수, core-api 내부, `contracts` 본문, `docs` 본문, 인프라 스크립트 내부는 깊게 읽지 않았다. 이동 호출 · 결정 ID 개수는 근사값이다.
