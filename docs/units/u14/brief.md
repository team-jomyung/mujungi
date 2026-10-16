# U14 목표 · 결과 (u14-goal-result) 구현 지시

core 줄의 두 번째 볼트(B6)다. **U15가 `main`에 합쳐진 뒤** U15가 들어간 `main`에서 `unit/u14-goal-result`를 딴다. U15가 늦으면 `unit/u15-knowledge-graph`에서 따고, U15 병합 뒤 `main`을 merge해 따라잡는다.
마이그레이션은 **core V9**(`V9__u14_goal_result.sql`)다. V8(U15)이 먼저 들어가야 한다(Flyway `outOfOrder` 꺼짐).

## 배경
- 목표 후보를 미리 정한 목록에서 고르지 않고, Luna가 아이 기록에서 만든다(FR8). 보호자가 고르기 전에는 확정하지 않는다(FR8.4).
- 새 연습 흐름(U12)은 '되묻기 뒤 전달'(`AFTER_REASK`)과 1 · 2개로 고정되지 않은 장면 수를 만든다. 결과 · 리포트가 이것을 받으면서도 과거 U5b 결과는 원래 뜻으로 읽어야 한다(FR7).
- 27 화면 모양은 바꾸지 않는다. `AFTER_REASK`는 27 표시에서만 `DONE_IMMEDIATE` 칩으로 접는다. 저장 값 · API · 리포트 집계에는 사실대로 남긴다(ADR-003, C-DS12).

## 범위
정본은 `unit-of-work.md` U14 절과 `bolt-plan.md` B6이다. 만들 것:
- **PracticeGoal이 후보 · 담은 목표를 소유**(ADR-002, core V9)
  - 지금 `core.practice`의 `CandidateRegistry` · `PracticeGoalRepository`와 `core.result`의 `CandidateScreen`을 옮긴다.
  - Luna 목표 후보 생성 · 검증 · 저장(`LunaPurpose.GOAL_CANDIDATES`, `ai-goal-candidate-output.schema.json`).
  - 담기는 후보 버전을 고정한다. 과거 행동 정의 목표도 계속 읽는다(`LEGACY_BEHAVIOR`, AC4.6.4).
  - `skill_ref` 검증: U15 `seedSummaries` 목록에 있는 id이거나 `NONE`이어야 한다(FR8.2).
  - `skill_ref = NONE` 후보는 U15 새 씨앗 생성으로 넘긴다(FR8.3). 부르는 시점은 여기서 정한다.
- **담은 목표 읽기 포트 `SavedGoalReader`(C-DS10)**: U10이 쓴다. 이 볼트가 소유한다.
- **결과 쪽(FR7)**
  - `AFTER_REASK` 시점을 더한다. 27 칩 매퍼를 저장 · 27 · 리포트 용도로 나눈다(Domain Design R-01).
  - 장면 수 고정을 푼다(`scene_no IN (1,2)` 제약 변경, R-M3 근거 필요).
  - 새 기록 사실을 입력으로 받는다. 형식은 `practice-record-v2.schema.json`(`flowVersion` 필수)이다. `flowVersion`이 없으면 U5b 형식이다(편차 (U8-d)).
  - 기록용 재판정(`REJUDGMENT`)을 하고, 진행 중 판정과 다르면 `differs_from_live`로 표시한다.
  - 버전별 결과 읽기(FR7.5).
  - 결과 작업 재시도를 추가 최대 3회(5 · 15 · 30초)로 맞춘다(FR7.6). `ai-io.md` 6.9의 '작업당 총 3회'도 함께 고친다(10.3).
  - 2차 검사는 기존 `classify` · `OutputChecker`를 유지한다. 제브로 보내지 않는다(FR7.7).
- **보호자 REST(C-DS12)**: 기존 경로에 값 · 선택 필드만 더한다. `fourElements` · `skillRef` · `reasonText`는 보호자 응답에 싣지 않는다(AC4.5.5).
- **삭제 참여 확장**(Domain Design R-02).

범위 밖: 프런트 27 화면 변경(칩 enum이 그대로라 필요 없다), U12의 기록 사실 생성.

## 정본
- 이 볼트의 인셉션 기록: `R = aidlc/spaces/default/intents/261015-doc-sync-code-update/`

| 무엇 | 어디 |
|---|---|
| 범위 · 완료 조건 | `R/inception/units-generation/unit-of-work.md` U14 절, `R/inception/delivery-planning/bolt-plan.md` B6 |
| 요구사항 | `R/inception/requirements-analysis/requirements.md` FR7.1 ~ FR7.7 · FR8.1 ~ FR8.5 |
| 스토리 · 수용 기준 | `R/inception/user-stories/stories.md` US4.1 ~ US4.6 |
| 화면 | `R/inception/refined-mockups/mockups.md` 27 · 목표 칸, 화면 설계 v1.5j(문서끼리 다르면 화면 설계가 맞다) |
| 컴포넌트 · 결정 | `R/inception/domain-design/components.md` PracticeGoal · ResultPipeline, `decisions.md` ADR-002 · ADR-003, 검토 지적 R-01 · R-02 · R-06 · R-07(`R/inception/domain-design/reviews/`) |
| 계약 | `R/inception/contract-design/contract-summary.md` C-DS2 · C-DS6 · C-DS10 · C-DS12 · 4절 열린 질문(4요소 이름, `displayEvidence`), 검토 지적 R-01 · R-09(`R/inception/contract-design/reviews/`) |
| 계약 파일 | `contracts/schemas/ai-goal-candidate-output.schema.json` · `ai-rejudgment-output.schema.json` · `practice-record-v2.schema.json` · `session-result-response.schema.json`, `contracts/ai-io.md` 6절 · 10절, `contracts/parent-api.yaml`, `contracts/events.asyncapi.yaml` |
| Luna 호출 방식 | U9 설계 `R/construction/u9-luna-clients/functional-design/`, `contracts/ai-io.md` 10.10, 편차 (U9-a) ~ (U9-c) |
| 기존 코드 | `core/practice/`(`CandidateRegistry` · `PracticeGoalRepository`), `core/result/`(`CandidateScreen` · `ResultTextGateway` · `ResultAiSchema`), `core/luna/adapter/LunaResultTextProvider`, `common/deletion/DeletionParticipant` |

## 정해 둔 값
- 마이그레이션 core `V9__u14_goal_result.sql`. `attempt_count` 제약과 `scene_no IN (1,2)` 제약 변경은 기존 표 제약이라 `infra/migration-approvals.txt`에 근거를 적는다(R-M3).
- Functional Design 첫 작업은 Contract Design 지적 R-01이다. 실제 `session-result-response.schema.json`과 대조하고, 엄격 스키마 변경은 깨는 변경으로 분류한다(`contracts/README.md` 규칙).
- Luna 재시도: `GOAL_CANDIDATES` · `REJUDGMENT` · `RESULT_TEXT`는 `JOB_ATTEMPT` 분류다. 호출 안에서는 재시도하지 않고 결과 작업 재시도가 다시 한다(`ai-io.md` 10.3). 기존 결과 경로는 지금 `json_object`로 1회만 부른다. 새 스키마로 바꾸는 일이 이 볼트 몫이다(10.10).
- 삭제 순서 상수: 기존 `ORDER_RESULT = 70` · `ORDER_PRACTICE = 150`을 쓴다. 새 참여자가 생기면 U15가 정한 값(제안 80)과 겹치지 않게 한다.
- 시험: 태그 `u14`, 범위 `backend/coverage-scope/u14.txt`, 범위 줄 80%. Luna는 가짜 공급자만 쓴다(R-F1).
- 건드리지 않는 것: `backend/realtime-activity`, 27 화면 코드, 다른 단위의 시험 단언과 커버리지 하한(R-F2).

## 다른 단위와 맞물리는 곳
1. **U15(앞 볼트)**: `seedSummaries`로 `skill_ref`를 검증하고, `NONE` 후보는 새 씨앗 생성으로 넘긴다.
2. **U10(다음 볼트)**: `SavedGoalReader`로 담은 목표의 4요소 · `skill_ref`를 읽는다. 포트 모양은 C-DS10 그대로 둔다.
3. **U12(realtime 줄)**: 새 기록 사실은 `SessionRecordCaptured.record.practice`(U8 계약)로만 받는다. U12가 합쳐지기 전에는 `practice-record-v2.schema.json` 예시로 만든 대역으로 시험한다.
4. **U6 · U5b 기록**: 과거 결과가 섞인 리포트 집계 시험을 둔다(B6 '보여 줄 것').

## AI-DLC로 진행하는 법
1. `unit/u14-goal-result` 브랜치에서 아래를 입력한다.

   ```
   /aidlc --new-intent --scope refactor "U14 목표 · 결과(u14-goal-result) 구현. 지시 docs/units/u14/brief.md를 정본으로 한다. 인셉션 산출물은 261015-doc-sync-code-update 기록의 것을 그대로 쓴다."
   ```

2. 작업 기록 이름은 `u14-goal-result`로 한다.
3. `/aidlc compose "deployment-pipeline과 deployment-execution을 빼줘"`를 입력하고 승인한다.
4. `/aidlc --depth Standard --test-strategy Standard`를 입력한다.
5. 단계: 기존 코드 파악 → Requirements Analysis → Functional Design → Code Generation → Build and Test.
   - Requirements Analysis는 FR7 · FR8 · US4.1 ~ US4.6을 다시 묶는다('원 FR7.1'처럼 구분).
   - Functional Design 결과는 `docs/units/u14/design.md`에도 옮긴다.
   - Code Generation 계획은 이 순서로 나누고 단계마다 `feat(u14): …`로 커밋한다.
     1. V9 + PracticeGoal 소유 이전 + `SavedGoalReader`
     2. Luna 목표 후보 + `skill_ref` 검증 + 새 씨앗 연결
     3. 결과 쪽(`AFTER_REASK` · 장면 수 · 새 기록 사실 · 재판정 · 재시도)
     4. 보호자 REST + 삭제 참여
6. 자기 기록 폴더(`aidlc/spaces/default/intents/<날짜>-u14-goal-result/`)는 브랜치에 함께 커밋한다.

## 끝나면
`docs/units/u14/report.md`에 아래를 쓰고 `main`으로 PR을 연다. U15 병합 뒤, `ci-gate`가 초록이면 합친다.
- 만든 것, 시험 결과
- 계약 편차 `(U14-a)`, 가정, 설계와 달라진 곳
- 증거는 종류별로 따로 적는다: 테스트 대역 / 합성 대화 / 실호출(R-M11)
- 남은 일
