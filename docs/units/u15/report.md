# U15 검증 기록

## 검토 보완 뒤 최신 결과

R-01~R-06 수정 후 U15 100개와 기존 Luna·설정/시간·마이그레이션/스키마/기동·활동 등록 회귀를 합쳐 193개, 실패 0개다. U15 범위는 876/962줄(91.06%)로 80% 관문을 통과했다. 아래 초기 결과는 수정 전 비교 기록이다.

기존 선택 명령에 `--tests '*FlywayConfIT' --tests '*SchemaPrivilegeIT' --tests '*CoreSchemaIT' --tests '*SchemaVersionCheckTest' --tests '*CoreStartupSequenceIT'`를 추가했다. 버전 8과 core 표 42개를 정확히 비교하고 권한/격리 단언은 유지했다. 실패 기록 삭제의 읽기·AI 입력·재구성 차단, 다른 요청의 예약 손상 방지, 검증 코드 성공/거절 증거, 버전 갱신 전후 원자 맥락 DTO의 4개 회귀를 추가했다.

공식 WSL bridge의 `engine sensor-required-sections`와 `engine sensor-traceability`는 모두 pass=true, finding 0이다. 코드 요약 H2 3개와 상위 FR/BR/NFR 및 원 요구사항 ID 연결을 확인했다. NFR4 전체 검증과 NFR6 최종 커밋 증거는 Deferred로 남겨 실제 완료를 앞서 주장하지 않는다.

이후 R-01 시험에 실제 모델 호출 횟수 단언을 추가해 U15ServiceIT만 재실행했고 26초 BUILD SUCCESSFUL이었다. 이 마지막 단독 실행은 /tmp의 XML/JaCoCo를 덮어쓰므로 위 91.06%는 직전 193개 통합 선택 실행의 관문 결과다. 최종 전체 Build and Test에서 다시 측정한다.

## 초기 구현 검증

구현 단계 검증: WSL Ubuntu, JDK 21, Docker 격리 Testcontainers PostgreSQL/Neo4j 5.26.31, 가짜 Luna HTTP 공급자와 메모리 그래프를 사용했다. 실 AI 키와 실제 아동 자료는 사용하지 않았다.

- U15 96개와 CoreLunaClient/설정 계약/시간 검사/ActivityStartEndIT를 합쳐 134개, 실패 0개.
- U15 범위 JaCoCo 줄 854/939 = 90.95%, 80% 관문 통과. 범위에는 신규 운영 소스와 수정한 공통 운영 소스를 모두 넣었다.
- infra/ci/test-host-tools.sh 37개, 실패 0개. 기존 NEO4J_AUTH 분배와 비밀 이름 비교를 함께 갱신했다.
- 원본 ZIP 경계, 실제 Neo4j commit/rollback 경쟁·종료 확인·재접속, DB 실패 뒤 그래프 유령 노드 회수, 동시 후보 중복 재사용, 동일 후보 재전달, 삭제 중 늦은 응답, 만료 예약 회수, 전체 읽기 시한 이후 쓰기 없음, 키 없는 기동을 검증했다.

실행 명령(backend에서):
```
bash gradlew --init-script ../../u15-wsl-build.init.gradle :core-api:test --tests 'kr.mujoong.core.knowledge.U15*' --tests '*CoreLunaClientTest' --tests '*ConfigKeyContractTest' --tests '*TimeLiteralScanTest' --tests '*ActivityStartEndIT' -Pmujoong.test.db=docker
bash gradlew --init-script ../../u15-wsl-build.init.gradle :core-api:jacocoTestCoverageVerification :core-api:jacocoTestReport -x :core-api:test -PcoverageScope=u15
```
두 번째 명령은 직전 시험 실행 자료를 사용했다. init-script는 Windows 마운트 대신 기존 WSL 임시 build 위치를 쓰는 로컬 파일이며 CI에는 넣지 않는다. 보고서는 /tmp/mujungi-u15-verification-build/core-api/build 아래에 생성된다.

전체 회귀·ArchUnit 전체·모듈 전체 80%·scripts/verify.sh·원격 CI·push는 부모 Build and Test 단계에서 최종 소스로 수행 후 이 문서에 결과를 추가한다. 현재 기록은 해당 완료를 주장하지 않는다. 대역 성공은 실모델 개인정보 탐지 품질이나 실제 기기 성공 증거가 아니다.
