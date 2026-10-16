# U8 계약 · 버전 리소스 — 코드 생성 계획

> 근거: `../functional-design/functional-spec.md`(1 · 2 · 6 · 7 · 8절) · `rules.md`(BR1 ~ BR7) · `entities.md`, `../nfr-requirements/security-requirements.md` · `tech-stack-decisions.md`, `../nfr-design/security-design.md`(SD1 ~ SD7), `../../../inception/contract-design/contract-summary.md`, `../../../inception/units-generation/unit-of-work.md`(U8), `../../../inception/requirements-analysis/requirements.md`(FR6.10 · FR10 · FR11).
> 브랜치: `main`에서 새로 딴 `unit/u8-contracts-catalog`(team.md 브랜치 규칙). 작업 트리의 `aidlc/` 미커밋 변경은 그대로 따라온다.
> 이 단위에는 스토리가 없다. 스토리 대신 FR10 · FR11과 Functional Design 규칙(BR) · NFR(NFR2.x · NFR3.x · NFR6.x)을 단계에 잇는다.
> 방법론은 test-after다. 시험할 수 있는 층 하나를 구현하면 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 간다.

## 단계

### 준비

- [ ] **Step 1. 브랜치와 기준선** — `main`에서 `unit/u8-contracts-catalog`를 딴다. 바꿀 쪽의 기존 시험을 한 번 돌려 기준선(통과 · 실패 수)을 `code-summary.md`에 적는다(brownfield 시험 기준선). 커밋 전 `git diff --cached --summary`로 실행 권한 변경이 없는지 본다(project.md 교정).
- [ ] **Step 2. 시험 실행 확인** — `unit-test-instructions.md`의 단위 범위 명령이 이 PC에서 도는지 확인한다(JDK 21 있음, Docker 없음 → DB가 필요 없는 단위 시험만 돌고, DB 통합 시험은 Build and Test에서 `verify.sh`로 확인).

### 층 1 — 계약 파일 (FR11.2 · FR11.1 · BR5.1 · BR6.1 · BR7.1)

- [ ] **Step 3. AI 입출력 스키마 구현** — `contracts/schemas/`에 다음 파일을 더한다. 모두 `$schema` 2020-12, `$id` `https://mujoong.invalid/contracts/schemas/<파일>`, 루트 · 하위 객체 `additionalProperties: false`, 아이 문자열 필드 `description`에 "아이 이름은 '아이'로 치환된 값"(SD1).
  - `ai-judgment-request.schema.json` · `ai-judgment-output.schema.json`(C-DS4)
  - `ai-scenario-output.schema.json` · `ai-scenario-review-output.schema.json`(C-DS5. 장면 필드에 `partnerRole`은 넣지 않는다 — Contract Design 검토 R-04, ai-io 7절 J22)
  - `ai-goal-candidate-output.schema.json` · `ai-rejudgment-output.schema.json`(C-DS6)
  - `ai-seed-output.schema.json`(C-DS7)
  - `guardrail-request.schema.json` · `guardrail-result.schema.json`(C-DS8)
  - `practice-record-v2.schema.json`(C-DS2, `flowVersion` 필수 — BR6.1)
- [ ] **Step 4. 계약 문서 구현**
  - `contracts/ai-io.md` 새 절 '10. U8 공통 규칙': 스키마 파일 목록, 금지 필드 목록, 목적 → 호출 경로 → 재시도 분류 표(functional-spec 8.4), `R-F15` 표식 문장(SD2), `UNKNOWN` · `NOT_MATCHED`는 제브 생략 근거가 아님(SD6), `MUJOONG_TEXT_AI_API_KEY` 미사용(BR4.2).
  - `contracts/timing.md` 새 절: `키 | 기본값 | 소유 앱` 표에 키 7개(functional-spec 8.2). 12절에 '레거시 `practice.judge-*`는 과거 경로용으로 남음' 한 줄.
  - `contracts/schemas/internal-session-start-request.schema.json`: `properties`에 선택 속성 `approvedSpeech`(값 `{contentVersionId, text}`, `additionalProperties: false`). `contracts/internal-api.yaml` 시작 요청 예시에 `approvedSpeech` 예시 하나(BR1.2).
  - `contracts/events.asyncapi.yaml` · `event-payloads.schema.json`의 `SessionRecordCaptured` 설명에 `record.practice` 새 형식(`flowVersion`)과 스키마 파일 이름(BR6.1). `snapshotVersion`은 건드리지 않는다.
  - `contracts/deviations.md` 새 절 'U8': 기호 `U8-a` ~ (새 AI 스키마, `approvedSpeech`, 새 시간 절과 `timing-defaults.json` 제외 해석, `record.practice` v2, 승인 발화 `APPROVED_SPEECH`). YAML 표시는 `x-u8-deviation`, Markdown은 `[U8 편차 (U8-a)]`(BR7.1).
- [ ] **Step 5. 계약 파일 시험 작성 · 실행** — contract-model 시험 `U8ContractSchemasTest`(`@Tag("u8")`):
  - 스키마마다 정상 예시 1개 통과
  - 금지 필드(`childId` · `guardianId` · `sessionId` · `deviceId` · `apiKey` · `audio`) 거절 예시 실패
  - 아이 문자열 필드 `description` 치환 문구 존재
  - `ai-io.md` 10절의 `R-F15` 표식 문장 존재
  - `practice-record-v2`가 `flowVersion` 없는 기록을 거절
  - 기존 `ContractFilesTest`(스키마 메타 · `$ref` · 예시) · `ErrorCodeTest`가 그대로 통과(새 오류 코드 없음, BR7.3)

### 층 2 — 설정 (FR11.1 · FR11.3 · BR3 · BR4)

- [ ] **Step 6. 시간 키 구현** — realtime `application.yml`에 `mujoong.timing.judgment.{live-budget, response-ready}` · `guardrail.jev-budget` · `conversation.{no-response-wait, focus-max, goal-active-limit}`, core `application.yml`에 `mujoong.timing.knowledge.graph-budget`. 설정 레코드(realtime: 판정 · 가드레일 · 대화 시간 레코드, core: `CoreProperties.timing`의 `knowledge` 묶음)를 기존 `RealtimeProperties` · `CoreProperties` 구조에 맞춰 더한다. `result.retry-delays`는 만들지 않는다(BR3.2).
- [ ] **Step 7. 시간 키 시험 실행** — 두 앱 `ConfigKeyContractTest` · `TimeLiteralScanTest`가 통과한다. 설정 레코드 바인딩 단위 시험(기본값 7개)을 `@Tag("u8")`로 더한다.
- [ ] **Step 8. 비밀 이름 구현** — `OPENAI_API_KEY`(core · realtime) · `JEV_API_KEY`(realtime)를 R-M9 목록 전부에 같은 커밋으로 넣는다(SD3).
  - `.github/workflows/ci.yml` 번들 검사 정규식
  - `infra/ci/image-checks.sh` `SECRET_NAME_PATTERN`
  - `infra/bin/infra-validate` `SECRET_NAMES`와 서비스별 `SECRET_TABLE`(이름순)
  - `infra/bin/render-env` 주석 · `OPTIONAL` · 앱별 `write_env`
  - `infra/bin/log-hygiene-check` `SECRET_VARS`
  - `scripts/verify/lib.sh` `VERIFY_SECRET_NAMES` · `scripts/verify/b-stack.sh` 서비스별 기대값
  - `infra/ci/gen-env.sh` 주석 · 앱별 빈 값 줄
  - `README.md` 비밀 목록, `infra/RUNBOOK.md` 단계 4(OPTIONAL 입력) · 12절 회전 표
- [ ] **Step 9. 비밀 이름 시험 실행** — `infra/ci/test-infra-validate.sh` · `infra/ci/test-host-tools.sh`에 새 이름의 앱별 분배 사례를 더하고 돌린다. `infra/bin/infra-validate`를 돌려 통과를 확인한다. 셸 파일은 shellcheck를 통과해야 한다.

### 층 3 — 데이터(시드) (FR10.2 · BR1.1 · 검토 R-01 남은 점)

- [ ] **Step 10. 승인 발화 시드 구현**
  - 새 파일 `infra/seed/004-u8-reference.sql`. 기존 001 ~ 003은 고치지 않는다.
    - `HELP_OFFER` 부모 행의 `content_type`을 `APPROVED_SPEECH`로 바꾸는 멱등 UPDATE를 넣는다.
    - v1 DRAFT를 RETIRED로 바꾼다.
    - v2 APPROVED(정본 문구)를 `ON CONFLICT DO NOTHING`으로 넣는다.
  - 새 code `D_REST_ACK_START` · `D_REST_ACK` · `D_REPROMPT` · `COACH_SWITCH` · `PEER_RETURN`의 부모 · 판 행을 `APPROVED_SPEECH`로 넣는다.
  - 고정 UUID는 기존 시드와 겹치지 않는 새 범위(`00000000-0000-4000-8000-0000000008xx`)로 정하고 `SeedIds`와 같은 값을 쓴다.
  - 문구 텍스트는 B §4 · §8.5에서 글자 그대로 옮긴다. 정본에 없는 code는 넣지 않고 code-summary에 적는다.
  - `ApprovedContent`에 `TYPE_APPROVED_SPEECH` 상수와 code 상수를 더한다.
  - `DevSeedRunner`에 같은 행을 넣는다(dev 프로필).
  - 시드 적용 목록에 `004-u8-reference.sql`을 더한다: `scripts/verify/b-stack.sh`(150행 루프) · `scripts/verify/b-host.sh`(155행 루프) · `infra/RUNBOOK.md`(시드 적용 절, 290행 부근). `infra/ci/image-checks.sh`는 001만 쓰므로 그대로 둔다.
- [ ] **Step 11. 시드 시험 실행** — 시드 SQL을 두 번 적용해도 결과가 같은지(멱등) 보는 시험과 dev 시드 시험을 더한다(DB 통합 시험은 `@Tag("db")`, Build and Test에서 Docker 스택으로 실행).

### 층 4 — 업무 로직 (BR1.2 · BR1.3 · BR1.4 · BR2.1 ~ BR2.3 · SD4 · SD5 · SD6)

- [ ] **Step 12. core 시작 패키지 조립 구현** — core `ApprovedContentCatalog`에 `APPROVED_SPEECH` 판을 고르는 조회를 더한다. `latestApproved` · pin 계열을 쓰고, 음성 있는 항목만 거르는 `sessionContent`는 쓰지 않는다. `StartActivityService`가 활동 유형별 허용 목록(functional-spec 8.1 표)을 설정 상수로 두고 `approvedSpeech`를 싣게 한다. 기존 PRACTICE의 `HELP_OFFER` 요청은 `approvedSpeech`로 옮긴다.
- [ ] **Step 13. core 조립 시험 작성 · 실행** — `@Tag("u8")` 단위 시험 5 ~ 8개.
  - 음성 없는 문구는 `approvedSpeech`로만 간다.
  - 허용 목록 밖 code(`CONSENT_POLICY` · DRAFT)는 싣지 않는다.
  - 두 맵의 code가 겹치지 않는다.
  - FREE_TALK와 PRACTICE의 목록이 다르다.
  - APPROVED 판이 없는 code는 빠진다.
  - 기존 `approvedContent`(CLOSING · WAITING_FILLER)는 그대로다.
- [ ] **Step 14. realtime `LiveContentRegistry` 구현** — realtime 새 패키지에 둔다.
  - 판 리소스 `judgment/option-sets-v1.json` · `guardrail/policy-v1.json`을 `JsonCodec`으로 읽고 functional-spec 8.3 규칙으로 검증한다. 실패하면 적재 예외를 던진다(SD5).
  - 세션 계획 고정 · 조회, 대본 등록, 원문 일치(`PracticeSpeech.matches` 재사용, ASCII 공백만 — SD6)와 정산 뒤 해제를 둔다. 세션 id 키 동시성 맵을 쓰고, 로그는 쓰지 않는다(SD4).
  - 판정 선택지 · 가드레일 문구는 A §7.3 · C §3.1 · §3.3에서 옮긴다.
  - `RealtimeStartupChecks`에 판 리소스 적재기 `Supplier`를 받는 생성자를 더하고, 기존 생성자는 오버로드로 남긴다.
  - `StartSessionHandler` · 세션 수신부가 `approvedSpeech`를 읽어 계획에 넣는다. 중복 code면 `approvedSpeech` 쪽을 버리고 `APP_ERROR` `errorKind` `APPROVED_SPEECH_DUPLICATE_CODE`만 남긴다.
  - 세션 정산 경로에서 해제를 부른다.
- [ ] **Step 15. realtime 로직 시험 작성 · 실행** — `@Tag("u8")`.
  - `LiveContentRegistry` 단위 시험 8개 안팎
    - 자원 적재 성공, 자원 형식 오류 3종(빠진 J · 빈 선택지 · 분류 6개) 적재 실패
    - 세션 고정 값이 세션 중 바뀌지 않음, 계획 고정 전 `UNKNOWN`
    - ASCII 공백 차이 `MATCHED`, 한 글자 차이 `NOT_MATCHED`, NBSP · 전각 공백 `NOT_MATCHED`
    - 대본 등록 전 대본 문장 `NOT_MATCHED`, 정산 뒤 `UNKNOWN` · 맵에서 제거
  - `RealtimeStartupChecks` 시험: 판 리소스 적재 실패 시 거부, 기존 생성자 시험이 그대로 통과
  - 세션 수신 시험: `approvedSpeech` 없는 옛 패키지 정상, 모르는 속성 비거절, 중복 code 처리, `LogCapture`로 중복 시 문구 텍스트가 로그에 없음
  - ArchUnit 규칙(`realtime/arch`): 레지스트리 패키지는 `org.slf4j` · `kr.mujoong.contract.log` · `System.out/err`에 의존하지 않음(SD4)

### 층 5 — API · 화면

- (해당 없음) 새 HTTP 경로 · 화면이 없다. 시작 요청 스키마 변경은 Step 4 · 13 · 15에서 다룬다.

### 마무리

- [ ] **Step 16. 범위 파일 · 전체 확인** — `backend/coverage-scope/u8.txt`에 U8이 만들거나 고친 운영 Java 파일을 적는다(빈 목록 금지, J137). 바꾼 쪽 전체 검사 `./gradlew clean check`(Docker가 없으면 DB 통합 시험 제외 사유를 적음)와 U8 범위 줄 80%를 확인한다. 하한 · 대조 시험 · ArchUnit은 낮추거나 건너뛰지 않는다(R-F2).
- [ ] **Step 17. 문서 · 추적** — `code-summary.md`(파일 · 결정 · 시험 · 편차 · 기준선), `source-manifest.json`(만들거나 고친 모든 경로), `traceability.json`(FR10 · FR11 · BR · NFR → 구현 · 시험 파일)을 쓴다. 커밋 메시지는 `<종류>(<범위>): <한국어 요약>` 형식.

## 단계 → 근거 대응

| 단계 | 근거 |
|---|---|
| Step 3 · 5 | FR11.2, BR5.1, BR5.4, NFR3.1 · NFR3.2, SD1 |
| Step 4 | FR11.1 · FR11.2, BR1.2, BR3.1, BR4.2, BR5.2, BR5.5, BR6.1, BR7.1, NFR3.3, SD2, SD6 |
| Step 6 · 7 | FR11.1, BR3.1 · BR3.2 · BR3.3 |
| Step 8 · 9 | FR11.3, BR4.1 · BR4.2 · BR4.3, NFR3.4 · NFR3.5, SD3 |
| Step 10 · 11 | FR10.2, BR1.1, 설계 검토 R-01(남은 점) |
| Step 12 · 13 | BR1.2 · BR1.3, NFR6.1 |
| Step 14 · 15 | FR6.10, FR10.1, BR1.4, BR2.1 · BR2.2 · BR2.3, NFR2.1 ~ NFR2.4, NFR3.6, NFR6.1, SD4 · SD5 · SD6 · SD7 |
| Step 16 · 17 | NFR5(시험 방침), R-F2 |

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "team",
  "ordering": "시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.",
  "scope": "classic",
  "test_strategy": "standard",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: test-after\n- **Ordering**: 시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.\n- 계약에 닿는 변경도 같은 순서를 따른다(Q3-A). 다만 설계 문서 변경이 계약에 닿으면 같은 작업에서 `contracts/`도 함께 고친다(`discovered-rules.md` R-M10).\n- 커버리지 하한(줄 기준만, 분기 · 함수 하한 없음):\n  - 모듈 전체 줄 80%를 CI가 막는다. 백엔드 JaCoCo는 `contract-model` · `core-api` · `realtime-activity`(`test-support`는 제외), 프런트 Vitest v8은 `src/**/*.{ts,tsx}`(`*.d.ts`만 제외).\n  - 유닛 범위 줄 80%는 범위 파일(`backend/coverage-scope/<유닛>.txt` · `frontend/coverage-scope/<유닛>.json`)이 있는 작업에 적용한다. **CI가 실제로 막는 범위는 U5b · U7뿐이다**(백엔드 `u5b` · `u5b-core` · `u7`, 프런트 `u5b` · `u7`). 나머지 범위 파일은 로컬 · 검토 근거다.\n  - `tools/`에는 커버리지 하한이 없다(시험 지원 코드, J107).\n  - 범위 측정은 빈 목록 · 없는 파일이면 실패한다(J137). 범위 파일을 새로 쓰면 이 방식을 그대로 따른다.\n  - `classic` 범위의 줄 80% 하한 · 병합 전 CI 실행과 같다.\n- 하한 · 기존 단언 · 대조 시험 · ArchUnit 시험은 시험을 통과시키려고 낮추거나 끄거나 건너뛰게 하지 않는다. 모자라면 시험을 더하거나 차이를 보고한다.\n- 시험 종류:\n  - 단위: JUnit 5 · AssertJ · Mockito(백엔드), Vitest + Testing Library + jsdom(프런트), Vitest(tools).\n  - 통합: Spring Boot 통합 시험 + Testcontainers PostgreSQL(`-Pmujoong.test.db=docker`), 상대 앱은 스텁.\n  - 계약 대조: `ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `OpenApiContracts` · `frontend/tests/u1/shared/timing.test.ts` · `SeedDictionaryFileTest`가 `contracts/`와 코드를 대조한다.\n  - 아키텍처: ArchUnit(`core/arch` · `realtime/arch`), ESLint `/parent` ↔ `/device` ↔ `shared` import 경계.\n  - 끝-끝: `bash scripts/verify.sh` C안(기기 시뮬레이터 + 가짜 공급자).\n  - 인프라: `infra-validate`(Compose 불변식 · `nginx -t` · shellcheck · 마이그레이션 expand 검사), 이미지 점검, 관문 음성 대조.\n- 시험 이름 · 태그: 단위 `*Test`, 통합 `*IT`, 백엔드 유닛 태그 `@Tag(\"u1\")` ~ `@Tag(\"u7\")`와 `@Tag(\"db\")`, 프런트 · tools는 `tests/<유닛>/` 폴더.\n- 관문의 한계: PR의 `ci-gate`는 정적 · 단위 · 통합 · 범위 커버리지 · 보안 · 인프라 · 이미지까지 본다. 끝-끝 회귀(`skeleton`)는 `main` 병합 뒤에 처음 돈다. 그래서 이번 작업은 Build and Test의 전체 `verify.sh`로 병합 전 끝-끝을 확인한다.\n- 시험 · CI · 검증에서는 가짜 공급자만 쓴다(ADR-002).\n- 결과 보고는 테스트 대역 · 합성 대화 · 실제 API · 실제 기기 성공을 따로 적고, 대역 성공을 실공급자 성공으로 표시하지 않는다(`discovered-rules.md` R-M11, Q10-A).\n- 이번 작업 기록의 시험 전략은 Standard다(컴포넌트당 5 ~ 8개 + 핵심 경계 통합 시험)."
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
  "input_sha256": "sha256:d0825a12332d7eac4fb2686bef9a46d4cbb0b12cb3b7b65856a6f4756f150dfd",
  "contract_sha256": "sha256:e1d124d6fec4a695af4a7014b4056157d1238be588783bbb0fa7a1e106249b0f"
}
```
