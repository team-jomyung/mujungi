# 설계 결정 기록 (ADR) — Domain Design

> 근거: `components.md`, `domain-design-questions.md`(Q1-A · Q2-A · Q3-A), `../requirements-analysis/requirements.md`, `../user-stories/stories.md`, CodeKB `architecture.md` · `component-inventory.md`, `../practices-discovery/team-practices.md`.
> 상태는 모두 '제안'이다. 이 단계의 승인으로 '채택'이 된다.

## ADR-001: 새 연습 흐름을 새 컴포넌트 `PracticeConversation`으로 둔다

- **Context** — 지금 `realtime.practice`의 `PracticeProtocol`은 FIRST → (부족하면) HELP → CUE → NEW_SCENARIO 고정 흐름이다. 레거시 `EvaluationProvider` · `CueProvider`에 묶여 있고, 기본 빈이 미연결이라 그대로는 새 연습이 멈춘다(TD-1). 새 흐름(A §4.3: SCENE_INTRO ~ CLOSING, J1~J5)은 단계 · 판정 · 기록이 모두 다르다. 과거 U5b 기록은 원래 의미대로 계속 읽어야 한다(FR7.5, US4.3).
- **Decision** — 새 상태 기계와 판정 지점 결정을 맡는 `PracticeConversation`을 새로 만든다. 새 세션은 이것만 쓴다. `PracticeProtocol` · `EvaluationProvider` · `ResponseEvaluator` · `CueProvider`는 새 세션에서 쓰지 않는 레거시로 남기고, 과거 기록은 기록 버전으로 나눠 읽는다. `SessionRuntime`은 기존 연습 확장점으로 새 컴포넌트를 부른다(사람 답 Q1-A).
- **Consequences**
  - 좋은 점: 새 흐름이 레거시 포트에 기대지 않아 AC1.1.5(레거시 미연결이 새 흐름을 막지 않음)를 구조로 보장한다. 새 흐름을 끄면 레거시 경로가 그대로 남아 되돌리기 쉽다.
  - 나쁜 점: 한동안 두 연습 흐름 코드가 함께 있다. 레거시 정리 시점을 따로 정해야 한다.
  - 중립: 확장점의 입력 · 출력 능력(아래 ADR-005)이 두 흐름의 공통 경계가 된다.
- **Alternatives Rejected**
  - 기존 `PracticeProtocol`을 그 자리에서 새 상태 기계로 고치기 — 코드는 하나지만, 레거시 판정 · Cue 의존을 떼는 동안 과거 기록 호환과 새 흐름이 한 클래스에 섞이고 되돌리기 어렵다.

## ADR-002: 목표 후보와 담은 목표의 주인을 새 `PracticeGoal` 하나로 둔다

- **Context** — 새 방식(B §9.6, FR8)에서 목표 후보는 Luna가 아이 기록에서 만든다. 후보는 4요소 · `skill_ref` · 근거 발화 · 화면용 근거 문구를 가진 독립 데이터다. 담은 목표는 후보 버전을 가리키고, 상황 생성(AC4.6.2)과 진행 중 판정 기준(AC4.6.3)의 입력이 된다. 지금은 표 저장이 core.practice(`CandidateRegistry` · `PracticeGoalRepository`), 후보 고르기가 core.result(`CandidateScreen`)에 나뉘어 있고, 둘 다 승인 행동 정의 FK에 묶여 있다.
- **Decision** — core-api에 `PracticeGoal`을 새로 두고 `GoalCandidate` · `SavedGoal`을 모두 소유하게 한다. 후보 생성(Luna 호출 · 검증 · 저장)도 이 컴포넌트가 한다. `ResultPipeline`은 결과 작업 중에 생성을 요청만 하고, `ScenarioGeneration`은 담은 목표를 읽기만 한다. 과거 행동 정의 기반 목표는 계속 읽힌다(사람 답 Q2-A).
- **Consequences**
  - 좋은 점: 목표 데이터의 주인이 하나라 화면 · 상황 생성 · 판정 기준이 한곳을 본다. 후보 버전 고정(AC4.6.1)이 한 트랜잭션 경계 안에 있다.
  - 나쁜 점: core.practice · core.result에서 코드를 옮겨야 한다. 이미 쓰인 표의 FK 구조를 바꾸는 마이그레이션(core V8~)이 필요하고, 제약을 강화하면 `infra/migration-approvals.txt` 근거가 필요하다(R-M3).
  - 중립: 기존 `CandidateEvidencePort`(근거 확인 포트)를 새 컴포넌트가 이어받을지는 Functional Design에서 정한다.
- **Alternatives Rejected**
  - 지금처럼 후보는 결과 쪽, 담은 목표는 연습 쪽에 두고 스키마만 바꾸기 — 옮길 코드는 적다. 하지만 후보 버전 참조가 모듈 경계를 넘고, 판정 기준을 만들 때 두 곳을 봐야 한다.

## ADR-003: 27 화면에서는 '되묻기 뒤 전달'을 '바로 전달'로 접어 보인다

- **Context** — 새 결과 시점 `AFTER_REASK`(FR7.1)가 생긴다. 한편 이번 작업은 27 표시를 바꾸지 않기로 했다(User Stories Q5-A, AC4.1.2). 27은 서버 `SceneChipMapper`가 만든 칩 5종(바로 전달 · 도움 뒤 전달 · 다음 기회로 · 보류 · 미실시)만 그린다. 시안 검토 지적 R-01은 이 값이 들어간 기록을 27에서 열 때 보일 것을 하나로 정하라고 했다.
- **Decision** — 서버의 27 표시용 칩 매핑에서만 `AFTER_REASK`를 '바로 전달'(`DONE_IMMEDIATE`)로 접는다. 저장 값 · API 결과 값 · 리포트 집계에는 `AFTER_REASK`를 그대로 둔다(사람 답 Q3-A). 추천안(C, 칩 생략) 대신 사람이 고른 안이다.
- **Consequences**
  - 좋은 점: 27의 모양과 칩 종류가 그대로라 Q5-A를 새 기록에도 지킨다. 프런트 변경이 없다.
  - 나쁜 점: 되묻기를 거친 장면이 27에서 '바로'로 보여 사실과 조금 다르다. 리포트(시점 구분 있음)와 27(구분 없음)이 같은 장면을 다르게 말할 수 있다.
  - 중립: 리포트 · PDF가 같은 매퍼를 쓴다면 27 전용 매핑을 따로 두어야 한다(Functional Design에서 확인). 화면 설계 개정 제안 RP-1이 정리되면 다시 본다.
- **Alternatives Rejected**
  - '도움 뒤 전달'로 접기 — 되묻기는 도움이 아닌데 '도움 뒤'로 보인다.
  - `AFTER_REASK` 장면의 칩 생략(추천안) — 사실과 다른 글은 없다. 하지만 새 기록에 칩이 빠진 장면이 생겨 '27 그대로'를 조금 바꾼다. 사람이 고르지 않았다.
  - 새 칩 '되묻기 뒤 전달' 추가 — Q5-A를 바꾸고 화면 설계 개정이 먼저 필요하다.

## ADR-004: Luna 호출부를 앱마다 따로 둔다

- **Context** — Luna는 core-api(상황 생성 · 자체 검토 · 결과 · 후보 · 씨앗 · 재판정)와 realtime-activity(진행 중 판정) 양쪽에서 부른다(FR1.1). 두 앱은 서로의 코드를 직접 참조할 수 없다(R-F5). 함께 쓰는 `contract-model`은 Spring · 외부 SDK가 없는 계약 타입 모듈이다.
- **Decision** — `CoreLunaClient`와 `RealtimeLunaClient`를 각 앱에 둔다. 공통 규칙(구조화 출력 · 필드 이름 감사 · 이름 치환 · 키를 환경에서 읽기 · 키가 없으면 끄기)은 같은 계약 문서(`contracts/ai-io.md`)로 맞추고, 코드는 공유하지 않는다. 재시도 정책은 호출 목적마다 다르다: 판정은 재시도 없음, 생성 · 결과는 스키마 1회 재요청.
- **Consequences**
  - 좋은 점: 두 앱 분리와 contract-model의 의존 최소 원칙이 그대로다. 앱마다 제한 시간 · 재시도를 다르게 쓸 수 있다.
  - 나쁜 점: 감사 · 치환 같은 작은 코드가 두 번 생긴다. 계약 문서와 시험으로 어긋남을 막아야 한다.
  - 중립: 공급자 응답 형식이 바뀌면 두 곳을 함께 고친다.
- **Alternatives Rejected**
  - contract-model에 공용 Luna 호출부 두기 — 중복은 없어지지만, 계약 타입 모듈에 openai-java와 비밀 처리가 들어가 두 앱 모두에 외부 SDK 의존이 생긴다.
  - 한 앱(core)에서만 Luna를 부르고 realtime은 core에 판정을 위임하기 — 2초 판정 예산에 앱 간 HTTP 왕복이 더해지고, 판정 턴이 core 장애에 묶인다.

## ADR-005: 연습 흐름은 '다음 행동 지시'만 돌려주고 실행은 세션 실행부가 한다

- **Context** — 새 연습 흐름은 재생 · GPT-Live 지시 · 타이머 · 장면 정리가 필요하다. 이 능력은 모두 `SessionRuntime`(SessionExecutor)이 가진다. 연습 흐름이 실행부를 직접 부르면 두 컴포넌트가 서로 의존하는 순환이 생긴다. 또 project.md 교정은 확장점의 출력 능력과 금지 능력을 정해 붙는 쪽이 실행기를 고치지 않게 하라고 한다.
- **Decision** — `SessionRuntime` → `PracticeConversation` 방향만 둔다. 실행부는 기존 연습 확장점으로 아이 말 · 재생 완료 · 타이머 · 판정 결과 신호를 넘긴다. 연습 흐름은 정해진 종류의 다음 행동 지시(승인 문구 재생 · GPT-Live 지시 · 타이머 설정 · 장면 정리 · 종료 요청)만 돌려준다. 지시에는 판정 라벨 · 확률 · 기준을 넣지 않는다(FR3.7). 구체 지시 목록과 금지 능력은 Functional Design에서 확정한다.
- **Consequences**
  - 좋은 점: 순환이 없고, 연습 흐름을 실행부 없이 단위 시험할 수 있다(가짜 시계 · 가짜 판정). `SessionExecutor`(TD-4)에 연습 규칙이 더 쌓이지 않는다.
  - 나쁜 점: 지시 종류를 늘릴 때마다 실행부도 고쳐야 한다.
  - 중립: 자유대화 J1 · J6은 상태 기계가 없으므로 실행부가 `LiveJudgment`를 직접 부른다.
- **Alternatives Rejected**
  - 연습 흐름이 실행부의 출력 · 지시 API를 직접 부르기 — 쓰기 쉽지만 순환 의존이 생기고, 연습 시험에 실행부 전체가 필요하다.

## ADR-006: 제브 검사는 출력 게이트 안 한 단계로, 판정 · 기존 게이트와 섞지 않는다

- **Context** — C는 제브를 GPT-Live 새 발화 전용으로 정했다. 요청 출처는 하나, 모드는 셋이다. `EvaluationProvider` · `GateDecision`과 섞지 말고, `OutputGate.inspect` 뒤 · `recheck` 전에 검사하라고 정했다(C §3.1 · §3.4). 실패는 막힘이어야 하고, 늦은 ALLOW와 전사-PCM 불일치도 막아야 한다(FR6.4 · FR6.5).
- **Decision** — 새 `JevGuardrail` 포트 · 어댑터 · 대역을 두고, `OutputGate`가 새 발화일 때만 이 단계를 부른다. 승인 원문과 공백 제거 뒤 완전 일치하는 재생은 `ApprovedContentCatalog`로 출처 · 원문을 확인한 뒤 생략한다. demo · prod 프로필에서 실검사가 꺼져 있거나 키가 없으면 realtime-activity 기동을 실패시킨다(US2.3).
- **Consequences**
  - 좋은 점: '보낼지 말지' 결정이 출력 게이트 한곳에 모여 판정 턴 보류 · 제브 · 늦은 결과 무효화가 같은 출력 세대 규칙을 쓴다.
  - 나쁜 점: 새 발화마다 최대 800ms가 더해진다(NFR1 측정 항목). 막힘이 잦으면 대화가 끊긴다(재지시 1회 뒤 장면 정리, Q6).
  - 중립: 공급자 실제 응답 필드는 실호출로 확정한다(요구사항 열린 항목 3). 변환은 어댑터 안에만 둔다.
- **Alternatives Rejected**
  - 제브를 판정 포트(`EvaluationProvider`)의 한 종류로 두기 — C가 금지했고, 판정(진행 결정)과 안전 검사(송신 결정)의 실패 의미가 다르다.
  - 게이트 밖(세션 실행부)에서 제브를 부르기 — 출력 세대 · 구간 일치 확인이 게이트와 갈라져 늦은 ALLOW를 놓칠 수 있다.

## ADR-007: GPT-Live 음성은 서버가 중계한다 — 조원 하네스의 브라우저 직접 연결은 참고만 한다

- **Context** — 단계 중간에 조원이 만든 하네스 패키지(`knowledge/documents/MUJUNG_SERVICE_HANDOFF_SPRING_2026-10-15.zip`, reference 0.1, 실제 API 호출 0회)가 들어왔다. 이 패키지의 Spring 예제는 실제 GPT-Live 주소 `POST /v1/live/sessions`로 세션을 만들고 `transport: webrtc`로 브라우저가 OpenAI와 직접 음성을 주고받는다. 서버는 sideband(`/attach`)로 제어만 한다. 패키지 문서 스스로 이 방식은 이미 재생된 말을 막을 수 없다고 적는다. 우리 요구사항은 판정 턴 출력 보류(FR5)와 제브 ALLOW 뒤 송신(FR6.3 · FR6.5)을 요구한다. 지금 서버의 선 형식은 실제 API로 확인한 적이 없다(요구사항 열린 항목 5).
- **Decision** — 기기 ↔ realtime-activity ↔ GPT-Live의 서버 중계 구조를 유지한다. 조원 패키지의 세션 생성 · 제어 채널 · 허용 이벤트 · 종료 형식은 `VoiceRelay` 어댑터 매핑의 참고 자료로만 쓰고, 서버 쪽 전송 방식은 FR12.2 실호출로 확정한다. 가드레일 정책과 오프라인 시험 사례는 `JevGuardrail` 규칙 · 시험 계획의 입력으로 쓴다(사람 답 Q4-A).
- **Consequences**
  - 좋은 점: 판정 턴 보류 · 제브 차단 · 늦은 ALLOW 무효화 · 전사-PCM 일치 확인이 구조로 가능하다. 기기 화면과 출력 게이트 설계가 그대로다.
  - 나쁜 점: GPT-Live가 서버 쪽 음성 전송(WebSocket 등)을 지원하지 않으면 큰 재설계가 필요하다. 이 위험은 FR12.2 실호출 전까지 열려 있다. 서버 중계라 브라우저 직접 연결보다 지연이 늘 수 있다(NFR1 측정).
  - 중립: 조원 코드(Java 17 · Spring Boot 3.5 · Maven)는 우리 스택(Java 21 · Spring Boot 4.1 · Gradle)과 달라 그대로 들여오지 않고 형식 · 계약만 옮긴다.
- **Alternatives Rejected**
  - 조원 방식(브라우저 WebRTC 직접 연결 + 서버 sideband)으로 전환 — 조원 코드를 재사용할 수 있지만, 재생 전 보류와 제브 차단이 불가능해 FR5 · FR6 · US1.10 · US2.1을 다시 써야 한다.

## Sources

- `components.md`(같은 폴더), `domain-design-questions.md`
- 조원 하네스 패키지 `aidlc/spaces/default/knowledge/documents/MUJUNG_SERVICE_HANDOFF_SPRING_2026-10-15.zip`(`docs/INTEGRATION_FLOW.txt` · `GUARDRAILS.txt`, `spring/…/OpenAiLiveGateway.java`)
- `../requirements-analysis/requirements.md`, `../user-stories/stories.md`, `../refined-mockups/mockups.md`
- `aidlc/spaces/default/codekb/mujungi/architecture.md`, `aidlc/spaces/default/codekb/mujungi/component-inventory.md`
- `../practices-discovery/team-practices.md`, `aidlc/spaces/default/memory/project.md`(R-F5 · R-F1 · R-M3 · 확장점 교정)

## Assumptions & Open Questions

- ADR-003의 리포트 · PDF 매퍼 공유 여부는 Functional Design에서 확인한다.
- ADR-005의 다음 행동 지시 목록 · 금지 능력은 Functional Design에서 확정한다.
- ADR-006의 제브 실제 응답 형식은 FR12.1 실호출로 확정한다.
