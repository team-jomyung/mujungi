# U9 Luna 호출부 — Functional Design 질문지

> 근거: `../../../inception/units-generation/unit-of-work.md`(U9), `../../../inception/contract-design/contract-summary.md`(C-DS4 ~ C-DS7, 열린 질문 1), `../../../inception/requirements-analysis/requirements.md`(FR1.1 ~ FR1.4, NFR3), `../../../inception/user-stories/stories.md`(US2.4), U8 `functional-spec.md` W3 · 8.4, 인계서 C §5 · §9.
> 코드 · 계약 사실(이번 단계에서 직접 확인함)
> - OpenAI SDK(`openai-java`)는 어느 앱에도 없다. 바깥 호출은 모두 JDK `java.net.http.HttpClient`다(`ProviderClient` · `GoogleTokenClient` · `InternalHttpClient`). `backend/gradle/libs.versions.toml`은 "뒤 단계는 이 파일을 고치지 않는다"고 적었다. Jackson은 J139로 `JsonCodec`(Jackson 3, `tools.jackson`) 한 곳에서만 쓴다. `openai-java`는 Jackson 2(`com.fasterxml`)와 OkHttp · Kotlin 런타임을 끌고 온다.
> - `OPENAI_API_KEY` · `JEV_API_KEY`는 U8이 인프라 · 문서 · 비밀 이름 목록에만 넣었고, 앱 설정 · 코드에는 아직 바인딩이 없다. 키를 env에서 바로 읽는 선례는 `ProviderClient`(`PROVIDER_API_KEY`)다.
> - 기존 실패 기본 빈: core `TextAiProvider`(→ `TextAiException(UNAVAILABLE)`), `ResultTextProvider`(→ `ResultAiException("PROVIDER_UNAVAILABLE")`). 둘 다 `@ConditionalOnMissingBean`이다. 재시도는 `TextAiGateway`(기술 재시도 ≤ 1회, 250ms)에, 이름 치환 · 식별자 제거는 `ResultTextGateway.sanitize`에 흩어져 있다. realtime에는 이름 치환 코드가 없고 아이 이름도 받지 않는다.
> - 런타임 스키마 검증 수단이 없다. `json-schema-validator`는 두 앱 모두 시험 범위이고, `contracts/schemas`는 앱 실행 자원에 들어 있지 않다.
> - 텍스트(Responses API)용 가짜 공급자는 없다. `tools/fake-provider`(Node)는 GPT-Live 웹소켓 `/v1/live/sessions`만 흉내 낸다. 시험용 HTTP 대역 `test-support` `ContractStubServer`(루프백, 스키마 검증 · 응답 대본 · 지연)는 있다.
> - Luna 재시도 규칙(Contract Design 열린 질문 1)은 U8 Q2-A로 이미 정해졌다(`ai-io.md` 10.3: 판정 `NONE` · 생성류 `REQUEST_BOUNDED` · 결과류 `JOB_ATTEMPT`). 다시 묻지 않고 구현만 정한다.
> 사람이 골라야 하는 것만 개별 질문으로 묻고, 코드 · 계약 사실로 정해지는 것은 맨 아래 가정 묶음으로 확인받는다.

## Q1. Luna를 무엇으로 부를까 (요구사항 FR1.1과 코드 관례가 다름)

요구사항 FR1.1은 "openai-java Responses API"라고 적었다. 그런데 저장소의 바깥 호출은 모두 JDK `HttpClient`이고, `openai-java`는 Jackson 2 · OkHttp · Kotlin 런타임을 함께 들여와 J139(Jackson은 `JsonCodec` 한 곳)와 부딪힌다. 의존성 목록 파일(`libs.versions.toml`)도 "뒤 단계는 고치지 않는다"고 적혀 있다. 어느 쪽이든 보내는 요청은 같은 Responses API(`POST /v1/responses`, 구조화 출력 `json_schema` strict)다.

A. JDK `HttpClient` + `JsonCodec`으로 Responses API를 직접 부른다. 요청 · 응답 JSON 모양은 어댑터 안의 작은 레코드로 둔다. FR1.1의 'openai-java'는 편차로 적는다 — 장점: 새 의존성 · 공급망 점검 대상이 없고 J139 · 기존 바깥 호출 관례와 같다, 시험 대역(루프백 HTTP)이 그대로 쓰인다 / 단점: SDK가 해 주는 응답 파싱 · 오류 분류를 직접 짜야 한다(요청 1종이라 작음) (추천)
B. `openai-java`를 의존성에 더해 쓴다(FR1.1 그대로) — 장점: 요구사항 문장과 같고 SDK 갱신을 따라간다 / 단점: Jackson 2가 들어와 J139 예외를 새로 만들어야 하고, 의존성 목록 규칙 · 취약점 점검(R-M7) 대상이 늘며, 이미지 크기가 커진다
X. Other (please specify)

[Answer]: A

## Q2. 실제 아동 데이터를 Luna로 보내도 되는지는 코드가 어떻게 판단할까 (R-F15)

팀 규칙 R-F15는 "공급자 약관 · 개인정보 처리 조건과 보호자 동의 범위를 확인하기 전에는 아동 데이터를 외부 모델에 보내지 않는다"이고, `ai-io.md` 10.2는 "'확인됨'을 코드에서 판정하는 방법은 U9가 정한다"고 넘겼다. 키(`OPENAI_API_KEY`)만 넣으면 바로 실제 아동 대화가 나가는 구조면 이 규칙을 사람 기억에 맡기게 된다.

A. 스위치 두 개를 모두 켜야 실제 어댑터가 켜진다: 키가 있고, 운영자가 확인을 마쳤다는 설정(`MUJOONG_AI_CHILD_DATA_APPROVED=true`, 기본 꺼짐)이 있을 때. 하나라도 없으면 지금 실패 경로 그대로다. 요구사항 FR12.1의 합성 자료 실호출은 앱을 거치지 않고, 어댑터 클래스를 직접 부르는 별도 시험 작업(기본 빌드 · CI에서 빠지는 태그)으로 한다 — 장점: 규칙이 코드로 강제되고, 기동 때 꺼진 까닭을 사유 코드로 남길 수 있다 / 단점: 운영 설정이 하나 늘고, 비밀이 아닌 설정이라 RUNBOOK에 절차를 적어야 한다 (추천)
B. 키 하나로 켠다. R-F15는 RUNBOOK 절차(키를 넣기 전에 확인)로만 지킨다 — 장점: 단순하다 / 단점: 키만 넣으면 확인 없이 실제 아동 데이터가 나갈 수 있다
C. A에 더해, 요청마다 해당 아이의 보호자 동의 항목(AI 처리 동의)을 확인한다 — 장점: 가장 촘촘하다 / 단점: 지금 동의 모델에 'AI 외부 처리' 항목이 있는지부터 따로 확인해야 하고, realtime은 동의 정보를 갖고 있지 않아 범위가 커진다
X. Other (please specify)

[Answer]: B

## Q3. Luna 응답을 서버가 어떻게 스키마 검증할까 (FR1.2)

FR1.2는 "서버가 응답의 스키마를 검증하고, 안 맞으면 1회 다시 요청"이다. Contract Design Q2-A는 `contracts/schemas/ai-*.schema.json`을 정본으로 정했고 "코드는 같은 파일로 공급자에 구조화 출력을 요청한다"고 적었다. 그런데 지금 그 파일들은 앱 실행 자원에 없고, 검증 라이브러리는 시험 범위에만 있다.

A. 빌드 때 `contracts/schemas`의 `ai-*.schema.json`을 두 앱 실행 자원으로 복사해 넣고, 같은 파일을 (1) 공급자 요청의 `json_schema`로 보내고 (2) 응답을 `json-schema-validator`(실행 범위로 옮김)로 검증한다 — 장점: 정본 한 곳, 요청과 검증이 같은 스키마라 어긋날 수 없다 / 단점: 실행 의존성 하나와 빌드 복사 단계가 는다 (추천)
B. 스키마는 요청에만 보내고, 응답은 `JsonCodec`으로 앱 레코드에 읽은 뒤 필수 필드 · enum · 범위를 손으로 검사한다 — 장점: 새 실행 의존성이 없다 / 단점: 스키마 파일과 손 검사가 따로 놀아 어긋날 수 있고, 검사 코드를 목적마다 짜야 한다
C. 공급자의 구조화 출력(strict)을 믿고 서버 검증은 JSON 파싱 성공만 본다 — 장점: 가장 간단하다 / 단점: FR1.2 '서버가 검증'을 지키지 못한다
X. Other (please specify)

[Answer]: A

## Q4. 시험 · CI · verify.sh에서 쓸 Luna 가짜 공급자를 어디에 둘까 (R-F1, AC2.3.4)

시험 · CI · `verify.sh`는 가짜 공급자만 써야 한다(R-F1). 지금은 텍스트용 가짜가 없다. `verify.sh` 끝-끝 한 턴(자유대화)이 끝나면 core 결과 작업이 `ResultTextProvider`를 부르는데, 지금은 키가 없어 실패 경로(결과 대체 템플릿)를 탄다.

A. 이번 U9는 Java 시험 대역만 만든다: `test-support`에 루프백 HTTP로 `POST /v1/responses`를 흉내 내는 대역(목적별 정상 · 스키마 위반 · 빈 응답 · 길이 초과 · 429 · 500 · 지연 대본)을 두고 단위 · 통합 시험에 쓴다. `verify.sh`는 지금처럼 키 없이 실패 경로를 확인한다. Node 가짜(`tools/fake-provider`)에 텍스트 경로를 더해 끝-끝으로 실제 어댑터 경로를 돌리는 일은 Build and Test에서 U10 · U14와 함께 정한다 — 장점: U9 범위가 작고, 끝-끝 대본은 내용을 정하는 U10 · U14가 만들 때 맞출 수 있다 / 단점: U9만 끝났을 때 `verify.sh`는 실제 어댑터 경로를 지나지 않는다 (추천)
B. Java 대역과 함께 Node 가짜에도 `POST /v1/responses`를 더하고, `verify.sh` 검증 스택에서 가짜 키 · 확인 설정을 켜서 결과 작업이 실제 어댑터 → 가짜 공급자를 지나게 한다 — 장점: U9만으로 끝-끝 경로가 바로 확인된다 / 단점: 결과 문장 대본을 U14 전에 미리 만들어야 하고, Compose · 비밀 분배 · 사후 단언(P2 로그 위생)을 함께 고쳐야 한다
X. Other (please specify)

[Answer]: A — 단, 사람이 키(`OPENAI_API_KEY`)를 넣으면 바로 실제 호출 시험을 돌릴 수 있어야 한다(사람 추가 요구).

## Assumption Confirmation

아래는 코드 · 계약 사실로 정한 가정이다. 하나라도 다르면 B를 골라 주면 개별 질문으로 바꾼다.

1. **위치 · 이름:** core `kr.mujoong.core.luna`에 `CoreLunaClient`, realtime `kr.mujoong.realtime.luna`에 `RealtimeLunaClient`를 둔다. 두 앱은 코드를 나누지 않는다(ADR-004 · R-F5). 공통 규칙은 `contracts/ai-io.md` 10절과 각 앱 시험으로 맞춘다.
2. **설정:** 키는 `OPENAI_API_KEY`를 `ProviderClient`처럼 env에서 바로 읽고, 비었거나 공백뿐이면 꺼진 것으로 본다(`ai-io.md` 10.7). 모델 이름은 core `OPENAI_ANALYSIS_MODEL`, realtime `OPENAI_JUDGE_MODEL`(기본 `gpt-6-luna`)을 `mujoong.luna.model`로 바인딩한다. 주소는 `mujoong.luna.base-url`(기본 `https://api.openai.com/v1`)이고, demo · prod 프로필은 `https` + 허용 호스트(`api.openai.com`)만, test 프로필은 루프백만 받는다(`ProviderHosts`와 같은 방식). 공급자 요청에는 `store: false`를 넣고, `max_output_tokens`(기본 4000, 조원 하네스 값) · `reasoning.effort`는 목적별 설정값이다(시간 값이 아니다).
3. **꺼졌을 때 (Q2-B 반영):** 키가 있으면 실제 어댑터가 켜지고, 없으면 빈을 만들지 않아 기존 `@ConditionalOnMissingBean` 실패 기본 빈이 그대로 쓰인다(FR1.3). 기동 때 어댑터가 켜졌는지 · 꺼졌는지를 사유 코드 한 줄로 남긴다. 키 없이도 기동은 거부하지 않는다(OPTIONAL). R-F15(약관 · 동의 확인 전 실제 아동 데이터 금지)는 코드 스위치 없이 RUNBOOK의 "키를 넣기 전 확인" 절차로 지킨다. 이 차이를 설계 문서와 `ai-io.md` 10.2의 'U9가 정한다' 자리에 적는다.
3-1. **키를 넣으면 바로 실호출 시험 (Q4 추가 요구):** 실제 공급자를 부르는 시험은 기본 빌드 · `check` · CI · `verify.sh`에서 빠지는 별도 Gradle 작업(예: `./gradlew lunaLiveTest`, 태그 `luna-live`)으로 둔다. 사람이 이 PC의 env에 `OPENAI_API_KEY`를 넣고 그 작업을 돌리면, 합성 자료로 목적별 최소 1회씩 실제 Responses API를 불러 스키마 검증 · 모델 이름 · 지연을 확인한다. 키가 없으면 그 작업은 '건너뜀'으로 끝나고 통과로 보고하지 않는다. 결과는 실제 API 증거로 따로 보고한다(R-F1 · R-M11).
4. **어댑터가 바꾸는 기존 빈:** U9는 core `TextAiProvider` · `ResultTextProvider`의 실제 구현을 지금 인터페이스 그대로(`ai-io.md` 7절 상황 생성 프롬프트, `prompts/result/{task}.{판}.txt`) `CoreLunaClient` 위에 둔다. 프롬프트 · 출력 모양을 바꾸는 일은 U10 · U14가 한다. realtime `JudgeClient` 포트와 그 구현은 U11과 경계가 맞물리므로, U9는 `RealtimeLunaClient`(공통 호출)만 만들고 `JudgeClient` 구현은 U11이 그 위에 얹는다. 기존 `EvaluationProvider`(U5b 판정)는 건드리지 않는다(FR3.1).
5. **재시도 구현:** `ai-io.md` 10.3을 호출부가 목적별 정책으로 받는다. `NONE`(판정)은 한 번만 부른다. `REQUEST_BOUNDED`(상황 · 자체 검토 · 씨앗)는 일시 실패(연결 오류 · 429 · 5xx · 빈 응답)에 기술 재시도 1회, 스키마 불일치에 재요청 1회를 호출자 마감 안에서만 하고, 쓴 시도 수를 결과에 돌려준다(U10이 내용 재생성 1회 예산에 센다). `JOB_ATTEMPT`(결과 · 후보 · 재판정)는 호출 안 재시도 없이 실패를 돌려주고, 결과 작업 재시도가 다시 한다. 길이 초과(`incomplete` · `max_output_tokens`) · 빈 응답 · 거절(refusal) · 스키마 최종 실패는 모든 목적에서 성공이 아니다(BR5.3).
6. **시간:** 새 시간 키는 만들지 않는다. 호출 시한은 호출자가 넘긴 마감과 기존 키(core `mujoong.text-ai.request-timeout` · `result.request-timeout`, realtime `mujoong.timing.judgment.live-budget`) 중 짧은 쪽이다. 연결 시한도 같은 값을 넘지 않는다. 시각은 `MujoongClock`으로 읽고, 숫자 시간 값은 코드에 쓰지 않는다(R-F6 · R-F7).
7. **보내는 필드 · 이름 치환:** 호출부는 보내기 전에 금지 키(`ai-io.md` 10.2: 아동 · 보호자 · 세션 · 기기 식별자, 키, 음성, 이메일, 동의 상태 등)를 지우고, 호출자가 넘긴 별칭 표(아이 표시 이름 → '아이')로 문자열 값을 바꾼다. core는 지금 `ResultTextGateway.sanitize`의 규칙(식별자 키 제거 · UUID → '참조N' · 연락처 가림)을 `CoreLunaClient`로 옮겨 한 곳에서 한다. realtime은 아이 이름을 받지 않으므로 별칭 표가 비어 있고, 금지 키 제거만 한다.
8. **감사 로그:** 공급자 요청마다 `EventLog`에 새 사건 `LUNA_CALL` 한 줄을 남긴다. 속성은 목적 · 모델 · 프롬프트 판 · 보낸 최상위 필드 이름 목록 · 결과 사유 코드 · 지연 · 시도 번호뿐이다. 요청 · 응답 본문, 전사, 이름은 남기지 않는다(FR1.4, R-F14). 앱마다 `Component.LUNA_CLIENT`를 더한다. 요청 · 응답 레코드의 `toString()`은 `[redacted]`이고, 예외 메시지에 본문을 넣지 않는다(기존 관례).
9. **결과 기록:** 성공 결과는 응답 JSON과 함께 실제 응답 `model`(공급자가 돌려준 값) · 보낸 `promptVersion` · 지연을 돌려준다. 결과 행마다 `model` · `prompt_version`을 저장하는 일(FR1.2)은 결과를 저장하는 단위(U10 · U11 · U14 · U15)가 이 값을 받아 한다.
10. **제브 미호출:** Luna 호출부는 제브(가드레일) 코드에 의존하지 않는다. ArchUnit으로 `..luna..`가 가드레일 패키지를 참조하지 못하게 막고, 시험에서 Luna 경로 호출 때 제브 대역 호출 수가 0인지 본다(AC2.4.1).
11. **오류 코드:** 새 HTTP 오류 코드는 더하지 않는다. 실패는 기존 `TextAiException`(UNAVAILABLE · TIMEOUT · INVALID_OUTPUT) · `ResultAiException`(PROVIDER_UNAVAILABLE · PROVIDER_ERROR · TIMEOUT · SCHEMA_INVALID)과 realtime 판정 보류 사유로 바꿔 돌려준다. 인증 실패(401 · 403)는 재시도하지 않는다.
12. **시험:** test-after, Standard(컴포넌트당 5 ~ 8개 + 핵심 경계 통합). 시험 태그 `@Tag("u9")`, 범위 파일 `backend/coverage-scope/u9.txt`(줄 80%). 기본 시험은 Q4-A의 Java 루프백 대역만 쓰고 실제 공급자는 부르지 않는다(R-F1). 실호출은 3-1의 별도 작업에서만 한다.

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions

## Q5. (검토 뒤) 설계 검토 지적을 어떻게 처리할까

아키텍처 검토는 '진행 가능'이었지만 Major 2건 · Minor 5건이 남았다(`reviews/review-01.md`). R-01: UUID 참조화를 호출부로 옮기면 `ResultTextProvider.generate`가 문자열만 돌려줘 참조 표가 게이트웨이로 돌아갈 길이 없다. R-02: 계약 스키마의 `minLength` · `pattern` · `$id` 같은 키워드를 공급자 strict 모드가 거절할 수 있는데 루프백 대역은 이를 잡지 못한다. Minor: R-03 `lunaLiveTest`를 기본 `test`에서 빼는 빌드 방법과 R-F1 편차 표기, R-04 `TextAiException` 변환 결정표, R-05 dev 프로필 · 실호출 시험의 주소 규칙, R-06 추적표에 NFR3 · NFR5 · FR12.1 누락, R-07 기존 `temperature` 값 처리.

A. 설계 보강 후 진행 — 지금 이 문서들에 위 7건의 결정을 넣고(예: R-01 UUID 참조화는 게이트웨이에 남김, R-02 요청용 스키마에서 비지원 키워드를 빼고 서버 검증은 원본 스키마로 · 대역에 거절 모드), 다시 검토를 받은 뒤 다음 단계로 간다 (추천)
B. 코드 생성 계획에서 정하기 — 설계는 그대로 두고, Code Generation 계획(사람이 승인함)에 이 결정을 단계로 넣는다
X. Other (please specify)

[Answer]: A
