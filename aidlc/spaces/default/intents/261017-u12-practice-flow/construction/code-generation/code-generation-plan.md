# U12 새 연습 흐름 — Code Generation 계획

> 작업 기록 `261017-u12-practice-flow` · 범위 `refactor` · 깊이 Standard · 시험 전략 Standard · 브랜치 `unit/u12-practice-flow`(커밋 한국어, merge commit PR)
> 입력: 승인된 Functional Design(`construction/functional-design/` 의 `functional-spec.md` · `rules.md` · `entities.md` · `traceability.json`), `requirements.md`, 지시서 `docs/units/u12/brief.md`, CodeKB. 이 기록은 유닛 나누기를 건너뛰었으므로 `<code-generation-record>`는 `construction/code-generation/`이다.
> 한 PR로 낸다. 아래 네 묶음(A ~ D)은 **커밋 단위**이며 묶음마다 `:realtime-activity:check` · `:core-api:check`를 돌린다(마이그레이션 · 계약 키를 더하므로 둘 다).

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "team",
  "ordering": "시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.",
  "scope": "refactor",
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
  "input_sha256": "sha256:cf30d2355aca9923bc50fde838dea7e43f427b9bf61958052caae840662d7c40",
  "contract_sha256": "sha256:5d6b9d7096340e0b040d134b020e781b77463b2dd951572f4b7f4829522c435e"
}
```

## 계획 개요

- **프런트 · core-api 코드는 바꾸지 않는다**(Q3 · Q4 답 A). `Frontend behavior` 층은 해당 없음이고, 12 화면이 `progress()` 6값 접기만으로 그대로 도는 것은 realtime 쪽 매핑 시험으로 고정한다. core-api는 코드 변경 없이 `FlywayConfIT` · `SchemaPrivilegeIT` 기대값(realtime V7 사실)만 바꾼다.
- 새 코드는 새 패키지 `kr.mujoong.realtime.practiceflow`에 둔다(레거시 `practice/`와 분리, BR10.2). 실행부(`SessionExecutor`)에는 호출 지점만 더한다(NFR6).
- 시간 값은 `mujoong.timing.conversation.*`에서만 읽고(`TimeLiteralScanTest`), 새 비시간 키는 `mujoong.practice-flow.retry-max` · `reexplain-max`다(`contracts/ai-io.md` 표 + `ConfigKeyContractTest` 등록).
- 연습 끝-끝 최소 범위(요구사항 열린 질문 6)는 **realtime 통합 시험 하나(`PracticeFlowIT`, 가짜 공급자 · 가짜 제브 · 가짜 판정)로 AC1.2.3 "그거 줘 → 어느 색? → 파란색" 한 장면을 시작부터 `record.practice`까지** 보이는 것으로 정한다. 기기 시뮬레이터 · `verify.sh` 단계 확장은 하지 않고 후속 과제로 `report.md`에 적는다(사람 판단은 계획 승인에서 받는다).
- 새 비밀 환경 변수는 없다(R-M9 해당 없음). 시험 · 검증은 가짜 공급자만 쓴다(R-F1).

## 구현 단계

### 묶음 A — V7 · 시작 경로 · 설정 · 대본 읽기 (커밋 1: `feat(u12): …`)

- [x] **Step 1. 구조 · 설정 뼈대** — 새 패키지 골격, `RealtimeProperties`에 `mujoong.practice-flow.{retry-max,reexplain-max}` 추가(`application.yml` 기본 2 · 1), `mujoong.timing.conversation.*` 읽기 연결, `contracts/ai-io.md` 표에 두 키 추가, `ConfigKeyContractTest` 레코드 등록. (FR7.1 · BR9.1 · BR9.2)
- [x] **Step 2. 시험 실행기 확인** — 기존 Gradle · JUnit 실행기와 단위 한정 명령(`unit-test-instructions.md`)을 확인하고 기록한다. 새 시험 태그 `u12`와 범위 파일 `backend/coverage-scope/u12.txt`를 만든다(빈 목록 금지 규칙에 따라 구현 뒤 채운다).
- [x] **Step 3. 데이터 — 구현** — `V7__u12_practice_flow.sql`(새 표 두 개: 장면 실행 + 사실, 도움 · 역할 전환. expand 전용, V4 레거시 표 제약 불변). `infra/migration-approvals.txt`는 새 표만 더하므로 해당 없음을 확인한다. (FR4.1 · BR7.4)
- [x] **Step 4. 데이터 — 시험** — `RealtimeSchemaIT` · `RealtimeDb` 갱신, 표 · 제약 · 색인 시험(`*IT`, `u12`/`db` 태그), core-api `FlywayConfIT` · `SchemaPrivilegeIT`를 V7 사실로만 갱신, 마이그레이션 expand 검사 통과. `:realtime-activity:check` + `:core-api:check`.
- [x] **Step 5. 저장소 — 구현** — `PracticeFlowRepository`(JDBC, 기능 패키지 관례), 삭제 참여자(순서 190), 정산 복구 연결. (BR7.4)
- [x] **Step 6. 저장소 — 시험** — 저장 · 조회 · 삭제 순서 · 복구 시험(원문 칸 없음 확인 포함). (BR7.1 · BR7.4)
- [x] **Step 7. 시작 경로 — 구현** — `SessionInternalController`가 `PRACTICE` + 선택 본문 `practice`를 받고(400 기존 코드 유지), `StartSessionHandler.plan`이 원문을 `Plan.practiceJson`에 싣고, `PracticeContext`에 승인 발화 문장 지도(생성자 하나 더, 기존 생성자 유지)를 더한다. (BR1.1 · BR1.2 · D7)
- [x] **Step 8. 시작 경로 — 시험** — 거절을 단언하던 `RealtimeInternalApiIT` · `SessionInternalControllerU4bTest`를 새 사실로만 바꾸고(자유대화 단언은 그대로), 202 · `practiceJson` 도달 · 승인 발화 지도 도달 시험을 더한다.
- [x] **Step 9. 대본 읽기 — 구현 · 시험** — U10 대본 읽기 타입(`PracticeScript` 등, 레거시 `PracticeScenarioClient.Scenario`는 건드리지 않음)과 시작 패키지 `practice` 해석(`PracticeStartPackage` · `GoalSpec`), 다음 목표 대본 비동기 요청(내부 호출 `POST /internal/practice/scenarios` kind ORIGINAL). 필수 6필드 · 선택 필드 누락 정규화 · `scriptVersion` 없음 시험. (BR1.3 · FR1.3)
- [x] **묶음 A 확인** — `:realtime-activity:check` · `:core-api:check` 통과, `git diff --cached --summary` 모드 확인, 커밋.

### 묶음 B — 상태 기계 (커밋 2)

- [x] **Step 10. 전이표 · 시계 로직 — 구현** — 순수 전이표(`FlowTransitions`: 판정 지점 + 선택지 코드 + 시계 만료 표지 → 다음 단계 · 사실 · 사유, `functional-spec.md` §3.3 · §3.3a), `FlowState`(시계 만료 표지 · `handledSlotIds` · `judgmentOpen`). (BR3 · BR4 · BR6)
- [x] **Step 11. 전이표 — 시험** — 표 행마다 입력 → 결과(J2 6 · J3 4 · J4 3 · J5 7 코드 전부), 경계(9초/10초 · 90초 · 재시도 2회 · 목표 10분), 변형 4조건 거짓 각각과 종료 원인별 생략 사유. (AC1.2.x · 1.3.x · 1.4.x · 1.6.x · 1.9.x)
- [x] **Step 12. 상태 기계 — 구현** — `PracticeConversation`(출력 능력 · `requestJudgment` · 시계 · 사건 처리, 슬롯 종료 사건 중복 무시), `PracticeFlowRouter`(Factory가 만드는 얇은 확장, `onActive`에서 `scriptVersion` 분기, `controlsOutput` 항상 참), `RolePolicy` · `PracticeInstructions`(역할별 지시문, 라벨 · 확률 없음). (BR1.4 · BR3.7 · BR5.2)
- [x] **Step 13. 상태 기계 — 시험** — 가짜 출력 · 가짜 판정으로 AC1.2.3 시나리오, 판정 거절(`false`) · 보류 경로, 지시문에 라벨 · 확률 없음, 라우터 분기 두 가지, 레거시 포트가 실패를 돌려줘도 새 흐름이 끝까지 감(AC1.1.5). (BR2.5)
- [x] **묶음 B 확인** — `:realtime-activity:check`, 커밋.

### 묶음 C — 역할 정책 · 출력 · 제브 연결 (커밋 3)

- [x] **Step 14. 실행부 호출 지점 — 구현** — `PracticeSpeech`에 `generated` 성분(기존 생성자 유지, `matches()`는 생성형이면 항상 거짓), `PracticeOutput.speakGenerated`(기본 거짓) 실행부 구현(슬롯 열기 · 보류 해제 · 세대 올림 · `replayable` 지시 송신 · 실패 알림), `announceRoleSwitch`(승인 문장은 기존 `speak` 길), `sealAndGate` 분기(`functional-spec.md` §3.5), 슬롯 종료 시 `PLAYBACK_FACT` 한 번 보장, `GUARDRAIL_BLOCKED` 전달 연결. `SessionExecutor`에는 호출 지점과 얇은 분기만 더한다. (BR5.1 · BR5.4 ~ BR5.7 · FR3.2 · FR3.3)
- [x] **Step 15. 실행부 — 시험** — 제브 ALLOW 뒤 재생, BLOCK 0바이트, 제브 비활성 · 실패 폐기(fail-closed), 첫 막힘 재지시가 같은 역할 지시문, 종료 원인별 사건 한 번(PLAYED · FAILED · INTERRUPTED), 레거시 확장 폐기 단언 무변경(U5b 시험 그대로 통과). `discardForRetry`가 슬롯을 닫는지 확인하고 닫으면 재지시 때 다시 연다.
- [x] **묶음 C 확인** — `:realtime-activity:check`(U5b 시험 포함, `u5b` 범위 80% 유지), 커밋.

### 묶음 D — 기록 · 진행 표시 (커밋 4)

- [x] **Step 16. 기록 · 진행 — 구현** — `PracticeFlowRecorder`(장면 행 · 사실 · 도움 기록, 도입 재생 성공 뒤 행 생성), 기록 이벤트 `record.practice` 조립(`flowVersion = practice-conversation-v1`, `scenes[]` 필수 4필드, `facts` 15종, `liveOutcome.timing`, `variantSkipReason`, `goalRef`=`goalSelectionId`), `progress()` 6값 접기 매핑. (BR7.2 · BR8.1)
- [x] **Step 17. 기록 · 진행 — 시험** — 이벤트 본문이 `practice-record-v2.schema.json`을 통과, U14 쪽 `PracticeRecordV2` 읽기 시험이 새 형식을 받음(U14 코드 변경 없음), 모든 단계 → 6값 매핑, **표식 문자열 시험**(대화에 넣은 표식이 로그 · 기록 · 지표 · 이벤트 어디에도 없음, R-05), 시작 전 미참여 · 도입 실패에는 행 없음. (BR7.1 · BR7.3)
- [x] **Step 18. 끝-끝 최소 범위** — `PracticeFlowIT`(AC1.2.3 한 장면, 시작 요청 → 도입 → 판정 → 되묻기 → 정리 → 정산 → `record.practice`). 가짜 공급자 · 가짜 제브만 쓴다.
- [x] **Step 19. 구조 시험** — ArchUnit 규칙(새 패키지가 `practice/`를 import하지 않음, 금지 능력 · 라벨 · 확률 지시문 금지, `SessionExecutor` 호출 지점 외 로직 없음에 준하는 의존 방향). (BR10.2 · NFR6)
- [x] **묶음 D 확인** — `:realtime-activity:check`(범위 `-PincludeTags=u12 -PcoverageScope=u12` 줄 80%) · `:core-api:check`, 커밋.

### 마무리

- [x] **Step 20. 환경 · 빌드 설정** — `backend/coverage-scope/u12.txt`(실제 새 클래스 목록), 새 시험 태그 연결, `.gitleaks.toml` 변경 없음 확인, 비밀 스캔(`bash scripts/verify/a-secrets.sh`) 통과 확인(문서에 비밀 모양 리터럴 금지).
- [x] **Step 21. 문서 · 추적성** — `docs/units/u12/design.md`(Functional Design 요약 + 편차 U12-D1 ~ D3 + 레거시 사실 기록), `docs/units/u12/brief.md`의 'AC 31개'를 29개로 정정, `docs/units/README.md` 갱신, `contracts/deviations.md`에 U12-D1 ~ D3, `code-summary.md` · `traceability.json` · `source-manifest.json`. `team.md`의 낡은 마이그레이션 번호 알림은 학습 절차로만 고친다(이번 단계에서 하지 않음). (FR8.2)

## 단계 ↔ 요구사항 · 설계 추적

| 단계 | 요구사항 · 규칙 | 스토리 · AC |
|---|---|---|
| 1 | FR7.1 · BR9.1 · BR9.2 · NFR3 | — |
| 3 ~ 6 | FR4.1 · BR7.1 · BR7.4 · NFR2 | AC1.5.5(U12-D2) |
| 7 ~ 8 | FR1.1 · FR1.2 · BR1.1 · BR1.2 | 전제 조건 |
| 9 | FR1.3 · BR1.3 | — |
| 10 ~ 11 | FR2.1 ~ FR2.7 · BR3 ~ BR4 · BR6 | AC1.2.x · AC1.3.x · AC1.4.x · AC1.5.4 · AC1.6.x · AC1.9.x |
| 12 ~ 13 | FR2.8 · FR5.1 · FR5.2 · BR1.4 · BR2.5 · BR3.7 · BR5.2 | AC1.1.5 · AC1.2.3 · AC1.2.5 |
| 14 ~ 15 | FR3.1 ~ FR3.3 · BR5.1 · BR5.3 ~ BR5.7 · NFR1 | AC1.2.2 · AC1.5.1 ~ AC1.5.3 · AC1.5.6 |
| 16 ~ 17 | FR4.2 · FR4.3 · FR6.1 · BR7.2 · BR7.3 · BR8.1 | AC1.2.3 시점 기록 |
| 18 | FR8.1 | AC1.2.3 끝-끝 |
| 19 | NFR6 · BR10.2 | — |
| 20 ~ 21 | FR5.3 · FR8.2 · BR10.1 | — |

AC1.1.1 ~ AC1.1.4(참여 확인 · 거부 · 휴식)는 실행부(U4b · U11)가 이미 충족하며 기존 시험이 통과하는지 묶음마다 확인한다(회귀).

## 위험과 순서 근거

- `SessionExecutor`(4,254줄)는 호출 지점만 더하므로 묶음 C가 가장 위험하다. 묶음 A · B가 끝나 상태 기계가 가짜 출력으로 검증된 뒤에 실행부에 닿는다(묶음 C).
- 마이그레이션(V7)이 core-api 시험(`FlywayConfIT` · `SchemaPrivilegeIT`)에 닿으므로 묶음 A에서 `:core-api:check`를 함께 돌려 먼저 확인한다.
- 제브 연습 모드 호출이 처음 운영에 연결된다. 실제 키는 R-F15 확인 기록 전에는 넣지 않으며 이 계획은 가짜 제브만 쓴다.
- 이어받기: 세션을 넘겨 맡길 때는 이 계획의 체크박스 표시를 믿지 말고 커밋 기록 · 저장소 상태로 끝난 단계를 먼저 확인한다(project.md 교정).
