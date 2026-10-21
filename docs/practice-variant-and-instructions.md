# 연습 변형 어휘 · 연습 세션 지시문 · 제브 연습 표본

- 브랜치: `claude/practice-variant-instructions` (main `fec5285`에서 땀)
- 근거 지시서: 작업 묶음 3 「'새 상황에서 재시도'(변형)가 실제로 나오게 하기 + 연습 대화 품질 자리 만들기」의 (a) · (c) · (d)를 구현했다. (b) 데모 5단계 표현과 (a)의 '언어 부담' 결정적 검사는 선택지와 권장안만 적었다(5 · 6절). (e) 17 화면은 다른 브랜치가 맡아 손대지 않았다.
- 작성: 사람 대신 작업한 모델(Claude). 사람에게 묻지 않고 스스로 정한 것은 8절 가정 목록(`PRACTICE-VARIANT-INSTRUCTIONS-A*`)에 적었다. 원 담당자 검토가 끝나기 전에는 초안이다.
- 증거 표시(R-M11): `[대역]` = 가짜 공급자 · 시험 대역으로 확인, `[실호출-합성]` = 실제 AI에 합성 자료, `[실기기]` = 실제 기기. **이 브랜치의 증거는 모두 `[대역]`이다.** 실제 Luna · GPT-Live · 제브는 한 번도 부르지 않았다.

## 1. 한눈에

| 항목 | 한 일 | 커밋 |
|---|---|---|
| (a) 변형 어휘 · 관계 좌표 · 생성 v3 | 자유 한국어 상대로도 BASE · VARIANT 두 장면이 나오게 고쳤다. 허용 어휘를 Luna 구조 문맥에 넣고 상황 생성 판을 v3로 올렸다 | `3d4a368` |
| (c) 연습 세션 GPT-Live 지시문 자리 | 활동 종류별 지시문 본문을 자원 파일로 나누고 판 · sha256을 세션 사건으로 남긴다. 자유대화 지시문은 바이트 단위로 같다 | `8207f81` · `8a4db10`(위치만 옮김) · `c49856b`(사건 시점 수정) |
| (d) 제브 연습 모드 표본 | 친구 · 코치 역할 합성 표본과 문턱별 다시 계산 보고를 더했다. 실호출은 하지 않았다 | `374f19d` |
| 리뷰 반영 | 어휘 밖 BASE 시험이 실제 계획기를 거치게 함 · v3 대본 시험 사례 추가 · 세션 사건 시점 수정 · NEAR 관계 축 위험 기록(11절) | `692b6c2` · `c49856b` · 이 문서 |
| (b) · 언어 부담 | 선택지 · 권장안만(5 · 6절) | 이 문서 |

## 2. 바꾼 동작

### 2.1 (a) 변형 계획 관계 좌표 · 허용 어휘 · 생성 v3 — core

**고치기 전 문제(재현함, `[대역]`)**

- 후보 목표의 상대 `FourElements.partner`(= `GoalSnapshot.relationType`)는 Luna가 쓴 자유 한국어다(예: "같은 반 친구"). BASE는 장면 `relation`이 이 값과 같아야 통과한다.
- 그런데 변형 계획 좌표(`ScenarioGraphContext.coordinates`)가 이 `relation`을 관계 좌표로 썼다. 관계 좌표는 씨앗 관계 열거값(`KnowledgeSeed.RELATIONSHIPS`)에 있어야 계획이 나오므로, 실제 자료에서는 계획이 나오지 않고 그래프 없는 BASE 한 장면으로 떨어졌다.
- 재현 시험 `PlannedGenerationIT.freeKoreanPartnerWithAllowedCoordinatesYieldsApprovedVariant`를 고치기 전 코드에서 돌린 결과: **실패, "Expected size: 2 but was: 1"**(장면 수만 적음, 원문 없음). 같은 클래스의 기존 5건은 통과.

**바꾼 것**

1. 관계 좌표 분리(지시서의 R1): `ScenarioScript.Preservation`에 `relationship`(씨앗 관계 열거값)을 더하고, 좌표는 `preservation.relationship`에서 읽는다. 장면 `relation`은 목표 상대로 그대로 둔다(BASE 검사 그대로).
2. 옛 노출 행 보호: 관계 좌표가 없는 옛 `scenes_json` 행은 좌표가 빈 값이 되어 `VariantPlanner`에서 NPE → 그래프 없는 경로로 빠졌다. 다섯 좌표가 모두 있는 노출만 계획 입력에 넣는다(`ScenarioGraphContext.complete`, `StructuredScenarioFlow`).
3. 허용 어휘 전달: `structureContext`에 `allowedTopics` · `allowedPlaces` · `allowedParticipants` · `allowedRelationships` · `allowedSituations`를 더했다. 값은 `VariantPlanner` · `KnowledgeSeed` 상수를 정렬한 목록이다(복사하지 않음, `Set` 순회 순서에 흔들리지 않음). 금지 키와 겹치지 않고 정리기를 거쳐도 남는 것을 시험으로 확인했다.
4. 생성 판 v3(아래 ①의 'VARIANT도 같은 상대'는 NEAR 관계 축 변형과 부딪힌다 — 11절 · A20): `prompts/scenario/generation.v3.txt`를 새로 두고 v2 파일은 그대로 남겼다. v2 규칙에 더한 것은 ① 모든 장면 `relation` = 입력 relation 그대로 ② structureContext가 있으면 BASE에도 preservation, 고정값 세 개는 글자 그대로 ③ 좌표 다섯 값은 허용 목록에서 글자 그대로 ④ BASE의 관계 · 자리는 씨앗 값, BASE의 거리 · 변경 축 칸은 `SAME_CONTEXT` · `topic` ⑤ VARIANT 좌표 · 거리 · 축은 `variantPlan` 값 그대로.
5. 판 이름 한 곳: `ScenarioScriptProvider.GENERATION_PROMPT_VERSION`(= `scenario-generation.v3`)과 `GENERATION_INSTRUCTIONS`(자원 경로). 흩어져 있던 네 곳(`LunaTextAiProvider` 두 곳, `ScenarioScriptGenerator` 두 곳)이 이 상수를 쓴다.
6. 계약
   - `contracts/schemas/ai-scenario-output.schema.json`: preservation에 `relationship`(필수)을 더하고 `participants` · `relationship` · `situation`을 서버 어휘 enum으로 묶었다. 그래프 경로 예시를 하나 더했다. 최상위 topic · place는 자유 문자열 그대로다(A3).
   - `contracts/schemas/practice-scenario.schema.json`: `generation.promptVersion`을 `const v2` → `enum [v2, v3]`. 이미 저장된 v2 대본이 멱등 재생으로 다시 나가도 통과한다.
   - `contracts/knowledge-graph.md`(표면 어휘 문단) · `contracts/ai-io.md` 10.11 · `contracts/deviations.md` `(U10-b)`.
7. realtime은 바꾸지 않았다. realtime `PracticeScriptReader`는 preservation을 읽지 않고 promptVersion을 검사하지 않는다.

**바뀌지 않은 것(일부러 둠)**: `VariantPlanner` 결정 규칙 · 정렬(기본 변경 축이 등장인물), 15초 · 호출 6회 한도, 서버의 preservation 고정값 채움(하지 않음), `validBase` 조건.

**그래프 경로에서 지금 남는 실패 조건(시험으로 고정함, `[대역]`)**

| 첫 응답 | 둘째 응답 | 결과 | 시험 |
|---|---|---|---|
| BASE preservation 없음 | BASE 정상(허용 어휘) | 내용 수정 1회를 써서 **BASE 한 장면만** 발급, calls 4, graphContextUsed true | `baseWithoutPreservationIsCorrectedOnceAndIssuesOnlyTheApprovedBase` |
| BASE preservation 없음 | 또 없음 | **연습 시작 실패** `SCENARIO_GEN_FAILED` | `baseWithoutPreservationTwiceFailsTheGraphPathStart` |
| topic이 허용 어휘 밖 | — | 실제 `VariantPlanner` 어휘 검사가 거절 → 계획 없음 → 그래프 없는 새 BASE 한 장면, graphContextUsed false, calls 4 | `baseOutsideVocabularyFallsBackToGraphFreeBase`(계획 대역을 덮어쓰지 않음, 리뷰 반영) |
| preservation 좌표가 enum 밖 | — | 실제 경로에서는 Luna 응답 스키마 검증 실패(`SCHEMA_INVALID` → 내용 실패) → 내용 수정 1회 | `ScenarioContractTest.graphBaseNeeds…`(스키마) · 기존 `ScenarioLunaProviderTest.schemaFailureIsContentCorrectionNotTechnical` |

### 2.2 (c) 연습 세션 GPT-Live 지시문 자리 — realtime

**바꾸기 전**: `session.start` 지시문은 언제나 `DialogInstructions.role(…)` + `JOIN_FIRST_QUESTION`("참여가 확인되면 관심사나 일상 질문 하나로 시작한다.")이었다. 활동 종류를 넘기지 않아 연습 세션에도 이 문장이 세션 내내 남았고, 어떤 지시문으로 대화했는지 기록이 없었다.

**바꾼 것**

1. 자원 파일 `backend/realtime-activity/src/main/resources/live-prompts/`
   - `free-talk.v1.txt`(판 `live-free-talk.v1`): 지금 문장 그대로. **자유대화 지시문은 이전과 바이트 단위로 같다**(시험으로 확인).
   - `practice.v1.txt`(판 `live-practice.v1`): '관심사나 일상 질문으로 시작' 문장을 빼고, AI 동작 명세 v0.2 §2.3 「연습 블록」을 지금 지시문 말투(~다)로 옮긴 다섯 문장. 역할놀이 연습이라는 것, 장면과 역할은 진행 지시로 받는다는 것, 친구 역할은 짧게 말하고 아이 말을 고쳐 주지 않는다는 것, 아이보다 먼저 할 말 · 힌트를 주지 않는다는 것, 코치 역할은 지시가 있을 때만 한다는 것. **본격 내용은 대화 품질(D) 담당 몫이다**(8절 A12 · 10절).
2. `dialog/LiveSessionInstructions`(새 운영 파일): 기동 때 두 파일을 읽는다. 파일 없음 · 빈 파일 · 임시 상한(4000자) 초과 · 자리 표시자(`{ }`) 있음이면 기동을 거부한다. 예외 메시지에는 자원 경로와 사유만 있고 본문은 없다. 판 이름과 보내는 본문 바이트의 sha256(소문자 16진 64자)을 갖고, `toString`에 본문을 넣지 않는다. 자원 이름은 판 이름에서 정해진다(`live-practice.v2` → `live-prompts/practice.v2.txt`).
3. 공통 역할 문장(`DialogInstructions.role`)과 따옴표로 묶은 아이 정보는 그대로 코드에 둔다. 지시문 = 역할 문장 + " " + 활동 본문.
4. `ProviderRequestFactory`가 활동 종류를 받는 `sessionStart`(같은 패키지 안에서만, 연결부가 부름)와 본문 묶음을 돌려주는 `liveInstructions()`를 더했다. 기존 `sessionStart(ChildContext)` · `sessionStart(ChildContext, int)`는 자유대화로 그대로 동작한다.
5. `ProviderConnector.connect`에 활동 종류를 받는 판을 더하고 기존 4인자 판은 자유대화로 넘긴다. `SessionExecutor`가 세션 계획의 `activityType`을 넘긴다.
6. 공급자가 `session.started`로 시작을 받아들였을 때(시작 단계에서 첫 번째 한 번) 세션 사건 `LIVE_INSTRUCTIONS {"kind","version","sha256"}` 하나를 남긴다. 본문 · 아이 정보는 없다. 연결이 안 되거나, 연결은 됐지만 `session.start` 전송이 실패하거나, `session.started`가 오지 않으면 남기지 않는다(리뷰 반영 `c49856b`: 처음에는 연결 직후에 남겨 시작 전송 실패에도 사건이 남았다).
7. `DialogInstructions.JOIN_FIRST_QUESTION` 상수는 자원 파일로 옮겨 없앴다(쓰던 곳은 공급자 요청 공장 하나).
8. 새 운영 파일은 `backend/coverage-scope/voice-session.txt`에 등록했다.
9. 계약: `contracts/ai-io.md` 3절 `session.start` 행 · 10.4 판 리소스, `contracts/deviations.md` `(U11-e)`. 선 형식 키는 같다.

**판 올리는 법(D 담당용, 3줄)**

1. `live-prompts/practice.v2.txt`(또는 `free-talk.v2.txt`)를 새로 만든다. 옛 파일은 지우지 않는다.
2. `LiveSessionInstructions.PRACTICE_VERSION`(또는 `FREE_TALK_VERSION`) 한 줄을 새 판 이름으로 바꾼다.
3. `./gradlew :realtime-activity:test --tests "kr.mujoong.realtime.dialog.LiveSessionInstructionsTest" --tests "kr.mujoong.realtime.voice.ProviderRequestFactoryWireTest" --tests "kr.mujoong.realtime.session.LiveInstructionsSessionTest"`를 돌리고, 판 이름 · 문장을 고정한 단언을 새 판에 맞춘다(자유대화 본문을 바꾸면 "바이트 단위로 같다" 단언도 함께 바꾼다).

**질문 편향 — 위치와 근거만(문장은 바꾸지 않음, 내용 개정은 D 담당)**

- 근거: 멘토링 자료 「무중 대화 품질 평가 및 개선 현황」(10/16) — 테스트 v0.2 26세션에서 Q4 질문 편향 1.00/2.00, 발화의 89.8%가 질문으로 끝났다. 이 수치는 이 브랜치에서 다시 재지 않았다(팀 레포 밖 자료, 배경 메모 기준).
- 자유대화에서 질문을 부르는 곳:
  - `live-prompts/free-talk.v1.txt` — "참여가 확인되면 관심사나 일상 질문 하나로 시작한다." `session.start`에 들어가 세션 내내 남는다. **이제 이 파일만 바꾸면 된다**(위 판 올리는 법).
  - `dialog/InstructionTemplates.java` `ResponseStart.FIRST` — "관심사나 일상 질문 하나로 아이와 이야기를 시작한다." 자유대화 ACTIVE 직후 1회. 이번에 옮기지 않았다(A13).
  - `dialog/DialogInstructions.java` `Correction.CAUSE` · `EMOTION_CLAIM` — "궁금하면 … 물어본다." 게이트가 응답을 버린 뒤 교정할 때마다 질문을 유도한다.
  - 기준 문서 자체도 질문을 권한다: AI 동작 명세 v0.2 §2.2 "일상 질문으로 시작하고, 아이가 한 말에 이어지는 질문을 해", §2.1 "질문은 한 번에 하나만". 고칠 때 이 기준 문서와 함께 맞춰야 한다.
- 연습 세션은 이번 변경으로 질문 시작 문장이 어디에도 없다(`session.start`에서 뺐고, 연습 새 흐름은 ACTIVE 직후 FIRST 응답 요청을 이미 건너뛴다, `SessionExecutor.afterActiveWon`의 `practice.controlsOutput()` 분기). 연습 역할 문장(`PracticeInstructions`)의 되묻기 · 제안 질문은 흐름상 맞아 그대로다.

### 2.3 (d) 제브 연습 모드 합성 표본 — realtime 시험 도구

- `guardrail/JevLiveProbe`(시험 소스, `jevLiveTest`에서만 실호출)
  - `Sample`에 검사 모드와 맥락을 더했다. 기존 자유대화 표본 S1 ~ S5는 그대로다(10/18 실호출과 비교할 수 있게).
  - 표본(모두 합성, 이름 · 식별자 없음, D 담당 검토 전 초안):

    | 모드 | 허용 기대 | 차단 기대 | 경계(판정 기대 없음) |
    |---|---|---|---|
    | FREE_TALK | S1 | S2 진단 · S3 점수 · S4 치료 · S5 압박 | O1 비밀 약속 · "부모는 못 봐"(서비스 사실 축 관찰용, 정책 v3 후보) |
    | PRACTICE_PEER | P1 되묻기 · P2 도움 제안 · P3 변형 제안 | P4 정답 먼저 알려 줌 · P5 재촉 반복 | P6 간접 힌트 · P7 기다리며 서두름 · P8 과한 칭찬 |
    | PRACTICE_COACH | C1 동의 뒤 표현 예시 하나 + 자기 말로 권함 · C2 코치 전환 알림 | C3 점수 평가 · C4 감정 단정 | C5 교정 섞인 권유 · C6 일반화 |

  - 맥락: 친구 역할 2턴(아이 "그거 줘." / 친구 "어떤 거?"), 코치 역할 3턴(위 + 아이 "몰라. 뭐라고 해야 돼?"). 자유대화 맥락은 그대로.
  - 보고서(`mujoong.jev.live.report`)에 더한 것: 모드 열, 모드 · 기대별 PASS 확률 최솟값 ~ 최댓값, 같은 응답으로 다시 계산한 문턱 0.4 · 0.5 · 0.6(과 운영 문턱)별 "허용 기대인데 막힘 / 차단 기대인데 통과" 수. 다시 계산은 운영 판정 규칙(PASS가 1순위 · 동률 아님 · PASS 확률 ≥ 문턱)을 따르고 호출을 더 하지 않는다. 경계 사례와 실패 표본은 세지 않는다. 후보 문장 · 맥락 원문 · 키 · 주소는 쓰지 않는다.
- `guardrail/JevLiveTaskIsolationTest`: 고정 수(5/5 · 4/5)를 표본 수에서 계산하게 바꾸고, 표 머리에 모드 열을 더했다. 모든 모드가 들어 있는지, 연습 두 모드에 허용 · 차단 · 경계가 4개 이상 있는지, 요청이 표본의 모드 · 맥락으로 나가는지, 문턱별 계산이 맞는지를 가짜 어댑터로 확인한다. 기본 문턱 0.5 단언은 그대로다.
- 문턱(`application.yml` `JEV_PASS_THRESHOLD:0.5`)은 바꾸지 않았다.

## 3. 고른 방안과 까닭

| 결정 | 고른 것 | 다른 안 | 까닭 |
|---|---|---|---|
| 관계 좌표 분리 | R1: `preservation.relationship`(열거값) 새 필드 | R2: 스키마 그대로, 좌표가 열거값 밖이면 씨앗 관계로 대체 | R2는 NEAR(관계 축 변경)에서 `variant.relation`에 열거값을 써야 해 '목표 상대'와 '관계 좌표' 뜻이 한 필드에 섞이고, 옛 노출 행 좌표도 씨앗 없이는 못 만든다. R1은 Luna 출력 스키마(core 안)만 바뀌고 공개 대본 · realtime은 그대로다 |
| 좌표 enum 범위 | preservation의 participants · relationship · situation만 enum | 최상위 topic · place도 enum | preservation은 그래프 경로에서만 쓰여 다른 경로에 영향이 없다. topic · place를 묶으면 그래프 없는 BASE도 세 값에 묶이고 기존 예시(`HOME` · "블록 놀이")와 v2 대본 모양이 바뀐다. 어휘 밖 topic은 지금도 계획 없음 → 그래프 없는 BASE로 안전하게 떨어진다 |
| preservation 고정값 | 서버가 채우지 않음(모델이 옮긴 값을 지금처럼 검사) | 서버가 씨앗 값으로 채운 뒤 검사 | 채우면 `validBase` · `valid`의 뜻이 바뀌는 결정이라 도연 몫이다. 대신 v3 프롬프트가 옮기라고 지시하고, 두 번 빠지면 실패하는 조건을 시험 · 문서로 고정했다. 데모 안정성에는 서버 채움이 낫다 — 권장안으로 남긴다(10절) |
| 판 이름 한 곳 | `ScenarioScriptProvider` 인터페이스 상수 | 새 상수 클래스 | 두 사용처(`luna.adapter` · `practice`)가 이미 이 인터페이스에 기대므로 새 파일 · 새 의존이 없다 |
| 세션 지시문 분리 범위 | 활동 본문만 자원 파일로, 공통 역할 문장은 코드에 둠 | 공통 역할 문장까지 자원 파일로 | 열린 PR #47이 `DialogInstructions.role` 첫 문장과 나이 처리를 바꾼다. 역할 문장을 옮기면 그 PR과 정면 충돌한다. 대신 판 · 해시가 활동 본문만 덮는다(A10) |
| 세션 사건 시점 | 공급자 `session.started` 처리(시작 단계, 첫 번째 한 번) | `connect` 요청 때 · 공급자 연결 직후(`ProviderConnected` 처리, 처음 구현) | 사건의 뜻은 '공급자가 이 판으로 세션을 시작했다'다. 연결 스레드는 연결을 먼저 알리고 그 뒤에 `session.start`를 보내므로, 연결 직후에 남기면 시작 전송이 실패해도 사건이 남는다. `session.started`는 공급자가 지시문을 받아들였다는 응답이라 사실과 맞다 |
| 활동 종류를 받는 공장 메서드 | 같은 패키지 안(연결부만)으로 둠 | 공개 메서드 | 기존 구조 시험(`ProviderRequestWhitelistIT`)이 공장 공개 메서드 인자 형식을 묶어 둔다. 그 단언을 넓히지 않고 맞췄다 |
| 제브 경계 사례 | 새 기대값 `BORDERLINE`(판정 기대 없음, 확률만 관측) | ALLOW/NOT_ALLOW에 끼워 넣기 | 문턱 근처 사례를 합격 · 오판 수에 섞으면 문턱 판단이 흐려진다 |

## 4. 설계 편차

| 기호 | 내용 | 기록 위치 |
|---|---|---|
| `(U10-b)` | 상황 생성 v3, `preservation.relationship`, 좌표 enum, 허용 어휘 전달, promptVersion v2 · v3 허용, 옛 노출 행 제외. 호환 분류: 새 필드 · 규칙 구체화 | `contracts/deviations.md` U10 표 · `ai-io.md` 10.11 · `knowledge-graph.md` |
| `(U11-e)` | 세션 지시문 활동 본문 분리와 `LIVE_INSTRUCTIONS` 사건(`session.started` 때), `JOIN_FIRST_QUESTION` 상수 제거. 호환 분류: 규칙 구체화(선 형식 키 같음) | `contracts/deviations.md` U11 표 · `ai-io.md` 3절 · 10.4 |
| 지시서와 다른 점 | ① 지시서는 항목마다 브랜치를 나누라고 했지만 이 브랜치 하나에 (a) · (c) · (d)를 담았다(작업 지시). ② 결과를 `docs/units/u10 · u11 · u13/report.md`가 아니라 이 문서 하나에 적었다(A17). ③ 세션 지시문 로그 한 줄은 남기지 않았다(A11). ④ `free-talk.v1`에 고정 3문장을 옮기지 않았다(A10) | 이 문서 |

## 5. (b) 데모 5단계 '새 상황 재시도' — 선택지와 권장안(고르는 것은 사람)

**선택지 1: 같은 세션의 VARIANT 장면** — BASE가 끝나면 친구 역할이 "비슷한 새 상황으로 한 번 더 해 볼래?"를 묻고(GPT-Live 생성, 제브 PRACTICE_PEER 검사), 아이가 수락하면 새 장면 설명이 재생되고 보호자 12 화면 단계가 '새 상황'으로 바뀐다.

필요한 조건:
- core: 고른 목표가 씨앗과 연결된 후보(skillRef ≠ NONE) · Neo4j 켜짐 · 씨앗 조회 2초 안 · **이 브랜치 (a) 반영** · 4회 호출이 15초 · 6회 안 · VARIANT 검토 APPROVE.
- realtime: BASE 장면이 NORMAL(J2 FULL · J3 DELIVERED · J5 FULL · COPIED) 또는 HELP_DECLINED로 끝남 · 대본에 VARIANT 있음 · 끝내기 요청 없음 · 목표 10분 안 · 집중 시계(코치 첫 도움부터 90초) 안 · 다시 해 보기 2회 미만 · 변형 제안 말이 제브를 통과(막히면 변형을 건너뜀) · 아이 대답이 J4 ACCEPT.
- 위험: 15초 초과 시 승인된 BASE까지 버리고 연습 시작 실패(지연 미측정) · J4 질문 문구 재사용으로 변형 동의 분류 품질 미확인(U12-D3) · 기본 변경 축이 등장인물이라 화면 · 음성 차이가 작음 · partnerRole · 기회 문장 이중 재생 결함(다른 묶음)이 그대로 보임 · 변형 제안이 제브 PRACTICE_PEER에서 막힐 수 있음((d) 실측 전).

**선택지 2: 다음 목표 · 다음 세션의 새 대본** — 그래프 · 변형 조건이 필요 없어 실패할 곳이 적다. 하지만 다음 목표는 행동이 달라 '같은 행동을 새 상황에서'가 아니고, 다음 세션은 데모가 길어지고 7단계 '다음 경험'과 겹친다. '이전과 다른 상황'은 노출 정산 결함(다른 묶음)을 고친 뒤에야 보장된다.

**권장: 선택지 1을 본선, 선택지 2를 대체 컷.** 까닭: 중간발표의 '바꿀 것 vs 지킬 것'을 한 세션 안에서 보여 줄 수 있는 것은 1뿐이다. 1을 확실히 하려면 ① 이 브랜치 (a) 병합 ② 리허설 전에 그 목표 대본이 2장면으로 발급되는지 `core.core_scenario`의 장면 수와 `generation_json.calls`로만 확인 ③ 역할극 대사는 BASE 첫 대답이 FULL로 판정되게(코치 · 재시도 없이 1분 안) ④ 변형 제안에 "응, 해 볼래" ⑤ 씨앗과 연결된 목표 하나만 고르기 ⑥ 변경 축 정렬(장소 · 화제 먼저)을 바꿀지 결정 ⑦ 15초 안에 4회 호출이 끝나는지 실호출로 한 번 재기. 하나라도 리허설에서 깨지면 2로 찍은 컷을 쓴다.

## 6. '언어 부담' 결정적 검사 — 선택지와 권장안(구현하지 않음)

지금 '지킬 것: 언어 부담'은 결정적 검사가 없다. 프롬프트의 "나이에 맞는 짧고 쉬운 말", 검토 사유 `AGE_MISMATCH`, 길이 상한(설명 8000자 · 기회 2000자)뿐이라 FR-F07(요구사항 명세 v1.1 '언어 부담 유지')에 못 미친다.

| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| L1 서버 비율 검사 | `ScenarioScriptValidation`의 VARIANT 검사에 "설명 · 기회 문장 길이(코드 포인트) ≤ BASE × r, 문장 수 ≤ BASE + k"를 더한다. 어기면 `INVALID_VARIANT` → 지금처럼 BASE만 다시 검토해 발급 | 결정적 · 계약 변경 없음(서버 검사만) · 실패해도 BASE는 남음 | 길이 · 문장 수만 본다(어휘 난이도는 못 봄) |
| L2 검토 프롬프트 사유 | 검토 판을 올려 "VARIANT의 말이 BASE보다 어렵다"를 사유로 내게 한다(새 사유 코드면 `ai-scenario-review-output` enum · `ScenarioReview.REASONS` 계약 변경) | 어휘 · 문장 구조까지 볼 수 있다 | 모델 판단이라 결정적이지 않고 실측이 필요 |
| L3 둘 다 | L1을 바닥으로 두고 L2를 더한다 | 가장 촘촘 | 작업량 · 호출 실패 경로가 는다 |

**권장: L1 먼저.** 초기 기준값 제안은 r = 1.3, k = 1(사람이 정한다). 합성 대본 몇 개로 BASE · VARIANT 길이 분포를 본 뒤 확정하고, 필요하면 L2를 다음 판에서 더한다.

## 7. 원 담당자와 맞출 것

| 누구 | 무엇 |
|---|---|
| 도연(core 상황 생성 · 지식 그래프) | R1 채택과 `preservation.relationship` 이름 · 필수 여부, 좌표 enum 범위(최상위 topic · place는 자유 문자열로 둠), v3 프롬프트 문구, 서버의 preservation 고정값 채움 여부(권장: 채움), `validBase`에 관계 · 자리 = 씨앗 조건을 더할지, `VariantPlanner` 기본 변경 축 정렬(BR3), 옛 노출 행 제외 방식, **NEAR 관계 축 변형과 v3 '모든 장면 relation = 목표 상대' 규칙의 충돌(11절, 선택지 가 · 나)** |
| 성민(realtime 연습 흐름 · 대화 · 제브) | `ProviderConnector.connect` 5인자 판 · `sessionStart` 활동 종류 판(같은 패키지 안) 서명, 자원 위치 `live-prompts/`와 판 이름 `live-free-talk.v1` · `live-practice.v1`, 세션 사건 이름 `LIVE_INSTRUCTIONS`와 남기는 시점(`session.started` 처리), 시험용 공급자의 새 모드 `START_FAILS`, `JOIN_FIRST_QUESTION` 상수 제거, 공개 대본 `generation.promptVersion`이 enum(v2 · v3)이 된 것(realtime은 검사하지 않고 옮기기만 함), 제브 표본 문안과 경계 사례 기대 |
| D 담당(대화 품질 · 가드레일 패키지) | `practice.v1` 다음 판 본문(service-0.1 · v0.3 규칙 8개 반영), 자유대화 `JOIN_FIRST_QUESTION`을 뺄지 · 바꿀지, `ResponseStart.FIRST`와 교정 문장을 같은 묶음으로 옮길지와 질문 편향 문구, 제브 표본 문안 검토, '정답 먼저 알려 주기' 범주가 정책에 없는 문제(정책 v3) |
| PR #47(feat/child-birthdate) 작성자 | 이 브랜치는 `DialogInstructions.role`을 건드리지 않고 `JOIN_FIRST_QUESTION` 상수만 지웠다. `ProviderRequestFactory`는 `sessionStart` 서명 줄과 지시문 조립부만 바꿨다. **PR #47 머리(`22b6e45`)와 `git merge-tree`로 시험 병합하면 충돌이 없다.** 두 브랜치를 다 합친 뒤에는 자유대화 지시문이 PR #47의 새 첫 문장 + `free-talk.v1` 본문이 된다(이 브랜치 시험은 `DialogInstructions.role(…)`을 기준으로 비교하므로 그대로 통과할 것으로 본다. 합친 뒤 한 번 돌려 확인) |
| 같은 날 작업한 다른 브랜치 | `claude/practice-seams`와는 `ScenarioScriptGenerator.approved()`에서 텍스트 충돌이 난다(저쪽은 partnerRole 줄, 이쪽은 판 이름 두 줄 — 둘 다 살리면 된다). `claude/join-gate`와 `ai-io.md` 10.4 충돌은 항목 위치를 옮겨 없앴다(`8a4db10`). `claude/done-screen-practice`와는 충돌 없음 |

계약이 바뀌는 작업(`ai-scenario-output` · `practice-scenario` 스키마, `ai-io.md` · `deviations.md`)이므로 core 담당과 realtime 담당이 같은 날 합친다.

## 8. 가정 목록

- **PRACTICE-VARIANT-INSTRUCTIONS-A1**: 관계 좌표는 R1(`preservation.relationship`)로 고쳤다. 지시서가 권장한 안이고, 사람 결정 없이 구현했다.
- **A2**: 장면 `relation`의 뜻은 '목표 상대(자유 문자열)'로 그대로 둔다. v3 프롬프트는 모든 장면에 입력 relation을 그대로 쓰게 한다(VARIANT도 같은 상대). **위험**: NEAR 등급에서 관계 축이 바뀌는 변형 계획이 나오면 이 규칙과 'VARIANT의 preservation.relationship은 variantPlan.context 값' 규칙이 서로 부딪힌다(11절 · A20).
- **A3**: 최상위 topic · place에는 enum을 두지 않았다. 어휘 밖이면 계획이 없어 그래프 없는 BASE로 떨어지는 지금 동작을 유지한다.
- **A4**: 서버가 preservation 고정값을 채우지 않는다. 그래프 경로에서 BASE preservation이 두 번 빠지면 연습 시작이 실패하는 지금 동작을 시험으로 고정했다.
- **A5**: `validBase`에 "BASE의 preservation 관계 · 자리 = 씨앗 값" 조건을 더하지 않았다(프롬프트로만 요구).
- **A6**: BASE의 `transferDistance` · `changedElement`는 스키마상 필수이지만 BASE에서는 검사하지 않으므로 v3 프롬프트가 `SAME_CONTEXT` · `topic`으로 두게 했다.
- **A7**: 옛 노출 행(관계 좌표 없음)은 변형 계획의 노출 횟수 · 이미 쓴 좌표 판단에서 뺀다. 그 행의 장면 문장은 지금처럼 반복 회피용 `history`에 그대로 들어간다.
- **A8**: 판 이름 상수는 `ScenarioScriptProvider`(인터페이스)에 두었다.
- **A9**: realtime 세션 지시문 자원 위치는 `live-prompts/`, 판 이름은 `live-free-talk.v1` · `live-practice.v1`, 세션 사건 이름은 `LIVE_INSTRUCTIONS`로 정했다(지시서 제안값).
- **A10**: 공통 역할 문장(고정 3문장)은 자원 파일로 옮기지 않았다(PR #47 충돌 회피). 그래서 판 · sha256은 활동 본문만 덮는다. PR #47 병합 뒤 역할 문장까지 묶음으로 옮기면 해시가 지시문 고정 부분 전체를 덮게 된다.
- **A11**: `LIVE_INSTRUCTIONS`는 세션 사건(DB)으로만 남기고 로그 한 줄은 남기지 않았다. 새 로그 사건 코드는 계약(EventCode) 변경이라 범위를 넘는다고 봤다.
- **A12**: `practice.v1` 본문은 AI 동작 명세 v0.2 §2.3의 다섯 줄 가운데 "장면이 바뀌어도 같은 놀이 이야기 안에서 자연스럽게 이어 가."를 뺀 네 줄 + 활동 이름 한 문장이다. 뺀 까닭: 변형 장면(SAME_CONTEXT)은 화제 · 장소를 바꾸고 다음 목표는 "이번에는 다른 이야기야"로 시작하므로(§8.4), '같은 놀이 이야기'라는 지시가 서로 부딪힐 수 있다. 화면 설계 v1.5j에는 GPT-Live 발화 지시에 해당하는 문구가 없어(보호자 화면 문구표뿐) 명세 §2.3을 근거로 삼았다.
- **A13**: `ResponseStart.FIRST`와 교정 문장은 옮기지도 바꾸지도 않았다(작업 지시).
- **A14**: 세션 지시문 활동 본문의 상한은 임시 4000자다. 공급자 `session.start` 지시문 상한은 팀 레포에 기록이 없다.
- **A15**: 활동 종류가 `UNRECOGNIZED`면 프로그램 오류로 보고 예외를 던진다(내부 API가 이미 400으로 막고, 세션 계획은 null을 FREE_TALK로 바꾼다).
- **A16**: 제브 표본 문안은 내가 쓴 합성 초안이다. 경계 사례(P6 ~ P8 · C5 · C6 · O1)가 실제로 PASS 확률 0.4 ~ 0.6에 오는지는 모른다.
- **A17**: 결과는 유닛 보고서(`docs/units/u10 · u11 · u13/report.md`) 대신 이 문서 한 곳에 적었다(여러 브랜치가 같은 보고서를 고치는 충돌을 피함). 병합 뒤 필요하면 원 담당자가 옮긴다.
- **A18**: 제브 문턱별 다시 계산은 응답에 다른 라벨 확률이 없어 동률(PASS_TIED) 판단을 원래 판정의 사유 코드로만 한다.
- **A19**: `(U10-b)` · `(U11-e)` 기호는 각 표의 다음 글자로 골랐다. 다른 브랜치가 같은 기호를 먼저 쓰면 병합 때 다음 글자로 바꾼다.
- **PRACTICE-VARIANT-INSTRUCTIONS-A20**: NEAR 관계 축 충돌(11절)은 프롬프트 · 계획기를 고치지 않고 위험과 선택지만 적었다. 까닭: 고치는 두 길이 모두 원 담당자 결정을 바꾼다 — (가)는 장면 `relation`의 뜻('목표 상대', A2)과 중간발표의 '지킬 것: 상대 역할'을, (나)는 BR3 변형 규칙을 바꾼다. 데모의 새 아이 자료에서는 노출 이력이 3회 미만이라 NEAR가 나오지 않으므로(SAME_CONTEXT만) 데모 경로에는 닿지 않는다고 봤다.
- **PRACTICE-VARIANT-INSTRUCTIONS-A21**: `LIVE_INSTRUCTIONS` 사건 시점을 `session.started` 처리로 옮겼다(리뷰 반영). 같은 세션에서 `session.started`가 두 번 와도 첫 번째만 남긴다. 사건 시각은 공급자 사건을 받은 시각이다.

## 9. 시험 결과

모두 `[대역]`이다(가짜 공급자 · 시험 대역, 실제 AI 호출 없음). 명령은 `backend/`에서 돌렸다.

| 명령 | 결과 |
|---|---|
| 고치기 전 `./gradlew :core-api:test --tests "kr.mujoong.core.practice.PlannedGenerationIT" -Pmujoong.test.db=docker` | 6건 중 1건 실패(재현 시험, 2장면 기대 · 1장면 발급), 5건 통과 |
| `./gradlew :core-api:test --tests "kr.mujoong.core.practice.Scenario*" --tests "…PlannedGenerationIT" --tests "…PreparationRecoveryIT" --tests "kr.mujoong.core.luna.*" --tests "kr.mujoong.core.knowledge.RulesTest" --tests "kr.mujoong.core.practice.start.*" -Pmujoong.test.db=docker` | 298건 중 2건 실패(새 시험의 가짜 공급자 설정 오류) → 고친 뒤 `PlannedGenerationIT` 11건 통과 |
| `./gradlew :contract-model:test` | 322건 통과 |
| `./gradlew :realtime-activity:test --tests "kr.mujoong.realtime.dialog.*" --tests "kr.mujoong.realtime.voice.*" --tests "kr.mujoong.realtime.session.LiveInstructionsSessionTest" --tests "kr.mujoong.realtime.arch.*" --tests "kr.mujoong.realtime.guardrail.*" -Pmujoong.test.db=docker` | 399건 통과 |
| `./gradlew :realtime-activity:test --tests "kr.mujoong.realtime.guardrail.*" -Pmujoong.test.db=docker`((d) 뒤) | 92건 통과(`JevLiveTaskIsolationTest` 10건 포함) |
| 넓은 회귀: `./gradlew :core-api:test -Pmujoong.test.db=docker` | **1734건 통과, 실패 0, 건너뜀 0**(5분 59초) |
| 넓은 회귀: `./gradlew :realtime-activity:test -Pmujoong.test.db=docker` | **1885건 통과, 실패 0, 건너뜀 0**(5분 23초) |
| 리뷰 반영(`692b6c2`): `./gradlew :core-api:test --tests "…PlannedGenerationIT" --tests "…start.PracticePackageTest" --tests "…ScenarioRepositoryIT" --tests "…ScenarioContractTest" -Pmujoong.test.db=docker` | 42건 통과(11 · 9 · 11 · 11). 확인용으로 `VariantPlanner`의 topic 어휘 조건을 잠시 빼고 돌리면 `baseOutsideVocabularyFallsBackToGraphFreeBase`가 "Expected size: 1 but was: 2"로 실패했다(되돌림, 커밋에 없음) |
| 리뷰 반영(`c49856b`): `./gradlew :realtime-activity:test --tests "kr.mujoong.realtime.session.LiveInstructionsSessionTest" -Pmujoong.test.db=docker` | 5건 통과. 옮기기 전 `SessionExecutor`로 돌리면 새 두 건(시작 전송 실패 · `session.started` 없음)이 실패했다 |
| 리뷰 반영 뒤 넓은 회귀: `./gradlew :realtime-activity:test -Pmujoong.test.db=docker` | **1887건 통과, 실패 0, 건너뜀 0**(5분 9초) |
| 최종 전체 검사(`920eb73`): `./gradlew --no-daemon clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker --continue` | core-api 1736건 중 2건 실패, 나머지 모듈 통과. 실패는 둘 다 Neo4j 시험이다(`GraphWorkflowIT` 2초 예산 기준선, `Neo4jIT.concurrentCommitAndTerminationCannotResurrectAfterCleanup` "Graph operation unresolved"). 이 브랜치는 `knowledge` 코드를 바꾸지 않았다. 같은 컨테이너에서 다른 작업 트리의 Gradle · Neo4j 컨테이너가 함께 돌던 때였다 |
| `Neo4jIT`만 다시: `./gradlew --no-daemon :core-api:test --tests "kr.mujoong.core.knowledge.Neo4jIT" -Pmujoong.test.db=docker` | 통과(부하 아래 시간 문제로 본다) |

실호출: 하지 않음. `jevLiveTest` · `lunaLiveTest` · `gptLiveTest`는 돌리지 않았다(R-F1 · R-F15, 사람 몫).

### 9.1 넓은 회귀 · 병합 확인

- 마지막 커밋 기준으로 core-api · realtime-activity 기본 `test` 작업 전체가 녹색이다(위 표). 알려진 기준선 실패 `GraphWorkflowIT`(느린 컨테이너의 Neo4j 초기 적재 2초 예산)도 이번 실행에서는 통과했다. 이 시험은 이 브랜치에서 `Preservation` 생성자 인자 하나만 바뀌었다.
- `check`(커버리지 하한 · 정적 검사 포함)와 `verify.sh`는 다음 단계가 돈다. 이 브랜치의 새 운영 파일은 `LiveSessionInstructions` 하나이고 `voice-session` 범위에 등록했다.
- `git merge-tree`로 시험 병합(작업 트리 변경 없음): PR #47 머리 `22b6e45` · `claude/join-gate` · `claude/done-screen-practice`와 충돌 없음, `claude/practice-seams`와 `ScenarioScriptGenerator.java` 한 곳 충돌(7절).

## 10. 사람이 할 일

1. **(b) 결정**: 데모 5단계를 선택지 1 / 2 / 1 본선 + 2 대체 컷 중 무엇으로 할지(권장: 1 본선 + 2 대체 컷).
2. **도연과 결정**: 8절 A1 · A3 · A4 · A5와 `VariantPlanner` 기본 변경 축 정렬. 특히 데모 안정성을 위해 preservation 고정값 서버 채움을 권한다(채우면 `(U10-b)`에 적고 `validBase` · `valid` 뜻 변경을 편차로 남긴다).
3. **(a) 실호출 확인(선택, OpenAI R-F15 기록이 새 시험까지 덮는지 먼저 확인)**: 합성 목표 3개 · 인메모리 씨앗으로 `generation.v3` · `review.v1`을 실제 Luna로 불러 장면 수 · 계획 유무 · BASE 좌표가 어휘 안인지 · 검토 verdict · 사유 코드 · calls · 지연(p50 · 최댓값)만 기록한다. 이 시험(`@Tag("luna-live")`, 예: `practice/ScenarioVariantLiveTest`)은 아직 없다. 15초 안에 4회 호출이 끝나는지도 이때 잰다.
4. **(c) GPT-Live 수락 확인**: `./gradlew :realtime-activity:gptLiveTest`로 `session.start` 지시문이 받아들여지는지 본다. 지금 `GptLiveProbe`는 자유대화 지시문만 보낸다 — 연습 지시문을 보려면 도구의 `sessionStart` 호출을 활동 종류 판으로 바꿔야 한다. D 담당 본문이 길어지면 4000자 임시 상한과 공급자 상한을 이때 정한다.
5. **(c) D 담당**: `practice.v1`의 다음 판 본문과 자유대화 질문 편향 문구(2.2절 위치 목록) — 판 올리는 법은 2.2절.
6. **(d) 제브**: `infra/RUNBOOK.md` 제브 R-F15 확인 상자 4개와 표를 확인한 사람이 채운다. 10/18 `jevLiveTest`가 기록 없이 돌았는지 처리한다. 그다음 셸 환경 변수 `JEV_API_KEY` · `JEV_BASE_URL` · `JEV_MODEL`을 넣고 `cd backend && ./gradlew :realtime-activity:jevLiveTest` 또는 `bash scripts/live/jev-probe.sh`로 돌려 결과를 `[실호출-합성]`으로 적는다(outcome · 모드별 판정 · PASS 확률 분포 · 문턱별 잘못 판정 수 · 지연 · 응답 model, 원문 없음).
7. **(d) 문턱 결정**: 실측 뒤 0.5 유지 · 변경을 정한다. 바꾸면 `application.yml` 기본값 · `contracts/ai-io.md` 10.12 · `contracts/deviations.md` `(U13-d)` · `infra/RUNBOOK.md` 8.8 · 시험 단언을 함께 고친다.
8. **언어 부담 검사(6절)**를 할지와 기준값.
9. **팀원 확인 · 공지**: 도연 · 성민 리뷰, 계약 변경 PR 같은 날 병합, D 담당 실명 정하기.
10. **전체 검증**: `./gradlew check`와 `bash scripts/verify.sh --mode compose`는 다음 단계에서 돈다(이 브랜치에서는 돌리지 않음).
11. **도연과 결정 — NEAR 관계 축(11절)**: 선택지 (가) · (나) 중 고르거나 지금대로 둔다.
12. **커밋 제목 범위**: 커밋 `fb35917`의 제목 `docs: 연습 변형 어휘 · 세션 지시문 · 제브 표본 작업 기록을 남긴다`에 범위가 없다(팀 형식 `<종류>(<범위>): …`). 이력 재작성이라 모델은 고치지 않았다. push 전에 사람이 `docs(practice): …`처럼 범위를 붙이거나, 그대로 둘 거면 PR 설명에 적는다.

## 11. 리뷰 반영 — NEAR 관계 축 변형과 v3 '같은 상대' 규칙의 충돌(고치지 않음, 위험 · 선택지)

- 무엇이 부딪히나: v3 6행은 "모든 장면의 relation에는 입력 relation 값을 글자 그대로 쓰고, 장면 설명은 이 상대에 맞춰" 쓰게 한다. 같은 프롬프트 9행은 VARIANT의 `preservation.relationship`을 `variantPlan.context` 값으로 쓰게 한다. `VariantPlanner`는 같은 씨앗 · 관계 · 상황 노출이 3회 이상이면 NEAR 등급으로 관계 · 상황 축 후보만 만들고, 정렬이 변경 축 이름순(`relationship` < `situation`)이라 아직 노출되지 않은 관계 축 후보가 있으면 **관계 축을 먼저 고른다**. 이때 좌표는 '관계가 바뀌었다'(예: `FRIEND_CLOSE` → `CLASSMATE`)고 하는데 장면 상대와 설명은 그대로라는 모순 지시가 된다.
- 결과 예상(코드와 프롬프트를 읽어 판단, 실호출로 확인하지 않음): 검토(`review.v1` 4행)는 "정확히 한 변경 요소를 실제 서술과 함께" 보므로 `UNCHANGED_VARIANT`나 `TRANSFER_MISMATCH`로 변형이 버려지고 BASE 한 장면만 발급될 가능성이 크다. 서버 검사(`ScenarioScriptValidation`)는 VARIANT `relation`을 보지 않아 막지 못한다.
- 언제 닿나: 노출 이력(최근 3건)이 모두 같은 씨앗 · 관계 · 상황일 때만이다. 데모의 새 아이 자료에서는 나오지 않는다(SAME_CONTEXT, A20).
- 선택지(고르는 것은 도연):
  - **(가) 프롬프트 예외**: `variantPlan.changedField`가 `relationship`인 VARIANT만 장면 설명에서 새 관계(`preservation.relationship`)를 드러내게 v3 문구에 예외를 둔다. 장면 `relation` 칸을 목표 상대로 둘지 새 관계를 한국어로 쓸지도 함께 정해야 한다. 판을 올리지 않고 v3를 고칠 수 있는 것은 v3가 아직 `main`에 없을 때까지다.
  - **(나) 계획기에서 관계 축 빼기**: `VariantPlanner`의 NEAR 후보에서 관계 축을 빼고 상황 축만 남긴다. 중간발표의 '지킬 것: 상대 역할'과 맞지만 BR3 변경이다.
  - 지금대로 두기: NEAR 관계 축 변형은 대부분 검토에서 버려진다. 검토 사유가 변형 사유(`UNCHANGED_VARIANT` · `TRANSFER_MISMATCH` 등)뿐이면 `ScenarioScriptGenerator`가 승인된 BASE만 한 번 더 검토해 BASE 한 장면으로 발급하므로 연습 시작은 실패하지 않는다. 다만 검토 호출이 하나 늘어 15초 한도에 더 가까워진다.
