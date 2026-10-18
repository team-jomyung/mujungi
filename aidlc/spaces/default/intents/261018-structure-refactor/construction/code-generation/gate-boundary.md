# 게이트 경계와 연습 확장점 분리 판단 (Step 17 · 설계 3.6 · 3.7)

> 이 문서는 코드를 옮기지 않고 사실만 적는다. 줄 번호와 수치는 `SessionExecutor.java` 3,090줄 기준이다(커밋 `cc4fff1`, Step 17 코드 커밋 뒤). 시험 수치는 `:realtime-activity:check` 1,866건 실행(실패 0)의 JaCoCo 값이다. 모든 결과는 가짜 공급자와 합성 대화 기준이며 실제 공급자 · 실제 기기 성공을 뜻하지 않는다.

## 0. 결정 요약

| 항목 | 결정 | 근거 요약 |
|---|---|---|
| 게이트 구역(3.6) | 이번에는 옮기지 않는다. 경계만 문서화한다. | 실행기 쪽 게이트 코드는 구간 확정 · 송신 · 폐기라는 출력 배관이고, `GuardrailCoordinator`는 검사 요청 생명주기라서 서로 다른 일이다. 합치거나 옮기려면 출력 큐 · 지연 계측 · 연습 슬롯을 함께 다뤄야 한다. |
| 연습 확장점 구역(3.7) | 이번 기록에서는 나누지 않는다. | 가장 큰 위험 구역이고 이번 합격 기준(AC-4 3.5 뒤 2,800줄)은 분리 없이는 못 맞추지만, 분리하려면 새 실행 능력 경계(아래 5절) 설계가 먼저 필요하다. |
| AC-4 | 3.4 뒤 상한 3,100줄 이하는 달성(3,090줄). 3.5 뒤 상한 2,800줄은 미달(290줄 초과). | 5절의 숫자 분석. |

## 1. 게이트 구역이 하는 일 (`// ---- 게이트`, 2722 ~ 2995, 274줄)

세션의 공급자 오디오 출력이 기기로 가기까지의 마지막 배관이다.

1. `sealAndGate(movedOn)`: 조용 시간 만기 또는 아이 입력 재개로 출력 구간을 확정(`SegmentBuffer.seal`)하고, 아이 전사 · 연습 슬롯 상태에 따라 제브 검사를 시작할지(`guardrail.begin`) 직접 보낼지 정한다.
2. `recheckAndSend` → `send`: 검사를 통과했거나 검사가 필요 없을 때 송신 직전에 `OutputGate`로 한 번 더 확인하고 `DeviceLink.sendOutput`으로 보낸다. 실패하면 폐기한다.
3. `discard` 두 개 · `isGuardrailReason` · `recordDiscard` · `closeAiRow` · `finishDiscard` · `correctAfterDiscard` · `correctionFor`(패키지 공개 정적, `SessionVoiceU3Test`가 부름): 폐기 사유 계측, AI 전사 행 닫기, 완결 요청 교정 지시 보내기, 대기 필러 재생.
4. `inRelayRange` · `inOutputRange`: 상태 범위 판정 보조(게이트 밖 구역도 부른다).

## 2. `GuardrailCoordinator`와의 경계 (759줄)

`GuardrailCoordinator`는 검사 요청 하나의 생명주기(대기열 · 만기 · 판정 반영 · 재시도 · 실패 닫힘 · 계측)를 가진다. 출력 구간을 만들거나 기기로 보내는 일은 하지 않고 그 몫은 `GuardrailCoordinator.Env`(20개 항목)를 통해 실행기에 있다.

| 일 | 책임 | 위치 |
|---|---|---|
| 구간 확정, 검사를 시작할지 결정 | 실행기 | `sealAndGate` |
| 검사 요청 대기 · 순번 · 만기 · 재시도 · 판정 처리 · 실패 닫힘 | 협력 객체 | `GuardrailCoordinator` (`begin` `onArrived` `onCheckTimerFired` `onNewTurn` `onTurnBoundary` `onExit` 등) |
| 직접 낭독(verbatim) 건너뛰기 여부 판정 | 협력 객체 | `verbatimSource` `onVerbatimSkipped` (실행기가 `sealAndGate`에서 부른다) |
| 통과 뒤 송신 직전 재확인과 송신 | 실행기 | `recheckAndSend` `send` |
| 폐기 · 전사 행 닫기 · 교정 지시 | 실행기 | `discard` `recordDiscard` `closeAiRow` `finishDiscard` `correctAfterDiscard` |
| 차단 시 어떤 폐기 사유로 보낼지 | 협력 객체(정적) | `GuardrailCoordinator.reasonFor` |
| 재시도 지시 재생 · 재시도 무음 | 실행기(Env 구현) | `GuardrailEnv.sendReplay` `sendRetryFreeTurn` |

실행기 → 협력 객체 호출: 게이트 구역 안에서 `guardrail.` 호출이 12곳, 실행기 전체로는 24곳이다(`isChecking` `enabled` `begin` `onBeginFailed` `onVerbatimSkipped` `verbatimSource` `onDiscardedBeforeCheck` 등).

협력 객체 → 실행기 되부름(`GuardrailEnv` 154줄, 실행기 안 중첩 클래스):

| `GuardrailCoordinator.Env` 항목 | 실행기 쪽 실체 | 게이트 구역 |
|---|---|---|
| `recheckAndSend` | `recheckAndSend` | 예 |
| `discardForRetry` | `recordDiscard` | 예 |
| `abandon` | `discard(…, true)` | 예 |
| `discardSilent` | `closeAiRow` | 예 |
| `finishAbandoned` | `closeGeneratedSlot` + `finishDiscard` | 반(연습 슬롯 + 게이트) |
| `practiceVoided` | `closeGeneratedSlot` | 아니오(연습) |
| `outputHeld` `listeningAgain` | 실행기 상태 | 아니오(메시지 처리) |
| `inputGroupOpenCount` `practiceSpeechId` | 실행기 필드(`inputGroupOpens` `pendingPracticeSpeech`) | 아니오 |
| `sendReplay` `sendRetryFreeTurn` `postFromProvider` | 지시 송신 · 편지함 | 아니오 |
| `status` `terminated` `now` `connectionId` `deviceGeneration` `providerGeneration` `holdAiRow` 등 읽기 | `ctx` | 아니오 |
| `armCheckTimer` `cancelCheckTimer` `markCheckedTurn` `practiceBlocked` `appError` ×2 `metrics` | `ctx`와 편지함 | 아니오 |

되부름 20개 중 게이트 구역 코드를 직접 가리키는 것은 4~5개뿐이고 나머지는 상태 읽기 · 시계 · 계측 · 연습 슬롯이다.

## 3. 옮길 후보와 옮기지 않는 이유 (BR3.3)

| 후보 | 옮기면 좋은 점 | 옮기지 않는 이유 |
|---|---|---|
| 폐기 묶음(`discard` ×2 `recordDiscard` `closeAiRow` `finishDiscard` `correctAfterDiscard` `correctionFor`, 약 120줄)을 `GuardrailCoordinator`나 새 `OutputDiscard`로 | `GuardrailEnv`의 `discardForRetry` `abandon` `discardSilent` `finishAbandoned` 4개가 직접 호출이 된다. | `finishDiscard`가 대기 필러(`ctx.plan().waitingFiller`) 재생과 `ctx.sendControl`을 쓰고, `discard`가 연습 슬롯(`pendingPracticeSpeech`)과 `practiceHold`를 건드린다. 연습 필드를 `Env`로 더 내보내거나 연습 구역 분리를 먼저 해야 한다. `correctionFor`는 시험(`SessionVoiceU3Test`)이 `SessionExecutor.correctionFor`로 부르므로 이름 참조 갱신도 필요하다. |
| `sealAndGate` 전체(약 90줄) | 실행기에서 가장 긴 게이트 메서드가 줄어든다. | 연습 통제 여부(`practiceControlled`) · 생성형 슬롯 · 직접 낭독 · 제브 사용 여부를 한 곳에서 분기한다. 가지마다 연습 상태를 읽어서 연습 구역과 함께 움직여야 한다. 커버리지도 가장 얇다(게이트 구역 미커버 13줄 중 `sealAndGate` 조기 반환 · 예외 가지가 포함됨). |
| `recheckAndSend` `send`(약 60줄) | 송신 배관이 한 파일로 모인다. | `DeviceLink.sendOutput` · `OutputBundle` · 지연 계측 · 연습 말 추적(`practiceAudio`)이 섞여 있다. 출력 큐 가득 참 폐기 가지는 시험이 지나가지 않는다(baseline 2절). |

경계 판단: 게이트 코드를 `GuardrailCoordinator`에 합치면 759줄이 약 880줄이 되고 두 이야기(검사 요청 생명주기 + 출력 배관)가 한 클래스에 들어간다. 새 클래스로 뺀다면 연습 구역 분리와 같은 시점에 하는 것이 맞다(5절 선행 작업).

## 4. 연습 확장점 구역의 규모와 결합 (3.7)

구역 제목 `// ---- 연습 확장점(U4b BR10.1 ~ BR10.6)`은 958 ~ 2666(1,709줄)에 걸치지만 내용은 연습 전용 코드만이 아니다. 구성은 다음과 같다.

| 부분 | 줄 | 크기 | 내용 | 미커버 줄 / 지나간 줄 |
|---|---|---|---|---|
| 연습 시작부 | 958 ~ 1254 | 297 | `onPracticeEvent` `practiceFailure` 타이머 일시정지 · 재개 `afterExtensionJudgment` 등 | 18 / 171 |
| 협력 객체 `*Env` 6개 | 1255 ~ 1766 | 512 | `JudgmentEnv` 76 · `JoinEnv` 17 · `EndingEnv` 96 · `IdleEnv` 67 · `ClocksEnv` 57 · `GuardrailEnv` 154 (연습 몫은 아님, 앞 Step의 협력 객체가 실행기에 되부르는 자리) | 2 / 174 |
| 연습 출력 능력 | 1739 ~ 1984 | 246 | `PracticeOutputPort`(1767 ~ 1982) `closeGeneratedSlot` `refusePractice` `practiceActive` | 33 / 94 |
| 공급자 사건 처리 | 1985 ~ 2666 | 682 | `onProviderEvent` 입력 전사 묶기 · 출력 조각 · `bumpOutput` · 지시 만기 · 공급자 끊김 · 종료 의도 판정(`judgeStopIntent` 등) · 종료 확인 질문 | 16 / 357 |

(겹치는 줄: 1739 ~ 1766은 `GuardrailEnv` 끝과 `closeGeneratedSlot`이 맞닿아 두 행에 걸린다. 표의 합은 구역 전체와 맞지 않고 구역 전체는 미커버 69 / 지나간 784다.)

연습 전용으로 볼 수 있는 코드는 시작부 297줄 + 출력 능력 246줄 = 약 543줄이고, 나머지 약 1,166줄은 연습과 무관한 공급자 사건 처리와 앞 Step이 남긴 `*Env` 구현이다. 구역 이름만 보고 "연습 확장점 1,860줄을 나눈다"고 읽으면 실제로 나눌 연습 전용 코드의 크기를 과대평가한다.

### 결합 (읽고 쓰는 상태, 호출 방향)

- 연습 필드 15개가 실행기에 있다: `practice` `practiceFailed` `practiceHold` `practiceThinking` `practiceTimers` `practicePlaybacks` `pendingPracticeSpeech` `practiceAudio` `pausedPracticeEvents` `practicePauseStartedAt` `practicePauses` `practiceEventsInSettle` `practiceOutput` `droppedLate` `missingReported`.
- 구역 밖(연습 구역 958 ~ 1984 바깥)에서 이 필드를 참조하는 줄: `practice` 26 · `pendingPracticeSpeech` 8 · `practiceHold` 5 · `practiceOutput` 4 · `practiceAudio` 3 · `practiceFailed` 2 · `practiceThinking` 2 · `practiceEventsInSettle` 2 · `droppedLate` 2 · `missingReported` 2 · 나머지 필드 각 1. 구역 안 참조는 `practice` 25 · `pendingPracticeSpeech` 13 · `practiceHold` 11 · `practiceTimers` 10 등이다.
- 협력 객체 쪽에서 연습으로 되부르는 `Env` 항목: `EndingFlow.Env` 8개(`stopPracticeProgress` `cancelPracticeTimer` `cancelPracticeTimers` `practicePlayback` `practiceAudio` `practicePlaybacks` `practiceSettlementDelay` `practiceRecordForSettle`), `IdleClock.Env` 5개(`practiceProgress` `practiceControlsOutput` `pausePracticeTimers` `resumePracticeTimers` `interruptPracticeSpeech`), `GuardrailCoordinator.Env`의 `practiceSpeechId` `practiceVoided` `finishAbandoned`.
- 호출 방향은 설계 정본대로 "실행기 → 확장점(`PracticeSessionExtension`)" 한 방향이고, 확장점은 `PracticeOutputPort` 능력으로만 실행기를 부른다. 이 방향은 이미 지켜지고 있고 `PracticeFlowArchTest`가 강제한다.
- 게이트 구역이 연습 상태를 읽는 곳: `sealAndGate`의 `practiceControlled` · 생성형 슬롯, `discard`의 `pendingPracticeSpeech` · `practiceHold`, `finishDiscard`.

### 특성화 시험 커버리지

- 직접 시험: `PracticeExtensionU4bTest` 9개 · `PracticeGeneratedSpeechTest` 10개 · `GuardrailPracticeEventTest` 5개 · `PracticeStartPathIT` 3개, 그리고 `practice/` · `practiceflow/` 시험 묶음. 연습 구역 전체 줄 커버리지는 784 / 853 = 91.9%다.
- 약한 곳: `PracticeOutputPort` 등 출력 능력(1739 ~ 1984)의 미커버 33줄(지나간 94, 74.0%). baseline 2절이 지적한 `cancelSpeech`(9줄)와 `cancelResponse`(4줄) 전부를 포함한다. 나눈다면 출력 능력 구역을 옮기기 전에 이 둘을 고정하는 시험이 먼저 필요하다.
- 모듈 전체 줄 커버리지는 93.7%(미커버 952, 지나간 14,234)로 하한 80%를 넘는다.

## 5. 연습 확장점을 이번에 나누지 않는 근거

모두 위에서 직접 센 사실이다.

1. **가장 큰 위험 구역이다.** 연습 출력 능력의 취소 가지(`cancelSpeech` · `cancelResponse`)가 시험으로 한 번도 지나가지 않고, 연습 구역 미커버 69줄 중 33줄이 출력 능력에 몰려 있다. 동작 불변 이동의 안전망이 이 구역에서 가장 얇다.
2. **상태 결합이 크다.** 연습 필드 15개를 구역 밖에서도 읽고 쓰며(`practice` 26줄 등), `EndingFlow` · `IdleClock` · `GuardrailCoordinator`의 `Env` 항목 중 연습으로 가는 것이 16개(8 + 5 + 3)다. 구역을 새 객체로 옮기면 이 항목이 사라지지 않고 새 객체의 `Env`로 옮겨 간다. 이번 단계가 목표로 한 "되부름 줄이기"가 되지 않는다.
3. **새 실행 능력 경계의 설계가 필요하다.** 연습 구역은 `outputHeld` `practiceHold` `practiceThinking` 같은 출력 보류 조건을 쥐고 게이트(`sealAndGate` `discard`)와 판정 협력 객체와 맞물린다. 나누려면 "연습 객체가 실행기에 낼 수 있는 능력(재생 · 제브 지시 · 시계 · 비동기 결과)"과 "보류 조건 읽기"의 경계를 먼저 설계하고 승인받아야 한다(BR3.5, 학습 규칙: 확장점 출력 능력과 금지 능력까지 정할 것).
4. **이번 합격 기준 밖이다.** 연습 구역을 나누는 것은 AC-4의 3.4 뒤 상한(3,100줄)에 필요하지 않았고(3,090줄 달성), BR3.3은 "분배만 하는 실행기"를 이번 합격 기준으로 삼지 않는다.
5. **AC-4 3.5 뒤 상한 2,800줄은 미달이다.** `SessionExecutor` 3,090줄 가운데 연습 구역 1,709줄 · 게이트 274줄이 실행기에 남아 있다. 남는 부분의 분석은 다음과 같다.

| 항목 | 줄 | 비고 |
|---|---|---|
| 머리(import · 클래스 설명 · `Settings` `Services` `Plan` `ApprovedItem` · 남은 필드 · 생성자) | 약 516 | 공개 중첩 타입 약 275줄은 외부 참조 때문에 옮기지 않기로 했다(baseline 3.5) |
| 실행 · 외부 입구 · 메시지 처리 | 약 340 | |
| 연습 구역 | 1,709 | 연습 전용 약 543, 앞 Step `*Env` 6개 512, 공급자 사건 처리 약 682 |
| 확인 질문 창 만기 · 출력 보조 | 55 | |
| 게이트 | 274 | |
| 재전달 · 최상위 예외 · 상태 채택 | 약 100 | |

   2,800줄에 닿으려면 290줄을 더 줄여야 한다. 안전하게 후보가 되는 것은 두 가지다: 공개 중첩 타입 분리(약 275줄, 운영 8파일 · 시험 약 10파일 참조 갱신) 또는 `*Env` 구현 일부 정리(남은 467줄). 둘 다 이번 지시 범위 밖이라 하지 않았다.

## 6. 나눈다면 필요한 선행 작업 (다음 기록 후보)

1. 연습 출력 능력의 취소 가지 특성화 시험: `PracticeOutputPort.cancelSpeech` · `cancelResponse`와 `closeGeneratedSlot`의 `INTERRUPTED` 가지(미커버 33줄 중 대부분).
2. 연습 객체가 실행기에 낼 수 있는 능력 목록과 읽을 보류 조건(`outputHeld` 등)의 경계 설계서 작성 · 승인.
3. 공급자 사건 처리(682줄)를 연습과 분리해 먼저 옮길지 정하기. 연습과 무관한 이 부분이 연습 구역의 40%이므로, 이것을 `ProviderEvents` 같은 협력 객체로 빼는 쪽이 위험은 낮고 줄 수 효과는 연습 전용 분리(약 543줄)와 비슷하다.
4. 게이트 폐기 묶음(`discard` 계열 약 120줄)은 연습 슬롯 필드(`pendingPracticeSpeech` `practiceHold`)의 소유자가 정해진 뒤에 옮긴다.
5. 위 3, 4를 하면 `GuardrailEnv`(154줄)와 `JudgmentEnv`의 되부름이 직접 호출로 바뀔 수 있다. 이때 실행기 3.5 뒤 상한 2,800줄을 맞출 수 있는지 다시 센다.
