# U14 빌드 지시

## 환경과 소스 고정

운영 소스 기준은 d5d4d4f다. Ubuntu ext4의 /home/pdypd/mujungi-u14-verify에 원본 로컬 Git 저장소에서 해당 커밋을 가져와 detached HEAD로 확인했다. 이 복사본의 origin은 GitHub가 아니므로 여기서 push하지 않는다. 원본 저장소의 미커밋 작업 기록은 유지한다. JDK21, Node22, npm lock, Docker/Compose, GNU date, shellcheck를 사용한다. mujoong-manual은 변경하지 않는다.

## 전체 실행

복사본 루트에서 아래 한 명령을 실행한다. Node 경로를 앞에 두며 새 비밀 값은 스크립트가 일회성으로 만들고 지운다. SKIP 옵션이나 실제 공급자 키를 사용하지 않는다.

```bash
env PATH=/home/pdypd/.nvm/versions/node/v22.23.3/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin GRADLE_OPTS='-Dorg.gradle.parallel=false -Dorg.gradle.workers.max=2' MUJOONG_VERIFY_EDGE_PREFIX=172.30.210 MUJOONG_VERIFY_BACKEND_PREFIX=172.30.220 bash scripts/verify.sh --mode compose
```

A1은 backend clean check와 verifyBootJarHasNoTestClasses, A2는 frontend npm ci/eslint/전체 Vitest coverage/Next build, A3은 tools 전체 시험, A4는 비밀 검사를 실행한다. B/C/P/T는 integration-test-instructions.md에 연결한다. stdout은 단계 요약만 읽고 민감 값이 있을 수 있는 원본 로그는 무차별 출력하지 않는다. 결과는 build/verify/report.txt와 logs/에 남는다. 최초 실행의 기존 두 연동 시험 오류 후 GRADLE_OPTS로 모듈 동시 실행만 제한했다. 시험 수·단언·시간 제한·커버리지 하한·검증 단계는 동일하다.

## 합격과 문제 해결

모든 단계 rc0 및 RESULT PASS, 모듈별 줄80%를 요구한다. 실패는 환경/제품/시험 fixture로 구분하고 하한이나 단언을 줄이지 않는다. Windows 파일 접근이 느린 문제는 소스를 변경하지 않는 ext4 복사본으로 해결한다. 전체 시험 결과 XML은 backend 각 모듈 build/test-results/test/에 있다. 전체 결과를 수집한 다음 U14 필터 시험을 실행하여 전체 XML과 범위 XML을 구분해 기록한다.
