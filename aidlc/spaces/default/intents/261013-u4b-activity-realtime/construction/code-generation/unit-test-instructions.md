# U4b 단위 시험 지시 — 활동 세션 Realtime 쪽

> Code Generation(3.5) · 작업 기록 `261013-u4b-activity-realtime`. 시험 방식은 test-after(층마다 구현한 뒤 시험), 전략은 Standard(구성요소마다 5 ~ 8개 + 경계 통합 시험)다. 모든 명령은 저장소 루트 기준이고 U4b 범위로 좁힌 것이다.

## 1. 시험 프레임워크와 실행 명령

- **프레임워크**
  - JUnit 5 · AssertJ(기존)
  - 통합 시험은 `test-support`의 `MujoongPostgres`(Testcontainers 또는 로컬 PG, 기존 방식)
  - 태그 필터 `-PincludeTags`, 커버리지 범위 `-PcoverageScope`(build-logic 규약)
- **U4b 시험 태그**: 새 시험 클래스는 모두 `@Tag("u4b")`를 단다. DB를 쓰는 시험은 기존처럼 `db` 태그도 붙인다.
- **U4b 범위 시험 + 커버리지**(Realtime 모듈만)
  ```
  cd backend && ./gradlew --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u4b -PcoverageScope=u4b
  ```
- **한 클래스만 돌릴 때**(개발 중)
  ```
  cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u4b --tests 'kr.mujoong.realtime.session.TransitionTableU4bTest'
  ```
- **회귀**(U4b가 기대값을 고친 시험 포함)
  ```
  cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1
  cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u3
  ```
- **core-api 한 파일(FR11.1)**
  ```
  cd backend && ./gradlew --no-daemon :core-api:test --tests 'kr.mujoong.core.db.FlywayConfIT'
  ```
- **흔들리던 시험 반복 확인(FR11.2)**: 아래를 5번 돌려 모두 통과하는지 본다.
  ```
  cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=u1 --tests 'kr.mujoong.realtime.web.RealtimeInternalApiIT' --rerun-tasks
  ```
- **준비**: JDK 21. 통합 시험용 Docker 또는 로컬 PostgreSQL(기존 `-Pmujoong.test.db` 설정을 따름). 그 밖의 수동 준비는 없다.

## 2. 커버리지 목표

- `backend/coverage-scope/u4b.txt`에 적은 Realtime 운영 소스의 줄 커버리지가 80% 이상이어야 한다(`jacocoTestCoverageVerification`이 실패하면 단계 미완료).
- 범위 파일에는 U4b가 새로 만들거나 바꾼 Realtime 운영 파일을 모두 넣는다.
  - dialog: JoinTracker · StopConfirmFlow · SilenceClock
  - session: TransitionTable · SessionExecutor · SessionTimers · EndRequestHandler · SettleCoordinator · ResultSnapshotBuilder · ResultInputSnapshotRepository · PracticeSessionExtension 관련
  - messaging: DeletionRequestedHandler · 세션 삭제 참가자
- 모듈 전체 줄 커버리지 80%(CI의 범위 없는 check)를 유지한다.
- 어떤 임계값도 낮추지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.

## 3. 대역 · 모의 객체 사용

- **실행기 단위 시험**: 기존 `testing/` 대역을 쓴다.
  - `SessionHarness`(실행기 하네스)
  - `ManualTime` · `ManualLauncher`(가변 시계 · 즉시 실행)
  - `FakeProviderPort`(공급자. `session.response.create` · `cancel` 기록을 더한다)
  - `FakeDeviceLink`(기기 송신 · 재생 표시)
  - `FakeSessionGaps` · `FakeAudioRecordings` · `InMemoryTransitions`
- **순수 클래스**(JoinTracker · StopConfirmFlow · SilenceClock · ResultSnapshotBuilder · 전이표): 대역 없이 입력 · 시각만 넣어 결정을 단언한다.
- **연습 확장점**: 시험 소스에 대역 구현(`RecordingPracticeExtension`)을 둔다. 신호 · 출력 능력 호출 · 예외를 재현한다. 운영 소스에는 NoOp만 둔다.
- **통합 시험**: `RealtimeItEnvironment` · `InternalApi` · `test-support`의 `FakeProviderWsServer` · `DeviceSimulatorClient` · `LogCapture`.
- **시간**: 모든 시계는 `MujoongClock`으로 주입한다. 시험 시간 값은 `application-test.yml`(넉넉한 기본값)과 U4b 통합 시험 클래스의 덮어쓰기(짧은 값)로 준다. 시스템 시계와 sleep은 쓰지 않고 상태를 기다린다.

## 4. 시험 데이터 관리

- 시험마다 새 세션 · 보호자 · 기기 id(UUID)를 만들어 서로 겹치지 않게 한다. 공유 가변 고정 데이터는 쓰지 않는다.
- 시작 패키지는 빌더로 만든다. 승인 문구 코드를 넣거나 빼서 "빠진 문구" 경로를 재현한다.
- 통합 시험 DB는 기존 방식대로 시험마다 정리하거나 격리 스키마를 쓴다.
- 해시 골든 값은 시험 소스에 상수로 두고, 정규화 규칙(키 정렬 · 공백 없음 · UTF-8)을 바꾸면 함께 갱신한다.
- 로그 검사 시험은 `LogCapture`로 모은 줄에 전사 원문 문자열이 없는지 본다.

## 5. 층별 시험 목록과 최소 수

| 층 | 시험 클래스 | 최소 수 | 경계값 |
|---|---|---|---|
| L1 | RealtimeSchemaIT(확장) · ResultInputSnapshotRepositoryIT · ConfigKeyContractTest | 5 | — |
| L2 | TransitionTableU4bTest · EndRequestU4bTest · SessionStateEmitterU4bTest | 8 · 8 · 3 | — |
| L3 | JoinTrackerTest · SessionJoinU4bTest · SessionLifecycleU4bIT | 6 · 8 · 3 | 19 · 21초 |
| L4 | StopConfirmFlowTest · SessionStopU4bTest · SilenceClockTest · TechnicalPauseU4bTest · SessionViewU4bIT | 5 · 5 · 8 · 6 · 3 | 19 · 21초, 3:59 · 4:01 · 5:59 · 6:01, 9 · 11초, 39 · 41초 |
| L5 | ResultSnapshotBuilderTest · SessionRecordU4bIT | 6 · 7 | — |
| 확장점 | PracticeExtensionU4bTest | 7 | — |
| L6 | RealtimeDeletionHandlerIT · RealtimeDeletionParticipantOrderTest | 7 · 3 | — |
| L7 | PrivacyLogU4bTest | 5 | — |

각 시험 클래스는 정상 경로와 오류 · 경계 경로를 두 개 이상 포함한다. 항상 통과하는 단언은 두지 않는다.
