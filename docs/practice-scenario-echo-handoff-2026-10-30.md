# 연습 상황 생성 · 지식그래프 · 입력 전사 메아리 작업 인계 (2026-10-30)

브랜치 `작업중/practice-scenario-graph-echo`(PR 없음). `main`의 PR #88 · #100 병합 뒤 이어서 한 작업이다. 다른 환경에서 이어 하려면 이 문서부터 읽는다.

## 1. 출발점과 증상

2026-10-30 실키(live 스택 · 실제 Luna · GPT-Live · 실기기)에서 본 것.

1. 지식그래프 씨앗이 있는 목표("모르는 정보 설명하기")의 연습 시작이 계속 실패했다.
2. 첫 "대상 이름 밝히기" 연습에서 아이가 하지 않은 말(무중이가 읽을 승인 문장)이 아이 전사로 들어갔다.
3. 두 번째 "대상 이름 밝히기" 연습의 흐름이 어색했다(사전 연습 · 장면 안내 · 마무리).
4. 연습 장면 노출 정리가 10초마다 실패했고(`PracticeExposureReconciliationFailed`), 변형 계획이 늘 비었다(`ScenarioGraphFallback_PLAN_EMPTY`).

## 2. 한 일 (편차 번호는 `contracts/deviations.md`)

| 편차 | 내용 | 핵심 파일 |
|---|---|---|
| FT-1030-g | 노출 정리가 새 연습 흐름 기록(`flowVersion`, `scenes[].scenarioRef`)도 읽는다. 밀린 세션이 정리되고 최근 노출 이력(`history`)이 채워진다 | `practice/start/RecordedScenarioExposures` |
| FT-1030-h | "대화하는 상대"처럼 사람을 정하지 않는 '…상대' 표현은 씨앗 관계 코드로 그래프 좌표를 맞춘다(변형 계획이 비지 않게) | `practice/StructuredScenarioFlow.graphRelation` |
| FT-1030-i | 변형 계획이 있어도 거절된 BASE는 변형 라운드로 보내지 않고, 사유 · 지적 필드를 넘겨 그래프 없이 BASE만 다시 만든다. 변형 라운드는 승인된 BASE 위에서만 하고 실패하면 그 BASE를 발급한다 | `practice/ScenarioScriptGenerator` |
| FT-1030-j | 장면 문장에 가나 · 한자가 섞이면 승인과 관계없이 `FOREIGN_SCRIPT`로 고쳐 쓰게 한다 | 같은 파일, 생성 v7 |
| FT-1030-k | 생성 v7 · 검토 v6: 아이의 원래 어려움을 적은 불변 문장은 "그 실수가 생길 조건"으로 보존을 판단, 목표 표현이 장면 사실이면 안내에 사실로 주는 것은 정답 노출이 아니다 | `prompts/scenario/generation.v7.txt` · `review.v6.txt` |
| FT-1030-l | `REPETITION`도 마지막 수정 라운드를 쓴다. 생성 v7은 최근 노출의 장소 · 활동 조합을 피한다(반복 판정 기준은 그대로 — 상황극 반복은 허용 안 함, 사용자 결정) | 생성기 · 생성 v7 |
| FT-1030-m | 목표 후보 지시 `goal-candidates.v5`: 목표 표현 · 네 요소에 이번 대화의 특정 물건 · 화제를 넣지 않고 `○○` 자리 표시로 쓴다 | `prompts/goal/goal-candidates.v5.txt`, `practicegoal/GoalCandidateGateway` |
| FT-1030-n | 장면 생성 · 검토 호출 한도를 `mujoong.text-ai.scenario.request-timeout` 15초로 분리(공통 10초 · 준비 예산 60초 그대로) | `textai/TextAiProperties`, `luna/CoreLunaClient`, `application.yml`, `contracts/timing.md` |
| FT-1030-o | 변형 라운드에서 BASE는 서버가 승인본으로 고정하고 모델에게서는 VARIANT만 받는다 | `ScenarioScriptGenerator.pinBase` |
| FT-1030-p | 변형 계획기의 축 순서를 장소 → 활동 → 참여자로(이름순으로 늘 참여자만 바뀌던 것) | `knowledge/VariantPlanner` |
| FT-1030-q | 승인 문장 낭독 대기 중 연 아이 입력 묶음은 확정 때 기록하고, 낭독 문장과 같으면(6자 이상) 버린다. 진단 코드로 조건을 모은다 | realtime `session/SessionExecutor` |

진단 코드(사유 코드만, 원문 없음): `ScenarioVariantPlan_*` · `ScenarioVariantBase_*` · `ScenarioReducedReview_*` · `ScenarioVariantBasePinned` · `ScenarioForeignScriptText` · `PracticeChildInputDuringApprovedSpeech_*` · `PracticeChildInputTimeline_*` · `PracticeChildTranscriptEchoDropped`.

## 3. 검증 결과

### 테스트 대역
- core-api 단위 + DB 없는 통합 1,360개, contract-model 405개, realtime 단위 + DB 없는 통합 1,955개 통과(정적 검사 포함).
- DB가 필요한 통합 시험(`@Tag("db")`)은 돌리지 않았다. CI에서 확인한다.
- 시험을 고친 곳(약화 아님, 설계 변경에 맞춤): `PlannedGenerationIT`의 "거절된 BASE도 변형 라운드" 시험을 FT-1030-i 설계로 바꿈, `ScenarioGenerationTest.twiceRejectedNeverReturnsIssuableScript` 호출 수 2 → 3(발급하지 않는다는 단언은 그대로).

### 실호출(합성 · 실제 목표)
- 합성 목표 3종 × 3회(생성 v6 · 검토 v5 단계): 첫 초안 승인 4/9 → 7/9.
- 예전 의자 목표("모르는 정보 설명하기", v4로 만든 목표) 직접 시험: 시작 2/10 성공 → 원인은 목표가 특정 물건에 묶여 반복 · 정답 경계가 얽힘.
- 15:11 자유대화(`146a4e8b`) 결과를 DB에서 `FAILED`로 바꾸고 결과 재시도 API로 다시 돌려 v5 목표 "질문 정보 구체화"(목표 표현 "내가 좋아하는 점은 ○○야.")를 만듦. 의자 후보 2개는 후보에서 무효 처리됨(저장된 연습 목표 둘은 목록에 남아 있음).
- v5 목표 직접 시험(V1 · V2 반영 뒤): 시작 4/5 성공. 변형 장면이 실제로 발급된 경우는 아직 0번(남은 일 2).
- 원문 진단(`ScenarioReplayLiveTest`, 실제 입력 재현): 검토 거절은 대부분 타당. 생성기가 고칠 때마다 다른 실수(이른 단서 · 목표 불일치)를 낸다.
- GPT-Live 메아리 재현 실험(`GptLiveInstructionEchoTest`): 무음 입력 · 말소리 뒤 낭독 각 여러 번 — 재현 안 됨.

### 실기기
- FT-1030-q(메아리 방어)는 실기기로 확인하지 않았다.

## 4. 남은 일

1. **[실기기] FT-1030-q 확인**: 실기기 연습 뒤 realtime 기록에서 `PracticeChildInputDuringApprovedSpeech_*` · `PracticeChildInputTimeline_*` · `PracticeChildTranscriptEchoDropped`를 본다. 현상이 다시 나면 시간선 코드로 원인 조건(지시 구간 앞 · 안 · 뒤)을 좁히고, 정상 아이 말이 잘못 버려지지 않는지 본다.
2. **변형 장면 품질**: 변형 라운드에서 `TRANSFER_MISMATCH`(기회 문장 구조 변경) 또는 모델이 VARIANT를 빼고 보냄. 변형 라운드 생성 지시에 "기회 문장 구조 유지 · VARIANT 반드시 출력"을 더하는 안.
3. **생성 품질**: 고쳐 쓸 때마다 새 이른 단서(`EARLY_CUE`, 여는 말이 답을 먼저 말함) · 목표 불일치가 나온다. 원문 진단으로 사례를 더 모아 생성 v7 점검 목록을 보완.
4. **3번 흐름 수정(사용자 결정: 제안대로 진행)**:
   - 사전 연습에서 한 발화가 둘로 나뉘면 앞 조각만 판정되고, 뒤 조각은 판정 없이 사전 연습을 끝낸다(`PracticeConversation.judge` → `requestJudgment` 거절 → `finishPreparation(false)`). 나뉜 조각을 이어 판정한다.
   - 사전 연습 예시가 장면의 정답 물건을 쓴다(사전 연습 지시가 장면을 참고). 예시가 장면 정답을 쓰지 않게 한다.
   - 승인 문장을 다르게 읽은 출력(장면 안내를 다시 말하고 여는 말을 덧붙임, `PracticeApprovedTextDivergedChecked`)을 내보내지 않게 한다.
5. **목표 정리**: 의자에 묶인 예전 저장 목표("모르는 정보 설명하기" · "대상 이름 밝히기")를 남길지 보호자 화면에서 정한다.
6. **PR · Jira**: 이 브랜치는 PR을 만들지 않았다. 이어 받은 뒤 `main`(현재 PR #102까지)을 병합하고 PR을 연다. 편차 FT-1030-g ~ q, 지운 · 바꾼 시험(위 3절)을 PR 본문에 적는다. Jira 이슈는 아직 없다.

## 5. 이어 하는 방법

- live 스택: `bash scripts/live/up.sh --local`(키는 `~/.config/mujoong/live.env`). 다른 작업 트리에서 띄우면 `build/live/env`가 새로 만들어져 DB 볼륨 비밀번호와 어긋날 수 있다 — 볼륨을 만든 작업 트리의 `build/live/env/*.env`(compose.env 제외)를 복사한다.
- 연습 시작 직접 시험: `python3 -I tools/diagnostics/practice-start-trial.py start "질문 정보 구체화" 5`. 기록 요약은 core-api 로그의 `LUNA_CALL`(SCENARIO_*) · `APP_ERROR`(Scenario*) 사유 코드로 본다.
- 실호출 진단(사람이 키를 넣고, 기본 test에서 빠짐):
  - `./gradlew :core-api:lunaLiveTest --tests '*ScenarioReadinessLiveTest'` — 합성 목표 3종(`SCENARIO_READINESS_ROUNDS` · `SCENARIO_READINESS_CONTINUE`).
  - `./gradlew :core-api:lunaLiveTest --tests '*ScenarioVariantReviewLiveTest'` — 같은 BASE를 변형 계획 있음 · 없음으로 검토 비교.
  - `SCENARIO_REPLAY_INPUT=<입력 JSON> ./gradlew :core-api:lunaLiveTest --tests '*ScenarioReplayLiveTest'` — 실제 연습 입력 재현, 모든 초안 · 검토를 로컬 파일에만 남김. 입력 JSON(`{payload, targetExpression}`)은 로컬 DB(`core_scenario`의 relation · opportunity · goal_context, 최근 EXPOSED 3건, Neo4j 씨앗)에서 뽑아 저장소 밖에 둔다.
  - `./gradlew :realtime-activity:gptLiveTest --tests '*GptLiveInstructionEchoTest'` — 승인 낭독 지시와 입력 전사 메아리 실험.
- 결과 작업 다시 돌리기(목표 후보 재생성): `core.core_session_result_state.job_status`를 `FAILED`로 바꾼 뒤 `practice-start-trial.py retry-result <sessionId>`.
