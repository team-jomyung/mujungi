# U8 계약 · 버전 리소스 — 엔티티

> 근거: `../../../inception/units-generation/unit-of-work.md`(U8 경계), `../../../inception/units-generation/unit-of-work-story-map.md`(U8은 스토리 없음, FR10 · FR11), `../../../inception/requirements-analysis/requirements.md`(FR6.10 · FR10.1 · FR10.2 · FR11.1 ~ FR11.4), `../../../inception/domain-design/components.md`(ApprovedContentCatalog → 이름 바꿈), `../../../inception/contract-design/contract-summary.md`(C-DS2 · C-DS9 · C-DS13 · C-DS14), 질문 · 답 `functional-design-questions.md`(Q1-A · Q2-A · 가정 1 ~ 9).
> 이 단위의 엔티티는 대부분 DB 표가 아니라 **계약 · 설정 · 자원 파일의 논리 모양**이다. 새 DB 표나 마이그레이션은 없다. 승인 문구는 기존 core 표에 행(시드)만 더한다.

## 엔티티 목록 (기계 판독용)

```yaml
entities:
  - name: ApprovedPhrase
    description: 승인 문구 한 판. 기존 core 표 core_approved_content + core_approved_content_version의 논리 모양. 새 연습 흐름 문구를 같은 표에 시드로 더한다(Q1-A)
    owner: core ApprovedContentCatalog(기존)
    attributes:
      - { name: contentCode, type: string, required: true, unique: true, constraints: "^[A-Z][A-Z0-9_]*$" }
      - { name: contentType, type: enum, required: true, allowed: [APPROVED_AUDIO, APPROVED_SPEECH], constraints: "APPROVED_SPEECH는 U8이 더하는 새 값(음성 파일 없음). 표에는 검사 제약이 없어 마이그레이션이 필요 없다" }
      - { name: version, type: integer, required: true, min: 1, constraints: "(content, version) 유일" }
      - { name: status, type: enum, required: true, allowed: [DRAFT, APPROVED, RETIRED] }
      - { name: text, type: string, required: true, min: 1, max: 200 }
      - { name: audioPath, type: string, required: false, constraints: "없으면 음성 파일 없는 승인 발화(GPT-Live 지시 + 완전 일치로 재생)" }
      - { name: source, type: string, required: true, constraints: "근거 문서 절(B §4 · §8.5 등). 시드 SQL 주석으로 남긴다" }
    relationships: []
  - name: ApprovedSpeechItem
    description: 세션 시작 패키지의 '음성 파일 없는 승인 발화' 한 항목. 기존 approvedContent(음성 파일 필수)와 따로 둔 새 선택 속성 approvedSpeech의 값
    owner: U8 계약(internal-session-start-request.schema.json)
    attributes:
      - { name: contentCode, type: string, required: true, constraints: "맵 키, ^[A-Z][A-Z0-9_]*$" }
      - { name: contentVersionId, type: uuid, required: true }
      - { name: text, type: string, required: true, min: 1, max: 200 }
    relationships:
      - { to: ApprovedPhrase, cardinality: "N:1", direction: "항목은 APPROVED 최고 판 하나를 가리킨다" }
  - name: JudgmentOptionSet
    description: 판정 지점 하나의 질문 · 선택지 판(J1 ~ J6). realtime 자원 파일
    owner: realtime LiveContentRegistry
    attributes:
      - { name: judgmentPoint, type: enum, required: true, allowed: [J1, J2, J3, J4, J5, J6] }
      - { name: setVersion, type: string, required: true, constraints: "자원 파일 판(예: judgment-v1)" }
      - { name: question, type: string, required: true }
      - { name: options, type: "list<string>", required: true, constraints: "A §7.3의 선택지 그대로, 판 안에서 순서 고정" }
      - { name: source, type: string, required: true, default: "A §7.3" }
    relationships: []
  - name: GuardrailPolicy
    description: 제브 시스템 프롬프트 · 분류 선택지 · 정책 판. realtime 자원 파일
    owner: realtime LiveContentRegistry
    attributes:
      - { name: policyVersion, type: string, required: true }
      - { name: systemPrompt, type: string, required: true, constraints: "C §3.3 원문" }
      - { name: categories, type: "list<string>", required: true, constraints: "C §3.1의 7개 분류" }
      - { name: modes, type: "list<enum>", required: true, allowed: [FREE_TALK, PRACTICE_PEER, PRACTICE_COACH] }
    relationships: []
  - name: SessionContentPlan
    description: 세션 시작 때 고정한 판 번호 묶음. 실행 계획(메모리)에 두고, 판정 · 가드레일 기록 행마다 판 번호를 함께 남긴다(가정 2)
    owner: realtime LiveContentRegistry
    attributes:
      - { name: sessionId, type: uuid, required: true, unique: true }
      - { name: judgmentSetVersion, type: string, required: true }
      - { name: guardrailPolicyVersion, type: string, required: true }
      - { name: approvedContentVersionIds, type: "map<code, uuid>", required: true, constraints: "시작 패키지 approvedContent + approvedSpeech에서 옴" }
      - { name: scriptVersion, type: string, required: false, constraints: "대본을 받으면 채움" }
    relationships:
      - { to: JudgmentOptionSet, cardinality: "N:1" }
      - { to: GuardrailPolicy, cardinality: "N:1" }
  - name: SessionScript
    description: 세션에서 쓰는 대본 문장 원문(Luna가 만든 장면 · 기회 문장 · 다시 설명 등)의 등록본. 원문 일치 확인에 쓴다
    owner: realtime LiveContentRegistry
    attributes:
      - { name: sessionId, type: uuid, required: true }
      - { name: scenarioRef, type: uuid, required: true }
      - { name: scriptVersion, type: string, required: true }
      - { name: sentences, type: "map<sentenceKey, string>", required: true }
      - { name: source, type: enum, required: true, allowed: [CORE_SCENARIO_API] }
    relationships:
      - { to: SessionContentPlan, cardinality: "N:1", direction: "한 세션에 대본 여러 개(목표마다)" }
  - name: TimingKey
    description: 새 시간 설정 키 하나. timing.md 새 절의 '키 | 기본값 | 소유 앱' 표 행 + application.yml + 설정 레코드에 같은 값으로 있다
    owner: U8 계약(timing.md)
    attributes:
      - { name: key, type: string, required: true, unique: true, constraints: "mujoong.timing.* 아래" }
      - { name: defaultValue, type: duration, required: true }
      - { name: ownerApp, type: enum, required: true, allowed: [core-api, realtime-activity] }
      - { name: readBy, type: string, required: true, constraints: "그 키를 읽을 단위(U11 등)" }
    relationships: []
  - name: SecretName
    description: 새 비밀 환경 변수 이름. 값은 저장소에 두지 않는다
    owner: U8 계약(R-M9 목록)
    attributes:
      - { name: name, type: enum, required: true, allowed: [OPENAI_API_KEY, JEV_API_KEY] }
      - { name: apps, type: "list<enum>", required: true, allowed: [core-api, realtime-activity] }
      - { name: required, type: boolean, required: true, default: false, constraints: "없으면 실제 어댑터를 끈다. demo · prod의 JEV_API_KEY만 예외(U13 기동 검사)" }
    relationships: []
  - name: AiIoSchema
    description: Luna · 제브 구조화 입출력 JSON Schema 파일 하나(contracts/schemas)
    owner: U8 계약
    attributes:
      - { name: fileName, type: string, required: true, unique: true, constraints: "ai-*.schema.json 또는 guardrail-*.schema.json" }
      - { name: id, type: string, required: true, constraints: "https://mujoong.invalid/contracts/schemas/<파일명>" }
      - { name: purpose, type: enum, required: true, allowed: [JUDGMENT_REQUEST, JUDGMENT_OUTPUT, SCENARIO_OUTPUT, SCENARIO_REVIEW, GOAL_CANDIDATE, REJUDGMENT, SEED, GUARDRAIL_REQUEST, GUARDRAIL_RESULT] }
      - { name: retryClass, type: enum, required: true, allowed: [NONE, REQUEST_BOUNDED, JOB_ATTEMPT], constraints: "Q2-A 목적별 재시도" }
    relationships: []
  - name: PracticeRecordV2
    description: 기록 캡처 이벤트 record.practice의 새 흐름 모양. flowVersion이 있으면 새 형식, 없으면 U5b 형식
    owner: U8 계약(practice-record-v2.schema.json)
    attributes:
      - { name: flowVersion, type: string, required: true, allowed: ["practice-conversation-v1"] }
      - { name: scenes, type: "list<object>", required: true, constraints: "실제로 시작한 장면만. 필드는 계약 요약 C-DS2" }
    relationships: []
  - name: DeviationRow
    description: contracts/deviations.md의 U8 절 한 행
    owner: U8 계약
    attributes:
      - { name: symbol, type: string, required: true, constraints: "U8-a, U8-b … (유닛 접두, 가정 7)" }
      - { name: change, type: string, required: true }
      - { name: basis, type: string, required: true }
    relationships: []
```

## 요약

- **승인 문구(ApprovedPhrase · ApprovedSpeechItem):** 정본은 지금처럼 core DB다. 새 연습 흐름 문구를 같은 표에 시드로 더한다. 음성 파일 없는 승인 발화는 시작 패키지의 새 선택 속성 `approvedSpeech`로 넘긴다. 기존 `approvedContent`는 음성 파일 필수 그대로라 기존 수신 쪽을 깨지 않는다.
- **판 리소스(JudgmentOptionSet · GuardrailPolicy):** realtime 자원 파일이다. 기동 때 읽고 실패하면 기동을 거부한다.
- **세션 고정(SessionContentPlan · SessionScript):** 메모리 실행 계획에 둔다. 증거는 판정 · 가드레일 기록 행의 판 번호다. 새 표는 없다.
- **계약 모양(TimingKey · SecretName · AiIoSchema · PracticeRecordV2 · DeviationRow):** `contracts/`와 설정 파일 안의 형식이다.

## Sources

- `functional-design-questions.md`(같은 폴더)
- `../../../inception/units-generation/unit-of-work.md` · `unit-of-work-story-map.md`
- `../../../inception/requirements-analysis/requirements.md`, `../../../inception/domain-design/components.md`, `../../../inception/contract-design/contract-summary.md`
- 코드 · 계약 확인: `core/activity/ApprovedContentCatalog.java`, `V1__core_base.sql`(approved content 표, `audio_path` NULL 허용), `contracts/schemas/internal-session-start-request.schema.json`(`approvedContent` 항목에 `audioUrl` 필수), `contracts/timing.md`, `ResultInputSnapshotRepository`(`SNAPSHOT_VERSION = 1`)

## Assumptions & Open Questions

- 새 승인 문구의 정확한 텍스트는 B §4 · §8.5와 화면 설계 문구표에서 그대로 옮긴다. 문구표에 없는 것(막힌 발화 대체 문구, 스토리 열린 항목 2)은 시드하지 않고 열린 항목으로 남긴다.
