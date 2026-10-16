# U9 Luna 호출부 — 업무 규칙

> 근거: `functional-design-questions.md`(Q1-A · Q2-B · Q3-A · Q4-A + 실호출 요구, 가정 1 ~ 12 · 3-1), `../../../inception/requirements-analysis/requirements.md`(FR1.1 ~ FR1.4, NFR3 · NFR5), `../../../inception/user-stories/stories.md`(US2.4, AC2.3.3 · AC2.3.4), `contracts/ai-io.md` 10절, U8 `functional-spec.md` W3.

```yaml
rules:
  # BR1 켜고 끄기 · 설정
  - id: BR1.1
    statement: 실제 Luna 어댑터는 env OPENAI_API_KEY가 비어 있지 않고 공백뿐이 아닐 때만 켜진다.
    category: policy
    applies_to: LunaSettings
    trigger: 앱 기동
    logic: IF 키가 있음 THEN 실제 어댑터 빈을 만든다 ELSE 만들지 않는다
    violation: 해당 없음(분기 규칙)
    source: FR1.3, AC2.3.3, Q2-B
  - id: BR1.2
    statement: 어댑터가 꺼지면 기존 실패 기본 빈(TextAiProvider → UNAVAILABLE, ResultTextProvider → PROVIDER_UNAVAILABLE)이 그대로 쓰이고, 기동은 거부하지 않는다.
    category: policy
    applies_to: LunaSettings
    trigger: 앱 기동
    logic: IF 어댑터 꺼짐 THEN @ConditionalOnMissingBean 기본 빈 유지 · 기동 계속
    violation: 기동 거부나 기본 빈 교체는 결함
    source: FR1.3, ai-io.md 10.7
  - id: BR1.3
    statement: 기동 때 어댑터가 켜졌는지 꺼졌는지를 사유 코드 한 줄로 남긴다(키 값은 남기지 않는다).
    category: policy
    applies_to: LunaSettings
    trigger: 앱 기동
    logic: 켜짐이면 LUNA_ENABLED, 꺼짐이면 LUNA_DISABLED_NO_KEY 사유 코드로 기록
    violation: 키 값 · 일부 문자가 로그에 나오면 결함(R-F3 · R-F14)
    source: FR1.4, 가정 3
  - id: BR1.4
    statement: 주소 규칙 — demo · prod 프로필은 https 스킴 + 허용 호스트(api.openai.com)만, dev 프로필은 그 규칙 또는 루프백, test 프로필은 루프백만 받는다. 실호출 시험(lunaLiveTest)은 앱 프로필 없이 호출부를 직접 만들고 demo · prod와 같은 규칙(https + 허용 호스트)을 쓴다. 허용 호스트 상수는 앱마다 따로 두고(R-F5), 두 앱 시험이 각자 ai-io.md에 적힌 호스트와 같은지 대조한다.
    category: validation
    applies_to: LunaSettings.baseUrl
    trigger: 앱 기동 · 실호출 시험 시작
    logic: IF 프로필 규칙에 맞지 않는 주소 THEN 기동 거부(MUJOONG_STARTUP_REJECTED) · 실호출 시험 실패
    violation: 허용 밖 호스트로 아동 데이터가 나갈 수 있음
    source: 가정 2, NFR3, 검토 R-05(Q5-A)
  - id: BR1.8
    statement: Luna(추론 모델) 요청에는 temperature를 보내지 않는다. 기존 ResultTextProvider.Request.temperature 값은 인터페이스 호환을 위해 받기만 하고 무시한다. 출력 성향은 프롬프트 판과 reasoning.effort로만 조절한다.
    category: constraint
    applies_to: LunaRequest
    trigger: 요청 조립
    logic: 요청 본문에 temperature 키를 넣지 않는다
    violation: 공급자가 지원하지 않는 매개변수로 요청이 거절됨
    source: 검토 R-07(Q5-A)
  - id: BR1.5
    statement: 모델 이름은 core OPENAI_ANALYSIS_MODEL, realtime OPENAI_JUDGE_MODEL에서 읽고, 비면 gpt-6-luna를 쓴다. 키 · 모델을 코드에 박지 않는다.
    category: constraint
    applies_to: LunaSettings.model
    trigger: 앱 기동
    logic: env 값이 있으면 그 값, 없으면 기본값
    violation: 코드에 키가 박히면 결함(R-F3)
    source: FR1.1
  - id: BR1.6
    statement: 모든 공급자 요청은 store=false로 보낸다.
    category: constraint
    applies_to: LunaRequest
    trigger: 요청 조립
    logic: 요청 본문에 store=false를 항상 넣는다
    violation: 공급자에 대화가 저장될 수 있음
    source: NFR3, 조원 하네스 설정
  - id: BR1.7
    statement: R-F15(약관 · 보호자 동의 범위 확인 전 실제 아동 데이터 전송 금지)는 코드 스위치 없이 RUNBOOK의 "키를 넣기 전 확인" 절차로 지킨다. 확인 전에는 키를 운영 환경에 넣지 않는다.
    category: policy
    applies_to: LunaSettings
    trigger: 운영 키 입력
    logic: IF 확인 절차를 마치지 않음 THEN 운영 env에 OPENAI_API_KEY를 넣지 않는다
    violation: 절차 위반은 운영 사고로 다룬다(코드가 막지 않는다)
    source: R-F15, Q2-B, ai-io.md 10.2

  # BR2 보내는 입력
  - id: BR2.1
    statement: 보내기 전 입력 전체(중첩 포함)에서 금지 키(ai-io.md 10.2 목록과 core 기존 PRIVATE_KEYS)를 지운다.
    category: validation
    applies_to: SanitizedInput
    trigger: 요청 조립
    logic: 모든 깊이의 객체에서 금지 키 이름과 같은 키를 뺀다
    violation: 식별자 · 키 · 음성이 공급자로 나감
    source: FR1.4, NFR3, ai-io.md 10.2
  - id: BR2.2
    statement: 호출자가 넘긴 별칭 표로 모든 문자열 값의 아이 표시 이름을 '아이'로 바꾼다.
    category: calculation
    applies_to: SanitizedInput
    trigger: 요청 조립
    logic: 별칭 표의 각 이름을 문자열 값 안에서 별칭으로 바꾼다. 빈 이름은 무시한다
    violation: 아이 이름이 공급자로 나감
    source: FR1.4, B §1.3
  - id: BR2.3
    statement: UUID → '참조N' 참조화와 복원은 호출자(지금은 ResultTextGateway)가 맡는다. 호출부는 참조화하지 않고, 이메일 · 휴대전화 번호 모양의 값만 '연락처'로 바꾸는 안전망을 둔다. ResultTextGateway.sanitize는 지금 그대로 두고, 호출부가 그 뒤에 금지 키 확인(BR2.4)을 한 번 더 한다.
    category: calculation
    applies_to: SanitizedInput
    trigger: 요청 조립(core)
    logic: 참조 표는 게이트웨이가 만들고 게이트웨이가 복원한다. 호출부는 연락처 가림 · 금지 키 확인만
    violation: 식별자 · 연락처가 공급자로 나감, 또는 참조 복원이 깨짐
    source: 가정 7, 검토 R-01(Q5-A)
  - id: BR2.4
    statement: 정리 뒤에도 금지 키가 남거나 요청 스키마(있는 목적)에 맞지 않으면 공급자를 부르지 않고 FORBIDDEN_INPUT으로 실패한다.
    category: validation
    applies_to: SanitizedInput
    trigger: 요청 조립 끝
    logic: IF 금지 키 존재 OR 요청 스키마 위반 THEN 호출 없이 실패
    violation: 잘못된 입력이 나감
    source: NFR3, ai-io.md 10.1(요청 스키마 additionalProperties false)
  - id: BR2.5
    statement: 시스템 지시(프롬프트 버전 파일)와 입력 데이터를 다른 자리로 보내, 입력이 지시를 바꾸지 못하게 한다.
    category: constraint
    applies_to: LunaRequest
    trigger: 요청 조립
    logic: 지시는 instructions 자리, 데이터는 input 자리(JSON 문자열)로만 보낸다
    violation: 입력에 의한 지시 주입
    source: C §9

  # BR3 재시도
  - id: BR3.1
    statement: NONE 목적(진행 중 판정)은 한 번만 부른다.
    category: policy
    applies_to: LunaPurpose.retryClass
    trigger: 호출
    logic: 시도 1회. 실패는 그대로 돌려준다(U11이 판정 보류로 바꿈)
    violation: 2초 예산 초과
    source: ai-io.md 10.3, U8 W3
  - id: BR3.2
    statement: REQUEST_BOUNDED 목적은 일시 실패에 기술 재시도 1회, 스키마 불일치에 재요청 1회를 호출자 마감 안에서만 하고, 쓴 시도 수 · 스키마 재요청 여부를 돌려준다. 시도는 모두 합쳐 3회를 넘지 않는다.
    category: policy
    applies_to: LunaPurpose.retryClass
    trigger: 호출 실패
    logic: IF 실패가 transient AND 기술 재시도 미사용 AND 남은 시간 > 0 THEN 기술 재시도. IF SCHEMA_INVALID AND 스키마 재요청 미사용 AND 남은 시간 > 0 THEN 재요청. 그 밖은 실패
    violation: 예산 밖 재시도 · 무한 재시도
    source: FR1.2, ai-io.md 10.3, U8 Q2-A
  - id: BR3.3
    statement: JOB_ATTEMPT 목적은 호출 안에서 재시도하지 않는다. 결과 작업 재시도가 다시 한다.
    category: policy
    applies_to: LunaPurpose.retryClass
    trigger: 호출 실패
    logic: 시도 1회. 실패는 그대로 돌려준다
    violation: 작업 시도 수 계산이 틀어짐
    source: ai-io.md 10.3
  - id: BR3.4
    statement: 기존 인터페이스 어댑터(TextAiProvider · ResultTextProvider)는 언제나 시도 1회로 부른다. 재시도는 바깥 TextAiGateway(기술 재시도)와 결과 작업이 이미 하고 있다.
    category: policy
    applies_to: 기존 인터페이스 어댑터
    trigger: 호출
    logic: 어댑터 안 재시도 없음. 일시 실패는 TextAiException(UNAVAILABLE, retryable=true)로 돌려 TextAiGateway가 재시도를 판단한다
    violation: 재시도가 두 겹으로 늘어 15초 예산을 넘음
    source: 가정 4 · 5, 코드 TextAiGateway
  - id: BR3.5
    statement: 인증 실패(401 · 403)는 어느 목적에서도 재시도하지 않는다.
    category: policy
    applies_to: LunaFailure
    trigger: 응답 401 · 403
    logic: AUTH_FAILED, transient=false
    violation: 잘못된 키로 반복 호출
    source: 가정 11
  - id: BR3.6
    statement: 기술 재시도 간격은 core 기존 키 mujoong.text-ai.retry.backoff를 쓰고, 남은 시간이 간격보다 짧으면 재시도하지 않는다.
    category: constraint
    applies_to: 재시도
    trigger: 기술 재시도 전
    logic: IF 남은 시간 <= backoff THEN 재시도 생략
    violation: 마감을 넘긴 시도
    source: 가정 6, ai-io.md 7절

  # BR4 응답 판정
  - id: BR4.1
    statement: 응답 상태가 미완료(출력 한도 도달 등)면 LENGTH_EXCEEDED, 출력 텍스트가 비면 EMPTY_OUTPUT, 거절이면 REFUSED로 실패한다. 성공으로 저장하지 않는다.
    category: validation
    applies_to: LunaResult
    trigger: 응답 수신
    logic: 미완료 → LENGTH_EXCEEDED, 빈 출력 → EMPTY_OUTPUT(transient), 거절 → REFUSED
    violation: 잘린 출력이 성공으로 저장됨
    source: FR1.2, ai-io.md 10.1, BR5.3(U8)
  - id: BR4.2
    statement: 출력 스키마가 있는 목적은 요청에 보낸 것과 같은 스키마 파일로 응답을 검증하고, 맞지 않으면 SCHEMA_INVALID다. 출력 스키마가 없는 기존 인터페이스 목적은 JSON 객체로 읽히는지만 보고, 내용 검증은 기존 게이트웨이가 한다.
    category: validation
    applies_to: LunaResult.outputJson
    trigger: 응답 수신
    logic: IF outputSchema 있음 THEN 같은 파일로 검증 ELSE JSON 객체 파싱만
    violation: 스키마 밖 응답이 쓰임
    source: FR1.2, Q3-A, Contract Q2-A
  - id: BR4.5
    statement: 공급자에 보내는 요청용 스키마는 계약 스키마 파일에서 만든다. 공급자가 구조화 출력에서 받는 키워드(type · properties · required · additionalProperties · items · enum · const · anyOf · $defs · $ref · description)만 남기고 나머지($schema · $id · minLength · maxLength · pattern · format · minimum · maximum · minItems · maxItems · uniqueItems 등)는 뺀다. 모든 객체가 additionalProperties false이고 모든 속성이 required면 strict=true, 아니면 strict=false로 보낸다. 응답 검증(BR4.2)은 언제나 원본 계약 스키마 전체로 한다.
    category: calculation
    applies_to: LunaPurpose.outputSchema
    trigger: 앱 기동(스키마 컴파일 때 한 번)
    logic: 허용 키워드만 남긴 사본을 만들고 strict 가능 여부를 판정한다. 만들 수 없으면 기동 거부
    violation: 공급자가 요청을 거절하거나, 빠진 제약이 검증되지 않음
    source: 검토 R-02(Q5-A), Q3-A
  - id: BR4.3
    statement: 응답 model 값이 비면 MODEL_MISSING으로 실패한다. 성공 결과에는 응답 model · 보낸 promptVersion · 지연 · 쓴 시도 수를 붙인다.
    category: validation
    applies_to: LunaResult
    trigger: 응답 수신
    logic: model 없음 → 실패. 있음 → 결과에 붙임
    violation: 결과 행에 모델 · 판을 저장할 수 없음
    source: FR1.2
  - id: BR4.4
    statement: 공급자 응답의 상태 코드 분류: 429 → RATE_LIMITED(transient), 5xx → SERVER_ERROR(transient), 연결 · 읽기 오류 → TRANSPORT_ERROR(transient), 시한 초과 → TIMEOUT, 401 · 403 → AUTH_FAILED, 그 밖의 4xx(스키마 거절 포함) → REQUEST_REJECTED(transient=false).
    category: calculation
    applies_to: LunaFailure
    trigger: 응답 수신 실패
    logic: 표대로 분류
    violation: 재시도하면 안 되는 오류를 재시도
    source: 가정 5 · 11

  # BR5 시간
  - id: BR5.1
    statement: 시도 하나의 시한은 호출자 마감까지 남은 시간과 목적의 시한 키(core text-ai.request-timeout · text-ai.result.request-timeout, realtime timing.judgment.live-budget) 중 짧은 쪽이다. 연결 시한도 이 값을 넘지 않는다.
    category: constraint
    applies_to: LunaRequest.deadline
    trigger: 시도 시작
    logic: limit = min(deadline - now, 목적 시한 키). IF limit <= 0 THEN TIMEOUT(호출 없음)
    violation: 예산 초과
    source: 가정 6, NFR1
  - id: BR5.2
    statement: 새 시간 키를 만들지 않고, 시각은 MujoongClock으로만 읽으며, 코드에 숫자 시간 값을 쓰지 않는다.
    category: constraint
    applies_to: 호출부 전체
    trigger: 구현
    logic: 시간 값은 기존 설정 레코드에서만
    violation: TimeLiteralScanTest · ArchUnit 실패
    source: R-F6 · R-F7, 가정 6

  # BR6 기록
  - id: BR6.1
    statement: 시도마다 EventLog LUNA_CALL 한 줄을 남긴다. 속성은 목적 · 모델 · 프롬프트 판 · 보낸 최상위 필드 이름 · 결과 사유 코드 · 지연 · 시도 번호뿐이다.
    category: policy
    applies_to: LunaCallAudit
    trigger: 시도 끝
    logic: 성공 · 실패 모두 기록. 공급자를 부르지 않은 FORBIDDEN_INPUT · 시한 0 실패도 시도 0으로 기록
    violation: 필드 감사 누락
    source: FR1.4
  - id: BR6.2
    statement: 요청 · 응답 본문, 전사, 이름, 키는 어떤 로그 · 예외 메시지 · toString에도 남기지 않는다.
    category: constraint
    applies_to: 호출부 전체
    trigger: 구현
    logic: 레코드 toString은 [redacted], 예외는 사유 코드만, LUNA_CALL 속성은 BR6.1 목록만
    violation: R-F14 위반
    source: FR1.4, R-F14
  - id: BR6.3
    statement: 앱마다 EventLog Component LUNA_CLIENT를 더하고, 호출부는 slf4j를 직접 쓰지 않는다.
    category: constraint
    applies_to: 호출부 패키지
    trigger: 구현
    logic: 기록은 앱 EventLogger로만
    violation: ArchUnit 실패
    source: team.md Code Style, 가정 8

  # BR7 경계
  - id: BR7.1
    statement: Luna 호출부 패키지(..luna..)는 제브 · 가드레일 코드를 참조하지 않는다. Luna 경로의 생성물 · 결과는 제브로 가지 않는다.
    category: constraint
    applies_to: 호출부 패키지
    trigger: 구현
    logic: ArchUnit 규칙으로 막는다
    violation: AC2.4.1 위반
    source: AC2.4.1, FR2.6 · FR7.7
  - id: BR7.2
    statement: 기존 인터페이스 어댑터는 바깥 검증 경로를 그대로 둔다. TextAiGateway(길이 · 판 · 금지 표현), ResultTextGateway(ResultAiSchema · 인용 대조), OutputChecker(1차 사전 · classify)는 Luna 연결 뒤에도 같은 순서로 돈다.
    category: constraint
    applies_to: 기존 인터페이스 어댑터
    trigger: 호출 뒤
    logic: 어댑터는 문자열 · 생성물만 돌려주고 검증을 건너뛰게 하는 길을 만들지 않는다
    violation: 검사 경로가 빠짐
    source: AC2.4.2, AC2.4.3, FR1.3
  - id: BR7.3
    statement: core와 realtime은 호출부 코드를 나누지 않고, 공통 규칙은 contracts/ai-io.md와 앱별 시험으로 맞춘다.
    category: constraint
    applies_to: 호출부 패키지
    trigger: 구현
    logic: contract-model에 호출부를 두지 않는다
    violation: R-F5 · ADR-004 위반
    source: R-F5, 가정 1

  # BR8 기존 인터페이스로 바꾸기
  - id: BR8.1
    statement: TextAiProvider 어댑터는 실패를 아래 결정표로 TextAiException에 바꾼다. TextAiGateway는 retryable && (UNAVAILABLE 또는 TIMEOUT)일 때만 기술 재시도한다.
    category: calculation
    applies_to: 기존 인터페이스 어댑터
    trigger: 실패
    logic: |
      TIMEOUT → TIMEOUT, retryable=true
      TRANSPORT_ERROR · RATE_LIMITED · SERVER_ERROR(5xx) · EMPTY_OUTPUT → UNAVAILABLE, retryable=true
      REQUEST_REJECTED · AUTH_FAILED · FORBIDDEN_INPUT → UNAVAILABLE, retryable=false
      LENGTH_EXCEEDED · REFUSED · SCHEMA_INVALID(JSON 객체 아님) · MODEL_MISSING → INVALID_OUTPUT, retryable=false
    violation: 재시도 판단이 틀어짐
    source: 가정 11, 코드 TextAiException · TextAiGateway, 검토 R-04(Q5-A)
  - id: BR8.2
    statement: ResultTextProvider 어댑터는 실패를 아래 결정표로 ResultAiException에 바꾼다. 결과 작업은 어느 사유든 시도 실패로 세고 작업 재시도로 다시 한다.
    category: calculation
    applies_to: 기존 인터페이스 어댑터
    trigger: 실패
    logic: |
      TIMEOUT → TIMEOUT
      EMPTY_OUTPUT · LENGTH_EXCEEDED · REFUSED · SCHEMA_INVALID → SCHEMA_INVALID (지금 게이트웨이가 빈 출력을 SCHEMA_INVALID로 보는 것과 같음)
      TRANSPORT_ERROR · RATE_LIMITED · SERVER_ERROR · REQUEST_REJECTED · AUTH_FAILED · MODEL_MISSING → PROVIDER_ERROR
      FORBIDDEN_INPUT → INTERNAL
    violation: 결과 작업 실패 사유가 틀어짐
    source: 가정 11, 코드 ResultAiException · ResultTextGateway, 검토 R-04(Q5-A)

  # BR9 시험
  - id: BR9.1
    statement: 기본 시험 · CI · verify.sh는 test-support의 Responses 루프백 대역만 쓰고 실제 공급자를 부르지 않는다.
    category: policy
    applies_to: 시험
    trigger: 시험 실행
    logic: base-url은 루프백 대역, 키는 가짜 값
    violation: R-F1 위반
    source: R-F1, AC2.3.4, Q4-A
  - id: BR9.2
    statement: 실호출 시험은 기본 빌드 · check · CI에서 빠지는 별도 작업(lunaLiveTest, 태그 luna-live)으로만 돈다. 키가 없으면 SKIPPED_NO_KEY로 끝나고 통과로 보고하지 않는다. 합성 자료만 쓰고, 결과는 '실제 API · 합성 자료' 증거로 따로 보고한다.
    category: policy
    applies_to: LiveTestRun
    trigger: 사람이 작업 실행
    logic: IF 키 없음 THEN SKIPPED_NO_KEY ELSE 목적별 합성 1건씩 호출 · 검증
    violation: R-F1 · R-M11 · R-F15 위반
    source: Q4 추가 요구, FR12.1, R-M11
  - id: BR9.3
    statement: 공통 빌드 규칙(mujoong.java-conventions)이 기본 test 작업에서 luna-live 태그를 언제나 빼고, lunaLiveTest 작업만 그 태그를 포함한다. check · CI(ci.yml) · verify.sh는 lunaLiveTest를 부르지 않는다. 이 실호출 시험은 R-F1(시험 · CI · 검증에서 실공급자 금지)의 글자와 부딪히므로 편차 U9-c로 적는다 — 사람이 직접 돌리는 선택 작업이고, 합성 자료만 쓰며, 자동 검증 경로에는 들어가지 않는다.
    category: policy
    applies_to: LiveTestRun
    trigger: 빌드 구성
    logic: test 작업 excludeTags에 luna-live 고정 추가. lunaLiveTest는 includeTags luna-live. 빌드 구성 시험이 check 작업 그래프에 lunaLiveTest가 없음을 확인한다
    violation: 실공급자 호출이 CI · 기본 시험에 섞임
    source: R-F1, Q4 추가 요구, 검토 R-03(Q5-A)
```

## 규칙 요약

| 묶음 | 규칙 | 한 줄 |
|---|---|---|
| 켜고 끄기 | BR1.1 ~ BR1.8 | 키가 있으면 켜짐(Q2-B), 없으면 기존 실패 빈. 프로필별 호스트 규칙, store=false, temperature 안 보냄. R-F15는 RUNBOOK 절차 |
| 입력 | BR2.1 ~ BR2.5 | 금지 키 제거 · '아이' 치환 · 연락처 가림 · 지시와 데이터 분리. UUID 참조화는 게이트웨이 몫. 정리 실패면 호출하지 않음 |
| 재시도 | BR3.1 ~ BR3.6 | 판정 1회, 생성류 기술 1 + 스키마 1(마감 안), 결과류 0. 기존 인터페이스 어댑터는 1회. 401 · 403 재시도 없음 |
| 응답 | BR4.1 ~ BR4.5 | 미완료 · 빈 응답 · 거절 · 스키마 위반 · 모델 없음은 실패. 요청용은 허용 키워드만 남긴 사본, 검증은 원본 계약 스키마 |
| 시간 | BR5.1 ~ BR5.2 | 시한 = min(호출자 마감, 기존 키). 새 키 없음 |
| 기록 | BR6.1 ~ BR6.3 | 시도마다 LUNA_CALL, 본문 · 이름 · 키 없음 |
| 경계 | BR7.1 ~ BR7.3 | 제브 참조 금지, 기존 검증 경로 유지, 앱 사이 코드 공유 없음 |
| 바꾸기 | BR8.1 ~ BR8.2 | 실패를 결정표대로 기존 예외 사유로 |
| 시험 | BR9.1 ~ BR9.3 | 기본은 루프백 대역, 실호출은 키를 넣고 돌리는 별도 작업(기본 test에서 태그 제외, 편차 U9-c) |
