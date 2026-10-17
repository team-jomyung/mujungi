# U13 제브 게이트 — 유닛 시험 안내

> 이 문서는 단계마다 **바꾼 쪽만** 돌리는 유닛 범위 명령이다. 전체 `bash scripts/verify.sh`는 Build and Test에서 한 번 돌린다. 모든 시험은 가짜 제브 · 가짜 공급자만 쓴다(R-F1). 결과는 테스트 대역 증거로만 보고한다(R-M11).

## 전제

- JDK 21, Docker(통합 시험의 Testcontainers PostgreSQL). 이 PC에서는 Docker 명령을 `sg docker -c '…'`로 감싼다.
- 저장소 루트의 `backend/`에서 실행한다.

## 명령

| 목적 | 명령 |
|---|---|
| U13 시험만(단위 + 통합) | `cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker` |
| 단위만(DB 없이) | `cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 -PexcludeTags=db` |
| 바꾼 모듈 전체(정적 검사 + 단위 + 통합) | `cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker` |
| 계약 대조(계약 파일을 고친 단계) | `cd backend && ./gradlew :contract-model:test :realtime-activity:test --tests '*ConfigKeyContractTest' --tests '*ContractFilesTest' --tests '*TimeLiteralScanTest' -Pmujoong.test.db=docker` |
| U13 범위 줄 커버리지 80% | `cd backend && ./gradlew :realtime-activity:test :realtime-activity:jacocoTestReport -PincludeTags=u13 -PcoverageScope=u13 -Pmujoong.test.db=docker` |
| `jevLiveTest` 기본 빌드 제외 점검(Step 15b) | `cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 --tests '*JevLiveTaskIsolationTest' -Pmujoong.test.db=docker` |
| 제브 실호출(사람이 키를 넣고 하는 수동 확인, 시험 · CI · `verify.sh`에 넣지 않음) | `cd backend && ./gradlew :realtime-activity:jevLiveTest` — 키는 셸 env로만 넣는다(`RUNBOOK`의 `jevLiveTest` 절) |
| 인프라 정렬 단계(Step 15) | `bash infra/bin/infra-validate`, `bash infra/ci/test-infra-validate.sh`, `bash infra/ci/test-host-tools.sh` |

`-PexcludeTags`는 DB 없는 빠른 확인에만 쓰고, 합격 근거는 `db` 태그를 포함한 실행이다(R-F2: 시험을 건너뛰어 통과시키지 않는다).

## 단계별 기대

- Step 4 · 6: `GuardrailEvent*` 시험 + 갱신한 `RealtimeSchemaIT` · `PracticeMigration*` · `SingleInstanceMarkerTest` · `RealtimeDeletionParticipantOrderTest` 통과.
- Step 8 · 10 · 12 · 14: 새 `@Tag("u13")` 시험 통과, 기존 U3 · U4b · U5b · U8 · U11 시험은 단언 변경 없이 통과.
- Step 15: `backend/coverage-scope/u13.txt` 범위 줄 80% 이상(범위 측정은 빈 목록 · 없는 파일이면 실패, J137).
- 모든 단계: 하한 · 기존 단언 · 대조 시험 · ArchUnit 시험을 낮추거나 끄지 않는다.

## 결과 보고 틀

| 증거 종류 | 이 단위 |
|---|---|
| 테스트 대역(가짜 제브 · 가짜 공급자) | 위 명령의 합격 결과 |
| 합성 대화 | `verify.sh` C안 기기 시뮬레이터 한 턴(Build and Test) |
| 실제 API(제브 실호출) | 이 단위에서 하지 않음 — 사람이 키를 넣은 뒤 확인(열린 항목) |
| 실제 기기 | 이 단위에서 하지 않음 |
