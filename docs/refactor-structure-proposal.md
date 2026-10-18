# 리팩토링 구조 제안서

> 읽기 전용 조사 기준(2026-10-18, `main` d835887). 코드는 바꾸지 않았다. 아래 수치는 `wc`와 `grep`으로 센 값이라 정확한 지표가 아니라 규모 감각용이다. 이 문서는 AI-DLC 작업 기록(`refactor` 범위)의 입력이다.

## 1. 판단 기준

역할에 따라 크기가 다른 것은 자연스럽다. 기준은 "파일 하나를 이해하거나 고칠 때 **다른 책임을 얼마나 알아야 하는가**"이다.

| 구분 | 현재 판단 |
|---|---|
| 파일·폴더 구조 | 기본적인 구분은 타당함. 깊이가 핵심 문제는 아님 |
| 백엔드 | 일부 실행기에 여러 책임이 집중됨. 특히 Realtime에서 역할과 처리 순서를 더 명확하게 정리할 필요가 있음 |
| 프런트 이름·주석 | 화면 번호·문서 식별자 중심의 이름과 누적된 과거 설명 때문에 현재 기능을 바로 파악하기 어려움 |
| 프런트 코드 | 이름뿐 아니라 일부 화면에 요청·오류·화면 이동 처리가 함께 있어 흐름 파악이 어려움 |
| 공통 분리 | 이미 분리는 되어 있음. 다만 기능마다 분리 기준과 처리 위치가 달라 예측하기 어려운 부분이 있음 |

## 2. 현황 진단 (근거)

| 영역 | 판단 | 근거 |
|---|---|---|
| 폴더 깊이·기본 구분 | 타당함, 손대지 않음 | 백엔드는 기능 패키지(`core.*`, `realtime.*`), 프런트는 `parent`·`device`·`shared`. 경계는 ArchUnit·ESLint가 강제 |
| Realtime 실행기 | **책임 과밀 (핵심 문제)** | `SessionExecutor.java` 4,361줄, 메서드로 보이는 줄 약 190개, 구역 12개. 두 번째로 큰 파일(`PracticeConversation` 862줄)의 5배 |
| 처리 순서 가시성 | 불명확 | 구역 사이 순서(메시지 수신 → 참여 확인 → 판정 → 확장점 → 게이트 → 끝내기)가 한 파일의 위아래 위치로만 드러남 |
| 프런트 이름 | **번호·식별자 중심** | 화면 파일 17개가 `Login01`·`Live12`·`Report30`·`Register05a`처럼 번호로 끝남. 그 밖에 `T19View`, `TextArea200`, `u5bCopy.ts`. 테스트 폴더(`tests/u1`~`u14`)와 커버리지 범위 파일(`u5b.txt` 등)도 유닛 번호 |
| 누적된 주석 | 현재 기능 파악을 방해 | `SessionExecutor` 안의 결정·요구 ID(J·U·BR·FR·R-) 약 315개, 프런트 약 547개. 팀 코드 스타일이 "주석은 결정 ID를 인용"하라고 정해서 생긴 결과 |
| 프런트 화면 책임 | 한 파일에 섞임 | `Live12`·`Home04`는 화면 이동 호출이 11~12곳, `Live12`는 요청·오류·상태·이동이 한 파일(274줄) |
| 공통 분리 기준 | 기능마다 다름 | 부모 API는 `parent/api/*.ts`인데 연습만 `parent/practice/api.ts`·`startApi.ts`로 따로 있음. 문구도 `shared/copy/*`, `parent/practice/copy.ts`, `u5bCopy.ts`로 흩어짐 |
| 같은 이름 혼동 | 있음 | realtime에 `practice/`(29파일)와 `practiceflow/`(21파일)가 따로 있고 레거시와 새 흐름의 경계가 이름만으로는 안 보임 |

## 3. 제안

### 3-1. Realtime: `SessionExecutor`를 역할별로 나눈다 (가장 큰 효과)

| 현재 구역(줄 수) | 분리 후 이름 | 책임 |
|---|---|---|
| 참여 확인 (~80) | `JoinFlow` | WAITING_CHILD 대답 분류, J1 열기 |
| U11 판정 턴 (~260) | 기존 `JudgmentCoordinator`로 흡수 | 판정 열기·닫기 |
| 연습 확장점 (**~1,450**) | `PracticeExtensionHost` | 확장점 호출·출력 능력 실행·생성형 슬롯 |
| 공백 시계·기술 일시중지 (~250) | `IdleClock` | 침묵·공백 경고 |
| 게이트 (~270) | 기존 `GuardrailCoordinator`와 `OutputGate` 쪽으로 | 출력 검사·보류 |
| 기기 표식·끝내기 (~500) | `EndingFlow` | 중단·마무리·종료 확인 |
| 시계 (~180) | `SessionClocks` | 시한 등록·만료 |
| 재전달·최상위 예외 (~60) | `SessionExecutor`에 남김 | 재시도·마지막 방어 |

- `SessionExecutor`는 "사건을 받아 순서대로 분배한다"만 한다. 처리 순서를 한 곳(상단 요약 + 분배 표)에서 읽을 수 있게 한다.
- 불변식: 단일 스레드 메일박스 모델을 유지한다. 분리한 협력 객체는 실행기 스레드에서만 호출되고 공유 상태는 `SessionContext` 하나가 소유한다. 새 스레드를 만들지 않는다.
- `PracticeConversation`(862)·`GuardrailCoordinator`(759)는 우선순위를 낮게 두고 실행기 분리 뒤 같은 기준으로 다시 본다.

### 3-2. 프런트: 이름·구조·책임

**이름: 화면 번호 → 기능 이름, 번호는 문서로**

| 현재 | 제안 |
|---|---|
| `parent/screens/Live12.tsx` | `parent/features/live/LiveScreen.tsx` |
| `Login01`·`Consent02`·`Child03`·`Home04` | `login/LoginScreen`·`consent/ConsentScreen`·`child/ChildScreen`·`home/HomeScreen` |
| `Report30`·`ReportRange28`·`Records26`·`RecordDetail27` | `reports/…`·`records/…` |
| `device/components/T19View.tsx`·`D0View.tsx` | `device/features/…/*View.tsx` (이름은 상태 의미로) |
| `u5bCopy.ts` | 해당 기능 폴더의 `copy.ts` |

- 화면 설계 번호(v1.5j)와 파일의 대응은 `docs/screen-map.md` 표 하나로 옮긴다. 파일 이름에는 번호를 쓰지 않는다.
- 폴더는 기능 단위(`parent/features/<기능>/`)로 모은다. 그 안에 화면·훅·요청·문구·시험을 함께 둔다.

**화면 책임 분리**

| 층 | 두는 곳 | 하는 일 |
|---|---|---|
| 화면 | `XxxScreen.tsx` | 그리기와 사용자 입력 연결만 |
| 흐름 훅 | `useXxxScreen.ts` | 요청, 폴링, 오류→문구 변환, 화면 이동 호출 |
| 요청 | 기능별 `api.ts` | 서버 호출만. 공통 클라이언트·CSRF는 `shared/api`로 |

- 오류 처리는 기존 `codeToCopy` 한 길로 모은다.
- 문구 위치 기준: 기능 전용은 기능 폴더 `copy.ts`, 둘 이상이 쓰면 `shared/copy`.

### 3-3. 이름·주석 규칙

- 파일명에 유닛 번호·화면 번호·결정 ID를 넣지 않는다.
- 주석은 "지금 이 코드가 무엇을 왜 하는가"만 쓴다. 결정 ID가 필요하면 한 줄 링크(`docs/…#id`)로 줄이고, 과거 변경 설명은 커밋과 설계 문서로 보낸다.
- 현재 `team.md`의 "코드 주석은 결정 ID를 인용" 규칙과 정면으로 부딪힌다. **규칙 변경이 먼저 필요하다**(6절).

### 3-4. 백엔드 나머지

- `realtime.practice`(레거시)와 `practiceflow`(새 흐름)를 이름으로 구분한다. 예: `practice` → `practicelegacy`(또는 `scenario`), `practiceflow` → `practice`. 방향은 6절에서 정한다.
- `CoreProperties`(589줄)·`RealtimeProperties`(570줄)는 기능별 설정 레코드로 나눈다. 설정 키 이름은 바꾸지 않는다(`ConfigKeyContractTest`).
- core-api는 구조를 바꾸지 않는다. 파일 단위로 큰 것(`StartActivityService` 510줄, `DeviceJdbcRepository` 546줄)만 같은 기준으로 재확인한다.

### 3-5. 테스트·커버리지 파일 이름

- `frontend/tests/u1`~`u14`를 소스 구조를 따라가는 폴더로 옮긴다.
- `backend/coverage-scope/u5b.txt`와 `frontend/coverage-scope/*.json`은 CI(`u5b-scope` 작업)가 이름으로 부른다. 이름 변경은 CI 설정과 같은 PR에서 한다.

## 4. 진행 순서 (PR 단위, 동작 변경 없음)

| 단계 | 내용 | 위험 | 확인 |
|---|---|---|---|
| 0 | 안전망: 실행기 특성화 시험 보강(현재 동작 고정), 화면 번호 대응표 `docs/screen-map.md` | 낮음 | `verify.sh` |
| 1 | 프런트 파일 이동·이름 변경만(`git mv`, 내용 변경 없음) | 낮음, 충돌 많음 | `eslint`·`vitest`·`next build` |
| 2 | 프런트 화면별 `useXxxScreen` 분리, API·문구 위치 정리 | 중간 | 화면 시험 + 수동 확인 |
| 3 | Realtime 실행기 분리를 협력 객체 하나씩 PR로(끝내기 → 시계 → 공백 → 연습 확장점) | **높음** | 기존 시험 무변경 통과 + `u5b` 범위 80% 유지 |
| 4 | 설정 레코드·패키지 이름 정리(`practice` 계열) | 중간 | ArchUnit·계약 대조 시험 |
| 5 | 테스트 폴더·커버리지 범위 파일 이름 변경 + CI 설정 | 중간 | `ci-gate` |
| 6 | 주석 정리(규칙 변경 뒤, 건드린 파일부터) | 낮음 | 리뷰 |

각 PR은 하나의 목적만 가진다. 이동과 내용 변경을 섞지 않는다.

## 5. 가드레일

- 커버리지 하한, 기존 단언, ArchUnit·계약 대조 시험은 낮추거나 끄지 않는다(R-F2). 클래스 이름이 바뀌면 `TimeLiteralScanTest` 허용 목록, `coverage-scope` 파일, `contracts/` 참조 경로를 같은 PR에서 고친다.
- `/parent` ↔ `/device` ↔ `shared` import 경계와 `core-api` ↔ `realtime-activity` 분리(R-F4·R-F5)는 유지한다.
- 이동 PR이 길게 열려 있으면 다른 유닛과 `SessionExecutor`에서 충돌한다. 3단계는 해당 파일을 건드리는 진행 중 작업이 없는 시점에 한다.
- 오류 코드, 설정 키, 시간 값, API 계약은 이번 리팩토링에서 바꾸지 않는다.

## 6. 결정이 필요한 것

1. **주석 규칙 변경**: "주석은 결정 ID를 인용" → "현재 동작 설명 + 필요 시 문서 링크". 바꾸면 `team.md` 코드 스타일 절을 학습 절차로 고쳐야 한다.
2. **실행기 분리 범위**: 연습 확장점(~1,450줄)까지 이번에 할지, 먼저 끝내기·시계·공백만 하고 평가할지.
3. **`practice` 이름 방향**: 레거시를 이름으로 구분할지, 레거시 제거 일정과 묶을지.
4. **유닛 번호 기반 이름**(테스트 폴더, 커버리지 범위 파일, `u5b-scope` 작업)을 지금 바꿀지.
5. **작업 기록**: 별도 AI-DLC 작업 기록(`refactor` 범위)으로 연다.

## 7. 한계

- 구조·크기·이름·호출 수를 센 것이다. 개별 메서드의 응집도는 읽어서 판단하지 않았다. 3-1의 분리 경계는 구역 제목 기준이며, 구역 사이 공유 필드 때문에 일부 조정이 필요할 수 있다.
- 3단계(실행기 분리)가 비용도 효과도 가장 크다. 시간이 한정되면 0단계 → 1단계 → 3단계 일부(끝내기·시계)만으로도 "파일 하나가 알아야 하는 것"이 눈에 띄게 줄어든다.
