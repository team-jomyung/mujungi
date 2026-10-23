# 연습 장면 시작 개선 — 원래 대화 요약 · 무중이 여는 말 (구현 지시)

작업 브랜치: `feat/practice-scene-start` (이 문서만 있는 상태로 올린다. PR은 구현을 마친 뒤 만든다)

## 0. 시작하기 전에

- 이 브랜치는 `fix/practice-empty-unit`(PR #67) 위에서 땄고, #67에는 `feat/practice-decisions-judge`(PR #66)가 병합돼 있다. 병합 순서는 **#66 → #67 → 이 작업**이다.
  - #66 · #67이 main에 들어가면 `git fetch && git merge origin/main`으로 따라잡는다. 그 뒤에는 이 브랜치의 차이가 이 작업만 남는다.
- `contracts/deviations.md` 표 끝에 편차 행을 더하는 작업이 여러 PR에서 겹치면 충돌한다. 이 작업의 편차 번호는 **FT-1023-g**로 미리 잡는다.
- 먼저 읽을 규칙: `.claude/CLAUDE.md`, `aidlc/spaces/default/memory/team.md`, `aidlc/spaces/default/memory/project.md`.
  - 특히 지킬 것: R-F1(시험에서 실공급자 금지), R-F2(시험 약화 금지), R-F14(로그에 원문 금지), R-F15(아동 데이터 외부 전달), R-M10(계약 같이 고치기), R-M11(대역 · 합성 · 실호출 · 실기기 결과를 따로 보고).

## 1. 배경 (2026-10-23 로컬 실키 연습, 시험자 음성 · 실제 GPT-Live)

17:14 연습 세션 시작 부분이다. 아이 역할은 시험자가 했고, 실제 아동 데이터는 없다.

| 순서 | 화자 | 내용 | 단계 |
|---|---|---|---|
| 1 | AI | 참여 안내 "…오늘 나랑 같이 연습해 볼래?" | 참여 확인 |
| 2 | 아이 | 그래 | 수락 |
| 3 | AI | "오늘 연습할 목표는 '대화 흐름 요청하기'야. …" | GOAL_BRIEF |
| 4 | AI | "음, 지난번 이야기부터 잠깐 떠올려볼까? 기억이 안 나거나 원하지 않으면 넘기고 바로 새로 해도 돼." | REFLECTION_OFFER |
| 5 | 아이 | (지난번 대화 내용을 말함) | 돌아보기 대답 |
| 6 | AI | "도서관에서 대화 상대와 여행 책을 같이 보고 있어. 상대가 한 쪽을 읽으며 잠시 생각을 정리하고 있어. 무중이가 대화 상대 역할을 해 볼게." | 장면 도입 |
| 7 | AI(상대) | "잠깐만, 생각을 좀 정리하고 있어." | 기회 문장 |
| 8 | 아이 | 그래 | — |
| 9 | AI | "내가 조금 놓쳤을 수도 있으니까, 편하면 한 번만 더 말해줄래?" | 되묻기 |
| 10 | 아이 | **"내가 무슨 말을 해야 돼"** | 방향을 잃음 → 도움으로 감 |

시험자 소감: "상대가 좀 주도를 해 주길 바랐는데, 뭐라고 해야 될지 감이 안 와서 기다렸더니 무응답 안내가 나왔다."

드러난 문제는 두 가지다.

1. **돌아보기 질문.** "떠올려볼까?"라고 묻는데, 아이는 기억하지 못할 가능성이 크다. 묻고 기다리는 단계가 연습 시작을 늦춘다.
2. **장면 도입과 기회 문장만으로는 방향이 없다.**
   - 도입에 아이의 처지 · 필요가 없고, 상대는 "대화 상대"로만 불린다.
   - 상대의 한 마디("잠깐만, 생각 좀 정리하고 있어")만 듣고는 아이가 무엇을 하면 되는지 모른다.

## 2. 요구사항 (사람 확정)

### 요구 1. 원래 대화 요약을 바로 들려준다

- 목표 안내 뒤, 묻지 않고 무중이 **이 연습 상황이 만들어진 원래 대화의 내용을 짧게 요약해 바로 들려준 뒤** 장면 도입으로 넘어간다.
- 인수 기준:
  1. "떠올려볼까?" 같은 질문과 그 대답을 기다리는 단계가 없다. 지금의 REFLECTION_OFFER · REFLECTION_PROMPT · 대답 판정(J4) · REFLECTION_ACK를 대신한다.
  2. 요약은 1 ~ 2문장 서술이다. 예: "지난번에 너는 내가 대답이 느리다고 말해 줬어. 오늘은 그런 순간에 어떻게 말하면 좋을지 연습해 볼 거야."
  3. 요약에는 원래 대화의 **확인된 사실만** 담는다(`reflectionContext.verifiedQuotes` 근거).
     - 감정 · 원인 · 평가 · 성공 · 실패를 지어내지 않는다.
     - 보호자 메모를 아이 말로 다루지 않는다.
     - 정답 표현을 먼저 알려 주지 않는다.
  4. 근거가 없는 목표(지금 `reflectionPrompt`가 null인 경우)는 요약 없이 바로 장면 도입으로 간다. VARIANT 장면에도 요약은 없다.
  5. 요약 재생 중이나 직후에 아이가 말해도 판정하지 않고 장면 도입으로 이어 간다. 전사 기록은 그대로 남는다.

### 요구 2. 상황 제시 뒤 무중이 연습 대화를 먼저 연다

- 상황을 제시한 뒤 무중이 **상대 역할로 대화를 먼저 열어**, 아이가 대화의 방향을 잡게 한다.
- 인수 기준:
  1. 장면 도입(`spokenIntroduction`)은 아이에게 말하듯 쓴다.
     - 아이의 처지와 필요를 담는다: 지금 무엇을 하는 중이고, 무엇이 필요하거나 불편한지.
     - 상대를 구체적으로 부른다(예: "같은 반 친구").
  2. 도입 뒤 무중이 상대 역할로 **여는 말**(`openingLine`)을 먼저 한다.
     - 아이가 쉽게 대답할 수 있는 열린 말 걸기다.
     - 기회 문장과는 다른 문장이다.
  3. 여는 말에는 정답 표현이나 아이가 할 행동을 넣지 않는다(기존 `ANSWER_INCLUDED` · `EARLY_CUE` 규칙 유지).
  4. 여는 말 다음 처리:
     - 아이의 대답이 무엇이든(예: "그래") 판정하지 않고 기회 문장으로 넘어간다.
     - 무응답이면 `no-response-wait` 뒤 기회 문장으로 넘어간다(무응답 재안내 횟수에 넣지 않는다).
  5. 그 뒤(기회 문장 → 판정 → 되묻기 · 도움 …)는 지금 흐름 그대로다.

## 3. 지금 구조 (코드 위치)

### core — 대본 생성
- `core-api/.../practice/StructuredScenarioFlow.java`: 대본 생성 입력을 조립한다.
  - 첫 BASE이고 아이 근거 인용이 있으면 `reflectionContext {source: CHILD_UTTERANCE, verifiedQuotes}`를 넣는다(48행 근처).
  - 근거 인용은 `practice/PracticeContinuity.verifiedChildEvidence`가 원래 전사와 대조해 만든다.
- `core-api/src/main/resources/prompts/scenario/generation.v2.txt`: 생성 지시문.
  - `spokenIntroduction` 규칙은 3행.
  - `opportunitySentence` 규칙은 2 · 17 · 18행.
  - `reflectionPrompt` 규칙은 30행.
- `prompts/scenario/review.v1.txt`: 독립 검토 지시문.
- `core-api/.../textai/ScenarioScript.java`: 대본 레코드(`spokenIntroduction`, `reflectionPrompt` 필드).
- `core-api/.../practice/ScenarioScriptValidation.java`: 서버 검사(길이 · 제어 문자 · 정답 포함 · 이름 등). `reflectionPrompt` 검사는 72행 근처.
- 계약:
  - `contracts/schemas/ai-scenario-output.schema.json`(AI 출력)
  - `contracts/schemas/practice-scenario.schema.json`(시작 패키지)
  - `contracts/schemas/internal-practice-scenario-request.schema.json`
- 설계 문서: `docs/adaptive-practice-dialogue.md`(45행 reflectionPrompt 설명)

### realtime — 연습 흐름
- `realtime-activity/.../practiceflow/PracticeScriptReader.java` · `PracticeScript.java`: 시작 패키지의 대본을 읽는다(`reflectionPrompt` 검사는 126행).
- `practiceflow/PracticeConversation.java`:
  - `startGoal` → GOAL_BRIEF(생성형) → `beginReflectionOrIntroduction()`(392행).
  - REFLECTION_OFFER(생성형 질문) → 아이 대답 판정 J4 → REFLECTION_PROMPT(`speakApproved(reflectionPrompt)`) → REFLECTION_ACK(생성형) → `finishReflection()` → `beginIntroduction()`(412행, `speakApproved(spokenIntroduction)`).
  - 장면 도입이 끝나면 OPPORTUNITY(`speakApproved(opportunitySentence)`)로 간다.
  - 돌아보기 관련 필드: `reflecting` · `reflectionAwaiting` · `earlyUtterance`. 관련 분기: 166 · 200 · 222 · 532 · 561 · 586 · 592 · 743 · 797행 근처.
- `practiceflow/PracticeInstructions.java`: `reflectionOffer()` · `reflectionAcknowledgment()`(98 ~ 104행), `spokenIntroduction(scene)`(59행).
- `practiceflow/FlowState.java`: `Sub.REFLECTION_OFFER` · `REFLECTION_PROMPT` · `REFLECTION_ACK`.

## 4. 설계 방향 (권장안 — 다르게 정하면 이 절을 고치고 진행)

### 요구 1
- **core**
  - 대본 필드 `reflectionPrompt`(질문)를 **`reflectionSummary`**(서술 요약, 1 ~ 2문장, 300자 이하)로 바꾼다.
  - 생성 규칙: `reflectionContext.verifiedQuotes`가 있을 때만 쓴다. 인용에서 확인되는 사실 한 가지를 서술하고 오늘 연습과 이어 준다. 물음으로 끝나지 않는다. 지어낸 사실 · 정답이 없다.
  - 검토(`review.v1.txt`)와 서버 검사(`ScenarioScriptValidation`)를 같은 기준으로 고친다.
    - 근거 밖 사실은 검토에서 막는다.
    - 물음표 끝 · 길이 · 이름 · 연락처 · 정답 포함은 서버 검사에서 막는다.
- **realtime**
  - REFLECTION 단계를 `Sub.REFLECTION_SUMMARY` 하나로 줄인다.
  - 흐름: `speakApproved(reflectionSummary)`(승인 문장 그대로 낭독) → 재생 끝(PLAYED · INTERRUPTED) → `beginIntroduction()`.
  - 판정(J4) · 무응답 시계 · 맞장구 발화는 없앤다. 낭독 실패는 지금 승인 낭독 실패 처리를 따르되, 장면 행 생성 전이므로 실패 시 연습을 닫지 말고 요약을 건너뛰고 장면 도입으로 가도록 한다.
- **옛 패키지 호환**: `reflectionPrompt`만 있는 옛 대본은 돌아보기를 건너뛴다(질문 단계를 되살리지 않는다).

### 요구 2
- **core**
  - `spokenIntroduction` 규칙을 보강한다: 아이 처지 · 필요 필수, 상대를 구체적으로, '너는 ~' 2 ~ 3문장. 검토 기준도 함께 고친다.
  - 새 대본 필드 **`openingLine`**을 더한다.
    - 화자는 상대이고, 1 ~ 2문장, 200자 이하다.
    - 아이에게 말을 거는 열린 말이고, `opportunitySentence`와 다른 문장이다.
    - 정답 · 아이 행동 · 목표 표현을 넣지 않는다.
  - 검사: 기존 `ANSWER_INCLUDED` · `EARLY_CUE` 검사를 이 필드에도 적용하고, 원문 포함 · 길이 · 제어 문자 검사를 한다.
- **realtime**
  - 장면 도입과 기회 문장 사이에 `Sub.OPENING`(Phase OPPORTUNITY 앞)을 둔다.
  - 흐름: `speakApproved(openingLine)`(상대 역할 낭독 큐 PEER) → 아이 대답 1회 또는 `no-response-wait` 만기 → 판정 없이 `speakApproved(opportunitySentence)`.
  - 여는 말 단계의 아이 발화는 판정 · 무응답 재안내 횟수 · 장면 결과에 넣지 않는다. 전사로는 남긴다.
- **옛 패키지 호환**: `openingLine`이 없으면 지금처럼 바로 기회 문장으로 간다.

### 기록 · 리포트
- 장면 행의 시작 시각(리포트 2항목 인용 범위 = 장면 시작 ~ 다음 장면)이 여는 말 앞인지 뒤인지 정한다.
  - 권장: 장면 시작 = 장면 도입 시작(지금과 같음).
  - 이렇게 두면 여는 말에 대한 아이 대답도 그 장면 인용 범위에 들어간다. 판정과 연결되지 않은 발화라 2항목 '장면 판정이 가리키는 발화'에는 잡히지 않는지 core `ReportRuleSections`에서 확인한다.
- `practice-record-v2.schema.json`에 새 사실(facts)이 필요한지 확인한다. 권장: 필요 없음 — 판정이 없는 단계다.

## 5. 계약 · 문서 (R-M10)
- `contracts/schemas/ai-scenario-output.schema.json` · `practice-scenario.schema.json`(필요하면 `internal-practice-scenario-request.schema.json`): `reflectionPrompt` → `reflectionSummary`, `openingLine`을 더한다. 옛 필드를 읽는 쪽 호환을 적는다.
- `contracts/ai-io.md`의 상황 생성 절(10.11 근처)과 연습 흐름 설명.
- `contracts/deviations.md` **FT-1023-g**: 근거는 2026-10-23 사람 결정(요구 1 · 2)과 17:14 로컬 실키 사례. 화면 설계 v1.5j의 연습 흐름(장면 → 기회)과 다름을 적는다.
- `docs/adaptive-practice-dialogue.md` 해당 절 갱신.
- 화면 설계 정본(`aidlc/spaces/default/knowledge/documents/`)은 고치지 않는다. 바꿀 필요가 있으면 개정 제안(RP-n) 표로 남긴다.

## 6. 시험 (대역 기준, R-F1 · R-M11)
- **core**
  - 생성 · 검토 · 서버 검사 단위 시험:
    - 근거 있을 때 `reflectionSummary` 허용, 없을 때 거부
    - 물음표 끝 · 정답 포함 · 근거 밖 사실(검토 대역) 거부
    - `openingLine` 정답 포함 · 기회 문장과 같음 · 화자 위반 거부
  - 시작 패키지 스키마 대조.
- **realtime `PracticeConversationTest` / SessionHarness**
  1. 요약 있음: GOAL_BRIEF → 요약 낭독(질문 · 판정 없음) → 장면 도입
  2. 요약 재생 중 아이 발화 → 판정 없이 장면 도입
  3. 요약 없음 → 바로 장면 도입
  4. 요약 낭독 실패 → 연습을 닫지 않고 장면 도입
  5. 여는 말 → 아이 "그래" → 판정 없이 기회 문장
  6. 여는 말 → 무응답 만기 → 기회 문장(재안내 횟수 불변)
  7. `openingLine` 없는 옛 패키지 → 지금 흐름
  8. 17:14 사례 재현: 여는 말이 나온 뒤 기회 문장으로 이어지는지
- **계약 대조 시험 · ArchUnit 통과**
- **실호출-합성(수동, CI 밖)**: core 대본 생성으로 요약 · 여는 말의 품질을 몇 건 확인하고 결과를 따로 보고한다.

## 7. 사람 확인이 필요한 것
- 요약 · 여는 말 문구 기준(임시 문구 J118): 길이, 말투, 예시.
- 요약을 '승인 문장 낭독'으로 할지(권장, 사실 보장) 생성형으로 할지.
- 아동 대화 내용을 다시 들려주는 것이 보호자 동의 · 표시 범위 안에 드는지(R-F15). 같은 내용은 이미 대본 생성 입력으로 Luna에 가고 있다.
- 여는 대화를 1차례로 할지.
- 화면 설계 v1.5j 개정 제안(RP) 처리 여부.

## 8. 진행 순서 (권장)
1. 계약 · 스키마 → core 생성 · 검토 · 검사 → core 시험
2. realtime 대본 읽기 → 흐름(요약 → 도입 → 여는 말 → 기회) → realtime 시험
3. 계약 문서 · 편차 · 설계 문서 갱신
4. 바꾼 쪽 정적 검사 · 단위 · 통합 시험(team.md '작업 단계마다의 검증')
5. 로컬 실키(`bash scripts/live/up.sh --local`)로 연습 1회 확인 → 결과를 대역 / 실호출 / 실기기로 나눠 PR에 적는다.
6. 커밋 형식: `<종류>(<범위>): <한국어 요약>`. 커밋 전 `git diff --cached --summary`로 실행 권한 변경을 확인한다.
