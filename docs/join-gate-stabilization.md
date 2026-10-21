# 참여 확인 관문(WAITING_CHILD → ACTIVE) 안정화

> 작업 브랜치 `claude/join-gate`(main `fec5285`에서 땀). 지시서 1(참여 관문 안정화, main `17e2489` 기준)의 6절 (0) ~ (6)을 구현한 기록이다.
> 지시서의 줄 번호는 `17e2489` 기준이고, `17e2489..fec5285`(JIRA-253 · 254 인프라 변경)는 이 작업이 건드리는 파일을 바꾸지 않았다.
> 줄 번호 대신 클래스 · 메서드 이름으로 다시 찾았다.
>
> 이 문서는 구현하는 동안 채운다. 아직 채우지 않은 칸은 `(작성 중)`이다.

## 1. 목표와 범위

- 짧거나 다른 말이 섞인 참여 대답이 실공급자에서도 안정적으로 ACTIVE로 가게 한다.
- 다음에 실패하면 로그만 보고 원인을 알 수 있게 한다.
- 화면 문구 · 승인 문구(`infra/seed/003-u4a-reference.sql`) · `option-sets-v1.json` · 보류 기준 0.6/0.15 · StopIntentJudge 판정 동작 · ACTIVE 판정 턴(J6 · 확장)의 SUPERSEDED 규칙 · main의 마이그레이션은 바꾸지 않는다.

PR은 둘로 나눈다.

| PR | 항목 | 커밋 범위 |
|---|---|---|
| PR-A | (0) 진단 로그, (1) 짧은 긍정 판정, (2) 입력 전사 발화 간격 분할 | (작성 중) |
| PR-B | (3) J1 진행 중 새 전사, (4) 창 만료 경계, (5) prompt-v2, (6) 재생 중 에코 | (작성 중) |

## 2. 바뀐 동작 한눈에

(작성 중)

## 3. 항목별 기록

### (0) 진단 로그 `JOIN_REPLY` · `JUDGMENT_TURN` (PR-A)

- **바꾼 동작**: 참여 확인 중 확정 전사를 처리할 때마다 `JOIN_REPLY` 한 줄, 판정 턴(J1 ~ J6)이 닫히거나 열지 못할 때마다 `JUDGMENT_TURN` 한 줄을 남긴다.
  둘 다 INFO · 병합 없음 · 보안 사건 아님이고 컴포넌트는 `realtime/SessionLifecycle`이다. 속성 표는 `contracts/ai-io.md` 10.15다.
  - `JOIN_REPLY`: `joinReply`(분류) · `joinAction`(할 일) · `prompts` · `textLen`(정규화 뒤 글자 수) · `wordCount` · `deltaCount`(묶인 조각 수) ·
    `maxGapMs`(조각 사이 최대 간격, 잴 수 없으면 -1) · `devicePlaying`(말을 시작할 때 기기 재생 중) · `lateForWindow`(대답 시각 ≥ 창 끝).
  - `JUDGMENT_TURN`: `judgmentPoint` · `judgmentOutcome` · `abstainReason` · `firstOption`(CONFIRMED일 때만) · `closeReason`(열지 못했으면 없음) ·
    `nextAction` · `latencyMs` · `promptVersion`.
  - 전사 원문 · 안내 글 · 확률은 어떤 속성에도 없다(R-F14). 확률은 판정 기록 표에만 있다.
- **고른 방안과 까닭**:
  - 입력 전사 묶음을 작은 클래스 `session/InputTranscriptGroup`으로 뺐다. 실행기(약 3,100줄)에 흩어져 있던 묶음 필드 넷을 한 곳에 모으고,
    진단에 쓰는 모양(조각 수 · 최대 간격 · 연 시각 · 그때 재생 중)을 함께 쥐게 했다. (2)의 발화 간격 분할과 (4)의 대답 시각도 이 칸을 쓴다.
  - `JUDGMENT_TURN`은 판정 기록 행을 남기는 `JudgmentCoordinator.write`에서 함께 남긴다. 기록 행과 로그 줄이 늘 짝을 이룬다.
    로그 형식은 실행기의 `JudgmentEnv.logJudgmentTurn`이 만든다(협력 객체는 로그 장치를 모른다).
  - `JOIN_REPLY`는 `JoinFlow`가 결정이 난 뒤 남긴다. J1로 간 대답은 두 줄이다: 열 때 `J1_OPENED`, 결과를 참여 대답으로 넣을 때 실제 할 일
    (JOIN-GATE-A3).
- **바꾼 파일**: `contract-model` `EventCode` · `AttrKey`, `realtime` `SessionExecutor` · `JoinFlow` · `JudgmentCoordinator` · `InputTranscriptGroup`(새 파일) ·
  `StopIntentJudge`(`tokens` 접근 범위만) · `JoinTracker`(글자 수 · 낱말 수 도우미), `infra/bin/log-hygiene-check`(허용 표 · 주석 21종),
  `infra/ci/test-host-tools.sh`(21종 단언 · 새 사건 위반 0/1 사례), `backend/coverage-scope/session-runtime.txt` · `judgment-turn.txt`(새 파일).
- **시험**: `StructuredLogFormatTest`(정확 목록 21종 · 허용 키), `JoinGateContractDocsTest`(새, ai-io 10.15 표 ↔ 자바 정본 · JG 편차 표시),
  `InputTranscriptGroupTest`(새), `JoinReplyLogTest`(새: 모양 · 판정 건너뜀 · 창 늦음 · 재생 중 · 열거값 대조), `JoinJudgmentExecutorTest`
  (CONFIRMED · SUPERSEDED · NO_PROVIDER마다 `JUDGMENT_TURN` 한 줄), `PrivacyLogTest`(참여 경로 두 시험에 새 줄 · 원문 없음).
- **문서**: `contracts/ai-io.md` 10.15(새), `contracts/deviations.md` (JG-b), `docs/live-run.md` 3절 진단 절차 · 5절 추후 확인 1번, `infra/RUNBOOK.md` 8.4 한 줄.

### (1) `JoinTracker` 짧은 긍정: 낱말 단위 · 변형어 · 거부 우선 (PR-A)

- **바꾼 동작**: `JoinTracker.classify` 순서는 주제 거부 → 거부 → **짧은 긍정(문장 전체 또는 첫 낱말)** → 참여 단서 → 불명확이다.
  - 거부 · 주제 거부를 먼저 보는 순서는 그대로다. "응 싫어" · "응, 그만할래"는 거부, "응 근데 이거 말고"는 주제 거부다.
  - 문장 전체가 짧은 긍정(기존 목록, '요' 붙어도 됨)이면 참여다(지금과 같다).
  - **새 규칙**: 공백으로 나눈 첫 낱말(정규화 뒤)이 아래 목록이거나 `^(응|웅|네|넹|예)+요?$` 꼴이면 뒤에 다른 말이 붙어도 참여다.
    목록: 응 · 응응 · 웅 · 웅웅 · 으응 · 네 · 네네 · 넹 · 넵 · 예 · ㅇㅇ · 그래 · 좋아 · 오케이 · ok · yes · 알았어 · 알겠어(모두 '요' 붙어도 됨).
  - '어' · '엉'은 말머리 군말로 자주 쓰여 첫 낱말 목록에서 뺐다. 문장 전체가 그 한 낱말일 때만 참여다. "어 할래"는 단서 "할래"로 참여다.
  - **물음표 규칙**: 원문 첫 낱말에 `?`(전각 `？`는 NFKC로 같아진다)가 있으면 짧은 긍정 두 규칙을 쓰지 않고 단서만 본다. "응?" · "네? 뭐라고요?"는 불명확이다.
  - 목록 항목도 전사와 같은 정규화(NFKC)를 거친 값으로 비교한다. 지금까지 'ㅇㅇ'(호환 자모)은 NFKC가 첫소리 자모로 바꿔 한 번도 맞지 않았다.
- **고른 방안과 까닭**: 지시서 권장안 그대로다. 정확 일치만 보던 탓에 "응, 들려" · "으응" · "네네"가 UNCLEAR → J1(demo는 Luna가 꺼져 곧 재안내)로 갔다.
  낱말 단위로 보면 "네가 해"의 '네가'처럼 짧은 긍정으로 시작하는 다른 낱말은 걸리지 않는다. 단서 목록은 새로 더하지 않았다("할게요"처럼 이미 걸린다).
- **함께 바뀌는 곳**: 종료 확인 질문(`StopConfirmFlow.onReply`)도 같은 분류기의 ACCEPT를 '끝내자'로 본다. 그래서 확인 질문에 "네네"는 이제 끝,
  "응?"은 이어 가기다(JOIN-GATE-A9, 성민 확인 필요).
- **바꾼 파일**: `dialog/JoinTracker.java`(분류 · javadoc), `dialog/StopIntentJudge.java`(`tokens` 패키지 공개, (0)에서).
- **시험**: `JoinTrackerTest` 분류 표에 25행(완료 조건 세 문장 · 변형어 · 거부 우선 · 되묻기 · 군말 · 인사), `SessionJoinTest`
  `theShortYesRepliesOfTheJoinGateStartTheActivityWithoutAJudgment`(실행기에서 "응" · "응 좋아" · "어 할래" · "응, 들려" · "네네" → ACTIVE),
  `StopConfirmFlowTest` `theConfirmationSharesTheJoinClassifier…`. 기존 행 · AC6.3.x 경계 시험은 그대로 통과한다.
- **문서**: 계약 변경 없음.

### (2) 입력 전사 묶음을 전사 시간선 간격으로도 나누기 (PR-A)

- **바꾼 동작**: 새 시간 키 `mujoong.timing.provider.input-utterance-gap`(임시 `700ms`). 묶음이 열려 있고 새 조각의 `start_ms`와 앞 조각의 `end_ms`가
  모두 0 이상이며 `start_ms − 앞 end_ms ≥ gap`이면,
  1. 열린 묶음을 조용 시계(`input-transcript-quiet`)가 울린 것과 같은 길(`onInputTranscriptQuiet`)로 먼저 확정하고,
  2. 새 조각으로 새 묶음을 연다.
  - 한쪽 시간이 -1이면 지금처럼 묶는다. 가짜 공급자 두 곳(`tools/fake-provider/scenarios.mjs` · `FakeProviderWsServer`)은 조각마다 같은
    `start_ms = end_ms`를 보내 나뉘지 않으므로 `verify.sh` · 통합 시험 동작은 그대로다.
  - 앞 발화의 확정이 상태를 바꿀 수 있어(WAITING_CHILD → ACTIVE) `onInputTranscriptDelta` · 정산 창의 `onLateProviderEvent`는 묶은 뒤의 상태 ·
    묶음 id를 읽는다. 앞 발화로 세션이 끝나 정산 창도 아니면 새 조각은 버린다.
- **고른 방안과 까닭**: 지시서 권장안 그대로다. 1500ms 조용 시계만 쓰면 "내 말이 들리니?" 뒤 1초 쉬고 말한 "응"이 한 전사로 합쳐져 UNCLEAR가 된다
  (지시서 1.3 후보 2). 공급자 조각에는 항목 id가 없지만 전사 시간선은 있으므로 그것으로 나눈다. 700ms는 실측 전 임시값이다(JOIN-GATE-A10).
  묶음을 연 서버 시각 · 조각 수 · 최대 간격 · 재생 중은 (0)에서 이미 `InputTranscriptGroup`이 쥔다.
- **바꾼 파일**: `config/RealtimeTimingProperties.java`(`ProviderTiming` 셋째 값 · 양수 검사), `session/SessionExecutor.java`(`Settings.inputUtteranceGap` ·
  `from()` · `groupInputDelta`), `session/InputTranscriptGroup.java`(`startsNewUtterance`), `application.yml` · `application-test.yml`(같은 700ms —
  나눔 기준이라 시험에서 줄이지 않는다), 시험 쪽 `Settings` 생성 셋(`SessionHarness` · `SessionTimersTest` · `SessionExecutorFaultTest`).
- **시험**: `GenerationSendExecutorTest` A7 절에 셋 — 참여 확인 중 "내 말이 들리니?"(0 ~ 1200) → 300ms 뒤 "응"(2000 ~ 2300) → ACTIVE이고
  `JOIN_RE_PROMPTED`(UNCLEAR) 1건 · `JOIN_REPLY` `UNCLEAR/CLARIFY` → `ACCEPT/START`, ACTIVE에서 "하나"(0 ~ 400) · "둘"(1500 ~ 1800)이 조용 시간
  전에 나뉨, 같은 `start_ms = end_ms` · 699ms 쉼은 한 전사. `InputTranscriptGroupTest`에 경계 699/700ms · -1 사례. 인자 2개 조각을 쓰는 기존
  시험은 그대로 통과한다. 연습 흐름 태그 재시험(`-PincludeTags=practice-flow,session-runtime,voice-session,judgment-turn`) 961건 통과.
- **문서 · 설정(R-M5 · R-M10, 같은 커밋)**: `contracts/timing.md` 14절 새 행과 머리말, `contracts/ai-io.md` 3절 입력 전사 묶음 문단,
  `contracts/deviations.md` (JG-a). 화면이 읽지 않는 서버 키라 `timing-defaults.json`에는 넣지 않는다. `ConfigKeyContractTest` · `TimeLiteralScanTest` 통과.

### (3) J1 진행 중 새 전사로 결과를 버리는 문제 (PR-B, 권장안 A)

- **바꾼 동작**(WAITING_CHILD · J1에서만, ACTIVE의 J6 · 확장 턴은 그대로):
  - `JoinFlow.onJoinReply`가 분류를 먼저 한다. J1이 열려 있고 새 대답이 **UNCLEAR**면 J1을 닫지 않고 '대기 칸' 한 칸(메모리만)에 둔다. 또 오면 덮어쓴다.
    `JOIN_REPLY`의 `joinAction`은 `J1_HELD`다.
  - 새 대답이 ACCEPT · REFUSE · TOPIC_REFUSAL이면 지금처럼 J1을 SUPERSEDED로 닫고(대기 칸도 버린다) 새 대답을 처음부터 처리한다.
  - J1이 닫히면: PARTICIPATE · DECLINE이면 대기 칸을 버리고 결과를 넣는다. 그 밖의 결과이고 대기 칸이 있으면 `join.reply(UNCLEAR)`를 넣지 않고
    대기 대답으로 J1을 한 번 더 연다(앞 전사를 `recentTurns`의 CHILD 줄로). 대기 칸이 없으면 지금처럼 UNCLEAR를 넣는다 — 두 대답에 재안내는 B17대로 한 번이다.
  - 미뤄 둔 창 만기(`joinTickDeferred`)는 어느 길에서도 잃지 않는다: 결과를 넣은 뒤, 다시 연 J1로 넘겨 그 J1이 닫힌 뒤, SUPERSEDED면 새 대답을 처리한 뒤 부른다.
  - 새 J1과 결과 넣기(상태 전이)는 `JudgmentCoordinator.onClosed` 안에서 하지 않고, 앞 턴의 판정 기록 · `JUDGMENT_TURN` 줄 · 보기 복원이 끝난 뒤
    (`onClosed` 끝, `afterClose`) 한다. 그래서 로그 순서가 `JUDGMENT_TURN` → `JOIN_REPLY`(결과 적용 · 새 J1)다.
- **고른 방안과 까닭**: 권장안 A. 대안 B(SUPERSEDED는 그대로 두고 새 J1에 앞 전사를 문맥으로 넣기만)는 잡음 전사가 이어지면 J1이 끝나지 않는다.
  A는 잡음이 이어져도 첫 J1 결과는 쓰고, 다시 여는 것도 대기 칸 한 번뿐이다. J1 결과 해석(PARTICIPATE → 참여 등)을 협력 객체에서 `JoinFlow`로 옮겨
  참여 판단 상태(대기 칸 · 미룬 만기)를 한 곳이 쥐게 했다. 협력 객체 `Env`의 `applyJoinReply` · `runDeferredJoinTick`은 없앴다.
  - 다시 판정하려고 닫힌 첫 J1의 기록 `nextAction`은 `NONE`이다(참여 판단에 아무것도 넣지 않았다, JOIN-GATE-A13).
- **설계 편차**: JG-D1(U11 W1 · BR5.3의 "J1 중 새 확정 전사는 SUPERSEDED"를 불명확한 새 대답에 한해 바꾼다). U11 설계 편차 표에 둘지는 성민과 정한다.
- **바꾼 파일**: `session/JoinFlow.java`(대기 칸 · J1 결과 해석 · 다시 열기), `session/JudgmentCoordinator.java`(`openJoinJudgment(전사, 확정 시각,
  직전 대화, 닫힘 뒤 일)` · `afterClose`), `session/SessionExecutor.java`(`JudgmentEnv`에서 두 메서드 삭제).
- **시험**(`JoinJudgmentExecutorTest`): `aSecondUnclearReplyWhileJ1IsOpenIsHeldAndTheResultIsNotThrownAway`(요청 1회 · SUPERSEDED 기록 없음 →
  PARTICIPATE로 ACTIVE · CONFIRMED 기록 하나), `anUnclearResultReopensJ1WithTheHeldReply…`(두 번째 요청의 CHILD 문맥 · `JOIN_RE_PROMPTED` 1건 · 로그
  순서), `aWindowExpiryDeferredByTheFirstJ1SurvivesTheReopenWithTheHeldReply`(미룬 만기가 다시 연 J1 뒤에 돌아 DECLINED). 기존
  `theRepromptIsCountedOnlyOnceAcrossTwoUnclearAnswers` · `aWindowExpiryDuringAnOpenJ1IsDeferred…` · `aNewReplyWhileJ1IsOpenSupersedesIt…`은 그대로 통과한다.
- **문서**: 계약 변경 없음.

### (4) 창 만료 경계 (PR-B, (a) · (b)만)

- **바꾼 동작**:
  - **(a)** 참여 대답 시각을 그 묶음의 **첫 조각을 서버가 받은 시각**(말을 시작한 때)으로 바꿨다. 지금까지는 마지막 조각 뒤 1500ms에 조용 시계가 울린
    시각이라, 창 끝 직전에 말해도 늦은 대답이 됐다. J1 결과도 이 시각으로 넣는다(W1 4). J1의 10초 시계(`READY`)는 그대로 확정 시각 기준이다
    (설계 개정 A8). 시험 대역의 완료 사건만 온 전사는 받은 시각 그대로다.
  - **(b)** 입력 묶음이 열린 동안(아이가 말하는 중) `JOIN_WINDOW`가 울리면 tick을 미루고, 묶음이 확정 · 처리된 뒤(또는 빈 전사로 버려진 뒤) 부른다.
    J1이 열린 동안의 미룸과 같은 방식이고 둘이 겹치면 J1 쪽이 먼저다. 조각이 왔으면 "무응답(전사 없음)"이 아니므로 AC6.3.3과 맞다. 발화 간격으로
    묶음을 나눌 때는 아이가 이어 말하는 중이라 미룬 tick을 부르지 않는다.
  - **(c)는 하지 않았다.** 만기 뒤에 시작한 ACCEPT는 지금처럼 만기(재안내)가 먼저이고 재안내 재생 중 START가 된다(`JoinTrackerTest` :62-69 기대값 그대로).
    경계 시험을 바꾸는 결정이라 성민 합의 때만 한다(지시서 (4)(c) · 9절).
- **고른 방안과 까닭**: 지시서 권장 (a) · (b). (a)만으로는 창 끝 직전에 시작한 말의 확정 전에 창 시계가 먼저 울려 재안내가 나가므로 (b)가 함께 있어야 한다.
- **바꾼 파일**: `session/JoinFlow.java`(대답 시각 · `speechTickPending` · `onInputSettled` · `Env.childSpeaking`), `session/SessionExecutor.java`
  (`confirmInputGroup`으로 나누고 조용 시계 · 완료 사건 뒤 `onInputSettled`).
- **시험**(`SessionJoinTest`): 창 끝 0.5초 전 첫 조각 · 확정은 창 끝 뒤 → START · `JOIN_RE_PROMPTED` 없음, 창 끝 0.5초 뒤 첫 조각 → 재안내(`NO_REPLY`)가
  먼저, 말하는 중 창 만기 → 확정 전까지 `RE_PROMPT` 재생 없음, 빈 묶음으로 끝난 말 → 미룬 만기가 재안내. 기존 경계 시험(`SessionJoinTest`의 19 · 21초 ·
  `JoinTrackerTest` :31-69)은 그대로 통과한다.
- **문서**: 값은 그대로이고 해석만 분명해져 `contracts/timing.md` 1절 `join-wait` · `join-wait-after-reprompt` 뜻 열에 한 구절을 더하고(R-M10),
  `contracts/deviations.md`에 (JG-c)를 더했다.

(다음 항목은 작성 중)

## 4. 설계 편차

(작성 중)

## 5. 가정 목록

(작성 중)

## 6. 원 담당자와 맞출 것

(작성 중)

## 7. 사람이 할 일

(작성 중)

## 8. 시험 결과

(작성 중)
