# Functional Design 질문 — 구조 · 이름 리팩토링

> 기준 문서: 승인된 `requirements.md`(FR1 ~ FR8, NFR1 ~ NFR9), 코드 스캔 `aidlc/spaces/default/codekb/mujungi/code-structure.md`, 제안서 `docs/refactor-structure-proposal.md`.
> 이 기록은 유닛 나누기와 도메인 설계를 건너뛰었으므로 현재 코드 구조를 사실상의 도메인 설계로 본다(refactor 범위). 설계 문서는 `construction/functional-design/`에 둔다.
> 요구사항 검토(자문)가 승인 때 남긴 지적 중 **요구사항 문서를 고치지 않고 이 설계 단계에서 정하기로 한 것**이 Q1 ~ Q5이다. 나머지 지적(R-01 ~ R-03, R-07 ~ R-10)은 제안이 코드 사실과 바로 맞아 아래 가정 묶음으로 확인받는다.
> 코드로 확인한 전제: ① `parent/api/apiClient.ts`는 보호자 웹 전용 REST 클라이언트이고 `device`는 자기 `device/api/deviceApi.ts`를 쓴다. ② ESLint 경계상 `shared`는 `parent` · `device`를 import할 수 없다. ③ `D0View`는 "처음 켜기 · 등록 코드 표시", `T19View`는 켜기 · 끄기 · 듣는 중 · 생각 중 등을 보이는 상태 화면이다. ④ 프런트 시험은 `tests/u1, u2, u3, u4a, u5a, u5b, u6, u7, u14`에 있고 `vitest.config.ts`가 `tests/u1/setup.ts`를 문자열로 부른다.

## Q1. 단계별 작업과 PR을 어떤 브랜치 전략으로 할까요? (검토 R-04)

요구사항은 "단계마다 PR 하나, merge commit으로 `main`에 합침"(NFR6)과 "작업은 `claude/refactor-structure`에서만"(NFR7)을 함께 적어 서로 맞지 않습니다. 팀 규칙은 "작업 브랜치는 `main`과 같은 커밋 위에서 새로 딴다, 브랜치는 짧게 산다"입니다. 협력 객체 하나가 PR 하나(FR4.2)면 PR이 10개를 넘을 수 있습니다.

A. 단계(또는 협력 객체)마다 `main` 최신 위에서 새 브랜치 `claude/refactor-<단계>`를 따서 PR을 따로 냅니다. 앞 PR이 합쳐진 뒤 다음 브랜치를 땁니다. 롤백은 PR 단위로 되돌립니다.
B. 통합 브랜치 `claude/refactor-structure` 하나에 단계별 커밋을 쌓고 끝에 PR 하나로 합칩니다. `main`은 끝까지 그대로지만 PR이 매우 커지고 다른 작업과 충돌이 쌓입니다.
C. A와 같되 서로 겹치지 않는 단계(프런트 이름 변경과 실행기 분리)는 병렬 브랜치로 동시에 진행합니다.
X. Other (please specify)

[Answer]: B

## Q2. 유닛 번호가 든 이름의 최종 범위는 어디까지인가요? (검토 R-06, 요구사항 OQ1)

Q4(B "전부 변경")와 요구사항 OQ1("Java 시험 클래스 63개 · 시험 태그 · 표식 파일 후속")이 어긋난다는 지적입니다. CI는 `-PincludeTags=u5b` 같은 **태그**와 범위 파일 이름으로 시험을 고릅니다. Java 클래스 이름(`U4aActivityApiIT` 등)은 CI가 부르지 않고 바꿔도 동작이 같지만, 63개를 한 번에 바꾸면 PR이 크고 이력 추적이 어려워집니다.

A. CI가 부르는 이름까지: 프런트 `tests/u*` 폴더와 `tools/tests/u*`, 커버리지 범위 파일 이름, CI 작업 이름, CI가 부르는 `@Tag("u5b")` 계열 태그를 기능 이름으로 바꿉니다. Java 시험 클래스 이름 63개와 표식 파일 약 134개는 이번에 바꾸지 않고 후속으로 둡니다(이 결정을 요구사항에 정정으로 기록).
B. 전부 바꿉니다: A에 더해 Java 시험 클래스 63개와 표식 파일 약 134개까지. 별도 PR 묶음으로 나눕니다.
C. 이름은 A 범위만 바꾸되 `@Tag("u5b")` 같은 태그는 유지합니다(태그는 시험 선택 계약이라 안정성 우선). CI는 태그 이름을 그대로 부릅니다.
X. Other (please specify)

[Answer]: B

## Q3. 병합 전에 어떤 검증을 돌릴까요? (검토 R-05)

`verify.sh`(전체 끝-끝)는 Build and Test에서 한 번만 돌고, CI `skeleton` 작업은 `main` 병합 뒤에야 돕니다. 지금 `main`에는 브랜치 보호도 없습니다. 병합 전에 "동작이 같다"(NFR1)를 확인할 수단을 PR마다 정해야 합니다.

A. PR마다 바꾼 쪽 검사를 돌립니다(백엔드 `gradlew clean check -Pmujoong.test.db=docker`, 프런트 `eslint` · `vitest --coverage` · `next build`, tools `vitest`). 실행기 분리(FR4)와 CI 이름 변경(FR6.3) PR은 병합 전에 `bash scripts/verify.sh`까지 돌립니다.
B. A와 같되 모든 PR에서 `verify.sh`를 돌립니다(오래 걸리지만 가장 안전).
C. PR마다 바꾼 쪽 검사만 돌리고 `verify.sh`는 Build and Test에서 한 번만 돌립니다(시간은 절약되지만 병합 전 끝-끝 확인이 없습니다).
X. Other (please specify)

[Answer]: B

## Q4. 보호자 쪽 공통 코드(`parent/api`, `parent/components`, `parent/hooks`)는 어디에 둘까요? (검토 R-08, 요구사항 FR3.2)

요구사항 FR3.2는 "공통 클라이언트 · CSRF는 `shared/api`"라고 적었는데, `apiClient`는 보호자 웹 전용입니다. `shared`로 옮기면 기기에서 쓰지 않는 보호자 전용 코드가 `shared`에 들어갑니다. 기능 폴더로 옮기는 범위와 공통으로 남기는 범위를 정해야 합니다. 기능 전용 요청(`account`, `activity`, `push`, `reports`, `results`)은 해당 기능 폴더 `api.ts`로 갑니다.

A. 보호자 전용 공통은 `parent/common/{api,components,hooks}`에 둡니다(`apiClient` · `csrf` · `queries` · `types`와 여러 기능이 쓰는 컴포넌트 · 훅). `shared`에는 보호자와 기기가 함께 쓰는 것(복사 문구 · 오류 변환 · 시간 · 프로토콜 · toast)만 둡니다. 요구사항 FR3.2의 "shared/api"는 "parent/common/api"로 정정합니다.
B. 요구사항 문구대로 공통 클라이언트 · CSRF를 `shared/api`로 옮깁니다.
C. `parent/api`, `parent/components`, `parent/hooks` 위치는 그대로 두고 기능 전용 파일만 `parent/features/<기능>/`으로 옮깁니다.
X. Other (please specify)

[Answer]: A

## Q5. 기기(`device`) 폴더의 최상위 구조는 어떻게 할까요? (검토 R-03 · R-08, 요구사항 FR2.2)

Q5 답은 "기기도 같은 기능 폴더로"였지만 최상위 폴더 이름이 정해지지 않았습니다. `device/`에는 화면(`D0View` = 처음 켜기, `T19View` = 켜기 · 끄기 · 듣는 중 상태)과 소리 · 연결 · 마이크 · 전원 같은 인프라(`audio`, `ws`, `mic`, `power`, `runtime`, `machine`, `api`)가 섞여 있습니다. 인프라 폴더 이름은 이미 책임 기준입니다.

A. 화면만 `device/features/`로 옮깁니다: `D0View` → `features/first-boot/FirstBootView.tsx`, `T19View` → `features/status/StatusView.tsx`. 인프라 폴더(`audio`, `ws`, `mic`, `power`, `runtime`, `machine`, `api`)는 이름이 책임 기준이라 그대로 둡니다.
B. 화면은 A처럼 옮기고 인프라는 `device/platform/` 아래로 묶습니다(`platform/audio`, `platform/ws` 등). import 경로가 많이 바뀝니다.
C. 기능 폴더 안에 인프라까지 나눠 넣습니다(예: 소리 관련은 `features/status/audio`). 폴더 기준이 기능이라 일관되지만 인프라는 여러 기능이 함께 씁니다.
X. Other (please specify)

[Answer]: A

## Assumption Confirmation

아래는 코드 스캔 사실이나 요구사항과 바로 맞는다고 보고 정한 가정입니다. 한 묶음으로 확인해 주세요.

1. **team.md 규칙 목록 (검토 R-01)**: 학습 절차(§13)로 고쳐야 할 `team.md` 규칙은 네 가지입니다. ① "프런트 화면 파일은 화면 설계 번호를 이름 끝에 붙인다"(FR2) ② 시험 이름 · 태그 절의 "프런트 · tools는 `tests/<유닛>/` 폴더"와 유닛 태그(FR6, Q2 답에 따름) ③ "코드 주석은 결정 ID를 인용"(FR7) ④ 커버리지 범위 파일 이름 규칙(FR6.2). 규칙은 해당 변경이 병합될 때 한 번에 올립니다. 설계 문서 `rules.md`가 목록과 올릴 시점을 정합니다.
2. **주석 규칙 선행 조건 (검토 R-02)**: 주석 정리(FR7.2)는 ③이 학습 절차로 기록된 **뒤에만** 실행합니다. 사람이 승인하지 않으면 6단계를 건너뛰고 주석은 옮기기만 합니다(기본값). 규칙이 바뀌기 전에 건드린 파일의 주석은 그대로 옮깁니다.
3. **합격 기준 (검토 R-03)**: 설계 문서에 기계로 확인할 수 있는 항목을 둡니다. 예: `parent/screens`에 번호 접미 파일 0개, 유닛 번호가 든 `tests/` · `coverage-scope/` 경로 0개, 지정 화면(`Live12` 등 코드 스캔 표의 9개)에서 `router.*` · 요청 호출이 화면 컴포넌트 밖 훅에만 있음, `SessionExecutor` 줄 수가 단계별 목표 이하, 기존 시험 무변경 통과. 열린 목록("등")은 확정 목록으로 바꿉니다.
4. **FR3.1 대상 화면**: 코드 스캔 표의 9개 화면(`Live12`, `Home04`, `Done17`, `Device05`, `Child03`, `Consent02`, `StopExpressions01`, `Free08`, `Register05a`)으로 확정합니다. 나머지 화면은 이동과 이름 변경만 합니다.
5. **브랜치 보호 확인 (검토 R-07)**: 확인 담당은 이 기록을 진행하는 사람이고, 확인되기 전까지 PR 병합 전에 `ci-gate` 통과를 사람이 직접 확인합니다(기본값). `team.md` 기록과 다른 점은 이 기록에서 규칙 문서를 고치지 않고 열린 항목으로 남깁니다.
6. **특성화 시험 완료 기준 (검토 R-09)**: 실행기 처리 순서 6구간(메시지 수신 · 참여 확인 · 판정 · 게이트 · 끝내기 · 시계)마다 고정 시험 1개 이상이 있어야 FR4 착수가 가능합니다. 이미 있는 시험(`SessionExecutor`를 직접 부르는 약 30파일)이 구간을 덮으면 새로 더하지 않고 대응표만 만듭니다. 새 협력 객체의 커버리지는 기존 시험으로 모듈 전체 80% 하한을 유지하고, 모자라면 시험을 더합니다(하한은 낮추지 않습니다).
7. **출처 표식 (검토 R-10)**: 설계 문서의 요구와 규칙은 요구사항 ID(FR · NFR)와 출처(제안서 절 · 질문 번호)를 한 줄로 달고, `traceability.json`의 상위 ID는 이 기록의 FR · NFR ID와 묶음 머리 ID 행(N/A)으로 잡습니다.
8. **협력 객체 이름과 순서**: 끝내기 → 시계 → 공백 시계 → 참여 확인 · 판정 흡수 순서로 나누고 이름은 `EndingFlow`, `SessionClocks`, `IdleClock`, `JoinFlow`입니다. 공유 상태는 `SessionContext` 하나가 소유합니다. 게이트 구역은 기존 `GuardrailCoordinator`와의 경계만 설계하고 이번에 옮기는 범위는 설계에서 정합니다. 연습 확장점 구역은 마지막 단계에서 결정합니다.

A. Accept assumptions
B. Convert to follow-up questions

[Answer]: A. Accept assumptions

## Consolidated Summary Confirmation

- 팀 확인: 전·후 구조 문서 `docs/refactor-structure-before-after.md`의 대상 구조와 7절 "팀이 정할 것" 11개 선택을 팀원들이 승인했다. 아래 답과 같은 값이다.
- Q1 브랜치 전략: 통합 브랜치 `claude/refactor-structure` 하나에 단계별 커밋을 쌓고 끝에 PR 하나로 합친다. 합치기 전 `main`을 브랜치로 merge해 따라잡는다.
- Q2 유닛 번호 이름: 전부 변경한다. 프런트 `tests/u*`와 `tools/tests/u*`, 커버리지 범위 파일, CI 작업 이름, 시험 태그, Java 시험 클래스 63개, 유닛 표식 파일 약 134개를 기능 이름으로 바꾸고 이름 충돌은 접미어로 구분한다. Flyway 파일명은 바꾸지 않는다.
- Q3 병합 전 검증: 단계(커밋 묶음)마다 바꾼 쪽 검사와 `bash scripts/verify.sh`를 돌린다. 통합 브랜치라 PR이 하나이므로 "PR마다"는 "단계마다"로 읽는다.
- Q4 보호자 공통: `parent/common/{api,components,hooks,push}`에 둔다. `shared`는 보호자 · 기기 공용만 둔다. 요구사항 FR3.2의 "shared/api"는 "parent/common/api"로 정정한다(설계 문서에 편차로 기록).
- Q5 기기 구조: 화면만 `device/features/`로 옮긴다(`D0View` → `first-boot/FirstBootView`, `T19View` → `status/StatusView`, `DebugLog` → `debug/`). `audio` · `ws` · `mic` · `power` · `runtime` · `machine` · `api`는 그대로 둔다.
- 가정 8개 수용: `team.md` 규칙 4개를 학습 절차로 개정, 주석 규칙이 기록된 뒤에만 주석 정리(거절이면 6단계 생략), 기계로 확인하는 합격 기준, FR3.1 대상 화면 9개 확정, 브랜치 보호 확인 기본값, 특성화 시험 구간별 1개 이상, 출처 표식, 협력 객체 `EndingFlow` · `SessionClocks` · `IdleClock` · `JoinFlow` + `SessionContext`.

Does this all look correct before I generate the functional design artifacts?

- Looks correct
- Request changes

[Answer]: Looks correct
