## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-16T01:05:28Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/functional-design/functional-spec.md > W3 두 번째 글머리표, rules.md > BR2.3 | BR2.3과 W3는 `ResultTextGateway.sanitize`의 일반 규칙(UUID → '참조N')을 `CoreLunaClient`로 옮기고, 게이트웨이는 "호출부가 돌려준 참조 표로 복원한다"고 적었다. 그런데 지금 코드는 게이트웨이가 `sanitize`로 입력을 먼저 정리해 참조 표를 만들고(`backend/core-api/src/main/java/kr/mujoong/core/result/ResultTextGateway.java:34-43`), 공급자 호출 뒤 같은 표로 `restore`한다. 어댑터가 구현할 `ResultTextProvider.generate(Request, Duration)`는 `String`만 돌려준다(`.../result/ResultTextProvider.java`). 참조 표가 이 인터페이스를 지나 게이트웨이로 돌아갈 길이 설계에 없다. 게이트웨이는 호출 전에 이미 정리해 버리므로 호출부 쪽 UUID 참조화는 이중 처리이거나, 표가 어긋나면 `goalSelectionId` 복원이 깨진다. | RESULT_TEXT 경로(`ResultTextProvider` 어댑터)에서는 UUID 참조화·복원 책임을 지금처럼 게이트웨이에 남기고, 호출부는 금지 키 재확인(BR2.4) 정도만 한다고 못박는다. 또는 인터페이스를 바꾸는 방식(표를 돌려주는 반환형)을 정하고 `ResultTextGateway`·관련 시험의 변경을 편차로 적는다. BR2.3의 "옮긴다"와 W3의 "게이트웨이는 표로 복원"을 한 가지 흐름으로 정리한다. | Resolved (reviewer) |
| R-02 | Major | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/functional-design/functional-spec.md > W2 3단계, rules.md > BR4.2 | 설계는 `contracts/schemas/ai-*-output.schema.json` 파일 내용을 그대로 `text.format = json_schema(strict)`로 보내고 같은 파일로 응답을 검증한다(Q3-A). 그러나 이 스키마 파일들은 `$schema` · `$id` · `title` · `examples` · `minLength` · `maxLength` · `minItems` · `maxItems` · `uniqueItems` · `pattern` · `minimum` · `maximum`을 쓴다(`contracts/schemas/ai-judgment-output.schema.json:2-3,19-20`, `ai-scenario-output.schema.json:32-57`). 공급자 strict 구조화 출력은 허용 키워드가 제한돼 있어 이런 키워드가 있으면 요청이 400으로 거절될 수 있다(모델·시점에 따라 지원 범위가 다르다). 루프백 대역은 이를 검사하지 않으므로 기본 시험·CI로는 드러나지 않고, 사람이 키를 넣고 `lunaLiveTest`를 처음 돌릴 때에야 터진다. 공급자용 스키마를 만드는 단계가 설계에 없고, '응답 필드 이름은 첫 실호출에서 확인'(Assumptions 2번)만 열린 점으로 있다. | 요청용 스키마를 만드는 방식을 정한다. 예를 들어 정본 스키마에서 공급자 비지원 키워드를 뺀 요청용 사본을 코드로 만들고, 응답 검증은 정본 파일로 한다. 아니면 요청에는 정본 그대로 보내되 거절 시 사유 코드를 명확히 하고 이 위험을 열린 점으로 올린다. 어느 쪽이든 `lunaLiveTest`의 첫 확인 항목에 '스키마 거절 여부'를 넣고, 루프백 대역이 지원 키워드 밖을 거절하는 모드를 두어 기본 시험에서도 잡히게 한다. 'Q3-A: 요청과 검증이 같은 스키마라 어긋날 수 없다'는 문장은 그에 맞게 고친다. | Resolved (reviewer) |
| R-03 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/functional-design/rules.md > BR9.2, functional-spec.md > W4 · 1절 빌드 행 / aidlc/spaces/default/memory/project.md > R-F1 | `lunaLiveTest`는 시험 명령에서 실제 공급자를 부르므로 R-F1("NEVER 시험 · CI · 검증 명령에서 실제 유료 AI 공급자를 부르지 않는다")의 글자와 부딪힌다. 요구사항 FR12.1 · NFR3은 합성 자료 실호출을 허용하고 사람이 추가 요구로 정했지만, 산출물은 BR9.2 `violation`에 "R-F1 위반"이라고 적을 뿐 이 예외를 편차로 올리지 않았다(7절 편차는 U9-a · U9-b만). 나중 검토자나 에이전트가 같은 규칙 충돌을 매번 다시 만난다. 또한 `luna-live` 태그를 기본 `test`에서 빼는 방법이 적혀 있지 않다. 지금 공통 빌드 로직은 태그 제외를 `-PexcludeTags`로만 받는다(`backend/build-logic/src/main/kotlin/mujoong.java-conventions.gradle.kts:79-86`). 기본 제외를 넣으려면 모든 모듈이 쓰는 이 파일을 바꾸게 되는데, 변경 목록에 없다. R-F2는 `-PexcludeTags` 같은 우회를 금지한다. | 편차 `U9-c`를 추가해 'FR12.1에 따라 합성 자료 실호출 전용 별도 작업은 R-F1의 예외'라고 근거와 함께 적고, 규칙 개정이 필요하면 학습 절차로 올린다. 기본 `test`에서 `luna-live`를 빼는 방법(공통 빌드 로직 수정 범위, 별도 `Test` 작업 정의)을 7절 변경 목록과 Code Generation 계획에 단계로 넣는다. | Resolved (reviewer) |
| R-04 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/functional-design/rules.md > BR8.1, BR4.4, entities.md > LunaFailure.transient | 실패 → `TextAiException` 변환이 결정적이지 않다. BR8.1은 "AUTH_FAILED · LENGTH_EXCEEDED · REFUSED · SCHEMA_INVALID · MODEL_MISSING · FORBIDDEN_INPUT → INVALID_OUTPUT 또는 UNAVAILABLE(retryable=false)"이라 사유마다 어느 쪽인지 정하지 않았다. `EMPTY_OUTPUT`은 `transient=true`(BR4.1)인데 BR8.1은 '출력 문제 → INVALID_OUTPUT'으로 읽히기도 한다. `TextAiGateway`의 재시도 판단은 `retryable`과 사유(UNAVAILABLE · TIMEOUT)에 달려 있어(`.../textai/TextAiGateway.java:51-54`) 이 표가 모호하면 재시도 동작이 구현자 해석에 따라 갈린다. BR4.4의 "그 밖의 4xx → SERVER_ERROR(transient=false)"도 이름과 뜻이 어긋난다. | 사유 11개 × (`TextAiException.Reason`, `retryable`) × (`ResultAiException` code) 한 장의 결정표로 바꾼다. `EMPTY_OUTPUT`의 매핑을 명시한다. | Resolved (reviewer) |
| R-05 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/functional-design/rules.md > BR1.4, functional-spec.md > W1 2단계 · W4 | 주소 규칙이 demo · prod(https + `api.openai.com`)와 test(루프백)만 정한다. 실공급자 호출용 `lunaLiveTest`가 어느 프로필·주소로 도는지, dev 프로필의 규칙(`ProviderHosts`는 dev에서 형식만 본다, `backend/realtime-activity/src/main/java/kr/mujoong/realtime/voice/ProviderHosts.java`)을 따르는지 적혀 있지 않다. `test` 프로필로 돌면 BR1.4가 실호출을 막는다. 또 `ProviderHosts`의 허용 호스트 상수는 재사용하지 않고 코드를 나누지 않는다는 BR7.3과 함께 두 앱이 각자 같은 허용 목록을 복제해야 하는지도 불분명하다. | BR1.4에 dev 프로필 규칙과 `lunaLiveTest`가 쓰는 프로필·주소 결정 방식을 한 줄 추가한다. 허용 호스트 상수는 앱마다 둔다고 적고 `ai-io.md` 10절에 둔 값과 시험으로 대조한다. | Resolved (reviewer) |
| R-06 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/functional-design/traceability.json > upstream_ids · coverage, rules.md > BR4.2 | 근거 문서(functional-spec Sources, rules.md 머리)가 NFR3 · NFR5 · FR12.1을 인용하지만 traceability에는 없다. 팀 규칙(project.md 정정 항목)은 자동 점검이 센 상위 ID를 N/A 행으로라도 넣게 한다. 또 FR1.2 "서버는 응답의 스키마와 **인용**을 검증한다"에서 인용 검증은 BR4.2에 없고 기존 `ResultTextGateway` 경로에만 있다는 점이 명시되지 않았다. | traceability에 NFR3(BR2 · BR6), NFR5(시험 계획), FR12.1(BR9.2)을 행으로 추가한다. 인용 검증은 U9 밖(기존 게이트웨이·U14)이 맡는다고 BR4.2나 9절에 한 줄 적는다. | Resolved (reviewer) |
| R-07 | Minor | aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/functional-design/rules.md > BR1.6 · BR2.5, entities.md > LunaRequest | 기존 `ResultTextProvider.Request`는 `temperature` 값을 가진다(`.../result/ResultTextProvider.java`, 게이트웨이가 판정/생성별 값을 채움 `ResultTextGateway.java:42`). 설계의 요청 모양은 `reasoning.effort`와 `max_output_tokens`만 있고, 이 `temperature`를 어떻게 다루는지(버릴지, 추론 모델에서 거절되는 값인지) 적혀 있지 않다. | 어댑터가 `temperature`를 공급자에 보내지 않는다고 정하고 그 까닭(추론 모델 비지원 가능성)과 `lunaLiveTest` 확인 항목을 적는다. | Resolved (reviewer) |

> R-01 Reviewer note: 해결됨. UUID 참조화와 복원을 ResultTextGateway에 그대로 남겼다. 참조 표는 call() 안의 지역 변수(references)이고 sanitize는 provider.generate 호출 전, restore는 검증 뒤에 일어난다(ResultTextGateway.java:34-43). 그래서 generate가 String만 돌려줘도 복원이 깨지지 않는다. 호출부는 연락처 가림과 금지 키 확인만 안전망으로 한 번 더 한다.; Required action: 없음

> R-02 Reviewer note: 해결됨. 요청용 사본(허용 키워드만 남김)과 검증용 원본을 나눴고, strict 여부를 판정하며, 만들 수 없으면 기동을 거부한다. 루프백 대역에 비지원 키워드 거절 모드를 두고, 실호출 시험의 첫 단계가 REQUEST_REJECTED 여부를 확인한다. 참고로 허용 키워드 목록은 문서 기준이라 공급자 실제 동작과 다를 수 있다. 이 위험은 Assumptions에 기록됐고 실호출 시험이 잡는다.; Required action: 없음

> R-03 Reviewer note: 대부분 해결됨. 편차 U9-c를 적었고, 기본 test에서 luna-live를 고정으로 빼며 lunaLiveTest만 포함하고, 빌드 구성 시험도 계획했다. 다만 mujoong.java-conventions.gradle.kts의 tasks.withType<Test>().configureEach(79-86행 부근)가 모든 Test 작업에 같은 excludeTags를 적용한다. 고정 제외를 그 블록에 넣으면 lunaLiveTest까지 제외되어 includeTags와 충돌한다(제외가 우선). 구현에서 lunaLiveTest가 제외를 덮도록 적용 범위를 분리해야 한다.; Required action: Code Generation 계획에 "고정 제외는 기본 test에만 걸고 lunaLiveTest는 필터를 재설정"을 적고 빌드 구성 시험이 lunaLiveTest의 포함 필터까지 확인하게 한다.

> R-04 Reviewer note: 해결됨. 두 어댑터의 결정표를 사유별로 확정했고 EMPTY_OUTPUT도 매핑했다. BR4.4의 이름은 SERVER_ERROR(5xx)와 REQUEST_REJECTED(그 밖 4xx)로 LunaFailure.reason 열거형과 일치한다.; Required action: 없음

> R-05 Reviewer note: 해결됨. dev 프로필 규칙(허용 호스트 또는 루프백)과 lunaLiveTest의 주소 규칙(프로필 없이 demo · prod와 같음)을 정했다. 허용 호스트 상수는 앱마다 따로 두고 각 앱 시험이 ai-io.md와 대조한다(R-F5 준수).; Required action: 없음

> R-06 Reviewer note: 해결됨. traceability에 NFR3 · NFR5 · FR12.1이 들어갔고 BR로 연결된다. FR1.2의 인용 검증은 게이트웨이의 인용 대조와 U14 소관이며 U9는 스키마까지만 본다고 명시했다.; Required action: 없음

> R-07 Reviewer note: 해결됨. temperature는 요청에 넣지 않고 Request.temperature는 받기만 하고 무시한다. 시험 계획에도 "temperature 없음"이 있다.; Required action: 없음

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| traceability (수동 대조) | 상위 ID 13개가 모두 coverage에 있고, 가리키는 BR이 rules.md에 존재 | 통과 |
| 코드 사실 대조 | ResultTextGateway.call의 지역 참조 표 · restore 순서 확인 | R-01 해결 근거 |
| 코드 사실 대조 | mujoong.java-conventions의 Test 작업 공통 필터 구조 확인 | R-03 잔여 구현 주의 |

### Summary

수정으로 R-01 ~ R-07이 모두 해소되어 개정으로 새 결함은 생기지 않았다. 구현에서 주의할 점은 R-03의 빌드 필터 적용 범위 하나뿐이며, 설계 차원에서는 구현 가능한 상태다.
