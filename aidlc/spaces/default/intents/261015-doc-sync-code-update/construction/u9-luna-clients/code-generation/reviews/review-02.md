## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-16T04:06:58Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | backend/core-api/src/main/java/kr/mujoong/core/luna/LunaRequest.java 의 생성자 `aliases = Map.copyOf(aliases)`, backend/core-api/src/main/java/kr/mujoong/core/luna/LunaInputSanitizer.java 의 `applyAliases`(realtime 쪽 같은 이름 클래스의 `clean` 안 치환 반복문도 같음) | 별칭 치환이 별칭 표 순서대로 `String.replace`를 반복하는데, 호출자가 준 표를 `Map.copyOf`로 복사하므로 순회 순서가 정해져 있지 않다. 표에 서로 포함 관계인 이름이 있으면(예: 이름 `민수`와 애칭 `수`, 또는 `민수`와 `민수야`) 짧은 쪽이 먼저 치환될 때 `민아이`처럼 이름 조각이 남아 공급자로 나갈 수 있다. 실행마다 순서가 달라져 재현도 어렵다. 아이 이름을 '아이'로 바꾸는 규칙(BR2.2 · NFR3.1)과 R-F14의 핵심 방어선이 입력 순서에 기대는 셈이다. 시험(`LunaInputSanitizerTest`)에 겹치는 이름 사례가 없다. | 치환 전에 별칭 키를 길이 내림차순(같으면 사전순)으로 정렬해 항상 긴 이름부터 바꾸고, 겹치는 이름 사례 시험을 core · realtime 양쪽에 더한다. | Resolved (reviewer) |
| R-02 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/LunaInputSanitizer.java 의 `FORBIDDEN_KEYS` · `isForbiddenKey` · `containsForbiddenKey` | 금지 키 비교가 소문자 정확 일치뿐이라 `child_name` · `api_key` · `device_id` · `guardian-id` 같은 변형 키는 지워지지 않는다. BR2.4의 정리 뒤 안전망 `containsForbiddenKey`도 같은 판정 함수를 다시 쓰므로 독립된 두 번째 검사가 아니다. 계약 스키마 키는 camelCase라 지금 호출부에서는 닿지 않지만, 호출자가 다른 스타일의 맵을 넘기면 통과한다. 새 스키마로 바꾸는 U10 · U14 · U15와 realtime 판정 요청 스키마는 요청 스키마 검증이 한 번 더 막는다(realtime만). | 비교 전에 키에서 `_` · `-`를 제거해 정규화하고 변형 키 시험을 더한다. 안 하기로 하면 camelCase 계약 키만 받는다는 가정을 `contracts/ai-io.md` 10.2에 적는다. | Resolved (reviewer) |
| R-03 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/CoreLunaClient.java 의 `call` 재시도 구간(`left.compareTo(wait) <= 0` 판정 뒤 다음 시도의 `limit`) | 남은 시간이 대기보다 아주 조금만 길어도 재시도하며, 대기 뒤 다음 시도의 시한은 남은 시간 전부라 몇 밀리초짜리 시도가 나갈 수 있다. 공급자를 부르고 곧바로 TIMEOUT으로 끝나므로 과금 · 부하만 낭비한다(마감 초과는 없다). 설계 BR3.6은 '대기가 남은 시간보다 짧을 때'만 요구하므로 규칙 위반은 아니다. | 대기 뒤 최소 시도 시간(설정 키) 이상 남을 때만 재시도하도록 하거나, 현재 동작을 편차 없이 허용한다는 판단을 기록한다. | Resolved (reviewer) |
| R-04 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/LunaTransport.java 의 `send`(보내기 전 거부 경로)와 CoreLunaClient.java 의 `attempt` · `audit`, backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/RealtimeLunaClient.java 의 `new LunaException(outcome.failure, 1)` | 보내기 직전 주소 재확인(REQUEST_REJECTED)이나 빈 키(AUTH_FAILED)로 공급자를 부르지 않고 끝났는데도, core는 `attempts++` 뒤 `LUNA_CALL attempt=1`로, realtime은 `LunaException(..., 1)`과 `attempt=1`로 남긴다. `LunaException`의 문서는 '쓴 공급자 시도 수(호출하지 않았으면 0)'라 의미가 어긋난다. 운영에서 키는 기동 때 확인되므로 드문 경로다. | 전송 계층이 낸 `attemptsUsed=0` 사유는 시도 수에 세지 않거나, 문서 문구를 '전송을 시도한 횟수'로 고친다. | Resolved (reviewer) |
| R-05 | Minor | backend/realtime-activity/src/main/java/kr/mujoong/realtime/luna/RealtimeLunaClient.java 의 `call`(정리 · 스키마 검증 · `json.write` 구간) | 문서는 '이 메서드는 던지지 않는다'고 하지만 `sanitizer.sanitize`의 `LunaException` 외 `json.write(input.payload())` · `schemas.isValidRequest` · 요청 본문 `json.write`가 런타임 예외를 던지면 `CompletionStage`가 아니라 호출 스레드로 예외가 나간다. 판정 턴(U11)이 이 계약에 기대 예외 처리를 생략하면 세션 실행기까지 올라갈 수 있다. | 이 구간을 `try`로 감싸 실패를 `failedFuture(LunaException)`으로 바꾸거나(원인 메시지는 버린다), 문서 문구를 고친다. | Resolved (reviewer) |
| R-06 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/LunaTransport.java 생성자 `HttpClient.newBuilder()`(realtime 쪽 같은 이름 클래스도 같음), LunaConfiguration.java `lunaTransport` 빈 | 앱마다 하나인 `HttpClient`를 만들지만 닫는 길이 없다. JDK 21 `HttpClient`는 `close()`가 있어 컨텍스트가 닫힐 때 `destroyMethod`로 닫을 수 있다. 운영은 앱 하나가 컨텍스트 하나라 영향이 작고, 시험에서 컨텍스트를 많이 만들 때 선택기 스레드가 남을 수 있다. | `LunaTransport`에 `AutoCloseable`을 구현해 빈이 닫을 때 `client.close()`를 부른다. | Resolved (reviewer) |
| R-07 | Minor | backend/contract-model/src/test/java/kr/mujoong/contract/log/StructuredLogFormatTest.java (코드 요약 Code Generation 편차 3), contracts/deviations.md U9 절 | 기존 시험의 '사건 코드 정확히 17종' 단언을 19종 목록으로 바꿨다. 정확 일치 단언은 유지했고 R-M10(포매터와 시험을 같은 커밋에서 고침)에 맞으며 근거도 요약에 적혀 있어 R-F2의 '낮춤'에는 해당하지 않는다고 본다. 다만 기존 단언을 고친 사실이라 사람이 한 번 확인해 두는 편이 안전하다. | 사람이 편차 3을 확인한다. 별도 코드 수정은 필요 없다. | Resolved (reviewer) |
| R-08 | Minor | backend/core-api/src/main/java/kr/mujoong/core/luna/adapter/LunaTextAiProvider.java 의 `input` (코드 요약 '열린 점') | 상황 생성 입력에서 아이 관심사(`interests`)를 뺀다. `contracts/ai-io.md` 7절은 관심사를 입력으로 적어 두었으므로 기존 `TextAiProvider` 경로의 생성 품질이 계약 문장보다 낮아진다. 금지 키(BR2.1)와 부딪히는 정본 사이의 충돌이라 코드 판단이 아니라 설계 판단이 필요하고, 코드 요약이 U10에 넘긴다고 밝혔다. | U10 설계 전에 ai-io.md 7절의 입력 목록과 10.2의 금지 키가 충돌한다는 사실을 deviations.md나 ai-io.md 7절에 한 줄로 남긴다. | Unresolved |

> R-01 Reviewer note: 고쳐졌다. 별칭 표를 긴 이름 먼저 · 같으면 사전순으로 고정하고(LunaRequest 생성자에서도 고정), applyAliases는 왼쪽부터 한 번만 훑으며 각 자리에서 가장 긴 이름을 바꾸고 바꿔 넣은 별칭은 다시 훑지 않는다. 겹치는 이름 조각이 남지 않으며 시험이 추가되었다. 표 순서와 무관한 결과이고 u9 시험이 통과한다.; Required action: 없음

> R-02 Reviewer note: 대부분 고쳐졌다. 1차 판정은 대소문자 · `_` · `-`를 무시하고, 안전망은 글자 · 숫자 외 문자를 모두 지운 꼴로 비교해 독립된 판정이 되었다(child_name · Child-Id · child.name 차단). 다만 두 판정 모두 전체 일치라 `childNameKr` 같은 접두 · 접미 변형은 통과한다. 잔여 위험으로 남기고 막지 않는다.; Required action: 필요하면 접두 · 접미 변형을 잔여 위험으로 문서에 한 줄 적는다

> R-03 Reviewer note: 고쳐졌다. 대기가 남은 시간보다 짧고 대기 뒤에도 기존 `retry.backoff` 이상 남을 때만 재시도한다. 새 숫자 시간 값이 없어 R-F7을 지킨다.; Required action: 없음

> R-04 Reviewer note: 고쳐졌다. 시한 0 · 주소 재확인 · 빈 키는 공급자 시도로 세지 않고 `LUNA_CALL attempt=0` 한 줄로 끝난다. 두 앱 모두 적용되었다.; Required action: 없음

> R-05 Reviewer note: 고쳐졌다. call은 런타임 예외를 잡아 APP_ERROR(예외 종류 이름만)를 남기고 FORBIDDEN_INPUT(시도 0) 예외 완료로 돌려준다. 기록 실패도 삼켜 던지지 않으며 근거 주석이 있다. null 요청도 같은 길로 간다.; Required action: 없음

> R-06 Reviewer note: 고쳐졌다. AutoCloseable로 만들고 컨텍스트 종료 때 HttpClient를 닫는다. close가 진행 중 요청을 기다려 종료가 늦어질 수 있으나 요청 시한이 짧아 허용 범위이다.; Required action: 없음

> R-07 Reviewer note: 이번 수정으로 바뀐 점이 없다. 근거 기록은 이미 확인되었다.; Required action: 없음

> R-08 Reviewer note: 이번 수정과 무관하다. U10 열린 점으로 기록되어 있다.; Required action: 없음

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `./gradlew --offline :core-api:test :realtime-activity:test -PincludeTags=u9` | PASS (종료 코드 0) | 수정분과 추가 시험이 통과한다. 이 결과는 테스트 대역 기준이며 실제 공급자 성공 증거가 아니다(R-M11). |

### Summary

수정 커밋 54cfc89가 R-01 ~ R-06을 코드에서 모두 해소했고 수정 때문에 새로 생긴 결함은 찾지 못했다. 남은 것은 금지 키의 접두 · 접미 변형이라는 경미한 잔여 위험뿐이며 READY이다.
