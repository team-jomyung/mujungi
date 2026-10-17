# U13 제브 게이트 — 업무 규칙

> 근거: `functional-design-questions.md`(Q1-A · Q2-B · Q3-A와 가정 1 ~ 17), `../../inception/requirements-analysis/requirements.md`(FR1 ~ FR8, NFR1 ~ NFR9), `../../inception/reverse-engineering/developer-scan.md`(갭 G1 ~ G12).
> 시험 · 문서 정비 요구(FR8)와 PR 나누기 같은 작업 방식 요구는 업무 규칙을 만들지 않고 `traceability.json`에 N/A로 추적한다(project.md 교정). 다만 어기면 위험한 것(실제 공급자 호출 금지 · 비밀 · 병합 순서)은 아래 규칙이나 제약으로 둔다.
> 용어: '구간'은 `SegmentBuffer`가 한 응답의 출력 조각을 모아 `segment-quiet`(800ms) 조용 뒤 봉인한 덩어리(`Sealed`), '두 세대'는 기기 프레임 세대(`outputGeneration`)와 공급자 출력 세대(`OutputGeneration.generation()`), '턴'은 아이의 확정 전사 하나에 대한 응답 한 덩어리의 수명이다.

```yaml
rules:
  # BR1 포트 · 모드
  - id: BR1.1
    statement: 제브는 새 포트 GuardrailClient로만 부른다. 기존 EvaluationProvider · GateDecision · JudgeClient와 섞지 않는다. 요청 출처는 GPT_LIVE_GENERATED 하나이고 모드는 FREE_TALK · PRACTICE_PEER · PRACTICE_COACH다. 지금 운영에서 실제로 부르는 것은 FREE_TALK뿐이다.
    category: policy
    applies_to: GuardrailClient, GuardrailRequest
    trigger: 새 발화 구간이 검사 대상이 될 때
    logic: 협력 객체가 FREE_TALK 요청을 만들어 GuardrailClient.check를 비동기로 부른다. 연습 출력 통제 중에는 부르지 않는다
    violation: 판정 포트나 게이트 판정으로 제브를 부르거나, 연습 중 제브를 부르면 결함
    source: FR1.1, FR5.2, 원 FR6.1
  - id: BR1.2
    statement: 제브의 모든 결과는 예외 없이 GuardrailVerdict로 완료된다. 실패(시간 초과 · 전송 오류 · 잘못된 JSON · 정책 판 불일치 · 어댑터 없음)도 FAILED 결과이고, 자동 ALLOW로 바뀌지 않는다.
    category: constraint
    applies_to: GuardrailClient, GuardrailVerdict
    trigger: 검사 결과를 돌려줄 때
    logic: 어댑터는 모든 예외를 FAILED(failure 종류)로 완료한다. ALLOW는 응답의 decision이 ALLOW이고 판이 같을 때만 만든다
    violation: 실패가 ALLOW가 되거나 예외가 실행기로 새면 결함
    source: FR1.2, NFR2, 원 FR6.2
  - id: BR1.3
    statement: 모드는 설정 키 mujoong.guardrail.jev.mode 하나의 값(REAL · STUB · OFF)이다. 기본값은 demo · prod = REAL, dev = STUB이다. 세션 안에서 바뀌지 않는다.
    category: constraint
    applies_to: GuardrailMode
    trigger: 앱 기동
    logic: 기동 점검이 읽어 협력 객체와 어댑터 빈 선택에 쓴다. 키는 ai-io.md 10절과 ConfigKeyContractTest 대조에 넣는다
    violation: 키가 두 개이거나 세션 중 모드가 바뀌면 결함
    source: FR1.3, FR7.1, Q1 답 A
  - id: BR1.4
    statement: STUB은 제브 단계를 모두 지나며(검사표 · 요청 구성 · 지연 · guardrail_event · 지표) 1차 사전 통과를 ALLOW로 돌려준다. 모든 기록 · 지표 · 로그에 '대역'(mode STUB)이 표시되고, 그 통과를 실제 검사 성공으로 보고하지 않는다. STUB은 공개 프로필(demo · prod) 밖(dev · test · 활성 프로필 없음)에서만 허용한다.
    category: policy
    applies_to: StubGuardrailClient, GuardrailEvent, 지표
    trigger: mode가 STUB일 때 검사
    logic: 요청 구성은 실제와 같고 호출 대신 즉시 완료하는 대역이 ALLOW(reasonCode STUB_ALLOW)를 돌려준다. 시험 · CI · verify.sh는 가짜 제브(STUB 또는 시험이 손으로 푸는 대역)만 쓴다
    violation: 대역 통과를 실검사로 표시하거나 demo · prod에서 STUB이 켜지면 결함
    source: FR1.3, NFR9, AC2.3.2, AC2.3.4, 원 FR6.9
  - id: BR1.5
    statement: OFF는 제브 단계를 건너뛴다(지금 동작과 같다). guardrail_event와 제브 지표를 남기지 않고, 기동 때 '비활성'을 한 줄 로그로 남긴다. OFF는 공개 프로필(demo · prod) 밖(dev · test · 활성 프로필 없음)에서만 허용한다.
    category: policy
    applies_to: GuardrailCoordinator
    trigger: mode가 OFF일 때
    logic: sealAndGate가 제브 협력 객체를 거치지 않고 바로 recheck로 간다. 기동 로그는 appError(OUTPUT_GATE, GuardrailModeOff, WARN)의 사유 코드로 남긴다(새 사건 코드 없음)
    violation: demo · prod에서 OFF가 켜지거나, OFF가 '비활성' 표시 없이 조용히 켜지면 결함
    source: FR1.3, FR7.1
  - id: BR1.6
    statement: 제브 장치(GuardrailSupport)를 넣지 않는 기존 생성 경로(Services 오버로드 · 시험 하네스)는 OFF와 같다. 기존 U1 ~ U11 시험의 단언은 바뀌지 않는다.
    category: constraint
    applies_to: SessionExecutor.Services, SessionHarness
    trigger: 실행기 생성
    logic: GuardrailSupport.DISABLED(= OFF 의미, '검사 없음'이지만 REAL이 아니라는 표시)를 기본값으로 둔다. REAL은 기동 점검을 통과한 설정으로만 만들어진다
    violation: 기본값이 자동 ALLOW인 REAL처럼 동작하거나 기존 시험이 단언 변경을 요구하면 결함
    source: NFR6, 코드 스캔 6절
  - id: BR1.7
    statement: 제브 포트 · 어댑터 · 대역 · 기록기는 새 패키지 kr.mujoong.realtime.guardrail에 두고, 이 패키지는 luna · voice · judgment를 가져오지 않으며 luna는 guardrail을 가져오지 않는다. 제브 대기 상태는 실행기 스레드 전용 협력 객체로 떼고 SessionExecutor에는 호출 지점만 둔다.
    category: constraint
    applies_to: 패키지 구조
    trigger: 코드 구조를 정할 때
    logic: ArchUnit 규칙을 allowEmptyShould(false) 패턴으로 더한다(기존 규칙은 약화하지 않는다)
    violation: 의존 방향이 어긋나거나 실행기에 제브 상태 필드가 흩어지면 결함
    source: NFR8, 원 AC2.4.1

  # BR2 어댑터 · 요청
  - id: BR2.1
    statement: 실제 어댑터는 제브를 HTTPS POST 한 번만 부르고, 재시도하지 않는다. 시간 상한은 mujoong.timing.guardrail.jev-budget(800ms)이고 넘기면 TIMEOUT(막힘)이다. 키는 호출 때마다 환경에서 읽는다.
    category: constraint
    applies_to: JevGuardrailClient
    trigger: mode REAL의 검사 요청
    logic: 리다이렉트 없음 · 프록시 없음으로 한 번 보내고 시간 상한에 닿으면 끊는다. contracts/ai-io.md 재시도 분류 표에 제브 행("재시도 없음")을 더한다. 구현 도구는 Luna 호출부의 선례를 따른다(Code Generation에서 정함)
    violation: 호출 안 재시도, 800ms 초과 대기, 시간 값을 코드 숫자로 쓰기
    source: FR2.1, NFR1, 원 FR6.2
  - id: BR2.2
    statement: 보낼 호스트는 허용 호스트 상수(JevHosts.ALLOWED_HOSTS)에 있어야 한다. demo · prod는 기동 점검이, 모든 프로필은 보내기 직전 재확인이 거른다. 계약 문서(ai-io.md)와 시험이 같은 목록을 대조한다.
    category: validation
    applies_to: JevHosts, RealtimeStartupChecks
    trigger: 기동, 요청 직전
    logic: 설정의 주소 호스트가 목록 밖이면 기동 거부(demo · prod), 요청 직전에도 같으면 UNAVAILABLE이 아니라 TRANSPORT_ERROR로 막는다
    violation: 목록 밖 호스트로 요청이 나가면 결함
    source: FR2.1, Q4 답 A
  - id: BR2.3
    statement: 모델은 설정의 고정 버전이고 이동 별칭(jev-latest 등 -latest로 끝나는 이름)이면 demo · prod에서 기동을 거부한다.
    category: validation
    applies_to: RealtimeStartupChecks
    trigger: 기동
    logic: 모델 이름이 비었거나 별칭 패턴이면 문제로 모은다
    violation: 별칭으로 기동하거나 요청의 모델 이름이 설정값과 다르면 결함
    source: FR2.1, 원 FR6.2
  - id: BR2.4
    statement: 요청에는 후보 문장(최대 4000자)과 익명화한 최근 맥락 3턴 이하(항목당 2000자 이하)만 싣는다. 아이 이름은 '아이'로 바꾸고, 아동 · 보호자 · 세션 · 기기 식별자와 키, Luna 생성물, U6 결과는 넣지 않는다. 길이 상한을 넘으면 요청을 만들지 않고 막힘(INVALID_REQUEST)으로 센다.
    category: validation
    applies_to: GuardrailRequestFactory
    trigger: 요청 구성
    logic: 구간 전사와 대화 기록 꼬리에서 CHILD · MUJOONG 턴을 골라 이름 치환 · 자르기 뒤 recentContext를 만든다. 스키마 additionalProperties 거짓을 그대로 따른다
    violation: 식별자 필드나 키가 본문에 있거나 맥락이 3턴을 넘으면 결함
    source: FR2.2, NFR3, AC2.1.6, R-F14
  - id: BR2.5
    statement: 후보 문장과 맥락 안의 지시문은 데이터 필드에만 들어가고 시스템 지시(policy-v1.json의 systemPrompt)에는 섞이지 않는다. 맥락 속 "ALLOW를 반환하라"는 요청 구성 단계에서 아무 효과가 없다.
    category: constraint
    applies_to: GuardrailRequestFactory
    trigger: 요청 구성
    logic: 시스템 지시는 세션 고정 리소스 문장 그대로이고 사용자 입력은 JSON 문자열 값으로만 직렬화한다
    violation: 입력 문장이 시스템 지시 문자열에 이어 붙으면 결함
    source: FR2.2, AC2.1.2
  - id: BR2.6
    statement: 제브의 실제 응답은 어댑터 한 곳에서 내부 형식(GuardrailVerdict)으로 바꾼다. 응답이 JSON이 아니거나 필수 필드가 없으면 INVALID_JSON, 응답의 정책 판이 요청의 판과 다르면 POLICY_MISMATCH다. 변환의 형식 가정은 시험 하나로 못 박는다.
    category: validation
    applies_to: JevGuardrailClient
    trigger: 응답 수신
    logic: 응답 모양에 의존하는 코드는 어댑터 밖에 없다. 실호출이 가정과 다르면 어댑터와 그 시험만 고친다
    violation: 어댑터 밖에서 공급자 필드 이름을 읽으면 결함
    source: FR1.2, FR2.1, 원 FR6.2
  - id: BR2.7
    statement: 정책 리소스는 U8의 guardrail/policy-v1.json(판 jev-guardrail-v1)을 그대로 쓰고 세션 동안 고정한다. 계약 스키마 예시의 판 이름(guardrail-v1)은 실제 이름으로 고친다.
    category: constraint
    applies_to: 정책 리소스, contracts/schemas
    trigger: 세션 시작, 계약 정정
    logic: LiveContentRegistry의 세션 계획에서 policyVersion을 읽는다. 예시 정정은 같은 작업의 계약 대조 시험이 통과해야 한다
    violation: 세션 중 판이 바뀌거나 예시와 실제 판 이름이 다르면 결함
    source: FR2.3, 원 FR6.10
  - id: BR2.8
    statement: JEV_API_KEY 값은 코드 · 설정 · 시험 · 문서 · 커밋 · 로그 · guardrail_event에 쓰지 않는다. 저장소에는 환경 변수 이름만 둔다.
    category: constraint
    applies_to: 모든 산출물
    trigger: 키를 다루는 모든 곳
    logic: 어댑터가 호출 때마다 읽고 보관하지 않는다
    violation: 값이 어디든 남으면 결함(R-F3)
    source: FR2.1, NFR3

  # BR3 게이트의 제브 단계
  - id: BR3.1
    statement: 제브 검사는 새 발화 구간에만 한다. sealAndGate에서 inspect(구조 · 1차 사전) 통과 뒤, 연습 원문 일치 검사 뒤, recheck 앞에서 협력 객체가 비동기로 이어 붙인다. OutputGate(동기 판정 클래스)는 바꾸지 않고, 실행기 스레드는 어떤 HTTP도 기다리지 않는다.
    category: constraint
    applies_to: SessionExecutor.sealAndGate, GuardrailCoordinator
    trigger: 구간 봉인 뒤 inspect 통과
    logic: 승인 원문이 아니면 검사표를 만들어 요청하고 sealAndGate를 거기서 끝낸다. 결과는 편지함 메시지 GuardrailArrived로 실행기 스레드에 돌아와 이어진다. 이 메시지는 U11 JudgmentArrived와 같이 일반 편지함(putFromProvider)으로 넣는다 — 안전 편지함은 일반 편지함보다 먼저 비워져 이미 쌓인 기기 · 공급자 사건이 반영되기 전 상태로 ALLOW가 검증되기 때문이다. begin을 부르는 자리에 전용 try/catch를 둔다(공용 sealAndGate catch는 hasOpenAi()가 거짓이면 아무것도 하지 않으므로 기대지 않는다). begin이 보관 행을 만든 뒤 예외를 던지면 ① 보관 행을 FINAL · 거짓으로 닫고 ② 검사표를 버리고 협력 객체를 IDLE로 돌린 뒤 ③ discard(sealed, GUARDRAIL_ERROR, correct=false)를 부른다. ③의 마무리(대기 문구 또는 listeningAgain)가 기기를 THINKING에서 풀어 준다
    violation: 실행기 스레드에서 HTTP를 기다리거나 inspect 앞에서 부르거나 결과를 안전 편지함으로 넣으면, 또는 begin 예외 때 보관 행이 PARTIAL로 남으면 결함
    source: FR3.1, 원 FR6.3
  - id: BR3.2
    statement: 유효한 ALLOW일 때만 그 구간을 기기로 보낸다. 유효한 ALLOW가 오면 그때 recheck(NOT_ACTIVE · RECORDER_DEGRADED · 출력 레인 예약)를 부르고 send한다. REAL 모드에서 자동 ALLOW는 없다.
    category: constraint
    applies_to: GuardrailCoordinator, OutputGate.recheck, send
    trigger: GuardrailArrived(ALLOW) 처리
    logic: 검사표 유효 조건(BR4.2)을 확인하고 통과하면 recheck → send. send 안의 보호자 화면 승인(aiFinal true)은 이 시점이다
    violation: ALLOW 전에 recheck 또는 send를 하거나 ALLOW가 아닌 결과로 송신하면 결함
    source: FR3.1, AC2.1.1, NFR2, 원 FR6.3
  - id: BR3.3
    statement: BLOCK · REVIEW · 시간 초과 · 전송 오류 · 잘못된 JSON · 정책 판 불일치 · 어댑터 없음은 모두 '막힘'이고 같은 흐름으로 처리한다. 그 구간은 기기로 한 바이트도 보내지 않는다.
    category: constraint
    applies_to: GuardrailCoordinator
    trigger: GuardrailArrived(ALLOW가 아닌 결과)
    logic: 구간 AI 행을 FINAL · 승인 거짓으로 닫고, 막힘 사유 코드(GUARDRAIL_BLOCK · GUARDRAIL_REVIEW · GUARDRAIL_TIMEOUT · GUARDRAIL_ERROR)로 mujoong.gate.discard 지표를 올린 뒤 BR6.1로 간다. 이 사유 값들은 GateDecision.DiscardReason 열거형에 새 값으로 더한다(기존 값은 그대로, 지표 태그 값 목록과 열거형을 단언하는 시험은 새 사실로 갱신). 대기열 폐기 GUARDRAIL_QUEUE_FULL도 같은 열거형에 더하고, 무효 원인은 discard 사유로 쓰지 않고 guardrail_event 사유 코드로만 남긴다
    violation: 막힘 결과로 소리가 나가거나 사유가 섞이면 결함
    source: FR3.2, AC2.1.3, NFR2, 원 FR6.4
  - id: BR3.4
    statement: 제브 대기와 다시 지시 동안 기기는 '생각 중'(THINKING)을 유지한다. 검사가 끝나 소리가 나가거나 턴을 포기할 때까지 listeningAgain이 듣는 중으로 되돌리지 않는다.
    category: constraint
    applies_to: SessionExecutor.listeningAgain, thinking
    trigger: 검사 시작부터 끝까지
    logic: listeningAgain의 유지 조건에 guardrail.isChecking() 또는 재지시 대기 중을 더한다(judgment.isOpen() · practiceThinking과 같은 모양)
    violation: 검사 중 듣는 중으로 돌아가 아이가 말을 걸어도 응답이 오가는 것처럼 보이면 결함
    source: FR3.2, AC2.1.3, 코드 스캔 1절

  # BR4 검사표 · 늦은 ALLOW
  - id: BR4.1
    statement: 검사표는 요청 id, 봉인된 구간(Sealed 인스턴스), 기기 프레임 세대, 공급자 출력 세대, 기기 연결 id, 입력 묶음 id, 연습 발화 id(있으면), AI 행 보관 손잡이를 담는다. 후보 문장은 그 Sealed의 전사이고 송신은 그 Sealed의 조각으로만 한다(다시 봉인하거나 버퍼에서 다시 읽지 않는다). 소리와 글이 실제로 같은지는 코드로 독립 검증하지 못한다는 한계를 설계서에 명시한다.
    category: constraint
    applies_to: GuardrailTicket
    trigger: 검사 요청 시
    logic: 전사 ↔ PCM 일치는 같은 응답 · 같은 구간이라는 구조로 보장한다
    violation: 다른 구간의 전사와 PCM이 짝지어 나가면 결함
    source: FR4.2, AC2.1.5, 원 FR6.5
  - id: BR4.2
    statement: ALLOW는 다음을 모두 만족할 때만 유효하다. ① 결과의 요청 id가 지금 진행 중인 검사표의 id와 같다 ② 공급자 출력 세대와 기기 프레임 세대가 요청 때와 같다 ③ 상태가 ACTIVE이고 같은 기기 연결이 열려 있으며 세션이 끝나지 않았고 출력 보류(outputHeld)가 아니다 ④ 연습 발화였다면 pendingPracticeSpeech가 그 발화와 같다 ⑤ 입력 묶음이 새로 열리지 않았다(BR4.4).
    category: constraint
    applies_to: GuardrailCoordinator
    trigger: GuardrailArrived 처리
    logic: 하나라도 어긋나면 무효(VOIDED). 판정 턴이 열리면 공급자 출력 세대가 오르므로 ②가 잡는다
    violation: 어긋난 상태에서 늦은 ALLOW가 재생되면 결함
    source: FR4.1, AC2.1.4, 원 FR6.5
  - id: BR4.3
    statement: 무효가 된 검사의 구간은 재생하지 않는다. guardrail_event에는 decision VOIDED와 원인 사유 코드(SESSION_ENDED · CONNECTION_CHANGED · GENERATION_CHANGED · CHILD_SPOKE · HELD · PRACTICE_CHANGED 등)를 남기고 구간 AI 행은 FINAL · 거짓으로 닫는다. 재지시는 하지 않는다(상태가 이미 바뀌었기 때문).
    category: constraint
    applies_to: GuardrailCoordinator
    trigger: 무효 판정
    logic: 새 턴 · 끼어들기 · 종료가 진행 중이면 그 흐름이 이어 간다. 무효 처리 뒤 listeningAgain만 부른다
    violation: 무효 결과로 재지시가 나가거나 기록이 남지 않으면 결함
    source: FR4.1, FR4.3, AC2.1.7
  - id: BR4.4
    statement: 검사 중 아이가 '새로 말을 시작'하면 그 검사는 무효다. 새 말 시작은 검사 요청(begin) 이후에 새 아이 입력 묶음이 열린 경우이고, 같은 입력 묶음의 입력 전사 꼬리 조각은 새 말로 보지 않는다. 기준 시점을 첫 출력 조각이 아니라 begin으로 잡는다. 알려진 한계: 첫 조각과 봉인 사이에 아이가 말을 시작해 아이 입력 재개로 sealAndGate(true)가 닫은 구간은 열림 횟수가 이미 오른 뒤 봉인되므로 이번 검사의 무효 조건에 잡히지 않고, quietEnough여서 완전 구간으로 봉인되어 inspect를 통과하면 그대로 검사된다. 제브가 없을 때의 지금 동작과 같아 회귀가 아니다.
    category: constraint
    applies_to: GuardrailCoordinator, SessionExecutor.onInputTranscriptDelta
    trigger: 검사 중 입력 전사 조각 도착, 결과 도착
    logic: 입력 묶음을 처음 열 때(groupInputDelta) 실행기가 세는 '입력 묶음 열림 횟수'를 검사표에 적고, 결과 시점의 횟수가 늘었으면 무효(CHILD_SPOKE)다. 묶음 id를 비교하지 않는다 — 묶음이 확정되어 clearInputGroup이 id를 지워도 횟수는 줄지 않아, 같은 묶음의 꼬리 조각이 입력 전사 확정(input-transcript-quiet, 1500ms)보다 먼저 끝나든 늦게 끝나든 판정이 어긋나지 않는다(검사 시한 800ms와 조용 시간의 대소에 기대지 않는다). ALLOW가 와도 재생하지 않고 기록은 남긴다
    violation: 같은 턴의 꼬리 조각이 정상 ALLOW를 무효로 만들거나, 새 묶음이 열렸는데 ALLOW가 재생되면 결함
    source: FR4.3, Q2 답 A(요구사항), 원 FR6.5
  - id: BR4.5
    statement: 세션 종료 · 정산 · 일시 정지 · 보호자 종료 · 동의 철회 · 공급자 끊김으로 teardown이 되면 진행 중인 검사 요청을 취소하고, 이후 도착한 결과는 terminated로 버린다. 취소된 검사의 구간 AI 행은 closeAll이 FINAL · 거짓으로 닫는다.
    category: constraint
    applies_to: GuardrailCoordinator, SessionExecutor.teardown
    trigger: 세션 이탈
    logic: 요청 취소를 호출 쪽 전송 계층까지 전파한다(LunaJudgeClient 선례)
    violation: 이탈 뒤 결과가 재생되거나 보관 행이 PARTIAL로 남으면 결함
    source: FR4.1, 코드 스캔 위험 요약 3

  # BR5 대기열 1칸
  - id: BR5.1
    statement: 세션당 진행 중인 제브 검사는 최대 1건이다.
    category: constraint
    applies_to: GuardrailCoordinator
    trigger: 새 구간 봉인
    logic: 진행 중 검사가 있으면 새 구간은 BR5.3으로 간다
    violation: 동시 검사가 둘 이상이면 결함
    source: FR3.3
  - id: BR5.2
    statement: 검사 중 도착한 새 출력 조각은 받는다. SegmentBuffer의 새 구간으로 모으고 공급자 출력 세대 열쇠 규칙은 그대로다. 봉인된 앞 구간은 검사 중이라 별개다.
    category: constraint
    applies_to: SessionExecutor.onOutputChunk, SegmentBuffer
    trigger: 검사 중 조각 도착
    logic: 출력 보류(outputHeld)처럼 버리지 않는다. 앞 구간의 AI 행은 보관 손잡이로 빠져 있으므로 새 aiStart는 새 행을 연다
    violation: 새 조각이 앞 구간에 섞이거나 앞 AI 행을 재사용하면 결함
    source: FR3.3, Q2 답 B
  - id: BR5.3
    statement: 새 구간이 봉인되었는데 앞 검사가 아직 진행 중이면 그 구간은 대기열 1칸에 보관한다. 대기열이 이미 찼는데 또 구간이 봉인되면 새 구간은 폐기한다(사유 GUARDRAIL_QUEUE_FULL, guardrail_event decision QUEUE_FULL).
    category: constraint
    applies_to: GuardrailCoordinator
    trigger: 검사 중 구간 봉인
    logic: 대기 구간은 inspect(구조 · 1차 사전)를 먼저 통과해야 하고, 통과 못 하면 보관하지 않고 기존 discard로 간다
    violation: 대기열이 두 칸 이상이 되거나 폐기 기록이 없으면 결함
    source: FR3.3, Q2 답 B
  - id: BR5.4
    statement: 앞 검사가 유효한 ALLOW로 송신을 마친 경우에만 대기 구간을 꺼내 검사한다. 앞 검사가 막힘 · 무효 · 포기(재지시 불발 포함)로 끝났으면 세대가 올랐는지와 무관하게 대기 구간을 모두 폐기한다.
    category: constraint
    applies_to: GuardrailCoordinator
    trigger: 앞 검사 결과 처리 뒤
    logic: 꺼내기 전에 대기 구간의 봉인 때 공급자 출력 세대가 지금과 같은지, 연결 · 상태 · 출력 보류 · 입력 묶음 열림 횟수가 그대로인지 다시 확인한다. 하나라도 다르면 재생하지 않고 폐기한다. 꺼낸 구간의 검사표는 꺼내는 순간의 세대 · 연결 · 입력 묶음 열림 횟수로 새로 만든다. 폐기하는 구간의 보관 AI 행은 aiFinal(손잡이, 전사, 거짓)로 닫고 guardrail_event를 남긴다(decision VOIDED, 사유 코드 QUEUE_DISCARDED). 재지시가 나가면 공급자 출력 세대가 오르므로 그것으로도 낡지만, 세대가 오르지 않는 무효 · 포기 경로(discard(correct=false)는 세대를 올리지 않는다)를 위해 폐기를 세대와 무관하게 강제한다
    violation: 낡은 대기 구간이 재생되거나, 폐기한 대기 구간의 AI 행이 PARTIAL로 남으면 결함
    source: FR3.3, FR4.1, Q2 답 B
  - id: BR5.5
    statement: 보호자 화면 AI 행은 구간이 검사를 기다리는 동안 보관 손잡이(HeldAiRow)로 TranscriptWriter의 열린 행(openAi)에서 분리한다. 보관 행은 DB에서 PARTIAL로 남아 있다가 결과에 따라 aiFinal(손잡이, 텍스트, 승인 여부, 시각)로 한 번에 닫는다. 종료 · 취소(closeAll) 때 보관 행도 FINAL · 거짓으로 닫는다.
    category: constraint
    applies_to: TranscriptWriter
    trigger: 구간 봉인(검사 요청), 결과 처리, 종료
    logic: aiHold()가 openAi를 손잡이로 돌려주며 비운다. 새 구간의 aiStart는 새 행 번호를 만든다. 보관 행은 최대 2개(검사 중 1건 + 대기열 1칸)이고 동시에 살아 있는 AI 행은 최대 3개다. 대기열이 차서 폐기되는 구간은 보관하지 않고 바로 FINAL · 거짓으로 닫는다
    violation: 보관 행이 PARTIAL로 영구히 남거나 새 구간이 보관 행을 재사용하면 결함
    source: FR3.3, FR6.4, Q2 답 B, 코드 스캔 1절
  - id: BR5.6
    statement: 검사 대기 때문에 겹치는 두 응답의 지연 기록(LatencyRecorder)은 앞 턴의 결과가 처리된 뒤 뒤 턴이 시작된 것으로 기록한다. 앞 턴의 지표를 뒤 턴이 덮지 않는다.
    category: policy
    applies_to: LatencyRecorder
    trigger: 검사 중 새 구간의 첫 조각
    logic: 새 구간의 providerFirstOut은 기록해 두되 turn 마감은 앞 턴 결과 처리 뒤에 한다. 세부는 Code Generation에서 LatencyRecorder 한 턴 규칙에 맞춘다
    violation: 앞 턴의 gatePassed · 지연이 사라지거나 두 턴이 섞이면 결함
    source: FR3.3, 코드 스캔 위험 요약 6

  # BR6 막힘 처리 · 재지시
  - id: BR6.1
    statement: 재지시는 턴당 최대 1회다. 한 턴에서 첫 막힘이면 같은 다음 행동을 한 번 다시 지시하고(BR6.2), 같은 턴에서 또 막히면 그 턴을 포기한다(BR6.3). 턴은 아이의 확정 전사 하나로 시작해 다음 확정 전사 · 기기 끼어들기 · 세션 종료로 끝난다.
    category: policy
    applies_to: GuardrailTurnState
    trigger: 막힘 처리
    logic: retries가 0이면 재지시하고 1로 올린다. 1이면 포기한다. 새 턴이 시작되면 0으로 돌린다
    violation: 한 턴에서 재지시가 2회 이상이거나 포기해야 할 때 재지시가 나가면 결함
    source: FR3.2, AC2.1.3, Q1 답 A(요구사항)
  - id: BR6.2
    statement: 막힌 구간이 서버가 보낸 다음 행동 지시에 대한 응답이면 같은 템플릿 지시를 다시 보낸다. 서버 지시 없는 자유 응답이면 새 템플릿 RETRY_FREE_TURN(방금 말은 쓰지 않고 같은 흐름에서 다른 말로 짧게 한 번만 이어 달라)을 InstructionTemplates에 더해 보낸다. 새 승인 문구는 만들지 않는다. 승인 발화(연습 speak) 지시는 다시 보내지 않는다.
    category: policy
    applies_to: InstructionTemplates, SessionExecutor.sendInstruction
    trigger: 첫 막힘(retries 0)
    logic: 기억하는 것은 판정 뒤 다음 행동 지시(NextAction 템플릿)와 응답 시작 지시(requestResponse의 ResponseStart) 두 호출 지점뿐이다. 연습 승인 발화 · 연습 확장점 지시 · 게이트 교정 · 제브 재지시 자신은 기억하지 않는다(functional-spec W2b 표). 기억한 지시는 새 턴(아이 확정 전사), 다른 종류의 sendInstruction, 재지시로 한 번 쓰임 중 먼저 오는 때에 지운다. 기억이 없으면(자유 응답 · 교정 응답) RETRY_FREE_TURN을 보낸다. 출력 세대 올림 사유는 새 값 GUARDRAIL_RETRY다. 재지시 문구에는 정책 내용 · 판정 사유 · 후보 문장이 없다
    violation: 지시문에 막힌 문장이나 사유가 담기거나 아이에게 지시문이 들리면 결함
    source: FR3.2, Q3 답 A
  - id: BR6.3
    statement: 같은 턴에서 재지시 뒤 또 막히면 자유대화는 그 턴을 포기한다. 구간은 폐기하고 기존 discard의 마무리를 쓴다(대기 문구 WAITING_FILLER 재생 또는 듣기로 복귀). 이때 고정 교정 문장(correctAfterDiscard)은 보내지 않는다.
    category: policy
    applies_to: SessionExecutor.discard
    trigger: 재지시 뒤 막힘(자유대화)
    logic: discard(sealed, 제브 사유, correct=false)를 부른다
    violation: 포기해야 할 때 세 번째 지시가 나가거나 아이가 아무 반응도 못 받으면 결함
    source: FR3.2, AC2.1.3
  - id: BR6.4
    statement: 재지시에 대한 응답도 같은 제브 검사를 거친다. 재지시 응답이라서 검사를 건너뛰지 않는다.
    category: constraint
    applies_to: GuardrailCoordinator
    trigger: 재지시 뒤 새 구간 봉인
    logic: 같은 BR3.1부터의 경로를 탄다
    violation: 재지시 응답이 검사 없이 재생되면 결함
    source: FR3.2, NFR2
  - id: BR6.5
    statement: 연습 중 막힘은 연습 확장점에 GuardrailBlockedEvent(연습 발화 id, 모드, 사유 코드, 재지시 여부)로 알린다. U13은 이 사건의 정의와 단위 시험(PRACTICE_PEER · PRACTICE_COACH를 대역으로 주입)까지만 하고, 운영에서 이 사건을 만드는 것은 U12다. 지금 운영 연습 중에는 제브를 부르지 않는다.
    category: policy
    applies_to: PracticeSessionExtension, GuardrailCoordinator
    trigger: 연습 모드 요청의 두 번째 막힘(시험에서만)
    logic: 확장점 이벤트 열거형에 사건 종류를 더하고 기존 확장 구현은 바꾸지 않는다
    violation: 운영 연습 경로에서 제브가 불리거나 기존 확장 구현체가 수정되면 결함
    source: FR3.2, FR5.2, Q5 답 A(요구사항)
  - id: BR6.6
    statement: 재지시가 소리로 이어지지 않으면 그 턴을 포기한다. 재지시를 보내지 못했거나(sendInstruction이 거짓 — 공급자 연결 없음) 송신이 실패했거나 새 말이 시한 안에 시작하지 않으면(NEW_SPEECH_NOT_OBSERVED), 또는 재지시 응답이 봉인됐지만 제브에 닿기 전에(begin 앞에서) 어떤 길로든 폐기되면(inspect 실패 · 연습 원문 불일치 · SEGMENT_OVERSIZE · 소리 없는 전사 ABANDON_NO_AUDIO · 공급자 오류 · catch · begin 예외) 협력 객체는 RETRYING에서 IDLE로 돌아오고 기기가 THINKING에서 풀린다. 기기가 THINKING에 계속 머무는 일은 없다.
    category: constraint
    applies_to: GuardrailCoordinator, SessionExecutor.sendInstruction onFailure, onInstructionWaitExpired, SessionExecutor.discard
    trigger: 재지시 송신 결과, 지시 응답 시한 만기, 재지시 응답의 begin 앞 폐기
    logic: sendReplay · sendRetryFreeTurn이 sendInstruction의 반환값과 onFailure를 onRetryNotHeard로 잇는다. 응답이 begin에 닿기 전에 폐기되는 길은 여럿이므로(sealAndGate의 inspect 실패 · 연습 원문 불일치 · catch, onOutputChunk의 오버사이즈, onOutputQuiet의 ABANDON_NO_AUDIO, providerError, recheck 실패) 길마다 훅을 걸지 않고 discard() 한 지점의 맨 앞(AI 행을 닫기 전)에서 사유가 GUARDRAIL_*가 아니고 협력 객체가 RETRYING이면 onDiscardedBeforeCheck()로 IDLE로 돌린다. 훅은 listeningAgain을 부르지 않는다 — discard의 기존 마무리(교정 지시 · 대기 문구 · listeningAgain)가 그대로 이어지므로 중복 호출이 없다. discard가 교정 지시를 보내 새 응답을 기다리게 되면 그 응답은 새 검사(CHECKING)로 가고 retries가 이미 1이라 또 막히면 곧바로 포기한다. sealed가 null인 경로는 폐기가 아니라 아직 응답이 오지 않은 것이라 훅을 부르지 않는다. 지시 응답의 첫 조각이 오면 instructionNotHeard가 지워지고 correctAfterDiscard가 새 sendInstruction으로 그것을 덮어쓸 수 있으므로 onFailure에만 기대지 않는다. 사유 코드 GuardrailRetryNotHeard를 WARN으로 남기고 retries는 1로 둔다(더 재지시하지 않음)
    violation: 재지시가 불발이거나 응답이 begin 앞에서 폐기되었는데 기기가 THINKING에 머물거나 retries가 되돌려져 재지시가 반복되면 결함
    source: FR3.2, AC2.1.3

  # BR7 승인 원문 생략
  - id: BR7.1
    statement: 승인 원문과 같은 재생은 제브를 부르지 않고 기존 게이트 검사와 출처 · 원문 일치 확인만 거쳐 재생한다. 분류 순서는 ① 연습이 출력을 쥐고 있고 pendingPracticeSpeech.matches(전사)이면 생략 ② 레지스트리 matchApproved가 MATCHED이면 생략 ③ 그 밖은 새 발화다.
    category: policy
    applies_to: SessionExecutor.sealAndGate, LiveContentRegistry
    trigger: inspect 통과 뒤
    logic: 생략하면 검사표를 만들지 않고 recheck로 간다. 두 일치 모두 서버 기억(서버가 보낸 지시 · 등록된 원문)만 근거로 한다
    violation: 모델이 밝힌 출처만으로 생략하거나, 일치하지 않는 출력이 생략되면 결함
    source: FR5.1, AC2.2.1, 원 FR6.6
  - id: BR7.2
    statement: 원문에서 단어를 더하거나 빼거나 바꾼 출력, 출처나 원문 일치를 확인할 수 없는 출력(UNKNOWN · NOT_MATCHED)은 새 발화로 보고 제브 검사를 거친다. 검사할 수 없으면 막힌다.
    category: constraint
    applies_to: LiveContentRegistry.matchApproved
    trigger: 분류
    logic: allowsGuardrailSkip()은 MATCHED만 참이다
    violation: UNKNOWN에서 제브가 생략되면 결함
    source: FR5.1, AC2.2.2, AC2.2.3
  - id: BR7.3
    statement: 원문 일치의 정규화는 ASCII 공백 제거뿐이다(PracticeSpeech.matches와 같다). 1차 사전의 정규화(SeedDictionary.normalize, NFKC 등)와 섞지 않는다.
    category: constraint
    applies_to: 일치 판단
    trigger: 분류
    logic: NBSP · 전각 공백 · 제로폭이 끼면 NOT_MATCHED(안전 쪽)다
    violation: 두 정규화를 섞어 쓰면 결함
    source: FR5.1, 코드 스캔 2절
  - id: BR7.4
    statement: LiveContentRegistry를 SessionExecutor.Services에 연결한다(좁은 포트). 세션 고정 대본(registerScript) 일치는 U12가 대본을 등록한 뒤부터 유효하고, 그 전에는 대본 문장은 NOT_MATCHED다. 승인 문구(approvedContent) 음성 파일 재생은 게이트를 지나지 않으므로 대상이 아니다.
    category: constraint
    applies_to: Services, LiveContentRegistry
    trigger: 실행기 생성
    logic: 오버로드 사슬을 늘리지 않고 GuardrailSupport 기록 안에 포트를 담는다
    violation: Services 오버로드가 시험을 깨뜨리면 결함
    source: FR5.1, 코드 스캔 2절 G10
  - id: BR7.5
    statement: 승인 원문 생략도 guardrail_event를 sourceKind APPROVED_VERBATIM_SKIPPED, decision ALLOW, 지연 0으로 남긴다(호출 수 지표의 '원문 재생 생략' 구분).
    category: policy
    applies_to: GuardrailEventRecorder
    trigger: 생략 결정
    logic: 모드 OFF에서는 남기지 않는다
    violation: 생략이 기록 없이 지나가면 결함
    source: FR5.1, FR6.3, AC2.1.8
  - id: BR7.6
    statement: 연습이 출력을 쥐고 있을 때 원문과 일치하지 않는 출력은 지금처럼 GATE_PATTERN으로 폐기하고 제브를 부르지 않는다.
    category: constraint
    applies_to: SessionExecutor.sealAndGate
    trigger: 연습 통제 중 inspect 통과 뒤
    logic: 기존 분기를 그대로 둔다
    violation: 연습 중 새 발화가 제브로 검사되어 U5b 연습 시험의 단언이 바뀌면 결함
    source: FR5.2, NFR6, Q5 답 A(요구사항)

  # BR8 기록 · 삭제 · 지표 · 보호자 화면
  - id: BR8.1
    statement: 검사마다(OFF 제외) guardrail_event 한 행을 남긴다. 담는 것은 id(= 검사 요청 id), 보호자 · 세션 id, 모드, 출처 구분, 판정, 사유 코드, 정책 판, 지연(ms), 만든 시각뿐이다. 전사 원문 · 후보 문장 · 맥락 칸이 없다.
    category: constraint
    applies_to: GuardrailEvent, V6 마이그레이션
    trigger: 검사 결과 처리, 생략 결정, 무효, 대기열 폐기
    logic: 같은 id는 덮어쓴다. 시각은 MujoongClock을 바인딩한다
    violation: 원문이 어떤 칸에든 담기면 결함(R-F14)
    source: FR6.1, NFR3, NFR4, AC2.1.7
  - id: BR8.2
    statement: 쓰기는 비동기 기록기(GuardrailEventRecorder)가 한다. 실행기는 DB를 기다리지 않고, 일시 실패는 다음 틱에 재시도하며, 외래 키 · CHECK 위반(23503 · 23514)은 버리고, 삭제 중인 보호자는 거절한다. 미커밋 칸 목록을 가지므로 단일 인스턴스 표식(SI-10)과 SingleInstanceMarkerTest 지도에 더한다.
    category: constraint
    applies_to: GuardrailEventRecorder
    trigger: put 호출
    logic: JudgmentLiveRecorder와 같은 모양의 새 클래스를 guardrail 패키지에 둔다(공통화하지 않음)
    violation: 실행기가 DB를 기다리거나 표식 지도에서 빠지면 결함
    source: FR6.1, 코드 스캔 4절
  - id: BR8.3
    statement: guardrail_event는 보호자 삭제 참여자(GuardrailEventDeletionParticipant, 순서 185)로 세션 행(200)보다 먼저 지운다. DeletionCompleted.Counts에는 항목을 더하지 않는다.
    category: constraint
    applies_to: DeletionRequestedHandler, RealtimeDeletionParticipant
    trigger: 보호자 삭제 요청
    logic: 순서 시험(RealtimeDeletionParticipantOrderTest)과 RealtimeDb의 비우기 목록을 새 사실로 갱신한다
    violation: 삭제 뒤 guardrail_event가 남거나 세션 삭제가 FK에 막히면 결함
    source: FR6.2
  - id: BR8.4
    statement: 보호자 화면용 승인(aiFinal true)은 유효한 ALLOW 뒤 기기로 보내는 시점에 한다. 막힌 구간 · 무효 구간 · 폐기된 대기 구간은 승인 거짓이라 12 말풍선 중계 · 27 대화 기록 · PDF에 나오지 않는다. 12 중계는 ALLOW 뒤 보낸 구간부터 보인다(재생 시작 PlaybackMark가 아니라 서버가 보내는 시점).
    category: constraint
    applies_to: SessionExecutor.send, TranscriptWriter
    trigger: send / 막힘 / 무효
    logic: 새 필터를 core나 프런트에 더하지 않는다(approved_for_display 필터가 이미 걸러 낸다). 막힌 문장 원문이 realtime_transcript에 승인 거짓으로 남는 지금 동작은 바꾸지 않는다
    violation: 막힌 문장이 보호자 화면 · 기록 · PDF에 승인된 행으로 나오면 결함
    source: FR6.4, AC2.1.9
  - id: BR8.5
    statement: 계측 항목은 ① 출처별 호출 수(새 발화 / 원문 재생 생략) ② 제브 지연 관측값(P50 · P95 · P99를 낼 수 있는 히스토그램) ③ 차단 수 ④ 시간 초과 수 ⑤ 아이 체감 대기(아이 말 끝 → 첫 음성)다. 합격 기준이 없는 측정 항목이다. 이름 · 종류는 설계서(docs/units/u13/design.md)에 적고 RealtimeMetrics에 mujoong.guardrail.* 접두어로 더한다. 모드 STUB은 태그로 구분한다.
    category: policy
    applies_to: RealtimeMetrics, application.yml 히스토그램 버킷
    trigger: 검사 결과 처리
    logic: 새 사건 코드를 만들지 않으므로 contract-model · log-hygiene-check 허용 표는 바뀌지 않는다
    violation: 대역 지표가 실검사와 구분되지 않거나 지표 이름이 설계서에 없으면 결함
    source: FR6.3, NFR4, AC2.1.8
  - id: BR8.6
    statement: 세션 종료 때 합계 로그 한 줄은 새 사건 코드(EventCode · AttrKey)가 필요해 계약 변경이 되므로 이 단위는 만들지 않는다. 대신 세션 단위 합계는 guardrail_event 행의 집계로 낼 수 있고, 프로세스 합계는 지표로 본다(편차 U13-D2).
    category: policy
    applies_to: 계측
    trigger: 세션 종료
    logic: 요구사항 FR6.3의 '세션 종료 시 합계 로그 한 줄'을 이 단계에서 줄인다. 가정 12(새 사건 코드를 만들지 않음)를 따른다
    violation: 합계 로그를 위해 contract-model을 몰래 바꾸면 결함
    source: FR6.3, 가정 12

  # BR9 기동 · 인프라
  - id: BR9.1
    statement: 기동 점검의 모드 × 프로필 × 키 규칙은 다음과 같다. 프로필 분류는 기존 PUBLIC_PROFILES = {demo, prod}를 따른다. 여기에 속하면 REAL만 허용하고 JEV_API_KEY가 비어 있으면 기동 거부(이유를 로그에 남김). 그 밖(dev · test · 활성 프로필 없음)은 REAL · STUB · OFF 모두 허용하되 REAL이면 키가 있어야 한다. 기본 모드는 공개 프로필 = REAL, 그 밖 = STUB이다. 어느 프로필이든 키가 없으면 실제 어댑터는 켜지지 않는다.
    category: validation
    applies_to: RealtimeStartupChecks
    trigger: 기동
    logic: problems()에 규칙을 더한다. 제브 입력(모드 · 키 유무 · 주소 · 모델)은 Inputs 레코드에 필드로 더하되 기존 생성자 오버로드를 유지해 기존 시험이 그대로 컴파일되게 한다(기존 Inputs에는 이 슬롯이 없다)
    violation: demo · prod가 STUB · OFF나 키 없는 REAL로 기동하면 결함
    source: FR7.1, AC2.3.1, AC2.3.2, AC2.3.3, 원 FR6.9
  - id: BR9.2
    statement: 비실검사 모드(STUB · OFF)에서 시작하면 그 사실이 기동 로그에 한 줄 남는다.
    category: policy
    applies_to: 기동 로그
    trigger: 기동
    logic: BR1.4 · BR1.5의 표시와 같은 사유 코드를 쓴다
    violation: 비실검사로 시작하고 흔적이 없으면 결함
    source: FR1.3, FR7.1
  - id: BR9.3
    statement: 인프라 검사는 새 규칙에 맞춘다. render-env의 realtime JEV_API_KEY는 demo에서 필수, image-checks.sh ④의 demo 더미 기동은 실행 때 만든 가짜 키(고정 비밀 값을 저장소에 두지 않음)와 가짜 호스트를 쓰고 .gitleaks.toml 허용 항목(경로 + 줄 정규식 + 사유)과 맞춘다. 검증 스택(compose.ci.yml)은 dev 프로필이라 기본 STUB으로 영향이 없다. RUNBOOK에 키를 먼저 Parameter Store에 넣는 순서를 적는다.
    category: constraint
    applies_to: infra/bin/render-env, infra/ci/image-checks.sh, infra-validate, test-infra-validate.sh, test-host-tools.sh, infra/RUNBOOK.md
    trigger: 인프라 정렬
    logic: 새 비밀 이름은 없다(R-M9 목록은 U8이 이미 반영). 이 작업은 배포를 다루지 않는다
    violation: 키 없는 demo에 이미지를 배포하면 realtime이 기동을 거부한다는 사실이 RUNBOOK에 없으면 결함
    source: FR7.2, 원 FR6.9
```

## 규칙 요약

| ID | 한 줄 | 분류 | 출처 |
|---|---|---|---|
| BR1.1 | 제브는 새 포트로만, 지금 운영은 FREE_TALK뿐 | policy | FR1.1 · FR5.2 |
| BR1.2 | 모든 결과는 예외 없이 완료, 실패도 자동 ALLOW 아님 | constraint | FR1.2 · NFR2 |
| BR1.3 | 모드 키 하나 mode = REAL·STUB·OFF | constraint | FR1.3 · FR7.1 |
| BR1.4 | STUB은 단계를 모두 지나고 '대역' 표시 | policy | FR1.3 · NFR9 |
| BR1.5 | OFF는 단계를 건너뜀, '비활성' 기동 로그 | policy | FR1.3 · FR7.1 |
| BR1.6 | 장치를 안 넣는 기존 경로 = OFF, 기존 시험 단언 불변 | constraint | NFR6 |
| BR1.7 | guardrail 패키지 경계 · 협력 객체 | constraint | NFR8 |
| BR2.1 | HTTPS 한 번, 800ms, 재시도 없음 | constraint | FR2.1 · NFR1 |
| BR2.2 | 허용 호스트 상수 + 기동·직전 확인 | validation | FR2.1 |
| BR2.3 | 고정 모델 버전, 이동 별칭 거부 | validation | FR2.1 |
| BR2.4 | 후보 + 익명 맥락 3턴, 식별자 · 키 없음 | validation | FR2.2 · NFR3 |
| BR2.5 | 지시문은 데이터 필드에만 | constraint | FR2.2 |
| BR2.6 | 응답 변환은 어댑터 한 곳, INVALID_JSON · POLICY_MISMATCH | validation | FR1.2 · FR2.1 |
| BR2.7 | policy-v1.json 재사용, 예시 판 이름 정정 | constraint | FR2.3 |
| BR2.8 | 키 값은 어디에도 쓰지 않음 | constraint | FR2.1 · NFR3 |
| BR3.1 | inspect 뒤 · recheck 앞, 새 발화만, 실행기는 대기 안 함 | constraint | FR3.1 |
| BR3.2 | 유효한 ALLOW일 때만 recheck → send | constraint | FR3.1 · NFR2 |
| BR3.3 | 막힘 일곱 가지는 같은 흐름 | constraint | FR3.2 |
| BR3.4 | 검사 중 THINKING 유지 | constraint | FR3.2 |
| BR4.1 | 검사표 · 같은 Sealed로 전사-PCM 구조 보장 | constraint | FR4.2 |
| BR4.2 | 유효 ALLOW 다섯 조건 | constraint | FR4.1 |
| BR4.3 | 무효는 재생 안 함 · 기록 · 재지시 없음 | constraint | FR4.1 · FR4.3 |
| BR4.4 | begin 이후 입력 묶음 열림 횟수가 늘면 무효, 꼬리 조각은 제외 | constraint | FR4.3 |
| BR4.5 | 이탈 시 요청 취소 · 늦은 결과 버림 | constraint | FR4.1 |
| BR5.1 | 동시 검사 1건 | constraint | FR3.3 |
| BR5.2 | 검사 중 새 조각은 새 구간으로 모음 | constraint | FR3.3 |
| BR5.3 | 대기열 1칸, 차면 폐기 | constraint | FR3.3 |
| BR5.4 | 유효 ALLOW 송신 뒤에만 대기 구간 검사, 그 밖은 세대와 무관하게 모두 폐기 | constraint | FR3.3 · FR4.1 |
| BR5.5 | AI 행 보관 손잡이 · closeAll이 닫음 | constraint | FR3.3 · FR6.4 |
| BR5.6 | 겹치는 턴의 지연 기록 순서 | policy | FR3.3 |
| BR6.1 | 재지시 턴당 1회 | policy | FR3.2 |
| BR6.2 | 같은 템플릿 또는 RETRY_FREE_TURN | policy | FR3.2 |
| BR6.3 | 두 번째 막힘 = 자유대화는 턴 포기 | policy | FR3.2 |
| BR6.4 | 재지시 응답도 검사 | constraint | FR3.2 · NFR2 |
| BR6.5 | 연습 "차단됨" 이벤트는 정의 + 대역 시험까지 | policy | FR3.2 · FR5.2 |
| BR6.6 | 재지시 불발 · 응답이 begin 앞에서 어떤 길로 폐기되어도(discard() 한 지점의 훅) 턴 포기, THINKING에 머물지 않음 | constraint | FR3.2 |
| BR7.1 | 승인 원문은 제브 생략, 분류 순서 | policy | FR5.1 |
| BR7.2 | UNKNOWN · NOT_MATCHED는 제브 검사 | constraint | FR5.1 |
| BR7.3 | 정규화는 ASCII 공백 제거뿐 | constraint | FR5.1 |
| BR7.4 | 레지스트리 연결, 대본 일치는 U12 이후 | constraint | FR5.1 |
| BR7.5 | 생략도 guardrail_event | policy | FR5.1 · FR6.3 |
| BR7.6 | 연습 중 원문 불일치는 기존 GATE_PATTERN | constraint | FR5.2 · NFR6 |
| BR8.1 | guardrail_event 칸 · 원문 없음 | constraint | FR6.1 · NFR3 · NFR4 |
| BR8.2 | 비동기 기록기 · SI-10 | constraint | FR6.1 |
| BR8.3 | 삭제 참여자 185 · Counts 불변 | constraint | FR6.2 |
| BR8.4 | 보호자 화면 승인은 ALLOW 뒤 | constraint | FR6.4 |
| BR8.5 | 계측 5항목 · 이름은 설계서 | policy | FR6.3 · NFR4 |
| BR8.6 | 세션 합계 로그 한 줄은 줄임(편차 U13-D2) | policy | FR6.3 |
| BR9.1 | 모드 × 프로필 × 키 기동 규칙 | validation | FR7.1 |
| BR9.2 | 비실검사 시작은 기동 로그에 흔적 | policy | FR1.3 · FR7.1 |
| BR9.3 | 인프라 검사 정렬 · RUNBOOK 순서 | constraint | FR7.2 |
