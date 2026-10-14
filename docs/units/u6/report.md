# U6 구현 보고

2026-10-14, `unit/u6-results-report`. 기준 커밋은 U4b 병합 `7a22b67`이다.

## 구현

V5 결과·리포트 표, 비동기 DB 작업 실행기, 정리 접수·재시도·재시작 복구, 구조화·재판정·칩·문장·팁·후보 처리, 9개 결과/리포트 API, 계정 삭제 참가자, 메모리 PDF, 보호자 17·26·27·28·30을 구현했다. 계약·시간 키·금지 사전·프롬프트와 U5a 공개 읽기 포트를 연결했다. 자세한 구성은 design.md와 코드 생성 code-summary.md, 요구사항 추적은 traceability.json을 따른다.

최초 컨테이너 PDF 검증에서 PDFBox의 OS 글꼴 검색이 `.pdfbox.cache`를 쓰는 문제를 발견했다. EmbeddedFontMapper가 내장 Regular/Bold만 메모리로 제공하도록 수정했고 새 JVM 빈 tmp/home 테스트를 추가했다.

## 검증 기록

- 변경 contract-model 범위(ErrorCode): 줄 100%(59/59). 전체 계약 시험 298개로 확인했고 `verifyBootJarHasNoTestClasses`는 운영 jar의 시험 항목 0건을 확인했다.

- 최종 백엔드 `./gradlew --no-daemon check`: **BUILD SUCCESSFUL(3분 50초)**. core-api 1,040, contract-model 298, realtime-activity 1,020 모두 통과; test-support 27 중 24 통과·기존 3 skip. 합계 2,385개 중 2,382 통과, 실패 0이다. 전체 줄은 core 96.21%(6,879/7,150), contract 98.47%(1,485/1,508), realtime 92.84%(8,387/9,034)로 모든 운영 모듈 80% 하한을 통과했다.

- 동일 HEAD 기준선 이미지를 깨끗한 임시 worktree에서 재빌드했다. 기준선 545,066,875바이트 → U6 564,883,067바이트, 증가 19,816,192바이트(18.90MiB). Dockerfile·기반 다이제스트·한도는 동일하다.
- 프런트 최종 전체: `npx vitest run --coverage --maxWorkers=2`, 64개 파일·864개 모두 통과(79.84초). 전체 줄 98.55%(3,145/3,191), statements 97.34%, branches 93.57%, functions 96.56%. `npm run lint`, `npm run typecheck`도 성공했다.

- 기준선 backend check: 2,169개 중 2,166개 통과, 3개 건너뜀. 기존 baseline-backend.log와 XML에서 확인했다.
- 프런트 U6: 8개 파일 61개 통과, 범위 줄 83.21%(228/274), statements 86.03%. fake timer로 정확한 2초 주기와 60초 마감을 검증했다.
- 백엔드 U6 최종 범위 실행: **212개 통과**, 줄 **86.17%(1,171/1,359)**, BUILD SUCCESSFUL(1분 22초). 20활동 첫 호출 실패·재시도 A는 11,213ms, 세션 작업이 밀린 경우 REPORT 전용 슬롯 D는 4,123ms로 READY가 됐다. B/C 절대 마감과 마감 이후 재시도 차단도 통과했다. PDF 단위 렌더는 270ms·22,607바이트였다.
- PDF 수정 표적: 새 JVM 1개, 폰트 매퍼 5개, 삭제 5개, 시간 스캐너 7개, Core schema 28개, schema privilege 9개 모두 통과. ErrorCode 표적도 통과했다.
- 최종 이미지 `mujoong-core-api:u6-verify`: 564,883,067바이트. 활동 20건·10항목·200인용 PDF는 1,709ms, 28,194바이트, 7페이지였다. `--network none --read-only --memory 1.6g --memory-swap 1.6g --tmpfs /tmp:size=64m`, heap 768m 조건에서 한글·항목 텍스트 검증과 새 임시 파일 0개를 통과했다.
- 서브셋 재생성: fonttools 4.66.1로 문서 명령을 실행한 결과 Regular SHA-256 `069c3f51e81ee015396350a950655e25f9279010c957a4fef5c8036a2a57d54e`, Bold `926a3d55f4238076243f8695104f6d195bacdfadb0eca9cf1aa466159000acac`가 체크인 파일과 일치했다.

첫 전체 실행은 변경 전 schema 기대값 실패와 동시 Gradle 결과 디렉터리 쓰기 충돌이 있어 최종 증빙에서 제외했다. 순차 재실행에서 모두 통과했다. 첫 frontend 전체 실행의 낡은 records redirect 단언은 실제 화면 진입으로 갱신했고, 다음 실행의 boundary 검사 60초 타임아웃은 worker 2로 재검증하여 통과했다. 타임아웃과 커버리지 하한은 바꾸지 않았다.

## 기존 회귀 시험의 필요한 적응

실행 환경은 Java 21.0.12.1, 저장소 Gradle wrapper 8.14.3이다. 다음 명령은 backend/ 또는 frontend/에서 실행했다.

```sh
./gradlew --no-daemon check
./gradlew --no-daemon :contract-model:test :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=u6 -PcoverageScope=u6
./gradlew --no-daemon :contract-model:test :contract-model:jacocoTestCoverageVerification :core-api:verifyBootJarHasNoTestClasses -PcoverageScope=u6
npx vitest run --coverage --maxWorkers=2
MUJOONG_COVERAGE_SCOPE=u6 npx vitest run tests/u6 --coverage --maxWorkers=2
npm run lint
npm run typecheck
```

기존 contract-model 오류 목록 시험은 u1 태그여서 마지막 별도 명령으로 변경된 ErrorCode의 U6 범위를 검사한다. 테스트 태그를 바꾸거나 빠진 측정을 성공으로 세지 않는다.

timing 기본값 기대 객체에 사용자 승인된 reportReadyTargetMs=60_000을 추가했다. 삭제된 records stub redirect 단언은 FR11의 실제 Records26 진입을 확인하도록 바꾸었다. U6 새 오류 2개, V5 표·열·인덱스·최고 버전과 낮은 버전 fixture를 반영했고, 시간 스캐너는 설정 Duration의 단위 변환 두 줄만 파일·조각·근거로 허용했다. 기존 검사나 80% 하한을 제거하지 않았다.

## 남은 인계

- U5b 합류 후 record.practice 반응·도움·scenarioId 사상표와 전체 흐름을 맞춘다.
- D 담당자가 U6-J AI 형식·프롬프트·금지 사전·생활 팁 시드를 검토한다.
- 자유대화 문장은 팀장이 확인한다.
- Android Chrome에서 PDF 파일명·실제 저장, Web Share 파일 공유, 새 탭 열기를 수동 확인한다.
- 승인된 Functional Design 1절·brief에 따라 실제 AI 공급자는 연결하지 않았다. 기본 PROVIDER_UNAVAILABLE로 AI가 필요한 결과는 총 3회 시도 뒤 FAILED이고 27에서 다시 시도할 수 있다. INSUFFICIENT는 AI 없이 DONE이다. READY 성능·내용 시험은 가짜 공급자 기준이며 운영 공급자 연결·품질·외부 지연 검증은 별도 작업이다.
