# U4b 시험 결과 — Build and Test

> 실행일 2026-10-13 · 대상 리비전 f41e7bd(Code Generation 커밋 e23c042 ~ f41e7bd) · 이 PC(WSL2, Docker 연동 없음). DB 시험은 임시 폴더에 푼 PostgreSQL 16 바이너리로 돌렸다(`-Pmujoong.test.db=local`, `build-instructions.md` 2절). 끝-끝 검증은 사용자가 직접 실행했다(host 모드, Q1 A).

## 빌드

| 명령 | 결과 |
|---|---|
| `cd backend && ./gradlew --no-daemon clean assemble` | 성공(rc 0, 11초) |
| 끝-끝 A1 `clean check verifyBootJarHasNoTestClasses` | 성공(138초) — bootJar에 시험 클래스 없음 |
| 끝-끝 A2 frontend(npm ci · eslint · vitest 80% · next build) | 성공(39초) |
| 끝-끝 A3 tools(npm ci · vitest) | 성공(8초) |

## 시험 결과

명령마다 한 번씩 돌렸다(같은 명령 중복 없음).

| 명령 | 시험 수 | 통과 | 실패 | 건너뜀 |
|---|---|---|---|---|
| u4b 범위 시험 + 커버리지 `:realtime-activity:test :realtime-activity:jacocoTestCoverageVerification -PincludeTags=u4b -PcoverageScope=u4b` | 194 | 194 | 0 | 0 |
| U1 회귀 `:contract-model:test :core-api:test :realtime-activity:test :test-support:test -PincludeTags=u1` | 1,485 | 1,482 | 0 | 3 |
| U3 회귀 `:realtime-activity:test -PincludeTags=u3` | 306 | 306 | 0 | 0 |
| core-api `FlywayConfIT`(FR11.1) | 5 | 5 | 0 | 0 |
| 흔들리던 시험 `RealtimeInternalApiIT` `--rerun-tasks` × 5(FR11.2) | 12 × 5 | 60 | 0 | 0 |
| 검토 지적 R-04 `RealtimeMetricsScrapeIT` `--rerun-tasks` | 7 | 7 | 0 | 0 |
| 모듈 전체 `check`(1차, Build and Test 실행) | — | — | 1 | — |
| `:test-support:check --rerun-tasks` × 3(1차 실패 재확인) | 27 × 3 | 72 | 0 | 9 |
| 끝-끝 A1 `clean check`(2차, 전 모듈 범위 없음) | 2,169 | 2,166 | 0 | 3 |
| 끝-끝 A2 frontend vitest | 803 | 803 | 0 | 0 |
| 끝-끝 A3 tools vitest | 125 | 125 | 0 | 0 |

끝-끝 A1의 모듈별 시험 수: contract-model 294 · core-api 828 · realtime-activity 1,020 · test-support 27(건너뜀 3). 건너뜀 3건은 기준선과 같은 test-support의 Docker 전용 시험이다.

## 실패 상세

| 실행 | 시험 | 내용 | 판단 |
|---|---|---|---|
| 모듈 전체 `check` 1차 | `MujoongPostgresTest.connectionFromOutsideTheAllowedCidrIsRejected(Backend)[2] LOCAL` | `IllegalStateException: pg_hba 재적재를 10초 안에 확인하지 못했다`(`MujoongPostgres$LocalCluster.reloadAndWait`, MujoongPostgres.java:807) | 시험 기반 코드의 로컬 클러스터 대기 시간이 네 모듈 시험이 함께 도는 부하에서 넘친 것으로 본다. U4b는 test-support를 바꾸지 않았다(e23c042^..f41e7bd 변경 없음). 그 모듈만 3회, 끝-끝 A1 `clean check`에서 다시 돌렸을 때 모두 통과했다. 고치지 않았고, CI에서 같은 실패가 나는지 지켜본다 |

그 밖의 실패는 없다. Code Generation 때 실패하던 tools `verify-exit` 7건은 이번 끝-끝 A3에서 통과했다(jq · unzip을 갖춘 환경).

## 커버리지

| 범위 | 줄 | 분기 | 하한 | 근거 |
|---|---|---|---|---|
| u4b 범위(`backend/coverage-scope/u4b.txt`, 운영 파일 42개) | 3,356 / 4,155 = **80.77%** | 1,262 / 2,074 = 60.85% | 줄 80% | `jacocoTestCoverageVerification -PcoverageScope=u4b` 통과 |
| realtime-activity 모듈 전체 | 8,383 / 9,034 = **92.79%** | 3,384 / 4,319 = 78.35% | 줄 80% | 끝-끝 A1 `check` 검증 규칙 통과 |
| core-api 모듈 전체 | 6,087 / 6,318 = 96.34% | 82.70% | 줄 80% | 끝-끝 A1 `check` 검증 규칙 통과 |

u4b 범위 줄 커버리지의 여유는 0.77%p로 얇다. 분기 커버리지 하한은 없고 60.85%다.

## 끝-끝 검증(`bash scripts/verify.sh --mode host`)

`RESULT PASS rc=0 total=212s`(보고 `build/verify/report.txt`, revision f41e7bd2ca6a). 단계 A1 · A2 · A3 · A4 · F · B · C · P1 · P2 · P3 · T 모두 PASS.

- B2.seed003 PASS: 시드 002 · 003 두 번 적용(멱등), 세션 승인 문구 6종 APPROVED
- C8 PASS: START_SESSION → ACK · DEVICE_READY → 시작 안내 · 참여 응답 → ACTIVE
- C9 ~ C12 PASS: 정상 턴, 금지 문장 게이트, 끝내기(CLOSING 1회), ENDED
- P1 PASS: 교차 스키마 쓰기 · CREATE TABLE 42501, P2 PASS: 로그 위생 · 비밀 값 0건, P3 PASS: 비밀 값 0건

host 모드에서 확인하지 못한 compose 전용 항목(보고서의 `NOT_VERIFIED(host)` 5줄): 이미지 6종 빌드 · 라벨 · 비루트, Compose 병합 설정, Nginx 렌더 설정, 망 격리, 이미지 기동 거부. 이 항목들은 이 작업 기록의 측정 목표(요구사항 NFR · Testing Contract)가 아니고 U4b가 바꾸지 않은 기반 설정이다. PR CI의 images · skeleton 작업이 확인한다.

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-NFR1 | requirements.md 비기능 요구사항 NFR1 | ConfigKeyContractTest · TimeLiteralScanTest 통과, 운영 소스 시간 숫자 상수 0건 | realtime · core ConfigKeyContractTest 11 · 11, TimeLiteralScanTest 7 · 7 통과 | 끝-끝 A1 `check` 결과 XML | build-and-test | Met |
| T-NFR2 | requirements.md NFR2 | 19/21초 · 3:59/4:01 · 5:59/6:01 · 9/11초 · 39/41초 경계 시험 통과 | SessionJoinU4bTest 16 · StopConfirmFlowTest 8 · SilenceClockTest 8 · SessionSilenceU4bTest 4 · TechnicalPauseU4bTest 8 통과 | u4b 범위 실행 | build-and-test | Met |
| T-NFR3 | requirements.md NFR3 | 중복 끝내기 · 늦은 사건 · 중복 ACK에서 전이 1회 · 기기 종료 명령 1회 | TransitionTableU4bTest 24 · EndRequestU4bTest 21 통과 | u4b 범위 실행 | build-and-test | Met |
| T-NFR4 | requirements.md NFR4 | 로그 · 이벤트에 전사 원문 · 원음 0건 | PrivacyLogU4bTest 6 통과, 끝-끝 P2 로그 위생 PASS | u4b 범위 실행 · `build/verify/report.txt` | build-and-test | Met |
| T-NFR5 | requirements.md NFR5 | 참여 전 · 확인 대기 · 공백 안내에서 생성 발화 0건 | SessionJoinU4bTest 16 · SessionLifecycleU4bIT 4 통과, 끝-끝 C8 PASS | u4b 범위 실행 · report.txt | build-and-test | Met |
| T-NFR6a | requirements.md NFR6 · Testing Contract coverage | u4b 범위 줄 커버리지 ≥ 80% | 80.77% | `jacocoTestCoverageVerification -PcoverageScope=u4b` rc 0 | build-and-test | Met |
| T-NFR6b | requirements.md NFR6 | 모듈 전체 줄 커버리지 ≥ 80% 유지 | realtime 92.79% · core-api 96.34% | 끝-끝 A1 `check` | build-and-test | Met |
| T-NFR7 | requirements.md NFR7 · Testing Contract scope_floor | U1 · U3 회귀와 전체 check 통과, 기존 시험 그린 | U1 1,485 · U3 306 · A1 check 2,169 실패 0. 1차 check의 test-support 1건은 재실행 4회 통과(실패 상세) | 회귀 실행 · 끝-끝 A1 | build-and-test | Met |
| T-NFR8 | requirements.md NFR8 | 스키마 검증 시험 · ContractFilesTest 통과 | ContractFilesTest 14 · SnapshotHashGoldenTest 2 · SessionRecordU4bIT 6(이벤트 스키마 검증) 통과 | A1 · u4b 범위 실행 | build-and-test | Met |
| T-NFR9 | requirements.md NFR9 | 삭제 뒤 Realtime 행 · 원음 파일 0건 | RealtimeDeletionHandlerIT 4 · RealtimeVoiceDeletionParticipantIT 4 통과 | u4b 범위 · A1 | build-and-test | Met |
| T-TC-VOL | Testing Contract obligations.strategy_volume | 구성요소마다 5 ~ 8개 + 경계 통합 시험 | 9개 구성요소 모두 5개 이상(최소: 시간 키 ConfigKeyContractTest 11), 통합 시험 8개 클래스 | u4b 범위 실행 | build-and-test | Met |
| T-FR11.2 | requirements.md FR11.2 | 흔들리던 시험 반복 실행 통과 | 5회 × 12건 통과 | flaky 실행 5회 | build-and-test | Met |
| T-FR11.4 | requirements.md FR11.4 | 검증 스크립트 시드 003 적용 · 끝-끝 C 참여 응답 | B2.seed003 PASS · C8 PASS | report.txt | build-and-test | Met |

## 참고: 시험 클래스별 수와 계획의 최소 수

단위 시험 지시 5절의 클래스별 최소 수보다 적은 클래스가 셋 있다. 구성요소 단위로는 Standard 목표(5 ~ 8개)를 채운다.

| 클래스 | 계획 최소 | 실제 | 같은 구성요소의 다른 시험 |
|---|---|---|---|
| RealtimeDeletionHandlerIT | 7 | 4 | RealtimeDeletionParticipantOrderTest 8 · RealtimeVoiceDeletionParticipantIT 4 |
| SessionRecordU4bIT | 7 | 6 | SessionSettleU4bTest 7 · ResultSnapshotBuilderTest 6 |
| SessionViewU4bIT | 3 | 2 | SessionInternalControllerU4bTest 7 |
