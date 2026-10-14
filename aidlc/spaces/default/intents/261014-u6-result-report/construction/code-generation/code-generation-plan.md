# U6 코드 생성 계획 — 결과와 리포트

> Code Generation(3.5) · 작업 기록 `261014-u6-result-report` · 유닛 없는 단일 반복(stage-level)
> 저장소 루트 `/home/jdmea/mujungi` · 브랜치 `unit/u6-results-report`(main `7a22b67`에서 땀)
>
> 입력
> - 승인된 기능 설계: `construction/functional-design/functional-spec.md` · `rules.md`(BR1.1 ~ BR14.2) · `entities.md` · `frontend-components.md` · `traceability.json`
> - 요구사항: `inception/requirements-analysis/requirements.md` FR1 ~ FR16 · NFR1 ~ NFR12
> - 지시: `docs/units/u6/brief.md`, 인수인계: `HANDOFF.md`, codekb: `aidlc/spaces/default/codekb/mujungi/`
>
> 이 범위는 스토리 단계를 건너뛰었다. 그래서 계획 단계를 스토리 대신 요구사항(FR/NFR)과 업무 규칙(BR), 원래 기록의 수용 기준(AC)에 연결한다.
>
> **세션을 넘겨 이어 받는 개발자에게**: 이 파일의 체크박스는 넘겨줄 때 비워져 전달된다. 체크 표시를 믿지 말고 `git log --oneline main..HEAD`와 저장소 상태로 끝난 단계를 먼저 확인한 뒤, 끝나지 않은 첫 단계부터 이어 간다.

## 1. 범위와 원칙

- **바꾸는 곳**
  - `contracts/`: parent-api(C5 9개 경로) · 응답 스키마 4개 · records-response · ai-io 6절 · timing · deviations
  - `backend/contract-model`: `ErrorCode`(JOB_NOT_FAILED · REPORT_NOT_READY)
  - `backend/core-api`: 새 패키지 `kr.mujoong.core.result` · `kr.mujoong.core.report`, core V5 마이그레이션, 설정, U5a 확장(더하기만), U4a 변경(functional-spec 5.4 표만), 삭제 참가자 70, 리소스(프롬프트 · 금지 사전 · HTML 템플릿 · 글꼴)
  - `frontend/`: 17 고침, 26 · 27 · 28 · 30 새 화면, 라우트 · API · 훅 · 구성요소 · 문구
  - `backend/coverage-scope/u6.txt` · `frontend/coverage-scope/u6.json`
  - `docs/units/u6/design.md` · `report.md`, `docs/units/README.md` 현황표
- **바꾸지 않는 곳**: realtime 스키마 · realtime-activity 코드 · `README.md` · `contracts/README.md` · 다른 작업 기록. U1 · U2 · U3 · U4a · U4b · U5a 시험 단언은 functional-spec 5.4 표의 두 줄(17 「오늘 기록 보기」) 말고는 바꾸지 않는다. 마이그레이션 버전 숫자를 단언하는 기존 시험이 V4를 기대하면 V5로 바꾸는 것만 허용하고 code-summary에 적는다.
- **기존 패턴을 따른다**
  - DB 선점 실행기는 `AccountDeletionScheduler` · `DeletionCommander` · Outbox 발송과 같은 `SmartLifecycle` + `FOR UPDATE SKIP LOCKED`(FD-Q1)
  - JDBC는 `NamedParameterJdbcTemplate` 저장소, 트랜잭션은 호출자가 연다
  - 시계는 주입 `Clock`, 시간 값은 설정 주입(`ConfigKeyContractTest` · `TimeLiteralScanTest`)
  - 공급자 기본 빈은 `@ConditionalOnMissingBean`으로 두고 시험에서 교체(U5a-J15)
  - 오류는 공통 봉투 `CoreApiException.of(ErrorCode.…)`, 로그에는 코드값만
  - 한국어 Javadoc에 설계 ID(BR · FR)를 적는다
- **품질 목표**: `u6` 범위 줄 커버리지 80% 이상(백엔드 · 프런트 각각), 모듈 전체 80% 유지. 어떤 임계값도 낮추지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.
- **설계 승인 때 감수한 지적 중 코드에서 막을 것**: R-12 — 판정을 미룬(UNDECIDED) 기록이 누락 점검 100건 몫을 계속 차지하지 않게, 기록 저장(recorded_at)에서 1시간이 지난 UNDECIDED는 NOT_ELIGIBLE 머리 행으로 굳히고 코드값 로그를 남긴다(Step 10, 시험 Step 11). 시간 값은 새 키 `mujoong.timing.result.undecided-limit: 1h`로 둔다.
- **커밋**: 단계 묶음마다 `feat(u6): …`(문서는 `docs(u6): …`). 커밋 전에 `git diff --cached --summary`로 mode change를 확인하고, 의도하지 않은 실행 권한 변경(예: 755 → 644)은 `git reset`으로 스테이지에서 뺀다(WSL).

## 2. 영향 범위 (Blast Radius)

| 파일 · 묶음 | 변경 | 의존하는 쪽 | 영향 |
|---|---|---|---|
| `db/migration/core/V5__u6_results_report.sql` | 새 표 10개 + `core_support_item.title` | Flyway · 스키마 시험 · 마이그레이션 CI 검사 | 중 |
| `config/CoreProperties` · `textai/TextAiProperties` · `application.yml` · `application-test.yml` · `contracts/timing.md` | `result.*` · `report.*` · `text-ai.result.*` · `limits.result-workers` 키 | ConfigKeyContractTest · 모든 core 시험 프로필 | 중 |
| `contract-model/.../ErrorCode` | 코드 2개 추가 | 오류 문구 매핑(프런트 codeToCopy) · 계약 시험 | 저 |
| `contracts/parent-api.yaml` · `schemas/*` | C5 · 응답 스키마 4개 · records FAILED | ContractFilesTest · 스키마 검증 시험 | 중 |
| `record/ActivityRecordRepository` · `RecordQueryService` · `RecordController` | 재시작 FAILED 노출(BR14.1) | 26 · 27 · U4a 시험 | 중 |
| `activity/ActivityContextRepository` | 공개 읽기 메서드 추가 | 구조화 입력 | 저 |
| `practice/*` · `knowledge/*`(U5a) | ScenarioLookup · BehaviorDefinitionLookup · viewForResult · approvedByBehaviorKeys · 근거 어댑터 교체 | U5a 시험(단언 · 하한 그대로) | 중 |
| `common/deletion/DeletionParticipant` | `ORDER_RESULT = 70` · 주석 | 계정 삭제 지휘 · 순서 시험 | 저 |
| 새 `result/*` · `report/*` | 실행기 · 처리기 · API · PDF | 신규 | 높음(새 비동기 경로) |
| `frontend/src/parent/screens/Done17.tsx` | 정리 상태 · 단추 | U1 · U4a 17 시험(두 단언 변경) | 중 |
| `frontend/src/app/parent/records/page.tsx` 외 새 라우트 · 화면 | 26 스텁 교체, 27 · 28 · 30 | 하단 탭 이동 · navigation 시험 | 중 |
| `frontend/src/parent/navigation.ts` | 경로 · 기록 탭 | U1 탭 시험 | 저 |

## 3. Testing Contract

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "org",
  "ordering": "implement each applicable testable layer, then write and run that layer's tests.",
  "scope": "refactor",
  "test_strategy": "standard",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    }
  ],
  "obligations": {
    "strategy": "standard",
    "strategy_volume": [
      "Five to eight tests per component.",
      "Unit tests plus integration tests for key boundaries.",
      "Add E2E, performance, or security tests when requirements demand them."
    ],
    "scope_floor": [
      "Keep the existing test suite green.",
      "This scope adds no extra new-test floor beyond the selected test strategy."
    ],
    "combination_rule": "Apply every selected-strategy obligation and every scope-floor obligation; neither replaces the other, and a targeted scope regression may add the narrowest necessary test type beyond the strategy default."
  },
  "plan_profile": {
    "methodology": "test-after",
    "runner_step": "Verify the existing test runner/configuration and record the exact unit-scoped command.",
    "runner_ready_before_first_test": true,
    "testable_layers": [
      "Data model / database behavior",
      "Repository / data access",
      "Business logic",
      "API / endpoint",
      "Frontend behavior"
    ],
    "steps": [
      "Project structure and production configuration skeleton.",
      "Verify the existing test runner/configuration and record the exact unit-scoped command.",
      "Data model / database behavior - implement.",
      "Data model / database behavior - write and run its tests after implementation.",
      "Repository / data access - implement.",
      "Repository / data access - write and run its tests after implementation.",
      "Business logic - implement.",
      "Business logic - write and run its tests after implementation.",
      "API / endpoint - implement.",
      "API / endpoint - write and run its tests after implementation.",
      "Frontend behavior - implement.",
      "Frontend behavior - write and run its tests after implementation.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:322f3cce51ad835b556974215be0ae0a5aba55c673c92ff6b1b327e7eb2426e8",
  "contract_sha256": "sha256:84a32ec0d9dd634774d2a290b523e6e0d5e6f77259469f3112500519cb132392"
}
```

순서: test-after. 층마다 구현한 뒤 그 층의 시험을 쓰고 돌린다. 업무 로직 층은 크기 때문에 실행기 · 접수 · 검사 · 처리기 · 삭제로 나누고, 나눈 묶음마다 구현 → 시험을 지킨다. 지시서대로 계약을 맨 앞에 두고, 큰 묶음은 ResultPipeline → Report → 화면 순이다.

## 4. 단계

### A. 골격 · 계약 · 실행기 확인

- [x] **Step 1 — 계약 먼저 (구조 · 설정 골격 1)** · FR16.1 · 편차 (U6-a) ~ (U6-l)
  - `contracts/parent-api.yaml`: C5 9개 경로(functional-spec 5.1), 머리 주석 "미룬 경로"에서 결과 · 리포트를 뺀다.
  - `contracts/schemas/`: `session-result-response` · `session-result-status-response` · `report-preview-response` · `report-response`(모두 additionalProperties false, 노출 금지 칸 없음 — BR8.1 · BR8.2). `records-response.schema.json` finalStatus에 FAILED, 설명 문장 고침(5.4).
  - `contracts/ai-io.md`: 6.1 설정 이름 `mujoong.text-ai.result.*`, 6.6 2차 분류 묶음 형식, 6.7 경험 구조화 · 6.8 목표 후보 초안(가정 U6-J 표시).
  - `contracts/deviations.md`: (U6-a) ~ (U6-l)(functional-spec 10.1 표).
  - `contracts/timing.md`: functional-spec 5.6 키와 `result.undecided-limit`(R-12)를 더하고 2절 예약표를 실제 키로 옮긴다.
- [x] **Step 2 — 설정 · 패키지 골격 (구조 · 설정 골격 2)** · FR16.2 · NFR6 · BR1.2 · BR1.3
  - `CoreProperties`에 `timing.result.*`(job-attempts · job-retry-backoff · job-tick · attempt-timeout · reconcile-interval · undecided-limit) · `timing.report.*`(ready-target · unsaved-retention) · `limits.result-workers`, `TextAiProperties`에 `result.*`(request-timeout · temperature-judgment · temperature-generation · prompt-version). `application.yml` · `application-test.yml`에 같은 키.
  - `ErrorCode`에 `JOB_NOT_FAILED` · `REPORT_NOT_READY`(HTTP 409), 프런트 `shared/errors/codeToCopy.ts`에 문구 매핑.
  - 패키지 골격 `kr.mujoong.core.result`(+ `check` · `web`) · `kr.mujoong.core.report`(+ `pdf` · `web`)와 `package-info.java`(설계 ID).
  - `core-api` 빌드 파일에 OpenHTMLtoPDF(`openhtmltopdf-pdfbox`, LGPL) 의존성, 시험 의존성에 PDF 텍스트 추출용 PDFBox(이미 전이 의존이면 그대로). 글꼴 Noto Sans KR Regular · Bold를 현대 한글 11,172자 + 기본 라틴 · 숫자 · 문장 부호로 서브셋(fonttools `pyftsubset`, 명령은 design.md에 기록)해 `src/main/resources/report/fonts/`에 둔다(FD-Q6). OFL 라이선스 파일을 같은 폴더에 둔다.
- [x] **Step 3 — 시험 실행기 확인과 기준선** · NFR9
  - `unit-test-instructions.md`의 명령이 지금 브랜치에서 돈다는 것을 확인한다: 백엔드 `-PincludeTags=u6 -PcoverageScope=u6`(아직 시험 0개라 범위 파일은 Step 32에서 채우므로, 여기서는 `-PincludeTags=u5a`로 실행기만 확인), 프런트 `npx vitest run tests/u5a`.
  - 회귀 기준선을 잰다: 백엔드 전체 `./gradlew --no-daemon check`, 프런트 `npx vitest run`. 통과 · 실패 · 건너뜀 수를 code-summary에 남긴다.
  - 커밋 `feat(u6): 계약 · 설정 골격` (Step 1 ~ 3)

### B. 데이터 모델

- [x] **Step 4 — core V5 마이그레이션 구현** · entities.md · BR1.4 · BR2.1 · BR4.5 · BR10.3
  - `V5__u6_results_report.sql`: 새 표 10개, 색인 `(status, next_attempt_at)`, 부분 유일 `subject_id WHERE status IN ('REQUESTED','RUNNING')`, `UNIQUE(job_id, attempt_no)` · `UNIQUE(session_id, version)` · `UNIQUE(guardian_id, idempotency_key)` · `UNIQUE(result_id, goal_selection_id, scene_no)`, enum CHECK, SceneJudgment `value IS NULL ⇔ scene_chip = 'NOT_DONE' ⇔ not_done_reason IS NOT NULL` CHECK, `core_support_item.title` nullable 열. 기존 표에 FK · 제약을 걸지 않는다(가정 C22).
- [x] **Step 5 — 데이터 모델 시험** · 위와 같음
  - `U6SchemaIT`(7): 표 · 열 존재, 부분 유일(REQUESTED 둘 → 충돌, DONE + REQUESTED → 허용), 장면 CHECK 세 가지 조합, idempotency 유일, support_item.title null 허용, 기존 표 제약 무변경, 스키마 권한(core 사용자).
  - 기존 마이그레이션 · 스키마 시험(`FlywayConfIT` 등)이 최신 버전을 단언하면 V5로만 고친다.

### C. 저장소 · 데이터 접근

- [x] **Step 6 — 저장소 구현** · BR1.2 · BR1.4 · BR1.5 · BR1.9 · BR1.10 · BR2.1 · BR2.3 · BR6.4 · BR10.3 · BR12.1 ~ BR12.3 · FR13.x
  - `result/ResultJobRepository`: submit(키 멱등 + 부분 유일 충돌 시 기존 작업 반환), `claim(jobType, limit)`(SKIP LOCKED), 시도 행, 전이 조건부 갱신, 기동 점검(RUNNING → FAILED), 임대 만료 조회.
  - `result/SessionResultStateRepository`(INSERT … ON CONFLICT DO NOTHING · 조건부 retry 갱신), `ActivityResultRepository`(버전 · invalidated_at · pending_candidates · candidates_registered_at), `SceneJudgmentRepository` · `StructuredExperienceRepository`.
  - `report/ReportRepository`(머리 · 활동 · 항목 · 인용, 묘비 버림, 24시간 대상 조회).
  - U4a: `ActivityContextRepository.findBySession`(공개 읽기), `record` 쪽 판정 조건 공개 상수(BR2.2 · BR14.1 공용, functional-spec 5.4).
  - U5a(더하기만): `ScenarioLookup.get`(scenario_text · trigger_script, partner_role_text 제외), `BehaviorDefinitionLookup.get`(`requireVersion`), `CandidateRegistry.viewForResult`, `SupportLibrary.approvedByBehaviorKeys`.
- [x] **Step 7 — 저장소 시험**
  - `ResultJobRepositoryIT`(8): 같은 키 2번 → 1건, 같은 subject 동시 submit 2개 → 1건, 두 스레드 동시 claim → 겹치지 않음, next_attempt_at 미래는 안 잡힘, 조건부 전이 진 쪽 0행, 기동 점검은 RUNNING만, 임대 만료 조회, 시도 번호 유일.
  - `SessionResultStateRepositoryIT`(5): 동시 삽입 2개 → 1행, retry 조건부 갱신 FAILED만, 현재 버전 교체 · invalidated_at, 미등록 후보 조회, 남의 보호자 조회 없음.
  - `ReportRepositoryIT`(5): 묘비 버림 뒤 하위 0행, 같은 키 조회, 24시간 대상(READY · FAILED만, SAVED 제외), 인용 저장, 활동 날짜.
  - `U5aExtensionU6IT`(6): ScenarioLookup 사상 · 상대 역할 미포함 · 남의 id 제외, RETIRED 버전 읽기, viewForResult evidence 포함, approvedByBehaviorKeys 미승인 제외. 기존 U5a 시험 그대로 통과.
  - 커밋 `feat(u6): 결과 · 리포트 데이터 모델과 저장소` (Step 4 ~ 7)

### D. 업무 로직 — ResultPipeline

- [x] **Step 8 — 실행기 구현** · BR1.1 ~ BR1.12 · FR1.1
  - `JobRunner`(register · submit), `JobRunnerLoop`(SmartLifecycle, job-tick, REPORT 전용 1 + SESSION_RESULT 1 스레드, REPORT 먼저 선점), `JobContext`(commit 확인: RUNNING · attempt_count · 삭제 중 아님 · REPORT면 리포트 GENERATING, afterCommit 후크), `Outcome`, 재시도 간격 `backoff[attempt_count − 1]`, 임대 만료, 기동 점검(가정 U6-K), REPORT 전이와 리포트 상태 동반(BR1.12), SESSION_RESULT 전이와 머리 행 동반(BR1.11).
- [x] **Step 9 — 실행기 시험**
  - `JobRunnerTest`(8, 가짜 Clock · 즉시 실행기): 1회 실패 → 5초, 2회 → 15초, 3회 → FAILED, 2회째 성공 → DONE, 재시도 불가 코드는 바로 FAILED, 시한 취소 → TIMEOUT, 같은 jobType 두 번 등록 → 기동 실패, 처리기 금지 능력(작업 행 직접 변경 경로 없음) 확인.
  - `JobRunnerIT`(6): 기동 점검(RUNNING → FAILED · REQUESTED 이어 실행), 쓰기 확인에서 진 시도는 결과를 쓰지 않음, 리포트 버림 → 작업 FAILED(REPORT_DISCARDED) · 리포트 DISCARDED 유지, 마감 지남 → REPORT FAILED 동반, 스레드 경합(SESSION_RESULT 셋이 느려도 REPORT가 다음 주기에 잡힘), 보호자 삭제 중 → ACCOUNT_DELETING.
- [x] **Step 10 — 접수 · 누락 점검 · 다시 시도 구현** · BR2.1 ~ BR2.5 · FR2.x · FR1.2 · R-12
  - `ResultEligibility`(ELIGIBLE · UNDECIDED · NOT_ELIGIBLE, record 쪽 공개 조건과 같은 판정), `ResultIntake`(@TransactionalEventListener AFTER_COMMIT, 새 트랜잭션), `ResultReconciler`(기동 + 5분: ① 머리 행 없는 기록 100건 ② 미등록 후보 재등록 ③ 리포트 24시간 정리 ④ R-12 UNDECIDED 1시간 초과 → NOT_ELIGIBLE), `ResultRetryService`.
- [x] **Step 11 — 접수 시험**
  - `ResultEligibilityTest`(8): 상태 6종 × 발화 0 · 1, 사본 미반영 → UNDECIDED, 사본 FAILED + 다른 failure_code → NOT_ELIGIBLE.
  - `ResultIntakeIT`(7): 기록 저장 커밋 뒤 작업 1건, 롤백 저장 → 0건, DECLINED · 발화 0 → NONE 머리 행, 기록이 사본보다 먼저 온 재시작 FAILED → UNDECIDED → 사본 반영 뒤 누락 점검에서 ELIGIBLE, 이벤트 처리와 누락 점검 동시 → 작업 1건, 누락 이벤트 → 다음 점검에 접수, R-12 1시간 초과 UNDECIDED → NOT_ELIGIBLE · 뒤 세션이 굶지 않음.
  - `ResultRetryIT`(5): FAILED → 202 · 새 차수, DONE · RUNNING → 409, 연타 3회 → 작업 1건, 다시 시도 DONE → 버전 2 · 현재 1, 다시 시도 FAILED → 이전 버전 유지.
- [x] **Step 12 — 출력 검사 · AI 경계 구현** · BR5.2 ~ BR5.4 · BR3.2 · BR9.7 · FR16.3 · NFR3 · NFR4
  - `check/QuoteMatcher`(앞뒤 공백만 무시, 글자 단위), `check/NumberGuard`(인용 부호 밖 숫자는 hh:mm · 장면 n만), `check/ForbiddenDictionary`(리소스 `forbidden/result-v1.json`, ai-io 4절 형식, appliesTo RESULT_TEXT · PDF, 6.6 초안 표, 인용 부호 안 제외), `check/OutputChecker`(①②③ + 2차 묶음).
  - `ResultTextProvider` 포트(기본 빈 UNAVAILABLE, `@ConditionalOnMissingBean`), `ResultTextGateway`(작업별 요청 조립: "아이" · 별칭 · activityNo, id · 원음 미전송, 호출 시한 min(10초, 남은 시도 시간), 게이트웨이 내부 재시도 없음, 출력 JSON 스키마 검사), 프롬프트 리소스 `prompts/result/*.v1.txt`(가정 U6-J).
- [x] **Step 13 — 검사 · AI 경계 시험**
  - `QuoteMatcherTest`(5) · `NumberGuardTest`(5) · `ForbiddenDictionaryTest`(6: 7범주 적중, 인용 부호 안 제외, 고정 문구 · 칩 문구 · 템플릿 · 9-20 ~ 9-34 · PDF 고정 문구 0건 — NFR3), `OutputCheckerTest`(6: 순서 · 템플릿 전환 · 2차 묶음 한 호출), `ResultTextGatewayTest`(6: UNAVAILABLE, 시한 취소, 재시도 없음, 스키마 실패 → SCHEMA_INVALID, 요청에 이름 · id · 원음 없음, 로그에 본문 없음 — NFR4).
- [x] **Step 14 — SESSION_RESULT 처리기 구현** · BR3.x · BR4.x · BR5.x · BR6.x · BR7.x · FR3 ~ FR7
  - `ResultLevelCalculator`(2단계), `SceneChipMapper` · `NotDoneReasonMapper`(FD-Q3 사상표, 모르는 값 로그), `HelpSummarizer`, `TipMatcher`(behavior_key 교집합 · 1차 사전 숨김 · 매칭 로그), `CandidateScreen`(근거 아이 회상만 · 최대 3 · 정의 ACTIVE), `SessionResultHandler`(WF3 자유대화 · WF4 연습, INSUFFICIENT는 AI 없이 DONE, pending_candidates 저장 → afterCommit 등록), `ResultEvidenceAdapter`(CandidateEvidencePort 교체, 잠금 없이 읽기).
- [x] **Step 15 — 처리기 시험**
  - `ResultLevelCalculatorTest`(8): 발화 2 · 3개, 어절 2 · 3개, uncertain, 첫 반응 장면 0 · 1, 목표 둘 중 하나만, CONSENT_WITHDRAWN, 미확인 · ABORTED · 재시작 FAILED는 FULL 아님.
  - `SceneChipMapperTest`(6) · `NotDoneReasonMapperTest`(6) · `TipMatcherTest`(6: 매칭 · 비매칭 · 미승인 제외 · 사전 숨김 · title 없음 · 0개 → NEED_MORE_OBSERVATION).
  - `SessionResultHandlerIT`(8, `FakeResultTextProvider`): 자유대화 정상(구조화 · 출처 두 종류 · 의도 감정 칸 없음), 연습 정상(장면 2개 · 차이 표시 · 칩), practice null → INSUFFICIENT · DONE, INSUFFICIENT → AI 호출 0, 금지 · 인용 불일치 → 템플릿 2종, UNAVAILABLE → 3회 뒤 FAILED, 후보 근거 없음 · 보호자 입력만 → 등록 0, afterCommit 실패 → 누락 점검 재등록 · 후보 1건.
  - `ResultEvidenceAdapterIT`(4): 있는 아이 발화 확인, 없는 번호 · 남의 세션 · AI 발화 거절.
- [x] **Step 16 — 계정 삭제 참가자 구현** · BR13.1 · BR13.2 · FR14.1
  - `DeletionParticipant.ORDER_RESULT = 70` · 순서 주석, `ResultDeletionParticipant`(하위부터 10개 표, 멱등), 쓰기 단계 삭제 중 확인(JobContext · 접수).
- [x] **Step 17 — 삭제 시험**
  - `ResultDeletionIT`(5): 삭제 뒤 그 보호자 U6 행 0건 · 다른 보호자 보존, 두 번 실행 멱등, 실행 중 작업과 겹쳐도 0건, 순서 70이 90보다 먼저, 삭제 중 접수 안 함.
  - 커밋 `feat(u6): ResultPipeline 실행기 · 정리 작업 · 삭제 참가자` (Step 8 ~ 17)

### E. API — 결과 · 재시작 FAILED 노출

- [x] **Step 18 — 결과 API · U4a 변경 구현** · BR8.1 ~ BR8.4 · BR2.4 · BR14.1 · BR14.2 · FR8.x · FR15.1
  - `result/web/ResultController`(GET result · GET result/status · POST result/retry, CSRF), `ResultQueryService`(현재 버전 조립, 칩만, viewForResult 후보, 머리 행 없음 처리 BR8.3), 응답 DTO(노출 금지 칸 없음).
  - U4a: `ActivityRecordRepository.LISTED_STATUSES`에 판정 함수 조건, `RecordQueryService` 상세 404 예외, 주석 고침(functional-spec 5.4).
- [x] **Step 19 — 결과 API 시험**
  - `ResultApiIT`(8): 응답 스키마 검증(additionalProperties false, 금지 칸 부재 — NFR2), 남의 세션 404, 삭제 중 403, status의 머리 행 없음 경우들(기록 있음 · 없음 · 로컬 FAILED → NONE), retry 202 · 409 · 연타, CSRF 없음 거절.
  - `RecordFailedExposureU6IT`(5): 재시작 FAILED + 발화 → 목록 · 상세 보임 · "중간에 끊김" 데이터, 발화 0 → 안 보임, 다른 FAILED → 안 보임 · 상세 404, records-response 스키마 통과.
  - 커밋 `feat(u6): 결과 API · 재시작 FAILED 노출` (Step 18 ~ 19)

### F. Report

- [x] **Step 20 — 리포트 업무 로직 구현** · BR9.x · BR10.1 ~ BR10.5 · FR9.x · FR10.1 · FR10.2
  - `report/ReportService`(미리보기 listed_at 날짜 · firstRecordDate, 생성 검사 · 같은 키 · 409 · 남의 세션 400 · 이전 저장 안 된 리포트 버림, 저장 멱등, 버림 묘비, 24시간 정리), `report/QuoteCandidatePicker`(항목마다 30 · 활동당 5 · 최근부터), `report/ReportAssembler`(서버 항목 1 · 3 · 4 · 7 · 8 · 9, DONE만 결과 값 항목, 9항목 2회 미만 자료 부족), `report/ReportHandler`(AI 한 번 + 2차 한 번, 템플릿 · 자료 부족, 10항목 고정 문장, 5항목 출처, citation_count · level, 마감 계산), ChildContext.get 사용(이름 · 나이는 AI에 안 보냄).
- [x] **Step 21 — 리포트 업무 로직 시험**
  - `QuoteCandidatePickerTest`(5): 31개째 제외, 활동당 6개째 제외, 내용 있는 발화 우선, 최근부터, activityNo 사상.
  - `ReportAssemblerTest`(8): 1항목 집계값, 3 · 9항목 칩만, 9항목 1 · 2회, DONE 아닌 활동은 결과 값 항목에서 빠짐, 남는 것 없음 → 자료 부족, 8항목 예 선택(정답 문장 없는 것), 4항목 관심사 없음, level 규칙.
  - `ReportHandlerIT`(8): 시험 A(활동 20건 · 호출당 2초 · 첫 호출 실패 · 60초 안 READY · 걸린 시간 기록 — NFR1), 시험 B(마감 3초 → DEADLINE_EXCEEDED), 시험 C(다음 시도 시각이 마감 뒤 → 바로 FAILED), 시험 D(스레드 경합), 인용 불일치 제거 · 템플릿, 5항목 출처 · 보호자 입력 quote 없음, citation_count 중복 제외, 버려진 리포트에 늦은 작업 → 항목 0.
  - `ReportServiceIT`(7): 미리보기 진행 중 · DECLINED 제외 · 재시작 FAILED 포함 · startedAt 없는 활동 날짜, 같은 키 첫 결과 · 다른 본문 409, 남의 세션 400 · 리포트 0건, 새 요청이 이전 저장 안 된 리포트 버림, 저장 멱등 · 첫 savedAt, SAVED 버림 204 · 그대로, 24시간 정리.
- [x] **Step 22 — PDF · 리포트 API 구현** · BR10.6 · BR10.7 · BR8.4 · FR10.3 · NFR12
  - `report/pdf/ReportPdfRenderer`(HTML 템플릿 `report/template.html` 채우기 → OpenHTMLtoPDF → byte[], 글꼴 클래스패스, 임시 파일 · 디스크 캐시 없음, 머리 · 고지 · 10항목 · 매 쪽 바닥글), `report/web/ReportController`(preview · create · get · pdf · saved · discard, Content-Disposition `filename*` UTF-8, `Cache-Control: no-store`, CSRF).
- [x] **Step 23 — PDF · 리포트 API 시험**
  - `ReportPdfTest`(6): 텍스트 추출이 30 항목 · 머리 · 고지 · 바닥글과 같음, 한글 깨짐 없음, 금지 칸 · 관심사 · 판정 라벨 없음, 텍스트 PDF(글자 추출 가능), 활동 20건 렌더링 중 `java.io.tmpdir` 새 파일 0개, 렌더링 실패 → 500 · 로그 본문 없음.
  - `ReportApiIT`(7): 응답 스키마, sections는 READY · SAVED만, PDF 409 · 파일명 · 캐시 머리, saved 409 · 204, discard 204, 404 · 403, CSRF.
  - 커밋 `feat(u6): 상담용 리포트 · PDF` (Step 20 ~ 23)

### G. 화면

- [x] **Step 24 — 공통 프런트 구현** · BR11.2 · BR11.11 · FR11 · FR12.3
  - `parent/api/results.ts` · `reports.ts`(C5 호출, ApiError 코드), `parent/hooks/useResultStatusPolling.ts` · `useReportPolling.ts`(`parent.poll-interval`), `parent/components/SceneChip.tsx` · `EndReasonCard.tsx` · `MonthCalendar.tsx` · `LiveRegion.tsx`, `shared/copy/result.ts`(TEMP_COPY), `navigation.ts` 경로 · 기록 탭.
- [x] **Step 25 — 17 · 26 · 27 구현** · BR11.1 · BR11.3 ~ BR11.6 · BR14.2 · FR11.1 ~ FR11.3
  - `Done17.tsx` 정리 상태 · 단추, `screens/Records26.tsx` + `app/parent/records/page.tsx`(스텁 교체), `screens/RecordDetail27.tsx` + `app/parent/records/[sessionId]/page.tsx`. data-testid는 frontend-components 표대로.
- [x] **Step 26 — 28 · 30 구현** · BR11.7 ~ BR11.10 · FR12.1 · FR12.2
  - `screens/ReportRange28.tsx` + `app/parent/reports/new/page.tsx`, `screens/Report30.tsx` + `app/parent/reports/[reportId]/page.tsx`(PDF Blob 받기 → 저장 → saved, Web Share files 지원 판단, 새 탭 열기).
- [x] **Step 27 — 프런트 시험**
  - `tests/u6/parent/done-17-result.test.tsx`(6) · `records-26.test.tsx`(7) · `record-27.test.tsx`(8) · `report-28.test.tsx`(8) · `report-30.test.tsx`(8) · `tests/u6/parent/hooks-u6.test.ts`(5: 폴링 시작 · 멈춤 · 떠나면 멈춤 · 60초), frontend-components 9절 내용.
  - 기존 단언 두 곳 고침: `tests/u1/parent/done-17.test.tsx:109-112` · `tests/u4a/parent/screens-u4a.test.tsx:155` — "정리 끝(DONE)에서만 보임"(functional-spec 5.4). 그 밖 기존 단언은 그대로.
  - 커밋 `feat(u6): 기록 · 리포트 화면` (Step 24 ~ 27)

### H. 환경 · 빌드 설정

- [x] **Step 28 — 빌드 · 이미지 설정** · NFR12 · FD-Q6
  - core-api 이미지 빌드(`infra/` Dockerfile · compose)가 글꼴 리소스를 포함하는지 확인하고, 이미지 크기 증가(글꼴 + 라이브러리)를 잰다. 컨테이너 한도(메모리 1.6g · /tmp 64m · 읽기 전용 루트)는 바꾸지 않는다.
- [x] **Step 29 — 커버리지 범위 파일** · NFR8
  - `backend/coverage-scope/u6.txt`(U6이 새로 만들거나 바꾼 core-api · contract-model 운영 자바 소스 전부), `frontend/coverage-scope/u6.json`(U6 신규 · 변경 운영 TS 전부).
  - 범위 시험 + 커버리지를 돌려 80% 이상을 확인한다. 모자라면 시험을 더한다.
  - 회귀: 백엔드 `check`, 프런트 `npx vitest run`, lint · typecheck가 Step 3 기준선보다 나빠지지 않음(NFR9).
  - 커밋 `feat(u6): 커버리지 범위 · 빌드 설정` (Step 28 ~ 29)

### I. 문서 · 추적

- [x] **Step 30 — 설계 · 보고 문서** · FR16.4
  - `docs/units/u6/design.md`: Functional Design 요지(entities · rules 묶음 · 상태 기계 · 워크플로 · 인터페이스 · 편차 · 가정 U6-J · U6-K), PDF 라이브러리 · 글꼴 정확한 버전 · 라이선스 · 서브셋 명령 · 이미지 크기 증가(FD-Q6).
  - `docs/units/u6/report.md` 초안(한 일 · 시험 결과 · 남은 일: U5b 사상표 맞춤, D 담당 프롬프트 · 사전 · 팁 시드, 팀장 확인 자유대화 문장, 안드로이드 크롬 수동 확인), `docs/units/README.md` 현황표 U4b(병합) · U6(진행) 줄.
- [x] **Step 31 — 코드 요약 · 출처 목록 · 추적표**
  - `construction/code-generation/code-summary.md`, `source-manifest.json`(만들거나 바꾼 모든 운영 · 시험 · 계약 · 문서 경로), `traceability.json`(BR1.1 ~ BR14.2 · FR16.x · NFR → 구현 · 시험 파일).
  - 커밋 `docs(u6): 설계 · 보고 문서와 추적표` (Step 30 ~ 31)

## 5. 요구사항 · 규칙 → 단계 추적

| 요구사항 | 규칙 | 단계(구현 · 시험) |
|---|---|---|
| FR1.1 실행기 | BR1.1 ~ BR1.12 | 6 · 8 / 7 · 9 |
| FR1.2 다시 시도 | BR2.4 · BR2.5 | 10 · 18 / 11 · 19 |
| FR2.1 ~ FR2.3 접수 · 정리 대상 · 누락 | BR2.1 ~ BR2.3 | 10 / 11 |
| FR3.1 구조화 | BR3.1 ~ BR3.3 | 12 · 14 / 13 · 15 |
| FR4.1 ~ FR4.3 재판정 · 칩 · 도움 | BR4.1 ~ BR4.6 | 14 / 15 |
| FR5.1 · FR5.2 결과 문장 · 수준 | BR5.1 ~ BR5.6 | 12 · 14 / 13 · 15 |
| FR6.1 · FR6.2 후보 · 근거 어댑터 | BR6.1 ~ BR6.5 | 14 / 15 |
| FR7.1 팁 | BR7.1 · BR7.2 | 6 · 14 / 7 · 15 |
| FR8.1 ~ FR8.4 결과 API | BR8.1 ~ BR8.4 | 18 / 19 |
| FR9.1 ~ FR9.4 리포트 생성 | BR9.1 ~ BR9.10 | 20 / 21 |
| FR10.1 ~ FR10.3 확인 · 저장 · PDF | BR10.1 ~ BR10.7 | 20 · 22 / 21 · 23 |
| FR11.1 ~ FR11.3 17 · 26 · 27 | BR11.1 ~ BR11.6 | 24 · 25 / 27 |
| FR12.1 ~ FR12.3 28 · 30 · 접근성 | BR11.7 ~ BR11.11 | 24 · 26 / 27 |
| FR13.1 ~ FR13.3 U5a 확장 | BR12.1 ~ BR12.3 | 6 / 7 |
| FR14.1 삭제 70 | BR13.1 · BR13.2 | 16 / 17 |
| FR15.1 재시작 FAILED | BR14.1 · BR14.2 | 18 · 25 / 19 · 27 |
| FR16.1 계약 먼저 | — | 1 |
| FR16.2 시간 키 | — | 1 · 2 |
| FR16.3 금지 사전 | — | 12 / 13 |
| FR16.4 문서 | — | 30 |
| NFR1 60초 | BR9.4 | 8 · 20 / 21(A ~ D) |
| NFR2 노출 금지 | BR8.1 · BR8.2 · BR10.7 | 18 · 22 / 19 · 23 |
| NFR3 금지 표현 | BR5.2 · BR5.3 · BR7.2 | 12 / 13 |
| NFR4 입력 최소화 | BR3.2 · BR1.6 · BR9.7 | 12 / 13 |
| NFR5 접근 제어 | BR8.4 | 18 · 22 / 19 · 23 |
| NFR6 시간 값 한 곳 | — | 2 |
| NFR7 멱등 | BR1.4 · BR1.5 · BR2.4 · BR9.2 | 6 · 10 · 20 / 7 · 11 · 21 |
| NFR8 커버리지 | — | 29 |
| NFR9 회귀 없음 | — | 3 · 29 |
| NFR10 계약 적합성 | BR8.1 | 1 / 19 · 23 |
| NFR11 삭제 완결성 | BR13.1 · BR13.2 | 16 / 17 |
| NFR12 컨테이너 한도 PDF | BR10.6 | 22 · 28 / 23 |

## 6. 위험과 남은 일

- **U5b 맞춤**: 미실시 사유 사상표(BR4.4)와 practice null 규칙은 U5b 담당과 맞출 안건이다. 연습 시험은 계약 6절 예시 값 대역으로만 만든다.
- **D 담당 확인**: 프롬프트 본문 · 금지 사전 v1 · 생활 팁 시드는 가정 U6-J 초안이다. 확인되면 리소스만 바꾼다.
- **팀장 확인**: 자유대화 결과 문장의 27 노출(편차 (U6-l)).
- **PDF 크기 · 성능**: 글꼴 서브셋 크기와 렌더링 시간이 컨테이너 한도 · 60초 안이어야 한다(가정 A6). 넘치면 Step 28에서 멈추고 보고한다.
- **단일 인스턴스 전제**(가정 U6-K): 여러 인스턴스로 늘리면 기동 점검을 임대 기준으로 바꿔야 한다.
- **기존 시험 영향**: 마이그레이션 버전 단언(V4 → V5)이 있으면 그것만 고친다. 그 밖의 기존 단언이 깨지면 고치지 말고 원인을 찾는다.
- **안드로이드 크롬 수동 확인**(R-07)은 Build and Test에서 한다.

## Sources

- 설계: `construction/functional-design/functional-spec.md` · `rules.md` · `entities.md` · `frontend-components.md` · `traceability.json`
- 요구사항: `inception/requirements-analysis/requirements.md`
- 지시 · 인수인계: `docs/units/u6/brief.md`, `HANDOFF.md`
- 기존 시험 방식: `aidlc/spaces/default/intents/261013-u4b-activity-realtime/construction/code-generation/unit-test-instructions.md`, `frontend/vitest.config.ts`, `backend/coverage-scope/u5a.txt`, `frontend/coverage-scope/u5a.json`
