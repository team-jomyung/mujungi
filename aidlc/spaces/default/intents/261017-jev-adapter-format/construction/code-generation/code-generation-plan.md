# 제브 어댑터 선 형식 정정 — 코드 생성 계획

> 작업 기록 `261017-jev-adapter-format` · Code Generation · 브랜치 `unit/u13-jev-guardrail`(U13이 아직 `main`에 병합 전이라 이어서 쓴다) · 2026-10-17 · 범위 `bugfix`
> 근거: `../../inception/requirements-analysis/requirements.md`(FR1 ~ FR6, NFR1 ~ NFR6) · `requirements-analysis-questions.md`(Q1 A · Q2 A · Q3 A, 가정 14개) · 코드 스캔 `../../inception/reverse-engineering/developer-scan.md` · 요구사항 검토 `../../inception/requirements-analysis/reviews/review-01.md`(READY, Major 2건은 아래 '정한 값'에서 해결) · 제브 공식 문서 [quickstart](https://docs.typesafe.ai/introduction/quickstart) · [API](https://docs.typesafe.ai/api).
> 시험 전략 Standard, 방법 test-after(층 하나 구현 → 그 층 시험 작성 · 실행 → 다음 층), `bugfix` 바닥: 이 버그를 막는 표적 회귀 + 기존 시험 green. 모든 시험은 가짜 제브 서버만 쓴다(R-F1). 합격 증거는 테스트 대역이다(R-M11). 이 기록은 유닛 나누기를 건너뛰어 산출물을 유닛 폴더 없이 `construction/code-generation/`에 쓴다.

## Testing Contract

```json
{
  "version": 1,
  "methodology": "test-after",
  "source": "team",
  "ordering": "시험할 수 있는 층을 하나 구현하면, 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 넘어간다.",
  "scope": "bugfix",
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
      "Include a targeted regression for the bug or vulnerability.",
      "Keep the existing test suite green."
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
  "input_sha256": "sha256:0472e09ff29449982e4b808b5a2dc82ed24ac2efabd5ce0632a93bb667ffe72a",
  "contract_sha256": "sha256:1a2a45c38bef863f92c4e5d2c72925a7b8dc09155f16c9de4f07c87e0ff70c90"
}
```

## 층별 적용 (Testing Contract 대응)

| Testing Contract 층 | 이 작업에서 | 단계 |
|---|---|---|
| Project structure · production configuration | 문턱 설정 키 `mujoong.guardrail.jev.pass-threshold` · `application.yml` · `GuardrailProperties` · 시험 호출부 · 계약 문서의 키 행 | Step 1 |
| 시험 실행 준비 | 기존 `./gradlew :realtime-activity:test -PincludeTags=u13` 확인 | Step 2 |
| Data model / database behavior | 해당 없음 — 마이그레이션을 만들지 않는다(Q2 답 A). `guardrail_event` 칸 변경 없음 | — |
| Repository / data access | 해당 없음 — 기록기 · 저장소 변경 없음 | — |
| Business logic | 정책 리소스 새 판 · 질문 구성 · 선 형식 요청 · 응답 변환 · 판정 규칙 · 기동 점검 · 실호출 도구 | Step 3 ~ 8 |
| API / endpoint | 해당 없음 — 이 앱이 여는 새 HTTP · WS 경로 없음. 나가는 공급자 호출은 '외부 연동'으로 Step 5 · 6에서 시험 | — |
| Frontend behavior | 해당 없음 — 프런트 변경 없음 | — |
| Environment/build configuration | `jevLiveTest` 보고서 · RUNBOOK 문턱 설명, `:core-api:check`까지 회귀 | Step 7 · 9 |
| Documentation and traceability | 계약(`ai-io.md` · `deviations.md` · 스키마 예시) · `design.md` · `report.md` · `code-summary.md` · `traceability.json` | Step 8 · 9 |

## 정한 값 (요구사항 검토 Major 해결 · 승인된 문서는 고치지 않고 여기에 적는다)

| 번호 | 정한 값 | 근거 |
|---|---|---|
| D1 | **동률 규칙(R-01):** `choice == "PASS"`이고 `PASS` 확률이 문턱 이상이면서 **다른 모든 라벨의 확률보다 엄격히 클 때만** `ALLOW`. `PASS`가 1순위지만 다른 라벨과 확률이 같으면 `REVIEW`(사유 `PASS_TIED`). 문턱 미만이면 `REVIEW`(사유 `PASS_BELOW_THRESHOLD`). `choice`가 `PASS`가 아니면 `BLOCK`(사유 = 그 라벨, 동률이어도 `BLOCK`). `choice`가 확률 최댓값 집합에 없으면 `INVALID_JSON`. 비교는 `double`이고 최댓값 비교는 정확히 같음(`==`)이다. | 안전 쪽(자동 ALLOW 금지, NFR1), FR2.2 |
| D2 | **`state` 모양(R-02):** 객체 `{ "candidate": <후보 문장>, "context": [ { "speaker": "CHILD\|PARTNER\|COACH\|MUJOONG", "text": <문장> }, … ] }`. `context`는 최대 3개이고 없으면 빈 배열. `mode`는 보내지 않는다(운영 호출은 `FREE_TALK`뿐, 연습 모드 추가는 U12가 정한다). | FR1.2, 내부 `GuardrailRequest`의 `candidate` · `recentContext` 그대로 |
| D3 | **질문 ID(R-02):** `guardrail`(코드 상수). `questions` = `{ "guardrail": { "type": "choice", "instructions": <정책 리소스의 한국어 검사 질문>, "criteria": { <라벨>: <한국어 설명>, … 8개 } } }`. | FR1.3 |
| D4 | **정책 리소스 v2 구조:** `version` `jev-guardrail-v2`, `source`, `instructions`(문자열), `criteria`(라벨 → 설명 맵, 키 정확히 8개 = 기존 7개 범주 + `PASS`, 삽입 순서 유지), `categories`(기존 7개 이름 배열, 계약 스키마 enum 그대로), `modes`(3개). `systemPrompt`는 없다. 설명 문안은 Luna JEV 인계서 v2 §3.2의 범주별 '위험 판단 예시 · 원칙'에서, 검사 질문과 직접 인용 예외 문장은 AI 동작 명세 v0.2 §10.2에서, 맥락 규칙은 인계서 §3.2 '맥락 규칙' 불릿에서 옮긴다. | Q1 A · Q3 A, 인계서 · 명세 |
| D5 | **`policy-v1.json`:** 지우지 않고 자원에 이력으로 남기되 코드가 읽지 않는다(`LiveContentResources`는 v2만 적재). 이 사실을 `design.md`에 적는다. | Q3 A |
| D6 | **문턱:** `GuardrailProperties`에 `passThreshold`(문자열이 아니라 `double`, 기본 0.5, 허용 `0 < x <= 1`). `application.yml` `pass-threshold: ${JEV_PASS_THRESHOLD:0.5}`. REAL 모드에서 허용 범위 밖이거나 숫자가 아니면 기동 점검이 거부한다(FR3.3). STUB · OFF는 읽지 않는다. 비교는 `>=`. | FR3 |
| D7 | **확률 합 허용 오차(R-03):** 합이 1에서 `0.001` 넘게 벗어나면 `INVALID_JSON`. 근거 없는 임시값이며 `design.md` 열린 항목에 '실호출 결과에 따라 조정'으로 올린다. `confidence`는 읽지 않고 판정에 쓰지 않는다. 확률 값은 각각 `0 ≤ p ≤ 1`이어야 한다. | FR2.1, 검토 R-03 |
| D8 | **`reasonCode` · `categories` 채우기:** 막힘이면 `reasonCode` = 고른 라벨이고 `categories` = `[그 라벨]`. `REVIEW`는 `PASS_BELOW_THRESHOLD` 또는 `PASS_TIED`이고 `categories` 빈 목록. `ALLOW`는 `reasonCode` `PASS`, `categories` 빈 목록. `policyFlags`는 항상 빈 목록. `policyVersion`은 요청에 실은 판(`request.policyVersion()`). | FR2.3 |
| D9 | **오류 본문 비기록 시험(R-04):** 오류 본문에 표식 문자열을 넣은 가짜 응답으로 로그 · 판정 · 기록 · 지표에 그 문자열이 없음을 단언한다. | FR2.4, 검토 R-04 |
| D10 | **모델 이름(R-05):** 공식 예시 요청 `model`은 `jev-latest`이고 우리 규칙은 `-latest`를 거부한다. 열린 질문으로 `design.md` · `report.md`에 같은 사실을 적는다. 고정 버전(예 `jev-1.13.0`)이 요청에서 받아들여지는지는 실호출로 확인하는 열린 항목. | 검토 R-05 |
| D11 | **FR3 출처(R-06):** 문턱 설정은 앞 기록에 대응 요구가 없다 — 처음 요청에서 신설. `traceability.json`에 그대로 적는다. | 검토 R-06 |

## 단계

단계마다 `fix(u13): …` 또는 `test(u13): …`로 커밋한다(문서 단계는 `docs(u13): …`). 커밋 본문은 한국어이고 끝에 `Co-Authored-By` 줄을 둔다. 요구사항 ID는 `../../inception/requirements-analysis/requirements.md`다.

- [x] **Step 1. 구조 · 설정 뼈대: 문턱 키** (FR3.1 · FR3.2 · NFR3)
  - `GuardrailProperties`에 `passThreshold`를 더한다(D6). 정규화는 기존 규칙대로. `application.yml`에 `mujoong.guardrail.jev.pass-threshold: ${JEV_PASS_THRESHOLD:0.5}`를 둔다. 시간 키가 아니므로 `timing.md`에는 두지 않는다.
  - `contracts/ai-io.md` 10.11 설정 표에 키 행을 더하고 '임시 시험값, AI 성능 시험으로 확정'을 적는다(FR3.2). `contracts/deviations.md`에 새 편차 행(U13-c)을 더한다(R-M10). `ConfigKeyContractTest`가 이 키를 대조하도록 레코드와 yml을 맞춘다(약화 없음, 키 추가).
  - `new GuardrailProperties(...)`를 부르는 시험 호출부를 모두 맞춘다(`GuardrailConfigurationTest` · `JevGuardrailClientTest` · `JevLiveProbe` · `JevLiveTaskIsolationTest` · `GuardrailModeStartupChecksTest`).
- [x] **Step 2. 시험 실행 준비** (Testing Contract 러너 단계)
  - `sg docker -c 'cd backend && ./gradlew :realtime-activity:test -PincludeTags=u13 -Pmujoong.test.db=docker'`가 Step 1 변경 뒤에도 도는지 확인하고 정확한 명령을 `unit-test-instructions.md`에 적는다. 계약을 건드렸으므로 `:core-api:check` 확인 명령도 적는다(앞 Build and Test 교훈).
- [x] **Step 3. 비즈니스 로직 구현 1: 정책 리소스 v2 · 질문 구성 · 기동 점검** (FR1.3 · FR3.3 · FR4.1 · FR4.2)
  - `guardrail/policy-v2.json`을 만든다(D4). `LiveContentResources.GuardrailPolicy` 레코드를 `instructions` · `criteria`를 가진 모양으로 바꾸고 검증을 갱신한다: `version` · `source` · `instructions` 비어 있지 않음, `criteria` 키가 정확히 8개(`categories` 7개 + `PASS`)이고 값이 비어 있지 않음, `categories` 7개 · 중복 없음, `modes` 3개. `policy-v1.json`은 남기되 적재하지 않는다(D5).
  - 어댑터가 받는 질문 원천을 `systemPrompts` 함수(`Function<String,Optional<String>>`)에서 `Function<String,Optional<JevQuestion>>`으로 바꾼다. `JevQuestion`은 새 값 객체(`instructions`, 삽입 순서 있는 `criteria`)다. `GuardrailConfiguration`이 정책 리소스에서 `JevQuestion`을 만든다. `RealtimeRuntimeConfig`의 정책 판 연결(`withPolicyVersion`)이 새 판을 가리킨다.
  - `GuardrailStartupRules`에 문턱 규칙(REAL에서 범위 밖이면 거부)을 더한다. 기존 모드 × 프로필 표는 그대로.
- [x] **Step 4. 비즈니스 로직 시험 1** (FR1.3 · FR3.3 · FR4.x)
  - `LiveContentRegistryTest` · `U8RealtimeStartupChecksTest` · `U8ContractSchemasTest`(영향 부분)를 새 판 사실로만 바꾼다(느슨하게 하지 않음). `GuardrailConfigurationTest`의 `systemPrompt` 보조 메서드 단언을 `JevQuestion` 기준으로 바꾼다. 새 `GuardrailPolicyV2Test`(리소스 구조 · 라벨 8개 · `PASS` 포함 · 설명 비어 있지 않음 · 검사 질문에 직접 인용 예외 문장 포함)와 `GuardrailModeStartupChecksTest`의 문턱 칸(범위 밖 · 숫자 아님 · 경계 1 허용 · 0 거부 · STUB 무관)을 더한다.
- [x] **Step 5. 비즈니스 로직 구현 2: 어댑터 선 형식 · 판정 규칙** (FR1.1 · FR1.2 · FR2.1 ~ FR2.4)
  - `JevGuardrailClient`: `CHECK_PATH`를 `SYSTEMONE_PATH = "/v1/systemone"`로 바꾸고 `endpointOf`가 이를 쓴다. `requestBody`를 공식 형식으로 다시 쓴다: `{ model, state: {candidate, context[{speaker,text}]}, questions: { guardrail: {type:"choice", instructions, criteria} } }`(D2 · D3). 인증 · 재시도 없음 · 800ms · 64KiB · 리다이렉트 미추종 · 허용 호스트 확인은 그대로.
  - `convert`를 다시 쓴다: 비 2xx → `TRANSPORT_ERROR`(오류 본문은 읽지 않음). 본문을 `Map`으로 읽어 `answers.guardrail`을 검증하고(`type == choice`, `choice`가 보낸 라벨 중 하나, `probabilities`가 모든 8개 라벨을 가진 숫자 맵이며 값이 0 ~ 1, 합이 1 ± 0.001, `choice`가 최댓값 집합에 있음) 위반이면 `INVALID_JSON`. 판정 규칙(D1)과 값 채우기(D8)로 `GuardrailVerdict.allow|block|review`를 만든다. `policyVersion` 응답 비교와 `POLICY_MISMATCH` 생성을 없앤다(열거값은 호환을 위해 남기고 사용하지 않음을 Javadoc에 적음). `CATEGORIES`는 라벨 8개(`PASS` 포함)로 정리하고 `GuardrailVerdict.categories`는 막힘일 때만 7개 범주 중 하나.
  - 어댑터 안의 숫자 상수는 시간 값이 아니다. `.orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS)` 줄은 바꾸지 않아 `TimeLiteralScanTest` 허용 항목을 그대로 둔다(바꾸면 항목도 함께 고친다).
- [x] **Step 6. 비즈니스 로직 시험 2: 표적 회귀** (FR1.x · FR2.x · FR6.1 · FR6.2 · NFR1 · NFR2)
  - `JevGuardrailClientTest`를 공식 형식 기준으로 다시 쓴다. 표적 회귀: 경로가 `/v1/systemone`이고 본문 최상위 키가 정확히 `model` · `state` · `questions`이며 옛 키(`candidateText` · `systemPrompt` 등)가 없다(이 시험은 옛 어댑터에서는 실패한다). 그 밖에: 요청 `questions.guardrail`의 라벨이 정확히 8개이고 `PASS` 포함 · `state` 필드 집합 · 아이 이름 · 식별자 없음 · 지시문 격리, 응답 → 판정(ALLOW · 문턱 0.5 직전 · 정확히 0.5 · 직후 · `PASS` 1순위 동률 → REVIEW · 다른 라벨 1순위 → BLOCK · 라벨 동률도 BLOCK), 비정상 응답 12종 이상(비JSON · 배열 · null · `answers` 없음 · 질문 답 없음 · `type` 아님 · `choice`가 보낸 라벨 아님 · `probabilities` 없음 · 값 범위 밖 · `PASS` 없음 · 합 오차 · `choice`가 최댓값 집합 밖) → `INVALID_JSON`, 상태 400 · 401 · 413 · 415 · 422 · 429 · 500 · 529와 리다이렉트 → `TRANSPORT_ERROR` · 호출 1회, 시간 초과 · 마감 경계(수동 시계), 보내기 전 거절(빈 키 · 모델 규칙 · 허용 밖 호스트 · 질문 없음), 키 비노출, 오류 본문 표식 비노출(D9). 공식 문서의 예시 응답 모양(`model` · `answers.<id>.choice` · `probabilities` · `confidence` · `usage`)을 한 시험에 그대로 넣어 형식을 못 박는다.
  - `TimeLiteralScanTest` 허용 목록 · `GuardrailRequestFactoryTest`(영향 부분)를 새 사실로만 맞춘다. `GuardrailTurnIT` · `GuardrailCoordinatorTest` 등 내부 형식 소비자 시험은 단언 변경 없이 통과해야 한다.
- [x] **Step 7. `jevLiveTest` 갱신** (FR5.2 · NFR2 · NFR6)
  - `JevGuardrailClient`에 읽기 전용 메서드 `inspect(request, deadline)`를 더한다. 돌려주는 값 `Inspection`은 `verdict`(기존 `GuardrailVerdict`) · `choice`(고른 라벨 또는 null) · `passProbability`(`PASS` 확률 또는 null) · `responseModel`(응답의 `model` 또는 null)이다. `check`는 `inspect(...).verdict()`와 같은 결과를 내고 시그니처는 그대로다. 확률 · 모델은 `GuardrailVerdict` · `guardrail_event`에 넣지 않는다(Q2 답 A).
  - `JevLiveProbe`가 `inspect`를 통해 새 형식으로 호출하고 보고서에 판정 · `choice` · `PASS` 확률 · 응답 `model` · 지연만 남긴다(후보 문장 · 키 · 주소 없음, 임시 문턱 0.5 표시). 키가 없으면 지금처럼 건너뛴다.
  - `JevLiveTaskIsolationTest`(분리 · 표본 5개 한 번씩 · 보고서 위생)를 새 서명에 맞추고 통과시킨다. `verifyJevLiveIsolation`은 그대로.
- [x] **Step 8. 계약 · 문서** (FR5.1 · FR5.3 · NFR6)
  - `contracts/ai-io.md`: 공급자 선 형식을 한 절로 적는다(경로 · 인증 · `state` · `questions` · 응답 · 판정 규칙 D1 · 문턱 · 오류 매핑 · 열린 항목). `contracts/deviations.md` (U13-c) 행을 채운다. `contracts/schemas/guardrail-request.schema.json` · `guardrail-result.schema.json` 예시를 정정하고 설명에 '내부 형식'임을 유지한다. `ConfigKeyContractTest` · `ContractFilesTest` · `U8ContractSchemasTest`가 통과해야 한다.
  - `docs/units/u13/design.md` · `report.md`의 선 형식 · 열린 항목 문장과 `infra/RUNBOOK.md` 8.8절(`JEV_PASS_THRESHOLD`, 임시 시험값 표시)을 새 형식에 맞춘다. 앞 기록의 승인된 설계 문서는 고치지 않는다.
- [x] **Step 9. 회귀 확인 · 문서 · 추적** (FR6.2 · NFR4 · NFR5)
  - `sg docker -c 'cd backend && ./gradlew :realtime-activity:clean :realtime-activity:check -Pmujoong.test.db=docker'`와 `sg docker -c 'cd backend && ./gradlew :core-api:check -Pmujoong.test.db=docker'`를 돌려 모두 통과시킨다. `backend/coverage-scope/u13.txt`에 새 운영 파일(`JevQuestion` 등)이 있으면 더하고 u13 범위 줄 커버리지가 80% 이상인지 확인한다(낮추지 않는다).
  - `code-summary.md`(만든 · 고친 파일, 결정 D1 ~ D11 이행, 계획 대비 편차, 시험 요약), `source-manifest.json`, `traceability.json`(FR1.1 ~ FR6.2 · NFR1 ~ NFR6 · 처음 요청 항목)을 쓴다.

## 요구사항 → 단계 추적

| 요구 | 단계 | 요구 | 단계 |
|---|---|---|---|
| FR1.1 | 5 · 6 | FR4.2 | 3 · 4 |
| FR1.2 | 5 · 6 | FR5.1 | 8 |
| FR1.3 | 3 · 4 · 5 · 6 | FR5.2 | 7 |
| FR2.1 | 5 · 6 | FR5.3 | 8 |
| FR2.2 | 5 · 6 | FR6.1 | 6 |
| FR2.3 | 5 · 6 | FR6.2 | 4 · 6 · 9 |
| FR2.4 | 5 · 6 | NFR1 | 5 · 6 |
| FR3.1 | 1 | NFR2 | 5 · 6 · 7 |
| FR3.2 | 1 · 8 | NFR3 | 1 · 5 |
| FR3.3 | 3 · 4 | NFR4 | 9 |
| FR4.1 | 3 · 4 | NFR5 | 2 · 9 |
| | | NFR6 | 7 · 9 |

## 알려진 위험

- 실제 제브 응답과 지연은 아직 모른다. 공식 문서의 예시 응답 모양을 시험 하나로 못 박고, 오류 본문 · 고정 버전 모델 요청 · 지연은 실호출 확인(`jevLiveTest`, 사람 몫)으로 남긴다.
- 한국어 검사 질문 · 라벨 설명이 제브에서 얼마나 잘 분류되는지는 실호출 · AI 성능 시험으로만 안다. 문턱 0.5는 임시 시험값이다.
- 정책 리소스 구조 변경이 `LiveContentRegistryTest` · `U8RealtimeStartupChecksTest` · `U8ContractSchemasTest`에 번진다. 단언은 새 사실로만 바꾸고 느슨하게 하지 않는다.
- 이 변경은 `contracts/`를 건드리므로 `ci-gate` 전 작업이 돈다(R-M10).
- 앞 기록 교훈: 계약 · 설정 키를 건드리면 `:realtime-activity:check`만이 아니라 `:core-api:check`도 돌린다.

## Sources

- `../../inception/requirements-analysis/requirements.md` · `requirements-analysis-questions.md` · `reviews/review-01.md`
- 코드 스캔 `../../inception/reverse-engineering/developer-scan.md`, 공유 CodeKB `aidlc/spaces/default/codekb/mujungi/`
- 제브 공식 문서: https://docs.typesafe.ai/introduction/quickstart · https://docs.typesafe.ai/api
- 정본 문서: AI 동작 명세 v0.2 §10.2, 기술명세서 v0.3 §5.7, Luna JEV 인계서 v2 §3.1 ~ §3.3
- 앞 기록 `aidlc/spaces/default/intents/261017-u13-jev-guardrail/`(요구사항 · 설계 규칙 BR · `test-results.md` 4절 교훈)
- 팀 규칙 `team.md` · `project.md`(R-F1 · R-F2 · R-F3 · R-F5 · R-F6 · R-F7 · R-F9 · R-F13 · R-F14 · R-F15 · R-M5 · R-M9 · R-M10 · R-M11)
