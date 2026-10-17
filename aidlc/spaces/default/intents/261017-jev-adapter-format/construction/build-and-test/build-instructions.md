# 제브 어댑터 선 형식 정정 — 빌드 지시서

> 작업 기록 `261017-jev-adapter-format` · Build and Test. 범위 `bugfix`, 시험 전략 Standard, 브랜치 `unit/u13-jev-guardrail`(U13 미병합, 이어서 작업).
> 이 PC(WSL Ubuntu)에서 실제로 돌린 명령을 적는다. 실제 유료 AI 공급자는 부르지 않는다(R-F1). 키 · 계정 · 실제 주소 · 모델 이름 값은 이 문서에 쓰지 않는다(R-F3). 비밀 스캔 패턴과 겹치는 모양의 리터럴도 적지 않는다(앞 기록 교훈).

## 1. 전제

| 항목 | 내용 |
|---|---|
| JDK | 21 |
| Docker | Testcontainers PostgreSQL과 검증 스택. 이 PC에서는 `sg docker -c '…'`로 감싼다 |
| 도구 | `jq` · `unzip` · `shellcheck`, Node(프런트), Gradle 래퍼(`backend/gradlew`) |
| `date` | `infra/ci/test-host-tools.sh`는 GNU date가 필요하다(이 PC는 현재 GNU 9.4) |
| 격리 | 검증은 Compose 프로젝트 `mujoong-verify`, `127.0.0.1:8443`만 쓴다. 수동 스택 `mujoong-manual`과 그 볼륨은 건드리지 않는다(R-F12) |

## 2. 환경 설정

- 환경 파일은 저장소에 값을 두지 않는다. `infra/ci/gen-env.sh`가 실행마다 무작위 값을 만들고 `verify.sh`가 부른다.
- 제브 변수(`JEV_API_KEY` · `JEV_BASE_URL` · `JEV_MODEL`)는 검증 · CI에서 비어 있거나 가짜다. **키를 넣지 않는다.** 새 설정 `JEV_PASS_THRESHOLD`(기본 0.5, 비밀 아님)는 설정하지 않아도 기본값으로 돈다.
- `mujoong.guardrail.jev.mode`는 dev · test · CI에서 STUB가 기본이고, demo · prod에서는 REAL + 키 + 주소 + 모델 + 문턱 범위가 맞아야 기동한다.

## 3. 빌드 명령

| 목적 | 명령 | 기대 |
|---|---|---|
| 백엔드 컴파일 · 정적 검사 · 단위 · 통합 · 모듈 JaCoCo 하한 | `sg docker -c 'cd backend && ./gradlew :contract-model:test :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'` | BUILD SUCCESSFUL, 실패 0 |
| 모듈 밖 회귀(계약 · 설정 키를 건드렸으므로 필수, 앞 기록 교훈) | `sg docker -c 'cd backend && ./gradlew :core-api:check -Pmujoong.test.db=docker'` | BUILD SUCCESSFUL |
| 전체 끝-끝 회귀(정적 → 기동 → 기기 시뮬레이터 한 턴) | `sg docker -c 'bash scripts/verify.sh --mode compose'` | 단계 A · B · C와 사후 단언 P1 · P2 · P3 · T 통과 |
| 인프라 점검 | `sg docker -c 'bash infra/bin/infra-validate'`, `sg docker -c 'bash infra/ci/test-infra-validate.sh'`, `bash infra/ci/test-host-tools.sh` | 모두 통과 · 어긋남 0 |
| 이미지 점검 ④(demo 더미 기동) | `sg docker -c 'bash infra/ci/image-checks.sh'` | 모든 항목 통과(③ trivy는 이 PC에 없어 건너뜀) |
| 비밀 스캔(문서 · 기록 포함) | `bash scripts/verify/a-secrets.sh` | 걸림 0 |

`verify.sh` 보고는 `build/verify/report.txt`에 남고 기본 기한은 5400초다.

## 4. 빌드 검증 단계

1. `:realtime-activity:check`가 `verifyJevLiveIsolation`을 포함해 통과해야 한다.
2. 이번 변경은 마이그레이션을 만들지 않는다(Flyway 변경 없음). 정책 리소스 `policy-v2.json`은 새 파일이고 `policy-v1.json`은 남기되 적재하지 않는다.
3. 계약 대조 시험(`ConfigKeyContractTest` · `TimeLiteralScanTest` · `ContractFilesTest` · `U8ContractSchemasTest`)이 통과해야 한다.
4. 비밀 스캔(A4)이 코드 · 시험 · `contracts/` · `docs/` · `infra/`뿐 아니라 `aidlc/` 기록 문서에도 걸림 0이어야 한다.

## 5. 문제 해결

| 증상 | 원인 | 조치 |
|---|---|---|
| `permission denied` (docker) | 셸이 docker 그룹을 아직 못 받음 | 명령을 `sg docker -c '…'`로 감싼다 |
| `A4.patterns` 걸림 | 시험 · 문서에 비밀 스캔 패턴과 겹치는 모양의 리터럴 | 시험은 문자열을 조각으로 조립하고, 문서는 설명으로 풀어 쓴다. 스캐너 패턴은 바꾸지 않는다 |
| `U8RealtimeStartupChecksTest`가 단독 실행에서만 실패 | Mockito 자가 부착 경고가 stderr에 찍혀 '비어 있음' 단언이 실패하는 순서 의존(이번 변경과 무관, 개발자 보고) | 전체 `check`에서는 통과한다. 단독 실행 대신 모듈 전체로 확인하고, 재발하면 별도 정리 후보로 기록한다 |
| `DeviceGatewayU3IT`가 OUTPUT_BEGIN 대기 초과 | 앞 기록에서 본 일시 실패(부하로 보임, 원인 미확정) | 단독 재실행 후 통과하면 기록만 남긴다 |
| demo에서 realtime이 `MUJOONG_STARTUP_REJECTED`로 종료 | 제브 키 · 주소 · 모델이 없거나 문턱이 범위 밖(의도된 거부) | `infra/RUNBOOK.md` 단계 4 순서대로 R-F15 확인 기록 → Parameter Store 입력 → 배포 |
