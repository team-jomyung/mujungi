# U8 계약 · 버전 리소스 — 업무 규칙

> 근거: `../../../inception/requirements-analysis/requirements.md`(FR1.1 · FR1.2 · FR6.6 · FR6.10 · FR7.5 · FR10 · FR11), `../../../inception/units-generation/unit-of-work.md`, `../../../inception/units-generation/unit-of-work-story-map.md`, `../../../inception/domain-design/components.md`, `../../../inception/contract-design/contract-summary.md`(C-DS2 · C-DS9 · C-DS13 · C-DS14, 검토 R-02 · R-03 · R-07), 팀 규칙 R-M5 · R-M6 · R-M9 · R-M10 · R-F2 · R-F3, 질문 · 답 `functional-design-questions.md`(Q1-A · Q2-A · 가정 1 ~ 9), `entities.md`.

## 규칙 목록 (기계 판독용)

```yaml
rules:
  # BR1 승인 문구
  - id: BR1.1
    statement: 새 연습 흐름의 고정 문구는 core 승인 문구 표에 시드로 더하고, 정본은 core DB 하나다
    category: policy
    applies_to: ApprovedPhrase
    trigger: 새 승인 문구가 필요할 때(설계 · 문구 개정)
    logic: IF 문구가 정본(B §4 · §8.5 · 화면 설계 문구표)에 있으면 THEN content_code · 판 · 텍스트 · 근거를 새 시드 파일 infra/seed/004-u8-reference.sql과 dev 시드(DevSeedRunner)에 같은 값으로 넣는다. 기존 시드 파일(001 ~ 003)은 고치지 않는다. 기존 HELP_OFFER(v1 DRAFT '같이 살펴볼까?')는 004에서 v2 APPROVED(정본 문구)로 올리고 v1은 RETIRED로 둔다. 코드 상수로 문장을 두지 않는다
    violation: 코드에 박힌 승인 문장('도움을 받아 볼까?' 등)은 시드로 옮기고 코드는 content_code로만 참조한다
    source: Q1-A, FR10.2, 설계 검토 R-01
  - id: BR1.2
    statement: 음성 파일 없는 승인 발화는 시작 패키지의 새 선택 속성 approvedSpeech로만 넘긴다
    category: constraint
    applies_to: ApprovedSpeechItem
    trigger: 세션 시작 패키지 조립
    logic: |
      음성 파일 없는 승인 발화는 content_type 'APPROVED_SPEECH'(새 값)로 시드한다.
      IF 세션 패키지를 조립하면 THEN 활동 유형별 허용 목록(functional-spec.md 8.1 표)에 있고 content_type이 APPROVED_SPEECH이며 APPROVED 판이 있는 code만 approvedSpeech에 {contentVersionId, text}로 넣는다.
      APPROVED_AUDIO는 지금처럼 approvedContent에 넣는다. 한 code는 두 맵 중 하나에만 들어간다(core 조립 시험 + realtime 수신 검사로 확인, 스키마로는 막지 못함).
      approvedSpeech는 internal-session-start-request.schema.json properties에 선택 속성으로 더한다(루트 additionalProperties false 유지).
    violation: approvedContent 항목의 audioUrl 필수를 풀지 않는다(기존 수신 쪽 · 기기 계약을 깨지 않음). 허용 목록 밖 code(CONSENT_POLICY · POLICY_TERMS_* · DRAFT)는 싣지 않는다. realtime이 두 맵에 같은 code를 받으면 approvedSpeech 쪽을 버리고 사유를 기록한다
    source: Q1-A, contracts/README.md 더하기만 규칙, 설계 검토 R-01 · R-03
  - id: BR1.3
    statement: 승인 문구는 세션 시작 때 APPROVED 최고 판으로 고정되고 세션 동안 바뀌지 않는다
    category: policy
    applies_to: [ApprovedPhrase, SessionContentPlan]
    trigger: 세션 시작
    logic: IF 세션이 시작되면 THEN 각 content_code의 APPROVED 최고 판 contentVersionId를 패키지에 싣고, realtime은 그 판만 쓴다
    violation: 세션 중 판이 바뀐 문구를 재생하지 않는다
    source: FR10.1, ai-io.md 5절
  - id: BR1.4
    statement: 승인 발화는 공백 제거 뒤 완전 일치할 때만 승인 원문 재생으로 본다
    category: validation
    applies_to: [ApprovedSpeechItem, SessionScript]
    trigger: GPT-Live 출력 전사가 완성될 때
    logic: IF 출력 전사에서 공백을 뺀 문자열이 고정된 승인 발화 또는 등록된 대본 문장과 정확히 같으면 THEN 원문 일치로 분류한다. ELSE 새 발화로 분류한다. 다른 정규화는 하지 않는다
    violation: 출처나 원문을 확인할 수 없으면 원문 일치로 보지 않는다(제브 생략 금지)
    source: FR6.6, AC2.2.1 · AC2.2.3, PracticeSpeech.matches
  # BR2 판 리소스와 세션 고정
  - id: BR2.1
    statement: 판정 질문 · 선택지와 가드레일 프롬프트 · 분류는 realtime 자원 파일 판으로만 둔다
    category: policy
    applies_to: [JudgmentOptionSet, GuardrailPolicy]
    trigger: 기동
    logic: IF 기동 때 자원 파일을 읽지 못하거나 형식이 틀리면 THEN 기동을 거부하고 사유를 기록한다(seed-dictionary.json과 같은 방식)
    violation: 판 없는 판정 · 검사를 시작하지 않는다
    source: FR6.10, FR10.1, 가정 2
  - id: BR2.2
    statement: 세션 시작 때 판정 판 · 가드레일 판을 실행 계획에 고정하고, 판정 · 검사 기록 행마다 그 판 번호를 남긴다
    category: policy
    applies_to: SessionContentPlan
    trigger: 세션 시작, 판정 · 검사 기록
    logic: IF 세션이 시작되면(StartSessionHandler가 세션을 받는 시점) THEN 그때의 판 번호를 고정하고, 세션 정산이 끝나면 놓는다. LiveContentRegistry는 고정된 판 번호를 돌려주는 조회를 제공한다. IF 판정 · 검사 기록을 남기면 THEN 그 조회 값을 함께 쓴다
    violation: U8 시험은 '세션 중 조회 값이 바뀌지 않음'을 본다. 기록 행에 판 번호를 쓰는 시험은 U11(판정) · U13(가드레일) 몫이다
    source: FR3.2, FR6.10, FR10.1, 설계 검토 R-05
  - id: BR2.3
    statement: 대본 문장은 realtime이 대본을 받은 직후 세션에 등록하고, 등록된 문장만 대본 원문 일치에 쓴다
    category: policy
    applies_to: SessionScript
    trigger: realtime이 core에서 대본을 받음
    logic: IF 대본 응답을 받으면 THEN 장면 · 기회 문장 · 다시 설명 · 집중 요점 · 표현 예시 문장을 scriptVersion과 함께 등록한다
    violation: 등록 전 문장은 원문 일치로 보지 않는다
    source: FR6.6, Domain Design 검토 R-03
  # BR3 시간 값
  - id: BR3.1
    statement: 새 시간 키는 timing.md 새 절의 '키 | 기본값 | 소유 앱' 표, 앱 application.yml, 설정 레코드에 같은 커밋에서 같은 값으로 둔다
    category: constraint
    applies_to: TimingKey
    trigger: 새 시간 키를 더할 때
    logic: |
      U8이 더하는 키는 functional-spec.md 8.2 표의 7개다.
      IF 키를 더하면 THEN timing.md 새 절의 '키 | 기본값 | 소유 앱' 표, 소유 앱 application.yml, 설정 레코드를 함께 고친다.
      새 연습 흐름 키는 기존 U5b의 mujoong.timing.practice(u5b-runtime.yml 바인딩)와 섞지 않으려고 새 이름 공간 mujoong.timing.conversation 아래에 두고 새 설정 레코드로 묶는다.
      화면이 읽지 않는 서버 키는 timing-defaults.json에 넣지 않는다. 기존 서버 키(result.* · text-ai.* 등)도 그 파일에 없고 timing.test.ts는 화면 키만 대조하므로, R-M5의 '세 곳'은 서버 키에서 timing.md · application.yml · 설정 레코드로 읽는다. 이 해석을 deviations.md U8 절에 한 행으로 남긴다
    violation: ConfigKeyContractTest가 실패하면 값을 맞춘다. 시험을 고치지 않는다
    source: FR11.1, R-M5, R-F2, 가정 3, 설계 검토 R-02
  - id: BR3.2
    statement: U8은 새 시간 키만 더하고, 기존 키의 값 변경은 그 값을 쓰는 단위가 코드와 같은 PR에서 한다
    category: policy
    applies_to: TimingKey
    trigger: 기존 키 값 변경 요구(예 결과 재시도 3 → 4회)
    logic: IF 기존 키 값을 바꿔야 하면 THEN U8에서 바꾸지 않고 해당 단위(U14 등)의 지시서 · 설계에 넘긴다. 계약 요약 C-DS13의 새 키 result.retry-delays는 만들지 않는다(기존 result.job-attempts · job-retry-backoff와 중복). U14가 바꾸기 전까지 결과 재시도 정본은 timing.md 10절(3회, [5s, 15s])이다
    violation: U8이 기존 키 값을 바꾸면 그 값을 쓰는 기존 시험이 깨진다
    source: 가정 4, Contract Design 검토 R-02, 설계 검토 R-02(bolt-plan B1 완료 기준의 'R-02 바로잡기'는 '중복 키를 만들지 않음'으로 읽는다)
  - id: BR3.3
    statement: 코드에 숫자 시간 값을 쓰지 않는다
    category: constraint
    applies_to: [LiveContentRegistry 코드]
    trigger: 코드 작성
    logic: IF 시간 값이 필요하면 THEN 설정 키에서 읽는다. 예외는 TimeLiteralScanTest 허용 목록에 근거와 함께 둔다
    violation: TimeLiteralScanTest 실패
    source: R-F7
  # BR4 비밀 이름
  - id: BR4.1
    statement: 새 비밀 이름 OPENAI_API_KEY · JEV_API_KEY를 R-M9 목록 전부에 같은 커밋으로 넣는다
    category: constraint
    applies_to: SecretName
    trigger: 비밀 이름 추가
    logic: IF 새 비밀 이름을 더하면 THEN ci.yml 번들 검사 · image-checks.sh · infra-validate(SECRET_NAMES · SECRET_TABLE) · render-env(주석 · REQUIRED/OPTIONAL · write_env) · log-hygiene-check · scripts/verify(lib.sh · b-stack.sh) · gen-env.sh · README · RUNBOOK(단계 4 · 회전 표)에 넣는다. 둘 다 OPTIONAL이다
    violation: 목록 하나라도 빠지면 CI 비밀 검사 · infra-validate가 실패하거나 비밀이 새어 나간다
    source: FR11.3, R-M9, 가정 5
  - id: BR4.2
    statement: Neo4j 비밀은 기존 NEO4J_AUTH를 쓰고, GPT-Live의 PROVIDER_API_KEY는 FR12.2 전까지 바꾸지 않는다
    category: policy
    applies_to: SecretName
    trigger: 비밀 이름 결정
    logic: IF Neo4j 접속이 필요하면 THEN NEO4J_AUTH(이미 목록에 있음)를 쓴다. core 예약 이름 MUJOONG_TEXT_AI_API_KEY는 쓰지 않는다고 ai-io.md에 적는다
    violation: 같은 용도에 비밀 이름을 둘 만들지 않는다
    source: 가정 5, Contract Design 계약 요약 C-DS14 정정
  - id: BR4.3
    statement: 계약 · 설정 · 문서 어디에도 비밀 값과 실제 도메인을 쓰지 않는다
    category: constraint
    applies_to: [SecretName, AiIoSchema, DeviationRow]
    trigger: 파일 작성
    logic: IF 값이 필요하면 THEN 이름만 쓰고 값은 환경 변수 · 비밀 저장소로 넘긴다
    violation: gitleaks · 비밀 검사 실패
    source: R-F3
  # BR5 AI 입출력 · 재시도
  - id: BR5.1
    statement: Luna · 제브 구조화 입출력은 contracts/schemas의 JSON Schema 파일이 정본이고, ai-io.md는 규칙 · 금지 필드 · 실패 처리만 적는다
    category: policy
    applies_to: AiIoSchema
    trigger: AI 입출력 계약 작성
    logic: IF AI 입출력을 정하면 THEN 스키마 파일을 두고 $id 규약을 지키며, ai-io.md에서 파일을 가리킨다
    violation: 스키마 없이 산문으로만 정하지 않는다
    source: Contract Design Q2-A, FR11.2
  - id: BR5.2
    statement: Luna 재시도는 목적별로 정한다
    category: policy
    applies_to: AiIoSchema
    trigger: Luna 호출 실패
    logic: |
      IF 목적이 진행 중 판정이면 THEN 재시도하지 않고 보류로 본다(retryClass NONE).
      IF 목적이 상황 생성 · 자체 검토 · 새 씨앗이면 THEN 일시 실패에 한한 기술 재시도 1회와 내용 재생성 1회를 요청 예산 안에서 한다. 스키마 불일치는 내용 재생성으로 센다(retryClass REQUEST_BOUNDED, ai-io.md 7절 승계).
      IF 목적이 결과 문장 · 목표 후보 · 기록용 재판정이면 THEN 호출 안에서 재시도하지 않고 결과 작업 시도로만 다시 한다(retryClass JOB_ATTEMPT)
    violation: 예산 밖 재시도 · 무한 재시도를 하지 않는다. 목적 → 호출 경로 → 분류는 ai-io.md 새 절 표(functional-spec.md 8.4)로 둔다. 목표 후보는 지금 코드에서 결과 작업 안에서만 부르므로 JOB_ATTEMPT다. 다른 경로에서 부르게 되면 그 단위가 분류를 다시 정한다
    source: Q2-A, FR1.2, FR3.4, ai-io.md 7절, 설계 검토 R-06
  - id: BR5.3
    statement: 길이 초과 · 빈 응답 · 스키마 최종 실패는 어떤 목적에서도 성공으로 저장하지 않는다
    category: validation
    applies_to: AiIoSchema
    trigger: Luna 응답 수신
    logic: IF 응답이 비었거나 길이 한도로 잘렸거나 스키마 검사에 최종 실패하면 THEN 실패 사유 코드만 원문 없이 기록하고 실패로 처리한다
    violation: 부분 응답을 결과로 저장하지 않는다
    source: Q2-A, 조원 하네스 Luna 설정, R-F14
  - id: BR5.4
    statement: 외부 AI로 보내는 필드는 스키마가 허용한 최소 필드뿐이고, 아동 · 보호자 · 세션 식별자와 키는 없다
    category: constraint
    applies_to: AiIoSchema
    trigger: 요청 스키마 작성
    logic: IF 요청 스키마를 정하면 THEN 금지 필드를 두지 않고(additionalProperties false), 아이 이름은 '아이'로 치환된 값만 받는다
    violation: 금지 필드가 있는 요청 스키마는 시험이 실패한다
    source: FR1.4, FR6.7, NFR3, R-F14
  - id: BR5.5
    statement: 실제 아동 데이터를 외부 모델로 보내는 것은 공급자 약관 · 보호자 동의 확인 전까지 막는다
    category: authorization
    applies_to: AiIoSchema
    trigger: 실제 어댑터 사용
    logic: IF 외부 전달 허가 상태가 확인되지 않았으면 THEN 합성 자료 실호출만 허용한다고 ai-io.md 금지 필드 문단에 적는다
    violation: 확인 전 실제 아동 데이터 전달은 금지(R-F15)
    source: R-F15, Contract Design 검토 R-08
  # BR6 기록 이벤트 형식
  - id: BR6.1
    statement: 새 연습 기록은 record.practice.flowVersion으로 구분하고 snapshotVersion의 뜻(세션당 한 판)은 바꾸지 않는다
    category: constraint
    applies_to: PracticeRecordV2
    trigger: 기록 캡처 이벤트 형식 정의
    logic: IF record.practice에 flowVersion이 있으면 THEN practice-record-v2 형식이다. 없으면 U5b 형식으로 읽는다
    violation: snapshotVersion을 형식 구분에 쓰지 않는다
    source: FR7.5, Contract Design 검토 R-03, 가정 6
  # BR7 계약 변경 절차
  - id: BR7.1
    statement: 계약 변경은 더하기만 하고, 바뀌는 곳마다 deviations.md U8 절에 한 행을 더하고 스펙에 표시한다
    category: policy
    applies_to: DeviationRow
    trigger: contracts/ 변경
    logic: IF 계약을 넓히거나 구체화하면 THEN 'U8-a' 같은 기호로 행을 더하고 YAML은 x-u8-deviation, Markdown은 [U8 편차 (U8-a)]로 표시한다
    violation: 표시 없는 계약 변경은 리뷰에서 막는다
    source: contracts/README.md, 가정 7
  - id: BR7.2
    statement: 계약 대조 시험을 고치거나 건너뛰어 통과시키지 않는다
    category: constraint
    applies_to: [TimingKey, AiIoSchema, DeviationRow]
    trigger: 시험 실패
    logic: IF ContractFilesTest · ErrorCodeTest · ConfigKeyContractTest · TimeLiteralScanTest · timing.test.ts가 실패하면 THEN 계약 · 설정 쪽을 고친다
    violation: 시험 수정 · @Disabled · 제외 태그 금지
    source: R-F2, R-M10
  - id: BR7.3
    statement: 새 HTTP 오류 코드는 더하지 않는다
    category: policy
    applies_to: AiIoSchema
    trigger: 실패 처리 설계
    logic: IF 판정 · 제브 실패면 THEN 내부 사유(보류 · 막힘)로, 상황 생성 실패면 기존 SCENARIO_GEN_FAILED로 처리한다
    violation: 새 code가 필요해지면 ErrorCode와 errors.md를 같은 커밋에서 고친다(R-M6)
    source: 가정 9, R-M6
```

## 규칙 요약

| ID | 무리 | 요지 |
|---|---|---|
| BR1.1 ~ BR1.4 | 승인 문구 | core DB 하나가 정본, 음성 없는 발화는 `approvedSpeech`, 세션 고정, 공백 제거 완전 일치 |
| BR2.1 ~ BR2.3 | 판 리소스 · 세션 고정 | 자원 파일(기동 실패), 세션 고정 + 기록 행 판 번호, 대본 등록 |
| BR3.1 ~ BR3.3 | 시간 값 | 새 키는 세 곳 함께, 기존 키 값은 쓰는 단위가, 코드에 숫자 금지 |
| BR4.1 ~ BR4.3 | 비밀 이름 | `OPENAI_API_KEY` · `JEV_API_KEY`를 목록 전부에, Neo4j는 `NEO4J_AUTH`, 값 금지 |
| BR5.1 ~ BR5.5 | AI 입출력 · 재시도 | 스키마가 정본, 목적별 재시도, 실패 저장 금지, 최소 필드, R-F15 가드 |
| BR6.1 | 기록 형식 | `flowVersion`으로 구분, `snapshotVersion` 그대로 |
| BR7.1 ~ BR7.3 | 계약 절차 | 더하기만 + U8 편차 행, 대조 시험 고치기 금지, 새 오류 코드 없음 |

## Sources

- `entities.md`, `functional-design-questions.md`(같은 폴더)
- `../../../inception/requirements-analysis/requirements.md`, `../../../inception/units-generation/unit-of-work.md`, `../../../inception/units-generation/unit-of-work-story-map.md`
- `../../../inception/domain-design/components.md`, `../../../inception/contract-design/contract-summary.md`
- 계약 · 코드: `contracts/ai-io.md`(5 · 7절), `contracts/timing.md`, `contracts/README.md`, `contracts/deviations.md`, `ConfigKeyContractTest`, `ContractFilesTest`, `.github/workflows/ci.yml:249` 등 비밀 목록

## Assumptions & Open Questions

- BR5.5의 '외부 전달 허가 상태'를 코드에서 어떻게 판정할지(설정 플래그 · 동의 스냅숏)는 U9 Functional Design에서 정한다. U8은 계약 문장만 둔다.
