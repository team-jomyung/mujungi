# U9 Luna 호출부 — NFR Design 질문지

> 근거: `../nfr-requirements/security-requirements.md`(NFR1.1 ~ NFR6.1, 검토 Q1-A 반영), `../nfr-requirements/tech-stack-decisions.md`, `../functional-design/functional-spec.md` · `rules.md`(BR1 ~ BR9), `../../../inception/contract-design/contract-summary.md`(C-DS4 ~ C-DS7).
> 코드 사실: core `TextAiConfiguration`(가상 스레드 실행기 `textAiExecutor`), realtime `ProviderHosts`(프로필별 호스트 규칙) · `RealtimeStartupChecks`(기동 확인), contract-model `EventLog`(사건 코드별 허용 속성 목록) · `JsonCodec`, test-support `ContractStubServer`(JDK `HttpServer` 루프백).
> U9는 `library` 단위라 성능 · 확장성 · 신뢰성 · 관측 설계 문서를 따로 만들지 않고, 보안 설계와 논리 컴포넌트만 쓴다. 정할 것이 모두 요구 · 설계 · 코드 관례에서 나오므로 아래 가정 묶음으로 확인받는다.

## Assumption Confirmation

1. **논리 컴포넌트(앱마다 같은 모양, 코드는 따로):** `LunaSettings`(설정 바인딩 · 호스트 규칙) · `LunaSchemas`(스키마 짝 · 검증기) · `LunaInputSanitizer`(금지 키 · 별칭 · 연락처) · `LunaTransport`(HTTP 한 번 보내기) · `LunaClient`(시도 · 재시도 · 분류 · 감사 기록). core에는 `TextAiProvider` · `ResultTextProvider` 어댑터 둘이 더 있다. 모두 `..luna..` 패키지 안이고, 어댑터만 기존 인터페이스를 구현한다.
2. **호출 모양:** core는 동기 호출(`call(LunaRequest) → LunaResult`, 실패는 예외)이다. 기존 게이트웨이가 이미 가상 스레드 실행기에서 부르기 때문이다. realtime은 비동기 호출(`CompletionStage<LunaResult>`)이다. U11 `JudgeClient`가 `CompletionStage`를 돌려주는 계약(C-DS4)이기 때문이다.
3. **HTTP 클라이언트:** 앱마다 하나를 공유한다. `Redirect.NEVER`, 프록시 없음(`ProxySelector` 미지정 → 직접 연결), HTTP/2 기본. 연결 시한은 그 시도의 시한을 넘지 않는다. 응답 본문은 바이트 수를 세는 수신기로 상한(`mujoong.luna.max-response-bytes`, 기본 1,000,000바이트)까지만 읽고, 넘으면 `LENGTH_EXCEEDED`다. 상한은 바이트로 센다(기존 게이트웨이의 문자 수 상한보다 엄격하거나 같다).
4. **차단기(circuit breaker) 없음:** 호출량이 적고(세션 · 결과 작업 단위), 재시도는 최대 3회 · 마감 안으로 묶여 있다. 공급자 장애 때 막아 줄 차단기는 두지 않고, 429 · 5xx는 일시 실패로 돌려 각 호출자 규칙(판정 보류 · 결과 작업 재시도 · 상황 생성 실패)이 처리한다. 필요해지면 관측 수치를 보고 따로 정한다.
5. **기동 확인:** 스키마 짝을 만들지 못하거나 주소가 호스트 규칙에 어긋나면 기존 기동 확인 방식(`MUJOONG_STARTUP_REJECTED`)으로 기동을 거부한다. 키가 없는 것은 거부 사유가 아니다(꺼짐 사유 코드만).
6. **감사 기록:** contract-model `EventLog`에 사건 `LUNA_CALL`(허용 속성: 목적 · 모델 · 프롬프트 판 · 필드 이름 목록 · 결과 사유 · 지연 · 시도 번호)과 기동 사건(켜짐 · 꺼짐 사유), `Component.LUNA_CLIENT`를 더한다. 필드 이름 목록은 최상위 키 이름만 쉼표로 이은 값이다. 앱 `EventLogger`를 거쳐서만 남긴다.
7. **허용 호스트 상수:** 앱마다 `LunaHosts`(가칭)에 `api.openai.com` 하나를 두고, 두 앱 시험이 각각 `contracts/ai-io.md` 10절 값과 대조한다. realtime `ProviderHosts`는 GPT-Live 웹소켓용이라 재사용하지 않는다.
8. **빌드:** 두 앱 `build.gradle.kts`에서 `processResources`가 `contracts/schemas/ai-*.schema.json`을 실행 자원 `luna/schemas/` 아래로 복사한다. 시험은 이 복사본이 `contracts/` 원본과 같은지 대조한다. `luna-live` 고정 제외는 기본 `test` 작업에만, `lunaLiveTest`는 그 태그만 포함하고 `check`에 물리지 않는다.
9. **시험 대역:** `test-support`의 Responses 대역은 대본(목적별 정상 · 스키마 위반 · 빈 응답 · 미완료 · 거절 · 429(+`Retry-After`) · 500 · 401 · 302 · 지연 · 큰 본문)과 요청 검사 모드(비지원 스키마 키워드 · `temperature` · `store` 누락이면 400)를 가진다. 받은 요청 본문을 시험이 검사할 수 있게 보관한다(금지 키 · 이름 유출 확인용).

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions

## Q1. (검토 뒤) 보안 설계 검토 지적을 어떻게 처리할까

아키텍처 검토는 '진행 가능'이었지만 Major 2건 · Minor 4건이 남았다(`reviews/review-01.md`). R-01: `LUNA_CALL`에 넣으려는 모델 · 프롬프트 판 · 필드 이름 목록 같은 문자열 속성은 지금 `EventLog` 값 형식(숫자 · 열거 · 참 거짓 · 시각 · UUID · 식별자)에 담기지 않고, 운영에서는 조용히 버려진다. R-02: 어댑터를 '기본 실패 빈보다 먼저 등록'하는 방법이 없어, 순서가 어긋나면 키가 있어도 어댑터가 무시된다. Minor: R-03 느리게 흘리는 응답 본문이 시한을 넘길 수 있음, R-04 `mujoong.luna.*` 설정 키의 계약 표 대조 여부, R-05 프로필이 없을 때 가장 엄격한 호스트 규칙, R-06 ArchUnit 허용 · 금지 로깅 패키지 목록.

A. 문서 고친 뒤 진행 — 속성마다 형식을 정하고(목적 · 사유는 열거 등록, 모델 · 판은 식별자 형식, 필드 이름 목록은 등록된 필드 이름 집합의 개수 · 해시 등) 사건 코드 변경을 계약 · 대조 시험에 잇고, 기본 빈에도 같은 켜짐 조건을 반대로 걸어 순서에 기대지 않게 하고 컨텍스트 시험으로 단언하며, Minor 4건도 반영한 뒤 다시 검토를 받는다 (추천)
B. 코드 생성 계획에서 정하기 — 문서는 그대로 두고, Code Generation 계획에 이 결정을 단계로 넣는다
X. Other (please specify)

[Answer]: A
