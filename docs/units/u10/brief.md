# U10 상황 생성 (u10-scenario-generation) 구현 지시

core 줄의 마지막 볼트(B7)다. **U14가 `main`에 합쳐진 뒤** `main`에서 `unit/u10-scenario-generation`을 딴다. U14가 늦으면 `unit/u14-goal-result`에서 따고, U14 병합 뒤 `main`을 merge해 따라잡는다.
마이그레이션은 **core V10**(`V10__u10_scenario_generation.sql`)이다. V8(U15) · V9(U14)가 먼저 들어가야 한다.

realtime 줄의 U12(새 연습 흐름)가 이 볼트의 대본을 받는다(C-DS1). U12 담당자는 C-DS1 선택 필드를 계약 PR로 먼저 넣고 대역으로 개발한다. **그 계약 PR이 먼저 합쳐져 있으면 그대로 쓰고, 고칠 곳이 있으면 U12 담당자와 함께 고쳐 같은 날 합친다**(`contracts/README.md` 깨는 변경 규칙).

## 배경
- 연습 상황을 하나 만들어 내보내는 순서: (그래프 맥락이 있으면 구조 맥락 · 변형 계획 조회) → Luna 생성 → **생성과 분리한** Luna 자체 검토 → 서버 독립 검사 → (반려면) 재생성(FR2.1).
- 이 전체를 지금 U5a 예산 **15초** 안에 한다. 예산은 늘리지 않는다. 넘으면 대안(미리 생성 · 캐시)을 보고한다(FR2.5, 위험 R5).
- 반려된 대본은 절대 발급 · 노출 · 재생하지 않는다(FR2.3). 생성물과 검토 결과는 제브로 보내지 않는다(FR2.6).

## 범위
정본은 `unit-of-work.md` U10 절과 `bolt-plan.md` B7이다. 만들 것:
- **생성 → 자체 검토 → 서버 검사 → 재생성(FR2.1 ~ FR2.3)**
  - 생성: `LunaPurpose.SCENARIO_GENERATION`, `ai-scenario-output.schema.json`.
  - 자체 검토: `LunaPurpose.SCENARIO_REVIEW`, `ai-scenario-review-output.schema.json`. 생성과 다른 요청으로 보낸다.
  - `APPROVE`만 승인이다. `REJECT` · `UNCERTAIN` · 누락 · 시간 초과는 승인이 아니다. `APPROVE`여도 서버 검사가 실패하면 막는다.
  - 끝내 실패하면 기존 `SCENARIO_GEN_FAILED`로 끝낸다. 새 오류 코드는 없다.
- **새 대본 필드(FR2.4, C-DS1)**
  - `practice-scenario.schema.json`에 `scriptVersion` · `scenes` · `selfReview` · `generation`을 더한다. 모두 선택 필드이고 `additionalProperties: true`를 유지한다.
  - core V10에 새 대본 필드를 저장한다.
  - 레거시 필수 필드는 첫 BASE 장면에서 서버가 만든다. 그래서 U5b 경로가 같은 응답을 계속 읽는다.
  - `partnerRole`은 모델 출력이 아니라 Core가 고정한다(Contract Design 지적 R-04).
  - 응답에 Luna 원문 응답 · 자체 검토 사유 원문 · 아동 식별자 외 개인 정보를 싣지 않는다.
- **입력**
  - 담은 목표의 4요소 · `skill_ref`는 U14 `SavedGoalReader`로 읽는다.
  - 구조 맥락 · 변형 계획은 U15 `KnowledgeGraphLookup`으로 읽는다.
  - 그래프가 꺼져 있거나 2초를 넘으면 기본 경로로 만들고 `generation.graphContextUsed = false`로 둔다(FR9.5).
- **변형 장면 보존 판정(AC4.6.2, FR9.6)**: 기본 장면의 불변 문장 · 보존 조건 충족, 같은 기술 판정, 전이 거리, 바뀐 요소를 본다.
- **기존 인터페이스 경로 전환**
  - 지금 core `TextAiProvider`의 Luna 구현(`LunaTextAiProvider`)은 `json_object`로 1회만 부른다(`ai-io.md` 10.10).
  - 이것을 새 스키마 · 자체 검토 흐름으로 바꾸는 일이 이 볼트 몫이다.

범위 밖: U12의 대본 소비(상태 기계), 실기기 확인.

## 정본
- 이 볼트의 인셉션 기록: `R = aidlc/spaces/default/intents/261015-doc-sync-code-update/`

| 무엇 | 어디 |
|---|---|
| 범위 · 완료 조건 | `R/inception/units-generation/unit-of-work.md` U10 절, `R/inception/delivery-planning/bolt-plan.md` B7 |
| 요구사항 | `R/inception/requirements-analysis/requirements.md` FR2.1 ~ FR2.6 · FR9.5 · FR9.6 |
| 스토리 · 수용 기준 | `R/inception/user-stories/stories.md` US3.1, AC4.6.2 |
| 컴포넌트 | `R/inception/domain-design/components.md` ScenarioGeneration |
| 계약 | `R/inception/contract-design/contract-summary.md` C-DS1 · C-DS5 · C-DS10 · C-DS11 · 4절 열린 질문(승인 사유 목록 최종 값), 검토 지적 R-04 |
| 계약 파일 | `contracts/internal-api.yaml`(`/internal/practice/scenarios`), `contracts/schemas/practice-scenario.schema.json` · `internal-practice-scenario-request.schema.json` · `ai-scenario-output.schema.json` · `ai-scenario-review-output.schema.json`, `contracts/ai-io.md` 7절 · 10절, `contracts/timing.md`(`practice.scenario-request-timeout` 15s) |
| Luna 호출 방식 | U9 설계 `R/construction/u9-luna-clients/functional-design/`, `contracts/ai-io.md` 10.3 · 10.10, 편차 (U9-a) ~ (U9-c) |
| 기존 코드 | U5a 상황 생성(`core/practice/ScenarioService` · `ScenarioContentPolicy` · `ScenarioRepository`, `TextAiGateway`), `core/luna/adapter/LunaTextAiProvider`, 프롬프트 `prompts/scenario/generation.v1.txt` |

## 정해 둔 값
- 마이그레이션 core `V10__u10_scenario_generation.sql`. 더하기만 한다(R-M3).
- Luna 재시도: 생성 · 자체 검토는 `REQUEST_BOUNDED` 분류다. 일시 실패 기술 재시도 1회 + 내용 재생성 1회(스키마 불일치 포함)를 15초 예산 안에서 한다(`ai-io.md` 10.3). 바깥 `TextAiGateway`가 이미 재시도하는 경로에 안쪽 재시도를 겹치지 않는다(project.md 교정).
- **관심사(`interests`)를 공급자로 보낼지** 정한다. 지금은 금지 키라 지워진다(`ai-io.md` 7절 열린 점). 이 결정은 Functional Design 질문으로 사람에게 묻는다.
- 자체 검토 승인 사유 목록의 최종 값(J7 항목 포함)을 정하고 `ai-scenario-review-output.schema.json`에 더한다. 프롬프트 판(`promptVersion`)과 스키마 판은 함께 올린다.
- 시험: 태그 `u10`, 범위 `backend/coverage-scope/u10.txt`, 범위 줄 80%. Luna는 가짜 공급자만 쓴다(R-F1).
- 15초 실측은 `lunaLiveTest`로 합성 자료만 써서 하고 `[실호출-합성]`으로 따로 보고한다(R-M11, R-F15 확인 기록은 `infra/RUNBOOK.md` 단계 4).
- 건드리지 않는 것: `backend/realtime-activity`, 다른 단위의 시험 단언과 커버리지 하한(R-F2).

## 다른 단위와 맞물리는 곳
1. **U14(앞 볼트)**: `SavedGoalReader`. 과거 행동 정의 목표(`LEGACY_BEHAVIOR`)로도 상황을 만들 수 있어야 한다.
2. **U15**: `skillContext` · `variantPlan`. 빈 응답이면 기본 경로로 간다.
3. **U12(realtime 줄)**: C-DS1 응답을 내부 HTTP로만 받는다(R-F5). 병합 뒤 U12 담당자가 실제 대본으로 끝-끝을 확인한다. 이 볼트는 레거시 U5b 경로가 같은 응답을 계속 읽는 시험을 둔다.

## AI-DLC로 진행하는 법
1. `unit/u10-scenario-generation` 브랜치에서 아래를 입력한다.

   ```
   /aidlc --new-intent --scope refactor "U10 상황 생성(u10-scenario-generation) 구현. 지시 docs/units/u10/brief.md를 정본으로 한다. 인셉션 산출물은 261015-doc-sync-code-update 기록의 것을 그대로 쓴다."
   ```

2. 작업 기록 이름은 `u10-scenario-generation`으로 한다.
3. `/aidlc compose "deployment-pipeline과 deployment-execution을 빼줘"`를 입력하고 승인한다.
4. `/aidlc --depth Standard --test-strategy Standard`를 입력한다.
5. 단계: 기존 코드 파악 → Requirements Analysis → Functional Design → Code Generation → Build and Test.
   - Requirements Analysis는 FR2 · FR9.5 · FR9.6 · US3.1을 다시 묶는다('원 FR2.1'처럼 구분).
   - Functional Design 결과는 `docs/units/u10/design.md`에도 옮긴다.
   - Code Generation 계획은 이 순서로 나누고 단계마다 `feat(u10): …`로 커밋한다.
     1. V10 + 계약(C-DS1 선택 필드, U12 계약 PR과 맞춤)
     2. 생성 · 자체 검토 · 서버 검사 · 재생성
     3. 목표 · 그래프 입력과 변형 보존 판정
     4. 레거시 응답 호환
6. 자기 기록 폴더(`aidlc/spaces/default/intents/<날짜>-u10-scenario-generation/`)는 브랜치에 함께 커밋한다.

## 끝나면
`docs/units/u10/report.md`에 아래를 쓰고 `main`으로 PR을 연다. U14 병합 뒤, `ci-gate`가 초록이면 합친다. 합친 뒤 U12 담당자에게 알린다.
- 만든 것, 시험 결과
- 계약 편차 `(U10-a)`, 가정, 설계와 달라진 곳
- 15초 예산 실측(`[실호출-합성]`)과 대역 결과를 나눠 적는다
- 남은 일
