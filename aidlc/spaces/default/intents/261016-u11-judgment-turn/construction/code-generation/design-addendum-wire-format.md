# 선 형식 · 출력 경계 설계 개정 (실호출 3회 반영, 2026-10-17)

> 승인된 Functional Design(`../functional-design/`)과 요구사항(`../../inception/requirements-analysis/requirements.md`)은 고치지 않는다(project.md 교정). 실호출 결과로 사실이 달라진 부분은 이 문서가 **뒤 단계(코드 생성 · Build and Test · 단위 보고서)의 기준**이다. 이 문서와 승인 문서가 다르면 아래 해당 규칙(A1 ~ A10)을 따른다.
> 사람 결정: 2026-10-17 "제안한 방향 승인"(append 한 번만 적용되는 문장, 전사 시간선으로 새 말 시작 판단, 허가 · 끊김 장치 제거). 근거: `real-call-evidence.md`.
> 편차 번호: 설계 편차 `U11-D9` ~ `U11-D13`, 계약 편차는 `(U11-a)` 안에서 대체 사실로 기록한다(Step 9 · 12).

## 바뀌는 점 (승인 문서 대비)

| ID | 대체하는 승인 문서 내용 | 새 규칙 |
|---|---|---|
| A1 | BR11.2(송신 다섯 가지) · FR12.3(`append` → `response.create`) | 송신 type은 **네 가지**: `session.start` · `session.input_audio.append` · `session.instructions.append` · `session.close`. `response.create`를 보내지 않는다(client 위임 세션은 거절한다). `append`는 `{"type":"session.instructions.append","event_id":…,"delegation_id":null,"content":…}`이고 `delegation_id`는 **값이 null이어도 반드시 포함**한다(JSON 직렬화에서 null 키를 지우지 않는다). 본문 필드 이름은 `content`이다 |
| A2 | W9 · BR3.3 · BR11.4(올림 → 끊김 → append → create, 허가 · 대조 · `SendAborted`) | **모든 서버 발화는 지시 문장 하나로 시작한다.** 참여 뒤 첫 응답 · 확인 뒤 이어 가기 · 연습 `requestResponse` · 연습 `speak` · 게이트 교정 · 되묻기 · 휴식 · 판정 뒤 지시가 모두 `append`다(지시 없이 응답만 요청하는 길이 없다). 순서 보장을 위해 세션당 직렬 큐 하나만 둔다. **끊김 대기 · 허가(`SendPermit`) · 송신 직전 대조 · `SendAborted` · `InstructionSent` 표시를 없앤다.** 송신하면서 출력 세대를 올린다(DISCARDING). 직렬 큐는 `append` 순서를 지키는 일만 한다 |
| A3 | BR3.7 · BR4.5 · BR11.7(지시 응답 = 송신 처리 뒤 첫 조각, 끊김 기준) | **지시 뒤 새 말의 시작은 전사 시간선으로 정한다**(A4). 지시 응답 시한은 `response-ready`(10s)로 지시 송신 시각부터 잰다 |
| A4 | BR11.6 · BR11.7 · W8 | 아래 "새 말 시작 판단" |
| A5 | BR11.7 기준 ② · Step 6의 `auto-response-start` | **`mujoong.timing.provider.auto-response-start` 키와 "말 끝 이후 조각이 있었는가" 기준을 없앤다.** 모델은 아이 말이 끝나기 전에 응답을 시작하므로 의미가 없다. 지시 없이 세대만 올린 경우(끼어들기 · 종료 의도 · 연습 폐기 · 게이트 폐기 · 보류 해제)의 끊김 확인은 "마지막 조각 · 세대 올림 중 늦은 쪽에서 `segment-quiet`(800ms)"만 쓴다 |
| A6 | BR3.3 · 템플릿 | 지시문은 **반드시 "이번 한 번만 …, 그 뒤에는 평소처럼 …"으로 범위를 닫는다.** 추가 지시는 이후 응답에도 남는다(지속형 문장은 2회 뒤에도 유지됨). 지우는 방법이 없으므로 지시문 템플릿이 스스로 범위를 닫는다. 지시문은 500토큰 이하, 누적 길이는 세션 로그에 사유 코드 · 개수로만 남긴다 |
| A7 | BR11.9 | 말 끝 · 입력 전사 완료 사건이 없다. 서버가 입력 전사 조각(`session.input_transcript.delta`)을 묶어 확정 전사를 만든다: 조각이 `mujoong.timing.provider.input-transcript-quiet` 동안 오지 않으면 그 묶음을 확정한다(새 시간 키 하나, 값은 임시 1500ms, 이유: 전사가 음성보다 0.2 ~ 2초 앞서 도착하고 모델이 말 끝 전에 반응하므로 아이 쉼보다 길게). 확정 전사는 지금 `InputTranscriptDone`과 같은 내부 레코드로 흘려 참여 확인 · 종료 의도 · 연습 흐름을 바꾸지 않는다. 입력 전사 `confidence`는 오지 않으므로 BR4.3의 uncertain은 거짓으로 본다 |
| A8 | BR4.1 | 10초 응답 준비 시계의 기준 시각은 판정 대상 확정 전사 시각(A7의 확정 시각)이다. 말 끝 사건이 없다 |
| A9 | FR12.6 · BR11.8 | 24kHz는 수락됨이 확인됐다(실호출 3회). 표본율 변환은 하지 않는다(`J131` 그대로). 표본율 변환 편차는 만들지 않는다 |
| A10 | W7 · BR9.2 · BR9.3 | 실호출 확인(출력 전사 있음 · 24kHz 수락 · 지시 방식)은 세 실행으로 끝났다(`real-call-evidence.md`). PR1 병합 전 조건 BR9.3은 이 증거와 단위 보고서 표로 충족한다. 남은 불확실한 값은 Build and Test 실호출에서 확인한다 |

## 새 말 시작 판단 (A4, 출력 세대)

용어: **오디오 시계** = 이번 세션에서 받은 출력 음성의 누적 바이트 ÷ 48(ms, PCM16 24kHz 모노). **전사 시간선** = 출력 전사 조각의 `start_ms` / `end_ms`. 전사가 음성보다 앞서 도착하며 그 폭은 단계마다 0.2 ~ 2초로 늘어난다(실호출 3).

### 지시를 보낸 경우 (A2)
1. `append`를 보내는 순간 세대를 올리고 DISCARDING으로 둔다. 열린 구간은 '불완전'으로 버린다. 음성 조각은 모두 버린다(판정 턴이면 전사만 모음, BR3.1).
2. `session.instructions.appended`(`client_event_id` 일치)가 오면 `ackEndMs = end_ms`를 기록한다.
3. 그 뒤(도착 순서 기준) 처음 오는 **출력 전사 조각 중 `start_ms ≥ ackEndMs`인 것**을 지시에 따른 새 말의 시작으로 본다. `newStartMs = 그 조각의 start_ms`. 이전 응답의 전사는 확인 사건보다 먼저 도착해 있다(전사가 음성보다 앞섬)는 관측에 기댄다.
4. 오디오 시계가 `newStartMs + new-speech-margin`에 닿는 첫 음성 조각부터 RECEIVING으로 바꾸고 그 조각부터 새 구간을 연다. `new-speech-margin`은 시간축 어긋남(단계마다 0.2 ~ 0.4초 늘어남)을 덮는 여유로 새 시간 키 `mujoong.timing.provider.new-speech-margin`(임시 600ms)에 둔다. 낡은 꼬리가 새어 나가는 것보다 첫 낱말이 잘리는 것이 낫다(fail-closed).
5. 낱말이 잘려도 문제가 없게 지시문 템플릿은 **아주 짧은 맞장구로 시작하라**(예: "먼저 '음,'이라고 짧게 말한 뒤 …")고 시킨다. 이 완충이 실제로 통하는지는 Build and Test 실호출에서 확인한다(실호출 3에서는 시험하지 않았다).
6. 확인 사건이 오기 전에 지시 응답 시한(`response-ready` 10s)이 지나면 사유 `ACK_MISSING`을 남기고 DISCARDING을 풀지 않고 다음 전사 중 `start_ms`가 가장 최근 지시 송신 이후인 것으로 3~4번을 진행한다. 새 말 전사가 시한 안에 없으면 사유 `NEW_SPEECH_NOT_OBSERVED`로 판정 턴 등을 닫는다(아이에게는 아무 말도 들리지 않는다 = 안전 쪽 실패).
7. 같은 세대에서 지시를 연속으로 보내면 마지막 지시의 `ackEndMs` 기준으로 다시 맞춘다.

### 지시 없이 세대만 올린 경우 (A5)
끼어들기 · 종료 의도 · 연습 폐기 · 게이트 폐기 · 보류 해제: 마지막 조각 · 세대 올림 중 늦은 쪽에서 `segment-quiet`(800ms) 동안 조각이 없으면 끊김 확인(GAP_CONFIRMED)이고, 그 뒤 처음 오는 조각부터 RECEIVING이다. 낡은 응답은 `append`와 달리 끊기지 않고 자연 종료까지 이어지므로(응답은 30초가 넘을 수 있다) 그 동안 음성은 모두 버린다. 응답 안 쉼은 최대 234ms로 800ms 기준에 충분히 여유가 있다.

### 구간 완전 (BR11.6 유지)
RECEIVING 구간은 마지막 조각 뒤 `segment-quiet`(800ms)가 지나면 '완전'으로 봉인해 게이트에 넣는다. 응답이 30초 넘게 이어질 수 있으므로 구간 상한(지금 출력 상한)으로 자르는 경로가 기본 경로다. 공급자 응답 끝 사건은 없다.

## 시험 · 가짜 공급자 변경
- 가짜 공급자(`FakeProviderWsServer` · `FakeProviderPort` · `tools/fake-provider`)는 실제처럼 동작한다: `append`에 `delegation_id` 키가 없으면 `error{missing_required_parameter, param delegation_id}`, `response.create`는 `error{invalid_request_error, "requires a session with Responses delegation"}`. `append`를 받으면 `session.instructions.appended{client_event_id,start_ms,end_ms}`를 보내고, 말하는 중이었다면 낡은 음성을 1.2 ~ 1.8초 더 보낸 뒤 쉼 없이(최대 200ms) 새 말을 시작한다. 새 말의 전사는 음성보다 0.6초 앞서 도착하고 `start_ms ≥ 확인 end_ms`를 만족한다. 입력 전사는 조각(`session.input_transcript.delta`)만 보내고 완료 · 말 끝 · 응답 끝 사건은 보내지 않는다. 지속형 지시를 보내면 이후 응답에 계속 영향이 있고 "한 번만" 문장은 그 한 번만 적용되는 것으로 흉내 낸다.
- 단위 · 실행기 시험은 위 경계 규칙(4.3 ~ 4.6), 지시 순서 큐, 확인 사건 누락 · 새 말 전사 누락, 입력 전사 묶음(A7), 지시 범위 닫는 문장(A6)을 단언한다.
- `ProviderRequestWhitelistIT` 등 송신 type 단언은 네 가지로 갱신한다(행동은 유지).

## 계획에 미치는 영향
- Step 6 · 7: 허가 · 대조 · 끊김 대기 · `SendAborted` · `InstructionSent` · `auto-response-start`를 없애고 위 규칙으로 바꾼다. 출력 세대의 DISCARDING → GAP_CONFIRMED → RECEIVING은 "지시 없는 세대 올림"에만 남기고, 지시를 보낸 경우는 `ackEndMs` / `newStartMs` 경로를 쓴다.
- Step 5의 요청 팩토리: 송신 type 네 가지, `append`에 `delegation_id: null`, 필드 `content`.
- Step 10 · 11 · 12: 실호출 3회로 끝났다. Step 12의 남은 일은 관측 이름 반영(`session.input_transcript.delta` · `session.output_transcript.delta` · `session.instructions.appended`)과 새 시간 키 두 개(`input-transcript-quiet`, `new-speech-margin`)의 세 곳 동기화, 계약 표 갱신이다.
- Step 19(지시 템플릿): A6의 "한 번만" 닫는 문장과 "짧은 맞장구로 시작" 완충을 모든 템플릿에 넣는다.
- Step 20: 판정 턴의 INSTRUCTING 단계는 "지시 송신 → 확인 사건 → 새 말 전사"로 단순해지고, `SendAborted` · 낡은 허가 `InstructionSent` 처리 행은 필요 없다.
- 시간 값 키 변화 요약: 없앰 `auto-response-start`. 새로 둠 `input-transcript-quiet`(1500ms 임시), `new-speech-margin`(600ms 임시). R-M5(두 앱 `application.yml` 중 realtime · `contracts/timing.md` · 설정 레코드)에 같은 커밋으로 반영한다.

## 감수한 위험
- 한 번만 문장 준수는 표본 1이다. 지속되는 지시가 쌓이면 이후 응답이 엉뚱해질 수 있다(템플릿 시험과 실호출로 줄인다).
- 새 말 시작 판단은 전사 시간선과 오디오 시계가 같은 축이라는 가정에 기대며, 어긋남 폭이 단계마다 늘었다. 여유 600ms가 부족하면 낡은 꼬리 일부가 새어 나갈 수 있어 Build and Test 실호출에서 확인한다.
- 입력 전사 확정을 시간 기준으로 하므로 아이가 길게 쉬면 한 발화가 둘로 나뉠 수 있다.
