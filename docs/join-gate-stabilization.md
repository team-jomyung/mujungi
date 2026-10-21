# 참여 확인 관문(WAITING_CHILD → ACTIVE) 안정화

> 작업 브랜치 `claude/join-gate`(main `fec5285`에서 땀). 지시서 1(참여 관문 안정화, main `17e2489` 기준)의 6절 (0) ~ (6)을 구현한 기록이다.
> 지시서의 줄 번호는 `17e2489` 기준이고, `17e2489..fec5285`(JIRA-253 · 254 인프라 변경)는 이 작업이 건드리는 파일을 바꾸지 않았다.
> 줄 번호 대신 클래스 · 메서드 이름으로 다시 찾았다.
>
> 이 작업은 모델(Claude)이 사람 대신 구현했다. 지시서가 사람 몫으로 둔 일(실키 발화 · R-F15 기록 · 10/18 DB 조회 · 팀원 합의)은 하지 않았고 7절에 남겼다.
> 성민 · 도연 · 동훈과 맞출 질문은 답을 기다리지 않고 지시서 권장안으로 진행했다(6절).

## 1. 목표와 범위

- 짧거나 다른 말이 섞인 참여 대답이 실공급자에서도 안정적으로 ACTIVE로 가게 한다.
- 다음에 실패하면 로그만 보고 원인을 알 수 있게 한다.
- 화면 문구 · 승인 문구(`infra/seed/003-u4a-reference.sql`) · `option-sets-v1.json` · 보류 기준 0.6/0.15 · StopIntentJudge 판정 동작 · ACTIVE 판정 턴(J6 · 확장)의 SUPERSEDED 규칙 · main의 마이그레이션은 바꾸지 않는다.

PR은 둘로 나눈다.

| PR | 항목 | 커밋 범위 |
|---|---|---|
| PR-A | (0) 진단 로그, (1) 짧은 긍정 판정, (2) 입력 전사 발화 간격 분할 | `60d46a8` ~ **`57638c8`**(PR-A 경계, 이 커밋까지) |
| PR-B | (3) J1 진행 중 새 전사, (4) 창 만료 경계, (5) prompt-v2, (6) 재생 중 에코 | `4eeac6c` ~ 브랜치 끝(마지막 문서 커밋 포함) |

- 커밋 순서: `60d46a8` (0) → `10c5dfb` (1) → `57638c8` (2) ‖ `4eeac6c` (3) → `ea36b66` (4) → `93d65d2` (5) → `5401f78` (6) → 문서 마무리.
- PR-A는 `57638c8`을 머리로 하는 브랜치(지시서 예: `claude/join-gate-diag`)로, PR-B는 PR-A 병합 뒤 이 브랜치 끝으로 낸다. 브랜치 push와 PR 생성은 하지 않았다(7절).

## 2. 바뀐 동작 한눈에

| 항목 | 전 | 후 |
|---|---|---|
| (0) 로그 | 참여 분류 · J1 결과가 로그에 없음(DB에만 판정 기록) | 대답마다 `JOIN_REPLY`, 판정 턴마다 `JUDGMENT_TURN`(코드 · 개수 · 시간만) |
| (1) 짧은 긍정 | 문장 전체 정확 일치만 ACCEPT("응, 들려" · "네네" · "으응" → UNCLEAR) | 거부 먼저, 그다음 첫 낱말이 짧은 긍정이면 ACCEPT. "응?"은 UNCLEAR. 'ㅇㅇ' 정규화 고침 |
| (2) 입력 묶음 | 1500ms 조용 시계로만 나눔 | 전사 시간선 쉼 ≥ `input-utterance-gap`(700ms)이면 앞 발화를 먼저 확정 |
| (3) J1 중 새 전사 | 늘 SUPERSEDED로 닫고 결과를 버림 | 불명확한 새 대답은 대기 칸, J1 결과가 불명확이면 대기 대답으로 한 번 더 판정 |
| (4) 창 끝 경계 | 대답 시각 = 조용 시계 시각(창 끝 직전 대답도 늦음) · 말하는 중에도 재안내 | 대답 시각 = 첫 조각 수신 시각 · 말하는 중 만기는 확정 뒤로 미룸 |
| (5) J1 지시문 | `prompt-v1`, 문맥 없음 | `prompt-v2`(J1 규칙 넷), 직전 참여 안내 글을 COACH 줄로 |
| (6) 에코 | 재생 중 대답도 그대로 분류(안내 글 에코 → 거부 · 참여 오분류 위험) | 8자 이상이고 재생한 안내 글의 부분 문자열이면 버림(`ECHO_SUSPECT`) |

- demo(ECS) 프로필은 realtime Luna가 꺼져 있어(`mujoong.luna.enabled: false`) J1이 열리지 않는다. 데모 안정성에 바로 효과가 있는 것은 (1) · (2) · (4) · (6)이고
  (3) · (5)는 Luna가 켜진 로컬 live 스택(`infra/compose.live.yml`)에서만 돈다. 스위치를 켜는 일은 이 작업 밖이다.
- 화면 문구 · 승인 문구 · 선택지 판 · 보류 기준은 그대로다.

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
- **시험**: `JoinTrackerTest` 분류 표에 24행(완료 조건 세 문장 · 변형어 · 거부 우선 · 되묻기 · 군말 · 인사), `SessionJoinTest`
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

### (5) J1 프롬프트 판 올리기: 직전 안내 문장 문맥과 짧은 긍정 규칙 (PR-B)

- **바꾼 동작**:
  - 판정 지시문을 `judgment/prompt-v2.txt`(판 번호 `prompt-v2`)로 올렸다. v1 본문의 마지막 줄("아래는 지점별 질문과 …") 앞에 J1에만 한정한 문장 넷을 더했다.
    1. "recentTurns의 COACH 줄은 무중이 방금 아이에게 들려준 말이다. J1에서는 아이 말이 그 말 끝의 참여 질문에 대한 대답인지로 판단한다."
    2. "J1에서 '응' · '네' · '어' · '그래' · '좋아' · '할래'처럼 짧게 긍정하면 앞뒤에 다른 말이 붙어도 PARTICIPATE다. 단, 하기 싫다 · 쉬고 싶다는 뜻이 함께 있으면 DECLINE이나 REST를 고른다."
    3. "J1에서는 아래 선택지 정의의 '너무 짧아'보다 이 규칙이 앞선다. 짧다는 이유만으로 UNREADABLE이나 UNCLEAR를 고르지 않는다. UNREADABLE은 글자가 깨졌을 때만 고른다."
    4. "J1에서 인사('안녕')나 들리는지 확인('내 말 들려?')만 한 말은 UNCLEAR다."
  - `JudgmentPrompt.RESOURCE` · `DEFAULT_VERSION`을 v2로 바꿨고 `prompt-v1.txt`는 이력으로 남긴다(`policy-v1.json`과 같은 방식). `JudgmentSupport.NONE`의
    `"prompt-v1"` 글자는 `JudgmentPrompt.DEFAULT_VERSION`으로 맞췄다. 다른 시험 안의 `"prompt-v1"`은 대역 자료라 그대로다.
  - J1 요청의 `recentTurns`에 **마지막으로 기기에 보낸 참여 안내의 승인 글**(`ctx.plan().approvedItem(code).text()`)을 COACH 줄로 넣는다. (3)의 다시 판정이면
    앞 전사를 CHILD 줄로 그 뒤에 둔다(최대 2줄, 상한 3줄 안). 글이 null · 빈 값이면 넣지 않는다. 패키지에 없어 재생을 건너뛴 안내는 아이가 듣지 못했으므로
    COACH 줄을 바꾸지 않는다(JOIN-GATE-A15).
- **고른 방안과 까닭**: 지시서 권장안 그대로다. 10/18에 J1이 4번 모두 참여가 아니었던 원인 후보 5(문맥 없음 · v1의 "자신이 없으면 낮게" · J1 UNREADABLE
  "너무 짧아")를 직접 줄인다. 새 화자 MUJOONG을 만드는 것은 스키마 · `JudgeRequest.Speaker` · ai-io 10.1을 고치는 계약 변경이라 하지 않았고, 이미 스키마에
  있는 COACH를 쓴다.
  - **판 규칙과의 관계**: `contracts/ai-io.md` 10절 머리말의 "프롬프트 판(`promptVersion`)과 스키마 판은 함께 올린다"는 스키마를 바꿀 때 프롬프트 판도
    올린다는 뜻으로 읽었다. 이번에는 요청 · 출력 스키마 파일(판 번호 칸이 없다)을 바꾸지 않았으므로 프롬프트 판만 올렸다(JG-d, 10.4에 한 줄).
  - 선택지 판 `judgment-v1`과 보류 기준 0.6/0.15는 바꾸지 않았다. 같은 지시문을 J2 ~ J6과 연습(INTRO_PRACTICE)도 쓰므로 더한 규칙은 모두 "J1에서"로 시작한다.
- **바꾼 파일**: `resources/judgment/prompt-v2.txt`(새), `judgment/JudgmentPrompt.java`, `session/JudgmentSupport.java`, `session/JoinFlow.java`(`lastPromptText` · COACH 줄).
- **시험**: `JudgmentInstructionsTest`(`prompt-v2` · J1 규칙 문장 · 지점별 뜻보다 앞), `JudgmentConfigurationTest`(`prompt-v2`), `JoinJudgmentExecutorTest`
  `theJ1RequestCarriesTheLastPlayedJoinPromptAsTheCoachLine`(첫 J1은 INTRO 글, 재안내 뒤에는 RE_PROMPT 글, 승인 글이 없으면 COACH 줄 없음, 로그에 글 없음),
  `RealtimeLunaInputAndSchemaTest` `aJoinJudgmentWithTheCoachPromptAndAnEarlierChildLinePassesTheRequestSchema`(입력 정리 + 요청 스키마 통과).
- **실호출 탐침(선택, 사람이 돌린다)**: `luna/JoinJudgmentReachTest`(`@Tag("luna-reach")`). 운영 `JudgmentPrompt.load` · `LunaJudgeClient` ·
  `RealtimeLunaClient`로 합성 J1 일곱 문장(PARTICIPATE 기대: "응" · "응 들려" · "음 그래 볼까" · "어 할래", UNCLEAR 기대: "안녕" · "내 말이 들리니?",
  DECLINE 기대: "싫어")을 판정하고, COACH 줄은 시드의 INTRO_FREE_TALK v2 글이다. 보고서 `realtime-activity/build/luna-reach/j1-report.md`에는 번호 · 기대 ·
  결과 코드 · 보류 사유 · 1 · 2순위 코드 · 지연만 남긴다. 기본 `test` · `check`에 들어가지 않는다(`verifyLunaReachIsolation` 통과). 이 컨테이너에서는 키 없이
  `lunaReachTest`를 돌려 두 탐침 모두 **건너뜀**(실호출 없음)을 확인했다. 실호출 결과는 아직 없다(7절 · 사람이 할 일).
- **문서**: `contracts/ai-io.md` 10.4 한 줄, `contracts/deviations.md` (JG-d).

### (6) 재생 중 에코의 참여 판정 오염 (PR-B, 최소 방어)

- **바꾼 동작**: WAITING_CHILD에서 확정 전사가 다음 둘을 모두 만족하면 '에코 의심'으로 보고 분류도 J1도 하지 않고 버린다. `JOIN_REPLY`만
  `joinReply=ECHO_SUSPECT` · `joinAction=NONE`으로 남긴다. 열린 J1도 닫지 않는다.
  1. 정규화(`StopIntentJudge.normalize`: NFKC · 소문자 · 한글 · 영문 · 숫자만) 뒤 **8자 이상**이다.
  2. 이 세션에서 기기에 보낸 참여 안내(INTRO · RE_PROMPT · DECLINE_ALTERNATIVE, 최대 3개)의 승인 글을 같은 방식으로 정규화한 글 하나의 **부분 문자열**이다.
  - 재생 중인지는 조건에 넣지 않았다. 재생이 끝난 직후에 전사가 늦게 확정되는 경우도 막기 위해서다(JOIN-GATE-A16). `devicePlaying`은 로그에만 남긴다.
  - "응" · "어 할래" · "응 이야기할래"처럼 짧은 대답은 8자 미만이라 걸리지 않는다. 재생 중 대답을 곧바로 처리하는 결정(U4b-J33)은 그대로다.
- **고른 방안과 까닭**: 지시서 최소 방어안 그대로다. 승인 v2 글이 에코로 전사되면 INTRO의 "사람이 **아니**라" · "**그만**", DECLINE_ALTERNATIVE의
  "하기 **싫**으면 **안 해**도"가 거부로, INTRO 끝의 "이야기**할래**"가 참여로 분류된다. 8은 시간 값이 아닌 상수(`JoinTracker.ECHO_MIN_CHARS`)이고,
  참여 대답(대개 1 ~ 7자)과 안내 글 조각을 가르는 기준이다(주석 · 이 문서). 안내 글과 아이 말이 한 묶음에 섞인 경우는 (2)의 분할로 줄인다. 그래도 남는
  오분류는 실키 로그(`devicePlaying=true` 건수와 그 분류)를 본 뒤 정한다.
- **점검(사람 몫)**: 지금 안내 음성은 334Hz 합성 톤이라 에코가 전사될 일이 적다. 사람 목소리 v3(다른 지시서)가 들어오면 스피커 음량을 키워 `JOIN_REPLY`의
  `devicePlaying=true` 건수와 `ECHO_SUSPECT` 건수를 센다.
- **설계 편차**: JG-D3(U4b-J33 "재생 중 대답도 바로 분류"를 긴 에코 의심 전사에 한해 좁힌다).
- **바꾼 파일**: `dialog/JoinTracker.java`(`ECHO_MIN_CHARS` · `echoOf`), `session/JoinFlow.java`(재생한 안내 글 목록 · 에코 버림).
- **시험**: `SessionJoinTest` — INTRO 재생 중 "오늘 나랑 이야기할래" → START · J1 · 재안내 없음(`ECHO_SUSPECT` 한 줄), INTRO 재생 중 "그만하고 싶으면 무중아
  그만할래라고 말해 줘" → `JOIN_ALTERNATIVE_OFFERED` 없음, 대안 재생 뒤 "하기 싫으면 안 해도 돼" → DECLINED 아님(뒤의 "응"으로 ACTIVE), 재생 중 "응 이야기할래"
  → START. 시험 자료의 INTRO · ALTERNATIVE를 승인 v2 꼴로 바꿔 위 문장이 부분 문자열이 되게 했다(기존 :205 "응" 시험 그대로 통과). `JoinTrackerTest`에
  `echoOf` 경계(8자 · 문장부호 · 섞인 말 · 빈 목록).
- **문서**: 로그 값 `ECHO_SUSPECT`는 (0)에서 ai-io 10.15에 넣었다. 계약 변경은 그 밖에 없다.


## 4. 설계 편차

계약 편차(`contracts/deviations.md` "참여 관문 안정화 편차" 묶음):

| 기호 | 내용 | 표시 |
|---|---|---|
| (JG-a) | 새 시간 키 `mujoong.timing.provider.input-utterance-gap`(임시 700ms) | timing.md 14절 · ai-io.md 3절 |
| (JG-b) | 새 로그 사건 `JOIN_REPLY` · `JUDGMENT_TURN`(19 → 21종) | ai-io.md 10.15 |
| (JG-c) | 참여 창 해석: 응답 시각 = 첫 조각 수신 시각, 조각이 오는 동안은 무응답 아님 | timing.md 1절 |
| (JG-d) | 판정 지시문 판 `prompt-v1` → `prompt-v2`(스키마 판 그대로) | ai-io.md 10.4 |

설계 편차(이 문서에만 둔다. U11 · U4b 설계 편차 표에 옮길지는 성민과 정한다):

| 번호 | 바뀐 설계 | 지금 동작 | 근거 · 항목 |
|---|---|---|---|
| JG-D1 | U11 W1 · BR5.3: J1이 열린 동안 새 확정 전사는 SUPERSEDED로 닫고 처음부터 다시 처리 | 불명확한 새 대답은 J1을 닫지 않고 대기 칸에 둔다. 분명한 대답만 SUPERSEDED | (3) 권장안 A |
| JG-D2 | U11 W1: J1 결과를 닫힘 뒤 일 안에서 바로 `join.reply`에 넣음 | 판정 기록 · `JUDGMENT_TURN` · 보기 복원이 끝난 뒤(`onClosed` 끝) 넣는다. 다시 연 J1도 그 뒤에 연다 | (3), 끝-끝 결과는 같다 |
| JG-D3 | U4b-J33: 안내 재생 중 대답도 곧바로 분류 | 8자 이상이고 재생한 안내 글의 부분 문자열인 전사는 분류하지 않는다(짧은 대답은 그대로 곧바로 분류) | (6) |
| JG-D4 | U4b BR2.2 · 창 경계: 대답 시각 = 확정 시각, 만기는 시계대로 | 대답 시각 = 첫 조각 수신 시각, 말하는 중 만기는 확정 뒤로 미룸(만기 뒤에 시작한 대답은 그대로 만기 먼저) | (4) (a) · (b), 계약 (JG-c) |
| JG-D5 | U4b 종료 확인(BR4.4): 확인 질문의 '예'는 참여 분류기의 ACCEPT | 분류기가 넓어져 "네네" · "응 좋아" 같은 대답도 끝, "응?"은 이어 가기 | (1)의 부수 효과 |

## 5. 가정 목록

애매한 판단을 스스로 정한 곳이다. 원 담당자가 다르게 정하면 바꾼다.

| 번호 | 정한 것 | 까닭 |
|---|---|---|
| JOIN-GATE-A1 | 지시서(main `17e2489`)의 줄 번호를 `fec5285`에서 클래스 · 메서드 이름으로 다시 찾았다. `17e2489..fec5285`는 `backend/` · `contracts/` · `infra/bin` · `infra/ci/test-host-tools.sh` · `docs/live-run.md` · `infra/RUNBOOK.md`를 바꾸지 않았다 | `git diff --stat`으로 확인 |
| JOIN-GATE-A2 | 사건 이름 `JOIN_REPLY` · `JUDGMENT_TURN`과 속성 이름은 지시서 그대로 썼다. `joinReply`의 자바 상수는 `AttrKey.JOIN_REPLY_KIND`다(`EventCode.JOIN_REPLY`와 같은 이름이면 `EventCode` 안에서 가려진다) | 성민 합의(질문 5) 전 권장안 |
| JOIN-GATE-A3 | J1로 간 대답은 `JOIN_REPLY` 두 줄(열 때 `J1_OPENED`, 결과를 넣을 때 실제 할 일)이다. 지시서의 `J1_SKIPPED` 값은 두지 않았다: J1을 열지 못하면 `JUDGMENT_TURN`(`NO_PROVIDER` · `UNREADABLE_INPUT`)이 그것을 보이고, `JOIN_REPLY`에는 실제 할 일(`CLARIFY` · `NONE`)을 적어야 demo(Luna 꺼짐)에서 재안내 여부를 한 줄로 읽는다 | 진단에 더 쓸모 있는 쪽 |
| JOIN-GATE-A4 | `maxGapMs` = max(0, 새 조각 `start_ms` − 앞 조각 `end_ms`)의 최댓값, 잴 수 없으면 -1. `devicePlaying`은 묶음을 열 때(아이가 말을 시작할 때) 값이고, 완료 사건만 온 대역 전사는 처리 시각 값이다 | 에코 판단에 쓸 값은 말 시작 시점 |
| JOIN-GATE-A5 | `textLen`은 정규화 뒤 코드 포인트 수, `wordCount`는 공백으로 나눠 정규화한 뒤 빈 낱말을 뺀 수다 | 분류기와 같은 기준 |
| JOIN-GATE-A6 | `JUDGMENT_TURN`은 판정 기록기 유무와 관계없이 남긴다(기록 행은 지금처럼 기록기 · 보호자 id가 있을 때만) | 로그만으로 진단 |
| JOIN-GATE-A7 | 물음표 규칙은 첫 낱말이 물음표로 "끝나면"이 아니라 물음표가 "있으면"이다 | "응?뭐라고"처럼 띄어 쓰지 않은 전사도 되묻기로 본다 |
| JOIN-GATE-A8 | 짧은 긍정 목록 항목도 전사와 같은 NFKC 정규화를 거쳐 비교한다 | 'ㅇㅇ'(호환 자모)이 한 번도 맞지 않던 기존 결함 |
| JOIN-GATE-A9 | 종료 확인 질문(`StopConfirmFlow`)도 같은 분류기를 쓰므로 (1)이 그쪽 대답 분류도 넓힌다. 분류기를 둘로 나누지 않고 그대로 두었다 | 설계가 한 분류기를 쓰게 했다. 성민 확인(6절) |
| JOIN-GATE-A10 | `input-utterance-gap`은 임시 700ms이고 경계(정확히 700ms)는 나눈다. 시험 프로필도 700ms다(대기 시간이 아니라 나눔 기준이라 줄이지 않는다) | 실측 전 지시서 값 |
| JOIN-GATE-A11 | 대답 시각 = 묶음의 첫 조각을 서버가 받은 시각. 시험 대역의 완료 사건만 온 전사는 받은 시각. J1 `READY`(10초) 시계는 지금처럼 확정 시각 기준 | 설계 개정 A8을 바꾸지 않는다 |
| JOIN-GATE-A12 | (4)(c) 만기 뒤 ACCEPT를 먼저 START로 보기는 하지 않았다 | 경계 시험 기대값을 바꾸므로 합의 필요(지시서 9절) |
| JOIN-GATE-A13 | 대기 대답으로 다시 판정하려고 닫힌 J1의 판정 기록 `nextAction`은 `NONE`이다. 다시 연 J1의 `recentTurns`는 [COACH 안내 글, CHILD 앞 전사] 순이다 | 참여 판단에 아무것도 넣지 않았다 · 대화 순서 |
| JOIN-GATE-A14 | 대기 칸은 한 칸이고 SUPERSEDED · FAULT_CANCELLED · SESSION_EXITED에서 버린다. 다시 연 J1이 열린 동안 또 불명확한 대답이 오면 다시 대기 칸에 둔다(아이가 계속 말하는 동안은 재안내 없이 판정을 잇는다) | 말하는 중은 무응답이 아니다 |
| JOIN-GATE-A15 | COACH 줄은 마지막으로 기기에 **보낸** 참여 안내 글이다. 패키지에 없어 건너뛴 안내는 COACH 줄을 바꾸지 않는다 | 아이가 들은 말이 문맥이다 |
| JOIN-GATE-A16 | 에코 대조 대상은 이 세션에서 보낸 참여 안내 글 전부(최대 3개)이고 재생 중인지는 보지 않는다. 에코 의심 전사는 열린 J1을 닫지 않는다 | 재생 끝 직후 늦게 확정되는 에코도 막는다 |
| JOIN-GATE-A17 | 새 운영 소스 `session/InputTranscriptGroup.java`를 `backend/coverage-scope/session-runtime.txt` · `judgment-turn.txt`에 더했다. 새 시험의 태그는 기능 이름 `join-gate`와 기존 `session-runtime`이다 | 팀 규칙(기능 이름 태그) |
| JOIN-GATE-A18 | prompt-v2의 J1 규칙 문장은 지시서 문장에 "J1에서"를 붙여 다른 지점에 번지지 않게 했고, 지시서의 `[J1]` 머리표는 선택지 절 머리표(`[J1] 질문`)와 겹쳐 쓰지 않았다 | 같은 지시문을 J2 ~ J6 · 연습도 쓴다 |
| JOIN-GATE-A19 | J1 합성 탐침의 COACH 글은 시드 파일(`infra/seed/003-u4a-reference.sql`)의 INTRO_FREE_TALK v2 글을 읽어 쓴다. 보고서에는 문장 대신 번호를 적는다 | 승인 문구를 코드에 다시 적지 않는다 · R-F14 |
| JOIN-GATE-A20 | 에코 기준 8자는 정규화 뒤 코드 포인트 수다 | 분류기와 같은 기준 |

## 6. 원 담당자와 맞출 것

지시서 4절의 질문이다. 답을 기다리지 않고 오른쪽 열의 방안으로 진행했다. 답은 PR 설명에 요약한다.

**성민(realtime 담당, U4b · U11 · U12 · 리팩토링)**

| # | 질문 | 이번에 진행한 방안 |
|---|---|---|
| 1 | JoinFlow · JudgmentCoordinator · JudgmentTurn · SessionExecutor · JoinTracker · prompt-v1을 건드리는 진행 중 작업이나 올리지 않은 브랜치가 있는가. 있으면 순서와 분담 | 없다고 보고 main `fec5285` 위에서 진행했다. 겹치면 이 브랜치를 그 뒤로 다시 맞춘다 |
| 2 | 10/18 live 스택 DB(도커 볼륨 `mujoong-live_*`)가 남아 있는가. 남아 있다면 (0)의 조회를 직접 돌려 결과 코드만 공유해 줄 수 있는가 | 조회 SQL과 절차를 `docs/live-run.md` 3절에 준비했다. 조회는 하지 않았다(7절) |
| 3 | (3)의 J1 대기 전사 방식과 (4)(c)의 만기 뒤 ACCEPT 처리에 동의하는가. 편차를 U11 설계 편차 표에 적을지, 새 문서에만 적을지 | (3)은 권장안 A로 구현했다. (4)(c)는 하지 않았다. 편차는 이 문서 4절(JG-D1 ~ D5)에만 적었다 |
| 4 | prompt-v2 방향(J1 규칙만 더함 · 안내 문장 화자는 COACH · 판 이름 `prompt-v2`)에 동의하는가 | 그대로 구현했다((5)) |
| 5 | 새 로그 사건 코드의 이름과 속성(`contract-model`은 core와 함께 쓰는 공통 모듈)에 동의하는가 | 지시서 이름으로 구현했다. `J1_SKIPPED`는 두지 않았다(A3) |
| 6 | 성민 문서인 `docs/live-run.md`에 진단 절차와 새 시험 기록을 더해도 되는가 | 3절에 진단 절차, 5절 추후 확인 1번 아래에 두 줄을 더했다. 실키 기록은 사람이 7절 양식으로 더한다 |
| 7 | PR 리뷰어를 맡아 줄 수 있는가 | 리뷰를 부탁할 예정(7절) |

이번 작업에서 더 생긴 확인 거리:

- 종료 확인 질문의 대답 분류도 넓어진다(JG-D5 · A9). 그대로 둘지, 종료 확인용 분류를 따로 둘지.
- J1 결과를 판정 기록 뒤에 넣는 순서(JG-D2)와 대기 칸 체인(A14)이 U11 W1 의도와 맞는지.
- 연습 세션(U12, INTRO_PRACTICE)도 같은 참여 관문 · prompt-v2를 쓴다. 태그 재시험(`practice-flow` 포함)은 통과했지만 연습 쪽 실키는 보지 않았다.

**도연**

- 10/20 로컬에서 ACTIVE에 갔을 때 어떤 SHA에서 무슨 말을 했는가(`docs/core-json-mode-requests.md:5`의 한 줄).
- `SessionExecutor`를 고치는 진행 중 작업이 있는가. 이 작업은 입력 전사 묶음(`groupInputDelta` · `onInputTranscriptQuiet` · `onConfirmedChildTranscript`)과 판정 협력 객체 `Env`를
  바꿨다. `217b4af`(연속 무음 봉인)와는 다른 자리다.

**동훈(인프라)**

- `infra/bin/log-hygiene-check` 허용 표(21종)와 `infra/ci/test-host-tools.sh`(21종 단언 · 새 사건 위반 0/1 사례)를 고쳤다. 진행 중인 인프라 PR(JIRA-25x)과 같은 파일이
  겹치는지 확인하고 리뷰를 부탁한다.
- demo(ECS) 프로필은 realtime Luna가 꺼져 있어 데모를 ECS에서 하면 (1) · (2) · (4) · (6)만 효과가 있다는 점을 공유한다.

## 7. 사람이 할 일

모델이 하지 않은 일이다. 하지 않은 확인을 한 것처럼 적지 않았다.

1. **R-F15 확인 기록**: 로컬 live 스택에서 `OPENAI_API_KEY`(GPT-Live · Luna)를 쓰기 전에, 확인한 사람이 `infra/RUNBOOK.md`의 확인 기록 표에 '환경: 로컬 live' 행을
   더하거나 기존 행이 로컬도 덮는다고 팀이 확인해 적는다. 기록이 없으면 2 · 3 · 5번을 하지 않는다. 제브 키는 쓰지 않는다(키를 비우면 STUB).
2. **10/18 판정 기록 조회**: 10/18 스택의 DB 볼륨이 남은 PC(성민 PC로 추정)에서 `docs/live-run.md` 3절의 SQL(결과 코드 열만)을 돌린다. 진단을 마치기 전에는
   `down.sh --purge`를 쓰지 않는다. 결과 코드만 `docs/live-run.md` 5절 "추후 확인할 것" 1번 아래에 적고, DB가 없으면 "기록 없음"이라고 적는다.
3. **실키 확인(지시서 7절)**: 두 PR이 들어간 브랜치나 main에서 `bash scripts/live/up.sh --local`. 팀원(성인) 목소리로 세션마다 하나씩 "응" · "응 좋아" · "어 할래"를
   말하고(1.5초 이상 쉰다), 보호자 화면 S-12 '자유대화 · 대화 중'과 로그 `SESSION_TRANSITION to=ACTIVE`를 본다. 로그는
   `docker compose -p mujoong-live logs realtime | grep -E '"event":"(SESSION_TRANSITION|JOIN_REPLY|JUDGMENT_TURN|LUNA_CALL|LATENCY_TURN)"'`.
   권장 추가 확인: "내 말이 들리니?" → 1초 쉼 → "응"((2)), 창 끝 직전 대답((4)), "싫어" → 대안 제안(거부 회귀), ACTIVE 뒤 무중 첫 음성 재생 · `LATENCY_TURN` 건수.
   결과를 `docs/live-run.md` 5절 끝에 아래 양식으로 더한다(전사 원문 · 로그 원문 · 키 금지).

   ```markdown
   ### 2026-10-2x 참여 확인 관문 재시험 (`<브랜치>` @ `<SHA 12자>`, `--local`, 실제 OpenAI 키 · 제브 STUB, `[실호출-합성]`)

   - 말한 사람: 팀원(성인) 목소리. 아이 음성 아님. 안내 음성: v2 합성 톤 자리표시(또는 v3)
   - R-F15: `infra/RUNBOOK.md` 단계 4 확인 기록 <행 날짜 · 확인자>

   | 세션 | 말한 문장 | JOIN_REPLY (joinReply / joinAction) | J1 (outcome / firstOption) | 도달 상태 | 비고 |
   |---|---|---|---|---|---|
   | 1 | "응" | ACCEPT / START | 없음 | ACTIVE | |
   | 2 | "응 좋아" | | | | |
   | 3 | "어 할래" | | | | |

   - ACTIVE 뒤 무중 첫 음성 재생: 예/아니오, `LATENCY_TURN` n건(참고)
   - 10/18 판정 기록 조회 결과(코드만) 또는 "기록 없음"
   - 증거 구분: 위 표는 실제 API · 합성(성인) 음성 · 실제 브라우저 기기다. 테스트 대역 결과와 섞지 않는다(R-M11)
   ```

4. **(6) 에코 점검**: 실키 로그에서 `JOIN_REPLY`의 `devicePlaying=true` 건수와 그 분류, `ECHO_SUSPECT` 건수를 센다. 안내 음성 v3(사람 목소리)가 들어오면 스피커
   음량을 키워 다시 센다.
5. **J1 합성 탐침(선택)**: R-F15 기록 뒤 그 PC의 셸 env로만 키를 넣고 `cd backend && ./gradlew :realtime-activity:lunaReachTest`. 결과는
   `backend/realtime-activity/build/luna-reach/j1-report.md`(번호 · 기대 · 결과 코드 · 1 · 2순위 · 지연만)이고 이 문서 8절에 `[실호출-합성]`으로 옮긴다.
6. **팀원 확인 · 공지**: 6절의 질문을 성민 · 도연 · 동훈에게 묻고 답을 PR 설명에 요약한다. 리뷰어: 성민(realtime 전체), 동훈(`infra/` 두 파일).
7. **브랜치 push · PR 두 개**: PR-A(머리 `57638c8`)와 PR-B(이 브랜치 끝). `ci-gate`가 통과한 뒤에만 병합한다(R-M1). 이 PR은 `contracts/`를 고치므로 모든 조건부 검사가 돈다.
8. **값 확정**: `input-utterance-gap`(임시 700ms)을 실키 로그의 `maxGapMs`로 확정한다. 바꾸면 `application.yml` · `contracts/timing.md` 14절 · 설정 레코드를 같은
   커밋에서 고친다(R-M5).
9. **이 작업 밖**: demo의 `mujoong.luna.enabled` 결정(ECS 배포), 안내 음성 v3 교체, 기기 '듣는 중' 표시.

## 8. 시험 결과

모두 이 컨테이너(CPU 4 · 메모리 15GB, 다른 브랜치 작업과 함께 돎)에서 가짜 공급자 · 가짜 판정기로 돌렸다(R-F1). 실제 AI 공급자는 부르지 않았다.

| 명령 | 결과 |
|---|---|
| `cd backend && ./gradlew --no-daemon :contract-model:check :realtime-activity:check -Pmujoong.test.db=docker --continue` (브랜치 끝 `5401f78` 코드) | BUILD SUCCESSFUL. contract-model 325건 통과 · 실패 0, realtime-activity 1,930건 통과 · 실패 0, JaCoCo 모듈 하한 검사 통과(줄 98.53% · 93.81%) |
| `cd backend && ./gradlew --no-daemon :core-api:test -Pmujoong.test.db=docker --continue` (contract-model을 함께 쓰는 앱) | 1,725건 중 실패 1건: `GraphWorkflowIT.realGraphSeedToU14CandidateToU10ApprovedVariantAndScopedDeletion`. 느린 컨테이너에서 Neo4j 초기 적재가 2초 트랜잭션 예산을 넘는 **알려진 기준선 실패**(main에서도 실패)이고 이 작업은 core · 그래프 경로를 바꾸지 않았다 |
| `cd backend && ./gradlew --no-daemon :realtime-activity:test -PincludeTags=practice-flow,session-runtime,voice-session,judgment-turn -Pmujoong.test.db=docker` ((2) 커밋 뒤) | 961건 통과 · 실패 0 |
| `cd backend && env -u OPENAI_API_KEY -u JEV_API_KEY ./gradlew --no-daemon :realtime-activity:lunaReachTest verifyLunaReachIsolation` | `LunaReachTest` · `JoinJudgmentReachTest` 모두 건너뜀(키 없음, 실호출 없음). `verifyLunaReachIsolation` 통과(check · test 그래프에 lunaReachTest 없음) |
| `bash infra/bin/infra-validate` | 8건 모두 통과(compose-config · compose-invariants · demo-profile · secret-env-files · nginx · shellcheck · migrations · bundle) |
| `bash infra/ci/test-host-tools.sh` | 44건 중 어긋남 0건(`log-hygiene-allowlist-matches-java(21종)` · `log-hygiene-join-gate-events` 포함) |

- 커밋마다 그 층의 시험을 좁게 돌렸고 모두 통과했다((0) 직후 realtime 전체 1,882건 통과 등). (1) 뒤 `session.*` · `dialog.*` 묶음에서
  `RealtimeDeletionHandlerIT` 1건이 기기 메시지 10초 대기 시간 초과로 한 번 실패했다. 그 시험만 다시 돌리면 4건 모두 통과했고 브랜치 끝 전체 check에서도
  통과했다. 이 작업이 바꾸지 않은 삭제 경로이고 그때 부하(load average 약 4)가 높았다 — 부하에 민감한 시험으로 본다.
- 바꾼 운영 파일의 줄 커버리지(모듈 전체 시험 기준): `InputTranscriptGroup` 100% · `JoinTracker` 100% · `JoinFlow` 93.0% · `JudgmentCoordinator` 93.1% ·
  `SessionExecutor` 90.8% · `EventCode` · `AttrKey` 100%.
- **돌리지 않은 것**(다음 단계 몫): 백엔드 전체 `./gradlew clean check verifyBootJarHasNoTestClasses -Pmujoong.test.db=docker --continue`, `bash scripts/verify.sh`,
  PR의 `ci-gate`(push하지 않았다).

증거 구분(R-M11):

| 확인 | 테스트 대역 | 합성 대화 | 실제 API | 실제 기기 |
|---|---|---|---|---|
| (0) 진단 로그 두 사건 · 허용 표 · 원문 없음 | 통과(JoinReplyLogTest · JoinJudgmentExecutorTest · PrivacyLogTest · test-host-tools) | 없음 | 없음 | 없음 |
| (1) 짧은 긍정 · 거부 우선 · 물음표 | 통과(JoinTrackerTest 24행 · SessionJoinTest) | 없음 | 없음 | 없음 |
| (2) 발화 간격 분할 | 통과(GenerationSendExecutorTest · InputTranscriptGroupTest) | 없음 | 없음 | 없음 |
| (3) J1 대기 칸 · 다시 판정 | 통과(JoinJudgmentExecutorTest, 가짜 판정기) | 없음 | 없음 | 없음 |
| (4) 창 끝 경계 · 말하는 중 | 통과(SessionJoinTest) | 없음 | 없음 | 없음 |
| (5) prompt-v2 · COACH 줄 | 통과(지시문 · 요청 스키마 · 실행기 시험) | 없음(J1 합성 탐침은 키 없이 건너뜀) | 없음 | 없음 |
| (6) 에코 의심 | 통과(SessionJoinTest · JoinTrackerTest) | 없음 | 없음 | 없음 |
| 완료 조건 2 · 3(실키 "응" · "응 좋아" · "어 할래" → ACTIVE) | 해당 없음 | 미실시 | 미실시 | 미실시(사람 몫, 7절) |

10/18 진단 결과(판정 기록 조회): **미조회**(사람 몫, 7절 2번). 실키 결과 표: **미실시**.

남은 위험 · 열린 질문:

- `input-utterance-gap` 700ms가 실공급자 전사 시간선에서 맞는지(너무 짧으면 한 발화가 나뉘어 앞 조각이 UNCLEAR → 되묻기가 된다). 실키 `maxGapMs`로 확정한다.
- 물음표 규칙 · 첫 낱말 규칙이 GPT-Live 실전사 모양(띄어쓰기 · 문장부호 · 영어 전사)에서 맞는지. `JOIN_REPLY`의 `textLen` · `wordCount`로 간접 확인한다.
- 종료 확인 대답 분류가 함께 넓어진 것(JG-D5)을 원 담당자가 받아들일지.
- (4)(c) 만기 뒤 ACCEPT 처리는 결정이 남았다(지금은 재안내 먼저).
- 안내 음성 v3(사람 목소리) 뒤 에코 위험 재확인, 8자 기준이 맞는지.
- demo(ECS)에서는 Luna가 꺼져 (3) · (5)가 돌지 않는다.
