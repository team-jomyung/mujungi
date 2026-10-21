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
