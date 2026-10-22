# U11 판정 턴 기반 — 기능 설계 (구현된 모습)

> 작업 기록 `261016-u11-judgment-turn`의 승인된 기능 설계(`functional-design/`)와 선 형식 설계 개정(`code-generation/design-addendum-wire-format.md`, 실호출 3회 반영)을 합쳐 **코드에 들어간 모습**으로 옮긴 문서다.
> 승인 문서와 개정이 다르면 이 문서는 개정을 따른다. 실호출 증거는 `aidlc/.../code-generation/real-call-evidence.md`에 있다.
> 번호 체계: **설계 편차 `U11-D<n>`**(이 문서), **계약 편차 `(U11-<글자>)`**(`contracts/deviations.md`). 둘은 섞지 않는다.

## 1. 범위와 묶음

| 묶음 | 내용 | 상태 |
|---|---|---|
| PR1 선 형식 정비 · 키 통일 | 실제 GPT-Live 형식으로 세션 시작 · 지시 · 수신 해석, 응답 id 대신 서버 **출력 세대**, 모든 서버 발화를 지시 하나로, 키 `OPENAI_API_KEY` 통일, realtime Luna 스위치 | main 병합(PR #28) |
| PR2 판정 턴 | 실행기에 판정 턴을 더한다. J1(참여 확인) · J6(종료 의도)를 실행기가 열고 J2 ~ J5는 확장이 요청한다 | 작업 중 |

공급자가 없으면(키 없음 · 스위치 꺼짐) 판정 턴을 열지 않고 U3 · U4b · U5b의 지금 흐름이 그대로 돈다(BR1.6).

## 2. 구성 요소

| 구성 요소 | 위치 | 책임 |
|---|---|---|
| `OutputGeneration` | `gate/` | 출력 세대 번호 · RECEIVING / DISCARDING / GAP_CONFIRMED, 오디오 시계, 지시 확인(`ackEndMs`) · 새 말 시작 판단 |
| `ProviderSendQueue` | `session/` | 세션당 직렬 큐 하나가 `append` 순서를 지킨다 |
| `InstructionTemplates` | `dialog/` | 모든 지시문 템플릿: 맞장구로 시작, "이번 한 번만 …"으로 범위를 닫음. `NextAction` 셋은 인자가 없다 |
| `JudgeClient` · `LunaJudgeClient` · `UnavailableJudgeClient` | `judgment/` | 판정 포트. 실패도 예외 없이 보류 결과로 완료 |
| `JudgeOutcomeValidator` | `judgment/` | 확률 범위 → 선택지 안 → 중복 → 0.6 → 0.15 순 검증(십진 비교, 경계는 확정) |
| `JudgmentPrompt` · `prompt-v1.txt` | `judgment/` | 판정 지시 본문(데이터는 지시가 아님)과 지점별 선택지 뜻(판 리소스에서 이어 붙임), `promptVersion = prompt-v1` |
| `JudgmentTurn` | `judgment/` | 판정 턴 한 번의 수명(상태 기계 2.2). 실행기 · 게이트 · 기기 게이트웨이를 모른다(ArchUnit) |
| `JudgmentCoordinator` · `JudgmentSupport` | `session/` | 열린 턴 하나 · 최근 확정 전사 4개 · 기록 쓰기, 실행기와는 `Env`로만 닿음 |
| `JudgmentLive*` | `judgment/` | 판정 기록 저장소 · 비동기 기록기 · 삭제 참여자(순서 180), 표 `realtime_judgment_live`(realtime V5) |

## 3. 상태 기계

### 3.1 출력 세대 (`OutputGeneration`, 구현된 모습)

세션당 하나, 번호는 올리기만 한다.

| 단계 | 뜻 |
|---|---|
| RECEIVING | 조각을 구간으로 받는다. 마지막 조각 뒤 `segment-quiet`(800ms)가 지나면 구간을 '완전'으로 봉인해 게이트에 넣는다 |
| DISCARDING | 세대를 올린 직후. 조각은 모두 버린다 |
| GAP_CONFIRMED | **지시 없이** 세대만 올린 경우(끼어들기 · 종료 의도 · 연습 폐기 · 게이트 폐기 · 보류 해제)의 끊김 확인: 마지막 조각 · 세대 올림 중 늦은 쪽에서 800ms 조용하면 다음 조각부터 받는다 |

**지시를 보낸 세대**(설계 개정 A4)는 끊김 대신 전사 시간선을 쓴다.
1. `append`를 큐에 넣는 순간 세대를 올리고 DISCARDING으로 둔다. 열린 구간은 불완전으로 버린다.
2. `session.instructions.appended`(`client_event_id` 일치)가 오면 `ackEndMs = end_ms`.
3. 그 뒤 처음 오는 출력 전사 조각 중 `start_ms ≥ ackEndMs`가 새 말의 시작(`newStartMs`).
4. 오디오 시계(누적 바이트 ÷ 48 = ms)가 `newStartMs + new-speech-margin`(700ms)에 닿는 첫 음성 조각부터 RECEIVING. 낡은 꼬리가 새는 것보다 첫 낱말이 잘리는 쪽을 택한다(fail-closed).
5. 확인 사건이 `response-ready`(10초) 안에 안 오면 사유 `ACK_MISSING`, 새 말 전사가 없으면 `NEW_SPEECH_NOT_OBSERVED`로 닫는다(아이에게는 아무 말도 들리지 않는다 = 안전 쪽 실패).

### 3.2 판정 턴 (`JudgmentTurn`)

세션당 열린 턴은 최대 1개. 상태 `AWAITING_RESULT → INSTRUCTING → CLOSED`.

| 지금 | 사건 | 다음 | 동작 |
|---|---|---|---|
| (없음) | 열기 | AWAITING_RESULT | 공급자 있음 · 입력 판독 가능 · 열린 턴 없음 · WAITING_CHILD 또는 ACTIVE. 출력 세대 올림(JUDGMENT_TURN), ACTIVE면 THINKING, 판정 요청, 시계 `JUDGMENT_LIVE`(열기 + 4s) · `JUDGMENT_READY`(확정 전사 시각 + 10s) |
| AWAITING | 출력 조각 | 그대로 | 음성 버림, 전사 조각만 모음(2000자) — 구간 · 대화 기록 · 보호자 중계에 넣지 않음 |
| AWAITING / INSTRUCTING | 아이 확정 전사 | CLOSED | 종료 표현이면 STOPPED(종료 처리), 아니면 SUPERSEDED로 닫고 새 전사를 처음부터 처리. 확장 소유 판정 단계는 턴을 그대로 두고 확장에 전달(설계 검토 R-04) |
| AWAITING | 결과 도착(일반 편지함) | INSTRUCTING 또는 CLOSED | 차례 id 일치 · 상태 일치일 때만. 지점별 분기가 지시 여부를 정함 |
| AWAITING | `JUDGMENT_LIVE` 만기 | 같음 | **정확히 4.000초 포함** 보류(TIMEOUT), 요청 취소. 같은 틱의 결과는 편지함에 먼저 들어온 쪽이 이김 |
| AWAITING | `JUDGMENT_READY` 만기 | 같음 | 보류(RESPONSE_NOT_READY). 턴이 확정 전사보다 6초 넘게 늦게 열린 경우에만 4초보다 먼저 만기 |
| INSTRUCTING | 진입 | — | READY · LIVE 해제, `JUDGMENT_INSTRUCTION`(진입 + 10s), 지시 `append` 하나를 직렬 큐로 |
| INSTRUCTING | 확인 사건 | — | `onInstructionAcked` |
| INSTRUCTING | 새 말의 첫 조각 | CLOSED | 이 조각부터 지시 응답이 평소 경로(게이트)로 간다. 보기는 복원하지 않는다 |
| INSTRUCTING | `JUDGMENT_INSTRUCTION` 만기 · 새 말 못 들음 | CLOSED | 확인 전이면 `ACK_MISSING`, 후면 `NEW_SPEECH_NOT_OBSERVED`. 지시를 다시 내지 않는다 |
| AWAITING / INSTRUCTING | 공급자 끊김 · 기술 일시 정지 | CLOSED | FAULT_CANCELLED |
| AWAITING / INSTRUCTING | 세션 상태가 연 상태를 벗어남 | CLOSED | SESSION_EXITED (`changeStatus` 훅, R-15) |
| 어느 상태든 | 낡은 차례 id · 닫힌 뒤 사건 | 그대로 | 버림, 사유 코드 `JUDGMENT_LATE_DROPPED`만 기록 |
| CLOSED | (닫힐 때) | — | 시계 셋 해제, 판정 요청 취소, 판정 기록 1건 비동기 쓰기, 보기 복원(ACTIVE · 재생 없음 · 응답 시작 아님), 미룬 참여 창 tick |

닫힘 보고는 한 번뿐이다. 판정 중 `listeningAgain()`은 건너뛴다.

## 4. 흐름

- **W1 참여 확인 J1**: WAITING_CHILD에서 확정 전사가 오면 `JoinTracker.classify`. ACCEPT · REFUSE · TOPIC_REFUSAL이면 판정 없이 지금처럼. **UNCLEAR만** J1(trigger `JOIN_UNCLEAR`)을 연다. 결과를 PARTICIPATE→ACCEPT, DECLINE→REFUSE, 그 밖(REST · UNCLEAR · UNREADABLE · 모든 보류)→UNCLEAR로 바꿔 `join.reply(답, 원래 확정 시각)`에 **한 번**만 넣는다. 재안내 횟수는 이 한 번으로만 센다. J1이 열린 동안 `JOIN_WINDOW` 만기는 표시만 하고 닫힌 뒤 tick. 휴식 요청에는 받아 주는 말이 없다(U11-D6). 보기는 바뀌지 않는다.
- **W2 종료 의도 J6**: 등록 표현(STOP)은 판정 없이 바로 종료(BR6.1). ACTIVE에서 `StopIntentJudge`가 CONFIRM이면 출력권 연습의 시계를 멈추고 연습 발화를 끊고 `stopOutputForIntent`한 뒤 **`stopConfirm.onJudgment`를 부르지 않고** J6를 연다.

| J6 결과 | 다음 행동 | 세션 사건 | 기록 nextAction |
|---|---|---|---|
| END_CLEAR | `beginClosing(CHILD_STOP_CONFIRMED)` | `CHILD_STOP_INTENT{basis: LUNA_J6}` | CLOSE |
| REST | ACTIVE에서 지시 하나: 출력권 연습이면 `REST_ACK_ONLY` + `REST_REQUESTED` 확장 사건 + 연습 시계 재개, 아니면 `REST_ACK_AND_RETURN` | 없음 | REST_ACK_… |
| NOT_END | 이어 가기: 출력권 연습이면 시계 재개 + (R-13) 가려 둔 전사를 `onChildUtterance`로, 아니면 이어 가기 지시(`requestResponse(CONTINUE)`) | 없음 | RESUME |
| UNCLEAR · 모든 보류 | 이때 `stopConfirm.onJudgment(CONFIRM)` → 지금의 확인 질문 | `STOP_CONFIRM_ASKED` … `RESOLVED` | ASK_STOP_CONFIRM |
| FAULT_CANCELLED | 연습 시계 재개 + (R-13) 전사 넘기기, 응답 요청은 하지 않음 | 없음 | NONE |
| SUPERSEDED | (R-14) 새 전사가 다시 CONFIRM이면 멈춤 유지(새 J6가 이어 받음), 아니면 연습 시계 재개 | — | — |

- **W3 판정 뒤 지시**: 모든 서버 발화는 `append` 하나다(송신 type 네 가지, `response.create` 없음). 지시문은 인자 없는 고정 문장이고 "먼저 '음,'이라고 짧게 말한 뒤 … 이번 한 번만 위 지시를 따르고, 그 뒤에는 평소처럼 …"로 닫는다. 지시문에 판정 라벨 · 확률 · 기준 · 아이 원문이 들어갈 길이 없다.
- **W4 확장이 요청한 판정 J2 ~ J5**: `PracticeOutput.requestJudgment(point, transcriptSeq, correlationId, criteria)`. ACTIVE가 아니면 `PRACTICE_EXTENSION_FAILED{NOT_ACTIVE, capability}`로 거절. 열린 턴 · J1 / J6 · 최근 확정 전사 4개 밖 · 기준 모양 틀림 · 공급자 없음이면 `false`. 소유자는 확장이라 실행부는 되묻기를 넣지 않는다. 결과 사건 `JUDGMENT_RESULT`(선택지 코드 둘, **확률 없음**) / `JUDGMENT_ABSTAINED`(사유, 장애는 `FAULT`. LOW_CONFIDENCE · LOW_MARGIN이면 확정 안 된 1순위 코드 `leadingOption`도 싣는다 — 확률 없음, docs/practice-live-fixes.md) / `HELD_OUTPUT_TRANSCRIPT`(비어 있지 않을 때). 최근 전사는 실행기 메모리에만 두고 로그에 쓰지 않는다. `announceRoleSwitch`는 자리와 거절 규칙만, `setThinking`은 생각 중 유지.
- **W5 시계**: LIVE 4s(열기부터), READY 10s(확정 전사 시각부터, 4초 포함, 판정 단계에만), INSTRUCTION 10s(지시 단계 진입부터). 말 끝 사건이 없어 기준은 확정 전사 시각이다(A8). 말 끝부터 지시 응답 첫 소리까지 최악 약 14초(U11-D3).
- **W6 장애 · 이탈**: `providerLost` · `abortProviderLost` · `startPause`가 FAULT_CANCELLED, 상태 전이는 SESSION_EXITED. 공급자 `error` 사건은 지금 `session.error` 경로(고정 사유만 기록, code · message 원문 없음)이고 열린 턴을 닫지 않는다. 앱 종료 · 삭제 중단은 SESSION_EXITED.
- **W7 실호출 확인(PR1)**: 합성 말소리 입력 실호출 세 번. 요지는 8절.
- **W8 출력 구간**: 응답 id가 없다. 구간 열쇠는 세대, 완전은 800ms 끊김 봉인. 판정 턴 · WAITING_CHILD · 종료 확인 · 연습 보류 중(`outputHeld()`)에는 조각을 버린다. 보류가 풀리면 세대를 올린다(HOLD_RELEASED).
- **W9 서버 송신 공통 경로**: 세션당 직렬 큐 하나. 송신하면서 세대를 올린다(SERVER_SEND). 끊김 허가 · 송신 직전 대조 · 중단 사건은 없다(개정 A2).
- **W10 기동 · 키 · 스위치**: demo에서 `OPENAI_API_KEY`가 비면 기동 거부, 다른 프로필은 키 없이 가짜 공급자. realtime Luna는 키 **와** `mujoong.luna.enabled`가 모두 있을 때만 켜진다. `LUNA_STARTUP lunaState`: `ENABLED` · `DISABLED_NO_KEY` · `DISABLED_SWITCH_OFF`.

## 5. 규칙 요약

| 묶음 | 요지 |
|---|---|
| BR1 판정 호출 | `JudgeClient`만, 판 고정 선택지 순서, 0.6 · 0.15(같음은 확정), 4초(4.000초 보류) · 재시도 없음 · 호출 1회, 선택지 밖 · 중복 · 확률 이상은 보류, 공급자 없으면 턴을 열지 않음, 최소 입력 · 아이 이름을 '아이'로, 결과는 일반 편지함 |
| BR2 판정 기록 | 턴마다 1건, 어느 화면 · 리포트에도 노출하지 않음, V5, 삭제 참여자 180, 비동기 쓰기 · 제약 위반 폐기 |
| BR3 출력 보류 | 음성은 버리고 전사만 모음, 기록 · 중계 밖, 지시 뒤 세대 응답만 게이트로, 지시문에 라벨 · 확률 · 기준 · 원문 없음, 생각 중 유지 · 복원 |
| BR4 진행 | 10초는 판정 단계만, 판독 불가는 공급자 없이 보류, 지시 단계 10초 |
| BR5 장애 · 대체 · 이탈 | FAULT_CANCELLED, 낡은 차례 id 버림, 새 전사는 SUPERSEDED(지시 단계까지), 상태 이탈은 SESSION_EXITED |
| BR6 · BR7 종료 · 휴식 | 등록 표현은 판정 없이 종료, J6 결과표, 자유대화는 J1 · J6뿐 |
| BR8 확장점 | 능력 셋은 기본 `false`, 확장 소유 판정은 되묻기 없음, 확률 없는 결과 사건 |
| BR11 · BR12 선 형식 · 키 | 송신 type 넷, 세대 열쇠, `OPENAI_API_KEY` 하나, demo만 필수 |

## 6. 설정 키

| 키 | 기본 | 뜻 |
|---|---|---|
| `mujoong.judgment.min-confidence` | `0.6` | 1순위 확률이 이보다 작으면 보류(LOW_CONFIDENCE). 같으면 확정. 0 ~ 1 |
| `mujoong.judgment.min-margin` | `0.15` | 1 · 2순위 차이가 이보다 작으면 보류(LOW_MARGIN). 같으면 확정. 0 ~ 1 |
| `mujoong.luna.enabled` | `false` | realtime Luna 스위치. 키가 있어도 false면 `RealtimeLunaClient`를 등록하지 않는다(core-api는 키만으로 켜짐). demo에서 켜기 전 RUNBOOK 단계 4의 R-F15 확인 기록을 남긴다 |
| `mujoong.timing.judgment.live-budget` | `4s` | 판정 한 번의 시한(편차 (U11-b), 2s에서 올림). 호출자 마감과 짧은 쪽 |
| `mujoong.timing.judgment.response-ready` | `10s` | 말 끝(= 확정 전사) 뒤 응답 준비 시한이자 지시 단계 시한 |
| `mujoong.timing.provider.input-transcript-quiet` | `1500ms` | 입력 전사 조각이 이만큼 안 오면 그 묶음을 확정 전사로 본다(임시값) |
| `mujoong.timing.provider.new-speech-margin` | `700ms` | 지시 뒤 새 말의 전사 시작에 더하는 오디오 시계 여유(임시값) |
| `mujoong.gate.segment-quiet` | `800ms` | 구간 완전 · 지시 없이 올린 세대의 끊김 확인(PR1 이전부터) |

시간 값은 `application.yml` · `contracts/timing.md` · 설정 레코드 세 곳에서 함께 바꾼다(R-M5). 없앤 키: `mujoong.timing.provider.auto-response-start`(개정 A5).

## 7. 설계 편차

| ID | 내용 | 까닭 |
|---|---|---|
| U11-D1 | 휴식에 승인 문구 `D_REST_ACK`를 재생하지 않고 일반 지시(REST_ACK_…)를 쓴다 | 음성 파일이 없다 |
| U11-D2 | 판정 턴 중 GPT-Live 응답은 취소하지 않고 받되 음성을 버리고 전사만 모은다(2000자) | 공급자에 응답 취소가 없다 |
| U11-D3 | 10초는 판정 단계에만, 지시 단계는 진입부터 10초를 따로 잰다(최악 약 14초) | 첫 판 Q3-A |
| U11-D4 | 실호출 확인을 Code Generation 첫 단계에서 했고 범위를 넓혔다 | 요구사항 Q1-B |
| U11-D5 | 판 번호 · 표 목록 단언 3곳을 새 사실로 갱신(낮추지 않음) | 요구사항 Q2-A |
| U11-D6 | 참여 확인 중 휴식은 받아 주는 말 없이 재안내 | 참여 전 응답이 들릴 수 없다(U4b-a) |
| U11-D7 | 응답 id · `output.done` 대신 서버 세대와 800ms 끊김으로 구간을 봉인한다. 기기 첫 소리가 최대 800ms 늦어진다 | 공급자에 응답 경계 사건이 없다 |
| U11-D8 | realtime Luna는 키 + 스위치, core는 키만 | Q6-B |
| U11-D9 | (개정 A1 · A2) 송신 type은 넷뿐, `response.create` 없음. 모든 서버 발화는 `append` 하나. 끊김 허가 · 송신 직전 대조 · `SendAborted` · `InstructionSent` 표시를 없앴다 (D7의 송신 쪽 대기를 대체) | 실호출: `response.create`는 위임 세션 전용으로 거절, `append`는 말하는 중이어도 새 말을 시작 |
| U11-D10 | (개정 A3 · A4) 지시 뒤 새 말은 확인 사건 `end_ms`와 전사 `start_ms`, 오디오 시계 + 여유 700ms로 정한다 | 실호출 3: 전사가 음성보다 0.2 ~ 2초 앞서 도착 |
| U11-D11 | (개정 A7 · A8) 말 끝 · 입력 전사 완료 사건이 없어 서버가 조각을 묶는다(`input-transcript-quiet`). 전사 신뢰도가 없어 uncertain은 항상 거짓, 10초 기준은 확정 전사 시각 | 실호출: 해당 사건 없음 |
| U11-D12 | (개정 A5) `auto-response-start` 키와 "말 끝 이후 조각" 기준을 없앤다 | 모델은 아이 말이 끝나기 전에 응답을 시작 |
| U11-D13 | (개정 A6 · A9) 모든 지시문이 스스로 범위를 닫고 짧은 맞장구로 시작한다. 24kHz는 수락돼 표본율 변환 편차를 만들지 않는다 | 추가 지시는 지울 수 없고 이후 응답에도 남는다 |

**번호 대응**: 계약 편차 `(U11-a)` = 공급자 선 형식(D7 · D9 ~ D13의 계약 쪽, PR1 안에서 실호출 3회 뒤 한 번 고쳐 씀), `(U11-b)` = `live-budget` 2s → 4s(PR2), `(U11-c)` = 키 통일 · realtime 스위치(D8의 계약 쪽). 판정 턴 묶음은 계약을 더 바꾸지 않는다: `LUNA_J6`는 realtime 내부 세션 사건 값이고, `mujoong.judgment.*`는 내부 설정이다.

## 8. 실호출 증거 요약 (**[실호출-합성]**, 실제 API · 합성 음성)

원본 요지는 `aidlc/spaces/default/intents/261016-u11-judgment-turn/construction/code-generation/real-call-evidence.md`. 실제 기기 · 실제 아이 음성 증거는 없다.

| 항목 | 관측 |
|---|---|
| 24kHz 중첩 세션 시작 | 수락 |
| 입력 전사 | 조각만(`session.input_transcript.delta`), 완료 · 말 끝 · 응답 끝 사건 없음 |
| `response.create` | 거절(위임 세션 전용) |
| `append` | `delegation_id: null`이 반드시 있어야 하고 본문 필드는 `content`. 오류 0건, 확인 사건 `session.instructions.appended` 3/3 일치 |
| 말하는 중 지시 | 낡은 말이 약 1.1 ~ 1.8초 더 이어진 뒤 쉼 없이 새 말(조각 사이 최대 쉼 203 ~ 234ms) |
| 시간표 | 확인 `end_ms` − 표식 `start_ms` = −1200ms, 전사 도착 지연 0.3 ~ 2.1초(단계마다 늘어남), 135개 전사 모두 도착 순서 단조 |
| 지속성 | 지속형 지시는 2회 뒤에도 남는다. "이번 한 번만" 지시는 그 한 번만 적용(표본 1) |
| 사용량 | 99 · 97 · 239초 |
