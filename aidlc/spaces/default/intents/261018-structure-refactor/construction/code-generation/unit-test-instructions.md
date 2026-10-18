# 구조 · 이름 리팩토링 — 단위 시험 지침

> 작업 기록 `261018-structure-refactor` · 시험 전략 Minimal · 방법론 test-after(층마다 구현 뒤 그 층의 시험을 쓰고 돌린다). 이 작업은 동작을 바꾸지 않는다. **합격 기준은 기존 시험이 단언 변경 없이 통과하는 것**이고, 새 시험은 실행기 7구간의 특성화 시험(계획 Step 2)뿐이다. 시험 · 검증은 가짜 공급자만 쓴다(R-F1). 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기를 구분한다(R-M11).

## 시험 틀과 실행 명령

백엔드는 JUnit 5 · AssertJ · Mockito, 통합은 Spring Boot + Testcontainers PostgreSQL이다. 프런트는 Vitest + Testing Library + jsdom, tools는 Vitest다. 도커가 필요한 명령은 `sg docker -c '…'`로 돌린다. 모든 명령은 이 작업의 한 단계가 바꾼 범위로 한정한다(프로젝트 전체 시험을 단계마다 반복하는 것은 아래 "단계 종료 검증"의 `check`뿐이다).

| 용도 | 명령 | 쓰는 계획 단계 |
|---|---|---|
| 실행기 7구간 특성화 시험(새 시험 포함) | `sg docker -c 'cd backend && ./gradlew :realtime-activity:test --tests "kr.mujoong.realtime.session.*" -Pmujoong.test.db=docker'` | Step 2, 12 ~ 17 |
| 구조 · 경계 시험 | `sg docker -c 'cd backend && ./gradlew :realtime-activity:test --tests "kr.mujoong.realtime.arch.*" --tests "*SingleInstanceMarkerTest" --tests "*TimeLiteralScanTest" --tests "*ConfigKeyContractTest" -Pmujoong.test.db=docker'` | Step 12 ~ 19 |
| 백엔드 한 모듈 전체 | `sg docker -c 'cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'` / `… :core-api:check …` | Step 12 ~ 22 |
| 프런트 린트 · 경계 | `cd frontend && npx --no-install eslint .` | Step 5 ~ 11, 20 |
| 프런트 화면 시험(단계별 범위) | `cd frontend && npx --no-install vitest run tests/parent tests/device tests/shared` (Step 20 이후 경로) / 이전에는 `tests/u1 tests/u2 tests/u3 tests/u4a tests/u5a tests/u5b tests/u6 tests/u7 tests/u14` | Step 5 ~ 11, 20 |
| 프런트 범위 시험 | `cd frontend && MUJOONG_COVERAGE_SCOPE=<이름> npx --no-install vitest run <목록 파일의 시험 파일들> --coverage` (이전: `tests/u5b`, `tests/u7`) | Step 11, 20, 21 |
| 프런트 빌드 | `cd frontend && npx --no-install next build` | Step 5 ~ 11 |
| tools | `cd tools && npx --no-install vitest run tests` | Step 20, 21 |
| 인프라 정적(셸 · compose · CI 이름) | `bash infra/bin/infra-validate --only shellcheck,compose-config,secret-env-files` | Step 23 |
| 비밀 스캔 | `bash scripts/verify/a-secrets.sh` | Step 4, 25 |

## 단계 종료 검증 (계획의 "단계 확인")

단계가 끝나면 아래를 모두 돌려 통과해야 다음 단계로 간다(BR5.1).

1. 바꾼 쪽 검사: 백엔드는 `sg docker -c 'cd backend && ./gradlew clean check -Pmujoong.test.db=docker'`, 프런트는 `eslint .` · `vitest run tests --coverage`(줄 80%) · `next build`, tools는 `vitest run tests`.
2. 전체 끝-끝: `sg docker -c 'bash scripts/verify.sh --mode compose'`. 도는 동안 저장소를 고치지 않는다(작업 트리 단계 T). 전제는 JDK 21 · Node 22 · Docker Compose.
3. 시험 수 대조: 단계 전후 백엔드 시험 수(`verify.sh`가 보고하는 합산)와 프런트 시험 파일 · 시험 수가 같다(이름 변경 제외). 줄면 같은 클래스가 덮였거나 시험이 빠진 것이므로 되돌린다.
4. 합격 기준 명령(설계 `functional-spec.md` 5절 AC-1 ~ AC-9)에서 그 단계 몫을 돌려 기대값을 확인한다.

## 시험 범위 (계획 Step → 시험)

| 층 | 시험 | 핵심 단언 |
|---|---|---|
| 프런트 이동 · 이름(Step 5 ~ 8) | 기존 `tests/u1 ~ u14`의 화면 · 기기 시험 74개, `eslint-boundary.test` | import만 바뀌고 단언은 그대로 통과, 영역 경계 규칙 통과 |
| 프런트 흐름 훅(Step 9 ~ 11) | 기존 화면 시험(`live-12` · `home-04` · `done-17` · `device-05` · `consent-02` · `child-03` · `stop-expressions` · `free-08` · `register-05a` 계열) | 화면 동작 무변경, 화면 컴포넌트에 `router.` · `catch` · `await`가 없음(AC-3) |
| 실행기 분리(Step 12 ~ 17) | 기존 `SessionHarness` 기반 시험 약 30파일 + 7구간 특성화 시험 | 처리 순서 7구간 동작 무변경, `SingleInstanceMarkerTest`(SI-2 · SI-9) · `TimeLiteralScanTest` · ArchUnit 통과 |
| 설정 · 패키지 이름(Step 18 ~ 19) | `ConfigKeyContractTest`, `PracticeFlowArchTest`, U5b 범위 시험 | 설정 키 · prefix 불변, 규칙이 빈 상태로 통과하지 않음(`allowEmptyShould`) |
| 시험 · 범위 · CI 이름(Step 20 ~ 23) | 전체 시험, 범위 측정, `infra-validate` | 시험 수 동일, 범위 `practice-legacy` 3개 파일 32개 시험 · `web-push` 6개 파일 89개 시험, 목록 줄 수 = 실행 파일 수, `hashFiles` 단계가 `success` |

**시험 수 목표(Minimal):** 새로 쓰는 시험은 실행기 7구간 중 고정 시험이 없는 구간마다 1개 이상이다. 그 밖에는 새 시험을 만들지 않는다(요구사항마다 검증 가능한 시험 하나는 기존 시험이 맡는다).

## 모의 · 시험 데이터 · 하한

- **모의:** 실행기 특성화 시험은 기존 `SessionHarness`와 가짜 공급자 · 가짜 제브 · 시험용 `MujoongClock`(수동 시계, R-F6)을 쓴다. 이름 변경은 모의를 바꾸지 않는다.
- **시험 데이터:** 기존 시험의 빌더 · 픽스처를 그대로 쓴다. 이름 충돌로 바뀌는 `U2Fixtures` · `U8Fixtures` 같은 공용 준비 클래스는 새 이름으로 옮기되 내용은 같다.
- **하한:** 모듈 전체 줄 80%(JaCoCo · Vitest v8)와 U5b · U7 범위 줄 80%를 낮추지 않는다. 기존 단언 · 계약 대조 · ArchUnit · 범위 파일 의미를 바꾸지 않는다. `@Disabled` · `it.skip` · `-PexcludeTags`로 끄지 않는다(R-F2).
- **증거 구분:** 이 시험이 통과해도 그것은 테스트 대역 · 합성 대화의 성공이다. 실제 API · 실제 기기 성공으로 적지 않는다(R-M11).
