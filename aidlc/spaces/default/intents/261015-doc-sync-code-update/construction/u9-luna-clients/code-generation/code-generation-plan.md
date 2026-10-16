# U9 Luna 호출부 — 코드 생성 계획

> 근거: `../functional-design/functional-spec.md` · `rules.md`(BR1 ~ BR9) · `entities.md`, `../nfr-requirements/security-requirements.md`(NFR1.1 ~ NFR6.1) · `tech-stack-decisions.md`, `../nfr-design/security-design.md`(SD1 ~ SD12) · `logical-components.md`, `../infrastructure-design/cicd-pipeline.md`, `../../../inception/contract-design/contract-summary.md`(C-DS4 ~ C-DS7), `../../../inception/units-generation/unit-of-work.md`(U9), `../../../inception/requirements-analysis/requirements.md`(FR1 · FR12.1 · NFR3 · NFR5).
> 브랜치: U8이 아직 `main`에 병합되지 않았으므로, `unit/u8-contracts-catalog`의 끝 커밋(지금 `feat/doc-sync-code-update`와 같은 내용)에서 `unit/u9-luna-clients`를 딴다. 병합 순서는 U8 → U9다(team.md: 의존 순서대로). 작업 트리의 `aidlc/` 미커밋 변경은 그대로 따라온다.
> 스토리는 US2.4(AC2.4.1 ~ AC2.4.3)이고, AC2.3.3 · AC2.3.4와 FR1.1 ~ FR1.4 · FR12.1을 단계에 잇는다.
> 방법론은 test-after다. 시험할 수 있는 층 하나를 구현하면 바로 그 층의 시험을 쓰고 돌린 뒤 다음 층으로 간다.

## 단계

### 준비

- [x] **Step 1. 브랜치와 기준선** — `unit/u8-contracts-catalog`에서 `unit/u9-luna-clients`를 딴다. 바꿀 쪽(contract-model · core-api · realtime-activity · test-support)의 기존 시험을 한 번 돌려 기준선(통과 · 실패 · 건너뜀 수)을 `code-summary.md`에 적는다(brownfield 기준선, NFR6.1). 커밋 전에는 `git diff --cached --summary`로 실행 권한 변경이 없는지 본다(project.md 교정).
- [x] **Step 2. 시험 실행 확인** — `unit-test-instructions.md`의 U9 범위 명령(`-PincludeTags=u9`)이 이 PC에서 도는지 확인한다. U9는 DB를 쓰지 않으므로 Docker 없이 돈다. Docker가 있으면 바꾼 쪽 전체 검사(`clean check -Pmujoong.test.db=docker`)도 기준선과 같이 돈다.

### 층 1 — 로그 계약(데이터 모델) (SD6, NFR1.2 · NFR3.5 · NFR4.1, BR6)

- [x] **Step 3. 로그 계약 묶음 구현(한 커밋)** — contract-model에 다음을 더한다.
  - `EventCode.LUNA_CALL`(허용 속성: `lunaPurpose` · `lunaOutcome` · `model` · `responseModel` · `promptVersion` · `sentFields` · `latencyMs` · `attempt`)과 `EventCode.LUNA_STARTUP`(`lunaState`).
  - `AttrKey`에 위 키들. `lunaPurpose` · `lunaOutcome` · `lunaState`는 ENUM(값 목록은 security-design SD6 표), `latencyMs` · `attempt`는 NUMBER.
  - `AttrKey.ValueType`에 `LABEL`(`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`)과 `NAME_LIST`(쉼표로 이은 `[A-Za-z][A-Za-z0-9_]{0,63}`, 최대 32개)를 더하고, 포매터 검증에서 어긋나면 `UNKNOWN`으로 바꾼다. 기존 사건 · 속성은 바꾸지 않는다.
  - `EventLog.Component.LUNA_CLIENT`를 core · realtime 쪽에 하나씩 더한다.
  - 같은 커밋: `infra/bin/log-hygiene-check`의 `ALLOWLIST_JSON`에 두 사건, `infra/ci/test-host-tools.sh` 대조 사례, `infra/RUNBOOK.md` 로그 사건 절, `contracts/ai-io.md` 10절 감사 로그 속성 표(R-M10, cicd-pipeline 1절 5행).
- [x] **Step 4. 로그 계약 시험 작성 · 실행** — contract-model 시험(`@Tag("u9")`): LABEL · NAME_LIST 정상 값 통과, 형식 위반(하이픈으로 시작 · 공백 · 33개 · 한글) → `UNKNOWN`, `LUNA_CALL`에 허용 밖 속성은 받지 않음, 기존 `EventLog` 시험 그대로 통과. `bash infra/ci/test-host-tools.sh`와 `infra/bin/infra-validate`(shellcheck 포함) 통과.

### 층 2 — 설정 · 빌드 (BR1.4 · BR1.5 · BR9.3, SD3 · SD8 · SD12, NFR3.4 · NFR5.1 · NFR5.4)

- [x] **Step 5. 설정 · 호스트 규칙 구현**
  - 두 앱에 `LunaProperties`(`mujoong.luna.model` · `base-url` · 목적별 `max-output-tokens` · `reasoning-effort` · `max-response-bytes`)와 `application.yml` 값. 모델은 env `OPENAI_ANALYSIS_MODEL`(core) · `OPENAI_JUDGE_MODEL`(realtime) 자리표시, 기본 `gpt-6-luna`. 시간 값 아님.
  - 두 앱 `ConfigKeyContractTest`의 레코드 목록에 `LunaProperties`를 더한다(SD12). `contracts/ai-io.md` 10절에 키 · 기본값 표.
  - 두 앱 `..luna.LunaHosts`: 허용 호스트 상수와 `check(uri, mode)`(STRICT · DEV · TEST, 그 밖은 STRICT).
- [x] **Step 6. 빌드 구성 구현**
  - core-api · realtime-activity `build.gradle.kts`: `json-schema-validator`를 `testImplementation` → `implementation`(`libs.versions.toml`은 그대로), `processResources`가 `contracts/schemas/ai-*.schema.json`을 `luna/schemas/`로 복사.
  - `backend/build-logic` `mujoong.java-conventions`: 기본 `test` 작업에만 `luna-live` 고정 제외(공통 `tasks.withType<Test>()` 블록이 아니라 `tasks.named<Test>("test")`), 고정 제외 값을 `-PexcludeTags`와 함께 작업 입력으로 잡음. 새 `Test` 작업 `lunaLiveTest`(같은 시험 소스, `luna-live`만 포함, `check` · `build` 미연결, 보고서 `build/luna-live/report.txt`).
  - 빌드 구성 확인: 루트 빌드에 Gradle 확인 작업 `verifyLunaLiveIsolation`을 두고 `check`가 의존하게 한다. 세 가지를 단언한다 — `check` 작업 그래프에 `lunaLiveTest` 없음, 기본 `test` 제외 목록에 `luna-live` 있음, `lunaLiveTest` 포함 목록 = `luna-live`. (build-logic에는 시험 디렉터리가 없어 포함 빌드 시험이 CI 127행에서 돌지 않을 수 있으므로, `check`에 물리는 확인 작업으로 둔다 — 인프라 검토 R-05.)
- [x] **Step 7. 설정 · 빌드 시험 실행** — `LunaHosts` 시험(모드별 허용 · 거부, 프로필 없음 → STRICT, 호스트 상수 = `ai-io.md` 값), 두 앱 `ConfigKeyContractTest` · `TimeLiteralScanTest` 통과, 실행 자원 `luna/schemas/*`가 `contracts/schemas` 원본과 같음, `./gradlew verifyLunaLiveIsolation` 통과. 의존성 이동 직후 bootJar trivy `rootfs` HIGH · CRITICAL 스캔을 로컬로 한 번 돌려 결과를 `code-summary.md`에 적는다(SD9). 걸리면 같은 메이저 안 수정판으로 올리고, 없으면 `ci/security-exceptions.yml` 승인 절차(R-M7)를 거치며, 승인 전에는 병합하지 않는다.

### 층 3 — 업무 로직(호출부) (BR2 ~ BR6, SD1 ~ SD7, NFR1.1 · NFR1.3 · NFR2.1 · NFR3.1 ~ NFR3.10)

- [x] **Step 8. 시험 대역 구현** — `test-support`에 `kr.mujoong.testsupport.luna.LunaResponsesStub`(JDK `HttpServer` 루프백, `ContractStubServer`와 같은 방식): 응답 대본(목적별 정상 · 스키마 위반 · 빈 출력 · 미완료 · 거절 · 모델 없음 · 429 + `Retry-After` · 500 · 401 · 302 · 지연 · 느린 본문 · 1MB 넘는 본문), 요청 검사 모드(요청용 스키마에 비지원 키워드 · `temperature` 있음 · `store` 없음 → 400), 받은 요청 본문 보관. 운영 코드는 이 모듈을 참조하지 않는다(R-F8).
- [x] **Step 9. core 호출부 구현** — `kr.mujoong.core.luna`:
  - `LunaSchemas`(실행 자원 적재, 목적별 스키마 짝: 검증용 원본 · 요청용 사본(허용 키워드만) · strict 판정, 문자열 입력 검증, 실패 시 기동 거부),
  - `LunaInputSanitizer`(금지 키 제거 · 별칭 · 연락처 가림 · 잔존 확인 · 보낸 필드 이름),
  - `LunaTransport`(공유 `HttpClient` Redirect NEVER · 프록시 없음, 보내기 직전 `LunaHosts.check`, env 키 헤더, 바이트 상한 수신, `orTimeout` + 취소),
  - `CoreLunaClient`(동기 `call(LunaRequest)`: 시한 = min(마감, 목적 키), 재시도 분류 `NONE` · `REQUEST_BOUNDED` · `JOB_ATTEMPT`, 대기 = max(backoff, Retry-After), 응답 분류 순서, 원본 스키마 검증, 시도마다 `LUNA_CALL`, 결과 · 실패),
  - `LunaKeyPresentCondition` · `LunaKeyAbsentCondition`, `LunaConfiguration`(키 있음일 때만 빈, `LUNA_STARTUP` 기록).
  - 레코드 `toString`은 `[redacted]`, 예외 메시지는 사유 코드만, 시각은 `MujoongClock`, 숫자 시간 값 없음.
- [x] **Step 10. core 호출부 시험 작성 · 실행** — `@Tag("u9")`, 컴포넌트마다 5 ~ 8개:
  - `LunaInputSanitizer`: 중첩 금지 키 제거, 별칭 치환, 연락처 가림, 잔존 시 `FORBIDDEN_INPUT` · 대역 요청 0건, 필드 이름 목록.
  - `LunaSchemas`: 계약 스키마마다 요청용 사본에 비지원 키워드 없음(대역 거절 모드 통과), strict 판정, 원본 검증이 길이 · 범위 · 패턴 위반을 잡음, 파일 없음 → 기동 거부.
  - `LunaTransport`: 302 → `REQUEST_REJECTED` · 다른 호스트 요청 0건, 1MB 초과 → `LENGTH_EXCEEDED`, 느린 본문 → `TIMEOUT`, 키 헤더가 로그 · 예외에 없음.
  - `CoreLunaClient`: 정상, 미완료 · 빈 출력 · 거절 · 모델 없음 · 스키마 위반 분류, `REQUEST_BOUNDED` 일시 실패 → 기술 재시도 1 · 스키마 위반 → 재요청 1 · 합계 ≤ 3, 남은 시간 ≤ 대기면 재시도 없음(429 큰 `Retry-After`), 401 재시도 없음, `JOB_ATTEMPT` · `NONE` 1회, `store=false` · `temperature` 없음 · 지시/데이터 자리 분리(대역 요청 검사).
  - 기록: `LogCapture`로 시도마다 `LUNA_CALL` 한 줄, 속성 = 허용 목록, 대역 본문 · 별칭 원래 이름 · 가짜 키 문자열 0건(`anyLineContains`).
  - 컨텍스트: 키 있음 → `TextAiProvider` · `ResultTextProvider` 빈이 각각 하나이고 Luna 어댑터, 키 없음 · 공백 → 실패 기본 빈, 시험 대역 빈을 넣으면 대역이 이김(키 있음 + 대역 조합도 하나만 남는지 확인, 필요하면 대역에 `@Primary`).
- [x] **Step 11. realtime 호출부 구현** — `kr.mujoong.realtime.luna`: Step 9와 같은 컴포넌트를 realtime 쪽에 따로 두고(코드 공유 없음, R-F5), `RealtimeLunaClient`는 `CompletionStage<LunaResult>`를 돌려준다. 목적은 `JUDGMENT_LIVE`(`NONE`, 요청 스키마 `ai-judgment-request` 검증), 시한 키는 `mujoong.timing.judgment.live-budget`. `JudgeClient` 포트 · 구현은 U11 몫이라 만들지 않는다. 키 있음일 때만 `RealtimeLunaClient` 빈, 기동 사건 기록.
- [x] **Step 12. realtime 호출부 시험 작성 · 실행** — `@Tag("u9")`, Step 10과 같은 묶음을 realtime 쪽으로(비동기 완료 · 시한 초과 시 요청 취소 · 요청 스키마 위반 시 미호출 포함). 기존 `RealtimeStartupChecks` 시험 그대로 통과.

### 층 4 — 기존 인터페이스 어댑터(API 경계) (FR1.3, BR3.4 · BR7 · BR8, SD11, AC2.3.3 · AC2.4.1 ~ AC2.4.3)

- [x] **Step 13. 어댑터 구현** — core `kr.mujoong.core.luna.adapter`:
  - `LunaTextAiProvider`(`TextAiProvider`): `ai-io.md` 7절 상황 생성 지시 + `ScenarioPrompt` 입력 → `SCENARIO_GENERATION` · `json_object` 1회 → `GeneratedScenario`(`modelVersion` = 응답 모델, `promptVersion` = 보낸 판). 실패 → BR8.1 결정표.
  - `LunaResultTextProvider`(`ResultTextProvider`): `RESULT_TEXT` · `json_object` 1회, `temperature` 무시, 출력 문자열 반환. 실패 → BR8.2 결정표.
  - `TextAiConfiguration.unavailableTextAiProvider` · `ResultTextConfiguration.unavailableResultProvider`에 `LunaKeyAbsentCondition`을 더한다(`@ConditionalOnMissingBean`은 그대로). `TextAiGateway` · `ResultTextGateway`(sanitize · restore · 인용 대조) · `OutputChecker` 흐름은 바꾸지 않는다.
- [x] **Step 14. 어댑터 · 경계 시험 작성 · 실행** — `@Tag("u9")`:
  - 통합: 루프백 대역 → `TextAiGateway` → `ScenarioService` 정상 · 결정표 사유마다 재시도 여부, 대역 → `ResultTextGateway` → `OutputChecker` 정상 · 스키마 실패 · `goalSelectionId` 참조 복원(AC2.4.2 · AC2.4.3 경로 유지).
  - ArchUnit(`core/arch` · `realtime/arch`): `..luna..`가 `..guardrail..` · `org.slf4j..` · `ch.qos.logback..` · `java.util.logging..` · `org.apache.logging..` · 상대 앱 · `tools.jackson..`에 의존하지 않음, `System.out/err` 접근 없음, realtime `..luna..`가 `java.net.http.WebSocket` · `ProviderClient`에 의존하지 않음(AC2.4.1 · NFR3.8).
  - 기존 `TextAiGateway` · `ResultTextGateway` · `OutputChecker` · `PracticeStartApiIT` 시험 그대로 통과(NFR6.1).

### 층 5 — 화면

- (해당 없음) 새 화면 · 프런트 변경이 없다.

### 마무리

- [x] **Step 15. 실호출 시험 작성** — 두 앱 시험 소스에 `@Tag("luna-live")` 사례: 프로필 없이 호출부를 직접 만들고 STRICT 주소 규칙을 쓴다. 키가 없으면 `SKIPPED_NO_KEY`(통과로 보고하지 않음). 키가 있으면 먼저 목적별 요청용 스키마 사본이 거절되지 않는지 · `temperature` 없이 받아들여지는지 확인하고, 목적별 합성 자료 1건씩 호출해 스키마 검증 · 응답 모델 · 지연을 `build/luna-live/report.txt`에 남긴다(원문 없음). 이 PC에서 키 없이 `./gradlew lunaLiveTest`를 돌려 SKIPPED 동작만 확인한다(실호출은 사람이 키를 넣고 돌린다).
- [x] **Step 16. 계약 · 운영 문서** —
  - `contracts/ai-io.md` 10.2: "'확인됨' 상태를 코드에서 판정하는 방법은 U9가 정한다(BR5.5)" → "U9는 코드로 판정하지 않고 RUNBOOK 확인 기록으로 지킨다(편차 U9-b)". 같은 커밋에서 `U8ContractSchemasTest`의 R-F15 문장 대조를 새 문장에 맞춘다(R-M10, 대조 시험 자체를 끄지 않음).
  - `contracts/ai-io.md` 10절: 호출 방식(Responses API 직접 · `store=false` · 요청용 사본 · 원본 검증), 실패 사유 코드 표, 기존 어댑터 1회 규칙, 설정 키 표(Step 5).
  - `contracts/deviations.md` 새 절 'U9': `U9-a`(openai-java → JDK `HttpClient`), `U9-b`(R-F15 운영 절차), `U9-c`(`lunaLiveTest` 선택 실호출).
  - `infra/RUNBOOK.md`: Parameter Store 단계 4의 `OPENAI_API_KEY` 바로 앞에 R-F15 체크 항목과 확인 기록 표(확인자 · 날짜 · 약관 판 · 환경), demo 투입 순서(cicd-pipeline 4절)와 롤백(같은 SHA `DEPLOY` 재실행), `lunaLiveTest` 사용법.
- [x] **Step 17. 범위 파일 · 전체 확인** — `backend/coverage-scope/u9.txt`에 U9가 만들거나 고친 운영 Java 파일을 적는다(빈 목록 금지, J137). 바꾼 쪽 전체 검사 `./gradlew clean check -Pmujoong.test.db=docker`(Docker가 없으면 DB 통합 시험 제외 사유를 적음)와 U9 범위 줄 80%를 확인한다. 하한 · 대조 시험 · ArchUnit은 낮추거나 건너뛰지 않는다(R-F2). 프런트 · tools는 바꾸지 않으므로 돌리지 않는다.
- [x] **Step 18. 문서 · 추적** — `code-summary.md`(파일 · 결정 · 시험 · 편차 · 기준선 · trivy 결과 · 증거 종류 구분), `source-manifest.json`(만들거나 고친 모든 경로), `traceability.json`(AC2.3.3 · AC2.3.4 · AC2.4.1 ~ AC2.4.3, NFR1.1 ~ NFR6.1, BR1.1 ~ BR9.3 → 구현 · 시험 파일). 커밋 메시지는 `<종류>(<범위>): <한국어 요약>` 형식.

## 단계 → 근거 대응

| 단계 | 근거 |
|---|---|
| Step 3 · 4 | BR6.1 ~ BR6.3, NFR1.2 · NFR3.5 · NFR4.1, SD6, R-M10 |
| Step 5 ~ 7 | BR1.4 · BR1.5 · BR9.3, NFR3.4 · NFR3.10 · NFR5.1 · NFR5.4, SD3 · SD8 · SD9 · SD12 |
| Step 8 | BR9.1, AC2.3.4, NFR5.3 |
| Step 9 ~ 12 | FR1.1 · FR1.2 · FR1.4, BR2 ~ BR6, NFR1.1 · NFR1.3 · NFR2.1 · NFR3.1 ~ NFR3.10, SD1 ~ SD7 |
| Step 13 · 14 | FR1.3, AC2.3.3 · AC2.4.1 ~ AC2.4.3, BR1.1 · BR1.2 · BR3.4 · BR7 · BR8, NFR2.2 · NFR3.8 · NFR6.1, SD7 · SD11 |
| Step 15 | FR12.1, BR9.2, NFR5.2, 편차 U9-c |
| Step 16 | BR1.7, NFR3.7, SD10, 편차 U9-a · U9-b · U9-c, R-M10 |
| Step 17 · 18 | NFR5.3, R-F2 |

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
