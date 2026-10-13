# U4b Build and Test 요약

> 작업 기록 `261013-u4b-activity-realtime` · scope refactor · 시험 전략 Standard · test-after. 대상 리비전 f41e7bd. 자세한 수치와 근거는 `test-results.md`에 있다.

## 빌드 상태와 준비물

- 빌드 성공(`clean assemble`), bootJar 두 개에 시험 클래스 없음(끝-끝 A1).
- 준비물: JDK 21 · Node 22. 이 PC는 Docker 연동이 꺼져 있어, DB 시험과 끝-끝 host 모드에 임시 폴더의 PostgreSQL 16을 썼다. 끝-끝 host 모드에 필요한 jq · unzip도 같은 방식으로 갖췄다(`build-instructions.md`).

## 시험 종류

| 종류 | 지시 파일 | 실행 |
|---|---|---|
| 단위 · 통합(u4b 범위) | Code Generation `unit-test-instructions.md` | 194건 통과 |
| 통합 · 유닛 사이 회귀 | `integration-test-instructions.md` | U1 1,485 · U3 306 · 전체 check 2,169 실패 0 |
| 시간 경계(성능 수치 목표 없음) | `performance-test-instructions.md` | 경계 시험 통과 |
| 보안 · 개인정보 | `security-test-instructions.md` | PrivacyLogU4bTest · 삭제 시험 · 끝-끝 A4 · P1 ~ P3 통과 |
| 끝-끝 시스템 | `integration-test-instructions.md` 2절 | `verify.sh --mode host` RESULT PASS(212초) |

## 커버리지 기대값과 결과

| 범위 | 기대 | 결과 |
|---|---|---|
| u4b 범위 줄(유닛 하나, stage-level) | ≥ 80% | 80.77% |
| realtime-activity 모듈 줄 | ≥ 80% | 92.79% |
| core-api 모듈 줄 | ≥ 80% | 96.34% |

## Target Verification Matrix

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| T-NFR1 | requirements.md NFR1 | 시간 키 대조 · 시간 상수 검사 통과 | ConfigKeyContractTest · TimeLiteralScanTest 통과 | test-results.md · 끝-끝 A1 | build-and-test | Met |
| T-NFR2 | requirements.md NFR2 | 경계 ±1초 시험 통과 | 경계 시험 5개 클래스 통과 | test-results.md | build-and-test | Met |
| T-NFR3 | requirements.md NFR3 | 전이 1회 · 종료 명령 1회 | TransitionTableU4bTest · EndRequestU4bTest 통과 | test-results.md | build-and-test | Met |
| T-NFR4 | requirements.md NFR4 | 원문 · 원음 0건 | PrivacyLogU4bTest 통과 · 끝-끝 P2 PASS | test-results.md · build/verify/report.txt | build-and-test | Met |
| T-NFR5 | requirements.md NFR5 | 참여 압박 생성 발화 0건 | SessionJoinU4bTest · 끝-끝 C8 통과 | test-results.md | build-and-test | Met |
| T-NFR6a | requirements.md NFR6 · Testing Contract | u4b 범위 줄 ≥ 80% | 80.77% | jacocoTestCoverageVerification rc 0 | build-and-test | Met |
| T-NFR6b | requirements.md NFR6 | 모듈 줄 ≥ 80% | 92.79% · 96.34% | 끝-끝 A1 check | build-and-test | Met |
| T-NFR7 | requirements.md NFR7 · Testing Contract scope_floor | 기존 시험 그린 | 실패 0(1차 check의 test-support 1건은 재실행 4회 통과) | test-results.md 실패 상세 | build-and-test | Met |
| T-NFR8 | requirements.md NFR8 | 계약 · 스키마 시험 통과 | ContractFilesTest · SessionRecordU4bIT 통과 | test-results.md | build-and-test | Met |
| T-NFR9 | requirements.md NFR9 | 삭제 뒤 0건 | 삭제 통합 시험 통과 | test-results.md | build-and-test | Met |
| T-TC-VOL | Testing Contract strategy_volume | 구성요소마다 5 ~ 8개 + 경계 통합 | 9개 구성요소 모두 충족 | test-results.md | build-and-test | Met |
| T-FR11.2 | requirements.md FR11.2 | 흔들리던 시험 반복 통과 | 5회 통과 | test-results.md | build-and-test | Met |
| T-FR11.4 | requirements.md FR11.4 | 시드 003 · C8 참여 응답 | B2.seed003 · C8 PASS | build/verify/report.txt | build-and-test | Met |

목표 출처 조사: 이 범위에는 NFR Requirements · NFR Design 단계가 없어 `nfr-requirements/` · `nfr-design/` 산출물이 없다. 측정 목표는 요구사항 NFR1 ~ NFR9, Code Generation 계획의 Testing Contract, 실행으로 확인할 수 있는 FR11.2 · FR11.4에서 모았다.

## 준비 상태

- **빌드 준비:** 됨
- **시험 준비:** 됨. 모든 목표 Met, 실행한 명령 전부 통과(최종 실행 기준)
- **배포 준비:** 이 작업 기록은 PR로 합치는 것까지다. 남은 것은 아래와 같다.

## 알려진 제한과 남은 일

- compose 전용 검사 5항목(이미지 · Compose 병합 설정 · Nginx · 망 격리 · 이미지 기동 거부)은 host 모드에서 확인하지 못했다. 측정 목표는 아니지만 PR CI의 images · skeleton 작업 결과를 보고 합쳐야 한다.
- test-support `MujoongPostgresTest`(LOCAL 백엔드)가 부하에서 한 번 실패했다. U4b와 무관한 시험 기반 코드의 10초 대기다. CI에서 다시 나면 따로 다룬다.
- u4b 범위 줄 커버리지 여유가 0.77%p로 얇고, 분기 커버리지는 60.85%다.
- Code Generation 검토 지적 R-01(WAITING_CHILD → ACTIVE 커밋 응답 분실 시험 없음)은 감수한 위험으로 남아 있다.
- R-04: `RealtimeMetricsScrapeIT`은 이번 단독 재실행 · 끝-끝 A1 · U1 회귀에서 모두 통과했다. 기준선(e23c042^)에서 같은 시험을 반복해 보지는 않았다.
- 커밋은 로컬 `master`에 있고, U4b-J24대로 브랜치 · PR로 옮겨야 한다. 커버리지는 Code Generation 보고(80.75%)와 이번 측정(80.77%)이 거의 같다.
