> 현재 범위의 정본은 바로 아래 "제브 어댑터 선 형식 집중 갱신" 절이다(`backend/realtime-activity` · `contracts` · `docs/units/u13` 한정, 2026-10-18, HEAD `421e1ad`). 그 뒤 구현 전 U13 절 · U14 절 · U15 절 · 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## 제브 어댑터 선 형식 집중 갱신 — 품질 · 위험 · 부채 (261017-jev-adapter-format, 2026-10-18)

이 스캔은 읽기만 했다. 앱 · 시험 · 외부 공급자는 실행하지 않았고 이번 시험 통과율 · 커버리지 수치는 새로 측정하지 않았다. 개발자 스캔이 인용한 U13 보고서 수치(realtime-activity 1,641개 통과, U13 태그 169개)는 **보고서의 주장이며 이번에 재현하지 않았다**. 아래는 사실이고 해법은 정하지 않는다(설계는 이후 단계 몫). 증거 구분(R-M11): 테스트 대역(`FakeJev` 루프백 서버 · `FakeGuardrailClient`) 성공만 있고, 실제 API · 실제 기기 · 합성 대화 실호출 성공 증거는 없다.

### 시험 · 검사 현황 (제브 쪽)

- 어댑터 시험 `JevGuardrailClientTest`(445줄, `@Tag("u13")`, 루프백 JDK `HttpServer` `FakeJev` + `LogCapture`)의 시나리오 11개. 형식 결합 강도는 아래 표.
- 팩토리 시험 `GuardrailRequestFactoryTest`(190줄, 10개)는 내부 형식만 보며 공급자 선 형식과 무관하다.
- 실호출 도구: `JevLiveProbe`(225줄, 합성 표본 5개: 허용 기대 1 · 막힘 기대 4) · `JevLiveTest`(29줄, 태그 `jev-live`, `REACHED`인지만 단언 — 분류 품질은 합격 조건이 아님) · `JevLiveTaskIsolationTest`(193줄). `backend/build.gradle.kts`의 `verifyJevLiveIsolation`이 `jev-live` 태그를 기본 `test` · `check`에서 빼고, `jevLiveTest`는 그 태그만 포함하며, 기본 test는 `JEV_API_KEY`를 시험 JVM에 넘기지 않는다(얕게 확인). U13 보고서: `jevLiveTest`는 키 없이 `SKIPPED_NO_KEY`까지만 확인됐다.
- ArchUnit: `GuardrailPackageArchTest`(패키지 의존 경계), `LunaArchTest`(`..luna..` → `..guardrail..` 금지), `TimeLiteralScanTest`(숫자 시간 값 금지; 어댑터 `.orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS)` 줄이 문자열 허용 항목, 91줄).
- 커버리지: 모듈 줄 80%(JaCoCo)는 CI가 막는다. CI가 막는 유닛 범위 파일은 U5b · U7뿐이다(`team.md`).

### 선 형식에 얼마나 묶였는가 (`JevGuardrailClientTest`)

| # | 시험 | 결합 |
|---|---|---|
| 1 | `anAllowAnswerIsConvertedAndTheRequestShapeIsPinned` | 강함: 경로 `CHECK_PATH`, Bearer 헤더, 본문 키 정확히 6개와 각 값, 응답 `answer(...)` 형식 |
| 2 | `blockAndReviewAnswersKeepCategoriesFlagsAndReasonCodes` | 강함: 응답의 `categories` · `policyFlags` · `reasonCode` · `reviewNeeded` 그대로 옮김 |
| 3 | `theContractExampleOfTheResultSchemaIsTheAssumedResponseFormat` | 강함: `guardrail-result.schema.json` 첫 예시를 공급자 응답으로 전송 |
| 4 | `aSlowAnswerEndsAsTimeoutWithOneCallAndNoRetry` | 약함: `allowBody()` 빌림, 단언은 TIMEOUT · 호출 1회 |
| 5 | `theDeadlineBoundaryUsesTheInjectedClockAndTheSmallerOfDeadlineAndBudget` | 약함: `allowBody()`만 빌림 |
| 6 | `transportFailuresAreBlockingResultsWithoutRetryAndRedirectsAreNotFollowed` | 약함: 500 · 429 · 리다이렉트(`CHECK_PATH` 사용) · 64KiB 초과 · 닫힌 서버 |
| 7 | `malformedAnswersAreInvalidJsonNotAllow` | 강함: 12개 나쁜 본문이 모두 가정 형식 기준 |
| 8 | `aDifferentPolicyVersionInTheAnswerIsAPolicyMismatchEvenForAnAllow` | 강함: 응답 `policyVersion` 비교 가정 |
| 9 | `itRefusesToSendWhenTheKeyIsBlankTheModelIsAMovingAliasOrThePolicyHasNoPrompt` | 약함: 요청 전 거절 |
| 10 | `aHostOutsideTheAllowListIsRejectedRightBeforeSendingEvenWithAWorkingServer` | 약함: 호스트 규칙 |
| 11 | `theKeyIsReadPerCallAndItsValueNeverAppearsInTheBodyTheResultsOrTheLogs` | 중간: 본문 문자열에 키가 없음(본문 형식이 바뀌어도 성립) |

그 밖에 어댑터 생성자 서명(`GuardrailProperties, Mode, Supplier, Function<String,Optional<String>>, Duration, MujoongClock, JsonCodec`)에 묶인 시험: `GuardrailConfigurationTest`(`systemPrompt` 보조 메서드 `Optional<String>`에도 묶임), `JevLiveProbe`, `JevLiveTaskIsolationTest`. 7개 범주에 묶인 단언: `LiveContentRegistryTest` · `startup/U8RealtimeStartupChecksTest` · `contract-model`의 `U8ContractSchemasTest`(`guardrail-result`의 잘못된 예 `RUDENESS` 범주 · 긴 `reasonCode` 거절). 정책 리소스 구조를 바꾸면 영향이 크다.

### 위험 · 부채 (JEV-F1 ~ JEV-F12, 사실만 — 해법을 정하지 않음)

| ID | 내용 | 증거 |
|---|---|---|
| JEV-F1 | **`GuardrailRequestFactory`는 공급자 형식을 만들지 않는다.** 요청 설명은 팩토리의 요청 구성을 바꾼다고 했지만, 현재 코드에서 공급자 본문을 구성하는 곳은 `JevGuardrailClient.requestBody`다. 팩토리는 내부 형식(후보 · 최근 3턴 · 정책 판)만 만들고 질문 문안 · 선택지 · 시스템 지시를 모른다. 질문 · 선택지를 어디서 가져올지는 현재 구조에 없다 | `GuardrailRequestFactory` Javadoc, `GuardrailRequest` 스키마 description("공급자 실제 요청은 어댑터 안에서만 바꾼다"), `GuardrailCoordinator.java:437` 호출 한 곳 |
| JEV-F2 | **소비 구조가 공식 응답에 없는 값을 요구한다.** `convert`는 `reasonCode` · `policyVersion` · `categories`(필수)를 응답에서 읽고, 응답 정책 판이 요청과 다르면 `POLICY_MISMATCH`로 막는다. 공식 응답(`model · answers · usage`)에는 이 값들이 없다. `GuardrailVerdict`는 `reasonCode`(null 불가 + `^[A-Z][A-Z0-9_]{0,79}$`)와 `policyVersion`(null 불가)을 요구하고, `guardrail_event`는 `reason_code NOT NULL` + CHECK, `policy_version NOT NULL`이다. 이 값을 채울 출처가 응답이 아니면 `POLICY_MISMATCH` 경로의 존재 이유와 시험 8번이 달라진다 | `JevGuardrailClient.convert`, `GuardrailVerdict` 생성자, V6 |
| JEV-F3 | **분류 체계 두 벌.** 정책 리소스 · 계약 스키마 · 어댑터 `CATEGORIES`는 7개(`DIAGNOSIS · TREATMENT · SCORE · CAUSE · GENERALIZATION · EMOTION_CLAIM · PRESSURE`), AI 동작 명세 v0.2 §10.2는 `PASS`를 포함한 11개(`DIAGNOSIS · SCORE · TREATMENT · DEFICIT · GENERALIZE · MIND_READING · FORCE · PRESSURE · PERSONA · LEAK · PASS`)다. 겹치는 이름은 `DIAGNOSIS · TREATMENT · SCORE · PRESSURE` 4개뿐이다. 7개에는 `PASS` 항목이 없다. 7개에 묶인 곳: `policy-v1.json`, `LiveContentResources.GUARDRAIL_CATEGORY_COUNT`(기동 시 정확히 7개 검증), `guardrail-result.schema.json`의 enum, `JevGuardrailClient.CATEGORIES`, `LiveContentRegistryTest` · `U8RealtimeStartupChecksTest` · `U8ContractSchemasTest`. 범주 설명(criteria 후보)은 Luna JEV 인계서 v2 §3.2에 있고 코드 리소스로 옮겨지지 않았다. 검사 질문 문장은 명세 §10.2에 있다 | 개발자 스캔 (4) |
| JEV-F4 | **문턱 설정 키가 없다.** `GuardrailProperties`는 `mode · baseUrl · model` 세 칸이고 `application.yml`에도 없다. 명세 §10.2의 판정 규칙(PASS가 1순위이고 문턱 이상이면 ALLOW 등)은 코드 어디에도 없다 — 지금 `ALLOW/BLOCK/REVIEW`는 공급자가 직접 준다고 가정한다. 문턱 값은 명세가 "AI 성능 시험으로 정한다"로 둔다. 판정용 별개 키 `mujoong.judgment.min-confidence 0.6 · min-margin 0.15`는 이름 공간이 다르다. 새 키를 `mujoong.guardrail.jev.*`에 더하면 고칠 곳: `GuardrailProperties` 레코드, `application.yml`, `contracts/ai-io.md` 10.11 표(계약 정본), `contracts/deviations.md` U13 행("새 키"), `new GuardrailProperties(...)` 호출 시험 5곳(`GuardrailConfigurationTest.client`, `JevGuardrailClientTest.client`, `JevLiveProbe.run`, `JevLiveTaskIsolationTest`, `GuardrailModeStartupChecksTest`), 어댑터 생성자 또는 `GuardrailConfiguration` 배선. `ConfigKeyContractTest`는 레코드와 yml이 서로 맞으면 통과한다. `TimeLiteralScanTest`는 시간 값만 보므로 확률 문턱은 대상이 아니다 | 개발자 스캔 (4)(6) |
| JEV-F5 | **확률을 담을 자리가 없다.** `categories` · `policyFlags`는 main에서 읽는 곳이 없고(쓰는 곳은 `JevLiveProbe` 보고서와 시험) `guardrail_event`에도 칸이 없다. 공식 응답의 `probabilities`를 어디에 둘지(내부 형식 · 기록 표 · 지표)는 현재 구조에 자리가 없다. 칸을 더하려면 새 마이그레이션이 필요하고, realtime은 V6까지 쓰였다 — `team.md`의 "다음 번호는 core V8 · realtime V5" 문장은 낡았다(U13 이후 realtime 다음 번호는 V7 이상). Flyway `outOfOrder`가 꺼져 있어 병합 순서가 번호를 따라야 한다(R-M2) | `GuardrailEventRecord`, V6 |
| JEV-F6 | **`CHECK_PATH` 사용처 전수**: 정의 `JevGuardrailClient.java:59`(`public static final String CHECK_PATH = "/v1/guardrail/check"`), 사용 108줄(`endpointOf`), Javadoc `{@value #CHECK_PATH}` 50줄, 시험 `JevGuardrailClientTest.java:114`(`recorded.path()` 비교)·217줄(리다이렉트 `Location`). `contracts/` · `scripts/` · `infra/`에는 이름이 없다. 문서 `docs/units/u13/report.md` · `design.md`가 경로 문자열을 "형식 가정"으로 적는다. `systemone`은 `contracts/ai-io.md`(호스트 설명), `docs/units/u13/brief.md:25`, `infra/ci/image-checks.sh:271`(`JEV_BASE_URL` 자리값), 기술명세서 v0.3 §5.7에 나타나며 **코드는 `/v1/systemone`을 쓰지 않는다**. 상수가 `public`이라 이름을 바꾸거나 없애면 시험 두 곳이 컴파일 오류로 같이 바뀐다 | 전수 grep(`build/` 제외) |
| JEV-F7 | **`systemPrompt`가 요청 본문에 한 덩어리로 실린다.** 공식 `questions`는 질문 ID별 `type · instructions · criteria`다. 정책 리소스는 `categories` 이름 목록과 `systemPrompt` 한 문자열뿐이고, 그 문자열은 "JSON 한 객체 출력(필드: decision, categories, policyFlags, reasonCode, reviewNeeded, policyVersion)"을 지시한다 — 지금 가정 형식과 한 쌍이다 | `policy-v1.json` |
| JEV-F8 | **비2xx 응답 처리.** 오류 본문은 읽지 않고 상태 코드만 본다. 공식 오류 본문 스키마는 문서에 없다. 429 · 529도 `TRANSPORT_ERROR` 한 가지이며 재시도하지 않는다(BR2.1, ai-io.md 10.3 "분류 NONE"). 800ms 시한(`jev-budget`) 안에서 재시도할 여유도 없다. 팀 학습: 바깥 게이트웨이 · 작업이 이미 재시도하는 경로의 안쪽 어댑터는 재시도하지 않는다. 막힘 뒤의 "다음 행동 지시 한 번 더"는 게이트 단계의 재지시이지 호출 재시도가 아니다(`GuardrailCoordinator.blocked`, 턴당 1회) | `convert`, `GuardrailCoordinator.java:577` |
| JEV-F9 | **고정 버전 모델 요청 가능 여부 미확인.** `JevModels`는 형식과 `latest` 이동 별칭만 막는다. 제브가 `JEV_MODEL`로 받는 값(기술명세서의 예 `jev-1.13.0`)을 실제로 받는지 코드 어디에도 확인이 없다 | `JevModels` |
| JEV-F10 | **응답 상한 64KiB가 숫자 상수다.** `MAX_RESPONSE_BYTES = 64 * 1024`는 시간 값이 아니라 R-F7 대상이 아니지만, 응답이 `answers`(질문 수 × 선택지 수 × 확률)로 커지면 상한을 다시 봐야 한다는 사실만 적는다 | `BoundedBodySubscriber` |
| JEV-F11 | **실호출 증거가 없다.** `jevLiveTest`는 `SKIPPED_NO_KEY`까지만 확인됐고, RUNBOOK 단계 4의 제브 R-F15 확인 기록이 비어 있어 실호출은 합성 자료로만 해야 한다(`JevLiveProbe`는 합성 표본만 쓴다). 제브 공식 형식은 코드에 반영되어 있지 않고 외부 문서 기준이다(api-documentation.md B). 여기 적힌 모든 사실은 정적 읽기이며 대역 성공 · 합성 대화 · 실제 API · 실제 기기를 구분해 보고해야 한다(R-M11) | `docs/units/u13/report.md` |
| JEV-F12 | **공식 요청의 `state`에 해당하는 현재 필드가 없다.** 현재 요청에서 이름이 가까운 것은 `mode` · `policyVersion`이지만 같은 뜻이라는 근거가 없다. 또 계약 변경(스키마 · `ai-io.md` · 편차 표)은 R-M10에 따라 같은 작업에서 `contracts/`도 고쳐야 하고 `ci-gate`의 전 작업이 돈다 | 개발자 스캔 (7) 11번, `team.md` |

### 소비자가 실제로 읽는 값 (참고)

`GuardrailCoordinator`(`resolve/allowed/blocked/voided`)는 `isAllow()` · `decision()` · `failure()` · `reasonCode()` · `latencyMs()` · `eventDecision()`을 읽는다. `reasonFor(verdict)`: `BLOCK→GUARDRAIL_BLOCK`, `REVIEW→GUARDRAIL_REVIEW`, `FAILED(TIMEOUT)→GUARDRAIL_TIMEOUT`, 그 밖의 `FAILED→GUARDRAIL_ERROR`. 기록기는 정책 판을 응답이 아니라 세션에 고정된 `support.policyVersion()`으로 쓴다. 지표 `countVerdict`는 `decision()`과 `failure()==TIMEOUT`을, `countLatency`는 `latencyMs()>0`일 때만 쓴다. `reasonCode`가 정규식을 어기면 `GuardrailVerdict` · `GuardrailEventRecord` 생성자가 던지고, 표 CHECK 위반은 기록기가 영구 실패로 버린다.

### 이번 스캔의 확인 한계

`SessionExecutor` · `SessionMailbox`의 제브 연결, `RealtimeStartupChecks`의 제브 점검 입력, `session/Guardrail*Test` 일곱 개와 `GuardrailTurnIT`는 이름과 연결 지점만 보았다. core-api 전체 · frontend · tools · 그 밖의 realtime 패키지는 읽지 않았다. 구현 전 U13 절의 갭 표(G1 ~ G12) 중 구현으로 해소된 항목이 있는지는 이번에 항목별로 확인하지 않았다.

---

## 구현 전 U13 품질 · 갭 · 위험 (2026-10-17, main `6a78536` 코드, 읽기 전용 — 이력)

> 구현 전 스캔이다. 아래 "기존 시험"·"갭"·"부채" 중 구현으로 달라진 것(예: `guardrail` 패키지 신설, V6 추가, `u13.txt` 범위 파일 여부)은 위 최신 절과 `docs/units/u13/report.md`가 우선한다.

앱 · 시험 · 외부 공급자는 실행하지 않았고 시험 통과율 · 커버리지 수치는 보고하지 않는다. 이전 U14 · U15 수치는 재사용하지 않는다.

### 시험 · 검사 현황
- 게이트 쪽 기존 시험: `gate/{OutputGateTest, OutputGateGenerationTest, OutputGenerationTest, SegmentBufferTest, SeedDictionary*}`, `session/{GenerationSendExecutorTest, JudgmentTurnExecutorTest, JudgmentFaultExecutorTest, SessionVoiceU3Test, SessionVoiceU3IT}`. 제브 단계 시험은 `GenerationSendExecutorTest` · `JudgmentExecutorSupport` 모양을 따르고 `FakeJudgeClient`처럼 시험이 손으로 푸는 약속 대역을 쓴다.
- ArchUnit: `RealtimeArchTest`(R-F5 · R-F6 · R-F8 등), `LunaArchTest`(`..luna..`가 `..guardrail..`에 의존 금지, `allowEmptyShould(false)`라 패키지 신설 전에도 통과), `U8LiveContentArchTest`, `TimeLiteralScanTest`, `JudgmentPackageArchTest`.
- 커버리지: 모듈 줄 80%(JaCoCo)는 CI가 막는다. `backend/coverage-scope/u13.txt`는 없다. u11.txt의 선례(`SessionExecutor`를 목록에서 빼고 사유를 정직하게 적음)를 따른다. CI가 막는 범위 파일은 U5b · U7뿐이다.
- 단위 800ms 경계는 시계를 밀어 검증하고, 통합 시험은 `mujoong.gate.segment-quiet`를 운영값 800ms로 두며 제브 대역은 수동 완료로 만든다(`SessionVoiceU3IT` ~207 · `RealtimeMetricsScrapeIT` ~84 선례, 50ms 흔들림 역사).
- 영향받는 기존 단언(V6): `RealtimeSchemaIT`(표 목록 · 버전 "5"), `PracticeMigrationIT` · `PracticeMigrationRegistrationTest`("5"), `testing/RealtimeDb`(비우는 표 순서), `SingleInstanceMarkerTest`(SI-10 지도), `RealtimeDeletionParticipantOrderTest`. 하드코딩 "5"를 바꾸는 것은 사실 반영이며 R-F2의 단언 약화와 구별해 근거를 적는다.

### 기술 부채 신호 (U13)
| ID | 내용 | 증거 |
|---|---|---|
| U13-D1 | `SessionExecutor` 3,964줄, `Services` 생성자 오버로드 사슬(4개 + 정식, ~170-205). 필드 추가 비용이 크고 U12도 같은 파일을 고친다 | `JudgmentSupport`처럼 `GuardrailSupport` 기록 + `NONE` 기본값으로 묶는 선례. `NONE`의 의미가 "검사 없음"이면 실검사 모드 자동 ALLOW 금지(FR6.4)와 충돌하므로 '대역 · 비활성 표시'가 붙는 비실검사 모드로 명시해야 한다 (구현 뒤: `GuardrailSupport.DISABLED`가 이 자리로 보인다) |
| U13-D2 | 세대 번호 둘이 한 클래스에 공존 | architecture.md 표. 시험으로 못 박을 것(`sendInstruction`이 기기 세대를 올리지 않는 사실) |
| U13-D3 | `TranscriptWriter`의 단일 열린 AI 행 · PARTIAL 전용 덮어쓰기. "구간 1 = AI 행 1 = 동기 판정 1"을 가정 | 비동기 단계가 얹히면 행 수명과 구간 수명이 갈라진다 |
| U13-D4 | `JudgmentLiveRecorder` 재사용 불가(패키지 · 타입 고정) → 복제 위험 | `@SingleInstanceAssumption(si = "SI-10")`, `MAX_PENDING = 500`, 영구 오류 23503 · 23514 판별이 클래스 안. 공통 기반 추출은 refactor 스코프 최소 변경과 균형을 설계가 정함 |
| U13-D5 | `RealtimeStartupChecks.Inputs` 필드 추가 시 시험 대량 컴파일 영향 | 오버로드 유지 패턴 |
| U13-D6 | `PracticeSessionExtension.ExtensionEvent.Kind.GUARDRAIL_BLOCKED`는 자리만 있고 보내는 곳이 없다 | 연습 발화가 제브에 막힐 때 알릴지는 U12 역할 전환 보류(AC1.5.6)와 연결, 경계 결정 필요 (구현 뒤: `SessionExecutor`의 `practiceBlocked`가 이 종류로 알린다) |
| U13-D7 | 폐기된 AI 구간 원문이 `realtime_transcript.text`(approved=false)에 남는다 | `discard` → `aiFinal(text, false)`. `ERASE_BY_GUARDIAN`이 삭제 때 비움. `guardrail_event`에 전사를 안 남기는 결정과의 일관성(G8) |
| U13-D8 | `team.md`의 "다음 번호는 core V8 · realtime V5" 문장이 낡음 | 실제 realtime 마지막 V5, 다음 V6 (구현 뒤: V6 사용됨, 다음은 V7 이상). 문서 수정은 이 스캔 밖, 후속 단계에서 보고 |

### 브리프 대 코드 갭 — 해결하지 않고 표시 (사람 결정 필요 항목 포함, 구현 전 기록)
| ID | 브리프 문장 | 실제 코드 | 영향 · 필요한 결정 |
|---|---|---|---|
| G1 | 게이트의 `inspect` 뒤 `recheck` 앞에서 부른다, `OutputGate` 안 한 단계 | `OutputGate`는 동기 · 순수 클래스, 흐름은 `sealAndGate`에 있다 | 제브 단계는 실행기(또는 협력 객체)에 둔다. ADR-006의 "게이트 한 단계"는 개념 표현으로 읽는다 |
| G2 | 세대가 올라가면 구간 무효 | 세대 번호 둘, 지시 · 판정 · 보류 해제는 기기 세대를 안 올림 | 검사표에 두 번호 모두 |
| G3 **[사람 결정]** | BLOCK 등 → 같은 다음 행동 1회 재지시, 또 막히면 그 장면 정리 후 다음 단계, 그동안 '생각 중' | 기존은 범주별 고정 교정 문장(`correctAfterDiscard`, 세대당 1회 · 연달아 2회) + `WAITING_FILLER`/`listeningAgain`. "같은 다음 행동 재지시" 장치는 없음(U11 `NextAction`/`InstructionTemplates`가 재지시의 다음 행동) | 재지시를 U11 `NextAction` 재송신으로 할지 교정 문장 재사용으로 할지, 두 번째 막힘 뒤 "장면 정리"의 정의(자유대화 · 연습별)가 코드에 없음 |
| G4 **[사람 결정 · 확인 필요]** | demo · prod에서 제브 실검사 꺼짐 또는 `JEV_API_KEY` 없음 → 기동 실패 | `render-env`는 `JEV_API_KEY`를 OPTIONAL, `gen-env.sh`(검증 스택 · CI)는 빈 값. `image-checks.sh` · `scripts/verify/b-host.sh` · `b-stack.sh`의 demo 가드는 읽지 않음 | 새 기동 규칙이 demo 프로필로 도는 검증 스택(`127.0.0.1:8443`)을 깨뜨릴 수 있다. Functional Design 첫 작업으로 한 번 더 읽는다 |
| G5 **[사람 결정]** | 지표 이름이 `contracts/` 표에 있어야 한다(R-M10) | `contracts/`에 지표 이름 표가 없다(기존 `mujoong.gate.discard`도 코드 + 261008 기록 문서에만) | 지표 표 위치(`timing.md` 새 절? 새 파일?) 결정. 코드에만 더하면 R-M10 문장 위반 소지 |
| G6 **[사람 결정]** | 취소 · 중단 · 연결 해제 · 세대 변경 뒤 ALLOW는 무효 | 아이 입력 재개는 세대를 올리지 않음(끼어들기만 올림) | 제브 대기 중 아이가 말을 시작하면 늦은 ALLOW가 재생될 수 있다. 정책 결정 필요 (구현 뒤: `VoidCause.CHILD_SPOKE`가 입력 그룹 열림 수 증가로 무효 처리한다) |
| G7 | 비동기 기록기는 `JudgmentLiveRecorder` 관례를 따른다 | 공통 기반 없음, SI-10 지도에 클래스 직접 열거 | 복제 + `SingleInstanceMarkerTest` 지도 갱신이 현재 방식(J319) |
| G8 **[확인 필요]** | `guardrail_event`에 전사 원문을 담지 않는다(R-F14) | 폐기 구간 원문은 `realtime_transcript.text`에 이미 저장 | `guardrail_event`는 원문 없이 만들 수 있으나 막힌 문장이 transcript 표에 남는 점은 별도 확인 |
| G9 **[사람 결정]** | 주소 · 모델은 설정 기본값, 도메인 값을 코드 · 문서에 쓰지 않는다(R-F3) | Luna `LunaHosts.ALLOWED_HOSTS` · GPT-Live `ProviderHosts`는 코드 상수, `application-demo.yml`에 공급자 URL 기본값이 이미 있음 | 같은 허용 호스트 방식을 제브에 쓰면 브리프 문장과 어긋난다. R-F3의 "실제 도메인"을 우리 서비스 도메인으로 읽은 선례를 따를지 사람 확인 (구현 뒤: `JevHosts.ALLOWED_HOSTS = {api.typesafe.ai}` 코드 상수가 쓰인다) |
| G10 | 승인 원문 + `PracticeSpeech.matches` 완전 일치면 제브 생략 | `matchApproved` · `registerScript` 운영 호출자 0개, `Services`에 레지스트리 없음. 연습 일치는 `sealAndGate`에서 이미 함 | 레지스트리(또는 좁은 포트)를 `Services`에 연결해야 한다. 대본 일치는 U12 이후 유효 |
| G11 **[사람 결정]** | 제한 800ms, 실검사 모드 자동 ALLOW 금지 + 재시도 분류 표 | 제브는 `ai-io.md` 10.3 표에 행이 없다. `jev-budget`은 "한 번의 상한" | 호출 안 재시도 없음으로 해석할지(팀 학습: 바깥 재시도 경로의 안쪽은 재시도하지 않음) 계약에 한 줄 더할지 |
| G12 | 시스템 프롬프트 · 분류 선택지는 버전 리소스 | U8이 `guardrail/policy-v1.json`(`jev-guardrail-v1`)로 이미 만들고 기동 검증 · 세션 고정까지 함 | 새 리소스 불필요. 스키마 예시 `guardrail-v1`은 실제 판 이름과 다름 (구현 뒤: `(U13-b)`로 정정) |

### 구현 전 위험 요약
동시성(`mailbox.putSafety`만) · `SEGMENT_QUIET`/`pendingOutputChunks` 레이스(`onOutputQuiet` ~2157) · 대기 중 종료 · 연결 교체 · 같은 응답 꼬리 조각 · 첫 소리 지연(`LatencyRecorder.gatePassed`/`segmentConfirmed` 의미) · `segment-quiet` 50ms 흔들림 · R-F1 · R-M11(가짜 제브의 1차 사전만 통과를 실검사로 표기 금지, 기동 로그 · 지표에 '대역 · 비활성' 표시). 제브 실호출 응답 형식은 미확정이며 어댑터 변환의 형식 가정은 시험 하나로 고정하고 실호출은 `[실호출-합성]`으로 따로 보고한다(R-F1 · R-F13 · R-F15).

### 구현 전 스캔의 확인 한계
core-api · frontend · infra · tools · scripts는 얕게만 봤고(비밀 이름 위치 검색 수준) `gateway/` · `recording/` · `practice/` 구현 · `dialog/` · `messaging/` · `ProviderClient` 계열도 이름과 호출 관계만 봤다. G4는 인프라 스크립트와 demo 프로필 조합을 Functional Design에서 읽어야 한다.

---

> 현재 범위의 정본(U14 갱신절, 보존 이력)은 바로 아래다. 그 뒤 원문은 보존 이력이며 재검증 범위는 timestamp에 한정한다.

## U14 집중 갱신 — 확인 사실과 검증 계획

이번 스캔은 읽기만 했고 시험·커버리지를 실행하지 않았다. U15의 이전 보고/CI 수치는 이번 U14 검증 결과로 재사용하지 않는다. result/practice 시험 목록과 SceneChipMapperTest·JobRunnerTest를 확인했다. u14.txt는 아직 없고 u6/u15 범위 목록은 있다. 새 시험은 u14 태그·LINE 80% 범위를 추가하고 타 유닛 단언·하한을 낮추지 않는다.

주요 회귀: 기존 행동 목표 읽기, 후보 버전 고정·동시 선택·삭제, 새 목표 근거 검증, invalid skillRef 거절 및 NONE 처리, seed 생성 장애 후 결과 DONE 유지, 기존 U5b와 새 flowVersion 혼합 리포트, 3개 이상 실제 장면, AFTER_REASK 저장/API/report와 27 칩 분리, 4번째 시도 및 5/15/30초 backoff, 삭제 중 늦은 쓰기 차단. fake 공급자만 사용한다.

중요 발견: V5의 attempt_count뿐 아니라 core_result_job_attempt.attempt_no도 1..3 제약이다. 4회 시도에서 두 제약을 함께 검토해야 한다. V4의 후보·선택은 행동 키/버전 NOT NULL과 복합 FK이므로 새 후보용 저장 구조를 호환 확장해야 한다. migration-approvals.txt는 실제 사람이 확인한 문장 해시·이름·날짜를 요구한다. 승인자의 이름을 만들어 적지 않는다.

품질: 책임 명명·출력 검증·잠금 경계는 양호하나 SessionResultHandler/ResultAiSchema/ReportAssembler의 압축된 로직은 보통 수준이며 버전 분기로 복잡도가 늘어날 위험이 있다. U15가 구현되었으므로 아래 TD-3의 어댑터 부재와 U15 미구현 서술은 더 이상 현재 사실이 아니다.

---

# 코드 품질 평가 (Code Quality Assessment)

> 최신 확인: 2026-10-16, HEAD 7e30ee8, U15 집중 분석. 아래 최신 절이 U15 관련 과거 설명보다 우선한다. 이전 분석 본문은 이력으로 보존하며 이번 범위 밖의 검증 주장은 shallow로 낮췄다.

## U15 검증 결과와 후속 위험

기존 PragmaticsKnowledgeTest는 disabled 무호출, enabled-without-adapter 오류, 공개 행동키와 과거 정의를 고정한다. U15에서 이 단언을 보존한다. LunaArchTest와 기존 Luna 경계도 회귀 대상이다. U15 기능 자체는 미구현이며 통과율·커버리지 수치를 아직 보고하지 않는다.

필수 검증: child A/B 격리, 멱등 쓰기·삭제, NONE/꺼짐, 실제 Neo4j Cypher CRUD, 2초 제한과 늦은 작업, V8 권한·마이그레이션, 생성/삭제 경쟁, 외부 삭제 실패 재시도, 원문/개인정보 비노출, 내용 검증·재생성 예산, source zip 해시 보존, u15 LINE 80% 및 기존 u2/u5a/u6/u9 회귀.

이번 기준 실행은 Java 컴파일까지 진행했으나 PostgreSQL 초기화 스크립트의 CRLF 때문에 컨테이너가 exit 127로 실패했다(env: bash\\r). 성공한 기준 시험으로 취급하지 않는다. Linux 실행용 체크아웃 줄바꿈을 복구한 뒤 재검증해야 한다.

우선 위험: 삭제 실패를 빈 값으로 삼키는 오류, 삭제 후 늦은 생성 쓰기, 결과 FK와 삭제 순서 충돌, Luna 선택적 빈의 필수 주입, 중첩 재시도, 리포트용 금지문구 예외 오용. 아래 오래된 기술 부채 중 Luna 미연결 평가는 이번에 확인된 U9 구현으로 대체한다.

## 이전 분석 기록 — 2026-10-15, 현재 범위 밖은 재검증하지 않음

> 기준: 커밋 `72d7034`, 2026-10-15 전체 재스캔. 이 문서가 기술 부채(TD-n) 목록의 정본이며, 다른 문서는 TD 번호로 가리킨다.

## 1. 시험

| 위치 | 규모 | 비고 |
|---|---|---|
| `backend/contract-model/src/test/` | 15 클래스 · @Test 120 | 계약 타입 |
| `backend/core-api/src/test/` | 157 클래스 · @Test 996 | ArchUnit `core/arch` 포함 |
| `backend/realtime-activity/src/test/` | 126 클래스 · @Test 819 | ArchUnit `realtime/arch` 포함 |
| `backend/test-support/src/test/` | 4 클래스 · @Test 21 | |
| `frontend/tests/u1 … u7/` | 시험 파일 73 | Vitest + Testing Library + jsdom |
| `tools/tests/u1 · u3 · u4a · u4b/` | 11 | Vitest |
| `infra/ci/test-*.sh` | — | Bash 음성 대조 시험 |
| `tools/skeleton-e2e/`, `scripts/verify/c-skeleton-turn.sh` | — | 끝-끝 스켈레톤 |

백엔드 시험 태그: db 107 · u1 142 · u2 15 · u3 24 · u4a 5 · u4b 24 · u5a 11 · u5b 34 · u6 35 · u7 17.

커버리지: 백엔드 JaCoCo 줄 80% 하한(모듈별, test-support 제외), `-PcoverageScope=<유닛>`로 `backend/coverage-scope/*.txt` 범위 측정. 프런트 v8 줄 80% 하한, `MUJOONG_COVERAGE_SCOPE`로 `frontend/coverage-scope/*.json` 범위 측정. CI가 전체 + U5b · U7 범위를 따로 검증. org 기본 `classic` 범위의 줄 80% 하한은 이미 CI에 걸려 있다.

시험 개수 · 태그만 확인했고 시험 본문 품질은 보지 않았다.

## 2. 린트 · 서식

- 프런트: ESLint flat config(`frontend/eslint.config.mjs`) — next core-web-vitals · typescript, `/parent` ↔ `/device` ↔ `shared` import 경계, `react/no-danger`, `react/jsx-no-literals`(화면 문구는 copy 모듈만), `getUserMedia` 사용처 1곳, 서비스 워커 금지(U7 보호자 SW 예외).
- 백엔드: javac `-Xlint:all`(경고를 오류로 바꾸지 않음) + ArchUnit 계층 · 의존 규칙. Java 포매터(Spotless · Checkstyle) 없음.
- `.prettierrc.json`(printWidth 100) · `.editorconfig` 있음. CI에 Prettier 검사 단계 없음.

## 3. CI/CD

- `.github/workflows/ci.yml` 작업: changes · backend(`gradle clean check verifyBootJarHasNoTestClasses`) · u5b-scope · U7 범위 · backend-deps(Trivy) · frontend(ESLint · Vitest 커버리지 · `next build` · 번들 비밀 문자열 0건) · frontend-deps(npm audit) · tools · secrets(gitleaks) · infra-validate(Compose 불변식 12 · `nginx -t` · shellcheck · 마이그레이션 expand 검사) · images(6종 빌드 · Trivy · 이미지 점검 · GHCR 푸시) · skeleton. 액션은 커밋 SHA 고정.
- `.github/workflows/deploy.yml`(단계 이름만 확인): 수동/자동 DEPLOY · ROLLBACK, OIDC → SSM `mujoong-deploy`, demo 환경. `docs/units/README.md`에 따르면 `SERVICE_HOST` · `AUTO_DEPLOY` 변수가 없어 실제 배포 단계는 건너뛴다.

## 4. 문서화

- 루트 `README.md` · `AGENTS.md`, `infra/RUNBOOK.md`(demo 운영 정본), `contracts/README.md` + `deviations.md`(편차 67행), `docs/units/<u>/{brief,design,report}.md`, `docs/erd/ERD_v0.2.md`, `docs/change-history.md`.
- 코드 주석: 한국어 Javadoc이 촘촘하고 결정 ID(J · BR · U*-J · 편차)를 인용한다. TODO · FIXME는 앱 소스에 0건(남은 일은 문서로 관리).

## 5. 기술 부채 (TD)

| ID | 내용 | 위치 · 증거 | 영향 | 갱신 문서 작업과의 관계 |
|---|---|---|---|---|
| TD-1 | **AI 공급자 운영 어댑터 부재 — 기본 빈이 항상 실패(fail-closed)** | `core/textai/TextAiConfiguration.java`(`unavailableTextAiProvider` → `TextAiException.Reason.UNAVAILABLE`), `core/result/ResultTextConfiguration.java`(`PROVIDER_UNAVAILABLE`), `realtime/practice/PracticeRuntimeConfiguration.java:46-51`(`EvaluationProvider` 실패 future, `CueProvider.UNAVAILABLE`). 실제 구현은 시험 대역(`FakeResultTextProvider` 등)뿐. 환경 변수 이름 `MUJOONG_TEXT_AI_ENDPOINT/MODEL/API_KEY`는 `application.yml` 주석에만 있음 | 운영에서 상황 생성 · 결과 문장 · 리포트 · 연습 판정 · 단서가 동작하지 않음. 기동 · 시험은 비밀 없이 가능 | `15_기술명세_AI동작_1015` 반영 시 핵심 변경 지점 |
| TD-2 | **음성 공급자 선 형식이 프로젝트 정의(J110)** | 모델 `gpt-live-1`, `session.*` type 체계, URL `/v1/live/sessions` — `realtime/voice/ProviderRequestFactory.java:27-39`, `ProviderClient.java:190-205`, `application-{demo,prod}.yml` | 실공급자 형식과 다르면 연결 불가. 가짜 공급자(`tools/fake-provider/server.mjs`) · `test-support` 대역도 같은 형식이라 함께 바꿔야 함 | `40_기능명세_GPT-Live` 반영 시 핵심 변경 지점 |
| TD-3 | 지식 그래프(Neo4j) 읽기 어댑터 없음 | `core/knowledge/KnowledgeGraphLookup` 인터페이스만, `mujoong.knowledge.neo4j.enabled=false`. Compose demo에 `neo4j` 서비스(프로필 knowledge)는 있음 | 기능 미사용, 설정 · 이미지만 존재 | 문서가 지식 그래프를 요구하면 새 어댑터 필요 |
| TD-4 | **거대 클래스 `SessionExecutor`** | `realtime/session/SessionExecutor.java` 3,388줄(상태 · 타이머 · 출력 · 연습 확장 실행). 다음: `FileAudioRecorder` 619 · `CoreProperties` 576 · `StopIntentJudge` 565 | 세션 동작 변경의 영향 범위 · 리뷰 비용 큼 | 세션 전이 · 시간 값 변경 시 집중 스캔 필요 |
| TD-5 | **압축 서식(한 줄에 메서드 전체)** | U6 계열 `core/report/web/ReportController.java`, `core/report/ReportRepository.java`, `core/result/SessionResultHandler.java`, `core/result/ResultAiSchema.java`, `core/result/ResultTextConfiguration.java`, 프런트 `parent/screens/Report30.tsx` · `RecordDetail27.tsx` · `ReportRange28.tsx` · `parent/api/results.ts`(250자 넘는 줄 다수) | 가독성 · diff 리뷰 저하. CI에 Java 포매터 · Prettier 검사가 없어 막히지 않음 | 결과 · 리포트 수정 시 서식 정리 여부 결정 필요 |
| TD-6 | **시간 값이 세 곳에 중복** | 두 앱 `application.yml`의 `mujoong.timing.*` ↔ `contracts/timing.md` ↔ `frontend/src/shared/timing/timing-defaults.json`(시험이 대조) | 하나만 바꾸면 시험 실패 또는 불일치 | 문서의 시간 값 변경은 세 곳 동시 수정 |
| TD-7 | **마이그레이션 번호 · 승인 규칙** | 다음 번호 core V8 · realtime V5, Flyway `outOfOrder` 꺼짐, 기존 표 제약 강화는 `infra/migration-approvals.txt` 승인 행이 있어야 CI expand 검사 통과 (2026-10-18 보충: realtime V5는 U11, V6은 U13이 썼다) | 병렬 브랜치에서 번호 충돌 위험 | DB 변경 시 번호 · 승인 행 함께 |
| TD-8 | **커버리지 범위 목록 유지 부담** | `backend/coverage-scope/*.txt` · `frontend/coverage-scope/*.json` + 유닛 태그 u1~u7 | 파일을 옮기거나 새로 만들면 목록 수정 필요(목록에 없는 파일이면 측정 실패) | 새 파일 추가 시 범위 목록 · 태그 수정 |
| TD-9 | 유닛 혼합 이력 | U2~U5a는 엔진 없이 병렬 구현, U5b · U6 · U7은 통합 브랜치로 반영(`docs/units/README.md`) | 유닛마다 설계 문서 · 편차 기록의 밀도가 다름 | 문서 차이 계산 시 유닛별 편차 확인 |
| TD-10 | 알려진 흔들림 · 남은 일 | `RealtimeInternalApiIT` 부하 시 409, 세션 문구 6종 음성이 합성음 자리(`infra/seed-audio/*-v1|v2.wav`), 시드 003을 검증 스크립트 · RUNBOOK 시드 순서에 넣는 일 | 시험 간헐 실패, 데모 음성 품질 | — |

## 6. 이번 스캔의 확인 한계

- 업무 로직 본문(core result · practice · activity, realtime session · gateway, frontend parent · device)은 얕게 봤다. 위 TD 중 TD-1 · TD-2 · TD-6은 파일을 직접 읽어 확인했고, TD-4 · TD-5는 크기 · 줄 길이로 판단했다.
- 문서 차이가 특정 기능에 걸리면 그 경로를 집중 스캔해야 한다([reverse-engineering-timestamp.md](reverse-engineering-timestamp.md)의 `shallow.paths`).

## 7. 종합

시험 · CI · 계약 관리는 촘촘하다(시험 약 2천 개, 줄 80% 하한, 계약 시험, 불변식 검사). 약점은 (1) 외부 AI 운영 어댑터가 없고 음성 선 형식이 자체 정의라 실공급자 연결이 검증되지 않은 점, (2) 세션 실행기 집중과 압축 서식, (3) 시간 값 · 커버리지 범위 · 마이그레이션처럼 여러 곳을 함께 고쳐야 하는 규칙이다.

---

## U11 집중 갱신 (261016-u11-judgment-turn, 2026-10-16 스캔 — main 병합 때 보존한 절)

> main의 U14 · U15 갱신본을 바탕으로 두고, U11 작업 기록의 집중 스캔에서 갱신한 절만 아래에 보존했다. 줄 번호 · 커밋은 2026-10-16 `9bcc706` 기준이며 PR1(선 형식 정비) 이전 코드다.

## 6. (2026-10-16 갱신) U11 판정 턴 관점 위험 (U11-Rn)

개발자 스캔(`aidlc/spaces/default/intents/261016-u11-judgment-turn/inception/reverse-engineering/developer-scan.md`)의 우려를 설계 담당이 코드로 다시 짚어 정리했다. 줄 번호는 커밋 `9bcc706` 기준. "다음 단계" 열은 결정이 아니라 어느 단계가 정해야 하는지만 적는다.

| ID | 위험 | 증거 | 왜 문제인가 | 다음 단계 |
|---|---|---|---|---|
| U11-R1 | **GPT-Live 선 형식 불확실(중단 조건)** | `ProviderClient` 수신 9종에 `response.created` 류 없음, 응답 id는 첫 출력 조각으로만 앎(`ProviderResponseId`). 선 형식은 J110 가정(ai-io.md 3절) | '지시 뒤 응답만 통과'(C-DS15)를 공식 신호가 아니라 세대 번호(`bumpGeneration`) · 취소 목록(`cancelledResponses`) 가정에 둘 수밖에 없다. 실선 형식(FR12.2)에서 맞는지는 실호출로만 확인. GPT-Live용 격리 실호출 작업이 없다 | FR12.2 실호출 결과 전에는 선 형식을 바꾸지 않음(ADR-007). Functional Design에서 가정과 실호출 확인 항목을 분리 |
| U11-R2 | **공급자 송신 순서 보장 없음** | `toProvider`(L2324 ~ L2340)가 출력권 없는 경로에서 송신마다 `launcher().launch`로 가상 스레드를 띄움(L2339). `ProviderClient.Session` 송신 잠금은 동시 쓰기만 막음. 순서 보장은 직렬 큐 `enqueuePracticeProvider`(L2342 ~ L2361)뿐이고 `controlsOutput()`일 때만 탐. `speak`(L1405 ~ L1428)는 한 작업 안에서 cancel → correct → create | `session.instructions.correct` 다음 `session.response.create`를 두 번의 `toProvider`로 보내면 공급자가 응답 생성을 먼저 받을 수 있다. `ManualLauncher`가 이 경쟁을 시험에서 드러내지 못한다 | Functional Design: 판정 턴 송신을 한 작업 안 연속 송신 또는 직렬 큐로. 시험은 순서를 단언하는 대역 필요 |
| U11-R3 | **보류 출력 경로가 '취소 + 원문 기록'** | `outputHeld()`(L1600 ~ L1604)가 참이면 `refuseHeldResponse`(L1593)가 `session.response.cancel`로 취소하고 이후 조각(전사 포함)을 버림. 보류가 아니면 `beginOutput`(L1734 ~ L1743)이 첫 조각에서 무조건 `transcripts.aiStart`(L1740). 게이트 폐기도 `aiFinal(text, false, …)`로 `realtime_transcript`에 원문 저장(`TranscriptWriter` L112 ~ L122), 표시 · 스냅숏에서만 거름(`ResultSnapshotBuilder` L115) | 판정 턴 요구(FR5: 자동 응답 음성은 버리되 전사는 `judgment_live`에만, `HELD_OUTPUT_TRANSCRIPT` 사건, 대화 기록에 남기지 않음)를 담을 갈래가 없다. 기존 보류에 얹으면 전사를 잃고, 게이트 경로에 태우면 대화 기록에 남는다 | Functional Design: 보류 출력은 구간 버퍼 · 전사 기록기 **앞**에서 갈라 `judgment_live`로만 |
| U11-R4 | **V5 추가 시 판 번호 단언** | `RealtimeSchemaIT` L46 ~ L56(표 목록) · L88 · L94(최신 판 "4"), `PracticeMigrationRegistrationTest` L22(`expectedVersion()` == "4"). `SchemaVersionCheck.expectedVersion()`은 classpath 최대 판을 스스로 계산 | `judgment_live`(realtime V5)를 더하면 앞 유닛 시험 단언이 깨진다. R-F2(기존 단언을 낮추지 않음)와의 관계를 사람이 정해야 한다. 앞 유닛 V4도 같은 방식으로 목록을 늘린 것으로 보임 | Requirements Analysis에서 질문: 단언 갱신(사실 반영)을 R-F2 위반으로 볼지 |
| U11-R5 | **판정 출력 스키마 검증 빈틈** | `ai-judgment-output.schema.json`: `first` · `second` 각 `{option: ^[A-Z][A-Z0-9_]*$, probability: 0~1}`만 | 요청 `options` 밖 코드, `first.option == second.option`, 확률 합 1 초과가 스키마를 통과한다. ai-io 10.3 "선택지 밖 코드는 판정 보류" | Functional Design: `JudgeClient` 구현이 직접 걸러 보류로 바꿈(스키마 변경은 계약 변경 — R-M10) |
| U11-R6 | **Luna 빈이 키 있을 때만** | `LunaConfiguration` L43 ~ L58 `@Conditional(LunaKeyPresentCondition)` — `LunaTransport` · `RealtimeLunaClient` 둘 다 | 시험 · 키 없는 demo에는 빈이 없어 `JudgeClient`가 주입 실패하거나 null 처리가 필요하다. R-F1(시험에서 실공급자 금지) · R-F15(확인 기록 전 아동 데이터 외부 전송 금지, 편차 U9-b)와 묶인다 | Functional Design: 빈 없을 때 '언제나 보류' 동작과 시험 대역을 정함 |
| U11-R7 | **겹치는 판정기** | 자유대화 J1(참여) = `JoinTracker`(ACCEPT · REFUSE · TOPIC_REFUSAL · UNCLEAR), J6(종료 의도) = `StopIntentJudge`(STOP · CONFIRM · NONE). `judgeStopIntent`(L1809) 호출 순서가 출력권 여부에 따라 다름 — 출력권 확장은 확장점 전달 **앞**, 아니면 **뒤** `thinking()` 조건 안(L1676 ~ L1688) | 같은 질문을 규칙과 Luna가 따로 답하면 결과가 엇갈린다. 등록 종료 표현 → 판정 멈춤(FR3.5)을 지키려면 종료 판정이 판정 턴 시작보다 앞서야 한다 | Functional Design: 대체/보조(예: 규칙이 UNCLEAR · CONFIRM일 때만 Luna) 결정, 종료 판정 순서 고정 |
| U11-R8 | **없는 설정 키 · 문구** | `mujoong.judgment.min-confidence` · `min-margin` 키 없음. `ConfigKeyContractTest`가 yml `mujoong.*` 키 집합 = 설정 레코드가 읽는 키 집합을 강제, `judgment` 묶음은 `TIMING_GOVERNED_GROUPS`(L73 ~ L75)에 없음. `contracts/timing.md` 13절의 응답 준비 대기 초과 `D_HOLD` 되묻기 문구가 `DialogInstructions`(`Correction` 열거 · `JOIN_FIRST_QUESTION`만)와 U8 승인 발화 시드(ai-io 10.6)에 없음. 다음 행동 지시 템플릿 · 프롬프트 지시문 · `promptVersion` 정본도 없음 | 새 키를 별도 설정 클래스로 두면 `ConfigKeyContractTest` 바인딩을 고쳐야 해 R-F2와 부딪힐 수 있다. `D_HOLD` 문구 정본이 없으면 대기 초과 동작을 구현할 수 없다 | 새 키는 `RealtimeProperties` 루트 레코드에 한 칸. `D_HOLD` · 지시 템플릿은 Requirements Analysis 질문(정본 개정이 필요하면 RP 표). 지시문에 판정 라벨 · 확률 · 기준을 넣지 않는 규칙(FR3.7)은 `PracticeInstruction` 같은 값 타입으로 강제 가능 |
| U11-R9 | **ArchUnit 패키지 제약** | `LunaArchTest` L31 ~ L37(`luna`는 `voice` · 제브 · 로그 프레임워크 · Jackson 참조 금지), `RealtimeArchTest` L174 ~ L180(`gate` · `voice`는 JDBC · 저장소 · Outbox 참조 금지) | `JudgeClient` 구현이나 `judgment_live` 저장소를 `luna` · `gate` · `voice`에 두면 아키텍처 시험이 실패하고, 시험을 고치는 것은 R-F2 위반 | Functional Design: 새 판정 패키지(예: `kr.mujoong.realtime.judgment`)에 둠 |
| U11-R10 | **`SessionExecutor` 파일 소유 · 충돌 면** | 3,388줄 · 상태 필드 약 70 · 메서드 약 130, 17번 커밋. U11 → U13 → U12가 차례로 고침(지시서 · project.md 교정). U13 제브 자리 = `sealAndGate`(L2165 ~ L2206) `inspect` 뒤 · `recheck` 앞 | 판정 턴 상태(차례 id · 보류 버퍼 · 응답 준비 시계 · 늦은 결과 거르기)를 이 클래스에 더 쌓으면 U13 · U12 병합 충돌과 리뷰 비용이 커진다 | Functional Design: 판정 턴 상태를 실행기 스레드 전용 협력 객체로 떼고, 실행기에는 호출 지점만 |
| U11-R11 | '생각 중'이 보류 중엔 안 나감 | `thinking()`(L2135 ~ L2139)은 `!outputHeld()`일 때만 THINKING. `listeningAgain()`(L2142)은 게이트 폐기 · 대기 문구 경로에서 LISTENING으로 | 판정 턴 보류를 `outputHeld()`에 합치면 FR5 "보낼 응답이 없으면 생각 중 유지"와 부딪힌다 | Functional Design: 판정 턴 보류를 `outputHeld()`와 다른 조건으로, 또는 `setThinking` 능력으로 |
| U11-R12 | 늦은 비동기 결과 거르기 틀 없음 | `RealtimeLunaClient.call`(L63)의 완료 콜백은 HTTP 클라이언트 스레드. 실행기 상태는 실행기 스레드만 바꿈. 편지함 경로(`PracticeOutput.post` → `putSafety` → `onPracticeEvent` L1224)는 있으나 비교 기준 '판정 차례 id'가 없음. 장애 경로 `providerLost()`(L1758) · `abortProviderLost()`(L1780) · `startPause`(L2046) | 판정 결과가 장애 취소 · 다음 차례 뒤에 도착하면 낡은 결과로 출력이 풀릴 수 있다(FR5 장애 취소) | Functional Design: 차례 id · 출력 세대로 늦은 결과를 버리고 장애 경로에서 판정 턴을 '장애 취소'로 기록 |
| U11-R13 | 삭제 · 계약 열린 질문 | `RealtimeDeletionParticipant.order()`: 음성 100 → 연습 150 → 세션 200 → 메시징 900. `DeletionCompleted.counts` 포함 여부는 U8 contract-summary 4절 열린 질문(U11 · U13 · U15). 실행기는 DB를 기다리지 않는 관례(`AsyncRecorder`) | `judgment_live`가 세션 행에 외래 키를 걸면 200 앞에서 지워야 한다. 쓰기 경로의 비동기 · 재시도 정책도 미정 | Requirements Analysis: counts 포함 여부, Functional Design: 삭제 순서 · 비동기 쓰기 |

추가로 지킬 사실(위험은 아님):

- 확장점 포트는 더하기만으로 넓힐 수 있다 — `PracticeOutput`의 `default false` 선례(`speak` · `cancelSpeech`), `ExtensionEvent.Kind`를 다 훑는 `switch`가 운영 · 시험 코드에 없음(값 추가가 컴파일을 깨지 않음).
- 레거시 `EvaluationProvider`(U5b)와 새 `JudgeClient`는 이름 · 역할이 겹치지만 별개로 둔다(지시서).
- 스캔 범위에서 `Instant.now()` · `System.currentTimeMillis()` 직접 사용은 없다. 숫자 리터럴은 `SegmentBuffer.MAX_BYTES`(바이트) · `InputRelay.VOICE_ENERGY_THRESHOLD`(진폭) · `LunaTransport`의 `Retry-After` 해석 정도이고 허용 목록으로 관리된다.
