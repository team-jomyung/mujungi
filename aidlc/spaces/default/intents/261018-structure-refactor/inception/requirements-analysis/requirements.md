# 구조 · 이름 리팩토링 — 요구사항 명세

> 작업 기록 `261018-structure-refactor` · Requirements Analysis(2.3) · 2026-10-18 · 범위 `refactor` · 깊이 Minimal · 브랜치 `claude/refactor-structure`(원격 `main` 570a625 위, 롤백용 `snapshot/main-pre-refactor-20261018`)
>
> 이 문서는 새 기능을 만들지 않는다. 이미 있는 코드의 **구조와 이름만** 바꾸는 요구를 정한다. 근거 순서:
> 1. 처음 요청과 제안서 `docs/refactor-structure-proposal.md`
> 2. 코드 스캔 `aidlc/spaces/default/codekb/mujungi/`(원격 `main` 570a625 기준, `business-overview` · `architecture` · `code-structure`)
> 3. 이 단계의 답(`requirements-analysis-questions.md` Q1 ~ Q5, 가정 확인, 요약 확인)
>
> 사람이 명시적으로 고른 답은 제안서의 추천과 다를 때 앞선다(project.md 교정). 실제로 Q4는 추천(A)이 아니라 B(전부 변경), Q5도 B(기기까지)다. 두 답은 제안서의 4단계 · 5단계를 넓힌다.

## 의도 분석

- **목표**: 파일 하나를 이해하거나 고칠 때 알아야 하는 다른 책임을 줄인다. 구체적으로 (1) Realtime 세션 실행기의 책임 과밀, (2) 프런트의 번호 중심 이름과 화면 한 파일에 섞인 요청 · 오류 · 이동, (3) 기능마다 다른 공통 분리 기준, (4) 누적된 결정 ID 주석을 정리한다.
- **하지 않는 것**: 동작 · 오류 코드 · 설정 키 · 시간 값 · API · 이벤트 계약 · DB 스키마를 바꾸지 않는다(NFR1).
- **현황 근거(코드 스캔)**: `SessionExecutor.java` 4,361줄(구역 12개, 가장 큰 구역 "연습 확장점" 1,454줄), 화면 파일 17개 중 번호 접미 14개, 유닛 번호가 든 이름(`u5bCopy.ts`, `frontend/tests/u*`, `backend/coverage-scope/*.txt` 18개, `frontend/coverage-scope/*.json` 8개, CI `u5b-scope`), `practice/`(29파일)와 `practiceflow/`(21파일)의 이름 혼동, 결정 ID 주석(SessionExecutor 약 315 · 프런트 약 547).
- **진행 방식**: 제안서 4절의 0 → 6단계를 PR 단위로 나눈다. 단계마다 기존 시험이 무변경으로 통과해야 한다.

## 기능 요구사항

### FR1 안전망 (제안서 0단계)

- **FR1.1** 실행기를 나누기 전에 처리 순서(메시지 수신 → 참여 확인 → 판정 → 연습 확장점 → 게이트 → 끝내기)를 고정하는 특성화 시험이 있는지 확인하고, 없는 구간은 현재 동작을 고정하는 시험을 더한다. (가정 4)
- **FR1.2** 화면 설계 v1.5j의 화면 번호와 파일의 대응을 `docs/screen-map.md` 표 하나로 만든다. (가정 8)
- **FR1.3** 이름 · 경로를 문자열로 부르는 곳(ArchUnit, `SingleInstanceMarkerTest`의 `REALTIME_SHARE`, `TimeLiteralScanTest` 허용 목록, `ConfigKeyContractTest`, 범위 파일, `ci.yml`, ESLint glob, `vitest.config.ts`, `a-checks.sh`)을 코드 스캔의 표(`code-structure.md` "이름 · 경로를 문자열로 부르는 곳")를 기준으로 단계별 점검표로 만든다. (가정 7)

### FR2 프런트 이름 · 폴더 (1단계, Q5 B)

- **FR2.1** 보호자 화면 17개를 번호 접미 없이 `parent/features/<기능>/<기능>Screen.tsx`로 옮긴다(예: `Live12` → `features/live/LiveScreen.tsx`). 이 단계는 이동과 import 수정만 한다.
- **FR2.2** 기기 쪽도 같은 기능 폴더 구조(`device/features/<기능>/`)로 맞춘다. 번호 이름 `T19View` · `D0View`는 상태 의미 이름으로 바꾼다. 오디오 · 웹소켓 · 기계 같은 이미 책임별로 나뉜 폴더의 위치는 설계 단계에서 `features` 아래로 둘지 정한다.
- **FR2.3** 유닛 번호가 든 소스 이름(`parent/practice/u5bCopy.ts` 등)을 기능 폴더의 `copy.ts`로 정리한다.
- **FR2.4** 이동하는 파일을 부르는 `src/app/**/page.tsx` import, `frontend/coverage-scope/*.json`, ESLint 경계 glob과 예외 파일 경로, `docs/` 경로 참조를 같은 PR에서 고친다.

### FR3 프런트 화면 책임 분리 (2단계)

- **FR3.1** 요청 · 오류 변환 · 폴링 · 화면 이동이 한 파일에 섞인 화면(`Live12`, `Home04`, `Done17`, `Device05`, `Child03`, `Consent02`, `StopExpressions01`, `Register05a` 등 코드 스캔 표 기준)에서 이 처리를 `useXxxScreen` 훅으로 옮기고, 화면 컴포넌트는 그리기와 입력 연결만 한다.
- **FR3.2** 요청 위치 기준을 하나로 정한다: 기능 전용 요청은 기능 폴더 `api.ts`, 공통 클라이언트 · CSRF는 `shared/api`. 연습 요청(`parent/practice/{api,startApi}.ts`)이 기준에 맞게 정리된다.
- **FR3.3** 문구 위치 기준을 하나로 정한다: 기능 전용은 기능 폴더 `copy.ts`, 둘 이상이 쓰면 `shared/copy`.
- **FR3.4** 오류 → 문구 변환은 기존 `shared/errors/codeToCopy` 한 길을 유지한다. 화면별 새 변환을 만들지 않는다.

### FR4 Realtime 실행기 분리 (3단계, Q2 A)

- **FR4.1** 공유 상태를 소유하는 `SessionContext`를 둔다. 분리한 협력 객체는 모두 실행기 스레드에서만 호출한다. (NFR5)
- **FR4.2** 작은 구역부터 순서대로 나눈다: 끝내기(약 504줄) → 시계(약 184줄) → 공백 시계 · 기술 일시정지(약 252줄) → 참여 확인(약 78줄) · 판정 턴(약 260줄)을 기존 `JudgmentCoordinator` 쪽으로 흡수. 한 협력 객체당 PR 하나.
- **FR4.3** `SessionExecutor`는 사건을 받아 순서대로 분배하고 재전달 · 최상위 예외만 맡는다. 처리 순서를 한 곳(상단 요약과 분배 표)에서 읽을 수 있게 한다.
- **FR4.4** 코드 스캔 표에서 담당이 정해지지 않은 머리 619줄(필드 · 생성자)과 끝의 "전이 보조" 약 282줄을 기능 설계 단계에서 `SessionContext` 또는 남는 실행기로 배정한다. (가정 6)
- **FR4.5** 게이트 구역(약 274줄)은 기존 `GuardrailCoordinator` · 출력 게이트 쪽과의 경계를 설계 단계에서 정하고, 정해진 만큼만 이동한다.
- **FR4.6** 연습 확장점 구역(약 1,454줄)을 나눌지는 FR4.2 결과를 평가한 뒤 이 기록의 마지막 단계에서 결정한다. 이번 요구사항의 합격 기준에는 포함하지 않는다.
- **FR4.7** 클래스를 나누면 `SingleInstanceMarkerTest`의 `REALTIME_SHARE` 표(SI-2 · SI-9), `TimeLiteralScanTest`(새 클래스도 검사 대상), 범위 파일, 문서 참조를 같은 PR에서 고친다. (가정 7)

### FR5 패키지 · 설정 이름 (4단계, Q3 A)

- **FR5.1** realtime 레거시 `practice/`를 `practicelegacy`로 바꾸고 `practiceflow`는 그대로 둔다. `PracticeFlowArchTest`의 패키지 문자열, `backend/coverage-scope/u5b.txt`(`realtime/practice/*` 경로), 계약 문서의 경로를 같은 PR에서 고친다.
- **FR5.2** `RealtimeProperties`(570줄) · `CoreProperties`(589줄)의 중첩 설정 레코드를 기능별로 나눈다. 설정 키 이름과 바인딩 prefix는 바꾸지 않는다(`ConfigKeyContractTest`). (가정 10)

### FR6 시험 · CI 이름 (5단계, Q4 B)

- **FR6.1** 프런트 `tests/u1` ~ `u14` 구조를 소스 구조를 따라가는 폴더로 옮기고, 공용 준비 `tests/u1/setup.ts` · `support`(9파일)의 위치와 `vitest.config.ts`의 문자열 참조를 같이 고친다. `tools/tests/u*`도 같은 기준으로 정한다.
- **FR6.2** `backend/coverage-scope/*.txt` · `frontend/coverage-scope/*.json`의 유닛 번호 이름을 기능 이름으로 바꾼다. 범위 측정이 빈 목록 · 없는 파일이면 실패하는 규칙(J137)은 그대로 유지한다.
- **FR6.3** `.github/workflows/ci.yml`의 `u5b-scope` 등 유닛 번호가 든 작업 이름, 범위 인자, `hashFiles` 경로, `ci-gate`의 `needs` 목록을 같은 PR에서 고친다.
- **FR6.4** 원격 `main`에 지금은 브랜치 보호(필수 검사)가 없다(확인함, `team.md`의 "ci-gate 필수"와 다름). 바뀐 작업 이름과 `ci-gate` 구성을 문서에 남겨, 나중에 보호 규칙을 만들 때 이름 불일치가 생기지 않게 한다.

### FR7 주석 (6단계, Q1 A)

- **FR7.1** 주석 규칙을 "지금 이 코드가 무엇을 왜 하는가만 쓰고, 결정 ID는 필요할 때 한 줄 문서 링크로 줄인다"로 바꾼다. `team.md` 코드 스타일 절은 학습 절차(§13)로만 고친다. (가정 14)
- **FR7.2** 이번 작업에서 **건드린 파일부터** 주석을 정리한다. 건드리지 않은 파일은 일괄 정리하지 않는다.

### FR8 문서 · 기록

- **FR8.1** `docs/screen-map.md`(FR1.2)와 단계별 점검표(FR1.3), 최종 구조 설명을 `docs/`에 남겨 팀원이 새 구조를 찾아갈 수 있게 한다.
- **FR8.2** 이력 문서(`docs/units/*`)의 옛 경로 참조를 고칠지는 열린 질문 OQ2에서 정한다.

## 비기능 요구사항

- **NFR1 동작 불변**: 오류 코드 · 설정 키 · 시간 값 · API · 이벤트 계약 · DB 스키마(Flyway 파일명 포함, R-M2)를 바꾸지 않는다. 판정은 `contracts/`와 계약 대조 시험이 변하지 않는 것이다.
- **NFR2 기존 시험 통과**: 기존 시험이 단언을 바꾸지 않고 통과한다. 허용되는 시험 변경은 이름 · 경로 · 패키지 참조 갱신뿐이다. 새 시험은 FR1.1의 특성화 시험에 한한다.
- **NFR3 커버리지**: 모듈 전체 줄 80%와 U5b · U7 범위 80%를 낮추지 않는다. 이름이 바뀐 범위 파일은 같은 대상 파일 집합을 가리킨다(R-F2).
- **NFR4 구조 경계**: `/parent` ↔ `/device` ↔ `shared` import 경계와 `core-api` ↔ `realtime-activity` 분리, ArchUnit 규칙을 유지한다. 규칙이 비어도 통과하는 상태(`allowEmptyShould`)가 되지 않게 한다(R-F4 · R-F5).
- **NFR5 실행기 모델**: 한 세션을 한 스레드로 처리하는 메일박스 모델을 유지한다. 새 스레드를 만들지 않는다.
- **NFR6 PR 규율**: 한 PR은 목적 하나다. 이동 · 이름 변경(`git mv`)과 내용 변경을 섞지 않는다. 모든 PR은 `ci-gate`를 통과하고 merge commit으로 합친다.
- **NFR7 롤백**: `main`에 직접 올리지 않는다. 작업은 `claude/refactor-structure`에서만 하고, 롤백 기준은 원격 `snapshot/main-pre-refactor-20261018`(570a625)과 PR 단위 되돌리기다.
- **NFR8 증거 구분**: 시험 결과는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기를 따로 적는다(R-M11). 이 기록의 시험은 기존 자동 시험과 `verify.sh`뿐이다.
- **NFR9 비밀 · 로그**: 새 파일 · 문서 · 시험에 비밀 값과 전사 원문을 쓰지 않는다(R-F3 · R-F14).

## 제약

- 원격 `main` 570a625에서 시작한 브랜치 `claude/refactor-structure`에서만 작업한다(빨리 감기 가능 상태). 병합 순서와 충돌은 `SessionExecutor`를 건드리는 다른 작업이 없는 시점에 한다.
- 이름이 문자열로 묶인 곳(`code-structure.md` 표)은 이동한 PR에서 같이 고쳐야 한다. 안 고치면 ArchUnit · `TimeLiteralScanTest` · 범위 측정 · CI가 깨진다.
- core-api 패키지 구조는 바꾸지 않는다(가정 10). 마이그레이션 파일명은 바꾸지 않는다(R-M2).
- 규칙 문서(`team.md` 등)는 학습 절차로만 고친다(가정 14).

## Assumptions & Open Questions

### 가정

가정 14개는 질문 파일의 Assumption Confirmation에서 수용했다. 요지: 동작 불변, 이동과 내용 변경 분리, 0 → 6단계 순서(해당 없는 단계는 뺌), 특성화 시험 먼저, 단일 스레드 모델 + `SessionContext`, 미배정 구역은 설계에서 배정, 이름에 묶인 참조는 같은 PR에서 수정, `docs/screen-map.md`, 프런트 기능 폴더 구조, core-api 구조 유지, 가드레일 유지, 기존 시험 무변경 통과가 합격 기준, 브랜치 · merge commit · 롤백 snapshot, 규칙 문서는 학습 절차로만 개정.

### 열린 질문

- **OQ1** 이름에 `U<번호>`가 든 Java 시험 클래스 63개(예 `U4aActivityApiIT`, `SessionStopU4bTest`)와 `@Tag("u5b")` 같은 시험 태그, 유닛 표식 파일 약 134개를 이번에 바꿀지. 태그는 CI의 `-PincludeTags`가 부르므로 바꾸면 CI와 함께 고쳐야 한다. 기본값은 "FR6.3의 CI 작업이 부르는 태그만 설계 단계에서 정하고 나머지는 후속"이다.
- **OQ2** `docs/` 이력 문서의 옛 경로 참조(경로 문서 18개, 화면 파일 이름 문서 3개, `SessionExecutor` 문서 약 15개)를 이번에 고칠지, 이력으로 두고 새 문서에서 대응표로 안내할지.
- **OQ3** 실행기의 구역 간 호출 순서를 고정하는 특성화 시험이 이미 있는지(코드 스캔이 확인하지 못함). FR1.1이 이 질문에 답한다.
- **OQ4** 연습 확장점 구역(약 1,454줄)을 나눌지는 FR4.2 결과 평가 뒤에 정한다.
- **OQ5** 원격 `main`의 브랜치 보호가 없는 것이 의도인지 팀에 확인한다(`team.md` 기록과 다름). 이 기록에서 규칙 문서는 고치지 않는다.
- **OQ6** `Live12`의 시험이 어느 `tests/u*` 폴더에 있는지(코드 스캔 미확인). FR6.1 설계 때 확인한다.

## 범위 밖

- 새 기능, 동작 · 계약 · 설정 키 · 오류 코드 · 시간 값 · DB 스키마 변경
- core-api 패키지 이동(큰 파일 재확인은 가능하나 변경은 후속)
- Flyway 마이그레이션 파일명 변경(R-M2)
- 연습 흐름 · 판정 · 제브 등 로직 변경
- 실제 API · 실제 기기 시험, 배포, 브랜치 보호 규칙 생성
- 건드리지 않은 파일의 주석 일괄 정리

## Sources

- [desc] Initial description: "코드 구조·이름 리팩토링: 제안서 docs/refactor-structure-proposal.md 기준으로 Realtime SessionExecutor를 역할별로 나누고, 프런트 화면 파일을 번호 대신 기능 이름과 기능 폴더로 정리하며, 화면의 요청·오류·이동 처리를 흐름 훅으로 분리하고, 공통 분리 기준과 이름 규칙을 통일한다. 동작·계약·설정 키는 바꾸지 않는다."
- [scope] Workflow-selected scope: refactor
- [Q1] 주석 규칙을 바꾼다 → FR7
- [Q2] 작은 구역부터 나눈다 → FR4
- [Q3] 레거시만 `practicelegacy` → FR5.1
- [Q4] 유닛 번호 이름 전부 변경 → FR6
- [Q5] 보호자와 기기 모두 기능 폴더로 → FR2
- [assumption] 가정 1 ~ 14 수용 → NFR1 ~ NFR9, FR1 ~ FR8
- 코드 스캔: `aidlc/spaces/default/codekb/mujungi/code-structure.md`, `architecture.md`, `business-overview.md`
- 제안서: `docs/refactor-structure-proposal.md`
