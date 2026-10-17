## Review

**Verdict:** NOT-READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-17T12:14:26Z
**Iteration:** 1

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

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| BR id 중복 검사(rules.md) | PASS: BR1.1 ~ BR9.3, 50개, 중복 없음 | 구조 이상 없음 |
| BR → traceability 대상 | PASS: 50개 모두 `traceability.json` target에 등장(쉼표로 묶인 target 포함) | 고아 규칙 없음 |
| requirements.md의 FR/NFR/AC/US id → traceability upstream_ids | PASS: 68개 모두 포함 | 상위 ID 누락 없음 |
| 편차 표시 검사 | FAIL: `traceability.json`에 `U13-D` 표기가 없음 | R-07 |
| 코드 대조(SessionExecutor · OutputGeneration · SegmentBuffer · TranscriptWriter · SessionMailbox · RealtimeStartupChecks) | 두 세대 구분 주장 확인: `sendInstruction` · `bumpOutput`은 기기 프레임 세대(`outputGeneration`)를 올리지 않고 `bumpGeneration`만 올린다. `TranscriptWriter.aiFinal`은 단일 `openAi`만 닫는다(보관 손잡이 필요성 확인) | 설계 방향은 맞고 R-01 ~ R-04 가 세부 결함 |
| 편차 U13-D2 근거(EventLog) | 확인: `EventLog.event(EventCode, …)` · `attr(AttrKey, …)`가 닫힌 어휘라 합계 줄에 새 `EventCode` · `AttrKey`가 필요하다는 주장은 사실이다 | 지적 없음 |

### Summary

두 세대 분리, 검사표 유효 조건, 대기열 1칸의 큰 구조는 코드와 맞고 FR/NFR/AC 추적도 빠짐이 없다. 그러나 결과 도착 편지함 선택의 모순(R-01), 재지시 뒤 `RETRYING`에서 빠져나오는 길 없음(R-02), 대기 구간 폐기 근거가 코드와 어긋남(R-03), 재지시 대상 미정(R-04) 네 가지 Major가 있어 이 설계만으로는 개발자가 추측 없이 구현할 수 없다.
