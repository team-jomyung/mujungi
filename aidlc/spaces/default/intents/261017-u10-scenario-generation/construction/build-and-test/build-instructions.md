# U10 빌드 지침

## Sources

승인된 code-generation의 계획·시험 지침·코드 요약과 Testing Contract를 따른다. 기준 제품 커밋은 `1e4db60d449153dd9848f527e0d31d5f3590912c`다. 제품 소스는 이 단계에서 바꾸지 않는다. 사용자는 R-05(RT 검증 위험)와 R-06(별도 준비 READY 마감 누락)을 위험으로 수용하고 다음 검증으로 진행하도록 승인했다. 이 승인은 시험 결과를 합격으로 바꾸지 않는다.

## Prerequisites

WSL Ubuntu, JDK 21, Node 22, Docker/Compose, Git, GNU date, shellcheck를 사용한다. 새 검증 사본은 `/home/pdypd/mujungi-u10-final-verify`이며 Windows 정본의 해당 브랜치에서 로컬 clone한다. 이 사본의 origin은 로컬 저장소이므로 여기서 push하지 않는다. 실제 키·사용자 환경 파일은 복사하거나 출력하지 않는다.

`npm ci`는 전체 검증이 수행한다. Gradle은 기존 전역 의존 캐시와 wrapper를 사용한다. 설정·시드·비밀은 검증 도구가 합성 자료로 준비한다. `mujoong-manual`과 볼륨·설정은 접근하지 않는다.

## Commands

저장소 루트에서 아래 전체 명령을 한 번 실행한다. Node 22 경로를 PATH 앞에 두며 Gradle 동시 작업 수만 제한한다. 시험 시한·단언·커버리지·검증 단계는 바꾸지 않는다.

```sh
GRADLE_OPTS='-Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1' bash scripts/verify.sh --mode compose
```

전체 명령은 backend clean check와 운영 JAR 시험 클래스 검사, frontend 설치·lint·전체 커버리지·빌드, tools 전체 시험, 비밀 검사, 이미지 빌드, Compose 기동·마이그레이션·시드·프로필 검사, 기기 대역 한 턴 및 P1/P2/P3/T 단언을 수행한다. 생성 결과는 검증 사본의 `build/verify/report.txt`와 `build/verify/logs/`다. 모든 필수 단계와 최종 RESULT가 PASS이고 종료 코드가 0이어야 이 명령의 성공이다.

## Verification and troubleshooting

실행 전 Git HEAD·작업 트리와 도구 판을 확인한다. 새 사본은 LF와 원래 실행 비트의 커밋 내용을 사용한다. GNU date의 밀리초 출력과 Node 22를 먼저 확인한다. 별도 검증 네트워크는 기존 수동 스택과 겹치지 않는 CIDR로 지정한다.

전제 실패는 코드 결함으로 오인하지 않고 원인을 확인한다. 실행 실패를 반복해 녹색만 고르지 않는다. RT의 기존 간헐 실패가 재현되면 원 단언·10초·운영 코드를 유지하고 해당 XML/로그·파일 상태를 기록한다. 검증 중 소스가 바뀌면 그 실행을 최종 근거로 쓰지 않는다.
