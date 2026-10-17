# 제브 어댑터 선 형식 정정 — 유닛 시험 안내

> 단계마다 **바꾼 쪽만** 돌리는 유닛 범위 명령이다. 전체 `bash scripts/verify.sh`는 Build and Test에서 한 번 돌린다. 모든 시험은 가짜 제브(루프백 가짜 서버 · 손으로 푸는 `CompletableFuture`)만 쓴다(R-F1). 결과는 테스트 대역 증거로만 보고한다(R-M11).

## 전제

- JDK 21, Docker(통합 시험의 Testcontainers PostgreSQL). 이 PC에서는 Docker 명령을 `sg docker -c '…'`로 감싼다.
- 저장소 루트의 `backend/`에서 실행한다.

## 명령

| 목적 | 명령 |
|---|---|
| U13 태그 시험만(단위 + 통합) | `cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker` |
| 어댑터 · 정책 리소스 시험만(빠른 확인) | `cd backend && ./gradlew :realtime-activity:test --tests '*JevGuardrailClientTest' --tests '*GuardrailPolicyV2Test' --tests '*GuardrailConfigurationTest' --tests '*LiveContentRegistryTest' --tests '*GuardrailModeStartupChecksTest' --tests '*JevLiveTaskIsolationTest' -Pmujoong.test.db=docker` |
| 바꾼 모듈 전체(정적 검사 + 단위 + 통합 + 모듈 JaCoCo 하한) | `cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker` |
| 계약 · 설정 키 · 스키마 대조(계약 파일을 고친 단계) | `cd backend && ./gradlew :contract-model:test :realtime-activity:test --tests '*ConfigKeyContractTest' --tests '*ContractFilesTest' --tests '*TimeLiteralScanTest' --tests '*U8ContractSchemasTest' -Pmujoong.test.db=docker` |
| 모듈 밖 회귀(계약 · 설정 키를 건드렸으므로 필수) | `cd backend && ./gradlew :core-api:check -Pmujoong.test.db=docker` |
| U13 범위 줄 커버리지 80% | `cd backend && ./gradlew :realtime-activity:test :realtime-activity:jacocoTestReport -PincludeTags=u13 -PcoverageScope=u13 -Pmujoong.test.db=docker` |
| `jevLiveTest` 분리 점검 | `cd backend && ./gradlew :realtime-activity:test --tests '*JevLiveTaskIsolationTest' -Pmujoong.test.db=docker` |

`-PexcludeTags`는 쓰지 않는다. 합격 근거는 `db` 태그를 포함한 실행이다(R-F2: 시험을 건너뛰어 통과시키지 않는다).

## 단계별 기대

- Step 1 · 2: 문턱 키를 더한 뒤에도 기존 `u13` 시험 169개가 통과하고 `ConfigKeyContractTest`가 새 키를 대조한다.
- Step 3 · 4: 정책 리소스 새 판 시험(`GuardrailPolicyV2Test` 새 5개 이상)과 영향받은 기존 시험이 통과한다.
- Step 5 · 6: `JevGuardrailClientTest`가 공식 형식 기준으로 통과하고, 표적 회귀(경로 `/v1/systemone` · 본문 최상위 키 `model` · `state` · `questions`)가 옛 어댑터에서는 실패한다. 판정 경계(0.5 직전 · 정확히 0.5 · 직후 · 동률)를 모두 시험한다.
- Step 7: `JevLiveTaskIsolationTest` 통과, 키 없는 `jevLiveTest`가 `SKIPPED_NO_KEY`로 끝난다.
- Step 8 · 9: 계약 대조 시험 통과, `:realtime-activity:check`와 `:core-api:check` 모두 실패 0, u13 범위 줄 80% 이상.
- 모든 단계: 하한 · 기존 단언 · 대조 시험 · ArchUnit 시험을 낮추거나 끄지 않는다.

## 결과 보고 틀

| 증거 종류 | 이 작업 |
|---|---|
| 테스트 대역(가짜 제브 서버) | 위 명령의 합격 결과 |
| 합성 대화 | `verify.sh` C안 기기 시뮬레이터 한 턴(Build and Test) |
| 실제 API(제브 실호출) | 이 작업에서 하지 않음 — 사람이 키를 넣고 `jevLiveTest`로 확인(`[실호출-합성]`) |
| 실제 기기 | 이 작업에서 하지 않음 |
