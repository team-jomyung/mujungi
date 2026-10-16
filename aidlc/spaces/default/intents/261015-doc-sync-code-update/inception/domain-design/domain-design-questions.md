# Domain Design 질문지

> 근거: `../requirements-analysis/requirements.md`, `../user-stories/stories.md`, `../refined-mockups/mockups.md`, CodeKB `component-inventory.md` · `architecture.md`.
> 정본 문서(A · B · C)와 코드 사실로 바로 정해지는 경계는 묻지 않는다. 예: 제브 가드레일은 realtime의 출력 게이트 옆에만 둔다(C), 진행 중 판정 포트는 realtime에 둔다(A §5.8), Luna 공통 호출부는 core와 realtime이 각자 둔다(두 앱 직접 참조 금지 R-F5). 아래는 분해 방법이 둘 이상이라 사람이 골라야 하는 것만이다.

## Q1. 새 연습 흐름을 어디에 둘까 (컴포넌트 경계)

지금 `realtime.practice`의 `PracticeProtocol`은 FIRST → (부족하면) HELP → CUE → NEW_SCENARIO 고정 흐름이고, 레거시 `EvaluationProvider` · `CueProvider`에 묶여 있다. 새 흐름(SCENE_INTRO ~ CLOSING, J1~J6 판정)은 단계 · 판정 · 기록이 모두 다르다. 과거 기록은 계속 읽어야 한다(US4.3).

A. 새 컴포넌트 `PracticeConversation`(새 상태 기계 + 판정 턴)을 만들고, 기존 `PracticeProtocol` · `EvaluationProvider` · `CueProvider`는 새 세션에서 쓰지 않는 레거시로 남긴다. 과거 기록 읽기는 기록 버전으로 나눈다 — 장점: 새 흐름이 레거시 의존 없이 깨끗하고, 되돌리기 쉽다 / 단점: 한동안 두 흐름 코드가 함께 있다 (추천)
B. 기존 `PracticeProtocol`을 그 자리에서 새 상태 기계로 고쳐 쓴다 — 장점: 코드가 하나 / 단점: 레거시 판정 · Cue 의존을 떼는 동안 과거 기록 호환과 새 흐름이 한 클래스에 섞이고, 되돌리기 어렵다
X. Other (please specify)

[Answer]: A

## Q2. Luna가 만든 목표 후보와 담은 목표의 주인 (엔티티 소유)

지금 목표 후보는 `core.result`(U6)가 만들고 `core_goal_recommendation`에 승인 행동 정의 FK로 저장한다. 담은 목표는 `core.practice`의 `core_practice_goal_selection`이다. 새 방식(B §9.6)에서는 후보가 4요소 · `skill_ref` · 근거를 가진 독립 데이터가 되고, 담은 목표가 후보 버전을 가리키며, 연습 상황 생성 · 판정 기준의 입력이 된다(US4.5 · US4.6).

A. 새 컴포넌트 `PracticeGoal`(core)을 두고 `GoalCandidate` · `SavedGoal`을 모두 소유하게 한다. `core.result`는 결과 작업 중에 후보 생성을 요청만 하고, `core.practice`는 담은 목표를 읽기만 한다 — 장점: 목표 데이터 주인이 하나, 화면 · 연습 · 판정이 한곳을 본다 / 단점: 기존 두 모듈에서 코드를 옮겨야 한다 (추천)
B. 후보는 `core.result`, 담은 목표는 `core.practice`가 지금처럼 나눠 소유하고 스키마만 바꾼다 — 장점: 옮기는 코드가 적다 / 단점: 후보 버전 참조가 모듈 경계를 넘고, 판정 기준을 만들 때 두 곳을 봐야 한다
X. Other (please specify)

[Answer]: A

## Q3. 27 화면 — '되묻기 뒤' 장면이 있는 기록을 열 때 (스토리 · 시안 검토 지적 R-01)

이번 작업에서 27 표시는 바꾸지 않기로 했다(User Stories Q5-A). 그런데 지금 27은 API의 칩 값을 그대로 그리고, 매퍼와 DB 제약은 5종만 받는다(바로 전달 · 도움 뒤 전달 · 다음 기회로 · 보류 · 미실시). 새 '되묻기 뒤 전달'(`AFTER_REASK`) 값이 들어간 기록을 27에서 열 때 보일 것을 하나로 정해야 한다. 리포트 집계 · API에는 `AFTER_REASK`를 그대로 둔다.

A. 27 표시용으로만 '바로 전달'로 접는다 — 장점: 27 모양이 그대로 / 단점: 되묻기를 거친 장면이 보호자 화면에서 '바로'로 보여 사실과 조금 다르다
B. 27 표시용으로만 '도움 뒤 전달'로 접는다 — 장점: 27 모양이 그대로 / 단점: 되묻기는 도움이 아닌데 '도움 뒤'로 보인다
C. `AFTER_REASK` 장면은 27에서 칩을 그리지 않는다(다른 장면의 칩은 그대로) — 장점: 사실과 다른 글을 보이지 않는다 / 단점: 새 기록에서는 칩이 빠진 장면이 생긴다(Q5-A의 '27 그대로'를 새 기록에 한해 조금 바꿈) (추천)
D. Q5-A를 바꿔 27에 새 칩 '되묻기 뒤 전달'을 그린다(화면 설계 RP-1 선택 2)
X. Other (please specify)

[Answer]: A

## Q4. 조원 하네스의 GPT-Live 연결 방식을 받아들일까 (추가 자료 `knowledge/documents/MUJUNG_SERVICE_HANDOFF_SPRING_2026-10-15.zip`)

조원 패키지(reference 0.1, 실제 API 호출 0회)의 Spring 예제는 `POST https://api.openai.com/v1/live/sessions`로 세션을 만들고 `transport: webrtc`로 **브라우저가 OpenAI와 직접 음성을 주고받는다**. 서버는 sideband(`wss …/v1/live/sessions/{id}/attach`)로 제어만 한다. 패키지 문서(`docs/INTEGRATION_FLOW.txt` · `GUARDRAILS.txt`) 스스로 "이 방식은 이미 재생된 말을 막을 수 없으므로 Jev 강제 차단이 된 서비스라고 표시하면 안 된다"고 적는다. 지금 코드와 요구사항(FR5 판정 턴 출력 보류, FR6.3 제브 ALLOW 뒤에만 송신)은 **서버가 음성을 받아 게이트를 통과한 것만 기기로 보내는** 구조다. 지금 서버의 선 형식(`session.start` 등)은 실제 API로 확인한 적이 없다(요구사항 열린 항목 5).

A. 서버 중계 구조를 유지한다. 조원 패키지는 실제 GPT-Live 세션 생성 · 종료 · 이벤트 형식의 참고 자료로 `VoiceRelay` 어댑터에만 반영하고(서버 쪽 전송 방식은 FR12.2 실호출로 확정), 가드레일 정책 · 24개 오프라인 시험 사례는 `JevGuardrail` · 시험 계획의 입력으로 쓴다 — 장점: FR5 · FR6 보류 · 차단이 그대로 가능하다 / 단점: 서버 쪽 전송(WebSocket 등)이 GPT-Live에서 되는지 아직 모른다 (추천)
B. 조원 방식(브라우저 WebRTC 직접 연결 + 서버 sideband)으로 바꾼다 — 장점: 조원 코드를 많이 재사용하고 지연이 줄 수 있다 / 단점: 재생 전 보류 · 제브 차단이 불가능해 FR5 · FR6 · US1.10 · US2.1을 다시 써야 하고, 기기 화면 · 출력 게이트 구조가 바뀐다
X. Other (please specify)

[Answer]: A
