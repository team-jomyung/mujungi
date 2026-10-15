# 작업 단위 (Unit of Work)

> 근거: `../domain-design/components.md`(컴포넌트 13개) · `../domain-design/decisions.md`(ADR-001~007), `../requirements-analysis/requirements.md`(FR1~FR12 · NFR1~NFR7), `../user-stories/stories.md`(US1.1~US5.2), 질문 · 답 `units-generation-questions.md`(Q1-A · Q2-B · 가정 1~7 수락).
> 단위 이름은 기존 팀 유닛 U1~U7 뒤를 이어 **U8~U15**로 매긴다(Q2-B). 저장소에 남는 이름(브랜치 `unit/u8-…` · 시험 태그 `@Tag("u8")` · 범위 파일 `backend/coverage-scope/u8.txt` · `frontend/coverage-scope/u8.json`)도 같은 번호를 쓴다.
> 이 문서는 단위의 경계 · 책임만 정한다. 무엇을 먼저 만들지는 Delivery Planning이 정한다.

## 단위 목록

| Unit ID | Directory | 이름 | 종류 | 크기 | 배포 모델 |
|---|---|---|---|---|---|
| U8 | u8-contracts-catalog | 계약 · 버전 리소스 | spec | M | 기존 `contracts/` + realtime-activity 안에 포함 |
| U9 | u9-luna-clients | Luna 호출부 | library | M | core-api · realtime-activity 안에 각자 포함 |
| U10 | u10-scenario-generation | 상황 생성 | service | L | core-api 안에 포함 |
| U11 | u11-judgment-turn | 판정 턴 기반 | service | XL | realtime-activity 안에 포함 |
| U12 | u12-practice-conversation | 새 연습 흐름 | service | XL | realtime-activity 안에 포함 |
| U13 | u13-jev-guardrail | 제브 게이트 | service | L | realtime-activity 안에 포함 |
| U14 | u14-goal-result | 목표 · 결과 | service | XL | core-api 안에 포함 |
| U15 | u15-knowledge-graph | 지식 그래프 | service | L | core-api 안에 포함(Neo4j는 외부 의존) |

- **새 배포 단위는 없다**(가정 1). 모든 단위는 기존 두 앱과 `contracts/` 안의 코드 조각이다. 한 단위가 두 앱을 함께 고칠 때도 앱 사이는 내부 HTTP · 이벤트 계약으로만 잇는다(R-F5).
- **종류**는 Construction에서 어떤 설계 문서가 필요한지를 정한다(가정 2). `spec`은 계약 · 스키마, `library`는 단독 실행이 없는 재사용 코드, `service`는 실행되는 앱 기능이다.
- **크기**는 상대 추정이다. S < M < L < XL. 스토리 수, 바뀌는 기존 파일의 크기(`SessionExecutor` 3,388줄 등), 마이그레이션 유무로 매겼다.

## 단위별 정의

### U8 계약 · 버전 리소스 (`u8-contracts-catalog`, spec)

- **경계:** 여러 단위가 함께 기대는 계약과 버전 리소스.
- **담는 컴포넌트:** `ApprovedContentCatalog`(realtime) + `contracts/` 문서.
- **책임**
  - `contracts/ai-io.md`: Luna 판정 · 자체 검토 · 제브의 입출력 계약과 금지 필드(FR11.2).
  - 판정 시간 값: 두 앱 `application.yml`의 `mujoong.timing.*` · `contracts/timing.md` · `frontend/src/shared/timing/timing-defaults.json`을 같은 커밋으로 고친다(FR11.1, R-M5).
  - 새 오류 코드: `ErrorCode` enum + `contracts/errors.md`(R-M6).
  - 새 비밀 이름(`OPENAI_API_KEY` · `JEV_API_KEY` 등): 비밀 이름 검사 목록 전부(FR11.3, R-M9).
  - 기록 캡처 이벤트(`SessionRecordCaptured`)에 새 연습 기록 사실 · 판정 요약 필드를 더하는 계약(FR7.3). U12가 내보내고 U14가 받는다.
  - 판정 지점 J1~J6 질문 · 선택지, 제브 시스템 프롬프트 · 분류, 승인 문구 버전 리소스와 세션 고정(FR6.10 · FR10.1 · FR10.2).
- **스토리:** 없음. 운영 · 개발 요구(FR10 · FR11)를 담는다(stories.md '스토리 밖 추적').
- **구현 메모**
  - 계약 대조 시험(`ContractFilesTest` · `ErrorCodeTest` · `ConfigKeyContractTest` · `TimeLiteralScanTest` · `timing.test.ts`)이 통과해야 한다. 대조 시험을 고쳐서 통과시키지 않는다(R-F2).
  - `contracts/` 변경은 CI 전 작업을 돌린다(team.md).
  - 그 계약을 처음 쓰는 단위에만 걸리는 계약은 그 단위 PR에 넣는다(가정 5).

### U9 Luna 호출부 (`u9-luna-clients`, library)

- **경계:** GPT-6-Luna 호출의 공통 규칙. 어떤 업무 규칙도 담지 않는다.
- **담는 컴포넌트:** `CoreLunaClient`(core), `RealtimeLunaClient`(realtime). 코드는 공유하지 않는다(ADR-004).
- **책임**
  - openai-java Responses API와 구조화 출력, 스키마 검증, `model` · `prompt_version` 반환(FR1.1 · FR1.2).
  - 필드 이름 감사 로그, 아이 이름 '아이' 치환, 요청 · 응답 본문 로그 금지(FR1.4, R-F14).
  - 키가 있을 때만 실제 어댑터가 켜진다. 꺼지면 기존 실패 경로를 유지한다(FR1.3, AC2.3.3).
  - 기존 `TextAiProvider` · `ResultTextProvider` 기본 실패 빈을 설정으로 켜고 끄는 어댑터로 바꾼다.
  - 가짜 공급자(시험 · CI · `verify.sh` 전용, R-F1).
- **스토리:** US2.4(Luna 경로는 제브를 부르지 않음). US2.4는 U10 · U14 · U15에 함께 걸친다.
- **구현 메모:** 호출 목적별 재시도 정책을 받는다(판정은 재시도 없음, 생성 · 결과는 스키마 1회 재요청). 조원 하네스의 Luna 분석 설정(자동 재시도 없음)과 FR1.2의 차이는 Functional Design에서 정한다.

### U10 상황 생성 (`u10-scenario-generation`, service, core-api)

- **경계:** 연습 상황 하나를 만들어 내보내기까지.
- **담는 컴포넌트:** `ScenarioGeneration`.
- **책임**
  - Luna 생성 → 분리된 Luna 자체 검토 → 서버 독립 검사 → 재생성, 15초 예산(FR2.1~FR2.5).
  - 새 대본 필드 저장(core 마이그레이션)과 realtime이 쓰는 내부 HTTP 대본 응답(`PracticePorts.Scene` 계약 변경, R-M10).
  - 담은 목표의 4요소 · `skill_ref` 구조 맥락 입력, 변형 장면 보존 판정(AC4.6.2, FR9.5 · FR9.6).
- **스토리:** US3.1.
- **구현 메모**
  - Luna 생성물은 제브로 보내지 않는다(FR2.6).
  - 그래프가 꺼져 있거나 조회가 2초를 넘으면 기본 경로로 만든다.

### U11 판정 턴 기반 (`u11-judgment-turn`, service, realtime-activity)

- **경계:** 판정 턴의 수명과 그동안의 출력 보류. 연습 · 자유대화가 함께 쓴다.
- **담는 컴포넌트:** `LiveJudgment`, `SessionRuntime`(판정 턴 · '생각 중' 유지 · 10초 응답 준비 · 종료 표현), `OutputGate`(판정 턴 보류 · 버린 출력 전사 분리), `VoiceRelay`(다음 행동 지시 주입).
- **책임**
  - `JudgeClient` 포트 · 보류 규칙 · `judgment_live` 기록(FR3, realtime 마이그레이션).
  - 판정 턴 출력 보류 · 지시 뒤 응답만 통과 · 장애 취소(FR5).
  - 등록 종료 표현은 판정 없이 즉시 처리하고, 판정 턴 중 종료 표현이 나오면 판정을 멈춘다(FR3.5).
  - 자유대화 J1 · J6(FR4.8).
  - 확장점(`PracticeSessionExtension`) 쪽 신호 전달.
- **스토리:** US1.7 · US1.8 · US1.10 · US2.5.
- **구현 메모**
  - `SessionExecutor` · `OutputGate`를 크게 고친다. U12 · U13도 같은 파일을 고친다.
  - Domain Design 지적 R-03(대본 등록 경로) · R-04(버린 전사 전달 경로) · R-05(확장점 계약 모델)는 이 단위의 Functional Design에서 정한다(가정 7).
  - GPT-Live 선 형식은 FR12.2 실호출 전까지 바꾸지 않는다(ADR-007).

### U12 새 연습 흐름 (`u12-practice-conversation`, service, realtime-activity)

- **경계:** 연습 상태 기계 하나. 실행부를 부르지 않고 확장점 출력으로만 움직인다(ADR-005).
- **담는 컴포넌트:** `PracticeConversation`.
- **책임**
  - SCENE_INTRO ~ CLOSING 단계와 J1~J5 지점(FR4.1~FR4.5).
  - 친구 · 코치 역할별 출력 정책.
  - 도움 · 역할 전환 · 변형 · 생략 사유 기록(FR4.7, realtime 마이그레이션).
  - 레거시 `PracticeProtocol` · `EvaluationProvider` · `CueProvider`는 새 흐름에서 떼고, 과거 기록 읽기용으로 남긴다(FR4.6, ADR-001).
  - U8 계약대로 기록 캡처 이벤트에 새 기록 사실을 싣는다.
- **스토리:** US1.1 · US1.2 · US1.3 · US1.4 · US1.5 · US1.6 · US1.9.
- **구현 메모**
  - US1.2(친구 역할 되묻기) · US1.5(코치 발화)는 U13이 있어야 소리가 들린다. 없으면 지금 연습 모드처럼 대본과 다른 출력이 버려진다(stories.md 의존 메모).

### U13 제브 게이트 (`u13-jev-guardrail`, service, realtime-activity)

- **경계:** GPT-Live 새 발화의 안전 검사와 송신 결정.
- **담는 컴포넌트:** `JevGuardrail`(포트 · 어댑터 · 대역 · 프로필 기동 검사 · `guardrail_event`), `OutputGate`의 제브 단계(새 발화 / 승인 원문 분류, 늦은 ALLOW 무효, 전사-PCM 일치).
- **책임:** FR6.1~FR6.10. demo · prod에서 실검사가 꺼져 있거나 키가 없으면 기동 실패(US2.3).
- **스토리:** US2.1 · US2.2 · US2.3.
- **구현 메모**
  - 포트 · 어댑터 · 대역은 게이트와 따로 만들 수 있다. 게이트에 붙이는 부분은 U11의 보류 변경 위에 얹는다.
  - 조원 하네스의 `guardrails/service_policy.json`과 오프라인 시험 사례 24개를 규칙 · 시험의 참고 입력으로 쓴다(ADR-007).
  - 대역 성공을 실검사 성공으로 보고하지 않는다(R-M11).

### U14 목표 · 결과 (`u14-goal-result`, service, core-api)

- **경계:** 결과 작업과 연습 목표(후보 · 담은 목표).
- **담는 컴포넌트:** `PracticeGoal`, `ResultPipeline`.
- **책임**
  - Luna 목표 후보 생성 · 검증 · 저장, 담기(후보 버전 고정), 과거 행동 정의 목표 읽기(FR8, ADR-002, core 마이그레이션).
  - `AFTER_REASK` 시점, 27 표시 접기(ADR-003), 장면 수 고정 해제, 새 기록 사실 입력, 재판정 · `differs_from_live`, 버전별 결과 읽기, 재시도 3회(5 · 15 · 30초)(FR7).
- **스토리:** US4.1 · US4.2 · US4.3 · US4.4 · US4.5 · US4.6.
- **구현 메모**
  - 지금 core.practice(`CandidateRegistry` · `PracticeGoalRepository`)와 core.result(`CandidateScreen`)에서 코드를 옮긴다.
  - 결과 재시도 제약(`attempt_count`)과 `scene_no IN (1,2)` 제약을 바꾸는 마이그레이션은 `infra/migration-approvals.txt` 근거가 필요할 수 있다(R-M3).
  - Domain Design 지적 R-01(27 접기 위치) · R-02(삭제 참여) · R-06 · R-07은 이 단위의 Functional Design에서 정한다(R-02는 모든 단위에 걸친다).
  - U12가 내보내는 기록 사실은 U8의 이벤트 계약으로만 받는다.

### U15 지식 그래프 (`u15-knowledge-graph`, service, core-api)

- **경계:** 화용 지식 그래프의 조회 · 적재 · 씨앗 · 삭제.
- **담는 컴포넌트:** `KnowledgeGraph`.
- **책임**
  - Neo4j 어댑터와 메모리 대역(FR9.1 · FR9.7).
  - 공통 씨앗 적재 · 그래프 버전(FR9.2, 원천 `knowledge/documents/social-comm-poc.zip`).
  - 새 씨앗 생성 · 검사(FR8.3), 원문 비적재(FR9.3), 삭제 참여(FR9.4), 장애 시 기본 경로(FR9.5).
- **스토리:** US3.2 · US5.1 · US5.2.
- **구현 메모**
  - 압축 파일의 재배포 불가 자료 · 커밋 여부(요구사항 열린 항목 1)를 Construction 전에 정해야 한다.
  - 대형 그래프 원본(`graph_out`)은 없다(열린 항목 2).

## 단위 밖에서 다루는 것

- **실호출 · 실기기 검증(FR12):** Build and Test에서 다룬다(가정 4).
- **보호자 웹:** 새 단위가 없다. 27 접기는 U14의 서버 매핑에서 끝난다(Domain Design).
- **스켈레톤:** `classic` 범위라 스켈레톤 의식이 없다. 끝-끝 회귀 기준은 `bash scripts/verify.sh`다(가정 6).

## Sources

- `../domain-design/components.md`, `../domain-design/decisions.md`
- `../requirements-analysis/requirements.md`, `../user-stories/stories.md`
- `units-generation-questions.md`(Q1-A · Q2-B · 가정 1~7)
- 팀 규칙 `aidlc/spaces/default/memory/team.md`(브랜치 · 마이그레이션 순서 · 범위 파일), `project.md`(R-F5 · R-M2 · R-M3 · R-M5 · R-M6 · R-M9 · R-M10 · R-M11)
- 코드 확인: `backend/coverage-scope/` · `frontend/coverage-scope/`(기존 u1~u7 이름)

## Assumptions & Open Questions

- 크기(S/M/L/XL)는 상대 추정이다. 실제 작업 시간 추정은 Delivery Planning에서 한다.
- 단위별 마이그레이션 번호(core V8~ · realtime V5~)와 병합 순서는 Delivery Planning에서 정한다(가정 3).
- U8이 `spec`인데 `ApprovedContentCatalog`(realtime 코드)를 함께 담는다. 리소스 적재 · 세션 고정 코드가 작다고 보고 계약 단위에 묶었다(가정 2 수락). Construction 설계 문서는 spec 기준으로 받는다.
