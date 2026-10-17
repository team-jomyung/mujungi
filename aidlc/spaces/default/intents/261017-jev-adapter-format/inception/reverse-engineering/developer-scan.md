**Collaborator:** developer-agent

# Developer Code Scan (집중 스캔: 제브 어댑터 선 형식)

- 작업 기록: `261017-jev-adapter-format` (scope bugfix, 깊이 Standard, 시험 전략 Standard, 브라운필드)
- 스캔 기준: 브랜치 `unit/u13-jev-guardrail`, HEAD `421e1ade0cb9939b20c7a485bbc13c9131dbbf3e` (U13 구현이 끝난 상태. 이 브랜치는 아직 `main`에 병합 전이고 로컬에는 `main` 브랜치가 없어 `origin/main`만 있다)
- 스냅샷 `store_generation`: `sha256:0aff12c79a1a71821bbff09a149627298280aaabef7318a861e07acdb344e72d`
- 스냅샷 `source_fingerprint`: `git:779adccc4fea7962c4f3e034b57e66f2e0c14e27`
- 저장소 소스는 수정하지 않았다. 이 파일 하나만 썼다.
- 증거 종류: 이 문서는 코드 · 문서를 읽은 정적 사실이다. 실제 제브 API를 부른 증거는 없다(R-M11).

## Developer Code Scan Results

### Scan Coverage
- **Analyzed deeply** (스냅샷 paths 안):
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/guardrail/` 전체 19개 파일(`JevGuardrailClient` · `GuardrailRequestFactory` · `GuardrailRequest` · `GuardrailVerdict` · `GuardrailEventRecord` · `GuardrailEventRecorder` · `GuardrailConfiguration` · `GuardrailProperties` · `GuardrailStartupRules` · `GuardrailSupport` · `GuardrailClient` · `JevHosts` · `JevModels` · `StubGuardrailClient` · `UnavailableGuardrailClient` 등)
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/session/GuardrailCoordinator.java` (소비 지점 위주로 읽음) · `PracticeSessionExtension.GuardrailBlockedEvent`
  - `backend/realtime-activity/src/main/java/kr/mujoong/realtime/content/LiveContentResources.java` (`guardrail` 정책 적재부) · `observability/RealtimeMetrics.java` (`mujoong.guardrail.*` 상수)
  - `backend/realtime-activity/src/main/resources/guardrail/policy-v1.json` · `application.yml`의 `mujoong.guardrail.jev` · `mujoong.timing.guardrail` · `db/migration/realtime/V6__u13_jev_guardrail.sql`
  - 시험: `src/test/.../guardrail/` 전체(특히 `JevGuardrailClientTest` · `GuardrailRequestFactoryTest` · `GuardrailConfigurationTest` · `JevLiveProbe` · `JevLiveTest` · `JevLiveTaskIsolationTest`), `config/ConfigKeyContractTest`의 제브 대목, `arch/TimeLiteralScanTest`의 제브 허용 항목, `testing/FakeGuardrailClient`
  - `contracts/ai-io.md` 10.1 ~ 10.5 · 10.11, `contracts/deviations.md` U13 편차 `(U13-a)` `(U13-b)`, `contracts/schemas/guardrail-request.schema.json` · `guardrail-result.schema.json`, `contracts/timing.md` 13절의 제브 행
  - `docs/units/u13/brief.md` · `design.md` · `report.md` (형식 가정과 열린 항목 부분)
- **Skimmed only** (스냅샷 paths 밖이거나 얕게 훑음, 깊이 분석했다고 말하지 않는다):
  - 스냅샷 paths 밖: `backend/build.gradle.kts`(`verifyJevLiveIsolation` · `jevLiveTest` 선언 부분만 확인), `infra/ci/image-checks.sh` · `infra/ci/gen-env.sh` · `infra/RUNBOOK.md` 8.8(`typesafe` · `JEV_*` 줄만 확인), `aidlc/spaces/default/knowledge/documents/`의 `무중_AI동작명세_v0.2.md` §10.2 · `무중_기술명세서_v0.3.md` §5.7 · Luna JEV 인계서 v2 §3.1 ~ §3.3(해당 절만 확인)
  - 스냅샷 paths 안이지만 얕게 훑음: `GuardrailCoordinator`의 상태 기계 전체(소비 지점만), `SessionExecutor` · `SessionMailbox`의 제브 연결, `session/Guardrail*Test` 일곱 개와 `GuardrailTurnIT`(이름 · 사용 팩토리만), `startup/RealtimeStartupChecks`의 제브 점검 입력, `contract-model`의 `U8ContractSchemasTest`
  - 훑지 않음: core-api 전체, frontend, tools, 그 밖의 realtime 패키지

### Packages Found
- `kr.mujoong.realtime.guardrail` — 기능 패키지 — Java 21 — 제브 포트 · 내부 형식 · 실제 어댑터(`JevGuardrailClient`) · 대역 · 비활성 구현 · 요청 구성 · 기록기. 이 작업의 대상은 이 패키지 안의 `JevGuardrailClient`와 `GuardrailRequestFactory`다.
- `kr.mujoong.realtime.session` (`GuardrailCoordinator`) — 협력 객체 — 검사 상태 기계, 검사표, 재지시, 지표, 연습 알림. 포트(`GuardrailClient`)와 내부 형식(`GuardrailRequest` · `GuardrailVerdict`)에만 기댄다.
- `kr.mujoong.realtime.content` (`LiveContentResources.GuardrailPolicy`) — 정책 리소스 적재 — `guardrail/policy-v1.json`을 기동 때 읽어 검증한다.
- `contracts/` — 계약 정본 — 내부 형식 스키마 두 개, `ai-io.md` 10절, 편차 표.

### Build System
- **Type**: Gradle 8.14 멀티 모듈(Java 21, Spring Boot 4.1). 이 작업은 `:realtime-activity` 모듈 안이다.
- **Config Files**: `backend/build.gradle.kts`(`verifyJevLiveIsolation`: `jev-live` 태그를 기본 test · check에서 제외, `jevLiveTest`는 그 태그만 포함, 기본 test는 `JEV_API_KEY`를 시험 JVM에 넘기지 않음), `backend/realtime-activity/build.gradle*`.
- **Build Dependencies**: `JevGuardrailClient`는 JDK `java.net.http.HttpClient`와 `kr.mujoong.contract.json.JsonCodec` · `MujoongClock`(contract-model)만 쓴다. 외부 SDK 없음. `GuardrailPackageArchTest`가 패키지 의존 경계를 강제한다.

### APIs Discovered
- 나가는 외부 호출 1개: `JevGuardrailClient` → 제브 `POST {base-url}{CHECK_PATH}` (지금 `CHECK_PATH = "/v1/guardrail/check"`, **형식 가정**).
- 들어오는 API 변경 없음. 내부 포트 `GuardrailClient.check(GuardrailRequest, Instant) → CompletionStage<GuardrailVerdict>` 하나.
- DB: `realtime.guardrail_event`(V6) 쓰기만.

### Frameworks & Libraries
- JDK `HttpClient`(리다이렉트 안 따름 · 프록시 없음) — 제브 호출
- `JsonCodec`(Jackson 한 곳, J139) — 요청 직렬화 · 응답 `Map` 읽기
- Micrometer(`RealtimeMetrics`) — `mujoong.guardrail.*`
- JUnit 5 · AssertJ · JDK `HttpServer`(시험용 `FakeJev`) — 시험

### Test Coverage
- **Test Directories**: `backend/realtime-activity/src/test/java/kr/mujoong/realtime/{guardrail,session,testing,config,arch,content,startup}`
- **Test Frameworks**: JUnit 5, AssertJ, Mockito, Testcontainers(`*IT`)
- **Coverage Config**: 모듈 JaCoCo 줄 80% 하한(realtime-activity 포함). 유닛 범위 파일은 U5b · U7만 CI가 막는다(`team.md`). U13 보고서 기준 realtime-activity 1,641개 통과(U13 태그 169개).

### Code Quality Indicators
- **Linting**: `-Xlint:all,...`(`-Werror` 없음), ArchUnit(`GuardrailPackageArchTest`), `TimeLiteralScanTest`(숫자 시간 값 금지).
- **CI/CD**: `ci-gate`. `contracts/` 변경 시 전 작업이 돈다(`team.md`).
- **Documentation**: 클래스 Javadoc이 BR · FR · 편차 ID를 인용한다. `JevGuardrailClient` Javadoc에 "형식 가정(실제 제브 응답 형식은 아직 모른다)"이 명시돼 있다.

---

## 집중 스캔 사실 (요청 항목 1 ~ 7)

### (1) 현재 `JevGuardrailClient` (366줄)

**호출 흐름** (`check` → `start`, `JevGuardrailClient.java`)
1. `modelProblem` 있으면 `UNAVAILABLE`. 키(`apiKey.get()`)가 null · 공백이면 `UNAVAILABLE`.
2. `systemPrompts.apply(request.policyVersion())`가 비면 `INVALID_REQUEST`. (정책 판 → 시스템 지시 함수. 운영 배선은 `GuardrailConfiguration.systemPrompt`가 `policy-v1.json`의 `systemPrompt`를 돌려준다.)
3. `endpoint == null` 또는 `JevHosts.check(endpoint, hostMode)` 문제 있음 → `TRANSPORT_ERROR`.
4. 제한 시간 `limit = min(deadline까지 남은 시간, budget)`. 0 이하면 `TIMEOUT`(보내지 않음).
5. 본문 `json.write(requestBody(...))`, `POST`, 헤더 `Content-Type: application/json` · `Authorization: Bearer <키>`. `HttpRequest.timeout(limit)` + `.orTimeout(limit)`로 본문 수신 끝까지 덮는다. 본문 수신기 `BoundedBodySubscriber`가 `MAX_RESPONSE_BYTES = 64 * 1024`를 넘으면 `BodyTooLargeException` → `TRANSPORT_ERROR`.
6. 완료 시 `convert(status, bodyString, request, latencyMs)`. 예외는 `classify`: `TimeoutException` · `HttpTimeoutException` · `CancellationException` → `TIMEOUT`, 그 밖 → `TRANSPORT_ERROR`. 시간 초과 · 전송 오류 시 `sent.cancel(true)`.
7. 호출은 한 번, 재시도 없음(BR2.1). 어댑터는 아무것도 기록하지 않는다.

**요청 본문** (`requestBody`, 지금 가정 형식, 닫힌 키 6개)
```
{ model, systemPrompt, mode, candidateText, recentContext:[{speaker,text}], policyVersion }
```
- `model` = 설정 `properties.model().strip()` 그대로(BR2.3). `systemPrompt` = 정책 리소스 문자열. `mode` = `FREE_TALK|PRACTICE_PEER|PRACTICE_COACH`. `candidateText` = `request.candidate()`. `recentContext[].speaker` = `CHILD|PARTNER|COACH|MUJOONG`.
- 공식 형식(`state` · `model` · `questions`)과 겹치는 키는 `model` 하나뿐이다.

**응답 변환** (`convert`, "응답 모양을 읽는 유일한 곳", BR2.6)
- 상태 코드가 2xx가 아니면 `TRANSPORT_ERROR`(리다이렉트 포함). 본문 전체를 `json.read(body, Map.class)`; 예외 · null이면 `INVALID_JSON`.
- 읽는 최상위 키: `decision`(문자열, `ALLOW|BLOCK|REVIEW`) · `categories`(필수 문자열 배열, 7개 이름 안, 중복 불가) · `policyFlags`(선택, `^[A-Z][A-Z0-9_]*$`) · `reasonCode`(필수, `GuardrailEventRecord.REASON_CODE` 정규식) · `policyVersion`(필수, 1 ~ 80자) · `reviewNeeded`(선택 boolean; `ALLOW`인데 true면 모순이라 `INVALID_JSON`).
- 응답 `policyVersion != request.policyVersion()` → `POLICY_MISMATCH`. 그 뒤 `GuardrailVerdict.allow|block|review(reason, version, latencyMs, categories, flags)`.
- 즉 지금은 **공급자가 `reasonCode` · `policyVersion` · `categories`를 직접 준다고 가정**한다. 공식 `answers` 응답에는 이 값들이 없다(요청 설명 기준). 이 값들을 어디서 얻을지는 이 스캔이 정하지 않는다.

**오류 매핑 요약**
| 상황 | `Failure` |
|---|---|
| 모델 이동 별칭 · 형식 오류, 키 없음 | `UNAVAILABLE` |
| 정책 판의 시스템 지시 없음 | `INVALID_REQUEST` |
| 주소 모양 오류 · 허용 호스트 밖 · 비2xx(429 · 5xx · 3xx 포함) · 본문 64KiB 초과 · 전송 오류 · 준비 중 예기치 못한 예외 | `TRANSPORT_ERROR` |
| 시한 초과 · 취소 | `TIMEOUT` |
| 본문이 JSON 객체 아님, 필수 필드 · 값 위반 | `INVALID_JSON` |
| 응답 정책 판 ≠ 요청 판 | `POLICY_MISMATCH` |
- 문서에 없는 오류 본문(비2xx 본문)은 읽지 않는다. 상태 코드만 본다. 429 · 529도 `TRANSPORT_ERROR` 한 가지이고 재시도하지 않는다.

**호스트 · 모델 · 키**
- 호스트: 생성자에서 `endpointOf(properties.baseUrl())`가 `base`(끝 `/` 제거) + `CHECK_PATH`로 URI를 만든다. `JevHosts.check(URI, Mode)`가 기동 때(`GuardrailStartupRules.problems`는 문자열 주소로 `check(String, Mode)`)와 보내기 직전 두 번 확인한다. `ALLOWED_HOSTS = {"api.typesafe.ai"}`. 모드 STRICT(https + 허용 호스트), DEV(STRICT 또는 루프백), TEST(루프백만). 사용자 정보 · 질의 · 조각이 있는 주소는 거부(ai-io.md 10.11). 주소 설정(`JEV_BASE_URL`)은 **기준 주소**이고 경로는 코드 상수가 붙인다.
- 모델: `JevModels.problem(model)`. 형식 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, 이동 별칭(`(^|[-_.])latest$`, 대소문자 무시)은 `MOVING_ALIAS`로 거부. 사유 열거만 돌려주고 이름 값은 담지 않는다. 고정 버전 모델이 실제 제브에서 요청 가능한지는 코드 어디에도 확인이 없다.
- 키: 필드로 보관하지 않고 호출마다 `Supplier<String> apiKey`(운영 배선: `environment.getProperty("JEV_API_KEY")`)로 읽어 `Authorization` 헤더에만 쓴다. `toString()`은 `JevGuardrailClient[mode=REAL]`뿐(R-F3 · BR2.8).

**`CHECK_PATH`가 쓰이는 모든 곳** (전수 grep, `build/` 제외)
| 위치 | 용도 |
|---|---|
| `JevGuardrailClient.java:59` | 상수 정의(`public static final String CHECK_PATH = "/v1/guardrail/check"`) |
| `JevGuardrailClient.java:108` | `endpointOf`가 기준 주소 뒤에 붙임 |
| `JevGuardrailClient.java:50` | 클래스 Javadoc의 `{@value #CHECK_PATH}` |
| `JevGuardrailClientTest.java:114` | 첫 시험이 `recorded.path()`와 비교 |
| `JevGuardrailClientTest.java:217` | 리다이렉트 시험이 `elsewhere.baseUrl() + CHECK_PATH`로 Location을 만듦 |
- `contracts/` · `docs/` · `scripts/` · `infra/`에는 `CHECK_PATH` 이름이 없다. 다만 `docs/units/u13/report.md` · `design.md`가 경로 문자열 `/v1/guardrail/check`를 "형식 가정"으로 적는다(문서 쪽 문장).
- `systemone`이 저장소에서 나타나는 곳: `contracts/ai-io.md`(호스트 설명), `docs/units/u13/brief.md:25`(`POST https://api.typesafe.ai/v1/systemone`를 실제 어댑터 설명으로 적음), `infra/ci/image-checks.sh:271`(`JEV_BASE_URL: https://api.typesafe.ai` 자리값), 기술명세서 v0.3 §5.7. **코드는 `/v1/systemone`를 쓰지 않는다.**

### (2) `GuardrailRequestFactory` · `GuardrailRequest(ContextTurn)`

- `GuardrailRequestFactory`(순수 함수, 115줄)는 `create(Mode, candidate, List<Turn> recentTurns, policyVersion, Collection<String> childNames) → Optional<GuardrailRequest>`를 가진다. 처리: 아이 이름을 긴 이름부터 한 번 훑어 `"아이"`(`CHILD_ALIAS`)로 치환 → 후보는 `strip()` 후 1 ~ 4000자 밖이면 빈 값 → 맥락은 null · 빈 줄을 건너뛰고 한 줄을 2000자로 자르며(서로게이트 쌍 보호) 마지막 3턴만 남긴다 → 정책 판 길이 1 ~ 80자 밖이면 빈 값.
- `GuardrailRequest(source, mode, candidate, policyVersion, recentContext)` 레코드. `source`는 상수 `"GPT_LIVE_GENERATED"` 하나. `Mode{FREE_TALK,PRACTICE_PEER,PRACTICE_COACH}`, `Speaker{CHILD,PARTNER,COACH,MUJOONG}`, `ContextTurn(speaker, text)`(텍스트 1 ~ 2000자 검증). `toString()`은 글자 수만 낸다(R-F14). 아동 · 보호자 · 세션 · 기기 식별자 칸이 없다(NFR3.1).
- 이것은 **계약 내부 형식**이고 공급자 형식이 아니다. 문서(`guardrail-request.schema.json` description)도 "공급자 실제 요청은 어댑터 안에서만 이 형식에서 바꾼다"고 적는다. 팩토리는 질문 · 선택지 · 시스템 지시를 만지지 않는다(클래스 Javadoc).
- 생성 지점은 한 곳이다: `GuardrailCoordinator.start`(`GuardrailCoordinator.java:437`)가 `factory.create(mode, sealed.transcript(), List.copyOf(context), support.policyVersion(), names())`를 부른다. `names()`는 `plan.childContext().displayName()` 하나. `context`는 협력 객체가 `remember(speaker, text)`로 쌓는 최근 3턴 `Deque<Turn>`. 빈 값이면 호출하지 않고 `INVALID_REQUEST`로 `resolve`한다.
- 그 요청을 `JevGuardrailClient.requestBody`가 위 6개 키로 풀어 쓴다. 따라서 **공식 형식으로 바꿀 때 바뀌는 자리는 `requestBody` 쪽(어댑터)과, 질문 · 선택지를 어디서 가져올지다.** `GuardrailRequestFactory` 자체는 후보 문장 + 맥락 + 정책 판만 만들고 질문 문안을 모른다. 요청 설명이 "GuardrailRequestFactory 요청 구성"을 바꾼다고 했으나, 현재 코드에서 공급자 본문 구성은 팩토리가 아니라 `JevGuardrailClient.requestBody`다(사실 기록. 어느 쪽에 둘지는 결정하지 않는다).
- 팩토리 사용자: `GuardrailCoordinator` 한 곳(운영), `GuardrailConfiguration`이 빈으로 만들지만 코디네이터는 `new GuardrailRequestFactory()`를 직접 쓴다(`GuardrailCoordinator.java:199`). 시험: `GuardrailRequestFactoryTest`.

### (3) 응답 → `GuardrailVerdict` 변환과 소비자

**`GuardrailVerdict`**(레코드 `decision, failure, categories, policyFlags, reasonCode, policyVersion, latencyMs`)
- `Decision{ALLOW,BLOCK,REVIEW,FAILED}`. `Failure{TIMEOUT,TRANSPORT_ERROR,INVALID_JSON,POLICY_MISMATCH,UNAVAILABLE,INVALID_REQUEST}` (여섯 개). `FAILED`일 때만 `failure`가 있다(생성자 검증).
- `reasonCode`는 `^[A-Z][A-Z0-9_]{0,79}$` 필수. `policyVersion`은 null 불가. `latencyMs >= 0`.
- 팩토리: `allow` · `block` · `review`(어댑터), `stubAllow`(대역, `STUB_ALLOW`), `failed(failure, policyVersion, latencyMs)`(사유 코드 = 실패 종류 이름, 분류 · 플래그 빈 목록).
- `eventDecision()`: ALLOW/BLOCK/REVIEW는 그대로, `FAILED`는 `TIMEOUT` 실패만 `TIMEOUT`, 나머지 실패는 `ERROR`.

**소비자별 어떤 필드를 읽는가** (main 코드 전수 grep)
| 소비자 | 읽는 값 | 비고 |
|---|---|---|
| `GuardrailCoordinator.resolve/allowed/blocked/voided` | `isAllow()`, `decision()`, `failure()`, `reasonCode()`, `latencyMs()`, `eventDecision()` | `reasonFor(verdict)`가 `BLOCK→GUARDRAIL_BLOCK`, `REVIEW→GUARDRAIL_REVIEW`, `FAILED(TIMEOUT)→GUARDRAIL_TIMEOUT`, 그 밖 `FAILED→GUARDRAIL_ERROR`, `ALLOW`는 예외 |
| `GuardrailEventRecorder` / 표 `realtime.guardrail_event` | `GuardrailEventRecord(id, guardianId, sessionId, mode, sourceKind, decision, reasonCode, policyVersion, latencyMs, createdAt)` | **`categories` · `policyFlags` 칸이 없다.** V6 열: `decision`(ALLOW/BLOCK/REVIEW/TIMEOUT/ERROR/VOIDED/QUEUE_FULL), `reason_code varchar(80)` + 정규식 CHECK, `policy_version varchar(80)`, `latency_ms int`. 정책 판은 코디네이터가 `support.policyVersion()`(세션에 고정된 값)으로 기록하지 응답의 판이 아니다(`GuardrailCoordinator.java:744`) |
| 지표 `countVerdict` | `decision()`, `failure()==TIMEOUT` | `mujoong.guardrail.blocked{mode,decision=block|review|error}`, `mujoong.guardrail.timeout{mode}`; `countLatency`는 `latencyMs()>0`일 때 `mujoong.guardrail.latency{mode}`; 호출 수 `mujoong.guardrail.calls{mode,source}`; `perceived.wait`는 별도(`RealtimeMetrics.java:75 ~ 83`) |
| 연습 알림 `PracticeSessionExtension.GuardrailBlockedEvent(correlationId, mode, reasonCode, retried)` | `verdict.reasonCode()` | 연습 모드 요청의 **두 번째** 막힘에서만 `env.practiceBlocked`. `reasonCode`는 null 불가이고 `mode==FREE_TALK`이면 생성자가 거부 |
| `categories()` · `policyFlags()` | **main 코드에서 읽는 곳이 없다**(전수 grep). 쓰는 곳은 `JevLiveProbe` 보고서(걸린 분류 열)와 시험뿐 | `LiveContentResources`가 읽는 `categories`는 정책 리소스 파일의 것이다 |
- 따라서 응답에서 `reasonCode` · `policyVersion`을 어떻게 채우든, **소비자가 실제로 쓰는 값은 `decision` · `failure` · `reasonCode`(기록과 연습 알림) · `latencyMs`**다. 단 `reasonCode`는 정규식 `^[A-Z][A-Z0-9_]{0,79}$`를 지켜야 한다(어기면 `GuardrailVerdict` · `GuardrailEventRecord` 생성자가 던지고, 표 CHECK 위반은 기록기가 영구 실패로 버린다).
- 막힘 처리(`blocked`): 첫 막힘은 `retries=1`로 턴당 한 번 재지시(`discardForRetry`), 두 번째는 `abandon`(BR6.1 ~ BR6.3). `REVIEW`는 `BLOCK`과 같은 폐기 경로를 탄다(사유만 `GUARDRAIL_REVIEW`).
- `GuardrailRequest.Mode`의 운영 사용: 지금 운영에서 실제로 부르는 것은 `FREE_TALK`뿐이다(BR1.1). 연습 모드 검사는 U12 몫으로 남아 있다.

### (4) 계약 내부 형식 · 정책 리소스 구조

**`contracts/schemas/guardrail-request.schema.json`** (닫힌 객체)
- 필수: `source`(const `GPT_LIVE_GENERATED`), `mode`(enum 3), `candidate`(1 ~ 4000자), `policyVersion`(1 ~ 80자). 선택: `recentContext`(최대 3, 항목 `speaker` enum 4 + `text` 1 ~ 2000자). 식별자 칸 없음. 예시 `policyVersion`은 `jev-guardrail-v1`(U13-b로 정정됨).

**`contracts/schemas/guardrail-result.schema.json`** (닫힌 객체)
- 필수: `decision`(`ALLOW|BLOCK|REVIEW`), `categories`(유일 배열, 최대 7, enum `DIAGNOSIS · TREATMENT · SCORE · CAUSE · GENERALIZATION · EMOTION_CLAIM · PRESSURE`), `reasonCode`(`^[A-Z][A-Z0-9_]{0,79}$`), `policyVersion`(1 ~ 80자). 선택: `policyFlags`(`^[A-Z][A-Z0-9_]*$`), `reviewNeeded`(boolean, "decision이 REVIEW이면 true"). description이 "어댑터가 공급자 응답을 바꿔 만드는 내부 형식"이라고 못 박는다. `FAILED`는 스키마에 없다(어댑터 내부 값).
- 이 스키마의 첫 `examples` 항목을 `JevGuardrailClientTest`가 공급자 응답으로 그대로 보낸다(아래 (5)). 스키마를 바꾸면 그 시험의 의미가 바뀐다. `U8ContractSchemasTest`(contract-model)가 두 스키마를 적재 · 검증하고 `guardrail-result`의 잘못된 예(`RUDENESS` 범주, 긴 `reasonCode`)가 거절되는지 단언한다.

**정책 리소스 `backend/realtime-activity/src/main/resources/guardrail/policy-v1.json`**
- 키: `version`(`jev-guardrail-v1`), `source`(Luna · JEV 구현 인계서 v2 §3.1 · §3.3), `systemPrompt`(한국어 긴 문자열: 검수기 역할, 판정 대상 `candidateText`, 확인 요소 5개, `ALLOW/BLOCK/REVIEW` 선택 규칙, "필드: decision, categories, policyFlags, reasonCode, reviewNeeded, policyVersion" JSON 한 객체 출력 지시), `categories`(7개 이름 배열), `modes`(3개).
- `LiveContentResources.guardrail(String)` 검증: `version` · `source` 비어 있지 않음, `systemPrompt` 비어 있지 않음, `categories` 정확히 7개 · 중복 없음 · `CODE` 정규식, `modes` 정확히 `GuardrailMode`-계열 3개(FREE_TALK · PRACTICE_PEER · PRACTICE_COACH). 어기면 기동 거부. `GuardrailPolicy(version, source, systemPrompt, categories, modes)` 레코드로 노출. 이 파일의 `source`를 포함해 `LiveContentRegistryTest` · `U8RealtimeStartupChecksTest`가 값 · 거절을 단언한다.
- 시스템 지시의 모양 요구(JSON 객체 출력)는 지금 가정 형식과 한 쌍이다. 공식 `questions` 형식에는 `instructions` · `criteria`가 따로 있다.
- **분류 선택지와 검사 질문 문안이 이미 있는 곳**:
  1. 코드 쪽 정책 리소스에는 **7개 범주 이름**(`categories`)과 **시스템 지시 문장**(`systemPrompt`)만 있다. 범주별 설명(criteria 후보)과 `PASS` 항목은 **없다**. `PASS`는 이 리소스 어디에도 없다.
  2. Luna JEV 인계서 v2 §3.2(`aidlc/spaces/default/knowledge/documents/mujungi_Luna_JEV_guardrail_implementation_handoff_v2_2026-10-15.md`)에 위 7개 범주별 "위험 판단 예시"와 "원칙" 표가 있다(예: `DIAGNOSIS` = 발화를 근거로 질환 · 장애 · 상태를 진단 / 진단적 단정 금지). 코드 리소스에는 옮겨지지 않았다.
  3. **AI 동작 명세 v0.2 §10.2**(`.../15_기술명세_AI동작_1015/무중_AI동작명세_v0.2.md` 959 ~ 985줄)에는 다른 분류 체계가 있다: 검사 질문 한 문장("아래 문장이 6~12살 아이와 보호자에게 보이거나 들려도 되는가? 해당하는 금지 분류를 고르라. 아이가 직접 한 말을 인용 부호 안에 그대로 옮긴 것은 금지가 아니다.")과 분류 선택지 **11개**: `DIAGNOSIS · SCORE · TREATMENT · DEFICIT · GENERALIZE · MIND_READING · FORCE · PRESSURE · PERSONA · LEAK · PASS`(각 한 줄 설명 포함). "PASS가 1순위이고 문턱(기술명세서 §13)을 넘을 때만 통과"라고 쓰여 있다.
  4. 두 체계는 **코드가 다르다**: 정책 리소스 7개(`CAUSE` · `GENERALIZATION` · `EMOTION_CLAIM` 포함) vs 명세 §10.2 11개(`DEFICIT` · `GENERALIZE` · `MIND_READING` · `FORCE` · `PERSONA` · `LEAK` · `PASS` 포함). 겹치는 이름은 `DIAGNOSIS` · `TREATMENT` · `SCORE` · `PRESSURE`뿐이다. 이 차이를 해소하는 결정은 이 스캔이 하지 않는다. `contracts/schemas/guardrail-result.schema.json`의 `categories` enum, `policy-v1.json`의 `categories`(정확히 7개 검증), `LiveContentResources.GUARDRAIL_CATEGORY_COUNT`, `LiveContentRegistryTest`/`U8RealtimeStartupChecksTest`의 7개 단언, `JevGuardrailClient.CATEGORIES`가 모두 7개에 묶여 있다.
  5. 기술명세서 v0.3 §5.7: 호출 `POST https://api.typesafe.ai/v1/systemone`, 모델 고정(예 `jev-1.13.0`), "받는 것: 고른 분류, 분류별 확률. 정확한 필드 이름은 계정 승인 뒤 API 문서로 확정", 응답 시간 70 ~ 500ms, 제한 시간 후보 800ms(§13).
- 문턱: AI 동작 명세 §10.2 · 기술명세서 §13은 문턱을 "AI 성능 시험으로 정한다"로 둔다. 코드에는 제브 문턱 설정 키가 **아직 없다**(전수 확인: `GuardrailProperties`는 `mode` · `baseUrl` · `model` 세 개뿐). 판정 쪽에는 별개 키 `mujoong.judgment.min-confidence: 0.6` · `min-margin: 0.15`(Luna 진행 중 판정용)가 있어 이름 공간이 다르다.

### (5) 이 변경이 닿는 시험 (형식에 얼마나 묶였는가)

**`JevGuardrailClientTest`** (445줄, `@Tag("u13")`, 가짜 `FakeJev` = 루프백 JDK HttpServer + `LogCapture`; 시나리오 11개)
| # | 시험 | 형식 결합 |
|---|---|---|
| 1 | `anAllowAnswerIsConvertedAndTheRequestShapeIsPinned` | **강함**: 경로 `CHECK_PATH`, `Bearer` 헤더, 본문 키 정확히 6개(`model, systemPrompt, mode, candidateText, recentContext, policyVersion`)와 각 값, 응답 `answer(...)` 형식으로 ALLOW 변환 |
| 2 | `blockAndReviewAnswersKeepCategoriesFlagsAndReasonCodes` | **강함**: 응답에서 `categories` · `policyFlags` · `reasonCode` · `reviewNeeded`를 그대로 옮기는 것을 단언 |
| 3 | `theContractExampleOfTheResultSchemaIsTheAssumedResponseFormat` | **강함**: `guardrail-result.schema.json`의 첫 예시를 공급자 응답으로 보낸다 |
| 4 | `aSlowAnswerEndsAsTimeoutWithOneCallAndNoRetry` | 약함: 응답 본문 형식은 `allowBody()`가 쓰나 단언은 TIMEOUT · 호출 1회 |
| 5 | `theDeadlineBoundaryUsesTheInjectedClockAndTheSmallerOfDeadlineAndBudget` | 약함: `allowBody()`만 빌려 씀 |
| 6 | `transportFailuresAreBlockingResultsWithoutRetryAndRedirectsAreNotFollowed` | 약함: 상태 500 · 429 · 리다이렉트(`CHECK_PATH` 사용) · 64KiB 초과 · 닫힌 서버. 경로 상수만 바뀌면 같이 바뀐다 |
| 7 | `malformedAnswersAreInvalidJsonNotAllow` | **강함**: 12개 나쁜 본문(비JSON · 배열 · null · `{}` · 잘못된 decision · 모르는 범주 · 소문자 플래그 · 나쁜 reason · policyVersion 없음 · reviewNeeded 문자열 · ALLOW+reviewNeeded=true)이 모두 가정 형식 기준 |
| 8 | `aDifferentPolicyVersionInTheAnswerIsAPolicyMismatchEvenForAnAllow` | **강함**: 응답의 `policyVersion` 비교 가정 |
| 9 | `itRefusesToSendWhenTheKeyIsBlankTheModelIsAMovingAliasOrThePolicyHasNoPrompt` | 약함: 요청 전 거절, 공급자 호출 0건. 형식과 거의 무관 |
| 10 | `aHostOutsideTheAllowListIsRejectedRightBeforeSendingEvenWithAWorkingServer` | 약함: 호스트 규칙 |
| 11 | `theKeyIsReadPerCallAndItsValueNeverAppearsInTheBodyTheResultsOrTheLogs` | 중간: 본문 문자열에 키가 없음을 단언(본문 형식이 바뀌어도 성립), `allowBody()` 사용 |
- 도우미 `answer(...)` · `allowBody()` · `request()`가 시험 파일 안에 있고, `ContractExamples.firstExample`이 `mujoong.repo.root` 시스템 속성(기본 `../..`)으로 계약 스키마를 읽는다.

**`GuardrailRequestFactoryTest`** (190줄, 시험 10개): 팩토리 순수 함수 시험. 이름 치환 · 3턴 · 길이 한계 · 서로게이트 · 정책 판 길이 · 레코드 검증. **공급자 선 형식과 무관**(내부 형식 `GuardrailRequest`만 본다). `instructionsInsideTheCandidateOrTheContextStayInsideDataFieldsAndNothingElseCarriesThem`은 `GuardrailRequest`를 JSON으로 풀어 지시문이 데이터 필드에만 있는지 본다(내부 형식 기준).

**`GuardrailConfigurationTest`** (131줄): `guardrailClient(...)` 빈이 모드별 구현(`StubGuardrailClient` · `JevGuardrailClient` · `UnavailableGuardrailClient`)을 돌려주는지, REAL이 키 없으면 `available()` 거짓, `systemPrompt(content, version)`이 정책 판에서 시스템 지시를 돌려주는지 단언. **`systemPrompt` 보조 메서드의 존재와 반환(`Optional<String>`)에 묶였다.** 어댑터 생성자 서명(`GuardrailProperties, Mode, Supplier, Function<String,Optional<String>>, Duration, MujoongClock, JsonCodec`)이 바뀌면 이 시험의 `client(...)` 호출도 바뀐다. 요청 · 응답 형식에는 무관.

**`JevLiveProbe` / `JevLiveTest` / `JevLiveTaskIsolationTest`**
- `JevLiveProbe`(225줄): 운영 어댑터를 STRICT 주소 규칙 · 정책 리소스 · `application.yml`의 `jev-budget`으로 만들어 합성 표본 5개(`S1-benign-offer` 허용 기대 1 · `S2-diagnosis` · `S3-score` · `S4-treatment` · `S5-pressure` 막힘 기대 4)를 `GuardrailRequest`로 보낸다. `Outcome{REACHED,NOT_REACHED,SKIPPED_NO_KEY,SKIPPED_NO_CONFIG}`. 보고서에 `categories` 열을 쓴다(`v.categories()`). `GuardrailRequest.of(FREE_TALK, ...)`와 `JevGuardrailClient` 생성자 서명, `GuardrailVerdict.categories()` · `failure()`에 묶임. `JEV_BASE_URL`/`JEV_MODEL`/`JEV_API_KEY` env 이름도 여기 있다(`BASE_URL_ENV` · `MODEL_ENV`).
- `JevLiveTest`(29줄, `@Tag("jev-live")`): `probe.run()` 결과가 `REACHED`인지만 단언. 분류 품질은 합격 조건이 아니다.
- `JevLiveTaskIsolationTest`(193줄): 분리 규칙(빌드 스크립트 문자열 검사), 가짜 클라이언트로 표본 5개가 정확히 한 번씩 가는지, 보고서에 후보 원문 · 키 · 주소가 없는지. 선 형식과 무관(가짜 `ClientFactory` 사용), `GuardrailProperties`/`GuardrailVerdict.failed` 서명에는 묶임.

**그 밖에 닿거나 의존하는 시험**
- `config/ConfigKeyContractTest`: `GuardrailProperties`의 레코드 잎 키 ↔ `application.yml` ↔ 묶인 값 대조(아래 (6)).
- `arch/TimeLiteralScanTest`: `JevGuardrailClient.java`의 `.orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS)` 줄이 문자열 기준 허용 항목으로 들어 있다(`TimeLiteralScanTest.java:91`). **어댑터의 그 줄 문자열을 바꾸면 이 허용 목록도 같이 고쳐야 한다.** 새 숫자 시간 값 금지(R-F7)는 새 코드에도 적용된다.
- `GuardrailPackageArchTest`(ArchUnit): 패키지 의존 경계.
- `contract-model` `U8ContractSchemasTest`: 두 스키마의 예시 · 거절 단언. 스키마 필드를 바꾸면 이 시험도 같이 바뀐다.
- `content/LiveContentRegistryTest`, `startup/U8RealtimeStartupChecksTest`: 정책 리소스 `version` · `categories` 7개 · `modes` 3개 · `systemPrompt` 시작 문장(`당신은 아동 사회적 의사소통 지원 서비스 '무중'의 출력 안전성 검수기다.`)과 개수 거절 단언. **정책 리소스 구조를 바꾸면 영향이 크다.**
- `session/Guardrail*Test`(Coordinator · Executor · Metrics · PracticeEvent · SafetyTimer · ApprovedSkip) · `GuardrailTurnIT` · `testing/FakeGuardrailClient` · `SessionHarness`: `GuardrailVerdict.allow/block/review`를 빈 `categories`/`policyFlags`로 만들어 쓴다(예: `FakeGuardrailClient.java:88 ~ 98`). 포트 · 내부 형식을 바꾸지 않으면 영향 없음.
- `StubGuardrailClientTest`: `GuardrailVerdict.block("SELF_HARM", POLICY, 300, List.of("SELF_HARM"), List.of())`처럼 임의 `categories` 이름도 만든다(내부 형식은 범주 enum을 강제하지 않음).
- `GuardrailEventRecorderTest` · `GuardrailEventRepositoryIT` · `db/GuardrailEventSchemaIT`: V6 표. 변환과 무관.
- `GuardrailModeStartupChecksTest`: 기동 점검(모드 × 프로필 × 키 × 주소 × 모델). 변환과 무관하지만 `JEV_BASE_URL`/`JEV_MODEL` 키를 쓴다.

### (6) 설정 키 `mujoong.guardrail.jev.*` · `ConfigKeyContractTest` 대조 방식

- 현재 키 세 개(`GuardrailProperties(String mode, String baseUrl, String model)`, `@ConfigurationProperties(prefix = "mujoong.guardrail.jev")`): `mode: ${JEV_MODE:}`, `base-url: ${JEV_BASE_URL:}`, `model: ${JEV_MODEL:}` (`application.yml` 213 ~ 219줄). 상수 `BASE_URL_KEY` · `MODEL_KEY`, `GuardrailMode.KEY`. 키 값은 환경 변수 자리표시자뿐이다. `application-demo.yml` · `application-dev.yml` · `application-prod.yml`에는 제브 키가 없다.
- `ConfigKeyContractTest`(`config/ConfigKeyContractTest.java`):
  - `applicationYmlAndRealtimePropertiesNameTheSameKeysWithTheSameValues`(166 ~ 175줄): `recordLeaves(GuardrailProperties.class, "mujoong.guardrail.jev")`로 레코드 컴포넌트 이름(케밥 변환)을 모아 `application.yml`의 `mujoong.*` 키와 양방향 대조하고, `bindGuardrail(base)`(487 ~ 493줄)로 실제 바인딩한 값과 yml 값을 비교한다.
  - `testProfileOverridesOnlyKeysTheRecordReadsAndStillSatisfiesTheInvariants`(189줄): 시험 프로필 덮어쓰기 키가 레코드 잎에 있는지 확인.
  - `RECORD_KEYS_WITHOUT_DEFAULT`(상수)는 기본값 없이 레코드에만 있는 키의 허용 목록이다(이름만 확인, 내용은 이 스캔에서 읽지 않음).
- **새 키(예: 문턱)를 `mujoong.guardrail.jev.*` 아래에 더할 때 고칠 곳**: ① `GuardrailProperties` 레코드 컴포넌트(+ 생성자 정규화, 값 범위 검증은 지금 레코드에 없음), ② `application.yml`의 `guardrail.jev` 아래 키와 기본값(env 자리표시자 또는 값), ③ `contracts/ai-io.md` 10.11 설정 표(이 표가 키의 계약 정본이며, 숫자 문턱은 시간 값이 아니라 `timing.md`가 아니다) + `contracts/deviations.md` U13 편차 행 추가(새 키 = 호환 분류 "새 키"), ④ `GuardrailProperties`를 만드는 시험 호출부 3곳 이상(`GuardrailConfigurationTest.client`, `JevGuardrailClientTest.client`, `JevLiveProbe.run`, `JevLiveTaskIsolationTest`, `GuardrailModeStartupChecksTest`의 `new GuardrailProperties(...)` 호출), ⑤ 어댑터 생성자 또는 `GuardrailConfiguration.guardrailClient`의 배선. `ConfigKeyContractTest`는 레코드와 yml만 서로 맞으면 통과하므로 대개 시험 본문을 고칠 필요는 없다.
- `TimeLiteralScanTest`는 숫자 **시간** 값만 본다. 0.5 같은 확률 문턱은 시간이 아니므로 R-F7 대상이 아니다. 단, `0.5`를 코드 상수로 박는지 설정 키로 받는지는 설계 결정이고 이 스캔이 정하지 않는다. 요청 설명은 "임시 문턱 0.5는 설정 키"라고 적는다.
- 비밀 이름 목록(R-M9): 새 **비밀** 환경 변수가 생길 때만 해당. 문턱 키는 비밀이 아니다. 단 새 env 이름(예: `JEV_*`)을 쓰면 `infra/ci/gen-env.sh` 등의 자리값 목록에 영향을 주는지 확인해야 한다(이 스캔에서는 `JEV_API_KEY` · `JEV_BASE_URL` · `JEV_MODEL`만 확인).

### (7) 기술 부채 · 위험 (사실만)

1. **응답 변환이 공급자가 주지 않을 수 있는 값에 기대고 있다.** `convert`는 `reasonCode` · `policyVersion` · `categories`(필수)를 응답에서 읽고, 응답 정책 판이 요청 판과 다르면 `POLICY_MISMATCH`로 막는다. 공식 응답(`model` · `answers` · `usage`)에는 `policyVersion`이 없다고 요청에 적혀 있다. `GuardrailVerdict`는 `reasonCode`(null 불가)와 `policyVersion`(null 불가)을 요구하고, 기록 표는 `reason_code NOT NULL` + 정규식, `policy_version NOT NULL`이다. 이 값들을 채울 출처가 응답이 아니면 `POLICY_MISMATCH` 경로의 존재 이유와 `JevGuardrailClientTest` 8번이 달라진다.
2. **분류 체계 두 벌.** 정책 리소스 · 계약 스키마 · 어댑터 `CATEGORIES`는 7개, AI 동작 명세 §10.2는 11개(`PASS` 포함)다. 공식 형식의 `choice` 질문 `criteria`(선택지 라벨 → 설명)에 어느 쪽을 쓰는지가 열려 있다. 7개에는 `PASS` 항목 자체가 없다.
3. **문턱 설정 키가 없다.** `GuardrailProperties`에 문턱이 없고 `application.yml`에도 없다. 판정 규칙(§10.2: PASS가 1순위이고 문턱 이상이면 ALLOW, 다른 분류가 1순위면 BLOCK, PASS가 1순위지만 문턱 미만이면 REVIEW)은 현재 코드 어디에도 없다. 지금 `ALLOW/BLOCK/REVIEW` 판정은 공급자가 직접 준다고 가정한다.
4. **`categories`/`policyFlags`의 소비자가 없다.** main에서 읽는 곳이 없고 `guardrail_event`에도 칸이 없다. 공식 응답의 `probabilities`를 어디에 둘지(내부 형식 · 기록 표 · 지표)는 현재 구조에 자리가 없다. 확률을 `guardrail_event`에 남기려면 새 마이그레이션이 필요한데, `team.md`의 다음 realtime 번호는 V5라고 적혀 있으나 U13이 이미 V6을 썼다(`V6__u13_jev_guardrail.sql`). 이 불일치는 문서 쪽 오래된 표기다(사실 기록).
5. **고정 버전 모델 요청 가능 여부 미확인.** `JevModels`는 형식과 `latest` 별칭만 막는다. 실제 제브가 `JEV_MODEL`로 주는 값(예 기술명세서의 `jev-1.13.0`)을 받는지는 어디에도 확인이 없다. 실호출 증거 없음(`report.md`: `jevLiveTest` 키 없이 `SKIPPED_NO_KEY`까지만).
6. **문서에 없는 오류 본문.** 비2xx 응답 본문은 읽지 않고 상태 코드만 본다. 공식 오류 본문 형식은 요청에 주어진 정본에 없다(요청에서 "문서에 없는 오류 본문"을 위험으로 지목). 이 스캔은 오류 본문을 추정하지 않는다.
7. **429/529 재시도 금지와 BR2.1.** 비2xx는 전부 `TRANSPORT_ERROR`이고 어댑터는 재시도하지 않는다(BR2.1, ai-io.md 10.3 "분류 NONE"). 막힘 뒤의 "다음 행동 지시 한 번 더"는 게이트 단계의 재지시일 뿐 호출 안 재시도가 아니다(`GuardrailCoordinator.blocked`, 턴당 1회). 429/529를 구분해 달리 다루는 코드는 없다. 800ms 시한(`jev-budget`) 안에서 재시도할 여유도 없다. 지침(`project.md` Corrections)은 "바깥 게이트웨이 · 작업이 이미 재시도하는 경로의 안쪽 어댑터는 재시도하지 않는다"고 적는다.
8. **`CHECK_PATH`는 `public static final`이다.** 시험 두 곳이 직접 참조하므로 이름을 바꾸거나 없애면 시험이 컴파일 오류로 같이 바뀐다.
9. **`systemPrompt`가 요청 본문에 한 덩어리로 실린다.** 공식 `questions`는 질문 ID별 `type` · `instructions` · `criteria`다. 한 판 리소스의 `systemPrompt` 한 문자열을 어떻게 나눌지, 또는 어디서 질문 문안을 가져올지는 현재 구조에 없다(정책 리소스는 `categories` 이름 목록과 `systemPrompt` 하나).
10. **R-F15(아동 데이터 외부 전달 확인) 기록은 비어 있다.** U13 보고서: RUNBOOK 단계 4의 제브 R-F15 확인 기록이 아직 비어 있어, 실호출은 합성 자료로만 해야 한다(`JevLiveProbe`는 합성 표본만 쓴다).
11. **`state` 필드(공식 요청의 `state`)에 해당하는 현재 필드가 없다.** 요청에 무엇이 들어가는지 이 스캔은 알 수 없다. 현재 요청에서 이름이 가장 가까운 것은 `mode`/`policyVersion`이지만 같은 뜻이라는 근거는 없다.
12. **상태 코드 · 헤더 동작.** `Content-Type: application/json`과 `Authorization: Bearer`는 이미 공식 정본(Bearer)과 같다. `Accept` 헤더는 보내지 않는다.
13. 이전 U13 기록의 `developer-scan.md`와 달라진 점(참고만 함): 어댑터 · 팩토리 · 기록기 · 협력 객체 · 지표 · 시험이 모두 구현되어 있고, V6 표가 있고, `jevLiveTest` 분리가 있다.

### Technical Debt Signals
- `JevGuardrailClient` Javadoc과 `convert`가 "형식 가정(실제 제브 응답 형식은 아직 모른다)"을 스스로 인정(`JevGuardrailClient.java:48 ~ 53`). 이번 작업은 그 가정을 정정하는 것이다.
- 정책 리소스(7개 범주)와 AI 동작 명세 §10.2(11개 분류) 불일치. 계약 · 리소스 · 어댑터 · 시험 네 곳이 7개 상수에 묶임.
- 확률(`probabilities`)을 담을 자리가 내부 형식 · 기록 표에 없음.
- 어댑터에 숫자 상수 `64 * 1024`(`MAX_RESPONSE_BYTES`)가 있다. 시간 값이 아니라 R-F7 대상은 아니지만 응답이 `answers`로 커지면(질문 수 × 선택지 수 × 확률) 상한을 다시 봐야 한다는 사실만 적는다.

## Handoff Summary
- **Intent-relevant finding**: 공급자 선 형식을 아는 코드는 두 곳이다. 요청 본문은 `JevGuardrailClient.requestBody`(`{model, systemPrompt, mode, candidateText, recentContext, policyVersion}`), 응답은 `JevGuardrailClient.convert`(최상위 `decision · categories · policyFlags · reasonCode · reviewNeeded · policyVersion`)다. 경로 상수 `CHECK_PATH = "/v1/guardrail/check"`(`JevGuardrailClient.java:59`)는 `endpointOf`(108줄)와 시험 두 곳(`JevGuardrailClientTest.java:114, 217`)에서만 쓰이고, 코드에는 `/v1/systemone`이 없다. `GuardrailRequestFactory`는 공급자 형식이 아니라 내부 형식 `GuardrailRequest`(후보 · 최근 3턴 · 정책 판)를 만들고 질문 문안 · 선택지를 모른다. 질문 · 선택지의 원천은 코드에 `policy-v1.json`의 `systemPrompt`와 7개 범주 이름뿐이고, 범주 설명은 Luna JEV 인계서 §3.2에, 11개 분류(`PASS` 포함)와 검사 질문 문장은 AI 동작 명세 §10.2에 있다. 소비자(`GuardrailCoordinator` · `GuardrailEventRecorder` · 지표 · `GuardrailBlockedEvent`)가 실제로 읽는 값은 `decision` · `failure` · `reasonCode`(정규식 필수) · `latencyMs`이고 `categories` · `policyFlags`는 main에서 읽는 곳이 없다. 제브 문턱 설정 키는 아직 없다(`GuardrailProperties`는 `mode` · `baseUrl` · `model`).
- **Risks / follow-up**: (a) 공식 응답에 `reasonCode` · `policyVersion` · `categories`가 없어 `convert`의 `INVALID_JSON`/`POLICY_MISMATCH` 경로와 시험 7 · 8이 가정 형식에 강하게 묶여 있고, `GuardrailVerdict`/`guardrail_event`는 `reasonCode`(NOT NULL + 정규식) · `policyVersion`(NOT NULL)을 요구한다. (b) 분류 체계 7개(리소스 · 계약 스키마 · `JevGuardrailClient.CATEGORIES` · `LiveContentRegistryTest` · `U8RealtimeStartupChecksTest` · `U8ContractSchemasTest`) 대 11개(명세 §10.2) 불일치. (c) 새 문턱 키를 더하면 `GuardrailProperties` · `application.yml` · `ai-io.md` 10.11 · `deviations.md` U13 행과 `new GuardrailProperties(...)`를 부르는 시험 호출부(`GuardrailConfigurationTest` · `JevGuardrailClientTest` · `JevLiveProbe` · `JevLiveTaskIsolationTest` · `GuardrailModeStartupChecksTest`)가 같이 바뀐다. `ConfigKeyContractTest`는 레코드와 yml이 맞으면 통과한다. (d) 어댑터의 `.orTimeout(limit.toNanos(), TimeUnit.NANOSECONDS)` 줄 문자열이 `TimeLiteralScanTest` 허용 목록(91줄)에 들어 있어 그 줄을 고치면 허용 항목도 고친다. (e) 비2xx 본문 형식 · 고정 버전 모델 요청 가능 여부 · 429/529 처리는 코드에도 문서에도 확인이 없고 실호출 증거도 없다(`jevLiveTest`는 키 없이 `SKIPPED_NO_KEY`까지만, R-M11 · R-F15). (f) 계약 변경(스키마 · `ai-io.md` · 편차 표)은 R-M10에 따라 같은 작업에서 `contracts/`도 고쳐야 하고 `ci-gate` 전 작업이 돈다. 이 스캔은 위 항목의 해법을 정하지 않았다.
