# U9 Luna 호출부 — NFR Requirements 질문지

> 근거: `../functional-design/functional-spec.md` · `rules.md`(U9 설계, Q1-A · Q2-B · Q3-A · Q4-A · Q5-A), `../../../inception/requirements-analysis/requirements.md`(NFR1 ~ NFR7), `../../../inception/contract-design/contract-summary.md`(C-DS4 ~ C-DS7), CodeKB `technology-stack.md`, `backend/gradle/libs.versions.toml`(networknt `json-schema-validator` 3.0.6 — Jackson 3.1로 빌드), 팀 규칙 R-F1 · R-F3 · R-F13 · R-F14 · R-F15 · R-M7 · R-M11.
> U9는 `library` 단위라 성능 · 확장성 · 신뢰성 · 관측 문서를 따로 만들지 않고 보안 요구와 기술 선택만 쓴다(단계 정의의 종류별 산출물). 정할 수치가 새로 없고 모두 요구사항 · 설계 · 팀 규칙에서 정해지므로, 아래 가정 묶음 하나로 확인받는다.

## Assumption Confirmation

1. **성능 · 시간(NFR1):** U9에는 자체 지연 목표가 없다. 시도 시한은 호출자 마감과 기존 키(core `text-ai.request-timeout` 3초 · `result.request-timeout` 10초, realtime `timing.judgment.live-budget` 2초) 중 짧은 쪽이다. 실측(P50 · P95 · P99)은 `LUNA_CALL` 지연 속성과 `lunaLiveTest` 보고서로 모으고, 목적별 목표 대조는 그 목적을 쓰는 단위(U10 15초 · U11 2초 · U14)와 Build and Test가 한다.
2. **안전 실패(NFR2):** 미완료 · 빈 응답 · 거절 · 스키마 위반 · 모델 없음 · 시한 초과는 모두 실패로 돌려주고, 어떤 경우에도 빈 값이나 부분 응답을 성공으로 바꾸지 않는다(BR4 · BR8). 어댑터가 꺼지면 기존 실패 경로가 그대로다.
3. **개인정보(NFR3)**
   - 보내기 전 금지 키 제거 · '아이' 치환 · 연락처 가림을 하고, 금지 키가 남으면 부르지 않는다(BR2).
   - `store=false`로 공급자 저장을 끈다. 허용 호스트(`api.openai.com`, https) 밖으로는 보내지 않는다(BR1.4 · BR1.6).
   - 로그 · 예외 · `toString`에 본문 · 전사 · 이름 · 키를 남기지 않는다. `LUNA_CALL`은 목적 · 모델 · 판 · 필드 이름 · 사유 · 지연 · 시도 번호만 담는다(BR6).
   - R-F15는 Q2-B대로 RUNBOOK "키를 넣기 전 확인" 절차로 지키고, 이 점을 보안 요구 문서에 '코드가 막지 않는 위험'으로 적는다.
   - 키는 env에서만 읽고 저장소 · 로그 · 채팅에 두지 않는다(R-F3 · R-F13). 새 비밀 이름은 없다(U8이 `OPENAI_API_KEY`를 넣음).
4. **관측(NFR4):** NFR4의 판정 보류율 · 지연은 U11이 `judgment_live`로 계측한다. U9는 그 재료(`LUNA_CALL`의 사유 · 지연 · 모델 · 판)를 준다.
5. **시험(NFR5):** test-after, Standard. 기본 시험 · CI · `verify.sh`는 루프백 대역만 쓴다(R-F1). 실호출은 사람이 돌리는 `lunaLiveTest`(편차 U9-c)뿐이고, 결과는 '실제 API · 합성 자료' 증거로 따로 보고한다(R-M11).
6. **호환(NFR6):** `TextAiProvider` · `ResultTextProvider` 인터페이스와 바깥 게이트웨이 · 검사 경로를 바꾸지 않는다. 키가 없으면 지금 동작과 같다.
7. **운영 격리(NFR7):** U9는 Compose 프로젝트 · 볼륨을 건드리지 않는다. `mujoong-manual`과 관계없다.
8. **기술 선택**
   - 호출: JDK `java.net.http.HttpClient`(Q1-A). 새 HTTP 의존성 없음.
   - JSON: `JsonCodec`(J139) 한 곳. 스키마 검증기도 Jackson 타입을 호출부에 들이지 않도록 문자열 입력 방식으로 부른다.
   - 스키마 검증: 이미 목록에 있는 `com.networknt:json-schema-validator` 3.0.6(2020-12, Jackson 3.1 빌드)을 두 앱에서 `testImplementation` → `implementation`으로 옮긴다. `libs.versions.toml`은 고치지 않는다. 운영 이미지에 들어가므로 취약점 점검(R-M7) 대상이 된다.
   - 스키마 파일: 빌드 때 `contracts/schemas/ai-*.schema.json`을 두 앱 실행 자원으로 복사한다(Q3-A).
   - 시험 대역: `test-support`에 JDK `HttpServer` 루프백 Responses 대역(기존 `ContractStubServer`와 같은 방식).

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions

## Q1. (검토 뒤) NFR 검토 지적을 어떻게 처리할까

아키텍처 검토는 '진행 가능'이었지만 Major 2건 · Minor 4건이 남았다(`reviews/review-01.md`). R-01: 허용 호스트 검사가 기동 때 설정 주소에만 걸려, 리다이렉트(3xx) · 프록시로 다른 호스트로 나가는 길과 응답 크기 상한이 요구에 없다. R-02: R-F15 '키 넣기 전 확인'의 증거를 어디에 남길지가 열려 있고, `ai-io.md` 10.2 문장("U9가 코드로 정한다")이 Q2-B와 어긋나는데 고칠 책임이 한 줄뿐이다. Minor: R-03 별칭 표 밖 이름(친구 · 가족)의 잔여 위험, R-04 429 · 5xx 대기 뒤 마감 확인 시험, R-05 `lunaLiveTest`가 `check`에 물리지 않음 확인 · 캐시 키, R-06 의존성 이동 직후 취약점 · 이미지 점검.

A. 문서 고친 뒤 진행 — 리다이렉트 금지 · 프록시 미사용 · 응답 크기 상한을 요구로 넣고, R-F15 확인 기록을 RUNBOOK 체크 항목(확인자 · 날짜 · 확인한 약관 판)으로 남기며 `ai-io.md` 10.2 수정을 Code Generation 단계로 명시하고, Minor 4건도 반영한 뒤 다시 검토를 받는다 (추천)
B. 코드 생성 계획에서 정하기 — 문서는 그대로 두고, Code Generation 계획에 이 결정을 단계로 넣는다
X. Other (please specify)

[Answer]: A
