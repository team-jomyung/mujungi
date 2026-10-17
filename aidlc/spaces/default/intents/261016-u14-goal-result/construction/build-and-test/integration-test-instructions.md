# U14 통합 시험 지시

## 기존 지시와 실행 순서

code-generation/unit-test-instructions.md의 실제 실행 명령을 사용한다. 최초 runner 명령은 변경 전 준비 증거이며, 단일 클래스와 frontend 파일 명령은 전체 묶음의 예시다. 본 단계에서는 실제 묶음 명령을 한 번씩 실행하고 중복 개수를 합산하지 않는다. 전체 compose 실행과 XML 집계 뒤 복사본 backend에서 실행한다.

```bash
./gradlew :core-api:test -PincludeTags=u14 -Pmujoong.test.db=docker
./gradlew :core-api:test :core-api:jacocoTestReport :core-api:jacocoTestCoverageVerification -PincludeTags=u14 -PcoverageScope=u14 -Pmujoong.test.db=docker
```

두 번째 명령은 첫 번째와 동일 입력 시험을 재사용할 수 있다. 이를 두 배의 사례 수로 보고하지 않는다. frontend에서는 다음 U14 묶음을 실행한다(개별 results.test.ts 예시와 중복 실행하지 않는다).

```bash
npx vitest run tests/u14/
```

## 끝까지 이어지는 실행

build-instructions.md의 전체 verify.sh가 A 이후 B의 이미지 빌드·마이그레이션·seed 멱등·기동/health/device/WSS를 확인하고 C의 기기 시뮬레이터 한 턴을 실행한다. P1은 스키마 격리, P2는 로그 위생, P3는 비밀 값 유출 부재, T는 작업 트리 불변을 확인한다. 별도 mujoong-verify 프로젝트와 격리 네트워크를 사용한다.

## 경계와 자료

실제 PostgreSQL 컨테이너로 V8→V9, FK·버전·선택 경합·삭제·동의 철회·빈 후보 재생과 오래된 핸들을 검증한다. U15 graph와 Luna는 가짜 포트를 사용한다. v2는 계약 기반 합성 기록이며 U12 실제 기록 생성기의 인수 시험이 아니다. SavedGoalReader 포트 제공까지 U14이고 U10 실행기는 범위 밖이다. raw AFTER_REASK/27 표시/혼합 리포트와 4회 재시도 회귀를 포함한다.
