# U10 시험 실행 지침

## Test framework setup

JDK21, Gradle wrapper, JUnit5/AssertJ/Mockito 및 Docker Testcontainers PostgreSQL이 이미 구성돼 있다. Standard는 각 변경 구성요소당 5~8개 이상과 핵심 경계 통합 시험이다. 설정은 backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts의 기존 includeTags와 test 작업을 재사용한다. 실제 공급자는 호출하지 않는다.

## Runner readiness

backend 디렉터리에서 구현 전에 실행한다. 기존 U10 관련 연습 생성 시험 한 클래스로 범위를 고정한다.

```sh
./gradlew :core-api:test --tests kr.mujoong.core.practice.ScenarioServiceTest -Pmujoong.test.db=docker --no-parallel --max-workers=2
```

## U10 tests

모든 신규 U10 시험에 @Tag("u10")를 붙인다. 계약 모듈과 Core에 구현한 U10 시험만 아래 필터로 실행한다. 테스트 보고서의 실제 시험수가 0이면 성공 근거가 아니다.

```sh
./gradlew :contract-model:test :core-api:test -PincludeTags=u10 -Pmujoong.test.db=docker --no-parallel --max-workers=2
```

개별 층은 위 명령의 해당 모듈과 --tests kr.mujoong.core.practice.U10ScenarioRepositoryIT 같은 정확한 실제 클래스 필터로 실행한다. 최종 클래스가 다른 패키지라면 code-summary에 실제 실행 필터를 적는다. 전체 회귀·verify.sh는 Build and Test의 별도 명령으로 다루며 이 파일의 반복 실행 명령에 포함하지 않는다.

## Coverage targets

backend/coverage-scope/u10.txt에 실제 변경 운영 파일을 열거하고 전체 시험의 JaCoCo XML에서 해당 파일 줄을 집계해 80% 이상인지 확인한다. 없는 파일·빈 범위는 실패다. 모듈 기존 줄80% 하한도 그대로다. U10 시험 필터 실행은 전체 모듈 커버리지의 근거로 사용하지 않는다.

## Mocking and test data

Luna는 생성/검토 응답이 독립인 가짜 공급자와 스텁 전송만 사용한다. 정상·반려·불명확·누락·스키마·시간 초과·재시도/취소·명령 삽입을 대조한다. MujoongClock과 실행/통신 제어를 사용해 전체15초 및 그래프2초를 검사한다. 시간 숫자를 운영 코드에 새로 넣지 않고 mujoong.timing 설정/계약을 함께 맞춘다.

PG 시험은 Docker 모드로 V9 fixture→V10, 기존 앱 INSERT, 새 JSON 왕복·EXPOSED 이력·권한/lease·삭제 경쟁을 확인한다. 이름·ID·연락처·관심사는 합성 값만 사용하고 실제 비밀/아동 자료를 읽지 않는다. 사용자 수동 스택은 접근하지 않는다.

## Evidence

main 기준 check는 tests3149/failures0/errors0/skipped3이며 skip은 기존 LOCAL PostgreSQL 조건 분기다. 이는 U10 구현 전 기준선이다. 구현 뒤 명령·결과·커버리지·편차는 code-summary와 docs/units/u10/report.md에 새 증거로 남긴다.

