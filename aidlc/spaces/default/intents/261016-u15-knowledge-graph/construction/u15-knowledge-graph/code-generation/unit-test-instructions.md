# U15 시험 지침

## 실행 환경

WSL Ubuntu의 JDK 21, Docker, 기존 Gradle wrapper, JUnit 5·Mockito·AssertJ·Testcontainers를 사용한다. 기존 U9 스키마 시험 통과로 실행기를 확인했다. U15 시험은 kr.mujoong.core.knowledge 아래 U15*Test/U15*IT 이름과 @Tag("u15")를 사용한다. 아래 명령은 backend 디렉터리에서 실행한다.

## 단위 범위 명령

```bash
bash gradlew :core-api:test --tests 'kr.mujoong.core.knowledge.U15*' -Pmujoong.test.db=docker
```

Windows 마운트의 ArchUnit 경로 문제는 저장소 밖의 기존 WSL init-script로 build 디렉터리만 옮겨 같은 필터로 실행할 수 있다. CI에는 이 로컬 우회 파일을 넣지 않는다. 시험이 없으면 성공 처리하지 않는다. 단위/통합 양쪽을 위 필터에 포함한다.

## 시험 자료와 품질

각 시험 가능한 층을 구현한 직후 해당 시험을 작성하고 실행한다. 컴포넌트별 정상 경로 및 오류/경계 포함 5~8개를 기본으로 한다. 고정 UUID·주입 시계·합성 한국어를 쓰며 실제 전사나 키를 저장하지 않는다. AI는 가짜 공급자, DB/그래프는 격리된 Testcontainers를 사용한다. 원본 ZIP은 읽기 전용이다.

U15 줄 80%와 기존 모듈 줄 80%를 충족하고 backend/coverage-scope/u15.txt로 실제 소스 범위를 기록한다. 비어 있거나 없는 파일은 실패로 처리한다. 원격 트랜잭션 종료 여부는 실제 Neo4j 시험으로 검증하고 대역 성공을 실제 AI 품질 증거로 표시하지 않는다.

## 핵심 경계

초기 14개·판 충돌·동의/아동 격리·읽기 2초·노출 2/3·한도 1/2/3·겹침 4/5·Unicode 60/61·중복 재전달·수정 슬롯 공유·삭제 경합·그래프 커밋 뒤 DB 실패·원격 종료 불명·재접속·키 없는 기동을 포함한다. 전체 프로젝트 회귀와 끝-끝 실행은 별도 Build and Test에서 수행한다.

