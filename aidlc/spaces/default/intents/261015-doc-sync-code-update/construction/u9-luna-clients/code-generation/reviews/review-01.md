## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-16T02:51:01Z
**Iteration:** 1

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | backend/core-api/src/main/java/kr/mujoong/core/luna/LunaRequest.java 의 생성자 `aliases = Map.copyOf(aliases)`, backend/core-api/src/main/java/kr/mujoong/core/luna/LunaInputSanitizer.java 의 `applyAliases`(realtime 쪽 같은 이름 클래스의 `clean` 안 치환 반복문도 같음) | 별칭 치환이 별칭 표 순서대로 `String.replace`를 반복하는데, 호출자가 준 표를 `Map.copyOf`로 복사하므로 순회 순서가 정해져 있지 않다. 표에 서로 포함 관계인 이름이 있으면(예: 이름 `민수`와 애칭 `수`, 또는 `민수`와 `민수야`) 짧은 쪽이 먼저 치환될 때 `민아이`처럼 이름 조각이 남아 공급자로 나갈 수 있다. 실행마다 순서가 달라져 재현도 어렵다. 아이 이름을 '아이'로 바꾸는 규칙(BR2.2 · NFR3.1)과 R-F14의 핵심 방어선이 입력 순서에 기대는 셈이다. 시험(`LunaInputSanitizerTest`)에 겹치는 이름 사례가 없다. | 치환 전에 별칭 키를 길이 내림차순(같으면 사전순)으로 정렬해 항상 긴 이름부터 바꾸고, 겹치는 이름 사례 시험을 core · realtime 양쪽에 더한다. | New |
| R-02 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/LunaInputSanitizer.java 의 `FORBIDDEN_KEYS` · `isForbiddenKey` · `containsForbiddenKey` | 금지 키 비교가 소문자 정확 일치뿐이라 `child_name` · `api_key` · `device_id` · `guardian-id` 같은 변형 키는 지워지지 않는다. BR2.4의 정리 뒤 안전망 `containsForbiddenKey`도 같은 판정 함수를 다시 쓰므로 독립된 두 번째 검사가 아니다. 계약 스키마 키는 camelCase라 지금 호출부에서는 닿지 않지만, 호출자가 다른 스타일의 맵을 넘기면 통과한다. 새 스키마로 바꾸는 U10 · U14 · U15와 realtime 판정 요청 스키마는 요청 스키마 검증이 한 번 더 막는다(realtime만). | 비교 전에 키에서 `_` · `-`를 제거해 정규화하고 변형 키 시험을 더한다. 안 하기로 하면 camelCase 계약 키만 받는다는 가정을 `contracts/ai-io.md` 10.2에 적는다. | New |
| R-03 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/CoreLunaClient.java 의 `call` 재시도 구간(`left.compareTo(wait) <= 0` 판정 뒤 다음 시도의 `limit`) | 남은 시간이 대기보다 아주 조금만 길어도 재시도하며, 대기 뒤 다음 시도의 시한은 남은 시간 전부라 몇 밀리초짜리 시도가 나갈 수 있다. 공급자를 부르고 곧바로 TIMEOUT으로 끝나므로 과금 · 부하만 낭비한다(마감 초과는 없다). 설계 BR3.6은 '대기가 남은 시간보다 짧을 때'만 요구하므로 규칙 위반은 아니다. | 대기 뒤 최소 시도 시간(설정 키) 이상 남을 때만 재시도하도록 하거나, 현재 동작을 편차 없이 허용한다는 판단을 기록한다. | New |
| R-04 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/LunaTransport.java 의 `send`(보내기 전 거부 경로)와 CoreLunaClient.java 의 `attempt` · `audit`, backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/RealtimeLunaClient.java 의 `new LunaException(outcome.failure, 1)` | 보내기 직전 주소 재확인(REQUEST_REJECTED)이나 빈 키(AUTH_FAILED)로 공급자를 부르지 않고 끝났는데도, core는 `attempts++` 뒤 `LUNA_CALL attempt=1`로, realtime은 `LunaException(..., 1)`과 `attempt=1`로 남긴다. `LunaException`의 문서는 '쓴 공급자 시도 수(호출하지 않았으면 0)'라 의미가 어긋난다. 운영에서 키는 기동 때 확인되므로 드문 경로다. | 전송 계층이 낸 `attemptsUsed=0` 사유는 시도 수에 세지 않거나, 문서 문구를 '전송을 시도한 횟수'로 고친다. | New |
| R-05 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/RealtimeLunaClient.java 의 `call`(정리 · 스키마 검증 · `json.write` 구간) | 문서는 '이 메서드는 던지지 않는다'고 하지만 `sanitizer.sanitize`의 `LunaException` 외 `json.write(input.payload())` · `schemas.isValidRequest` · 요청 본문 `json.write`가 런타임 예외를 던지면 `CompletionStage`가 아니라 호출 스레드로 예외가 나간다. 판정 턴(U11)이 이 계약에 기대 예외 처리를 생략하면 세션 실행기까지 올라갈 수 있다. | 이 구간을 `try`로 감싸 실패를 `failedFuture(LunaException)`으로 바꾸거나(원인 메시지는 버린다), 문서 문구를 고친다. | New |
| R-06 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/LunaTransport.java 생성자 `HttpClient.newBuilder()`(realtime 쪽 같은 이름 클래스도 같음), LunaConfiguration.java `lunaTransport` 빈 | 앱마다 하나인 `HttpClient`를 만들지만 닫는 길이 없다. JDK 21 `HttpClient`는 `close()`가 있어 컨텍스트가 닫힐 때 `destroyMethod`로 닫을 수 있다. 운영은 앱 하나가 컨텍스트 하나라 영향이 작고, 시험에서 컨텍스트를 많이 만들 때 선택기 스레드가 남을 수 있다. | `LunaTransport`에 `AutoCloseable`을 구현해 빈이 닫을 때 `client.close()`를 부른다. | New |
| R-07 | Minor | backend/contract-model/src/test/java/kr/mujoong/contract/log/StructuredLogFormatTest.java (코드 요약 Code Generation 편차 3), contracts/deviations.md U9 절 | 기존 시험의 '사건 코드 정확히 17종' 단언을 19종 목록으로 바꿨다. 정확 일치 단언은 유지했고 R-M10(포매터와 시험을 같은 커밋에서 고침)에 맞으며 근거도 요약에 적혀 있어 R-F2의 '낮춤'에는 해당하지 않는다고 본다. 다만 기존 단언을 고친 사실이라 사람이 한 번 확인해 두는 편이 안전하다. | 사람이 편차 3을 확인한다. 별도 코드 수정은 필요 없다. | New |
| R-08 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/adapter/LunaTextAiProvider.java 의 `input` (코드 요약 '열린 점') | 상황 생성 입력에서 아이 관심사(`interests`)를 뺀다. `contracts/ai-io.md` 7절은 관심사를 입력으로 적어 두었으므로 기존 `TextAiProvider` 경로의 생성 품질이 계약 문장보다 낮아진다. 금지 키(BR2.1)와 부딪히는 정본 사이의 충돌이라 코드 판단이 아니라 설계 판단이 필요하고, 코드 요약이 U10에 넘긴다고 밝혔다. | U10 설계 전에 ai-io.md 7절의 입력 목록과 10.2의 금지 키가 충돌한다는 사실을 deviations.md나 ai-io.md 7절에 한 줄로 남긴다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `git diff --stat 586238b..HEAD` | 84개 파일, 매니페스트 경로와 일치. U9와 무관한 경로 없음(contract-model 로그 4개 · `infra/bin/log-hygiene-check` · `infra/ci/test-host-tools.sh` · RUNBOOK · contracts는 모두 BR6 로그 계약에 연결). 기존 게이트웨이(`TextAiGateway` · `ResultTextGateway`)는 변경 목록에 없음 | 계획 · 매니페스트와 어긋남 없음, 기존 게이트웨이 무변경 확인 |
| `./gradlew --offline :core-api:test :realtime-activity:test -PincludeTags=u9` | 통과(종료 코드 0) | U9 시험이 이 PC에서 다시 통과함. 증거 종류는 테스트 대역(루프백 `LunaResponsesStub`) · 합성 자료이며 실제 API 성공이 아님 |
| 운영 코드 금지 패턴 검색(`Instant.now` · `currentTimeMillis` · `nanoTime` · `LoggerFactory` · `System.out` · 키 모양) | `..luna..` 운영 소스 두 앱에서 0건 | R-F3 · R-F6 · R-F14 준수 |
| 코드 읽기 대조 | `HttpClient` `Redirect.NEVER` + `NO_PROXY`, 보내기 직전 `LunaHosts.check` 재확인, 응답 바이트 상한 `BoundedBodySubscriber`, `orTimeout`과 `http.cancel(true)`, 호출자 Future 취소 전파 모두 확인. 예외는 `writableStackTrace=false`에 사유 코드만, `LunaRequest` · `SanitizedInput` · `RawResponse` · `ResponsesRequest` · `LunaResult` `toString`은 본문 숨김. `LUNA_CALL` attrs는 허용 표(`EventCode`)로 제한되고 값은 형식 검사. 빈 조건은 `LunaKeyPresentCondition`과 `LunaKeyAbsentCondition`이 서로 반대라 `TextAiProvider` · `ResultTextProvider`가 정확히 하나. `lunaLiveTest`는 `check` 그래프에서 분리되고 기본 `test`는 `luna-live`를 제외하며 시험 JVM에서 `OPENAI_API_KEY`를 지움(`verifyLunaLiveIsolation`). 두 앱 ArchUnit 규칙은 자기 시험(위반 표본)과 `allowEmptyShould(false)` · 클래스 수 하한을 갖춤 | BR1 ~ BR9 · SD3 · SD7 · SD8 · SD11의 핵심 항목이 코드와 맞음. 위 R-01 ~ R-08만 어긋남 |

### Summary

계획과 설계(BR1 ~ BR9, SD1 ~ SD12)가 코드와 시험에 거의 그대로 옮겨졌다. 보내기 전 정리는 실제로 적용되고(시험이 요청 본문에 이름 · 식별자가 없음을 확인), 로그 · 예외 · `toString`에 본문 · 이름 · 키가 남지 않으며, 전송 계층은 리다이렉트 금지 · 프록시 없음 · 바이트 상한 · 본문까지 덮는 시한을 지키고, 빈 조건과 실호출 분리도 맞다. 가장 눈여겨볼 점은 별칭 치환이 순서에 의존해 겹치는 이름이 부분만 가려질 수 있다는 R-01이고, 나머지는 하드닝 수준이다. Q1-A · Q2-B(R-F15는 RUNBOOK 절차) · Q3-A · Q4-A와 편차 U9-a ~ U9-c는 그대로 존중했다(R-F15가 코드로 강제되지 않는다는 점은 사람이 정한 사항이라 지적하지 않는다).
