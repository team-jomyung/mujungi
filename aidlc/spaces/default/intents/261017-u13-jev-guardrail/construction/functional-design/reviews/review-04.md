## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-17T12:50:00Z
**Iteration:** 2

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261017-u13-jev-guardrail/construction/functional-design/functional-spec.md > 3절 W1 5단계 · 4절 `SessionMailbox.GuardrailArrived` 행 · `Env`의 `postFromProvider` / rules.md > BR3.1 | 결과를 실행기로 돌려놓는 길이 문서 안에서 둘로 갈린다. W1 · 4절 · BR3.1은 `mailbox.putSafety(GuardrailArrived)`라고 쓰고, 같은 4절의 `Env` 행은 `postFromProvider`라고 쓰며 "U11 JudgmentCoordinator와 같은 방식"이라고 한다. 실제 코드(SessionExecutor 1656행, 837행 `putFromProvider` → `putGeneral`)에서 U11 `JudgmentArrived`는 일반 편지함으로 들어간다. 안전 편지함(`putSafety`, SessionMailbox 230행)은 일반 편지함보다 먼저 꺼내므로, 이미 일반 편지함에 쌓여 있는 기기 끼어들기 · 입력 조각 · 출력 조각보다 `GuardrailArrived`가 먼저 처리된다. 그러면 검사표 유효 조건(BR4.2)이 아직 반영되지 않은 낡은 상태를 보고 ALLOW를 통과시키는 틈이 생긴다(`onOutputQuiet`가 `pendingOutputChunks`로 같은 문제를 따로 막아야 했던 바로 그 성질이다). | 결과 도착 길을 하나로 정한다. 근거 없이 `putSafety`를 쓰지 말고 U11 선례대로 일반 편지함(`putFromProvider`)을 쓰거나, `putSafety`를 쓰려면 앞서 쌓인 일반 편지함 사건을 먼저 소화한다는 보장(예: `pendingOutputChunks` 같은 장치)과 그 까닭을 BR3.1에 적는다. 4절 `Env` 행과 W1 · BR3.1 표현을 일치시킨다. | New |
| R-02 | Major | functional-spec.md > 2.1 상태 기계 `RETRYING` · W2 3단계 / rules.md > BR3.4 · BR6.2 | `RETRYING`에서 나가는 길이 '재지시 응답 구간이 봉인될 때'와 '새 턴 · 끼어들기 · 종료'뿐이다. 재지시가 아무 말도 만들지 못하면 검사 상태가 `RETRYING`에 영영 머문다. 코드에서 `sendInstruction`은 `onFailure` 인자를 받고(SessionExecutor 2218행 부근 `instructionNotHeard`), 지시 응답 시한 만료(`onInstructionWaitExpired`의 `NEW_SPEECH_NOT_OBSERVED`)와 송신 실패(`ProviderSendQueue.Job`)가 그것을 부르며, U11 판정 턴은 `JudgmentInstructionFailed`로 이 길을 쓴다(1642행). 설계는 `onFailure`를 쓰지 않고, `sendInstruction`이 거짓(공급자 연결 없음)을 돌려줄 때도 정하지 않았다. BR3.4는 `RETRYING` 동안 `listeningAgain`이 듣는 중으로 되돌리지 않는다고 하므로, 아이 화면이 '생각 중'에 갇히고 서버는 아무것도 하지 않는다. | 재지시의 `onFailure`(응답 시한 만료 · 송신 실패 · `sendInstruction`이 거짓)를 `RETRYING` → 포기(턴 포기와 같은 마무리: `discard` 계열 + `listeningAgain`)로 잇는 전이를 상태 기계와 BR6.x에 더한다. 그 경로의 `guardrail_event` 판정 값과 시험 항목도 정한다. | New |
| R-03 | Major | rules.md > BR5.3 · BR5.4 logic · functional-spec.md > 2.2 흐름도 `J` 가지 / construction/functional-design/functional-design-questions.md 가정 17 | 대기 구간 처리의 근거가 코드와 어긋난다. BR5.4는 "앞 검사가 막힘 또는 무효였다면 재지시로 공급자 출력 세대가 오르므로 대기 구간은 낡은 세대"라고 하고 꺼낼 때 '세대 · 연결 확인'으로 거른다고 한다. 그러나 코드에서 세대를 올리는 것은 재지시(`sendInstruction` → `bumpForInstruction`)뿐이다. 무효 경로(HELD · CHILD_SPOKE · 연결 변경, BR4.3 '재지시 없음')와 두 번째 막힘의 포기 경로(`discard(…, correct=false)`는 연습 분기 말고는 `bumpOutput`을 부르지 않는다, SessionExecutor 2866 ~ 2880행)는 세대를 올리지 않는다. 따라서 이 경로들에서 대기 구간은 세대가 같아 '낡았다'고 걸러지지 않는다. 규칙 문장("무효였다면 폐기")과 logic("세대가 달라진 대기 구간은 재생하지 않는다, 꺼내는 순간의 세대 · 연결 · 입력 묶음으로 새로 만든다")이 서로 다른 구현을 낳으며, logic대로 만들면 포기 · 무효 뒤에 대기 구간이 새 검사표를 받아 검사되고 재생될 수 있다. 또 폐기하는 대기 구간의 AI 행 닫기와 `guardrail_event` 값은 흐름도에만 있고 BR5.4에는 없다. | BR5.4의 판정을 세대 비교에 기대지 말고 '앞 검사 결과' 자체로 명시한다(유효 ALLOW → 꺼내서 검사, 그 밖 모든 결과 → 폐기). 꺼낼 때 검사표의 입력 묶음 id는 봉인 시점 값으로 잡을지 꺼내는 시점 값으로 잡을지 정한다. 폐기하는 대기 구간의 AI 행 닫기(FINAL · 거짓)와 기록 값을 BR5.4에 적는다. | New |
| R-04 | Major | functional-spec.md > 열린 질문 3 · 4절 `SessionExecutor` 호출 지점 ⑥ / rules.md > BR6.2 | AC2.1.3(같은 다음 행동 1회 재지시)의 핵심인 '무엇을 다시 보내는가'가 열려 있다. 열린 질문 3이 `ReplayableInstruction`을 어느 `sendInstruction` 호출에서 기억하는지를 Code Generation으로 미룬다. 코드의 `sendInstruction`은 `Function<String, InstructionsAppend>` 빌더 · 사유 · 이름 · `onFailure`를 받는 한 메서드이고, 호출처는 서버 발화(`requestResponse`), 게이트 교정(`GATE_CORRECTION`), 판정 턴 지시, 연습 `speak` 등으로 다르다. 설계는 교정 지시를 다시 보낼 대상에 넣는지, 기억을 언제 비우는지(BR6.1은 '턴 상태'라고만 하고 새 턴에서 비운다고는 쓰지 않는다), 서버 발화 없이 시작하는 턴(인사 · 종료 확인)에서 이전 턴의 지시를 되풀이하지 않는지 정하지 않았다. 개발자가 추측해야 한다. | 재생 대상 호출 지점 목록(서버 발화 · 판정 뒤 지시 · 되묻기 포함 여부, 교정 · 연습 `speak` 제외 여부)과 기억을 비우는 시점(새 확정 전사 · 새 지시 · 종료)을 BR6.2에 확정하고 열린 질문 3을 닫는다. | New |
| R-05 | Minor | functional-spec.md > W6 기동 점검 표 · rules.md > BR1.3 · BR9.1 | 모드 × 프로필 표가 `demo` · `prod`와 `dev`만 다룬다. 실제 시험은 `test` 프로필로 기동 점검을 돌리고(U8RealtimeStartupChecksTest 등) 프로필 없음도 있다. 이 프로필들의 모드 기본값이 정해지지 않았다(`REAL` 기본이면 키 없이 기동 거부). 또 BR9.1은 "Inputs 레코드는 필드를 늘리지 않는다"고 하지만 현재 `problems(Inputs)`는 5개 값만 가진 정적 점검이라 모드 · 키 · 호스트를 넘길 자리가 없다(기존 오버로드는 생성자 쪽이다). | `test` · 무프로필의 기본 모드와, `problems`에 모드 · 키 · 호스트를 전달하는 방식(별도 입력 레코드 또는 `problems` 오버로드)을 명시한다. | New |
| R-06 | Minor | functional-spec.md > 4절 `Env.discard(sealed, 사유, correct)` · 6절 오류 코드 | 제브 사유 `GUARDRAIL_*`를 `discard`와 `mujoong.gate.discard{reason}`에 쓴다고 하지만, 코드의 `discard`는 `GateDecision.DiscardReason` 열거형(6개 값)을 받고 `gateDiscard(reason.name())`을 부른다. 열거형 확장인지 별도 오버로드인지 정해지지 않았고, 열거형을 바꾸면 `OutputGateTest` 등 기존 단언에 닿는다. | 사유 타입(열거형 값 추가 또는 문자열 오버로드)과 영향받는 기존 시험을 4절에 적는다. | New |
| R-07 | Minor | construction/functional-design/traceability.json > FR6.3 · AC2.1.3 행 | 편차 U13-D2(FR6.3 합계 로그 줄임) · U13-D3(AC2.1.3 구체화) · U13-D4(Q2 답 B)가 traceability 행에 표시되지 않았고 FR6.3은 `OK`로만 적혀 있다. project.md 교정은 사람이 정본과 다른 쪽을 고르면 해당 AC 행에도 편차 번호를 표시하라고 한다. | 해당 행에 편차 번호를 붙이고 FR6.3은 일부 줄임임을 표시한다. | New |
| R-08 | Minor | rules.md > BR4.4 | '새로 말을 시작했는가'를 입력 묶음 id 비교로 판정한다. 코드에서 묶음이 확정되면 `inputGroupId`가 `null`로 돌아가므로(`clearInputGroup`), 검사 시작 때 `null`이던 표와 결과 시점의 `null`이 같아 보이는 경우가 있다. 지금은 `input-transcript-quiet` 1500ms가 `jev-budget` 800ms보다 길어 묶음 하나가 검사 시간 안에 열렸다 닫힐 수 없지만, 이 부등식은 문서에 없다. | 부등식을 BR4.4의 전제로 적거나, id 대신 단조 증가하는 묶음 번호를 쓰도록 바꾼다. | New |
| R-09 | Minor | rules.md > BR3.1 · functional-spec.md > 4절 호출 지점 ① | `begin` 안에서 `aiHold()` 뒤에 예외가 나면 `sealAndGate`의 기존 catch(`if (transcripts.hasOpenAi()) discard(…)`)가 열린 행이 없어 아무것도 하지 않아 보관 행과 검사표가 남을 수 있다. fail-closed 정리가 정해지지 않았다. | `begin` 실패 시 보관 행 닫기 · 검사표 해제 · 막힘 처리를 BR3.3에 적는다. | New |
| R-10 | Minor | rules.md > BR2.1 · BR4.5 logic | 이 단계 정의는 프레임워크 · 코드 참조 없이 기술 중립으로 쓰라고 한다. BR2.1(JDK HttpClient · orTimeout)과 BR4.5(`CompletableFuture.cancel`) 같은 구현 도구 이름이 규칙 본문에 있다. 기존 클래스 이름 인용은 브라운필드 관례로 보고 문제 삼지 않는다. | 도구 이름은 설계서 부록이나 Code Generation 계획으로 옮기고 규칙에는 동작('요청 취소가 전송까지 전파된다')만 남긴다. | New |
| R-00 | Major | aidlc/spaces/default/intents/261017-u13-jev-guardrail/construction/functional-design/functional-spec.md > review findings table | The reviewer's findings table could not be read, so its rows are not listed here: aidlc/spaces/default/intents/261017-u13-jev-guardrail/construction/functional-design/functional-spec.md: invalid finding ID "ID" | Address the reviewer's findings as written below this table. | Unresolved |

**The reviewer's findings, as written:**

> #### Prior findings
>
> | ID | Severity | Location | Finding | Required action | Status |
> |---|---|---|---|---|---|
> | R-01 | Major | aidlc/spaces/default/intents/261017-u13-jev-guardrail/construction/functional-design/functional-spec.md > 3절 W1 5단계 · 4절 `GuardrailArrived` 행 / rules.md > BR3.1 | 해소 유지. W1 · 4절 · BR3.1 모두 일반 편지함(`putFromProvider`)이다. | 없음. | Resolved |
> | R-02 | Major | functional-spec.md > 2.1 상태 기계 `RETRYING` · 4절 '폐기 훅' 행 / rules.md > BR6.6 | 해소됐다. 훅을 `discard()` 맨 앞 한 지점으로 모았다(spec 170행, BR6.6 logic). 코드 대조: `ABANDON_NO_AUDIO`(2173행) · `providerError`(2261행) · `SEGMENT_OVERSIZE`(2130행) · `sealAndGate`의 inspect 실패 · 연습 불일치 · catch(2808 · 2826행)는 모두 `discard(…)`를 거치므로 한 지점이 전부 덮는다. 자기 호출 검사: 제브 자신의 막힘 · 대기열 폐기는 `GUARDRAIL_*` 사유라 훅을 건너뛰고, 제브 막힘 시점에 협력 객체는 `CHECKING`이라 `RETRYING` 조건도 맞지 않아 자기 유발이 없다. `begin` 예외의 `discard(GUARDRAIL_ERROR)`는 먼저 `IDLE`로 돌린 뒤라 이중 전환이 없다. `sealed == null`은 훅 제외로 정해 조기 포기가 없다. 교정 지시가 새 응답을 기다리게 되는 경우도 retries 1 유지로 정했다. 남은 표현 차이는 R-17(Minor)로 올렸다. | 없음. | Resolved |
> | R-03 | Major | rules.md > BR5.4 / functional-spec.md > 2.2 · W3 · W4 | 해소 유지. '앞 검사 결과' 기준, 세대 비교 의존 없음, 흐름도 · W3 · W4 · BR5.4가 같은 문장이다. | 없음. | Resolved |
> | R-04 | Major | functional-spec.md > W2b 표 / rules.md > BR6.2 | 해소 유지. 기억 호출 지점과 지우는 시점이 W2b · BR6.2 · entities에서 일치한다. | 없음. | Resolved |
> | R-05 | Minor | functional-spec.md > W6 · rules.md > BR9.1 | 해소 유지. `test` · 무프로필 행과 `Inputs` 오버로드 유지가 정해졌고, entities · BR1.4 · BR1.5의 '공개 프로필(demo · prod) 밖(dev · test · 활성 프로필 없음)' 문구와도 맞는다. | 없음. | Resolved |
> | R-06 | Minor | functional-spec.md > 4절 `GateDecision.DiscardReason` 행 | 해소 유지. 열거형 확장과 갱신할 기존 시험이 적혀 있다. | 없음. | Resolved |
> | R-07 | Minor | construction/functional-design/traceability.json | 해소 유지. U13-D2 · D3 · D4가 AC2.1.3 · FR3.2 · FR3.3 · FR6.3 · AC2.1.8 행에 표시돼 있다. | 없음. | Resolved |
> | R-08 | Minor | rules.md > BR4.4 | 해소 유지. 열림 횟수 비교, `begin` 기준, entities · W3 · BR5.4와 일치한다. 근거 문장은 이제 알려진 한계(`sealAndGate(true)`가 열림 횟수가 오른 뒤 봉인한 완전 구간은 무효에 잡히지 않으며 제브가 없을 때와 같아 회귀가 아님)를 적어 코드(1973 → 1982행, `sealEarly` `quietEnough`)와 맞는다. | 없음. | Resolved |
> | R-09 | Minor | rules.md > BR3.1 · functional-spec.md > 4절 `begin`의 예외 처리 행 | 해소됐다. `begin` 호출 자리에 전용 `try/catch`를 두어 ① 보관 행 FINAL · 거짓 ② 검사표 해제와 `IDLE` ③ `discard(sealed, GUARDRAIL_ERROR, correct=false)` 순서를 정했다. 공용 catch가 `hasOpenAi()` 거짓이면 무동작이라는 점(2825행)을 명시하고 기대지 않는다. | 없음. | Resolved |
> | R-10 | Minor | rules.md > BR2.1 · BR4.5 | 해소 유지. 도구 이름이 동작 표현으로 정리됐다. | 없음. | Resolved |
> | R-00 | Major | functional-spec.md > review findings table | 엔진 기록 행. 이번 검토는 산출물과 코드를 다시 읽고 각 우려를 개별 행으로 적었다. | 없음. | Resolved |
> | R-14 | Major | aidlc/spaces/default/intents/261017-u13-jev-guardrail/construction/functional-design/rules.md > BR3.1 · functional-spec.md > 4절 `begin`의 예외 처리 행 · 2.1 상태 기계 | 해소됐다. `begin`이 `aiHold` 뒤에 던질 때의 마무리가 BR3.1 · spec 171행에 정해졌다: 전용 `try/catch` → 보관 행 FINAL · 거짓 → 검사표 해제와 협력 객체 `IDLE` → `discard(sealed, GUARDRAIL_ERROR, correct=false)`. `RETRYING` 중 던져도 `IDLE`로 먼저 돌려 THINKING 정지가 없고, `GUARDRAIL_*` 사유라 폐기 훅과 이중 처리되지 않는다. 시험 항목(spec 256행)에도 `begin` 예외가 들어갔다. | 없음. | Resolved |
> | R-15 | Minor | aidlc/spaces/default/intents/261017-u13-jev-guardrail/construction/functional-design/entities.md > `GuardrailMode` constraints · rules.md > BR1.4 · BR1.5 / functional-spec.md > W6 표 · rules.md > BR9.1 | 해소됐다. entities(18행)는 'STUB · OFF는 공개 프로필(demo · prod) 밖(dev · test · 활성 프로필 없음)에서만 허용', BR1.5도 같은 표현, BR9.1 · W6와 일치한다. 'dev에서만' 문구는 entities · rules · spec에 남아 있지 않다. | 없음. | Resolved |
> | R-16 | Minor | aidlc/spaces/default/intents/261017-u13-jev-guardrail/construction/functional-design/rules.md > BR4.4 statement / functional-spec.md > 4절 호출 지점 ⑧ | 해소됐다. BR4.4가 '알려진 한계'를 적어 틀린 전제를 없앴다: 첫 조각과 봉인 사이에 아이가 말을 시작해 `sealAndGate(true)`가 닫은 구간은 열림 횟수가 이미 오른 뒤 봉인되어 무효에 잡히지 않고, `quietEnough`면 완전 구간으로 inspect를 통과해 그대로 검사된다. 제브가 없을 때의 지금 동작과 같아 회귀가 아니다. | 없음. | Resolved |
>
> #### New findings
>
> | ID | Severity | Location | Finding | Required action | Status |
> |---|---|---|---|---|---|
> | R-17 | Minor | aidlc/spaces/default/intents/261017-u13-jev-guardrail/construction/functional-design/rules.md > BR6.6 statement · BR3.1 logic ③ | '`discard`의 마무리가 `listeningAgain`까지 한다 / 기기가 THINKING에서 풀린다'는 표현이 `discard`의 모든 꼬리 가지에서 참은 아니다. 코드 `discard`(2866 ~ 2889행)는 ① `pendingPracticeSpeech != null`이면 `PRACTICE_DISCARD`로 `return`해 `listeningAgain`을 부르지 않고(훅은 그보다 먼저 `IDLE`로 돌려놓음. 운영에서 연습 통제 중에는 제브가 없어 닿지 않고 시험 대역에서만 가능) ② 상태가 ACTIVE이고 재생 중이 아니며 대기 문구가 있으면 대기 문구를 재생할 뿐 `listeningAgain`을 직접 부르지 않는다(재생 끝 쪽이 푼다). 나머지 가지(비 ACTIVE · 재생 중 · 대기 문구 없음)는 `listeningAgain`을 부른다. 협력 객체는 어느 쪽이든 `IDLE`이라 정지는 없고, spec 4절 문장('교정 지시 · 대기 문구 · `listeningAgain`')은 정확하다. BR6.6 · BR3.1의 '기기가 THINKING에서 풀린다'만 과장이다. | BR6.6 · BR3.1 문장을 'discard의 기존 마무리(대기 문구 재생 또는 `listeningAgain`, 연습 폐기 가지는 연습 쪽이 마무리)가 이어진다'로 맞추고, 연습 가지에서 협력 객체가 `IDLE`이 되는 것만 시험 항목에 한 줄 더한다. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| 규칙 개수 대조(rules.md yaml id vs 요약 표) | 둘 다 51개 | 요약 표와 yaml 일치 |
| 코드 대조(`SessionExecutor` `discard` 2866 ~ 2889행, 호출처 2130 · 2173 · 2261 · 2808 · 2826행, `listeningAgain` 2755행, `sealAndGate` catch 2823 ~ 2826행) | `begin` 앞 폐기가 모두 `discard()`를 거침, 제브 사유 호출은 훅 제외 | R-02 · R-09 · R-14 해소 근거 |
| 문구 grep('dev에서만' · BR4.4 근거 · 훅 위치) | entities · BR1.4 · BR1.5 · spec 1절 정리됨, BR4.4 한계 명시 | R-15 · R-16 해소 확인 |
| 단계 검증 도구 | 지시되지 않아 실행하지 않음 | 해당 없음 |

### Summary

훅을 `discard()` 한 지점으로 모으고 `begin`에 전용 try/catch를 둔 수정으로 `RETRYING` 정지 경로가 코드 기준으로 모두 닫혔고, 제브 자신의 막힘 흐름이 훅을 자기 유발하지도 않는다. 열린 Major는 없고 Minor 하나(R-17: 마무리 표현이 연습 · 대기 문구 가지에서 과장)만 남아 READY다.
