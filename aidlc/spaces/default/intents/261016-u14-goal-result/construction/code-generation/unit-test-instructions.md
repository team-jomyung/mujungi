# U14 Unit Test Instructions

## 실행 환경과 runner

Ubuntu WSL, JDK21, Docker/Testcontainers PostgreSQL, Gradle wrapper, Node22/npm lock을 사용한다. 테스트 프레임워크/버전은 기존 Gradle build-logic과 frontend Vitest 설정을 재사용한다. 다른 유닛의 설정·단언·하한·ArchUnit은 바꾸지 않는다. 신규 Java 시험에는 @Tag("u14"), DB 경계 시험에는 @Tag("db")를 함께 둔다. frontend 시험은 tests/u14/만 사용한다.

아래 명령은 각 작업 디렉터리에서 한 줄씩 실행한다. PowerShell에서 dotted Gradle 속성은 문자열 전체를 인수로 따옴표 처리한다. WSL 작업 디렉터리는 저장소의 backend 또는 frontend다. U14 전용 init script를 쓰면 Gradle 인수로 --init-script ../../u14-wsl-build.init.gradle을 추가할 수 있으나 시험 필터/하한은 같다. 실제 기준선/전체 모듈 회귀 명령은 Build and Test의 전체 검증에서 별도로 실행하며 이 파일의 U14 명령을 전체 명령으로 바꾸지 않는다.

## 첫 시험 전에 runner 확인

U14 파일이 없는 시점에도 실행 가능한 기존 영향 범위 한 클래스만 확인한다. 작업 디렉터리 backend:

```bash
./gradlew :core-api:test --tests 'kr.mujoong.core.result.SceneChipMapperTest' -Pmujoong.test.db=docker
```

이 명령은 U14 변경 전 runner readiness 증거이며 U14 신규 시험 통과 증거는 아니다. 새 파일을 만든 뒤 각 층의 단일 클래스는 다음 형식으로 해당 이름을 정확 지정한다:

```bash
./gradlew :core-api:test --tests 'kr.mujoong.core.practicegoal.SavedGoalReaderTest' -PincludeTags=u14 -Pmujoong.test.db=docker
```

패키지가 책임에 따라 result/report/기존practice에 배치되면 실제 완전한 클래스명을 그대로 지정하며 파일이 없거나 0개 실행이면 완료로 처리하지 않는다.

## U14 백엔드 묶음과 커버리지

각 층 구현 직후 그 층의 *Test/*IT를 작성하고 실행한 뒤에만 다음 층을 구현한다. 동일 파일 재실행 캐시 때문에 미실행으로 끝나면 --rerun-tasks를 사용하고 XML의 실제 사례를 확인한다. 전체 U14 묶음:

```bash
./gradlew :core-api:test -PincludeTags=u14 -Pmujoong.test.db=docker
```

모든 새/수정 운영 Java 경로를 backend/coverage-scope/u14.txt에 넣은 뒤 같은 u14 필터 실행의 범위 보고·하한을 확인한다:

```bash
./gradlew :core-api:test :core-api:jacocoTestReport :core-api:jacocoTestCoverageVerification -PincludeTags=u14 -PcoverageScope=u14 -Pmujoong.test.db=docker
```

목표는 U14 범위 줄80% 이상이며 빈 목록/없는 경로는 실패해야 한다. 보고서는 기본 core-api/build/reports/jacoco/test/jacocoTestReport.xml 및 html/index.html, JUnit XML은 core-api/build/test-results/test/다. init script 사용 시 /tmp/mujungi-u14-verification-build/core-api/build/ 아래 동일 하위 경로다. 모듈 전체80%와 기존 전체 시험은 별도 전체검증으로 확인하며 필터 실행 보고를 전체 모듈 보고로 주장하지 않는다.

## U14 프런트 adapter

작업 디렉터리 frontend. 기존 node_modules가 없으면 lockfile 그대로 npm ci를 먼저 수행한다. 화면 수정은 없다.

```bash
npx vitest run tests/u14/results.test.ts
```

신규 동등 책임 파일을 나눴으면 tests/u14만 묶는다:

```bash
npx vitest run tests/u14/
```

이 필터 보고를 frontend 전체 커버리지로 주장하지 않는다. 전체 frontend 줄80%·eslint·typecheck/build는 전체검증에서 기존 설정 그대로 확인한다.

## 시험 파일·경계·자료

- U14MigrationIT: V8→V9·구 앱 쓰기·새 FK/유일성/제약 확대. 실제 PostgreSQL로 검증한다.
- SavedGoalReaderTest/PracticeGoalRepositoryIT/CandidateVersionSelectionIT: C-DS10·타아동/철회/삭제·legacy·추천핸들 고정과 여섯 버전/선택 경합. 각 testable 컴포넌트5~8개 기본 이상.
- GoalCandidateValidatorTest/GoalCandidateGatewayTest/LunaGoalCandidateProviderTest: 후보 최대3·비식별화·이름12/근거40·4요소·seq/quote·seed목록·NONE·금지검사·schema·AI목적별 한호출.
- CandidateSeedLinkTest/CandidateSeedReplayIT: DONE 뒤 새버전게시·NONE유지 실패·구조없음·멱등·최신버전/선택/삭제/철회 경합과 durable replay.
- PracticeRecordV2Test/PracticeRecordV2IT: flowVersion 분기·가변 실제 장면·unknown fact 무시·중복 사실 dedupe·변형/종료·snapshotVersion불변.
- RejudgmentGatewayTest/U14SceneProjectionTest/U14MixedReportIT/U14ResultPipelineIT: 장면집합·value/timing·AFTER_REASK·live차이 내부 저장·27칩만접기·목표표현안전·레거시/새혼합.
- U14ResultRetryIT/U14TimingContractTest: 총4회·5/15/30초·내부재시도0·임대/수동재요청·양앱/계약/frontend 기본값 동등.
- U14ResultApiIT/U14GoalApiIT 및 tests/u14/results.test.ts: 새/레거시 응답·내부필드없음·timing·기존화면모델 adapter·인증/선택.
- U14DeletionIT: RESULT70/PRACTICE150, 실제 FK순서·전체새자료삭제·일부실패·late callback/replay 부활없음.

합성 아동/보호자/활동 UUID와 비식별 합성 발화를 쓰고 각 시험을 트랜잭션/전용 DB fixture로 격리한다. Clock은 고정하고 재시도는 시간을 전진시켜 검증하며 긴 실제 sleep을 쓰지 않는다. Luna/분류/U15는 가짜 공급자 또는 포트대역만 사용하고 OPENAI_API_KEY를 읽거나 lunaLiveTest를 실행하지 않는다. JSON fixture는 contracts/schemas의 v2/goal/rejudgment 예시를 근거로 만들되 U12 생산 코드는 수정하지 않는다. 실제 공급자/실기기 품질 증거와 자동대역 통과를 구분한다.

## 실패와 제한 기록

환경 실패·실제 제품 회귀·누락 시험을 구분하여 code-summary/report에 명령·종료코드·실제개수·skip·커버리지와 보고서 위치를 남긴다. 신규 coverage-ignore/skip/exclusion을 넣지 않는다. 실제 사람 확인이 필요한 마이그레이션 문장 승인 표는 검토 전 서명하지 않으며 검사가 막으면 구체 문장·해시·호환 근거를 제공한다.

