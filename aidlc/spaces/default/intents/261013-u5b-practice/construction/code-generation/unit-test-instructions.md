# U5b 시험 실행 지침

## BT-F01 재시도 명령

이번 재시도는 PracticeHeader.tsx의 제목 문구 조합을 u5bCopy.ts로 옮기는 수정이다. 기존 시험과 아래의 전체 독립 구현 기준을 보존하며, 구현 후 저장소 루트에서 다음 명령을 실행한다.

```text
./frontend/node_modules/.bin/vitest run --root frontend tests/u5b/practice-start.test.tsx tests/u5b/practice-progress.test.tsx
MUJOONG_COVERAGE_SCOPE=u5b ./frontend/node_modules/.bin/vitest run --root frontend tests/u5b/practice-start.test.tsx tests/u5b/practice-progress.test.tsx --coverage
./frontend/node_modules/.bin/vitest run --root frontend tests/u4a tests/u5a
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

기존 발견 수는 U5b 16건, U4a/U5a 116건이다. 재실행의 실제 발견 수와 성공·실패를 기록하고 누락 또는 실패를 숨기지 않는다. frontend/coverage-scope/u5b.json의 운영 파일 5개 전체와 줄 80% 하한을 유지한다. 표시 제목·접근성 동작을 유지하고 lint 오류 3건 해소, typecheck 및 production build 성공을 확인한다. 새 시험은 기존 동작 검증에 필요한 누락이 있을 때만 추가한다.

백엔드 소스·빌드 입력의 변경이 없음을 확인한 경우 기존 U1·U3·Core U4a/U5a 및 U5b 백엔드 증거는 이전 실행임을 표시하여 재사용할 수 있다. 기존 회귀 기준은 유지하며 Build and Test의 필수 명령을 면제하지 않는다. 기존 BT-F01 실패와 Loop-Back Log는 보존한다. 실제 통합·성능·품질 미검증 항목은 프런트 검증 성공으로 완료 처리하지 않는다.

## 범위와 준비

작업 위치는 저장소 루트다. Java 21과 저장소 Gradle wrapper, frontend의 잠금 파일에 맞춘 Node 22/npm 의존성을 사용한다. DB 경계는 기존 MujoongPostgres의 문서화된 Docker 또는 로컬 PostgreSQL 시험 지원을 사용하며 공유/운영 DB에는 적용하지 않는다. 값/시간 대역은 합성 데이터와 기존 MutableClock을 사용한다.

방법은 계획의 Testing Contract에 따른 test-after다. 각 층을 구현한 뒤 해당 시험을 작성하고 실행한다. 시험 실행기/의존성은 첫 실행 전에 준비한다. 기존 JUnit 태그 처리·JaCoCo 80%·Vitest/jsdom/v8 설정을 재사용하며 필요 변경은 U5b 범위 추가에 한정한다. 이 문서의 명령은 U5b로 한정된다. 기존 유닛 회귀는 계획의 별도 회귀 절을 따른다.

## 실행 명령

첫 모델 시험을 Step 3에서 작성한 직후 실행할 정확한 명령:

```text
./backend/gradlew -p backend --no-daemon :realtime-activity:test --tests kr.mujoong.realtime.practice.PracticeModelTest -PincludeTags=u5b
```

Realtime 각 층은 같은 패키지의 명시적 시험 클래스에 --tests 필터를 사용한다. 시험 파일 경로는 backend/realtime-activity/src/test/java/kr/mujoong/realtime/practice/다. 전체 U5b 묶음과 범위 커버리지는 다음 두 모듈을 따로 실행한다. 실제 구현 파일 목록이 생긴 뒤에만 coverageScope 검증을 실행한다.

```text
./backend/gradlew -p backend --no-daemon :realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u5b -PcoverageScope=u5b
./backend/gradlew -p backend --no-daemon :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=u5b -PcoverageScope=u5b-core
```

Core 개별 부품 시험은 backend/core-api/src/test/java/kr/mujoong/core/practice/start/에 두며 첫 명령은 다음과 같다.

```text
./backend/gradlew -p backend --no-daemon :core-api:test --tests kr.mujoong.core.practice.start.FrozenGoalOrderTest -PincludeTags=u5b
```

프런트 U5b의 정확한 파일 필터와 커버리지:

```text
./frontend/node_modules/.bin/vitest run --root frontend tests/u5b/practice-start.test.tsx tests/u5b/practice-progress.test.tsx
MUJOONG_COVERAGE_SCOPE=u5b ./frontend/node_modules/.bin/vitest run --root frontend tests/u5b/practice-start.test.tsx tests/u5b/practice-progress.test.tsx --coverage
```

## 합격 기준

- 각 부품 5~8개 의미 있는 사례, 정상 경로와 최소 두 오류/경계 사례. 복잡한 경합 사례는 필요한 만큼 추가한다.
- backend/coverage-scope/u5b.txt는 변경 Realtime 운영 파일, u5b-core.txt는 변경 Core 운영 파일, frontend/coverage-scope/u5b.json은 변경 프런트 운영 파일을 빠짐없이 포함한다. 각 범위 줄 커버리지 80% 이상이다. 빈 시험 발견/빈 범위를 통과로 보지 않는다.
- 모델·리포지터리 테스트는 실제 반응 보존, 최종 라벨의 원자적 일치, 중복/종료/삭제 경합을 관찰한다. 모킹으로 원자성을 증명했다고 쓰지 않는다.
- 가중 추첨은 고정 시드·1,000회 표본에서 첫 자리 기대 비율 ±5%p, 재시도에서 동일 원본 재사용을 검증한다.
- CG-Q1: 첫 반응 20초 만료 → NOT_PERFORMED/NO_RESPONSE·평가 0회·HELP_OFFER; 실제 도움 질문 재생 완료부터 10초; 동의 시 Cue 1회/도움 후 기회, 거부·무응답 시 Cue 0회/새 상황 준비다.
- 20초/10초는 직전·정각·직후를 가변 Clock으로 검증한다. 만료가 처리된 뒤 중복/늦은 사건은 기회를 다시 열지 않는다. 같은 시각의 사건은 가능한 직렬 순서를 각각 주입하되 U4b 우선순위를 임의 확정하지 않는다.
- 판정의 시작은 유효 반응 수락, 끝은 최종 원자적 커밋이다. 12초 전체 예산에 큐·왕복·검증·재시도 간격·DB를 포함한다. 최종 저장 R을 남기고 시도당 min(5초,C-시작)으로 제한한다. 예산 소진 후 uncertain은 기존 반응을 보존한다. D 이내 커밋과 D 이후 예산 위반을 구분한다.
- R은 시험 데이터에서 명시적으로 주입한다. 실제 저장/예약기 상한 및 부하 시험 없는 운영 R 확정이나 12초 성능 달성 선언을 금지한다. 시간만 가짜로 앞당기고 DB 경합은 검증하지 않은 시험을 실제 저장 성능 증거로 쓰지 않는다.
- U4b 실제 재생/종료/스냅숏·Core 준비/API·새 상황 실패 후 전이·V3→V4 업그레이드는 합의/소스 의존 조건이 충족된 뒤 검증한다. 현재 대역은 U4b 내부 구현을 복제하지 않으며 미실행 통합을 통과로 표시하지 않는다.

## 시험 데이터와 실패 처리

공급자 성공/실패·반응·기기 재생 사건은 시험 소스에만 둔다. 기준/판정 원문·아동 자료·비밀 값을 로그로 내보내지 않는다. DB 롤백·커밋 응답 유실·삭제 중 늦은 결과는 별도 사례로 주입한다. 계정/세션/목표/장면 ID는 매 시험의 합성 값이며 정리 후 재실행 가능해야 한다.

실패하면 원인을 소스/환경/미합의 의존으로 구분한다. 단언 삭제, skip으로 은폐, 커버리지 목록 축소, 하한 인하는 하지 않는다. 실행 명령·시험 발견/성공/실패 수·커버리지와 미실행 이유는 코드 요약과 U5b 보고서에 남긴다. 이 파일은 계획이며 아직 실행 결과가 아니다.
