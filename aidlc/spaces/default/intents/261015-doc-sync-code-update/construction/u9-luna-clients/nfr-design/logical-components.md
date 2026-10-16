# U9 Luna 호출부 — 논리 컴포넌트

> 근거: `security-design.md`(SD1 ~ SD11), `nfr-design-questions.md`(가정 1 ~ 9), `../functional-design/functional-spec.md`. U9는 새 배포 단위가 없고, 두 앱 안에 같은 모양의 컴포넌트를 각자 둔다(ADR-004 · R-F5).

## 1. 컴포넌트 목록

| 컴포넌트 | 앱 | 패키지(예정) | 책임 | 의존 | 설계 |
|---|---|---|---|---|---|
| `LunaSettings` | core · realtime | `..luna` | `mujoong.luna.*` 바인딩(모델 · 주소 · 목적별 출력 한도 · 추론 강도 · 응답 바이트 상한), env 키 존재 확인, 켜짐 판단 | Spring 설정, `LunaHosts` | BR1, SD3 · SD11 |
| `LunaHosts` | core · realtime | `..luna` | 허용 호스트 상수(`api.openai.com`)와 `check(uri, mode)`(STRICT · DEV · TEST, 그 밖은 STRICT) | — | SD3 |
| `EventLog` 확장 | contract-model | `kr.mujoong.contract.log` | 사건 `LUNA_CALL` · `LUNA_STARTUP`, 속성 키, 값 형식 LABEL · NAME_LIST, `Component.LUNA_CLIENT` | — | SD6 |
| `LunaSchemas` | core · realtime | `..luna` | 실행 자원 `luna/schemas/`의 계약 스키마 적재, 목적별 스키마 짝(검증용 원본 · 요청용 사본 · strict 여부), 문자열 입력 검증 | `json-schema-validator`, `JsonCodec` | BR4.2 · BR4.5, SD2 · SD4 |
| `LunaInputSanitizer` | core · realtime | `..luna` | 금지 키 제거 · 별칭 치환 · 연락처 가림 · 잔존 확인 · 보낸 필드 이름 목록 | `JsonCodec` | BR2, SD1 |
| `LunaTransport` | core · realtime | `..luna` | 공유 `HttpClient`(Redirect NEVER · 프록시 없음), 보내기 직전 주소 재확인, 키 헤더, 바이트 상한 수신, 상태 코드 전달 | JDK `HttpClient`, `LunaHosts` | SD3 |
| `LunaClient` | core(동기) · realtime(`CompletionStage`) | `..luna` | 목적별 시도 · 재시도 · 시한 계산, 응답 분류, 스키마 검증, `LUNA_CALL` 기록, 결과 · 실패 반환 | 위 네 컴포넌트, `MujoongClock`, 앱 `EventLogger` | BR3 ~ BR6, SD4 ~ SD6 |
| `LunaTextAiProvider` | core | `..luna.adapter` | `TextAiProvider` 구현. `SCENARIO_GENERATION` · `json_object` 1회 호출, 실패 → `TextAiException` 결정표 | `LunaClient` | BR3.4 · BR8.1, SD11 |
| `LunaResultTextProvider` | core | `..luna.adapter` | `ResultTextProvider` 구현. `RESULT_TEXT` · `json_object` 1회 호출, `temperature` 무시, 실패 → `ResultAiException` 결정표 | `LunaClient` | BR3.4 · BR8.2, SD11 |
| `LunaConfiguration` | core · realtime | `..luna` | `LunaKeyPresentCondition`일 때만 위 빈 등록(기존 실패 빈은 `LunaKeyAbsentCondition`), `LunaProperties` 바인딩, 기동 사건 `LUNA_STARTUP` 기록, 스키마 · 주소 확인 실패 시 기동 거부 | Spring, 기동 확인 | BR1.1 ~ BR1.4, SD11 · SD12 |
| `LunaResponsesStub` | test-support | `kr.mujoong.testsupport.luna` | 루프백 `POST /v1/responses` 대역: 응답 대본 · 요청 검사 모드 · 받은 요청 보관 | JDK `HttpServer` | BR9.1, 가정 9 |
| `lunaLiveTest` 사례 | core · realtime 시험 소스 | 시험(`@Tag("luna-live")`) | 키가 있으면 목적별 합성 1건 실호출, 없으면 SKIPPED, 보고서 작성 | `LunaClient` | BR9.2, SD8 |

## 2. 실패 영역과 영향 범위

| 실패 | 영향 범위 | 막는 곳 |
|---|---|---|
| 공급자 느림 · 장애 | 그 호출 한 건(시도 ≤ 3, 마감 안) | `LunaClient` 시한 · 재시도 상한 → 호출자 실패 경로 |
| 스키마 파일 · 주소 설정 오류 | 앱 기동 | `LunaConfiguration` 기동 거부 |
| 키 없음 | U9 어댑터 전체 꺼짐(기존 실패 동작) | `LunaConfiguration` |
| 금지 키 잔존 | 그 호출 한 건(미호출) | `LunaInputSanitizer` |
| 거대한 · 리다이렉트 응답 | 그 호출 한 건 | `LunaTransport` |

공유 자원은 앱마다 `HttpClient` 하나와 스키마 짝 캐시뿐이다. 둘 다 읽기 전용이거나 스레드 안전하다. DB · 파일 쓰기는 없다.

## 3. 다른 단위와 닿는 점

- U10 · U14 · U15(core)는 `LunaClient`를 직접 부르고, 목적 · 프롬프트 판 · 별칭 표 · 마감을 넘긴다.
- U11(realtime)은 `JudgeClient` 구현 안에서 `LunaClient`(`CompletionStage`)를 부른다.
- U13(제브)과는 닿지 않는다(SD7).
- Infrastructure Design에서 새 인프라 자원은 없다. 나가는 HTTPS(443) 연결만 필요하고, 이미 GPT-Live가 같은 호스트로 나간다.

## Sources

- `security-design.md`, `nfr-design-questions.md`(같은 폴더)
- `../functional-design/functional-spec.md` 1 · 6절, `../nfr-requirements/tech-stack-decisions.md`
- `../../../inception/domain-design/components.md`(CoreLunaClient · RealtimeLunaClient)

## Assumptions & Open Questions

- 컴포넌트 이름 `LunaClient`는 Domain Design의 `CoreLunaClient` · `RealtimeLunaClient`를 앱 패키지 안에서 줄여 부른 것이다. 클래스 이름을 `CoreLunaClient` · `RealtimeLunaClient`로 그대로 둘지는 Code Generation 계획에서 정한다.
- 나가는 연결이 Nginx · 방화벽을 거치는지는 기존 GPT-Live 연결과 같다고 본다. 다르면 Infrastructure Design에서 확인한다.
