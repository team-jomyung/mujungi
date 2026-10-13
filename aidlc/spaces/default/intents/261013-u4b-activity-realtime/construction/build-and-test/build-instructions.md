# U4b 빌드 지시 — 활동 세션 Realtime

> Build and Test(3.6) · 작업 기록 `261013-u4b-activity-realtime`. 대상은 Code Generation 커밋 e23c042 ~ f41e7bd다. 모든 명령은 저장소 루트(`/home/jdmea/finalProject`) 기준이다.

## 1. 준비물

| 항목 | 버전 · 조건 | 이 PC(WSL) 확인값 |
|---|---|---|
| JDK | 21 | OpenJDK 21.0.12 |
| Node.js | 22(프런트 · tools · 끝-끝 검증) | v22.22.3 |
| Gradle | 래퍼(`backend/gradlew`)가 내려받는다. 실행 비트가 있어야 한다 | 100755 확인 |
| DB 시험 백엔드 | Docker(Testcontainers) 또는 호스트 PostgreSQL 16(`-Pmujoong.test.db=local`) | Docker 연동 꺼짐 → PostgreSQL 16 바이너리를 임시 폴더에 풀어 씀 |
| 끝-끝 검증 | compose 모드: Docker + Compose v2.24.4 이상 / host 모드: PostgreSQL 16 바이너리 · jq · openssl · curl · node · unzip | host 모드 |

비밀 값 · API 키는 쓰지 않는다. 끝-끝 검증은 가짜 공급자를 쓰고, 생성 비밀은 검증 스크립트가 만들고 지운다.

## 2. 환경 설정

- 기본은 설정 없이 돈다. 시험 프로필은 `backend/*/src/test/resources/application-test.yml`이다.
- Docker가 없을 때 DB 시험을 호스트 PostgreSQL 16으로 돌린다.
  - 바이너리가 `/usr/lib/postgresql/16/bin`에 있으면: `-Pmujoong.test.db=local`만 붙인다.
  - 다른 곳에 풀었으면(이 PC): 그 경로를 시스템 속성으로 넘기고 공유 라이브러리 경로를 더한다.
    ```
    export LD_LIBRARY_PATH="<풀어 둔 곳>/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export JAVA_TOOL_OPTIONS="-Dmujoong.test.pg-bin=<풀어 둔 곳>/usr/lib/postgresql/16/bin"
    ```
  - 끝-끝 host 모드는 `MUJOONG_PG_BIN=<풀어 둔 곳>/usr/lib/postgresql/16/bin`을 쓴다.

## 3. 빌드 명령

```
cd backend && ./gradlew --no-daemon clean assemble
```

- 네 모듈(contract-model · test-support · core-api · realtime-activity)을 컴파일하고 bootJar 두 개(core-api · realtime-activity)를 만든다.
- U4b가 바꾼 빌드 입력: realtime V3 마이그레이션, `application.yml` 시간 키, `backend/coverage-scope/u4b.txt`.

## 4. 빌드 확인

- `BUILD SUCCESSFUL`과 종료 코드 0
- 마이그레이션 판 확인은 시험으로 한다: `RealtimeSchemaIT`(realtime 판 3) · core-api `FlywayConfIT`.
- bootJar에 시험 클래스가 없는지: `cd backend && ./gradlew --no-daemon verifyBootJarHasNoTestClasses`(끝-끝 검증 A1에 포함)
- 끝-끝 전체: `bash scripts/verify.sh --mode host`(또는 `--mode compose`). 결과는 `build/verify/report.txt`의 마지막 줄 `RESULT`다.

## 5. 자주 나는 문제

| 증상 | 원인 | 조치 |
|---|---|---|
| `./gradlew: Permission denied` | WSL에서 Windows 도구가 실행 비트를 지움 | `git diff --summary`로 mode change 확인 뒤 `chmod +x backend/gradlew`, 의도하지 않은 755→644는 커밋하지 않는다(project 규칙) |
| DB 시험이 `BackendUnavailableException` | Docker도 호스트 PostgreSQL 16도 없음 | 2절대로 바이너리 경로를 넘기거나 Docker WSL 연동을 켠다 |
| `docker: command not found`(WSL) | Docker Desktop의 WSL 연동이 꺼짐 | Settings → Resources → WSL integration을 켜거나 host 모드를 쓴다 |
| 커버리지 검증 실패 | 범위 줄 커버리지 80% 미만 | 하한을 낮추지 않는다. 시험을 더하거나 차이를 보고한다 |
