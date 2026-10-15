**Collaborator:** aidlc-quality-agent

## Contribution

품질 관점(시험 방식 · 커버리지 도구 · CI 품질 관문 · 시험 패턴 · 인터뷰에서 정할 빈틈)으로 리드 초안을 저장소 사실과 대조했다.
기준: HEAD `72d7034`, `.github/workflows/ci.yml`, `scripts/verify.sh`, `backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts`,
`backend/test-support/build.gradle.kts`, `frontend/vitest.config.ts`, `tools/vitest.config.mjs`, `backend/coverage-scope/`, `frontend/coverage-scope/`.

### 1. 사실 보정 (Testing Posture 절에 반영 요청)

- **유닛 범위 커버리지를 CI가 막는 범위는 U5b · U7뿐이다.** 리드 초안의 "유닛 범위 파일로 유닛이 만들거나 바꾼 코드도 80% 이상. CI가 막는다"는 넓게 적혔다.
  범위 파일은 백엔드 10개(`u1` ~ `u7`, `u5b-core` 포함) · 프런트 8개(`u4b.json` 없음)가 있지만, CI가 `-PcoverageScope` · `MUJOONG_COVERAGE_SCOPE`로 실제 재는 것은
  `u5b-scope` 작업의 `u5b`(realtime) · `u5b-core`(core) · `u7`(core, 파일 있을 때만)과 `frontend` 작업의 `u5b` · `u7`뿐이다.
  나머지 범위 파일은 해당 유닛 작업 때 로컬 · 검토 근거로 쓰였고 지금 CI 관문은 아니다. → "모듈 전체 줄 80%는 CI가 막고, 유닛 범위 80%는 U5b · U7만 CI가 막는다"로 고친다.
- **모듈 하한 적용 범위**: 백엔드 80% 줄 하한은 `contract-model` · `core-api` · `realtime-activity`에 걸리고 `test-support`는 `coverageFloorApplies = false`로 빠진다.
  프런트는 `src/**/*.{ts,tsx}`(`*.d.ts`만 제외) 줄 80%. **`tools/`는 하한이 없다**(`tools/vitest.config.mjs` "시험 지원 코드라 커버리지 하한 대상이 아니다(J107)").
  지표는 줄(LINE)만이다. 분기 · 함수 하한은 없다.
- **범위 측정은 "빈 측정으로 통과하지 않음"이 설계로 들어 있다**: 범위 목록이 없거나 비었거나 없는 파일을 적으면 Gradle · Vitest가 실패한다(J137). 좋은 패턴이므로 이번 작업에서 범위 파일을 새로 쓴다면 그대로 따른다.
- **끝-끝(검증 명령 C안)은 PR 관문이 아니다.** `changes` 작업이 `skeleton`을 `workflow_dispatch`와 `main` 푸시에서만 켠다(J103, 시간이 김).
  즉 PR의 `ci-gate`는 정적 · 단위 · 통합 · 범위 커버리지 · 보안 · 인프라 · 이미지까지만 보고, 기기 시뮬레이터 한 턴 회귀는 **병합 뒤에** 처음 돈다.
  Walking Skeleton 절의 "CI `skeleton` 작업이 `main` 병합 · 수동 실행마다 같은 명령을 돈다"는 맞지만, Testing Posture에 "끝-끝 회귀는 병합 후 확인"이라는 한계를 같이 적는다.
- **경로 필터가 문서 변경을 거른다.** PR에서 `docs/` · `aidlc/` · 루트 Markdown만 바뀌면 모든 작업이 `skipped`이고 `ci-gate`는 통과한다(건너뜀 허용).
  반대로 `contracts/` 또는 `.github/`가 바뀌면 전 작업이 돈다. 이번 작업은 "갱신된 문서에 맞춘 코드 수정"이라 이 경계가 중요하다(아래 3절).
- **시험 태그 체계**: 백엔드 시험은 유닛 태그(`@Tag("u1")` ~ `@Tag("u7")`)와 `@Tag("db")`(107곳)를 쓰고, `-PincludeTags` · `-PexcludeTags`로 고른다. 프런트 · tools 시험은 `tests/<유닛>/` 폴더로 나눈다.
  이번 작업처럼 유닛이 아닌 작업 기록이 새 시험을 어디에 두고 어떤 태그를 달지 정해진 규칙이 없다(4절 질문 Q4).
- **문서 ↔ 코드 대조 시험이 이미 있다**: `contracts/` 파일을 읽는 시험 — `ContractFilesTest` · `ErrorCodeTest`(contract-model), `ConfigKeyContractTest`(두 앱), `TimeLiteralScanTest`(두 앱),
  `OpenApiContracts`(core 웹 시험), `frontend/tests/u1/shared/timing.test.ts`(시간 값 대조), `SeedDictionaryFileTest`. 리드의 R-M5(시간 값 세 곳 동시 수정)는 이 시험들이 이미 깨뜨려 알려 주므로,
  규칙 문구에 "대조 시험이 실패하면 값을 맞추고, 대조 시험을 고치거나 끄지 않는다"를 붙이면 R-F2와 이어진다.
- **검증 명령의 전제**: `scripts/verify.sh`는 모드와 관계없이 처음에 JDK 21을 찾는다(`find_java21`, 없으면 종료 코드 2). compose 모드라도 A1(Gradle)을 호스트에서 돌리기 때문이다.
  그래서 이 PC에서 "Docker compose 모드만 쓰면 JDK 없이 된다"는 선택지는 없다. JDK 21 설치가 전제다. 실행 시간은 기본 내부 기한 5400초(90분)이며,
  `--engine` 모드(110초, A와 이미지 빌드를 건너뛰고 앞선 전체 실행의 지문이 같을 때만 B · C · 사후 단언)가 체크포인트용으로 따로 있다.

### 2. Testing Posture 기록 형식 제안

`team.md` `## Testing Posture`에 승격할 때 Code Generation이 그대로 읽을 수 있게 다음 칸을 나눠 적기를 권한다(값은 인터뷰 결과로 채운다).

- `Methodology`: test-after (현재 실행 사실) — 또는 인터뷰에서 고른 값
- `Ordering`: 층 하나 구현 → 그 층 시험 작성 · 실행 → 다음 층
- 커버리지: 모듈 줄 80%(백엔드 3모듈 · 프런트 `src`), `tools`는 하한 없음, 유닛 범위 80%는 범위 파일이 있는 작업에 적용(CI 강제는 U5b · U7)
- 관문: PR = `ci-gate`(경로 필터 적용), 끝-끝 = `main` 병합 뒤 `skeleton` · 로컬 `bash scripts/verify.sh`
- 공급자: 가짜 공급자만(R-F1)
- 하한 · 단언 · 대조 시험은 낮추거나 끄지 않는다(R-F2)

### 3. 이번 작업(문서 동기화 코드 수정)에 특유한 품질 위험

1. **"문서만 바뀐 PR이 초록"의 착시**: 설계 문서(`aidlc/spaces/default/knowledge/documents/` · `docs/`) 갱신은 어떤 CI 작업도 켜지 않는다. 코드가 문서를 따라갔는지는
   `contracts/`를 거쳐야 시험이 잡는다. 문서 변경이 계약(API · 이벤트 · 오류 코드 · 시간 값)에 닿으면 `contracts/`를 먼저 고치고 대조 시험을 빨갛게 만든 뒤 코드를 맞추는 순서가
   test-after 안에서도 가장 싸게 회귀를 막는다. 이것을 팀 방식으로 둘지 인터뷰에서 묻는다(Q2).
2. **바뀐 코드의 커버리지를 재는 수단이 없다**: 이번 작업은 유닛이 아니라서 범위 파일이 없고, diff 커버리지 도구도 없다. 모듈 전체 80%는 기존 코드에 묻혀 새 코드의 낮은 커버리지를 가린다(Q3).
3. **끝-끝 회귀가 병합 뒤에야 돈다**: 음성 · 활동 흐름을 건드리면 PR 단계에서는 C안 회귀가 없다. 로컬 `verify.sh` 전체 실행을 병합 전 조건으로 둘지 정해야 한다(Q1).
4. **사람이 정본과 다른 쪽을 고르면 앞 유닛 시험이 바뀐다**: `project.md` Corrections의 규칙대로 별도 FR로 묶어야 하고, 이때 "시험을 바꾼 것"과 "시험을 약하게 한 것"을 구분하는 근거(바뀐 문서 문장 인용)를 시험 주석 · 계획에 남기게 한다.

### 4. 인터뷰에서 사람이 정해야 할 품질 질문 (리드 질문 후보에 더하거나 합칠 것)

- **Q1. Construction 검증 명령**: (A) `bash scripts/verify.sh`(전체, JDK 21 설치 필요, 최대 90분) / (B) A단계 상당만 — `cd backend && ./gradlew clean check -Pmujoong.test.db=docker` + 프런트 `eslint` · `vitest --coverage` · `next build` + tools `vitest`(끝-끝 없음) /
  (C) A를 매 Bolt, 전체 `verify.sh`는 Construction 끝(Build and Test)에서 한 번. 품질 관점 추천은 (C): 문서 동기화는 여러 곳을 조금씩 바꾸므로 빠른 피드백과 끝-끝 확인을 둘 다 남긴다.
- **Q2. 시험 방식**: (A) test-after 유지 / (B) 혼합 — 문서 변경이 계약에 닿으면 `contracts/` · 대조 시험을 먼저 바꿔 실패를 확인한 뒤 코드(그 외는 test-after) / (C) TDD 전면.
  추천 (B). 바뀐 문서 문장이 곧 기대값이라 먼저 고정하는 비용이 낮다. 고르면 `Methodology`에 `custom`과 이 순서를 그대로 적어야 Code Generation이 읽는다.
- **Q3. 바뀐 코드 커버리지**: (A) 모듈 80%만 / (B) 이번 작업용 범위 파일(`backend/coverage-scope/doc-sync.txt` · `frontend/coverage-scope/doc-sync.json` 같은 이름)을 만들어 로컬 · 검토 근거로 80% 확인 /
  (C) (B)에 더해 CI `u5b-scope` 작업처럼 CI 단계를 추가. 추천 (B). (C)는 CI 파일 변경이라 범위가 커진다.
- **Q4. 새 시험의 자리와 태그**: 유닛이 아닌 작업의 시험을 (A) 고친 코드가 속한 유닛 폴더 · 태그에 둔다 / (B) 새 태그 · 폴더(예: `docsync`)를 만든다. 추천 (A): 기존 유닛 회귀 묶음과 범위 측정이 그대로 이어진다.
- **Q5. 병합 전 끝-끝 확인**: 음성 · 활동 경로를 바꾼 PR은 병합 전에 로컬 `verify.sh` 통과(report.txt `RESULT` 줄)를 PR 본문에 붙인다 — 팀 규칙으로 둘지.
- **Q6. 분기 커버리지**: 지금은 줄만 본다. 이번에 분기 하한을 더할지(권하지 않음 — 기존 모듈이 맞출 수 있는지 측정 전이라 하한 낮추기 압박만 생긴다). 질문 대신 "줄 80% 유지, 분기 하한 없음"을 가정 묶음으로 확인해도 된다.

### 5. discovered-rules 후보에 대한 품질 쪽 보강

- R-F2 문구에 "대조 시험(`ConfigKeyContractTest` · `TimeLiteralScanTest` · `ContractFilesTest` · `timing.test.ts` 등)과 ArchUnit 시험을 고치거나 건너뛰게 해 통과시키지 않는다"를 붙인다.
  `@Disabled` · `it.skip` · `-PexcludeTags`로 실패를 숨기는 경로도 같은 금지에 넣는다.
- R-M1(`ci-gate` 통과 후 병합)은 경로 필터 때문에 문서만 바뀐 PR에서는 아무것도 보지 않는다는 한계를 근거 칸에 적는다. 규칙 자체는 유지한다.
- 새 후보(확인 필요) **R-M6**: ALWAYS 설계 문서 변경이 API · 이벤트 · 오류 코드 · 시간 값 계약에 닿으면 같은 작업에서 `contracts/`를 함께 고쳐, CI 전 작업과 계약 대조 시험이 돌게 한다.
  근거: `ci.yml` `changes` 작업(`contracts/` 변경 → 전 작업 실행), 1절의 대조 시험 목록.

## Positions

- AGREE: Methodology test-after · Ordering(층별 구현 후 그 층 시험)을 현재 실행 사실로 적은 것 — U1 · U4b · U5b · U6 계획과 맞고, 팀이 고른 적 없다는 표시도 정확하다.
- AGREE: 백엔드 JaCoCo 줄 80%(`test-support` 제외) · 프런트 Vitest v8 줄 80%가 CI에서 막힌다는 사실 — build-logic과 `vitest.config.ts`에서 확인했다.
- OBJECT: "유닛 범위 파일로 유닛이 만들거나 바꾼 코드도 80% 이상. CI가 막는다" — CI가 범위를 재는 것은 U5b · U7(백엔드 `u5b` · `u5b-core` · `u7`, 프런트 `u5b` · `u7`)뿐이므로 범위를 한정해 고쳐야 한다.
- OBJECT: 리드 4절 5번의 "또는 Docker compose 모드만 쓸지" 선택지 — `verify.sh`는 모드와 관계없이 JDK 21을 요구하므로(종료 코드 2) JDK 없이 쓰는 선택지는 없다. 질문을 "JDK 21 설치 + 어느 범위로 돌릴지"로 바꾼다.
- OBJECT: Testing Posture에 끝-끝 회귀 시점이 빠졌다 — `skeleton`은 PR에서 돌지 않고 `main` 푸시 · 수동에서만 돌므로, PR 관문의 범위와 병합 후 회귀를 구분해 적어야 한다.
- AGREE: R-F1(실 공급자 금지) · R-F2(하한 · 단언 낮추기 금지) · R-M5(시간 값 세 곳) 후보 — 단, R-F2와 R-M5에 5절의 대조 시험 보강 문구를 붙이기를 권한다.
- AGREE: 시험 종류 목록(단위 · 통합 Testcontainers · 계약 · ArchUnit · ESLint 경계 · 끝-끝 · 인프라) — 저장소 구성과 일치한다. `tools/`에 커버리지 하한이 없다는 점만 더한다.
