# U15 빌드 지침

## 환경과 자료

WSL Ubuntu, JDK 21, Node 22, Docker와 Compose 2.24.4 이상, GNU date, 기존 Gradle wrapper를 쓴다. Windows 마운트의 클래스 경로 문제를 피하려고 동일 커밋 3cee1bbfe4a4를 Linux 파일 시스템 /home/pdypd/mujungi-u15-verify에 체크아웃했다. 테스트는 가짜 공급자만 사용하며 실제 AI 키는 필요하지 않다. 검증은 mujoong-verify의 전용 볼륨·포트를 사용한다. 수동 스택을 건드리지 않는다.

## 빌드 및 검증 명령

저장소 루트에서 Node 22를 PATH에 둔 뒤 아래 전체 명령을 한 번 실행한다. 이 명령이 백엔드 의존성/clean check/bootJar, frontend npm ci/eslint/vitest/next build, tools npm ci/vitest, 이미지 빌드와 통합을 모두 수행한다. 반복 실행은 앞선 실패 보완 후만 했다.

```bash
MUJOONG_VERIFY_EDGE_PREFIX=172.30.210 MUJOONG_VERIFY_BACKEND_PREFIX=172.30.220 bash scripts/verify.sh --mode compose
```

원본 코드 생성 지침의 단위 명령도 backend에서 별도로 실행한다: `bash gradlew --no-daemon :core-api:test --tests 'kr.mujoong.core.knowledge.U15*' -Pmujoong.test.db=docker`. 전체 실행의 범위 측정은 이 단위 실행 전에 `bash gradlew --no-daemon :core-api:jacocoTestCoverageVerification :core-api:jacocoTestReport -x :core-api:test -PcoverageScope=u15`로 얻었다. test를 제외한 것은 직전 전체 실행의 실제 측정 자료를 읽기 위함이며 시험을 생략한 전체 성공 주장이 아니다.

## 결과와 문제 해결

최종 전체 실행 종료 코드 0, 519초. 재현 가능한 네트워크 대역 변경 방법은 infra/RUNBOOK.md에 있다. 앞선 B1 비밀 이름 검사 누락과 Docker 대역 충돌은 검증 설정에서 수정했으며 품질 하한·아키텍처/권한 단언은 유지했다. 원본 report와 로그는 Linux 체크아웃 build/verify/에 남는다. 실제 키나 환경 파일 내용을 보고서에 복사하지 않는다.
