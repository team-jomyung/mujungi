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

## 승인된 도구 시험 수리의 한정 검증

main U11 병합 뒤 발견한 수신 순서 경합은 사용자가 docs/units/u10/tools-test-review.md의 수신 대기 보완으로 한정 허용했다. tools/tests/u11/fake-provider-wire.test.mjs 마지막 사례에 수신 완료 조건만 추가하며 기존 단언·3000ms 제한·운영 코드는 유지한다. tools 디렉터리, Node22에서 다음 정확한 파일 명령을 실행한다.

```sh
node ./node_modules/vitest/vitest.mjs run tests/u11/fake-provider-wire.test.mjs
```

이 명령은 해당 파일8건만 실행한다. tools 전체 회귀131건과 전체 verify.sh는 별도의 통합 검증이며 위의 U10 태그 명령이나 커버리지 기준을 대신하지 않는다. 실제 공급자·키·자료를 사용하지 않는다. 기존 25단계의 구현·시험은 커밋과 실행 증거로 유지하며 이 수리 때문에 다시 구현하지 않는다.

## 승인된 C9 채움 자료 보완의 한정 검증

docs/units/u10/c9-test-review.md의 승인 범위는 시험 지원3파일이다. 기존600ms 계약판에서 일반 기본 채움은600ms, 기존700ms 악조건은 명시 옵션으로 유지한다. 위의 동일한 wire 파일 명령으로 이제12건을 실행한다. 기존8건을 유지하고 실제 timing.md·realtime application.yml과의 기본값 대조 및 기본 WS의 정확한 길이/해시1건, 잘못된 옵션 값3건을 추가했다. 실제 운영 시간 계약과 C9 단언을 변경하지 않는다.

검증 복사본의 CRLF shebang은 Vitest에서0건 로딩 실패를 낼 수 있다. Git input 정규화한 LF 소스와 정본의 LF 정규화 소스를 대조한다. 도구 전체 회귀와 전체 verify.sh는 여전히 별도의 통합 검증이며, 최신 main이 시간 계약을 변경했다면 그 통합판을 새 근거로 검증한다.600ms 판의 한정 통과를 다른 계약판의 통과로 표시하지 않는다.

## 최종700ms main 통합판의 실행 기준

main6a785360은 시간 계약·RT 설정을700ms로 변경하고 기존 FILLER_MS=700과 맞췄다. 최종 통합876eaaf에서는 scenarios/server의 중복600ms 기본값·선택 옵션을 제거해 main과 같게 두고 기존 wire8건과 추가 기본 음성/계약 대조1건을 유지한다. 위의 정확한 wire 명령은 최종판에서9건이다.600ms 수리판의12건과 혼동하지 않는다. 검증 복사본은 원본 소스 커밋을 fast-forward해 LF를 유지하며 `/home/pdypd/mujungi-u10-final-verify`를 사용한다.

Core V10과 RT V5 이력 기대값을 합친 기존 공통 시험의 충돌 해결은 backend에서 다음 한정 명령으로 검증한다. 전체 모듈 커버리지·전체 backend·U10 태그 검증을 대체하지 않는다.

```sh
./gradlew :core-api:test --tests kr.mujoong.core.db.FlywayConfIT -Pmujoong.test.db=docker --no-parallel --max-workers=1
```

