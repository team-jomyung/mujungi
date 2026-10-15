# 상호작용 명세

> 형식: `.claude/knowledge/aidlc-design-agent/component-spec-template.md`. 시안은 `mockups.md`, 근거 스토리 ID는 각 절에 적었다. 기존 컴포넌트를 고치는 범위이고 새 컴포넌트는 만들지 않는다.

---

## DeviceStatusView (T19 아이 기기 상태 화면)

| Field | Value |
|---|---|
| Component | DeviceStatusView |
| Description | 아이 기기의 현재 상태를 캐릭터 표정과 글자로 보인다 |
| Category | display / feedback |

### States

| State | Description | Trigger |
|---|---|---|
| idle (대기) | 활동 없음 | 켜기 완료, 마무리 끝 |
| listening (듣는 중) | 아이 말을 듣는 중 | 무중이 말 끝, 재안내 뒤 |
| thinking (생각 중) | 판정 · 지시 · 안전 검사 대기 | 아이 말 끝. **판정 턴의 버린 출력 · 제브 차단 · 재시도 중에도 유지**(AC1.10.4 · AC2.1.3) |
| speaking (말하는 중) | 게이트 · 제브를 통과한 음성 재생 중 | 승인된 PCM 재생 시작 |
| closing (마무리) | 끝 인사 | 종료 처리 |
| disconnected (연결 끊김) | 다시 연결 중 | 연결 끊김 |
| mic-denied | 마이크 허용 안 됨 | 허용 거부 |
| off (꺼짐) | 꺼짐 | 끄기 |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| state | enum(위 상태) | yes | off | 서버가 보내는 기기 상태(`SESSION_STATE` · `View`) |
| outputPending | boolean | no | false | 서버 쪽에서 판정 · 지시 · 제브 결과를 기다리는 중이면 true. 이때 버린 출력 뒤에도 listening으로 바꾸지 않는다 |

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| mobile / tablet | 기존 v1.5j 레이아웃 그대로(전체 화면 캐릭터 + 상태 글자) |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `status` (상태 글자 영역) |
| Keyboard interaction | 켜기 · 끄기 단추만 Tab · Enter · Space |
| Label | 상태 글자를 보이는 텍스트로 둠(표정 그림은 장식, `alt=""`) |
| Contrast | 상태 글자 4.5:1 |
| Screen reader | 상태가 바뀔 때 `aria-live="polite"`로 상태 글자 안내 |
| Non-color | 상태를 색만으로 구분하지 않음 — 표정 + 글자 |

### 동작 규칙

1. 서버가 판정 턴에 GPT-Live 출력을 버리면, 보낼 응답이 없어도 기기 상태를 `thinking`으로 유지한다(지금 `SessionExecutor.listeningAgain()`이 `listening`으로 돌리는 경로를 이 경우에서 제외).
2. 제브 `BLOCK` · 시간 초과 뒤 같은 지시를 1회 재시도하는 동안에도 `thinking`이다. 재시도도 막히면 장면 정리 지시의 응답이 재생될 때 `speaking`으로 간다.
3. 종료 표현을 알아들으면 판정 · 검사를 기다리지 않고 `closing`으로 간다.

---

## ActivityTranscriptBubbles (12 활동 진행 말풍선 · 27 대화 기록 · PDF)

| Field | Value |
|---|---|
| Component | ActivityTranscriptBubbles |
| Description | 아이와 무중이가 주고받은 말을 시간순 말풍선으로 보인다 |
| Category | display |

### States

| State | Description | Trigger |
|---|---|---|
| live | 12에서 실시간 중계 | 활동 진행 중 |
| record | 27 · PDF의 저장된 기록 | 활동 끝 |
| gap | 끊김 점선 | 연결 끊김 구간 |
| empty | 말풍선 없음 | 시작 직후 |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| utterances | list(speaker, text, at) | yes | [] | **아이가 실제로 들은 무중이 말**과 아이 말만. 서버가 걸러서 보냄 |

### 동작 규칙 (AC1.10.1 · AC2.1.9)

- 무중이 말풍선은 기기에서 재생이 시작된 구간부터 보인다(`ALLOW` 뒤 재생 시작 이벤트 기준).
- 다음은 중계 · 기록 · PDF 어디에도 넣지 않는다. 걸러 내는 일은 서버 쪽에서 하고, 화면은 받은 목록만 그린다.
  - 판정 턴에 버린 출력
  - 제브가 막은 발화
  - 무효가 된 `ALLOW`의 발화

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | `log` (12 실시간), 목록(27) |
| Screen reader | 12는 `aria-live="polite"`로 새 말풍선 안내, 화자(아이/무중)를 글자로 앞에 붙임 |
| Non-color | 화자를 말풍선 색만으로 구분하지 않고 글자 · 위치로도 구분 |
| Contrast | 4.5:1 |

---

## EndReasonLine (17 활동 끝 · 종료 사유 줄)

| Field | Value |
|---|---|
| Component | EndReasonLine |
| Description | 활동이 왜 끝났는지 한 줄로 보인다 |
| Category | display |

### States

| State | Description | Trigger |
|---|---|---|
| child-ended-matched | 등록 표현 일치로 끝남 | 종료 표현 일치 |
| child-ended-judged | Luna 판정(J6 '종료 명확')으로 끝남 | 판정 |
| guardian-ended · auto-ended · disconnected · device-no-response | 기존 사유 | 기존 |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| reason | enum | yes | — | 종료 사유 |
| matchedPhrase | string? | no | null | child-ended-matched일 때만 채워짐 |
| endedAt | time | yes | — | 끝난 시각 |

### 동작 규칙 (AC1.7.5, RP-3)

- `child-ended-matched`면 C.S-17.72.4 + C.S-17.72.5(`"<matchedPhrase>" · <시각>`)를 보인다.
- `child-ended-judged`면 C.S-17.72.4 + 시각만 보인다(RP-3 C.S-17.72.5b). 판정한 아이 말을 따옴표로 보이지 않는다. 서버는 이 경우 `matchedPhrase`를 보내지 않는다.

### Accessibility

- 사유는 글자로 보인다. 아이콘만 쓰지 않는다. 대비 4.5:1.

---

## GoalCandidateCard (27 연습 목표 칸)

| Field | Value |
|---|---|
| Component | GoalCandidateCard |
| Description | Luna가 아이 기록에서 만든 연습 목표 후보를 근거와 함께 보이고 담게 한다 |
| Category | display / input |

### States

| State | Description | Trigger |
|---|---|---|
| default | 후보 + [담기] | 후보 있음 |
| added | [담김 ✓] | [담기] 누름 |
| empty | 후보 없음 | 검증 뒤 남은 후보 0 |
| loading | 정리 중 | 결과 작업 진행 중 |
| error | 정리 실패 · 다시 시도 | 결과 작업 실패 |

### Props / Inputs

| Prop | Type | Required | Default | Description |
|---|---|---|---|---|
| goalName | string(≤12자) | yes | — | 목표 이름(목표 행동 표현) |
| evidenceSummary | string(≤40자) | yes | — | 화면용 근거 문구(관찰 요약). 금지 표현 검사 통과분 |
| evidenceAt | time | yes | — | 근거 시각 |
| added | boolean | yes | false | 담김 여부 |
| (내부) reason · 4요소 · skillRef · evidenceUtterance | — | — | — | **화면에 보내지 않음** |

### 동작 규칙 (AC4.5.5~AC4.5.7, AC4.6.1)

- 후보는 최대 3개다. 탭 글자 "연습 목표 n"의 n은 후보 수다.
- [담기]를 누르면 18에 쌓이고 [담김 ✓]로 바뀐다(화면 이동 없음). 담은 목표는 그 후보의 버전을 가리킨다.
- [담김 ✓]를 다시 누르면 담기가 취소된다(v1.5j 분기 그대로).

### Responsive Behaviour

| Breakpoint | Behaviour |
|---|---|
| mobile | 목표 이름 · 담기 단추 한 줄, 근거 문구는 다음 줄(줄바꿈 허용, 말줄임 없음 — 40자 한도로 보장) |

### Accessibility

| Requirement | Implementation |
|---|---|
| ARIA role | 목록 항목 + `button`(담기) |
| Keyboard | Tab으로 [담기], Enter/Space로 토글 |
| Label | 단추 `aria-label="<목표 이름> 담기"` / `"<목표 이름> 담김, 누르면 취소"` |
| Touch target | 44×44px 이상 |
| Contrast | 4.5:1(글자), 3:1(단추 테두리) |
| Screen reader | 담기 토글 뒤 `aria-pressed` 갱신 |

---

## SceneChip (27 장면 칩 — 표시 유지, 값만 확장)

| Field | Value |
|---|---|
| Component | SceneChip (기존) |
| Description | 장면 결과 칩. **이번 작업에서 27 표시는 바꾸지 않는다**(Q5-A) |
| Category | display |

### 동작 규칙

- API · 리포트 집계에 `AFTER_REASK`('되묻기 뒤 전달') 값을 더한다(AC4.1.1).
- 27에 새 값이 그려지지 않도록 다음 중 하나를 Functional Design에서 정한다(mockups 2.5). 정하기 전까지 27의 지금 표시는 바뀌지 않아야 한다.
  - (a) 표시용 매핑에서 기존 값으로 접는다.
  - (b) 27에서 칩을 숨긴다.
- 칩 글이 비었거나 금지 표현에 걸리면 '전달'을 쓴다. 칩을 그리는 곳에서는 색만으로 구분하지 않는다(AC4.1.5).

---

## 아이 음성 상호작용 (화면 없음) — 요약

| 순간 | 서버 동작 | 아이가 듣는 것 | 기기 상태 |
|---|---|---|---|
| 판정 턴 | 출력 게이트 닫음, 자동 응답 버림 | 없음 | 생각 중 |
| 판정 보류 | 교정 없는 확인 · 맞장구 지시 | "다시 말해 줄래?" 등 | 생각 중 → 말하는 중 |
| 제브 차단 | 같은 지시 1회 재시도 → 또 막히면 장면 정리 | 없음 → 다음 단계 말 | 생각 중 |
| 코치 전환 · 복귀 | 알림 지시(B §8.5), 알림도 제브 검사 | "잠깐, 무중이가 도와줄게" / "다시 친구 역할로 돌아갈게" | 말하는 중 |
| 휴식 요청 | 받아 주기 → 다시 묻기 | `D_REST_ACK` → `D_REPROMPT` | 말하는 중 → 듣는 중 |
| 종료 표현 | 즉시 종료 | 승인 마무리 문구 | 마무리 |
