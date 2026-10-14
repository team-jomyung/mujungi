# U6 단위 시험 지시 — 결과와 리포트

> Code Generation(3.5) · 작업 기록 `261014-u6-result-report`. 시험 방식은 test-after(층마다 구현한 뒤 시험), 전략은 Standard(구성요소마다 5 ~ 8개 + 경계 통합 시험)다. 모든 명령은 저장소 루트 기준이고 U6 범위로 좁힌 것이다.

## 1. 시험 프레임워크와 실행 명령

- **백엔드 프레임워크**
  - JUnit 5 · AssertJ(기존)
  - 통합 시험은 `test-support`의 `MujoongPostgres`(Testcontainers 또는 로컬 PG, 기존 방식)
  - 태그 필터 `-PincludeTags`, 커버리지 범위 `-PcoverageScope`(build-logic 규약)
- **U6 시험 태그**: 새 시험 클래스는 모두 `@Tag("u6")`를 단다. DB를 쓰는 시험은 기존처럼 `db` 태그도 붙인다.
- **U6 범위 시험 + 커버리지(백엔드)**
  ```
  cd backend && ./gradlew --no-daemon :contract-model:test :core-api:test :core-api:jacocoTestCoverageVerification -PincludeTags=u6 -PcoverageScope=u6
  ```
- **한 클래스만 돌릴 때(개발 중)**
  ```
  cd backend && ./gradlew --no-daemon :core-api:test -PincludeTags=u6 --tests 'kr.mujoong.core.result.JobRunnerTest'
  ```
- **60초 시험만(NFR1, 시험 A ~ D)**
  ```
  cd backend && ./gradlew --no-daemon :core-api:test -PincludeTags=u6 --tests 'kr.mujoong.core.report.ReportHandlerIT'
  ```
- **프런트 프레임워크**: Vitest 4 · jsdom · Testing Library(기존 `frontend/vitest.config.ts`, 준비 파일 `tests/u1/setup.ts`).
- **U6 범위 시험 + 커버리지(프런트)**
  ```
  cd frontend && MUJOONG_COVERAGE_SCOPE=u6 npx vitest run tests/u6 --coverage
  ```
- **프런트 한 파일**
  ```
  cd frontend && npx vitest run tests/u6/parent/record-27.test.tsx
  ```
- **U6이 고친 기존 단언 두 곳(17 「오늘 기록 보기」)**
  ```
  cd frontend && npx vitest run tests/u1/parent/done-17.test.tsx tests/u4a/parent/screens-u4a.test.tsx
  ```
- **Step 3 실행기 확인(아직 u6 시험이 없을 때)**
  ```
  cd backend && ./gradlew --no-daemon :core-api:test -PincludeTags=u5a
  cd frontend && npx vitest run tests/u5a
  ```
- **준비**: JDK 21, Node 22. 통합 시험용 Docker 또는 로컬 PostgreSQL(기존 `-Pmujoong.test.db` 설정). 그 밖의 수동 준비는 없다. 실제 텍스트 AI 공급자 · 네트워크는 쓰지 않는다.

## 2. 커버리지 목표

- `backend/coverage-scope/u6.txt`에 적은 운영 소스 줄 커버리지 80% 이상(`jacocoTestCoverageVerification` 실패 = 단계 미완료).
  - 범위: U6이 새로 만든 `kr.mujoong.core.result` · `kr.mujoong.core.report` 전부, U6이 바꾼 U4a · U5a 파일(RecordQueryService · ActivityRecordRepository · ActivityContextRepository · ScenarioLookup · BehaviorDefinitionLookup · CandidateRegistry · SupportLibrary 읽기 · DeletionParticipant), `ErrorCode`.
- `frontend/coverage-scope/u6.json`의 운영 TS 줄 커버리지 80% 이상(vitest thresholds lines 80).
  - 범위: `Done17.tsx`, 새 화면 4개와 라우트 page, `parent/api/results.ts` · `reports.ts`, 새 훅 · 구성요소, `shared/copy/result.ts`, `navigation.ts`, `shared/errors/codeToCopy.ts`.
- 모듈 전체 줄 커버리지 80%(CI의 범위 없는 check)를 유지한다.
- 어떤 임계값도 낮추지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.

## 3. 대역 · 모의 객체 사용

- **텍스트 AI**: 시험 소스에만 `FakeResultTextProvider`를 둔다.
  - 작업별 고정 출력(JSON) · 호출당 지연 · n번째 호출 실패 · 스키마 어긋난 출력 · 금지 표현 · 인용 불일치를 주입한다.
  - 받은 요청을 기록해 아이 이름 · id · 원음 · 08 원문(구조화 외)이 없는지 단언한다(NFR4).
  - 운영 소스에는 UNAVAILABLE 기본 빈만 둔다.
- **시간**: 주입 `Clock`(가변 시험 시계)과 줄인 시간 값으로 시험한다(`application-test.yml` 기본 + 시험 클래스 덮어쓰기). 60초 시험 A만 `report.ready-target` 60초를 그대로 쓰고 호출당 2초 지연을 준다. sleep으로 기다리지 않고 상태를 폴링해 기다린다(상한 있음).
- **실행기 단위 시험**: 즉시 실행기(같은 스레드)와 가짜 Clock으로 선점 · 재시도 · 시한을 결정적으로 돌린다.
- **연습 대역**: `record.practice`는 계약 6절 예시 값으로 만든 빌더만 쓴다. U5b 내부를 흉내 내지 않는다.
- **U5a 후보 등록**: 통합 시험은 실제 CandidateRegistry를 쓴다. 등록 실패 재현은 afterCommit 후크에 실패를 주입하는 시험 전용 빈으로 한다.
- **프런트**: `fetch`를 기존 방식(`jsonResponse` 도우미 · 경로별 응답)으로 대역하고 vitest 가짜 타이머로 2초 폴링 · 60초 실패를 진행한다. `navigator.share` · `URL.createObjectURL`은 시험마다 정의 · 제거한다.
- **로그 검사**: 기존 `LogCapture`로 전사 원문 · AI 출력 본문 · 아이 이름이 로그에 없는지 본다.

## 4. 시험 데이터 관리

- 시험마다 새 보호자 · 아이 · 세션 id(UUID)를 만든다. 공유 가변 고정 데이터는 쓰지 않는다.
- 활동 기록은 빌더(`ActivityRecordFixture`)로 core 표에 직접 넣고, 필요하면 ActivityRecorded를 발행한다(롤백 시험은 트랜잭션을 되돌린다).
- 활동 사본(core_activity)의 상태 · failure_code 반영 순서를 시험에서 바꿔 UNDECIDED 경로를 재현한다.
- 리포트 20건 시험은 자유대화 10 · 연습 10 활동과 DONE 결과를 빌더로 만든다.
- 통합 시험 DB는 기존 방식대로 시험마다 정리하거나 격리 스키마를 쓴다.
- PDF 시험은 PDFBox로 텍스트를 추출해 비교하고, `java.io.tmpdir` 목록을 렌더링 전후로 비교한다.

## 5. 층별 시험 목록과 최소 수

| 층 | 시험 클래스 | 최소 수 | 경계값 |
|---|---|---|---|
| 데이터 모델 | U6SchemaIT | 7 | 부분 유일 · 장면 CHECK |
| 저장소 | ResultJobRepositoryIT · SessionResultStateRepositoryIT · ReportRepositoryIT · U5aExtensionU6IT | 8 · 5 · 5 · 6 | 동시 claim · 동시 삽입 |
| 실행기 | JobRunnerTest · JobRunnerIT | 8 · 6 | 1회 → 5초, 2회 → 15초, 3회 → FAILED |
| 접수 | ResultEligibilityTest · ResultIntakeIT · ResultRetryIT | 8 · 7 · 5 | 상태 6종 × 발화 0 · 1, UNDECIDED 1시간 |
| 검사 · AI 경계 | QuoteMatcherTest · NumberGuardTest · ForbiddenDictionaryTest · OutputCheckerTest · ResultTextGatewayTest | 5 · 5 · 6 · 6 · 6 | 공백만 다른 인용, 시각 · 장면 번호 |
| 처리기 | ResultLevelCalculatorTest · SceneChipMapperTest · NotDoneReasonMapperTest · TipMatcherTest · SessionResultHandlerIT · ResultEvidenceAdapterIT | 8 · 6 · 6 · 6 · 8 · 4 | 발화 2 · 3, 어절 2 · 3 |
| 삭제 | ResultDeletionIT | 5 | — |
| 결과 API | ResultApiIT · RecordFailedExposureU6IT | 8 · 5 | — |
| 리포트 | QuoteCandidatePickerTest · ReportAssemblerTest · ReportHandlerIT · ReportServiceIT | 5 · 8 · 8 · 7 | 인용 30 · 31, 활동당 5 · 6, 9항목 1 · 2회, 60초 A ~ D |
| PDF · 리포트 API | ReportPdfTest · ReportApiIT | 6 · 7 | 임시 파일 0 |
| 프런트 | done-17-result · records-26 · record-27 · report-28 · report-30 · hooks-u6 | 6 · 7 · 8 · 8 · 8 · 5 | 2초 폴링, 60초 |

각 시험 클래스는 정상 경로와 오류 · 경계 경로를 두 개 이상 포함한다. 항상 통과하는 단언은 두지 않는다.

## Sources

- 계획: `construction/code-generation/code-generation-plan.md`
- 설계: `construction/functional-design/functional-spec.md` 8절 · 10.3, `frontend-components.md` 9절
- 기존 방식: `frontend/vitest.config.ts`, `backend/coverage-scope/u5a.txt`, `frontend/coverage-scope/u5a.json`, U4b `unit-test-instructions.md`
