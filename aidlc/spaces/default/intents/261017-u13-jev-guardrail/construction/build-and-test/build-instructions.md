# U13 제브 게이트 — 빌드 지시서

> 작업 기록 `261017-u13-jev-guardrail` · Build and Test. 범위 `refactor`, 시험 전략 Standard, 브랜치 `unit/u13-jev-guardrail`.
> 이 지시서는 **이 PC(WSL Ubuntu)** 에서 실제로 돌린 명령을 적는다. 실제 유료 AI 공급자는 부르지 않는다(R-F1). 키 · 계정 · 실제 도메인 값은 이 문서에 쓰지 않는다(R-F3).

## 1. 전제

| 항목 | 내용 |
|---|---|
| JDK | 21 (`java -version`) |
| Docker | 통합 시험의 Testcontainers PostgreSQL과 검증 스택. 이 PC에서는 셸이 docker 그룹을 늦게 받으므로 `sg docker -c '…'`로 감싼다 |
| 도구 | `jq` · `unzip` · `shellcheck`(`verify.sh` 전제 점검이 처음에 확인한다), Node(프런트), Gradle 래퍼(`backend/gradlew`) |
| `date` | `infra/ci/test-host-tools.sh`는 GNU date가 필요하다. 이 PC의 `date`가 uutils이면 GNU date를 PATH 앞에 둔다(`project.md` Corrections). 지금은 GNU 9.4 |
| 격리 | 검증은 Compose 프로젝트 `mujoong-verify`, `127.0.0.1:8443`만 쓴다. 사용자의 수동 스택 `mujoong-manual`과 그 볼륨은 건드리지 않는다(R-F12) |

## 2. 환경 설정

- 환경 파일은 저장소에 값을 두지 않는다. `infra/ci/gen-env.sh`가 실행마다 무작위 값을 만들고(`verify.sh`가 부른다), 실행 끝에 `verify.sh`가 정리한다.
- 제브 변수(`JEV_API_KEY` · `JEV_BASE_URL` · `JEV_MODEL`)는 검증 · CI에서 비어 있거나 가짜다. 가짜 공급자는 키를 보지 않는다(U11 BR12.2). **키를 넣지 않는다.**
- `mujoong.guardrail.jev.mode`는 dev · test · CI에서 STUB가 기본이고, demo · prod에서는 REAL + 키 + 허용 호스트 + 고정 버전 모델이 아니면 기동을 거부한다.

## 3. 빌드 명령

| 목적 | 명령 | 기대 |
|---|---|---|
| 백엔드 컴파일 + 정적 검사 + 단위 + 통합 + 모듈 JaCoCo 하한 | `sg docker -c 'cd backend && ./gradlew :contract-model:test :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'` | BUILD SUCCESSFUL, 실패 0 · 건너뜀 0 |
| 백엔드 core-api(이 단위가 건드리지 않았음을 회귀로 확인) | `sg docker -c 'cd backend && ./gradlew :core-api:check -Pmujoong.test.db=docker'` | BUILD SUCCESSFUL |
| 전체 끝-끝 회귀(정적 → 기동 → 기기 시뮬레이터 한 턴) | `sg docker -c 'bash scripts/verify.sh --mode compose'` | 단계 A · B · C와 사후 단언 P1 · P2 · P3 · T 통과 |
| 인프라 점검 | `sg docker -c 'bash infra/bin/infra-validate'`, `sg docker -c 'bash infra/ci/test-infra-validate.sh'`, `bash infra/ci/test-host-tools.sh` | 모두 통과 · 어긋남 0 |
| 이미지 점검 ④(demo 더미 기동) | `sg docker -c 'bash infra/ci/image-checks.sh'` | 제브 키 없는 realtime이 기동 거부로 끝나는지 포함 |

`verify.sh`의 보고는 `build/verify/report.txt`에 남는다. 기본 기한은 5400초다.

## 4. 빌드 검증 단계

1. `:realtime-activity:check`가 `verifyJevLiveIsolation`(기본 빌드가 `jevLiveTest`를 부르지 않는지)을 포함해 통과해야 한다.
2. Flyway: 새 마이그레이션은 realtime `V6__u13_jev_guardrail.sql` 하나다. 기존 마이그레이션 파일 내용은 바꾸지 않았다(R-M2). 기존 표 제약을 더하지 않아 `infra/migration-approvals.txt` 대상이 아니다(R-M3).
3. 계약 대조 시험(`ConfigKeyContractTest` · `TimeLiteralScanTest` · `ContractFilesTest`)이 통과해야 한다.

## 5. 문제 해결

| 증상 | 원인 | 조치 |
|---|---|---|
| `permission denied` (docker) | 셸이 docker 그룹을 아직 못 받음 | 명령을 `sg docker -c '…'`로 감싼다 |
| `verify.sh` PREREQ 실패 | `jq` · `unzip` · `shellcheck` 없음 | 설치 후 다시 실행 |
| `infra-validate`가 nginx 단계에서 조용히 rc=1 | `docker run` 권한 | `sg docker -c`로 감싼다 |
| `test-host-tools.sh`의 turn-metrics 실패 | uutils `date`가 `%3N` 자릿수를 무시 | GNU date를 PATH 앞에 둔다. 코드 결함으로 보고하지 않는다 |
| `DeviceGatewayU3IT`가 OUTPUT_BEGIN 대기 10초 초과 | 부하로 보이는 일시 실패(U3 시험, 이번 변경과 무관해 보임, 원인 미확정) | 단독 재실행 후 통과하면 기록만 남기고, 재발하면 이번 변경과의 관련성을 다시 본다 |
| `demo`에서 realtime이 `MUJOONG_STARTUP_REJECTED`로 종료 | 제브 키 · 주소 · 모델이 없음(의도된 거부) | `infra/RUNBOOK.md` 단계 4의 순서대로 R-F15 확인 기록 → Parameter Store 입력 → 배포 |
