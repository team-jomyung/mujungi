# 건너뛴 시험과 재검증 절차

## 상태와 사유

**최신 상태: 아래 LOCAL3개를 PostgreSQL18.6에서 재실행하여 모두 통과했다. 클래스전체6개 통과·skip0·실패0, 후속 전체check2,405개 통과·skip0·실패0이다.** 프로젝트 기준 LOCAL16은 별도 미검증이며 아래 초기 건너뜀 이력은 보존한다.

2026-10-14 전체 백엔드 집계 2,405개 중 2,402개 통과·3개 건너뜀·실패0이다. 건너뛴 3개는 모두 기존 test-support의 `MujoongPostgresTest`에서 **LOCAL 매개변수**를 받는 사례다. Docker 사례는 통과했으나 호스트 PostgreSQL을 쓰는 경로는 검증되지 않았다. 이번 전체 check의 test-support는 UP-TO-DATE라 이전 결과를 재사용했다.

JUnit XML의 공통 사유는 `Assumption failed: local 백엔드를 쓸 수 없어 건너뜀(J132)`이며 `TestAbortedException`으로 기록됐다. XML 표시 이름은 셋 모두 `[2] backend = LOCAL`이므로 아래 메서드 이름은 각 skipped 스택에서 식별했다. 지금 호스트에는 기본 경로 `/usr/lib/postgresql/16/bin`의 PostgreSQL16이 없다. 새로 확인한 U4a local 실행의 초기화 실패도 같은 환경 부재를 확인한다. 검사를 끄거나 성공으로 바꾸지 않았다.

## 항목별 상세

| 항목 | 클래스·메서드 / 매개변수 | 미검증 동작 | 통과 기준 |
|---|---|---|---|
| SKIP-01 | `kr.mujoong.testsupport.db.MujoongPostgresTest.initScriptsCreateRolesSchemasAndSingleHbaAllowLine` / LOCAL | 로컬 초기화 스크립트의 역할·스키마·데이터 분류·UTC·권한·HBA 구성 | 역할별 접속 사용자 일치, synthetic·UTC, core/realtime 권한 분리, scram-sha-256, 허용 CIDR 한 줄과 local/나머지 reject 규칙 일치 |
| SKIP-02 | `kr.mujoong.testsupport.db.MujoongPostgresTest.twoMigrationSetsKeepSeparateHistoryTablesInFlywaySchema` / LOCAL | 로컬에서 core/realtime 마이그레이션 이력 분리와 기본 표 권한 | flyway의 core_schema_history/realtime_schema_history 두 이력, core_app DML 허용, realtime_app 접근 SQLSTATE42501 |
| SKIP-03 | `kr.mujoong.testsupport.db.MujoongPostgresTest.connectionFromOutsideTheAllowedCidrIsRejected` / LOCAL | 로컬 HBA 허용 대역 밖 접속 차단 | 문서용 주소192.0.2.1/32만 허용한 클러스터에서 core_app 접속 거절 SQLSTATE28000 |

소스: `backend/test-support/src/test/java/kr/mujoong/testsupport/db/MujoongPostgresTest.java`의34·85·70행, 공통 가용성 확인109행. 현재 증거 원본: `backend/test-support/build/test-results/test/TEST-kr.mujoong.testsupport.db.MujoongPostgresTest.xml`. 세부 집계 사본: `evidence/skipped-tests.json`.

## 재실행 준비

1. 호스트 PostgreSQL16의 `initdb`, `pg_ctl`, `postgres`, `psql` 네 바이너리가 기본 경로에서 실행 가능해야 한다. 운영 DB에 붙는 시험이 아니라 시험이 임시 클러스터를 생성·종료하는 방식이다.
2. 비루트 사용자로 실행한다. root 실행이라면 `runuser`와 `postgres` OS 사용자도 필요하다. Docker 사례도 함께 확인하려면 Docker 데몬이 접근 가능해야 한다.
3. JDK21과 저장소 Gradle wrapper를 사용한다. Node나 실제 AI 공급자는 이 시험에 필요 없다. 자동 생성 비밀번호를 로그나 문서에 복사하지 않는다.
4. PostgreSQL 설치 경로가 다르면 시험 JVM에 `mujoong.test.pg-bin`을 전달한다. 단순 Gradle `-Pmujoong.test.pg-bin`은 현재 build-logic에서 전달하지 않는다. 기본 경로 설치를 권장하며 별도 경로는 아래 JAVA_TOOL_OPTIONS 방식을 사용한다.

## 재실행 명령

backend 디렉터리에서 실행한다. `--rerun-tasks`는 이전 UP-TO-DATE 결과를 재사용하지 않게 한다. 이 매개변수 시험은 LOCAL과 DOCKER를 명시 열거하므로 `-Pmujoong.test.db=local`만으로 Docker 사례가 사라지지는 않는다.

```sh
./gradlew --no-daemon :test-support:test --tests 'kr.mujoong.testsupport.db.MujoongPostgresTest' --rerun-tasks
```

별도 PostgreSQL 경로가 필요한 경우 `/path/to/postgresql/16/bin`을 실제 경로로 바꾸고 실행한다. 이 옵션은 해당 명령의 JVM들에만 적용한다.

```sh
JAVA_TOOL_OPTIONS='-Dmujoong.test.pg-bin=/path/to/postgresql/16/bin' ./gradlew --no-daemon :test-support:test --tests 'kr.mujoong.testsupport.db.MujoongPostgresTest' --rerun-tasks
```

항목 하나만 다시 확인하려면 메서드 단위 필터를 쓴다. 각 명령은 해당 메서드의 LOCAL·DOCKER 두 사례를 실행한다.

```sh
./gradlew --no-daemon :test-support:test --tests 'kr.mujoong.testsupport.db.MujoongPostgresTest.initScriptsCreateRolesSchemasAndSingleHbaAllowLine' --rerun-tasks
./gradlew --no-daemon :test-support:test --tests 'kr.mujoong.testsupport.db.MujoongPostgresTest.twoMigrationSetsKeepSeparateHistoryTablesInFlywaySchema' --rerun-tasks
./gradlew --no-daemon :test-support:test --tests 'kr.mujoong.testsupport.db.MujoongPostgresTest.connectionFromOutsideTheAllowedCidrIsRejected' --rerun-tasks
```

## 완료 확인과 기록 갱신

JUnit XML과 `backend/test-support/build/reports/tests/test/index.html`을 확인한다. 클래스 전체 명령에서 Docker·LOCAL이 모두 준비됐다면 **6개 통과, 실패0, 건너뜀0**이어야 한다. 적어도 위 LOCAL3개가 실제 실행돼 통과했는지 각각 확인한다. BUILD SUCCESSFUL만으로 건너뜀이 해소됐다고 판단하지 않는다.

재실행 날짜·환경/PostgreSQL 버전·명령·각 항목 결과·종료코드·XML 증거를 `test-results.md`와 이 목록에 추가한다. 이전 건너뜀 이력은 삭제하지 않는다. 그 다음 전체 `./gradlew --no-daemon check`를 실행해 전체 집계를 갱신한다. 재실행 전까지 LOCAL3개는 미검증 상태다.

## 2026-10-14 재검증 — PostgreSQL18.6

사용자가 PostgreSQL을 설치한 뒤 `/usr/lib/postgresql/18/bin`의 네 바이너리가 실행 가능한 것을 확인했다. 서버 버전은18.6이며 프로젝트 기준16과 다르다. 위 별도 경로 명령을 실제18경로로 실행하여 클래스 전체6개(DOCKER3·LOCAL3)가 모두 통과했다. 실패0·오류0·건너뜀0, 시험17.371초·BUILD SUCCESSFUL59초다. 따라서 SKIP-01·02·03은 **LOCAL18.6에서 통과** 상태다. 이전16환경 미검증·건너뜀 이력은 유지하며18.6 성공을16 검증으로 바꾸지 않는다.

증거: [evidence/skipped-retest-postgresql18.json](evidence/skipped-retest-postgresql18.json). `JAVA_TOOL_OPTIONS='-Dmujoong.test.pg-bin=/usr/lib/postgresql/18/bin'`을 해당 명령에만 적용했고 시험 코드·기준 버전·스키마·품질 하한은 수정하지 않았다. 후속 전체check는34초에 성공했고 test-support27개 모두 통과·skip0·실패0이었다. 나머지 모듈의 UP-TO-DATE 결과와 합한 전체집계는2,405개 통과·skip0·실패0이다. evidence/backend-check-postgresql18.json에 보존했다.

## Sources

MujoongPostgresTest.java; MujoongPostgres.java의isLocalAvailable·PG_BIN_PROPERTY; backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts; JUnit XML; evidence/backend-all.json.
