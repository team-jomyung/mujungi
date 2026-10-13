# Build and Test 질문 — U4b 활동 세션 Realtime

> 시험 방식(test-after · Standard), 품질 목표(u4b 범위 줄 커버리지 80% · 모듈 80% · 기존 시험 통과), 실행 명령은 Code Generation 계획 · 단위 시험 지시에서 이미 정했다. 계획 7절은 "리눅스 전용 검사는 이 PC(WSL)에서 돌 수 있으면 Build and Test에서 돌리고, 안 되면 PR의 CI로 확인한다"고 정했다. 아래는 이 PC 환경 때문에 답이 필요한 것만 모았다.

## Q1. 끝-끝 검증 명령(`bash scripts/verify.sh`)을 돌리는 방법

이 PC의 상태는 다음과 같다.
- Docker Desktop은 Windows에 설치돼 있지만 이 WSL 배포판에는 연동이 꺼져 있어 `docker`를 쓸 수 없다.
- PostgreSQL 16은 시스템에 없다. Code Generation 때 내려받아 임시 폴더에 푼 바이너리로 DB 시험을 돌렸다.

verify.sh에는 Docker 없이 도는 host 모드가 있다. host 모드에서 확인하는 것과 못 하는 것은 다음과 같다.
- 확인함: 시드 001 → 002 → 003 적용, 끝-끝 한 턴 C1 ~ C12(C8 참여 응답 포함), 스키마 격리 · 로그 위생 · 비밀 값 검사
- 확인 못 함(NOT_VERIFIED(host)): 이미지 6종 빌드 · 라벨 · 비루트, Compose 병합 설정, Nginx 렌더 설정, 망 격리, 이미지 기동 거부

A. 먼저 host 모드로 이 PC에서 돌린다. 확인 못 한 compose 전용 항목은 PR의 CI(images · skeleton 작업)로 확인하는 것으로 결과에 적는다.
B. 사용자가 Docker Desktop의 WSL 연동(Settings → Resources → WSL integration)을 켠 뒤 compose 모드로 전체를 돌린다.
C. 이 PC에서는 verify.sh를 돌리지 않고 PR의 CI 결과로만 확인한다.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- 실행 명령: 단위 시험 지시의 명령을 그대로 한 번씩 돌린다. Docker가 없으므로 DB 시험은 임시 폴더의 PostgreSQL 16으로 돌린다(`-Pmujoong.test.db=local`).
  - 빌드(`clean assemble`)
  - u4b 범위 시험 + 커버리지
  - U1 회귀(네 모듈) · U3 회귀
  - core-api `FlywayConfIT`
  - 흔들리던 시험 `RealtimeInternalApiIT` 5회
  - 모듈 전체 `check`
- 검토 지적 R-04: `RealtimeMetricsScrapeIT`을 따로 다시 돌려 흔들림을 확인한다.
- 끝-끝 검증: `bash scripts/verify.sh --mode host`로 이 PC에서 돌린다(Q1 A). compose 전용 항목(이미지 · Compose · Nginx · 망 격리 · 이미지 기동 거부)은 이 단계에서 확인할 수 없어 PR CI가 확인할 몫으로 적는다.
- 품질 목표는 낮추지 않는다. 확인하지 못한 목표는 표에 '미확인'으로 남기고, 그러면 실패 처리 절차(고칠 방법과 영향을 붙여 여쭙기)를 따른다.
- 시험 전략이 Standard라 지시 파일은 build-instructions · integration-test-instructions만 내용을 채운다. performance · security 지시 파일은 해당 NFR(NFR4 개인정보 · 로그 위생)을 다루는 최소 내용으로 쓴다.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
