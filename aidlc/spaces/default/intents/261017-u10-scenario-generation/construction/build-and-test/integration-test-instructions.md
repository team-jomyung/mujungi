# U10 통합시험 지침

## Setup

Standard 전략, JUnit 5·AssertJ·Mockito·Docker PostgreSQL을 사용한다. 기존 시험 설정과 u10 태그를 유지한다. 별도 생성/검토 가짜 공급자, 합성 계정·아이·목표·동의·노출 이력만 사용한다.

## Commands

backend 디렉터리에서 Code Generation 지침의 서로 다른 명령을 각각 한 번 실행한다. 이후 전체 verify.sh가 전체 회귀를 실행한다. 아래 한정 실행을 전체 모듈 커버리지 근거로 쓰지 않는다.

```sh
./gradlew :core-api:test --tests kr.mujoong.core.practice.ScenarioServiceTest -Pmujoong.test.db=docker --no-parallel --max-workers=2
./gradlew :contract-model:test :core-api:test -PincludeTags=u10 -Pmujoong.test.db=docker --no-parallel --max-workers=2
```

## Boundaries

- U14 고정 목표·후보와 기존 행동 목표의 시작, 같은 키 복구, 다른 아이·담기 해제·동의 철회·삭제 경합을 대조한다.
- U15 실제 BASE의 계획을 생성·검토에 전달하는 입력 의존 대역, 계획 없음·장애·합계 예산·변경 BASE·BASE-only 별도 검토를 대조한다.
- V9→V10 기존 자료와 구버전 쓰기, nullable 5컬럼, 새 대본·kgVersion의 DB 재조회와 C10/C8 첫 전달을 확인한다.
- 생성/검토 분리, APPROVE+서버 거절, 기술1·내용1·최대6호출, 마감·취소, 이름·연락처·내부 ID 최소화를 확인한다.
- RT 포함 전체 회귀와 기존 자유 대화 끝-끝은 전체 verify.sh에서 확인한다. U12 실제 상태 기계·실기기는 범위 밖이다.

## 현재 재실행의 명령 구분

7125ee0 통합판에서는 Code Generation 시험 지침의 서로 다른 네 명령을 각각 한 번 실행한다: ScenarioServiceTest, u10 태그, wire 단일 파일(9건), FlywayConfIT 단일 클래스. wire와 Flyway의 한정 명령은 code-generation/unit-test-instructions.md의 정확한 명령을 사용한다. 전체 verify.sh는 별도 한 번이며 A3가 전체 tools 회귀를 포함하므로 같은 전체 tools 실행을 불필요하게 다시 반복하지 않는다. 보안 지침의 정적 infra-validate 및 음성 대조도 각각 한 번 실행한다. 전체 backend XML/JaCoCo를 먼저 보존한 뒤 한정 명령을 수행한다.

## Evidence

JUnit XML의 시험수·실패·오류·skip과 실제 테스트 이름을 기록한다. 범위 파일의 모든 운영 경로를 전체 Core JaCoCo XML에 대응시켜 빈 목록·없는 파일을 실패시키고 줄 80% 이상을 집계한다. 기존 LOCAL PostgreSQL skip은 Docker 경로와 구분한다. R-06의 승인된 잔여 위험을 기존 시험 통과로 해소됐다고 표시하지 않는다.
