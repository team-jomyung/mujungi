# U8 계약 · 버전 리소스 — NFR Design 질문지

> 근거: `../nfr-requirements/security-requirements.md`(NFR2.1 ~ NFR6.2) · `tech-stack-decisions.md`, `../functional-design/functional-spec.md`(8절), `../../../inception/contract-design/contract-summary.md`.
> 코드 사실(직접 확인): `gate/SeedDictionary`는 `JsonCodec`으로 자원을 읽고 실패하면 `LoadException`을 던지며, `startup/RealtimeStartupChecks`가 기동 때 이를 확인한다. 시험 지원 모듈에 `testsupport/log/LogCapture`(로그 수집)가 있다.
> U8은 `spec` 단위라 보안 설계 문서 하나만 만든다. 새로 고를 것이 없어 아래 가정 묶음으로 확인받는다.

## Assumption Confirmation

1. **판 리소스 적재 · 기동 거부:** `LiveContentRegistry`의 자원 적재는 `SeedDictionary`와 같은 모양으로 한다. 자원을 `JsonCodec`으로 읽고, 검증에 실패하면 적재 예외를 던지며, 기동 확인은 `RealtimeStartupChecks`에 한 줄 더한다.
2. **세션 저장:** 세션별 계획 · 대본은 앱 메모리(세션 id를 키로 하는 동시성 맵)에만 둔다. 세션 정산이 끝나면 지운다. 앱이 재시작되면 사라지고, 진행 중 세션은 기존 재시작 규칙대로 실패 처리된다.
3. **로그 없음 증명:** `LogCapture`를 붙인 단위 시험으로 '원문 일치 · 대본 등록 뒤 전사 · 대본 부분 문자열이 어느 로그 줄에도 없음'을 단언한다. `LiveContentRegistry`는 로그를 쓰지 않는다. 앞뒤가 다른 패키지 등 이상 수신은 사유 코드만 기존 `EventLog`로 남긴다.
4. **스키마 닫기 검증:** U8 계약 시험은 요청 스키마마다 금지 필드 이름(`childId` · `guardianId` · `sessionId` · `apiKey` 등)을 넣은 거절 예시가 실패하는지, 아이 문자열 필드 `description`에 치환 문구가 있는지, `ai-io.md`에 `R-F15` 표식 문장이 있는지를 단언한다.
5. **비밀 분배:** `infra-validate`의 서비스별 표에서 core-api에 `OPENAI_API_KEY`, realtime-activity에 `OPENAI_API_KEY` · `JEV_API_KEY`를 더한다. `render-env`는 두 이름을 OPTIONAL로 두고 앱별 `write_env`로 나눠 준다.

A. Accept assumptions
B. Convert to follow-up questions
X. Other (please specify)

[Answer]: A. Accept assumptions

## Q1. (검토 뒤) 보안 설계 검토의 Major 2건을 어떻게 처리할까

R-01: SD6가 '유니코드 공백 포함'과 '기존 `PracticeSpeech.matches`와 같음'을 함께 적었는데, 실제 `matches`는 ASCII 공백만 지운다. R-02: `LogCapture`는 `EventLog` 로거만 모으므로 '로그 없음'을 증명하지 못한다. 구조 시험(ArchUnit)이 필요하다. Minor 4건(빈 비밀 값 처리, 이상 수신 기록 코드, 기동 확인 생성자, 세션 수 상한)도 같은 묶음이다.

A. 문서 고친 뒤 진행 — 정규화는 기존 `matches`(ASCII 공백만)를 그대로 쓰고 NBSP · 전각 공백 시험을 넣는다. '로그 없음'은 ArchUnit 규칙으로 증명한다. 빈 값은 미설정으로 보고, 이상 수신은 기존 `APP_ERROR`의 `errorKind`로 남긴다. 기동 확인은 오버로드로 넓히고, 세션 수 상한은 남는 위험 표에서 뺀다(정산 경로 시험으로 대신) (추천)
B. 코드 생성 계획에서 정하기
X. Other (please specify)

[Answer]: A
