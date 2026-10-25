# 연습 대화 C안 — 무중이 직접 묻기

> **한 줄 요약.** 장면 속 친구 역할을 없앤다. 무중이 상황과 친구의 말을 자기 말로 전하고 "너라면 뭐라고 말할래?"라고 묻는다. 장면 안에서 무중이 하는 말은 모두 대본을 만들 때 미리 써 둔 승인 문장이다. 그 자리에서 GPT-Live가 말을 지어내지 않는다.
>
> - 결정: 2026-10-24 팀장(준하). 이 문서의 1장에 결정과 기본값을 적었다.
> - 바탕: `main` `916ec12b`(10/24). 브랜치 `claude/practice-direct-ask`. main 병합은 팀 합의 뒤에 한다.
> - 상태: **구현 · 자동 시험 완료(가짜 공급자), 5회차 실측 준비 중.** 구현 결과는 13장에 적었다. 5회차 실측 결과는 그 뒤에 이어 적는다.
> - 앞선 기록: `claude/practice-dialogue-standard` 브랜치의 `docs/practice-dialogue-standard.md`(4회차까지 실측). main과의 비교는 같은 날 조사로 확인했다(35개 파일 충돌, 같은 문제를 다르게 푼 곳 다수).

## 1. 결정

### 1.1 C안을 고른 까닭

4회차 실측(실제 API + 합성 아이 음성, 실제 기기 · 실제 아이 아님)과 main 코드 조사에서 다음을 확인했다.

- **그 자리에서 만든 말이 자주 빠지고 느리다.** 우리 4회차에서 친구가 그 자리에서 만든 말은 13번 중 2번만 들렸다. 코치 말은 13.5~18.1초, 친구 반응은 12초를 기다렸다.
  - main도 `session.instructions.append`는 "지시를 추가하는 동작이며 발화를 보장하지 않는다"고 적었다(`docs/flexible-practice-dialogue.md`).
  - main이 여러 번 주고받는 친구 대화에서 친구 말이 몇 번 들렸는지는 아직 센 기록이 없다.
- **미리 써 둔 문장은 믿을 만했다.** 우리 미리 만든 음성은 35번 모두 재생됐다. main의 승인 원문 읽기도 단건 시험 3/3이 원문과 같았다.
- **main은 제브(가드레일)가 꺼지거나 실패하면 그 자리에서 만든 연습 말을 버린다.** 승인 원문과 같은 말은 제브를 건너뛴다(`SessionExecutor` 생성형 슬롯 · 원문 일치 분기).
- **흐름이 단순해진다.** main 문서에 남은 문제로 "목표 달성 뒤 친구가 동의해도 아이의 추가 답만 기다려 마무리가 불분명"이 적혀 있다(`docs/practice-main-integration-20261023.md`).

대신 잃는 것이 있다.
- 친구의 자연스러운 반응과 여러 번 주고받기.
- 할 말을 직접 소리 내어 보는 연습(아이가 할 말을 '전하는' 식으로 답하기 쉽다).
- 고칠 범위가 크다. 대본 생성 · 판정 · 기준 문서 개정 제안, 그리고 도연 님의 유연 대화를 운영에서 내리는 일까지 들어간다.

### 1.2 팀장이 고른 세 가지

| 질문 | 고른 답 | 이 문서에서 하는 일 |
|---|---|---|
| 장면 속 친구의 말을 어떻게 들려주나 | **무중이 자기 말로 전하기** (목소리 하나) | 대본 새 칸 `opportunityRelay` (4.2) |
| "다음에 하자고 할래"처럼 전하는 식으로 답하면 | **그대로 인정하기** (내용이 맞으면 바로 다음으로) | 판정 `prompt-service-v4` · 재판정 `rejudgment.v2` (6장) |
| 아이가 잘 말했을 때 | **친구 반응 전해 주기** (칭찬 대신, 대본에 미리 씀) | 대본 새 칸 `friendReaction` (4.2) |

### 1.3 이 문서가 정한 기본값

팀장 확인이 더 필요하면 바꾼다.

1. **C안이 바꾸는 범위는 장면 안의 말이다.** 장면 밖 말(목표 안내 · 돌아보기 · 목표 회고 · 낭독 실패 복구)은 main 동작을 그대로 둔다. 이 말들은 생성형이고 제브가 필요하다.
   - main 코드가 목표 안내 · 회고를 "생성 발화, 제브 필수, 승인 낭독으로 건너뛰지 않음"으로 정해 두었다(`PracticeInstructions.factualNotice` 주석). 그 결정을 따른다.
   - 문구 안의 '역할놀이' · '상대 역할'만 C안에 맞게 고친다(7.3).
2. **도움은 장면마다 한 번이다.** 첫 도움에 표현 예시를 넣고("예를 들면 … 처럼 말할 수 있어"), 다시 해 보기도 한 번이다(`retry-max` 2 → 1).
   - 기준 문서 예시 대화(AI동작명세 §8.2 차례 6)와 팀 기준 대화가 이렇다.
   - main의 `RolePolicy`(예시는 동의 + 두 번째 회차부터)와는 다르다. 편차 FT-1024-d.
3. **유연 대화 경로는 지우지 않는다.** C안은 지금의 '대본형' 경로(`flexibleDialogue=false`)를 고쳐 만들고, 운영 설정을 그쪽으로 돌린다. 유연 대화 코드와 시험은 남겨 설정 하나로 되돌릴 수 있게 한다(7.1).
4. **말소리는 main의 승인 원문 읽기(GPT-Live)로 낸다.** 무중 목소리가 자유대화와 같아진다.
   - 우리 브랜치의 미리 만든 음성(TTS)은 1단계에서 옮기지 않는다.
   - 5회차에서 원문 읽기가 자주 빠지거나 느리면 2단계로 옮긴다(11장).

## 2. 아이에게 들리는 대화 (예)

목표 "친구 마음 살피며 말하기", 공룡 그림 장면이다. 무중의 말은 모두 승인 문장이다.
- 장면 밖 말(목표 안내 · 회고)만 생성형이고 제브를 거친다.
- `[칸]`은 대본 칸, `[고정]`은 시드 승인 문장이다.

| 차례 | 갈래 | 무중의 말 |
|---|---|---|
| 0 | 목표 안내(생성 · main) | 오늘 연습할 목표는 ‘친구 마음 살피며 말하기’야. 오늘 고른 목표는 2개이고, 지금은 1번째야. 이 목표로 짧게 이야기 연습을 해 볼 거야. 필요하면 도움을 받아도 되고, 언제든 쉬거나 끝내도 돼. |
| 1 | 상황 안내 `[spokenIntroduction]` | 친구랑 공룡 그림을 같이 그리는 이야기야. 너는 친구한테 같이 그리자고 했어. |
| 2 | 친구 말 전하기 + 묻기 `[opportunityRelay]` + `[askPrompt]` | 그런데 친구가 손이 좀 아파서 그림을 오래 못 그릴 것 같대. 너라면 친구한테 뭐라고 말할래? |
| 3A | (아이) "다음에 같이 그리자고 할래." → J2 FULL(전하는 식 인정) → 친구 반응 `[friendReaction]` | 그렇게 말해 주면 친구도 마음이 편해서 고마워할 거야. |
| 4 | 변형 제안 `[고정 VARIANT_OFFER]` | 비슷한 이야기로 한 번 더 해 볼까? |
| 3B | (아이) "그럼 그만해." → J2 PARTIAL → 되묻기 `[reaskPrompt]` | 친구 손이 아프다고 했지? 그 말을 듣고 친구한테 어떤 말을 해 주면 좋을까? |
| 3B′ | (아이) "손 괜찮아? 오늘은 쉬고 다음에 그리자." → J3 DELIVERED → 친구 반응 `[friendReaction]` | 그렇게 말해 주면 친구도 마음이 편해서 고마워할 거야. |
| 3C | (아이) "몰라. 뭐라고 해야 돼?" → J2 HELP → 도움 `[helpSpeech]` | 친구가 힘들 때는 친구 마음을 먼저 물어보고 다른 방법을 말해 주면 돼. 예를 들면 손 괜찮아, 그럼 다음에 같이 그리자처럼 말할 수 있어. 네 말로 해 볼래? |
| 3C′ | (아이) "손 아프면 오늘은 조금만 하고 내일 또 그리자." → J5 FULL → 친구 반응 `[friendReaction]` | 그렇게 말해 주면 친구도 마음이 편해서 고마워할 거야. |
| 3D | (아이) "무슨 말이야?" → J2 NOT_UNDERSTOOD → 다시 설명 `[reexplanation]` + 묻기 `[askPrompt]` | 친구가 손이 아파서 그림을 오래 그리기 힘들대. 너라면 친구한테 뭐라고 말할래? |
| 3E | (무응답) → 다시 묻기 `[고정 WAIT_LEAD]` + `[askPrompt]` | 천천히 생각해도 돼. 너라면 친구한테 뭐라고 말할래? |
| 3E′ | (또 무응답) → 장면 닫기 `[고정 SCENE_CLOSE]` | 알겠어. 이 이야기는 여기까지 할게. |
| 5 | 목표 회고(생성 · main) | 오늘은 ‘친구 마음 살피며 말하기’를 네 말로 해 봤어. 이 목표 연습은 여기까지야. |

- 역할 전환 안내("잠깐, 무중이 도와줄게" · "다시 친구 역할로 돌아갈게")는 나오지 않는다.
- 친구 반응 문장에는 따옴표를 넣지 않는다. 원문 대조가 문장부호까지 엄격해서 따옴표가 빠지거나 바뀌면 불일치로 버려지기 때문이다(4.3).

## 3. 흐름

### 3.1 목표 하나

```
목표 시작 ── 목표 안내(생성) ── [돌아보기(main 그대로)] ──┐
                                                          ▼
SCENE_INTRO: 상황 안내[spokenIntroduction] ─▶ OPPORTUNITY: 친구 말 전하기 + 묻기[opportunityRelay + askPrompt] ─▶ 아이 대답 → J2
  FULL ──────────────▶ 친구 반응[friendReaction] (IMMEDIATE) ──────────────┐
  PARTIAL ───────────▶ REASK: 되묻기[reaskPrompt] → J3                      │
                          DELIVERED ─▶ 친구 반응 (AFTER_REASK) ───────────┤
                          STILL_SHORT ▶ HELP_OFFER                         │
                          HELP ──────▶ FOCUS                               │
  HELP · 절차 질문 ───▶ FOCUS: 도움[helpSpeech] ─▶ RETRY → J5              │
                          FULL · COPIED ▶ 친구 반응 (AFTER_HELP) ─────────┤
                          그 밖 ─────▶ 장면 닫기[SCENE_CLOSE] (NONE)        │
  NOT_UNDERSTOOD · IRRELEVANT ▶ 다시 설명[reexplanation] + 묻기[askPrompt] → J2 (1회, 넘으면 HELP_OFFER)
  UNREADABLE · 판정 보류 ▶ 확인[INPUT_CONFIRM] → J2 (2번째면 장면 닫기)    │
  무응답 ────────────▶ 다시 묻기[WAIT_LEAD + askPrompt] (2번째면 장면 닫기) │
HELP_OFFER: [HELP_OFFER] → J4  ACCEPT ▶ FOCUS / REFUSE ▶ 장면 닫기 (HELP_DECLINED) / UNCLEAR 2회 ▶ 장면 닫기
                                                                           ▼
VARIANT_OFFER: [VARIANT_OFFER] → J4  ACCEPT ▶ 변형 장면 SCENE_INTRO / 그 밖 ▶ (변형 생략)
목표 회고(생성) ─▶ 다음 목표 또는 마무리(목표 3개 상한, main 그대로)
```

- 바탕은 main의 대본형 전이표(`FlowTransitions` OPPORTUNITY · REASK · HELP_OFFER · RETRY · VARIANT_OFFER)다. 갈래와 결과값은 거의 그대로다. 바뀌는 곳은 네 군데다.
  1. 역할 전환 단계(`ROLE_NOTICE` · `RETURN_NOTICE`)를 거치지 않는다.
  2. 모든 출력이 승인 낭독이다.
  3. 다시 설명 뒤 묻기를 다시 읽는다. main은 다시 설명 뒤 듣기만 한다.
  4. 다시 해 보기 뒤 부족하면 도움을 다시 하지 않고 장면을 닫는다(`retry-max` 1).
- 변형 장면도 같은 흐름이다. 변형 대본의 `spokenIntroduction` · `opportunityRelay` · `askPrompt` …를 쓴다.

### 3.2 상태별 말

| 상태 | 나가는 말 | 출처 | 비고 |
|---|---|---|---|
| SCENE_INTRO | 상황 안내 | 대본 `spokenIntroduction` | 없으면(옛 대본) 고정 대체 문장(7.4) |
| OPPORTUNITY 진입 | 친구 말 전하기 + 묻기 | 대본 `opportunityRelay` + `askPrompt` | 한 슬롯으로 이어 읽음 |
| OPPORTUNITY 무응답 1회 | 다시 묻기 | 고정 `WAIT_LEAD` + 대본 `askPrompt` | |
| REEXPLAIN | 다시 설명 + 묻기 | 대본 `reexplanation` + `askPrompt` | main은 다시 설명만 |
| REASK | 되묻기 | 대본 `reaskPrompt` | |
| HELP_OFFER | "무중이가 조금 도와줄까?" | 고정 `HELP_OFFER`(시드 004에 이미 있음) | main은 생성형 |
| FOCUS | 도움 | 대본 `helpSpeech` | 끝은 고정 꼬리 "네 말로 해 볼래?" |
| RETRY 무응답 1회 | "네 말로 한 번 말해 볼래?" | 고정 `RETRY_PROMPT` | |
| 확인(판독 불가 · 보류) | "잘 못 들었어. 한 번 더 말해 줄래?" | 고정 `INPUT_CONFIRM` | main은 생성형 |
| 장면 성공 | 친구 반응 | 대본 `friendReaction` | 칭찬 없음 |
| 장면 실패 · 거절 · 한도 | "알겠어. 이 이야기는 여기까지 할게." | 고정 `SCENE_CLOSE` | |
| VARIANT_OFFER | "비슷한 이야기로 한 번 더 해 볼까?" | 고정 `VARIANT_OFFER` | main은 생성형("잘 마쳤다고" 평가 포함) |

### 3.3 한도

AI동작명세 §8.3(G-16)과 같다. 바뀌는 값은 다시 해 보기 하나다.

| 대응 | 한도 | 넘으면 |
|---|---|---|
| 되묻기 | 기회마다 1회 | (J3 결과로 진행) |
| 다시 설명 | 기회마다 1회(`reexplain-max` 1, 그대로) | HELP_OFFER |
| 무응답 다시 묻기 | 기회마다 1회 | 장면 닫기 |
| 판독 불가 · 보류 확인 | 기회마다 2회까지 | 장면 닫기 |
| 도움 | 장면마다 1회 | — |
| 다시 해 보기 | 장면마다 1회(`retry-max` 2 → **1**) | 장면 닫기 |

## 4. 대본 (core)

### 4.1 판

- 대본 판: `scenario-script.v2`(계약 `practice-scenario` 스키마의 판 값에 v2 추가).
- 생성 프롬프트 `generation.v3`(main `generation.v2`를 바탕으로 새 파일), 검토 프롬프트는 main `review.v1` 그대로다. 검토 입력 대본에서는 장면마다 C안 칸 다섯 개를 뺀다(상황 안내 `spokenIntroduction`은 main도 검토하므로 남긴다). 옛 판 파일은 지우지 않고, 같은 판 이름을 다른 내용으로 고치지 않는다.
  - 처음에는 C안 칸까지 검토하는 `review.v2`를 만들었다. 5회차 대본 생성 실측(실제 API + 합성 입력)에서 이 검토가 거절을 늘려 발급이 main보다 줄었고(main 3/6, C안 2/6 · 0/6), 그동안 C안 칸 서버 검사는 한 번도 걸리지 않았다. 그래서 출시 전에 지우고 main 검토로 되돌렸다(13.4 #10).
- 배포 순서: realtime을 먼저 배포한다. realtime이 v1 · v2를 모두 읽은 뒤에 core가 v2를 낸다.

### 4.2 칸

**들리는 칸.** 모두 반말이다.

| 칸 | 새 칸? | 뜻 | 예 |
|---|---|---|---|
| `spokenIntroduction` | 있음 | 상황 안내. 누구와 무엇을 하는지, 아이가 방금 한 일. 단서는 넣지 않는다 | 친구랑 공룡 그림을 같이 그리는 이야기야. 너는 친구한테 같이 그리자고 했어. |
| `opportunityRelay` | **새** | 상대가 한 말을 무중이 간접 인용으로 전한다. 상대의 낱말과 말투를 거의 그대로 살리고 끝만 "~대 · ~래 · ~라고 했어 · ~냐고 물어봤어"로 바꾼다. 단서는 상대의 말 자체에 남기고 속뜻 · 이유 · 감정 · 의도를 풀어 말하지 않는다 | 그런데 친구가 오늘은 손이 좀 아파서 그리기 어려울 것 같대. / 그런데 친구가 주말에 뭐 하냐고 물어봤어. |
| `askPrompt` | **새** | 상대에 맞춘 묻는 말 한 문장 | 너라면 친구한테 뭐라고 말할래? / 너라면 선생님께 뭐라고 말씀드릴래? |
| `reaskPrompt` | **새** | 일부만 전했을 때 되묻는 질문 하나. 단서를 다시 짚되 답은 주지 않는다 | 친구 손이 아프다고 했지? 그 말을 듣고 친구한테 어떤 말을 해 주면 좋을까? |
| `reexplanation` | 있음 | 다시 설명 | 친구가 손이 아파서 그림을 오래 그리기 힘들대. |
| `helpSpeech` | **새** | 도움. 요점 한 문장 + 표현 예시 하나 + 고정 꼬리 "네 말로 해 볼래?" | (2장 차례 3C) |
| `friendReaction` | **새** | 목표를 이룬 어떤 대답 뒤에도 맞는 친구의 반응을 무중이 전한다. 칭찬하지 않는다 | 그렇게 말해 주면 친구도 마음이 편해서 고마워할 거야. |

**안 들리는 칸.** 지금 뜻 그대로 둔다.
- `sceneDescription`: 내부 사실(조건 원문 + 상대 대사 인용). main 규칙 그대로.
- `opportunitySentence`: 상대의 직접 대사. trigger script · 노출 근거라 뜻을 바꾸지 않는다. C안에서는 들려주지 않는다.
- `focusPoints` · `expressionExamples`: 기록(FOCUS_HELP · EXPRESSION_EXAMPLE 사실)과 `helpSpeech` 검사 근거.
- `relation` · `place` · `topic` · `preservation` · `partnerRole` · `reflectionPrompt`.

### 4.3 들리는 칸의 공통 규칙

생성 지시와 서버 칸별 검사(4.4)에 넣는다. 별도 검토(main `review.v1`)는 C안 칸을 보지 않는다(4.1).

- **문장부호는 마침표 · 쉼표 · 물음표만 쓴다.** 따옴표(" ' “ ” ‘ ’) · 느낌표 · 물결 · 말줄임표 · 괄호는 쓰지 않는다.
  - main 원문 대조는 공백만 빼고 문장부호까지 같아야 통과한다(`PracticeSpeech.matches`). 전사에서 이런 기호가 빠지거나 바뀌면 불일치로 버려지고 재시도한다.
- **길이.** 한 칸 2문장 이하, 200자 이하. `helpSpeech`만 3문장 이하.
- **역할 말 금지.** "역할" · "코치" · "무중이가 친구야" · "무중이가 ○○ 할게" 같은 말을 쓰지 않는다.
- **답 누설 금지.** `opportunityRelay` · `askPrompt` · `reaskPrompt` · `reexplanation`에 목표 표현이나 정답 단서를 넣지 않는다. 이 네 칸 가운데 검토가 보는 것은 `reexplanation`(필수 칸)뿐이고, 검토 사유는 기존 `ANSWER_INCLUDED` · `EARLY_CUE`다.
  - 서버 검사의 목표 표현 누설 검사를 이 네 칸에 건다(`reexplanation`은 장면 판정, 나머지 셋은 칸별 판정).
- **`opportunityRelay` 규칙.** 아이가 알아챌 단서가 들어 있어야 한다(생성 지시 몫). 상대 말을 간접 인용으로 전한다: 상대의 낱말을 거의 그대로 살리고 끝만 "~대 · ~래 · ~라고 했어 · ~냐고 물어봤어"로 바꾸며, 속뜻 · 이유 · 감정 · 의도를 풀어 말하지 않는다. 서버는 `opportunitySentence`와 글자가 같은 것(직접 대사를 그대로 옮긴 것), 상대 말에 없는 짐작 말투, 낮은 충실도를 칸별로 거른다(4.4).
- **`reaskPrompt` 규칙.** 단서를 다시 짚되 답은 주지 않는다. 서버는 상대 말에 없는 짐작 말투를 거른다(충실도는 보지 않는다).
- **`helpSpeech` 규칙.** 끝은 정확히 "네 말로 해 볼래?"다. 표현 예시는 `expressionExamples` 중 하나의 뜻을 담는다. 목표 표현 누설 검사는 빼고 압박 검사는 건다.
- **`friendReaction` 규칙.**
  - 칭찬 · 평가어(잘했 · 멋지 · 정답 · 훌륭 등)를 쓰지 않는다(서버 `REACTION_PRAISE`).
  - 물음으로 끝내지 않는다.
  - 아이 대답의 구체 내용을 가정하지 않는다. 목표를 이룬 어떤 대답에도 맞아야 한다.
  - 목표를 이룬 뒤에 들리는 칸이라 목표 표현 누설 검사는 하지 않는다.
- **`askPrompt` 규칙.** "너라면"으로 시작하고 물음표로 끝나는 한 문장이다. 상대 호칭은 `relation`에 맞춘다. 선생님 · 어른이면 높임말을 쓴다. 서버는 선생님 장면인지와 '선생님' 낱말이 맞는지만 본다(`ASK_PROMPT_RELATION`).

### 4.4 서버 검사 · 생성 흐름

- `ScenarioScriptValidation`: 장면 판정과 칸별 판정으로 나눈다.
  - 장면 판정(`INVALID_BASE` · `INVALID_VARIANT`, 진단 `ScenarioRule_<사유>`, 보완 흐름)은 main 규칙 그대로 필수 칸만 본다(BASE 설명의 조건 원문 · 기회 문장, 돌아보기 검사, 거절 사유 코드). main과 달리 상황 안내는 여기서 보지 않는다.
  - 칸별 판정(`optionalSpokenIssues`)은 들리는 선택 칸(상황 안내 + C안 칸 다섯 개)을 칸마다 보고 처음 걸린 사유 하나를 낸다. 모양(빈 값 · 길이 · 제어 문자 · 문장부호 · 문장 수 · 역할 말) → 내용(금지 표현 · 원래 이름 · 연락처 → 목표 표현 누설 → 압박) → 칸의 뜻 순서다. 칸의 뜻은 전하기의 상대 대사 복사 · 짐작 말투 · 충실도(상대 말 두 글자 조각의 0.5 이상), 되묻기의 짐작 말투, 묻기의 모양 · 선생님 관계, 도움 꼬리, 반응의 칭찬 · 물음 끝이다. 상황 안내는 main 규칙 그대로다. 목록 · 문턱은 계약 10.17이 정본이다.
- `ScenarioScriptGenerator`: main 로직(보완 3회 · 실패 사유 전달 · 마감 계산 · 진단 · 이전 연습 기록)을 그대로 쓴다.
  - 검토에는 장면마다 C안 칸 다섯 개를 뺀 사본을 보낸다(키를 보내지 않는다).
  - 발급 직전(`approved`, BASE · VARIANT)에만 칸별 판정에 걸린 칸을 빼고(null) 장면은 발급한다. 뺀 칸마다 진단 `ScenarioField_<칸>_<사유>`(고정 코드뿐, R-F14)를 남긴다. 칸 문제는 수정 요청을 만들지 않는다.
  - 생성 루프 안의 BASE 보존 비교와 수정 요청 대본은 칸을 빼기 전의 원래 대본이다.
- realtime은 빠진 칸 자리에 대체 문장(7.4)을 읽는다.
- `StructuredScenarioFlow`: 그대로다.
- 요청 스키마(`LunaSchemas` · `CoreLunaClient`)는 계약 스키마 사본이다. 새 칸을 계약 스키마에 넣으면 따라온다. strict 요청에서는 모든 칸이 required가 되므로, 새 칸 다섯 개도 모델이 반드시 채운다.
- `generation.v3`에서 "무중이가 친구 역할을 해 볼게" 규칙(v2 3행)을 지우고 C안 칸 설명을 넣는다.

## 5. 고정 승인 문장 (시드 006)

`APPROVED_SPEECH` 종류로 `infra/seed/006-practice-direct-ask-reference.sql`에 넣는다.
- 종류 행은 `…0008a5`부터, 판 행은 `…0008b6`부터다.
- 등록할 곳: `infra/migrate/Dockerfile`, `infra/RUNBOOK.md`, `scripts/verify/b-host.sh` · `b-stack.sh`, `ApprovedContent` · `SeedIds` · `DevSeedRunner`, 시작 허용 목록(`StartActivityService`), `contracts/ai-io.md` 10.6.

| 코드 | 문장 | 쓰는 곳 |
|---|---|---|
| `HELP_OFFER` (있음) | 무중이가 조금 도와줄까? | HELP_OFFER |
| `VARIANT_OFFER` | 비슷한 이야기로 한 번 더 해 볼까? | 변형 제안 |
| `RETRY_PROMPT` | 네 말로 한 번 말해 볼래? | 다시 해 보기 무응답 |
| `INPUT_CONFIRM` | 잘 못 들었어. 한 번 더 말해 줄래? | 판독 불가 · 판정 보류 |
| `SCENE_CLOSE` | 알겠어. 이 이야기는 여기까지 할게. | 장면 실패 · 거절 · 한도 |
| `WAIT_LEAD` | 천천히 생각해도 돼. | 무응답 다시 묻기 앞머리 |
| `ASK_DEFAULT` | 너라면 친구한테 뭐라고 말할래? | `askPrompt`가 없는 친구 장면의 대체(7.4) |

- `COACH_SWITCH` · `PEER_RETURN`은 시작 허용 목록에서만 뺀다. 이미 main에 있는 시드 004 파일은 고치지 않는다(R-M2와 같은 원칙).
- 승인 문장의 팀 승인 기록은 AI동작명세 관례를 따라 남긴다. 이 문서의 표가 초안이다.

## 6. 판정

- **`prompt-service-v4`**: main `prompt-service-v3`을 바탕으로 C안 틀을 쓴다.
  - 무중이 상대의 말을 전하고 "너라면 뭐라고 말할래?"라고 묻는다.
  - 아이 대답이 직접 말 · 전하는 말("다음에 같이 그리자고 할래") · 할 행동 설명 중 무엇이든, 내용이 목표 핵심 기능을 채우면 FULL · DELIVERED다.
  - 할 말을 정하지 못하고 방법만 묻는 말("뭐라고 말해야 돼?")은 HELP다.
  - 역할 대화 전제 문단과 J3 `ROLE_DIALOGUE_CLOSURE` 특수 단락은 뺀다. C안에서는 J3을 되묻기 뒤에만 쓴다.
- **`judgment-service-v2`**: J2 · J3 · J5 질문의 '장면 속 친구' · '코치'를 '무중의 질문' · '무중의 도움'으로 바꾼다. 코드와 지점은 그대로다.
- **화자 값 `MUJOONG`**: 판정 입력의 최근 대화에서 무중의 줄을 `PARTNER` 대신 `MUJOONG`으로 보낸다(`ai-judgment-request` 스키마). 제브 쪽은 이미 이 값을 받는다.
- **절차 질문 규칙**(`PracticeProcedureQuestion`, "…라고 말하면 돼?" → HELP)은 그대로 둔다. 확인하려는 아이에게 도움(예시 포함)이 가는 쪽이 낫다.
- **기록용 재판정**(core `RejudgmentGateway` 인라인 `rejudgment.v1`) → `rejudgment.v2`로 같은 인정 규칙을 넣는다. 리포트 2항목의 줄 이름("바로 전달" 등)이 이 값을 쓴다.
- 판정 경로는 main 그대로 Decisions API 한 길이다(effb4024). 시한 · 확률 하한도 그대로다.

## 7. realtime

### 7.1 모드와 설정

- `PracticeFlowSettings.from`이 늘 `flexibleDialogue=true`를 넣던 것을 설정 키로 바꾼다. 운영 기본값은 `false`(C안)다.
  - 키 이름은 기존 `mujoong.practice-flow.*`(retry-max · reexplain-max) 옆의 `mujoong.practice-flow.flexible-dialogue`로 한다. 계약 · `ConfigKeyContractTest` 목록에 더한다.
- `false` 경로(대본형)를 C안으로 바꾼다. `true` 경로(유연 대화)와 그 시험은 그대로 둔다.
- `retry-max` 2 → 1. `contracts/ai-io.md` 10.14를 고친다.

### 7.2 바꾸는 곳

| 대상 | 할 일 |
|---|---|
| `PracticeConversation`(대본형 경로) | 출력을 모두 `submitApproved`(승인 낭독)로 바꾼다. 역할 전환 단계를 거치지 않는다. OPPORTUNITY 슬롯 = `opportunityRelay` + `askPrompt`. 다시 설명 뒤 묻기를 다시 읽는다. 무응답 · 확인 · 다시 해 보기 무응답은 고정 문장으로 낸다 |
| `FlowTransitions`(대본형) | HELP_OFFER ACCEPT · HELP 요청 → FOCUS에서 `ROLE_NOTICE` 없이 곧장 도움. wrap에서 `RETURN_NOTICE`를 거치지 않는다. 다시 설명이 끝나면 묻기로 간다 |
| `PracticeScript` · `PracticeScriptReader` | 새 칸 다섯 개를 읽는다. v1 대본이면 대체 문장을 쓴다(7.4) |
| `PracticeInstructions` | 목표 안내의 "짧게 역할놀이를" → "짧게 이야기 연습을"로 바꾼다. 목표 회고의 "상대가 다시 물었을 때" → "무중이 다시 물었을 때"로 바꾼다. 대체 도입 문장(7.4)도 바꾼다 |
| `JudgmentPrompt` · 판정 자원 | `prompt-service-v4` · `judgment-service-v2`를 기본 판으로 하고 화자 `MUJOONG`을 쓴다 |
| 무응답 시계(옮김: 우리 브랜치 R1) | 아이 말의 첫 입력 전사 조각에서 `TIMER_NO_RESPONSE`를 멈추고 `child-speech` 보호(= `no-response-wait`)를 건다. main은 확정 전사 판정 때만 멈춰서, 아이가 길게 말하는 중에 무응답이 터질 수 있다(우리 3회차 6/9 → 4회차 0) |
| 끊긴 마무리 문장 | 아이가 `friendReaction` · `SCENE_CLOSE` 위에 말해 끊기면 전달된 것으로 보고 다음으로 간다. main은 이때 연습 전체가 `OUTPUT_FAILED`로 끝난다 |
| 도움 동의 기록 | 아이가 먼저 도움을 청하면(J2 · J3 HELP) 동의(ACCEPT)로 기록한다. 리포트 8항목이 이것을 '답 없음'으로 세던 문제를 함께 푼다 |

### 7.3 장면 밖 말 (main 그대로)

- 목표 안내 · 목표 회고 · 돌아보기 제안 · 받아 주기 · 승인 낭독 실패 뒤 '뜻 전하기' 복구는 생성형이고 제브를 거친다.
- 그래서 **제브가 꺼져 있으면(OFF) 연습이 목표 안내에서 실패한다**(main 동작).
  - 로컬 기본값은 대역(STUB)이다. 대역은 제브 단계를 지나며 ALLOW를 돌려주므로 연습이 진행된다(`GuardrailMode.STUB`, `GuardrailSupport.enabled()`는 OFF만 거짓).

### 7.4 칸이 없을 때 대체 (옛 대본 v1 · core가 뺀 칸)

옛 대본(v1)에는 새 칸이 없고, v2 대본도 core가 칸별 판정(4.4)으로 뺀 칸은 없다. 없는 칸만 칸마다 대체하고, 있는 칸은 대본 칸을 읽는다.

| 칸이 없을 때 | 대체 |
|---|---|
| `spokenIntroduction` | 상상 속 이야기를 떠올려 봐. 무중이가 이야기를 들려줄게. |
| `opportunityRelay` | 친구가 이렇게 말했어. + `opportunitySentence` |
| `askPrompt` | 고정 `ASK_DEFAULT` |
| `reaskPrompt` | 조금 더 자세히 말해 줄래? |
| `helpSpeech` | `focusPoints[0]` + "예를 들면 " + `expressionExamples[0]` + "처럼 말할 수 있어. 네 말로 해 볼래?" |
| `friendReaction` | 그렇게 말해 주면 친구도 네 마음을 알 거야. |

대체 문장도 4.3 문장부호 규칙을 지킨다. 고정 대체 문장은 realtime 상수로 두고 계약(10.17)에 적는다.

- 위 표의 전하기 · 묻기 · 반응은 친구 장면 문장이다. 이 셋은 장면 `relation`의 상대 호칭으로 고른다: `TEACHER`는 높임말("선생님이 이렇게 말씀하셨어." · "너라면 선생님께 뭐라고 말씀드릴래?" · "그렇게 말씀드리면 선생님도 네 마음을 아실 거야."), `CLUB_SENIOR`는 선배, `CLUB_JUNIOR`는 동생, `FAMILY`는 호칭 없는 문장("그때 이렇게 말했어." · "너라면 뭐라고 말할래?" · "그렇게 말해 주면 네 마음이 잘 전해질 거야."), 그 밖과 모르는 값은 위 친구 문장이다.
- 코드가 아닌 관계 이름(예: '동아리 형')은 낱말로 고른다. 낱말 목록과 전체 표는 계약 10.17이 정본이다(13.4 #16).

### 7.5 그대로 쓰는 main 장치

- 승인 낭독: `PracticeSpeech.scenario` → `session.instructions.append`.
  - 앞 출력이 800ms 조용해진 뒤 보내고, 원문 대조가 맞으면 제브를 건너뛴다.
  - 틀리면 버리고 한 번 더 읽힌다. 또 틀리면 생성형 '뜻 전하기'를 한 번 낸다.
- 실행부 단계별 시한, 슬롯이 닫힌 동안 GPT-Live 자동 응답 버리기.
  - 말이 모두 승인 문장이면 자동 응답은 아이에게 들리지 않는다.
- 목표 3개 상한, 목표 10분 한도, 그만하기(등록 표현만), 참여 규칙.

## 8. 기록 · 리포트

- 연습 기록의 모양과 `flowVersion`(`practice-conversation-v1`)은 그대로다.
- 시점 5값의 뜻도 그대로다: IMMEDIATE / AFTER_REASK(+REASK_RESOLVED) / AFTER_HELP / NONE / ABSTAINED.
- SITUATION_EXPLAINED · FOCUS_HELP · EXPRESSION_EXAMPLE · HELP_ACCEPTED/DECLINED · 변형 사유 5종 · 실패 · 종료 사실을 계속 낸다.
- 역할 사실(COACH_* · ORIGINAL_SCENE_RETURN)은 내지 않는다. 계약과 core는 깨지지 않는다(V7 제약 안). 마이그레이션은 없다.
- 보호자 화면 말풍선은 AI · CHILD 두 화자이고 AI는 "무중"으로 보인다. 바꿀 것이 없다.
- 리포트 2항목 인용은 그대로 된다. 줄 이름은 `rejudgment.v2`를 따른다.

## 9. 계약 · 편차 · 기준 문서

### 9.1 계약 (R-M10)

- `contracts/ai-io.md`:
  - C안 절을 새로 쓴다(흐름 · 칸 · 고정 문장 · 대체 문장 · 판정 규칙).
  - 10.4(판정 판 이름), 10.6(승인 문장 표), 10.10 · 10.11(판정 입력 화자), 10.14(`retry-max`), 10.15(연습 출력)를 고친다.
  - main 문서의 "승인 낭독 = commentary" 서술은 코드와 반대라서 함께 바로잡는다(지금 코드: 생성 = commentary, 승인 = instructions).
- `contracts/schemas/ai-scenario-output.schema.json`: 새 칸 다섯 개 · 설명 · 예시를 넣는다. '코치 역할' · '무중이가 친구야' 설명과 예시를 바꾼다.
- `contracts/schemas/practice-scenario*.json`: 대본 판에 v1 · v2를 허용한다.
- `contracts/schemas/ai-judgment-request.schema.json`: 화자 `MUJOONG`.
- `contracts/timing.md`: 바꾸지 않는다(시간 값 변화 없음).

### 9.2 편차 (`contracts/deviations.md`)

| ID | 내용 |
|---|---|
| FT-1024-a | 연습 대화를 C안으로 바꾼다: 장면 속 친구 역할 · 역할 전환 없음, 장면 안 무중의 말은 모두 승인 낭독. 운영 기본 `flexibleDialogue=false` |
| FT-1024-b | 전하는 식 대답도 내용이 맞으면 수행으로 본다(`prompt-service-v4` · `rejudgment.v2`) |
| FT-1024-c | 장면 성공 뒤 칭찬 대신 대본에 미리 쓴 친구 반응을 무중이 전한다 |
| FT-1024-d | 도움은 장면마다 1회이고 첫 도움에 표현 예시를 넣는다. 다시 해 보기 1회(`retry-max` 1). main `RolePolicy`와 다르다 |
| FT-1024-e | 대본 판 v2(새 칸 다섯 개, `generation.v3` · 검토는 main `review.v1`), 들리는 선택 칸의 칸별 판정(걸린 칸만 빼고 발급), 칸이 없을 때의 상대 호칭별 대체 문장 |

### 9.3 기준 문서 개정 제안 (정본은 고치지 않는다)

`aidlc/spaces/default/knowledge/documents/` 원본은 그대로 둔다. 아래 제안을 팀이 승인하면 반영한다.

| RP | 대상 | 지금 → 제안 |
|---|---|---|
| RP-1 | AI동작명세 §1.1 VOICE-ROLE-001 | 한 세션이 친구 · 코치 역할을 겸함 → 무중은 끝까지 무중으로 말한다 |
| RP-2 | §2.3 연습 블록 | "역할놀이" → C안 블록(전하기 · 묻기) |
| RP-3 | §3.1 · §3.2 지시 표(D_SCENE_START ~ D_WRAP) | 친구 역할 · 생성 · 역할 전환 → 승인 낭독(3.2 표) |
| RP-4 | §4 START_PRACTICE | "무중이가 친구 역할을 맡을게" 삭제(실제 시드 005에는 이미 없음) |
| RP-5 | §4 · §8.5 역할 전환 표현 | 삭제 |
| RP-6 | §5.1 · §5.2 · §5.3 판정 정의 | 친구 말 · 코치 도움 → 무중 전하기 · 도움, 전하는 식 대답 인정 |
| RP-7 | §8.1 칸 목록 · §8.2 예시 대화 · §8.6 순서 | C안 칸과 예시(이 문서 2장) |
| RP-8 | §9.1 · §9.3 상황 생성 | "무중은 상대 역할" → 무중이 전하는 장면 |
| RP-9 | 화면 설계 v1.5j 문구표 예시 | 무중이 상대인 예시 → C안 예시 |
| RP-10 | 화면 설계 2안 설명 | "그대로 이어 가기 · 원래 상황" → "친구 반응 전하기" |

함께 닿는 아래 순위 문서는 기술명세서 v0.3 §3.4 · §4.3, 요구사항 FR-E06 · FR-E08이다. FR-E08 "도움은 실시간으로 만들고 정답 문장은 주지 않는다"와 C안은 다르다.

## 10. 시험

- **core**: 새 칸 검사(문장부호 · 길이 · 역할 말 · 답 누설 · 칭찬 · 꼬리), 대본 v2 계약 대조, 시드 006 등록(`ReferenceSeedMigrationIT`), `rejudgment.v2`.
- **realtime**:
  - C안 흐름 단위 시험: 2장의 갈래 3A~3E′, 변형, 옛 대본 대체.
  - 판정 판 값, 무응답 시계(R1), 끊긴 마무리, 도움 동의 기록.
  - 통합(`PracticeFlowIT`)에서 C안 끝-끝 갈래.
- **R-F2 대체**: 대본형 경로의 역할 · 코치 · 친구 마무리 단언(`PracticeConversationTest` 약 21건, `FlowTransitionsTest`의 역할 알림 · 코치 시험 등)은 설계로 동작이 바뀐다. 그래서 같은 갈래의 C안 단언으로 바꾼다.
  - 바꾼 시험과 그 자리를 지키는 새 시험은 커밋 본문과 이 문서 결과 장에 목록으로 남긴다.
  - 유연 대화 시험(`FlexiblePracticeConversationTest` 등)은 그대로 둔다.
  - 계약 대조 · ArchUnit 시험은 약하게 고치지 않는다.
- 바꾼 쪽마다 `./gradlew clean check -Pmujoong.test.db=docker`(core-api · realtime-activity)를 돌린다. 시험은 가짜 공급자만 쓴다(R-F1).

## 11. 5회차 실측 계획

- 우리 브랜치의 실측 장치(`PracticeVoiceDialogueLiveTest` 계열: 합성 아이 음성 · 판정 기록 · 전사 시각 기록)를 main 모양(`Services` · `Plan`)에 맞춰 옮긴다.
- C안 대본은 core 생성(`generation.v3`, 실제 Luna)으로 새로 만든다.
- 제브는 로컬 기본값인 대역(STUB)으로 둔다(7.3). 장면 안 말은 승인 낭독이라 제브를 건너뛰고, 대역은 장면 밖 생성형 말에만 걸린다. 실제 제브의 판정 · 지연은 이번에 재지 않는다.
- 실제 아동 데이터는 쓰지 않고 합성 아이 정보만 쓴다.
- 합성 아이 갈래: 바로 성공 · 전하는 식 대답 · 일부 → 되묻기 · 도움 요청 · 상황 모름 · 무응답 · 그만하기.
- 잴 것:
  - 장면 결과.
  - 승인 낭독 성공률(첫 시도 · 재시도 · 복구 · 실패).
  - 무중 차례마다 기다린 시간.
  - 전하는 식 대답의 J2 판정, 무응답 시계 오작동 수, 리포트 2항목 줄 이름.
- **증거 표기(R-M11)**: 실제 API(GPT-Live · Luna) + 합성 아이 음성 + 제브 대역. 실제 기기 · 실제 아이 · 실제 제브 성공으로 표시하지 않는다.
- **2단계 기준**: 승인 낭독이 첫 시도에 자주 실패하거나 무중 차례 대기가 길면 미리 만든 음성(TTS)을 옮긴다. 그전에 R-F15 확인이 필요하다.

## 12. 위험 · 남은 일

- **GPT-Live 승인 낭독의 신뢰도를 아직 재지 않았다.** 원문 읽기는 `instructions.append`라 발화가 보장되지 않는다. C안은 모든 말을 이 길로 보내므로 5회차의 첫째 확인 항목이다.
- **원문 대조가 엄격하다.** 문장부호 규칙(4.3)으로 줄이지만, 전사가 쉼표를 빼는 경우 등은 실측으로 확인한다.
- **팀 정렬.** 운영에서 도연 님의 유연 대화를 내리고 C안을 올린다. main에 합치기 전에 팀 합의가 필요하고, 그때까지 이 브랜치에만 둔다.
- **제브 의존.** 장면 밖 말(목표 안내 · 회고)이 생성형이라, 제브를 빼고 운영하려면 이 부분을 따로 정해야 한다(성민 님 결정 영역).
- **R-F15.** 실제 아동 데이터로 켜기 전에 Decisions API 끝점 확인 기록이 필요하다. 이것은 main RUNBOOK에 이미 미확인 항목으로 적혀 있다.

## 13. 구현 결과 (2026-10-24)

### 13.1 커밋

core와 realtime을 두 갈래로 나눠 동시에 구현하고 합쳤다. 합친 커밋은 `184b6bf7`이다. 그 뒤 계약 보충 `4b01e9c9`를 더했다.

| 갈래 | 커밋 | 내용 |
|---|---|---|
| 계약(먼저) | `d6c9dbda` | 스키마: 대본 새 칸 다섯 개 · 판 v2 · 화자 MUJOONG |
| core | `9d740f5a` | 대본 칸 · 들리는 칸 서버 검사(사유 코드 7개) · `generation.v3` · `review.v2`(뒤에 `c5c01f36`이 지우고 main `review.v1`로 되돌렸다) |
| core | `23865f10` | 시드 006(고정 승인 문장 6종) · 연습 허용 목록(`COACH_SWITCH` · `PEER_RETURN` 제외) |
| core | `02314f58` | 기록용 재판정 `rejudgment.v2`(전하는 식 대답 인정) |
| core | `bb48e719` | 계약 문장: `ai-io.md` 10.17(C안 절) · 편차 FT-1024-a ~ e |
| realtime | `411cd7a0` | 대본 판 v2의 새 칸 읽기 · 옛 대본 대체 문장 |
| realtime | `16ccd72c` | 대본형 경로를 C안으로(`DirectAskLines` · `FlowTransitions` C안 표 · 문구) |
| realtime | `238eeaa6` | `flexible-dialogue`(기본 false) · `retry-max` 1 · `judgment-service-v2` · `prompt-service-v4` |
| realtime | `ce756064` | 판정 입력의 무중 줄 화자 `MUJOONG` |
| realtime | `cc7792f9` | R1 무응답 시계(말 시작에서 멈추고 확정 전사를 기다림) |
| 계약 보충 | `4b01e9c9` | 10.17 구현 세부 · 무응답 시계 · 대체 규칙 · 기록, `timing.md` 무응답 줄 설명 |

그 뒤 5회차 core 실호출 대본 생성 하니스(`753b564a`, 합친 커밋 `3595e77d`)와 전하기 · 다시 설명 지시 고침(`0deb39a8`)을 더했다. 이 실측에서 C안 대본 발급이 main보다 줄어서(4.1) 아래 커밋으로 검토와 서버 검사를 바꿨다(발급 회복).

| 갈래 | 커밋 | 내용 |
|---|---|---|
| 발급 회복 core | `c5c01f36` | 검토를 main `review.v1`로 되돌리고 검토 입력에서 C안 칸 다섯 개를 뺌(`review.v2` 지움, 스키마 `selfReview` 판 v1), 들리는 선택 칸의 칸별 판정 · 발급 때 걸린 칸만 빼기 · 진단 `ScenarioField_<칸>_<사유>`, 새 칸별 사유 3개(`RELAY_LOW_FIDELITY` · `PARTNER_INTERPRETED` · `ASK_PROMPT_RELATION`), 스키마 예시 장면의 전하기, 로컬 진단 도구 허용 목록 |
| 발급 회복 core | `e92b2d6b` | core 실호출 하니스: 목표마다 뺀 칸 진단 코드와 합계(`strippedFields`), 검토 판 v1 표기 |
| 발급 회복 계약 | `b782d170` | `ai-io.md` 10.11 · 10.17(칸별 판정 · 짐작 말투 표 · 검토 · 상대 호칭별 대체), 편차 FT-1024-c · e |
| 발급 회복 realtime | `5072156e` | 뺀 칸 · 옛 대본 칸의 대체 문장을 상대 호칭별로(`DirectAskLines.Partner`), 칸 일부가 빠진 v2 대본 시험 |
| 발급 회복 문서 | 이 문서를 고친 커밋 | 4장(검토 · 서버 검사) · 5장 `ASK_DEFAULT` 행 · 7.4 · 9.2 · 13장 |

### 13.2 자동 시험

처음 구현을 합친 브랜치와 발급 회복 뒤(`5072156e`)에서 각각 `./gradlew clean check -Pmujoong.test.db=docker`를 돌렸다. 두 번 모두 BUILD SUCCESSFUL이다.

| 모듈 | 시험(합친 브랜치) | 시험(발급 회복 뒤) | 실패 · 오류 · 건너뜀 |
|---|---|---|---|
| contract-model | 394 | 394 | 0 |
| core-api | 1,883 | 1,905 | 0 |
| realtime-activity | 2,310 | 2,317 | 0 |
| test-support | 31 | 31 | 0 |
| 합계 | 4,618 | 4,647 | 0 |

- 증거 종류는 가짜 공급자 · 합성 대화다. 실제 API · 실제 기기 · 실제 아이 성공이 아니다(R-M11).
- 발급 회복에서 늘어난 시험: core `ScenarioRelayCheckTest`(새 12건) · `ScenarioGenerationTest` · `ScenarioDirectAskValidationTest` · `ScenarioLunaProviderTest` · `PracticePackageTest`, realtime `DirectAskLinesTest` · `DirectAskConversationTest` · `PracticeScriptReaderTest`. 실호출 하니스(`luna-live`)는 일반 `check`에서 돌지 않는다(컴파일만 확인).
- 자유대화 시험 `RealtimeLogHygieneIT.aProviderErrorMidTurn…`이 realtime 갈래 작업 중 두 번 실패하고 세 번째에 통과했다. 바탕 `d6c9dbda`에서도 같은 실패가 재현되므로 이번 변경과 무관한 시간 의존 흔들림이다. 합친 실행에서는 통과했다.

### 13.3 바꾼 시험 (R-F2)

지운 시험은 없다. 설계로 동작이 바뀐 단언은 같은 갈래의 새 동작 단언으로 바꿨다.

| 시험 | 바꾼 것 | 대신 지키는 것 |
|---|---|---|
| core `ScenarioLunaProviderTest` | 판 단언 `generation.v2` · `review.v1` → `v3` · `v2`(검토 판은 발급 회복에서 다시 `v1`, 아래 표) | 새 판 파일과 옛 판 파일이 함께 남아 있는지 보는 시험을 더함 |
| core `ScenarioApiIT` 2건 | `scenario-script.v1` → `v2` | `ScenarioGenerationTest`에 판 기록 시험을 더함 |
| core `ApprovedSpeechAssemblyTest` | 연습 code 5종 → 9종(역할 전환 code 없음) | 시드 006이 없는 DB 시험을 더함 |
| core `DevSeedRunnerStatementsTest` · `LiveContentDevSeedRunnerIT` | dev 시드 행 5개 → 11개 | 시각까지 값으로 단언 |
| core `RejudgmentGatewayTest` | 대역이 돌려주는 판 문자열 v1 → v2 | — |
| realtime `PracticeConversationTest` | 대본형 역할 · 코치 · 친구 답변 단언 26건 → 같은 갈래의 C안 단언 | — |
| (새) realtime `FlexibleRoleNoticeTest` | 역할 전환 · 코치 두 회차 · 복귀 알림 · 알림 안전 시계 · 생성형 알림 · 막힌 알림 단언을 유연 대화 경로로 옮김 | 같은 단언 그대로 |
| realtime `PracticeProgressMappingTest` · `PracticeFlowRecordTest` · `PracticeFlowRouterTest` | 준비 단계(흐름 단계)만 C안으로 | 단언 내용 그대로 |
| realtime `PracticeFlowIT` | 대본형 2건 → C안 끝-끝 2건 | — |
| realtime `PracticeFlowSettingsTest` | 이름 변경, 10.14 표 · yml · 설정 레코드 대조를 새 키까지 넓힘 | — |

- `FlowTransitionsTest`(유연 대화 · 공통 전이)는 손대지 않았다.
- 새 시험: C안 전이표 · 문장 · 대화(`DirectAskLinesTest` 등), 판정 판 · 화자, R1(`PracticeChildSpeechTest` · `ChildSpeechStartExecutorTest`), 서버 검사 사유, 시드 006.

**발급 회복(`c5c01f36` ~ `5072156e`)에서 바꾼 시험.** 지운 시험은 없다. C안 칸 문제로 장면이 거절된다고 단언하던 시험은 "그 칸만 빠지고 장면은 발급된다"는 새 동작 단언으로 바꿨다(약하게 한 단언 없음). 계약 대조 · ArchUnit 시험은 손대지 않았다.

| 시험 | 바꾼 것(지운 단언 → 새 단언) | 대신 지키는 것 |
|---|---|---|
| core `ScenarioDirectAskValidationTest` | C안 칸 사유마다 장면 실패(`INVALID_BASE` · 변형은 `INVALID_VARIANT`) → 장면은 `VALID`이고 그 칸에만 같은 사유(문장부호 · 길이 · 문장 수 · 제어 문자 · 역할 말 · 누설 · 압박 · 금지 · 복사 · 묻기 모양 · 꼬리 · 칭찬 · 물음 끝) | 같은 사유 코드를 칸별로 단언하고 다른 칸은 걸리지 않음을 함께 본다. 사유 여럿의 계약 순서 · 칸 빼기(`withoutFields`) · 진단 도구 허용 목록 시험을 더함 |
| 〃 시험 문장 | 기본 전하기 문장(짐작 말투 '쉬고 싶어 한대'가 있던 것) · '역할 말이 아닌 무중' 예(상대 말과 겹치지 않음)를 상대 말의 간접 인용으로, 또래 장면의 '선생님께 말씀드릴래?' 통과 예를 '짝꿍한테'로 | 새 충실도 · 짐작 말투 · 묻기 관계 검사에 걸리지 않는 정상 예로 같은 갈래를 본다. 선생님 높임말 통과는 `ScenarioRelayCheckTest`의 선생님 장면이 지킨다 |
| core `ScenarioValidationTest` 2건 | 상황 안내의 누설 · 압박 · 이름 · 조건 꼬리 · 길이 · 제어 문자 → `INVALID_BASE` → 장면 `VALID`, `spokenIntroduction` 칸 사유 | 값마다 사유를 단언(빈 값을 더함) |
| core `ScenarioGenerationTest` 3건 | 판 기록 시험: `scenario-review.v2` → `v1` / 검토 입력 시험: '검토도 새 칸을 본다' → '검토는 C안 칸을 보지 않는다(상황 안내는 본다)' / `aDirectAskRuleFailureIsDiagnosedAndCorrectedLikeOtherServerChecks`: 'C안 칸 실패는 진단 `ScenarioRule_*` + 보완' → '첫 시도(호출 2번)에 그 칸만 빼고 발급, 진단 `ScenarioField_*`, 수정 요청 없음'(`aDirectAskFieldProblemIsIssuedOnTheFirstAttemptWithOnlyThatFieldRemoved`) | 검토 입력 JSON · 공급자 입력에 키가 없음, 필수 칸 문제는 그대로 장면 실패 · 보완, 변형 장면 칸 빼기, 사유 종류별 빼기 시험을 더함 |
| core `ScenarioLunaProviderTest` | 검토 판 · 지시 `v2` → main `v1` | 검토 지시에 C안 칸 낱말이 없음, `review.v2.txt`가 없음, 계약 예시 장면이 칸별 검사를 모두 통과함을 더함 |
| core `PracticePackageTest` | 검토 판 문자열 `v2` → `v1`(계약 스키마 고정 값) | 칸 일부를 뺀 v2 장면이 시작 패키지에 그 키 없이 가는 시험을 더함 |
| realtime `PracticeScriptReaderTest` · 시험 자료 | 검토 판 `v2` → `v1` | 칸 일부가 빠진 v2 대본 읽기 시험을 더함 |
| core `PracticeQualityLiveTest`(실호출 하니스, 일반 `check`에서 안 돈다) | C안 사유 묶음(`directAsk`) 집계 → 뺀 칸 진단 집계(`strippedFields`) | 단언이 아니라 기록이다 |

### 13.4 구현하면서 정한 것 (설계와 다르거나 설계에 없던 것)

| # | 내용 | 까닭 |
|---|---|---|
| 1 | 서버 길이 상한은 계약 스키마를 따른다(`helpSpeech` 300자, `askPrompt` 100자 · 1문장) | 스키마가 받는 값을 서버가 거절하지 않게 |
| 2 | 뜻에 관한 규칙(전하기의 단서 · 호칭과 높임말 · 예시의 뜻 · 반응이 대답을 가정하지 않음)은 생성 · 검토 지시로만 막는다. 서버는 모양만 본다(발급 회복에서 바뀜: 검토는 C안 칸을 보지 않고, 서버가 전하기 충실도 · 짐작 말투 · 선생님 호칭을 본다, #10 · #13 ~ #15) | 서버가 판단할 수 없는 의미 규칙 |
| 3 | 칭찬 거절 서버 사유는 `REACTION_PRAISE`, 검토 사유는 `PRESSURE`. 도움 꼬리 대조는 공백을 무시한다(발급 회복 뒤 검토는 반응 칸을 보지 않는다, #10) | 원문 대조와 같은 규칙 |
| 4 | 도움을 받고 해낸 장면(`AFTER_HELP`)에도 변형을 권한다. 집중 시계 만료만 변형 생략 사유다 | `retry-max` 1이면 기존 표가 변형을 막아서 |
| 5 | REASK 무응답 1회는 `WAIT_LEAD` + `reaskPrompt`. 도움 · 변형 제안의 무응답 · 불명확은 같은 제안 문장을 다시 읽는다 | 설계 3.2에 없던 갈래 |
| 6 | `SCENE_CLOSE`는 상황 안내를 들려준 장면에서만 낸다. 끊긴 마무리 문장은 전달된 것으로, 재생 실패한 마무리 문장은 `OUTPUT_FAILED`로 본다. 마무리 도중의 종료 · 실패는 이룬 시점을 지우지 않는다 | 기록 정확성 |
| 7 | 옛 대본 대체: `focusPoints`가 없으면 `reexplanation`, 예시가 없으면 예시 없이 쓴다. 이어 붙이는 옛 칸은 문장부호를 정리한다 | 원문 대조 실패를 줄이려고 |
| 8 | 목표 안내 · 회고 · 돌아보기를 C안에서도 낸다 | 설계 7.3 '장면 밖 말은 main 그대로'를 운영 동작으로 읽음 |
| 9 | 도움 말 기록 행의 역할 값은 `COACH` 그대로다 | V7 CHECK 제약 안, 화면은 '무중'으로만 보임 |

발급 회복(13.1 아래 표)에서 정한 것:

| # | 내용 | 까닭 |
|---|---|---|
| 10 | 검토는 main `review.v1`이고 검토 입력에서 C안 칸 다섯 개를 뺀다(상황 안내는 남긴다). `review.v2`는 출시 전이라 지웠다. C안 칸의 뜻은 생성 지시와 서버 칸별 판정이 지킨다 | 5회차 대본 생성 실측(실제 API + 합성 입력)에서 C안 칸까지 본 검토가 거절을 늘려 발급이 main보다 줄었다(main 3/6, C안 2/6 · 0/6). 그동안 C안 칸 서버 검사는 0건이었다 |
| 11 | 들리는 선택 칸(상황 안내 + C안 칸 다섯 개)은 칸별 판정이다. 걸린 칸만 발급 직전에 빼고(null) 장면은 발급한다. 뺀 칸은 진단 `ScenarioField_<칸>_<사유>`로만 남기고 수정 요청을 만들지 않는다. 생성 루프 안의 비교 · 수정 요청은 원래 대본이다 | 들리는 칸 하나 때문에 장면을 버리지 않는다. 빠진 칸은 realtime 대체 문장(7.4)이 메운다 |
| 12 | 상황 안내도 칸별 판정으로 옮겼다(main에서는 장면 실패였다). 규칙은 main 그대로다 | 같은 들리는 선택 칸이고 대체 안내 문장이 있다 |
| 13 | 전하기 충실도: 상대 말의 서로 다른 두 글자 조각 가운데 전하기에 있는 비율이 0.5(`RELAY_BIGRAM_RATIO_MIN`) 이상이면 통과다. 분모는 상대 말이고, 조각이 2개 미만인 짧은 말은 통째로 들어 있어야 한다 | 전하기 앞에 장면 맥락 문장이 붙어도 비율이 떨어지지 않게 한다. 문턱은 5회차 기록 장면(실제 API + 합성 입력)에 대어 보고 정했다 |
| 14 | 짐작 말투는 낱말 줄기로 면제한다(상대 말에 그 줄기가 있으면 통과). 줄어든 꼴('궁금해한대' · '싶어 한대')도 잡는다. '-나 봐' · '-ㄴ가 봐'는 어간에 붙은 꼴만 보고 '-나다' 동사 + '-아 보다'('만나 봐' · '일어나 봐')는 뺀다 | 상대가 직접 한 말을 전하는 것은 해석이 아니다. 정상 문장을 거르지 않게 한다 |
| 15 | 묻기 관계 검사는 '선생님' 낱말만 본다(선생님 장면 ⇔ '선생님'이 있음) | 다른 관계는 이름이 자유 글이라 서버가 맞는 호칭 낱말을 정할 수 없다 |
| 16 | 대체 문장의 호칭은 `TEACHER` 높임말 · `CLUB_SENIOR` 선배 · `CLUB_JUNIOR` 동생 · `FAMILY` 호칭 없음 · 그 밖과 모르는 값은 친구다. 코드가 아닌 관계 이름은 낱말로 고른다: '선생님' · '교사' → 선생님, '선배'나 '동아리' + 형 · 누나 · 언니 · 오빠 → 선배, '후배' · '동생' → 동생, 가족 낱말이나 동아리 밖의 형 · 누나 · 언니 · 오빠 → 호칭 없음 | 칸 빼기로 대체 문장이 자주 들린다. core `graphRelation`은 정해진 이름만 그래프 코드로 바꾸므로 relation에 '동아리 형' 같은 이름이 그대로 온다(5회차 S10). 동아리 밖의 손윗사람 호칭은 가족일 수도 있어 호칭을 붙이지 않는 문장이 안전하다 |
| 17 | 친구가 아닌 호칭의 묻기 대체는 시드 승인 문장이 아니라 realtime 상수다(시드 `ASK_DEFAULT_TEACHER` 같은 행을 더하지 않았다) | 장면 안 승인 낭독은 대본 ID로만 기록하고 고정 문장 코드 · `contentVersionId`로 기록하지 않는다. 승인 원문 대조도 문장 자체로 한다 |

모두 `contracts/ai-io.md` 10.17에 적었다.

### 13.5 다음

- 발급 회복 뒤 core 실호출 대본 생성을 다시 재서 목표별 발급 수와 뺀 칸 진단(`strippedFields`)을 main과 견준다. 뺀 칸이 잦은 칸 · 사유는 생성 지시로 줄인다(대체 문장은 대본 칸보다 덜 자연스럽다).
- 실측 장치 이식: core 실호출 대본 생성(실제 Luna로 C안 대본 → 시작 패키지)과 realtime 실음성 연습 대화(실제 GPT-Live · Luna 판정 + 합성 아이 음성 + 제브 대역). 옛 브랜치 장치를 main 구조에 맞춰 옮긴다.
- 그다음 5회차 실측(11장 계획)을 돌리고 결과를 14장에 적는다.
