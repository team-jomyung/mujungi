# U11 코드 생성 요약 (PR1 Part B, Step 4 ~ 10 진행 기록)

> 브랜치 `unit/u11-wire-format`. 이 파일은 사람이 커밋한다(개발자 위임은 `aidlc/`를 스테이지하지 않는다).
> 증거 구분(R-M11): 아래 모든 시험은 **테스트 대역**(가짜 공급자 · 수동 시계)이다. `[실호출-합성]`은 Step 11(사람)이 만든다.

## 1. 바꾼 기존 단언 목록 (Step 8 · 파일 · 전 → 후)

원칙: 같은 행동(순서 · 횟수 · 버림)을 새 형식으로 다시 단언했다. 단언을 느슨하게 하거나 지우거나 건너뛰게 하지 않았다. 아래 "행동 변화" 표시는 응답 끝 사건이 없어서 **같은 행동을 그대로 둘 수 없는** 항목이고 계획 대비 편차로 보고한다.

### 1.1 키 통일 · Luna 스위치 (Step 4)

| 파일 | 전 | 후 |
|---|---|---|
| `RealtimeStartupChecksTest` (`aMissingOrShortInternalTokenAnd...`) | 프로필 `test`에서 공백 GPT-Live 키가 `PROVIDER_API_KEY`로 거부됨 | 키는 demo만 필수라 프로필 `demo`로 같은 거부를 단언 |
| `RealtimeStartupRecoveryIT` (`aStartupCheckRejection...`) | 공백 `PROVIDER_API_KEY`로 기동 거부 | test 프로필에서 같은 "기동 점검이 웹 서버 전에 거부" 경로를 내부 토큰 공백으로 확인 |
| `RealtimeLunaConfigurationTest` (키 있으면 켜짐 2건) | 키만으로 `ENABLED` · 빈 등록 | 키 + `mujoong.luna.enabled=true`일 때만(키만이면 `DISABLED_SWITCH_OFF`는 `LunaActivationTest`가 단언) |
| `GptLiveIsolationTest` | `PROVIDER_API_KEY` 이름 | `OPENAI_API_KEY` 이름(같은 격리 규칙) |

### 1.2 선 형식 (Step 5)

| 파일 | 전 | 후 |
|---|---|---|
| `ProviderRequestWhitelistIT` | `session.start` 키 집합이 flat(`childContext` 포함), 허용 type에 `session.response.cancel` · `session.instructions.correct` · `session.response.create` | 중첩 `session{model, instructions, audio{format{type, rate}}}`, 허용 type은 `session.instructions.append` · `response.create`뿐(알 수 없는 type은 즉시 실패). 아이 맥락 자르기 · 나이 묶기 · 따옴표 닫기 단언은 `session.instructions` 문장 안의 값으로 같은 규칙을 단언(필드 없음) |
| `RealtimeArchTest` (`PROVIDER_REQUEST_TYPES`) | 요청 레코드 목록에 `ChildContextData` · `ResponseCancel` · `InstructionsCorrect` | 새 레코드(`SessionBody` · `AudioFormat` · `InstructionsAppend` · `ResponseCreate`) 목록. 규칙 자체(요청은 팩토리만 만든다)는 그대로 |
| `ProviderClientIT` | 이벤트 순서에 `OutputDone`, `session.error` | `OutputDone` 없음, `UsageUpdated` · `Error("invalid_value")`, 모르는 type은 이벤트가 되지 않음 |
| `ProviderClientU3IT` | 응답 취소 2건 · 지시 교정이 선에 나간 키 단언 | 지시 추가 · 응답 생성이 정한 키만 갖고 나가고 보낸 type이 모두 지원 목록 안 |
| `ProviderResponseIdTest` | 응답 id 형식 검사(삭제) | 출력 세대 협력 객체 시험(`OutputGenerationTest`)으로 교체 |
| `tools/tests/u1/fake-provider.test.mjs` | 응답 id · `output.done` 순서, 401(키 없음) | `delta` 음성 · 응답 끝 사건 없음, 키 없어도 연결 받음, 지원 목록 밖 type → `error{invalid_value}` |
| `tools/tests/u3/fake-provider-u3.test.mjs` | 응답 취소가 남은 출력을 멈춤 | 응답 취소 type은 `error{invalid_value}`로 거절되고 출력은 계속(끼어들기는 서버 세대가 버린다) |
| `tools/tests/u4b/fake-provider-u4b.test.mjs` | `session.response.create` 기록 | `response.create` 기록, 출력 끝 신호 없음 |
| `tools/skeleton-e2e/steps.mjs` | 공급자 연결에 Bearer 있음, `session.start` 키 집합 flat, `session.response.create` 대기 | CI는 키 자리값이 없으므로 Bearer 없음, 중첩 키 집합, `response.create` 대기 |

### 1.3 구간 · 게이트 · 실행기 (Step 6 ~ 8)

| 파일 | 전 | 후 |
|---|---|---|
| `SegmentBufferTest` | 응답 id 구간, 응답 끝 신호까지 담아야 완전 | 세대 열쇠, 첫 조각부터 끊김 봉인이어야 완전(`sealQuiet`), 세대 변경 · 첫 조각 유실 · 상한 초과는 불완전 |
| `OutputGateTest.inspectionNeverLooksAtTheSessionState...` | `responseDone`으로 완결 | `sealQuiet`(완전) / `sealByGeneration`(불완전)로 같은 GATE_EMPTY · 통과 구분 |
| `OutputGateTest.childInputResumingConfirmsTheOpenSegmentAtOnce` | 응답이 끝난 구간은 아이가 말을 재개하면 곧바로 통과 | **행동 변화**: 끊김 시간이 이미 찼을 때만 곧바로 통과(`...WhenTheOutputHadAlreadyGoneQuiet`). 끊김 전에 닫으면 응답이 끝났는지 알 수 없어 불완전로 폐기(`...BeforeTheOutputWentQuiet...`, GATE_EMPTY · 미승인 행) |
| `OutputGateTest.aResponseCutByTheQuietTimeBeforeItsDoneSignal...` | 응답 중간 800ms 쉼으로 잘린 두 덩어리를 모두 불완전로 폐기 | **행동 변화(계획 W7 ⑨ 위험)**: 쉼과 끝을 가를 수 없어 두 덩어리를 따로 봉인 · 따로 검사(앞은 통과, 금지 패턴 뒤는 GATE_PATTERN). 금지 내용은 여전히 승인되지 않음 |
| `OutputGateTest.fragmentsOfAnotherResponseAreNeverMixed...` | 다른 응답 id 조각은 섞지 않고 둘 다 폐기 | 조각 사이가 800ms 넘게 끊기면 따로 봉인 · 따로 통과(`fragmentsSeparatedByAQuietGap...`), 끊기지 않으면 한 구간이라 금지 패턴이 든 전체를 폐기(`responsesThatStartWithinTheQuietTime...`, fail-closed) |
| `OutputGateTest.aQuietExpiryThatOvertakesQueuedDeltas...` | 일반 편지함 3건(조각 2 + 응답 끝), 불완전 2건 GATE_EMPTY | 일반 편지함 2건, 끊김 만기가 쌓인 조각을 앞질러도 끊김으로 보지 않아(대기 중 조각 수 확인) 한 응답으로 모아 검사 → GATE_PATTERN 1건, 승인된 행 0 |
| `OutputGateTest.anEmptyTranscript...` | 폐기 직후 다음 응답이 바로 도착 | 폐기가 교정 지시를 보내려고 올린 세대가 끊김 확인 · 송신을 마친 뒤(`h.settleSend()`) 다음 응답 |
| `OutputGateTest.aLateInspectionAfterCancellation...` | CLOSING 직후 늦은 응답이 NOT_ACTIVE로 폐기 | 마무리가 올린 세대의 꼬리가 끊긴 뒤(800ms) 새로 시작한 응답이 NOT_ACTIVE로 폐기(같은 재확인 경로) |
| `SessionVoiceU3Test` 끼어들기 3건 | 공급자 응답 취소 2건 `r1` · `r2` 기록, 취소 불가 시 조용히 버림, id 없는 취소 | 출력 세대가 오르고 공급자로 나가는 요청 0건 · `provider-*` 작업 0건, 늦은 조각 버림 · 끊긴 뒤 새 응답 통과, 소켓이 닫힌 뒤에도 세대만 올리고 APP_ERROR 0건 |
| `SessionVoiceU3Test` 종료 의도 2건 | 취소 작업 1건 | 출력 세대 상승 · 송신 작업 0건, 종료 의도가 아니면 세대 그대로 |
| `SessionVoiceU3Test` 교정 3건 | 응답당 한 번 교정(응답 id 키), 응답 끝 없이 멈춘 응답 = 미완 | 세대당 한 번 교정(늦은 조각은 올라간 세대가 버려 다시 폐기되지 않음), 미완 = 전사 없는 음성 · 상한 초과, 아이 재개로 닫은 미완은 교정 없음, CLOSING에는 교정 없음 |
| `SessionVoiceU3Test` 확인 질문 뒤 이어 가기 | 바로 다음 응답 | 이어 가기 응답 요청이 끊김 확인 · 송신된 뒤(`h.settleSend()`) |
| `SessionStopU4bTest` / `SessionJoinU4bTest` / `PracticeExtensionU4bTest` | `provider-cancel-` 작업 · 취소 레코드 기록 | 송신 작업 0건 · 공급자로 나가는 요청 없음, 참여 전 응답은 출력 보류가 버림(응답 요청 0건, 출력 0), 이어 가기 응답 요청은 `h.settleSend()` 뒤 한 번 |
| `SessionExecutorTest.onlyTheOutputDone...` | AI 행 `view_item_ready` = 응답 끝(300ms) → 통과(800ms) = 500ms | `theAiRowsViewItemReadyTimeStartsAtTheLastChunkOfTheSealedSegment`: 마지막 조각 → 통과 = 800ms(응답 끝 사건이 없어 공급자 출력 끝 = 구간의 마지막 조각 시각, W8-5) |
| `SessionExecutorReview2U3Test` / `SessionExecutorReviewU3Test` | 폐기 직후 다음 폐기 | 앞 폐기의 교정 송신이 끝난 뒤(`h.settleSend()`), 끼어들기로 올린 세대의 꼬리가 끊긴 뒤 |
| `SessionExecutorFaultTest.aDisconnectCheck...` | `activate` 직후 39.5초 | `activate`가 끊김 확인으로 시계를 800ms 앞당기므로 기기 마지막 메시지 시각을 `touch`로 다시 잡음(경계 의미 그대로) |
| `PracticeExecutorIntegrationTest.audio()` | 응답 끝 신호 뒤 드레인 | 응답 끝 신호 없이 조각 뒤 드레인 |
| `SessionLifecycleIT.aFullTurnThenGuardianStop...` | CLOSING 뒤 늦은 말이 미승인 AI 행 1개로 남음(AI 행 3개) | 마무리가 올린 세대가 늦은 말을 구간으로 열기 전에 버려 AI 행 2개(턴 · 마무리 인사), 늦은 말은 행으로도 남지 않음. 기기로 한 바이트도 안 가는 단언은 그대로 |
| `SessionVoiceU3IT.aBargeIn...` | 응답 취소 `resp-1` 수신, `instructions.correct` | 응답 취소 0건(수신 type 모두 지원 목록 안), `instructions.append`, 끼어들기 뒤 끊김을 기다린 다음 새 응답 |
| `RealtimeLogHygieneIT.aProviderErrorMidTurn...` | 응답 끝 없는 부분 응답이 게이트에서 폐기되어 대기 문구 | `error` 사건이 열린 구간을 불완전로 닫아 같은 폐기 · 대기 문구 경로(코드 변경: `providerError`) |

### 1.4 통합 시험 장치

- `SessionHarness`: `activate()`가 참여 뒤 첫 응답 요청의 끊김 확인 · 송신(`settleSend()`)까지 한다. 요청 대기 상태를 보는 시험은 `activateUnsettled()`. `Settings` 생성 시험 3곳에 새 두 값(자동 응답 시작 대기 800ms · 끊김 대기 상한 10s).
- `ManualLauncher.runAll`: 작업을 따로 띄운 스레드에서 돌리고 5초 안에 못 끝나면 중단 · 실패(끊김 허가를 받지 못한 송신 작업이 시험을 영영 멈추는 일을 막는다).
- `RealtimeItEnvironment.join`: ACTIVE 뒤 가짜 공급자가 `response.create`를 받을 때까지(최대 3초) 기다린다. `application-test.yml`에 `mujoong.timing.provider.auto-response-start: 100ms`.
- `FakeProviderPort` · `FakeProviderWsServer`: 새 형식 요청 기록 · 방출(`emitAudio` · `emitError` · `emitUsage`), 지원 목록 밖 type 오류.

## 2. 계획 대비 편차 · 열린 점

1. **Step 8 단언 3건은 "같은 행동"을 유지할 수 없다**(위 `행동 변화`). 응답 끝 사건이 없어 완전성의 근거가 "끊김 봉인"뿐이기 때문이다(설계 BR11.6 · W7 ⑨의 위험). 안전 방향(불확실하면 폐기)으로 맞췄다.
2. `session.usage.updated`는 해석기가 `UsageUpdated(seconds)`로 읽지만 실행기는 지표로 쓰지 않는다(지표 이름이 `contracts/` 표에 없어 R-M10 부담이 크다). 실호출 도구 보고서가 사용량을 기록한다.
3. 출력 전사 · 입력 전사 · 말 끝 사건의 이름과 `session.instructions.append`의 본문 필드 이름(`text`)은 실호출로 확인되지 않았다(미확인). Step 12까지 기존 이름을 응답 id만 빼고 임시로 쓴다.
4. 새 시간 키 `mujoong.timing.provider.auto-response-start` = 800ms는 임시값이다(Step 12가 정함).
5. `render-env`는 PR1 이후 옛 `PROVIDER_API_KEY`를 realtime.env에 쓰지 않는다. PR1 이전 이미지로 롤백할 때는 그 SHA의 `render-env`로 렌더되는지 먼저 확인해야 한다(RUNBOOK에 적음).
6. demo에서 키를 비우는 길은 이제 realtime 기동 거부를 부른다(BR12.2). core-api Luna는 키만으로 켜지므로 키가 있는 동안 realtime의 `mujoong.luna.enabled`로만 realtime Luna를 끌 수 있다(RUNBOOK 롤백 절에 적음).

## Step 9 · 10 메모

- Step 9: `contracts/ai-io.md` 2 · 3 · 8 · 10절을 실호출 선 형식으로 고치고 편차 (z) · (aa) · (U4b-a)를 "(U11-a)로 대체"로 표시했다. `(U11-a)` · `(U11-c)`를 `deviations.md`에 더했다.
- Step 10: `GptLiveProbe`는 운영 형식 한 모드만 남기고 `GPT_LIVE_RATE` · `GPT_LIVE_UTTERANCES`를 더했다. 루프백 시험(`GptLiveLoopbackTest`)이 보고서 항목 채움을 확인한다.
- 남은 위험: `auto-response-start` 800ms는 임시값, 전사 · 말 끝 사건 이름과 `instructions.append` 본문 필드는 미확인(Step 12), 사용량 지표는 연결 전, `image-checks.sh`는 로컬 이미지가 낡아 실행하지 못했다.

## PR1 개정(실호출 3회 반영)

기준: `design-addendum-wire-format.md`(A1 ~ A10) · `real-call-evidence.md`. 승인 문서(FD · 요구사항)는 고치지 않았다. 커밋 `5322e69`(백엔드) · `b4b1329`(tools) · `f8286ac`(계약) · `2725608`(실호출 도구 대조).

### 3.1 운영 코드가 달라진 점

| 영역 | 개정 전(Step 5 ~ 8) | 개정 후 |
|---|---|---|
| 송신 type | 다섯(`response.create` 포함), `append` 본문 `text` | 네 가지. `append` = `{type, event_id, delegation_id: null, content}`, `delegation_id`는 코덱이 null을 지우므로 속성에 `@JsonInclude(ALWAYS)` |
| 서버 발화 | 지시 → (끊김 허가) → `response.create` | 모두 지시 하나. `InstructionTemplates`(짧은 맞장구 + "이번 한 번만 …, 그 뒤에는 평소처럼 …"). 승인 발화(연습 `speak`)와 연습 확장점 지시는 맞장구 없이 범위 닫는 문장만(원문 일치 검사 때문) |
| 직렬 큐 | 허가 칸 · 송신 직전 대조 · `InstructionSent` · `SendAborted` | 작업을 차례로 `append`하는 일만. 실패는 `onFailure`(스레드 안전한 일만) |
| 출력 세대 | 끊김 허가 · `auto-response-start` · 말 끝 이후 조각 기준 | 지시를 보내며 올린 세대: 확인 사건 `end_ms` → 첫 `start_ms >= ackEnd` 전사 = `newStartMs`(그 조각부터 전사 수용) → 오디오 시계가 `newStartMs + new-speech-margin`에 닿는 첫 음성부터 수용. 지시 없이 올린 세대: `segment-quiet` 끊김 |
| 시한 | 끊김 대기 상한 | `response-ready`: 확인 누락 `OutputAckMissing`(한 번 더 기다림) → `OutputNewSpeechNotObserved`(세대 닫고 `segment-quiet` 뒤 다시 받음, 연습 발화는 확장에 실패 알림) |
| 입력 | `InputTranscriptDone` · `SpeechStopped` 사건 | 서버가 `input-transcript-quiet`(1500ms) 동안 조각이 없으면 묶음을 같은 `InputTranscriptDone` 입구로 확정(신뢰도 null = 확실). 말 끝 사건 제거, 정산 창 안에서도 묶고 창이 끝날 때 확정 |
| 시간 키 | `auto-response-start` 800ms | 없앰. `input-transcript-quiet` 1500ms · `new-speech-margin` 600ms(임시) — 설정 레코드 · `application.yml` · `contracts/timing.md` 한 커밋(R-M5) |

### 3.2 바꾼 단언 · 사라진 행동

| 파일 | 전 | 후 |
|---|---|---|
| `ProviderRequestFactoryWireTest` | 다섯 type, `append {type, text}`, `responseCreate()` | 네 type, `{type, event_id, delegation_id(null), content}`, 응답 요청 입구 없음, 모든 템플릿이 범위를 닫음 |
| `ProviderRequestWhitelistIT` | `INSTRUCTIONS_APPEND_KEYS {type,text}` · `RESPONSE_CREATE_KEYS` | `{type,event_id,delegation_id,content}`, 응답 생성 키 집합 삭제, 공장 공개 인자에 `String`(event id) · `ResponseStart` 허용 |
| `RealtimeArchTest` | 공급자 요청 레코드 허용 목록에 `ResponseCreate` | 그 레코드가 없어져 목록에서 뺐다(규칙 자체는 그대로) |
| `ProviderClientU3IT` | 확정 전사 `confidence` 파싱 · `parse(done)` 결과 단언 | 입력 전사 완료 사건은 앱 사건이 아니다(무시) → `parse(done)`은 null. `confidence()` 변환 규칙 단언은 그대로(공급자는 보내지 않는다) |
| `ProviderClientIT` · `FakeProviderWireIT` | 사건 순서에 `SpeechStopped` · `InputTranscriptDone` | 두 사건이 없는 순서, 전사 조각 `start_ms`/`end_ms` 단언 추가 |
| `OutputGenerationTest` · `GenerationSendExecutorTest` | 허가 · 끊김 대기 · 표시 순서 | 새 규칙(확인 · 새 말 전사 · 여유 · 시한 · 입력 전사 묶음 · 큐 순서 · 교정 연쇄)으로 다시 썼다 |
| `SessionVoiceU3Test` · `SessionExecutorReview2U3Test` · `PracticeExtensionU4bTest` | 보낸 지시 목록 = 교정 문장들 | 목록 맨 앞에 참여 뒤 첫 응답 지시가 더해졌다(`FIRST`), 교정은 `correctionContent` |
| `SessionVoiceU3Test` 생각 중 | 말 끝 사건으로 `THINKING` | 말 끝 사건이 없어 확정 전사로 `THINKING`(같은 view 순서 단언). 재생 중 말 끝 신호 → 재생 중 입력 전사 조각 |
| `OutputGateTest.aPassedTurnLogsAllEightTimeKeys...` | `tSpeechEndSource = PROVIDER_EVENT` | `INPUT_TRANSCRIPT_FINAL` |
| `SessionSettleU4bTest` | (단언 그대로) | 정산 창 안에서도 입력 전사 묶음을 이어 받아 창이 끝나면 확정하는 동작을 추가해 그대로 통과 |
| `SessionVoiceU3IT` 지시 | `instructions.append` 1건 `text` | 첫 응답 지시 + 교정 지시, 둘 다 `delegation_id: null`, 교정은 `correctionContent` |
| `SessionVoiceU3IT` 저신뢰 전사 | 공급자 `done{confidence 0.3}`이 review_needed | **사라진 행동**: 공급자는 `confidence`를 보내지 않는다. 묶은 조각 전사가 확실(review_needed 거짓)로 저장되는지로 바꿨고, 저신뢰 표시는 확정 전사 입구를 직접 부르는 실행기 단위 시험이 지킨다 |
| `SessionLifecycleIT` 늦은 말 | `emitTurn`으로 곧바로 | 아이 전사 확정을 기다리게 한 가짜 서버 옵션 때문에 이 시험만 `childSays` + `emitSpeech`로 곧바로 보낸다(같은 단언) |
| `RealtimeLogHygieneIT` | 시간 없는 전사 · 음성을 직접 흘림 | 지시 응답은 확인 · 새 말 전사 뒤에만 받으므로 `emitSpeech` 사용(같은 단언) |
| `TimeLiteralScanTest` | (수정 없음) | 이름을 `PCM_BYTES_PER_MILLISECOND`로 해 허용 목록을 건드리지 않았다 |

### 3.3 가짜 공급자 · 장치

- `FakeProviderWsServer`: `delegation_id` 키 필수(`missing_required_parameter`), `response.create` 거절, 확인 사건(`start_ms`/`end_ms`는 이 서버가 센 오디오 시계), `emitSpeech`(지시 응답이면 확인 `end_ms` 뒤 새 말 + 채움 음성), `emitTurn`(아이 입력 전사 조각만), `instructedSpeech`(낡은 꼬리 뒤 쉼 없는 새 말, 지속형/한 번만 지시 추적), `autoAck`/`childConfirmDelay` 옵션.
- `FakeProviderPort.FakeSession`: 시간 없는 출력 사건이 확인 안 된 지시 뒤에 오면 확인 사건 · 새 말 시작(빈 전사) · 채움 음성 · 빈 조각을 먼저 보내는 핸드셰이크(옛 시험이 그대로 통과). `SessionHarness.speak` · `settleSend`는 새 의미.
- `RealtimeItEnvironment`: `join`은 첫 응답 지시를 기다리고, `childSays`는 조각만 보내며, 가짜 서버가 아이 전사 확정(150ms) 뒤에 AI 말을 보내도록 300ms 기다린다(옛 순서 흉내).
- `tools/fake-provider`(Node) · Vitest · `skeleton-e2e` 참여 단계가 같은 규칙을 따른다(`--confidence` 삭제).

### 3.4 편차 · 위험

1. **교정 연쇄 상한(추가)**: 교정 지시가 곧 발화라 모델이 계속 어기면 끝없이 이어진다 → 통과한 구간 없이 연달아 두 번까지(`MAX_CORRECTIONS_IN_A_ROW`). 설계 개정에는 없는 안전 상한이다.
2. **승인된 연습 발화에는 짧은 맞장구를 더하지 않았다**(A4.5의 예외): 맞장구가 들어가면 전사가 승인 원문과 달라져 원문 일치 검사가 실패한다. 대신 새 말 앞 `new-speech-margin`(600ms)이 잘릴 수 있다 → Build and Test 실호출에서 확인해야 한다(필요하면 승인 발화 앞에 승인된 짧은 맞장구를 원문에 포함).
3. **확정 전사 지연**: 아이 전사가 마지막 조각 뒤 1.5초에 확정되므로 THINKING 표시 · 종료 의도 · 참여 확인 반응이 그만큼 늦다. 모델이 말 끝 전에 반응하므로 지연 기록의 `tSpeechEnd`가 첫 AI 출력보다 늦을 수 있다(출처는 `INPUT_TRANSCRIPT_FINAL` · `LAST_INPUT_FRAME`).
4. **전사 시간선 = 오디오 시계 가정**: 실호출 3회에서 단조였고 모두 구간 안이었지만 어긋남 폭이 단계마다 늘었다(0.2 ~ 0.4초). 600ms 여유가 모자라면 낡은 꼬리가 샌다(Build and Test 실호출 확인).
5. **ACK_MISSING 대체 규칙**은 "가장 최근 지시를 보낼 때의 오디오 시계 이후 `start_ms`"로 약하다(전사가 음성보다 앞서므로 낡은 말의 전사가 먼저 걸릴 수 있다). 실호출에서는 확인 사건이 항상 왔다.
6. 연습 확장점의 `instructProvider`는 이제 곧 발화를 시작한다(지시 = 발화). 확장점이 `instructProvider` 직후 `requestResponse`를 부르면 앞 발화는 세대 올림으로 버려지고 뒤 것만 들린다(낭비).
7. 승인된 발화 지시문 길이 상한(500토큰)은 `PracticeInstruction.forSpeech`가 64000자까지 허용하는 기존 값 그대로다 — 긴 발화는 공급자가 거절할 수 있다(이 작업 밖).
8. 실제 공급자 · 실제 기기 · 아이 목소리 성공은 이 작업에 없다(가짜 공급자 증거만, R-M11). `verify.sh` 전체는 돌리지 않았다(Build and Test).
