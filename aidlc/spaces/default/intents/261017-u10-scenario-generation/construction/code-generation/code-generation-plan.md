# U10 코드 생성 계획

## Summary

- Builds: 목표를 고정한 상황 생성·별도 검토·서버 검사와 승인 대본 발급
- Touches: Core 연습·시작·Luna, core V10, 선택 응답 계약·프롬프트·U10 시험
- Tests: 구성요소별 5~8개 이상 단위 시험과 PG·HTTP·계약·권한 경계 통합 시험, U10 범위 줄 80%

## Sources

정본은 docs/units/u10/brief.md, 이 기록의 승인된 requirements-analysis/requirements.md 및 functional-design/{entities.md,rules.md,functional-spec.md}, 동반 docs/units/u10/design.md다. 설계 R-01 이름 별칭 경로와 R-02 실제 노출 최근 3건 조회를 포함한 수정안을 사용자가 승인했다. 요약 확인·계획 승인은 off이며 순차 실행이다. Unit DAG는 없으므로 Unit·source-manifest·Bolt를 만들지 않는다.

## Implementation steps

- [x] Step 1 — 현재 브랜치 unit/u10-scenario-generation, main 기준 48a132b8와 기존 변경 소유를 확인한다. 필요한 기존 Core 소스·프로필·동의·JDBC·Luna 계약과 읽기 전용 RT 수신 타입을 조사한다. R-F1~15 및 설계 범위를 지킨다. (NFR3,NFR6)
- [x] Step 2 — 기존 Gradle/JUnit5/Testcontainers와 U10 태그 필터를 확인한다. unit-test-instructions.md의 기존 ScenarioServiceTest 한정 명령을 구현 전 실행해 시험 실행기를 검증한다. 기존 main 전체 check 증거는 3,149개/실패0/기존 LOCAL skip3이다. (NFR2)
- [x] Step 3 — V10 선택 컬럼, typed 대본/장면/출처·검토 모델과 C-DS1 선택 응답 및 생성/검토 스키마를 구현한다. 기존 필수 필드·제약·과거 생성자/JSON을 유지한다. 최종 사유 코드와 프롬프트 판을 맞추고 contracts/deviations.md U10-a에 후보 운반 식별자 경계를 기록한다. (FR3,NFR4)
- [x] Step 4 — 모델/스키마를 구현한 뒤 U10ScenarioContractTest·U10ScenarioMigrationIT를 작성·실행한다. V9 fixture 보존, 구버전 INSERT, V10 선택 필드 null 및 새 모양을 검증한다. 기존 계약 대조는 고치지 않는다. (FR3.3,NFR2)
- [x] Step 5 — ScenarioRepository에 확장 필드 왕복과 recentExposed 권한·EXPOSED·시각·정렬·최근3건·문자 상한을 구현한다. 읽기 오류를 빈 이력으로 바꾸지 않는다. timeout은 전용 읽기 트랜잭션과 최초 잔여 마감을 사용한다. (BR1.3,BR2.2,BR3.3)
- [x] Step 6 — 저장소 구현 뒤 U10ScenarioRepositoryIT를 작성·실행한다. 과거/새 행 재조회, 지정 원본과 실제 노출 분리, 교차 아이·ISSUED 제외, 정렬·제한·빈 값/오류·삭제·경합을 시험한다. (FR1.3,FR2.3,FR3.3)
- [x] Step 7 — 이 층 검증 후 첫 커밋 feat(u10): 대본 저장 형식과 선택 계약 확장. 해당 소스·계약·시험과 자기 기록만 명시적으로 스테이지하고 mode change를 확인한다. push는 전체 검증 뒤다.
- [x] Step 8 — core.textai의 구조화 생성/검토 단일 호출 경계, CoreLunaClient의 스키마 강제 단일 호출 모드, LunaTextAiProvider 연결을 구현한다. 기존 CONTRACT/LEGACY 동작과 다른 유닛 LunaCallBudget3은 유지한다. (FR1.1,BR8.1)
- [x] Step 9 — Luna 단일 호출을 구현한 뒤 U10ScenarioLunaProviderTest·U10LunaSingleCallTest를 작성·실행한다. 별도 목적/스키마/프롬프트, 유효 APPROVE만 허용, 기존 경로 회귀, 오류별 내부 재시도0을 검증한다. (FR1.1,FR1.2,NFR4)
- [x] Step 10 — ScenarioScriptGenerator와 독립 서버 검사를 구현한다. 생성→별도 검토→검사→내용수정1→재검토, 기술재시도 전체1, BASE-only 검토1, 총 최대6이되 최초15초 잔여만 사용한다. 정각/늦은 결과/취소를 저장하지 않는다. (FR1,FR2,FR6,NFR1)
- [x] Step 11 — 생성/검사 구현 뒤 U10ScenarioGenerationTest·U10ScenarioValidationTest·U10ScenarioDeadlineTest를 작성·실행한다. 정상·REJECT·UNCERTAIN·누락·스키마·APPROVE+서버거절·두 번 실패·중복·반려 대본 발급0·기술재시도·대기·취소를 검증한다. (BR1,BR2,BR6.2,BR8.1)
- [x] Step 12 — 해당 층 통과 후 두 번째 커밋 feat(u10): 생성과 별도 검토 및 발급 검사 구현.
- [ ] Step 13 — 목적별 관심사 허용 정리와 ScenarioPrivacyContextPort 운영 어댑터를 구현한다. ChildProfileService 소유 경계에서 이름을 얻어 생성·검토 모두에 비공개 별칭으로 적용한다. 조회 실패·소유 불일치·유효 이름 없음은 공급자 미호출이다. 다른 AI 목적의 interests 금지 유지, 원래 이름은 저장/로그/기본 출력 금지다. (FR7,설계 R-01)
- [ ] Step 14 — 개인정보 경계 구현 뒤 U10ScenarioPrivacyTest·U10ScenarioPrivacyIT를 작성·실행한다. 관심사·목표·이력·지정 원본·검토 생성물 속 이름/연락처, 비공개·비로그·오류/다른 아이·다른 목적 금지 시험을 수행한다. (BR7,NFR6)
- [ ] Step 15 — SavedGoalReader 기준으로 기존 행동/후보 typed 고정 맥락을 FrozenGoal·WeightedGoalOrder·GoalSnapshot 및 시작 준비에 연결한다. 후보는 candidate:<candidateRef> 운반 이름 공간과 별도 kind/version/context이며 임의 행동 매핑·카탈로그 행 추가가 없다. 과거 JSON/생성자는 기존 행동 기본값이다. (FR4)
- [ ] Step 16 — 목표 연결 구현 뒤 U10FrozenGoalTest·U10SavedGoalStartIT를 작성·실행한다. 두 종류별 준비/순서/가중치/고정 버전/담기 해제/권한·동의·멱등/과거 JSON과 후보를 카탈로그 조회하지 않는 경계를 검증한다. (BR4,BR2.2)
- [ ] Step 17 — U15 ChildKnowledgeService의 구조 맥락·variantPlan 합계2초와 기본 경로, 실제 노출 이력, 변형의 같은 기술·불변·보존·거리·변경1축 검사를 연결한다. 지원하지 않는 자유 맥락을 추측하지 않는다. (FR5,FR6,설계 R-02)
- [ ] Step 18 — 연결 뒤 U10ScenarioGraphTest·U10ScenarioHistoryTest·U10ScenarioVariantTest를 작성·실행한다. 합계2초/전체15초 차감·장애/늦은 결과·graphContextUsed 진실성·최근 실제 노출 양쪽 요청 전달·변형 보정과 BASE-only 재검토를 확인한다. (BR1.3,BR5,BR6,NFR1)
- [ ] Step 19 — 해당 층 통과 후 세 번째 커밋 feat(u10): 담은 목표와 그래프 및 개인정보 입력 연결.
- [ ] Step 20 — ScenarioService 실제 구성 경로와 PreparedPracticePackageMapper/내부 HTTP 응답을 연결한다. 새 실제 Luna 구성은 구조화 흐름을 사용하고 레거시 직접 어댑터 시험은 보존한다. 첫 BASE 필수8필드와 신규 선택 필드를 저장/재조회/첫 전달에 함께 전달한다. (FR3.1,FR3.2,FR4.1)
- [ ] Step 21 — 응답 경계 구현 뒤 U10ScenarioApiIT·U10PracticePackageTest를 작성·실행한다. 현재 C8 JSON과 RT 수신 모양 대조, 후보 키 보존, 첫 대본/과거 행, 기존 U5b 경로·오류 호환·실제 구성 선택·삭제 경쟁을 검증한다. RT 운영 소스와 다른 유닛 단언은 변경하지 않는다. (BR3,BR4,NFR3)
- [ ] Step 22 — U10 태그 전체와 필요한 기존 회귀를 실행한다. backend/coverage-scope/u10.txt에 실제 U10 변경 운영 클래스를 빠짐없이 적고 새 파일/빈 범위 실패 정책을 지켜 줄80% 이상을 증명한다. 부족하면 시험을 보완한다. (NFR2)
- [ ] Step 23 — 네 번째 커밋 feat(u10): 첫 대본 전달과 기존 응답 호환 완성. 커밋 전에 명시적 파일 범위·비밀·실행 비트·기존 변경 분리를 확인한다.
- [ ] Step 24 — code-summary.md와 traceability.json을 작성한다. 현재 FR/NFR·원 AC·BR을 실제 구현/시험 경로로 연결하며 결과는 실제 실행한 근거만 기록한다. docs/units/u10/report.md에 자동 대역 결과와 미측정 실호출·실기기 한계, 편차·남은 일을 분리한다. (NFR4,NFR5)
- [ ] Step 25 — 코드 생성 종료 전 변경된 백엔드 전체 clean check를 Docker PG로 실행한다. 전체 verify.sh/보안·인프라·이미지·CI는 Build and Test에서 수행하며 여기서 완료를 선취하지 않는다. 실패를 skip/약화로 숨기지 않는다. (NFR2,NFR6)

## Execution boundaries

각 층 구현→그 층 시험 작성·실행 순서다. 프런트 변경·배포·RT 운영 변경은 없다. 사용자 mujoong-manual 스택과 볼륨을 건드리지 않는다. 테스트는 가짜 공급자만 쓰며 RUNBOOK 단계4/R-F15 확인 전 실호출하지 않는다. 비밀 값을 읽거나 채팅에 요청하지 않는다. SQL 새 제약이 필요해지면 R-M3의 별도 사람 확인이 필요하므로 더하기 컬럼 설계를 임의로 바꾸지 않는다.

검증 복사본은 /home/pdypd/mujungi-u14-verify를 사용하며 origin이 로컬 작업 저장소이므로 이 복사본에서 push하지 않는다. 소스 정본은 Windows work/mujungi이며 커밋은 원 브랜치에서만 만든다. 전체 검증 통과 후 원 저장소에서 push·main 대상 PR·필수 CI를 확인한다. U10 main 병합/배포/팀원 메시지는 이번 요청으로 실행하지 않는다.

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

