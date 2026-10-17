# U11 판정 턴 기반 (u11-judgment-turn) 구현 지시

realtime 줄의 첫 볼트(B3)다. realtime 줄은 **U11 → U13 → U12** 순서로 한 담당자가 차례로 한다. 셋 다 `SessionExecutor`(3,388줄) · `OutputGate`를 고치기 때문이다(`docs/units/README.md` 「U8 ~ U15 문서 동기화 볼트」).
core 줄(U15 → U14 → U10)은 다른 담당자가 같은 시기에 진행한다. 파일이 겹치지 않는다.

## 시작 전 상태
- U8 · U9가 `main`에 합쳐졌다(PR #21, `main` CI #60 skeleton까지 통과).
- 브랜치: `main`(7e30ee8)에서 `unit/u11-judgment-turn`을 딴다.
- 마이그레이션: **realtime V5**(`V5__u11_judgment_turn.sql`). realtime은 지금 V4까지다. U13 = V6, U12 = V7이 뒤를 잇는다.

## 첫 작업: 위험 확인 (중단 조건)
설계를 뒤집을 수 있는 가장 큰 위험은 R1이다. GPT-Live가 서버 쪽 음성 전송을 지원하지 않을 수 있다(조원 예제는 WebRTC만 쓴다).
- 그래서 판정 턴 설계 전에 GPT-Live 실호출(FR12.2)을 짧게 당겨 합성 대화로 아래를 확인한다.
  - 서버 쪽 음성 전송이 되는지(ADR-007)
  - 세션 생성 · 지시 주입 · 종료 형식
  - 말 끝 신호
- 참고: 조원 하네스의 `POST /v1/live/sessions` · `/attach` 형식.
- 실호출은 사람이 키를 넣고 직접 돌린다. 시험 · CI · `verify.sh`에는 넣지 않는다(R-F1). 합성 자료만 쓰고, 넣기 전에 `infra/RUNBOOK.md` 단계 4의 R-F15 확인 기록을 남긴다. 결과는 `[실호출-합성]`으로 따로 보고한다(R-M11).
- **안 되면 이 볼트를 멈추고** 사람과 설계를 다시 정한다(`risk-and-sequencing-rationale.md` 중단 조건). core 줄은 영향 없이 계속한다.

## 범위
정본은 `unit-of-work.md` U11 절과 `bolt-plan.md` B3이다. 만들 것:
- **진행 중 판정(FR3, C-DS4)**
  - `JudgeClient` 포트: U9 `RealtimeLunaClient`(`JUDGMENT_LIVE`, 재시도 없음)를 쓴다. 기존 `EvaluationProvider`와는 별개다.
  - 판정 지점 · 선택지는 U8 판 리소스 `judgment/option-sets-v1.json`을 쓴다.
  - 1 · 2순위 확률로 보류를 정한다. 하한 · 차이는 설정값(`mujoong.judgment.min-confidence` · `min-margin`)이고 시간 값이 아니다.
  - 4초(`timing.judgment.live-budget`, 설계 편차 U11-b로 2초에서 늘림) 초과 · 형식 오류는 재시도 없이 보류다. 보류를 교정 행동으로 바꾸지 않는다.
  - 판정마다 `judgment_live`(realtime V5)를 남긴다.
  - 다음 행동 지시에는 판정 라벨 · 확률 · 기준을 넣지 않는다(FR3.7).
- **종료 표현(FR3.5)**: 등록 종료 표현은 판정 없이 바로 처리한다. 판정 턴 중 종료 표현이 나오면 판정을 멈춘다.
- **판정 턴 출력 보류(FR5)**
  - 판정 턴에는 `OutputGate`를 닫고, 그동안 온 GPT-Live 자동 응답 음성을 버린다.
  - 버린 출력 전사는 `judgment_live`에만 남기고, 대화 기록 · 보호자 중계에는 남기지 않는다.
  - 판정 뒤 다음 행동 지시(`session.instructions.correct` + `session.response.create`, C-DS15)를 넣고, 그 지시 뒤 응답만 통과시킨다.
  - 말 끝 뒤 10초(`timing.judgment.response-ready`) 안에 응답이 없으면 보류로 보고 무난한 되묻기 · 맞장구를 지시한다.
  - 판정 턴 중 음성 대화 장애가 나면 판정을 버리고 '장애 취소'로 기록한다. 늦게 온 전사 · 판정은 쓰지 않는다.
  - 보낼 응답이 없으면 기기 '생각 중'을 유지한다.
- **자유대화 J1 · J6(FR4.8)**: 상태 기계 없이 GPT-Live 자동 응답으로 진행하고 J1과 J6만 판정한다.
- **확장점 넓히기(C-DS3)**
  - `PracticeOutput`에 `requestJudgment` · `announceRoleSwitch` · `setThinking`을 더한다.
  - `ExtensionEvent`에 `JUDGMENT_RESULT` · `JUDGMENT_ABSTAINED` · `HELD_OUTPUT_TRANSCRIPT` · `GUARDRAIL_BLOCKED`를 더한다.
  - 기존 U5b 확장 구현은 그대로 돈다. 더한 능력은 기본 구현(`false` 반환)으로 둔다.
  - 확장점은 실행부를 부르지 않는다. 실행부 → 확장점 한 방향만 둔다(project.md 교정).
- **기록 캡처 이벤트(C-DS2)**: 발행은 지금처럼 실행부가 한다. 새 형식 구분은 `snapshotVersion`이 아니라 `flowVersion`이다(편차 (U8-d)). 채우는 일은 U12 몫이다.

범위 밖: U12 연습 상태 기계 · 역할 정책, U13 제브 단계(게이트에 얹을 자리만 둔다), GPT-Live 선 형식 변경(FR12.2 결과가 나오기 전까지는 바꾸지 않는다, ADR-007).

## 정본
- 이 볼트의 인셉션 기록: `R = aidlc/spaces/default/intents/261015-doc-sync-code-update/`

| 무엇 | 어디 |
|---|---|
| 범위 · 완료 조건 | `R/inception/units-generation/unit-of-work.md` U11 절, `R/inception/delivery-planning/bolt-plan.md` B3 |
| 위험 · 중단 조건 | `R/inception/delivery-planning/risk-and-sequencing-rationale.md` R1 · R3 · R4 · R8, `external-dependency-map.md` E1 · E2 · E8 |
| 요구사항 | `R/inception/requirements-analysis/requirements.md` FR3.1 ~ FR3.7 · FR4.8 · FR5.1 ~ FR5.4 · FR12.2 |
| 스토리 · 수용 기준 | `R/inception/user-stories/stories.md` US1.7 · US1.8 · US1.10 · US2.5 |
| 컴포넌트 · 결정 | `R/inception/domain-design/components.md` LiveJudgment · SessionRuntime · OutputGate · VoiceRelay, `decisions.md` ADR-001 · ADR-005 · ADR-007, 검토 지적 R-03 · R-04 · R-05(`R/inception/domain-design/reviews/`) |
| 계약 | `R/inception/contract-design/contract-summary.md` C-DS2 · C-DS3 · C-DS4 · C-DS15 · 4절 열린 질문(GPT-Live 형식, `DeletionCompleted.counts`), 검토 지적 R-05(a) · R-06(`R/inception/contract-design/reviews/`) |
| 계약 파일 | `contracts/schemas/ai-judgment-request.schema.json` · `ai-judgment-output.schema.json` · `practice-record-v2.schema.json`, `contracts/ai-io.md` 3절 · 10절, `contracts/events.asyncapi.yaml`, `contracts/timing.md` 13절 |
| U8 · U9 결과 | `R/construction/u8-contracts-catalog/` · `R/construction/u9-luna-clients/`, 편차 `contracts/deviations.md` (U8-a) ~ (U8-e) · (U9-a) ~ (U9-c) |
| 기존 코드 | `realtime/session/SessionExecutor` · `PracticeSessionExtension`, `realtime/gate/OutputGate`, `realtime/luna/RealtimeLunaClient`, `realtime/content/LiveContentRegistry`, `resources/judgment/option-sets-v1.json` |

## 정해 둔 값
- 마이그레이션 realtime `V5__u11_judgment_turn.sql`(`judgment_live`). 더하기만 한다(R-M3).
- 시간 키 `judgment.live-budget`(4s, U11-b로 2s에서 늘림) · `judgment.response-ready`(10s)는 U8이 이미 더했다. 새 시간 키가 필요하면 R-M5 세 곳을 함께 고친다.
- 판정 하한 · 차이 설정 키가 없으면 더하고 `ConfigKeyContractTest` 규칙을 지킨다.
- 시험: 태그 `u11`, 범위 `backend/coverage-scope/u11.txt`, 범위 줄 80%. GPT-Live · Luna는 가짜 공급자만 쓴다(R-F1).
- 기존 U4b · U5b 동작이 깨지지 않게 레거시 연습 경로는 떼지 않고 공존시킨다(ADR-001, 위험 R4). 기존 시험과 `verify.sh` 끝-끝을 단계마다 돌린다.
- 건드리지 않는 것: `backend/core-api`, 다른 단위의 시험 단언과 커버리지 하한(R-F2).

## 다른 단위와 맞물리는 곳
1. **U13(다음 볼트)**: 제브 단계는 이 볼트가 닫은 게이트 위에 얹는다. 게이트에 그 자리를 남긴다.
2. **U12(그다음)**: 넓힌 확장점을 구현만 한다. U12는 U13 뒤 대역(C-DS1 `ContractStubServer`)으로 일부 개발하고, U10 병합 뒤 마저 끝낸다.
3. **U14(core 줄)**: `SessionRecordCaptured.record.practice`를 받는다. 이 볼트는 발행 경로만 지키고 필드 모양은 U8 계약 그대로 둔다.

## AI-DLC로 진행하는 법
1. `unit/u11-judgment-turn` 브랜치에서 아래를 입력한다.

   ```
   /aidlc --new-intent --scope refactor "U11 판정 턴 기반(u11-judgment-turn) 구현. 지시 docs/units/u11/brief.md를 정본으로 한다. 인셉션 산출물은 261015-doc-sync-code-update 기록의 것을 그대로 쓴다."
   ```

2. 작업 기록 이름은 `u11-judgment-turn`으로 한다.
3. `/aidlc compose "deployment-pipeline과 deployment-execution을 빼줘"`를 입력하고 승인한다.
4. `/aidlc --depth Standard --test-strategy Standard`를 입력한다.
5. 단계: 기존 코드 파악 → Requirements Analysis → Functional Design → Code Generation → Build and Test.
   - Requirements Analysis는 FR3 · FR4.8 · FR5 · US1.7 · US1.8 · US1.10 · US2.5를 다시 묶는다('원 FR3.1'처럼 구분).
   - Requirements Analysis 전에 위 「첫 작업」의 실호출 확인 결과를 받는다. 안 되면 멈춘다.
   - Functional Design의 첫 작업은 Domain Design R-03 · R-04 · R-05와 Contract Design R-05(a) · R-06 정리다.
   - Functional Design 결과는 `docs/units/u11/design.md`에도 옮긴다.
   - Code Generation 계획은 이 순서로 나누고 단계마다 `feat(u11): …`로 커밋한다.
     1. V5 + `JudgeClient` + 보류 규칙 + `judgment_live`
     2. 게이트 보류 · 지시 뒤 통과 · 10초 응답 준비 · 장애 취소
     3. 종료 표현 · 자유대화 J1 · J6
     4. 확장점 넓히기
6. 자기 기록 폴더(`aidlc/spaces/default/intents/<날짜>-u11-judgment-turn/`)는 브랜치에 함께 커밋한다.

## 끝나면
`docs/units/u11/report.md`에 아래를 쓰고 `main`으로 PR을 연다. `ci-gate`가 초록이면 합친다. 합친 뒤 `main` skeleton 결과도 확인한다(skeleton은 PR에서 돌지 않는다).
- 만든 것, 시험 결과
- 계약 편차 `(U11-a)`, 가정, 설계와 달라진 곳
- GPT-Live 실호출 확인표(`[실호출-합성]`)와 대역 결과를 나눠 적는다
- 남은 일
