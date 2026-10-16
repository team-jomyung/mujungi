## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | security-design.md > SD6, Assumptions & Open Questions 첫 항목 | SD6은 `LUNA_CALL`의 허용 속성에 모델 · 프롬프트 판 · 필드 이름 목록(쉼표로 이은 문자열) · 목적을 넣는다고 하고, 미결 항목은 "기존 `APP_ERROR` 방식을 따르고 길이 상한만 정한다"고 적었다. 그러나 `AttrKey.ValueType`은 NUMBER · ENUM · BOOLEAN · TIMESTAMP · UUID · IDENTIFIER뿐이고(AttrKey.java:114-126), ENUM은 등록된 닫힌 집합만 받으며 미등록은 `UNKNOWN`으로 바뀐다(EventLog.java:232-233). IDENTIFIER는 `errorKind` 전용이다. 자유 문자열이나 목록 속성은 지금 구조에 없다. 운영(LENIENT)에서는 맞지 않는 속성이 조용히 버려져 감사 기록이 비게 된다. | 속성별 값 형식을 정한다. 목적 · 사유 · 모델 · 프롬프트 판은 닫힌 열거값으로 등록할지(모델 · 판이 바뀔 때마다 enum을 고쳐야 함), 새 `ValueType`을 더할지 정한다. 필드 이름 목록은 열거값 집합이나 개수 · 해시로 줄이는 안을 포함해 선택한다. 또 `EventCode`/`AttrKey` 변경이 `contracts/` 관측 문서와 사건 코드 개수 대조 시험(R-M10)에 닿는다는 점을 SD6에 적는다. | Resolved (reviewer) |
| R-02 | Major | security-design.md > SD11 | "`@ConditionalOnMissingBean` 기본 빈보다 먼저 등록"이라고만 적고 방법이 없다. 기본 실패 빈은 일반 `@Configuration`의 `@ConditionalOnMissingBean`이다(TextAiConfiguration.java:16-17, ResultTextConfiguration.java:6-7). 이 조건은 구성 클래스 처리 순서에 따라 달라져서, 순서가 어긋나면 키가 있어도 실패 기본 빈이 먼저 등록되어 어댑터가 조용히 무시된다. 장애로 드러나지 않고 모든 호출이 `PROVIDER_UNAVAILABLE`로만 보인다. | 순서에 기대지 않는 방식을 정한다. 예를 들어 기본 빈에 `@ConditionalOnProperty`나 어댑터와 같은 `LunaSettings` 켜짐 조건을 걸거나, 어댑터를 `@Primary`로 두거나, 하나의 구성 클래스 안에서 `@Import` 순서를 고정한다. 키가 있을 때 어댑터 빈이 선택되는 것을 컨텍스트 시험으로 단언하는 항목도 SD11에 넣는다. | Resolved (reviewer) |
| R-03 | Minor | security-design.md > SD3 · SD5 | 시도 시한이 응답 본문 수신 전체를 덮는지 정해지지 않았다. JDK `HttpRequest.timeout`은 응답 헤더까지만 덮으므로, 바이트 수신기가 본문을 천천히 흘리는 응답(slow-drip)을 읽는 동안 `min(마감, 목적 시한)`을 넘길 수 있다. 바이트 상한(NFR3.10)만으로는 시간이 막히지 않는다. | 본문 수신에도 같은 마감을 건다(수신기가 `MujoongClock`으로 남은 시간을 확인하거나 `CompletableFuture.orTimeout`을 쓰고 취소한다). 느린 본문 대본을 `LunaResponsesStub`에 더한다. | Resolved (reviewer) |
| R-04 | Minor | security-design.md > SD3 | 새 설정 키 `mujoong.luna.max-response-bytes`는 시간 값이 아니라서 `TimeLiteralScanTest`와는 무관하지만, 두 앱 `application.yml`과 `ConfigKeyContractTest`가 대조하는 계약 문서에 키를 더해야 하는지 적혀 있지 않다. 다른 `mujoong.luna.*` 키도 같다. | 키가 `contracts/`의 설정 키 표에 오르는지 SD3 또는 Code Generation 계획에 한 줄로 못 박고, 오르면 R-M10대로 같은 커밋에서 고친다. | Resolved (reviewer) |
| R-05 | Minor | logical-components.md > LunaSettings · LunaHosts, security-design.md > SD3 | 호스트 규칙에 `lunaLiveTest`는 "프로필 없이" `LunaClient`를 직접 만든다고 했고, test는 루프백만 허용한다. 프로필이 없을 때 어느 규칙을 적용하는지(https + `api.openai.com`만)가 `LunaHosts` 함수 시그니처 수준으로 정해지지 않아, 프로필이 비어 있으면 dev의 "형식만" 규칙으로 흘러 임의 호스트가 허용될 위험이 남는다. | 프로필이 없거나 인식되지 않으면 가장 엄격한 규칙(https + 허용 호스트)으로 판정하고, 이를 단위 시험에 포함한다. | Resolved (reviewer) |
| R-06 | Minor | security-design.md > SD7 | ArchUnit 규칙이 `..luna..`는 "slf4j를 뺀 로깅 구현"에 의존하지 않는다고 하는데, 문장이 모호하다. `kr.mujoong.contract.log`는 허용되고 `org.slf4j`는 금지인지(팀 규칙: 운영 코드는 `LoggerFactory` 직접 사용 금지) 읽는 사람마다 다르다. | 허용 · 금지 패키지를 목록으로 적는다(금지: `org.slf4j..`, `ch.qos.logback..`; 허용: `kr.mujoong.contract.log..`). | Resolved (reviewer) |

> R-01 Reviewer note: `AttrKey.ValueType`에 LABEL · NAME_LIST를 더하고 형식 규칙(정규식 · 최대 32개)과 어긋나면 `UNKNOWN`이 되도록 고정했다. 같은 커밋에서 고칠 목록(EventCode · AttrKey · 포매터 시험, log-hygiene-check, RUNBOOK, contracts/ai-io.md 10절)도 적었다. 현재 코드의 enum(AttrKey.java:115)에는 아직 없지만 이는 Code Generation 몫이다.; Required action: 없음. 구현 때 위 목록을 같은 커밋에서 처리한다.

> R-02 Reviewer note: 순서 의존을 없앴다. `LunaKeyPresentCondition`과 `LunaKeyAbsentCondition`으로 어댑터와 실패 기본 빈을 서로 배타적으로 등록하고, 키 있음 · 키 없음 · 시험 대역 세 경우를 컨텍스트 시험으로 단언한다. 기존 `@ConditionalOnMissingBean`(TextAiConfiguration.java:17, ResultTextConfiguration.java:6)과 함께 두는 것도 맞다. 키가 있는 환경에서 대역 빈을 넣으면 두 빈이 생길 수 있으나, 시험 환경에는 키가 없으므로 막을 정도는 아니다.; Required action: 없음. 컨텍스트 시험은 키 없음 환경에서 돌리도록 구현한다.

> R-03 Reviewer note: `HttpRequest.timeout`이 헤더까지만 덮는다는 점을 인정하고, `CompletableFuture`에 `orTimeout`과 요청 취소를 더해 본문 수신까지 시한을 덮는다.; Required action: 없음.

> R-04 Reviewer note: `LunaProperties`를 두 앱 `ConfigKeyContractTest` 레코드 목록에 올리고, 키 이름 · 기본값을 `contracts/ai-io.md` 10절에 적고, 시간 값이 아니라 timing 계약은 건드리지 않는다고 밝혔다.; Required action: 없음.

> R-05 Reviewer note: 호스트 규칙 모드를 STRICT · DEV · TEST 셋으로 고정하고, 프로필이 없거나 모르는 프로필이면 STRICT가 되게 했다.; Required action: 없음.

> R-06 Reviewer note: ArchUnit이 막는 패키지(slf4j · logback · JUL · log4j · 상대 앱 · 가드레일)와 허용하는 기록 패키지(`kr.mujoong.contract.log..` · `..observability..`)를 분명히 했고, `System.out` · `System.err`도 금지했다.; Required action: 없음.

### Summary

이전 지적 여섯 건이 모두 설계 문서에 반영됐고, 수정이 새로 만든 결함은 찾지 못했다. 이 설계는 구현에 들어가도 된다.
