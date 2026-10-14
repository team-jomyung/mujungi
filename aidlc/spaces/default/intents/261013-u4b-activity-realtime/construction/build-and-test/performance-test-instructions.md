# U4b 성능 시험 지시 — 활동 세션 Realtime

> Build and Test(3.6) · 시험 전략 Standard. 이 작업 기록에는 성능(지연 · 처리량) 목표 NFR이 없다. 그래서 부하 · 지연 시험은 만들지 않고, 성능에 가까운 시간 정확성 목표만 아래처럼 다룬다.

## 1. 범위

- 지연 · 처리량 · 동시성 목표: 없음. 요구사항 NFR1 ~ NFR9에 성능 수치 목표가 없고, 이 범위(refactor)에는 Performance Validation 단계도 없다.
- 시간 규칙 정확성(NFR2)은 가변 시계로 결정적으로 확인한다. 실시간 측정은 하지 않는다.

## 2. 시간 경계 시험

| 규칙 | 경계값 | 시험 클래스 |
|---|---|---|
| 참여 창 | 19 · 21초 | `SessionJoinU4bTest` · `JoinTrackerU4bTest` |
| 종료 확인 대기 | 19 · 21초 | `StopConfirmFlowTest` |
| 공백 경고 · 종료 | 3:59 · 4:01 · 5:59 · 6:01 | `SilenceClockTest` · `SessionSilenceU4bTest` |
| 끊김 점선 · 종료 | 9 · 11초, 39 · 41초 | `TechnicalPauseU4bTest` |

실행: `cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u4b`(Docker가 없으면 `-Pmujoong.test.db=local`)

## 3. 시험 데이터 · 환경

- 시계는 `MujoongClock` 가변 시계(`ManualTime`)로 주입한다. 시스템 시계 · sleep을 쓰지 않으므로 기계 부하와 무관하게 같은 결과가 나와야 한다.
- 시간 값은 `contracts/timing.md`가 정본이고 설정으로 주입한다(NFR1, `ConfigKeyContractTest` · `TimeLiteralScanTest`).

## 4. 기대값

- 위 경계 시험 전부 통과. 성능 수치 목표는 없음(해당 없음).
