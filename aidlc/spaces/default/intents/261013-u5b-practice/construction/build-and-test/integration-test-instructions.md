# U5b 독립 경계 및 회귀 시험 지침

## 범위와 근거

code-generation/unit-test-instructions.md의 모든 서로 다른 명령을 한 번씩 실행한다. 최초 모델·Core 개별 명령과 전체 명령의 실행 발견 수는 기록하되 같은 시험을 합산해 고유 시험 수를 부풀리지 않는다. PostgreSQL 원자성·FK/중복·종료·삭제와 Clock 경계는 실제 저장소 및 명시 대역으로 검증한다. 실제 U4b와 Core 시작 준비/API·V3→V4·화면 흐름은 미연결이며 독립 시험이 대신하지 않는다.

## U5b 명령

```text
./backend/gradlew -p backend --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.practice.PracticeModelTest -PincludeTags=u5b
./backend/gradlew -p backend --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u5b -PcoverageScope=u5b
./backend/gradlew -p backend --no-daemon :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=u5b -PcoverageScope=u5b-core
./backend/gradlew -p backend --no-daemon :core-api:test --tests kr.mujoong.core.practice.start.FrozenGoalOrderTest -PincludeTags=u5b
./frontend/node_modules/.bin/vitest run --root frontend tests/u5b/practice-start.test.tsx tests/u5b/practice-progress.test.tsx
MUJOONG_COVERAGE_SCOPE=u5b ./frontend/node_modules/.bin/vitest run --root frontend tests/u5b/practice-start.test.tsx tests/u5b/practice-progress.test.tsx --coverage
```

Realtime/Core/frontend 신규 운영 파일 전체의 줄 커버리지는 각각 80% 이상이어야 한다. 현재 기대 고유 발견 수는 74·12·16건이다. 상세 클래스 결과·실제 분모는 XML/JaCoCo/Vitest 보고서에서 명령 직후 읽어 보존한다.

## 기존 회귀

```text
./backend/gradlew -p backend --no-daemon :contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1
./backend/gradlew -p backend --no-daemon :realtime-activity:test -PincludeTags=u3
./backend/gradlew -p backend --no-daemon :core-api:test -PincludeTags=u4a,u5a
./frontend/node_modules/.bin/vitest run --root frontend tests/u4a tests/u5a
```

기존 LOCAL PostgreSQL 환경 부재에 따른 skip과 Docker 실제 실행을 구분한다. 실패·오류·skip을 명시하며 기존 기준을 변경하지 않는다.

## 데이터와 핵심 경계

매 시험에 합성 계정·세션·목표·상황 UUID와 폐기 DB를 사용한다. V4만 명시 적용하는 대역 DB와 실제 V3→V4 업그레이드를 구분한다. 목표1의 잔존 만료 t=20에서 목표2 기회가 유지되고 자체 t=22에만 닫히는지, 이전 준비 타이머와 늦은 결과가 다음 목표에 반영되지 않는지 확인한다. 첫 무응답/동의/도움/새 상황의 반응·판정·도움은 서로 덮어쓰지 않아야 한다.

