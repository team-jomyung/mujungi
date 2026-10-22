# 실제 AI 측정 뒤 연습 경로 고치기 (practice-live-fixes)

> 브랜치 `claude/practice-live-fixes`(기준 `claude/practice-path-merge` b27a271 = main + 연습 연결부 수정 + 변형 어휘 수정 + 검토 스키마 수정 + 실제 AI 평가 하니스).
> 사람 대신 모델이 지시대로 구현했다. 사람이 정할 일은 7절 · 8절에 남겼다.
> **증거 구분(R-M11)**: 이 브랜치의 시험 결과는 모두 **테스트 대역(가짜 공급자 · 가짜 판정) · 합성 자료** 증거다. 실제 AI는 부르지 않았다.
> 2절의 숫자는 이 브랜치 이전에 사람이 돌린 **실제 API · 합성 자료** 실행(`PracticeQualityLiveTest` · `PracticeDialogueLiveTest`)의 결과 파일에서 읽은 것이다.
> 이 브랜치의 고침이 그 숫자를 얼마나 바꾸는지는 아직 재지 않았다(8절).

## 1. 바꾼 동작 한눈에

| # | 항목 | 전 | 후 | 앱 | 커밋 |
|---|---|---|---|---|---|
| 1 | 자체 검토 지시 | `scenario-review.v1`: 어느 필드가 언제 아이에게 들리는지 말하지 않았다 | `scenario-review.v2`: 처음 들리는 필드(`sceneDescription` · `opportunitySentence`)만 ANSWER_INCLUDED · EARLY_CUE 대상이고, 도움 동의 뒤에만 쓰는 `focusPoints` · `expressionExamples`의 목표 표현은 실패가 아니다. VARIANT가 없는 대본에는 UNCHANGED_VARIANT · TRANSFER_MISMATCH를 쓰지 않는다 | core | 2772ccc |
| 2 | 검토 입력 | 첫 BASE 검토 · BASE만 남긴 재검토에도 다음 생성용 `variantPlan`이 들어갔다 | 대본에 VARIANT 장면이 없으면 `variantPlan`을 빼고 보낸다(`structureContext`는 그대로) | core | 2772ccc |
| 3 | 머뭇거림 | 기회(J2)에서 보류 두 번이면 장면을 ABSTAINED로 닫았다 | 확신 · 차이 문턱 미달 보류(LOW_CONFIDENCE · LOW_MARGIN)는 확정 안 된 1순위가 HELP이면 곧바로, 아니면 재안내 뒤 두 번째에 **도움 제안(HELP_OFFER, J4 동의 확인)**으로 간다 | realtime | e12b296 |
| 4 | 추론 강도 | 상황 생성 · 검토 `reasoning-effort: medium` | `low`(다른 목적은 그대로) | core | 8f7b01f |
| 5 | 생성 지시 | `scenario-generation.v3`: '기회 문장을 장면 설명에도 포함한다'만 적었다 | `scenario-generation.v4`: BASE 설명에 `opportunityCondition` · `opportunitySentence`를 글자 그대로 넣으라고 적고(서버 검사가 이미 요구), 처음 들리는 두 필드에 핵심 단서 · 상대 속마음 · 해야 할 행동을 설명하지 말라고 적는다 | core | ca34cfb |

판정 프롬프트(`prompt-v1`) · 선택지 판(`judgment-v1`) · 보류 기준(0.6 / 0.15) · 시간 값(15초 · 3초)은 바꾸지 않았다.

## 2. 실측에서 본 것 (고침의 근거)

### 2.1 core — 목표 6개, 연습 대본 발급 0건

- 검토 13회 중 REJECT 12 · APPROVE 1. 사유 횟수: ANSWER_INCLUDED 10, INVARIANT_MISMATCH 9, EARLY_CUE 5, UNCHANGED_VARIANT 5, INCOMPLETE_SCENE 1, TRANSFER_MISMATCH 1.
- 검토 13회 모두 입력에 `variantPlan`이 있었다. 그중 10회는 대본에 BASE 한 장면뿐이었고(BASE만 생성한 호출 뒤의 검토 9회 · BASE만 남긴 재검토 1회), UNCHANGED_VARIANT 5회 · TRANSFER_MISMATCH 1회가 모두 이 BASE 단독 검토에서 나왔다. 코드 원인: `ScenarioScriptGenerator`가 첫 BASE를 받은 뒤 `withPlan`으로 다음 생성용 계획을 만든 입력(`reviewedInput`)으로 그 BASE를 검토했다. 검토는 계획의 `participants`(예: 아이와 모둠원)와 BASE의 값(아이와 또래)이 다르고 BASE의 고정값 `changedElement=topic`이 계획(`participants`)과 다르니 '변형이 안 됐다'고 읽었다.
- 유일한 APPROVE(`s8` 둘째 검토)는 `expressionExamples`가 빈 대본이었다. 나머지 대본은 모두 `expressionExamples`에 목표 표현을 담았고 ANSWER_INCLUDED가 붙었다. 서버 검사(`ScenarioScriptValidation`)는 처음 들리는 세 필드(`sceneDescription` · `opportunitySentence` · `reexplanation`)에 목표 표현이 있는지 이미 결정적으로 보고, 12개 BASE 모두 통과했다. 검토가 코치 전용 칸을 정답 선제 제시로 읽었다고 판단했다.
- 진짜 EARLY_CUE도 있었다: `s4` 첫 BASE 설명 "친구는 공룡 이름을 잘 모르고, 공룡 그림에 대해 이야기한 적도 많지 않다". 아이가 스스로 알아채야 할 상대의 지식 상태를 서술자가 설명했다. 다만 이 목표의 `opportunityCondition`("공룡을 잘 모르는 친구에게 …") 자체가 같은 단서를 담고 있고, 서버는 그 문장을 설명에 글자 그대로 넣으라고 요구한다(6절 LIVE-FIX-A3).
- **지시에 없던 원인**: BASE 12개 중 6개(`s1` 둘째 · `s6` 첫째 · `s8` 둘 다 · `x4` 둘 다)는 검토와 별개로 서버 검사에 걸렸다. 서버는 BASE `sceneDescription`이 목표 `opportunityCondition`과 그 장면의 `opportunitySentence`를 정규화 비교로 담기를 요구하는데(`contains`), 모델은 기회 조건을 바꿔 쓰거나(“…빌려 달라고 한다”) 기회 문장을 설명 밖에만 두었다. `s8`은 검토가 APPROVE였는데도 이 검사 때문에 발급되지 않았다. 그래서 생성 지시를 v4로 올렸다(4절).
- 걸린 시간: 목표당 42.6 ~ 80.0초. 생성 호출 10.3 ~ 32.1초, 검토 호출 5.5 ~ 15.0초(모두 `reasoning-effort: medium`). 운영 예산은 시작 요청 전체 15초 · **호출당 3초**다(5절).

### 2.2 realtime — 연습 대화 모의 4가지 반응

- 잘함 · 일부 · 엉뚱한 말은 기대대로 돌았다(J2 FULL · 다시 설명 뒤 FULL · 변형 수락/거절).
- 머뭇거림(SILENT_THEN_HELP): 첫 기회 무응답 → 재안내 → "음, 잘 모르겠어." → J2 보류(LOW_CONFIDENCE, 1순위 HELP 0.52) → 재안내 → 같은 말 → J2 보류(LOW_CONFIDENCE, 1순위 HELP 0.48) → 장면 ABSTAINED. 도움 제안 · 코치 도움이 한 번도 나오지 않았다.
- 판정 11회 중 운영 판정 시한(4초) 안에 끝난 것은 7회였고, 머뭇거림 실행의 두 판정은 둘 다 4초를 넘겼다. 운영이었다면 이 두 판정은 LOW_CONFIDENCE가 아니라 TIMEOUT 보류였을 수 있다(7절 성민 항목).

## 3. (1) 검토 오판 줄이기 — core

### 3.1 실제 검토 입력 (코드로 확인)

- `LunaTextAiProvider.review`는 `ScenarioScriptGenerator.review`가 만든 입력을 그대로 `SCENARIO_REVIEW` 목적 · 계약 스키마 단일 호출로 보낸다. 입력 = 생성 입력(`StructuredScenarioFlow`가 정리한 `ageYears` · `interests` · `goal{kind, skillRef, targetExpression, fourElements}` · `opportunityCondition` · `relation` · `history` · (`sourceScenario`) · (`structureContext`) · `baseOnly` 또는 `variantPlan`)의 사본에 `script`를 더한 것. 생성 때만 붙이는 `correction` · `baseToPreserve`는 검토 입력에 없다.
- 고친 점: 대본에 `sceneKind=VARIANT` 장면이 없으면 검토 입력에서 `variantPlan`을 지운다. 첫 BASE 검토와 BASE만 남긴 마지막 재검토(`reduced`)가 여기에 든다. 변형이 실린 검토는 지금처럼 생성에 실제로 넘긴 계획을 본다.
- 바뀐 시험: `PlannedGenerationIT.rejectedBaseChangingCoordinatesCannotUseUndeliveredNewPlanForVariantReview`의 '모든 검토가 넘긴 계획을 본다' 단언을 '변형이 실린 검토만 넘긴 계획을 보고, BASE 단독 검토에는 계획이 없다'로 바꿨다. 넘기지 않은 계획이 검토에 닿지 않는다는 원래 뜻은 그대로이고 더 좁아졌다(R-F2).

### 3.2 장면 필드가 실제로 쓰이는 때 (realtime 코드로 확인)

| 필드 | 쓰이는 때 | 도움 동의 필요 |
|---|---|---|
| `sceneDescription` | 장면 도입 승인 발화. 친구 · 코치 생성형 지시문의 '상황' 칸 | 아니오(처음 들림) |
| `opportunitySentence` | 기회 문장 승인 발화(설명이 기회 문장으로 끝나면 다시 재생하지 않음) · 재안내 | 아니오(처음 들림) |
| `reexplanation` | J2 · J5가 NOT_UNDERSTOOD · IRRELEVANT일 때 친구 목소리로 한 번(기록상 도움 SITUATION_EXPLAIN) | 아니오(첫 시도 뒤) |
| `focusPoints` | 코치 첫 도움 발화의 '떠올려 볼 점' | 예(J4 수락 또는 HELP) |
| `expressionExamples` | 코치 둘째 발화(동의 + 다시 해 보기 1회 뒤)의 표현 예시 | 예 |

지시서는 `reexplanation`도 도움 동의 뒤 자료라고 적었지만 코드상 동의 없이 들린다. 그래서 v2는 `reexplanation`을 EARLY_CUE 대상에서 빼되(첫 시도 뒤에 들리는 상황 재설명이라 '초기' 힌트가 아니다), 목표 표현을 그대로 담으면 ANSWER_INCLUDED로 둔다(서버 검사와 같은 기준, LIVE-FIX-A2).

### 3.3 `scenario-review.v2` 요지

- 처음 들리는 것은 `sceneDescription` · `opportunitySentence`뿐이다. `focusPoints` · `expressionExamples`에 목표 표현 · 단서가 있는 것은 정상이다.
- ANSWER_INCLUDED · EARLY_CUE는 처음 들리는 두 필드만 본다: 목표 표현(또는 거의 같은 문장)을 말해 주거나, 아이가 스스로 알아채야 할 핵심 단서 · 상대 속마음 · 해야 할 행동을 설명하면 실패. 상대의 대사 · 행동 · 상황 사실로 단서가 드러나게 한 것과 `opportunityCondition`을 글자 그대로 옮긴 부분은 실패가 아니다(LIVE-FIX-A3).
- 불변 문장은 아이가 놓치기 쉬운 순간의 요약이라 장면에는 그 순간이 생길 자리가 있으면 되고, 아이가 이미 실수했다고 서술할 필요는 없다(LIVE-FIX-A4). BASE의 `transferDistance` · `changedElement` 고정값은 검사하지 않는다.
- 전이 거리 · 한 변경 요소는 VARIANT가 있을 때만 BASE · `variantPlan`과 비추어 본다. VARIANT가 없으면 UNCHANGED_VARIANT · TRANSFER_MISMATCH를 쓰지 않는다.
- 사유 코드 · 스키마 · APPROVE 조건은 v1과 같다.

### 3.4 판 올림에 같이 맞춘 곳

`ScenarioScriptProvider.REVIEW_PROMPT_VERSION` · `REVIEW_INSTRUCTIONS`(판 이름 한 곳), `LunaTextAiProvider`, 발급 대본 `selfReview.promptVersion`, `contracts/schemas/practice-scenario.schema.json`(`selfReview.promptVersion`을 v1 · v2 enum으로 — 저장된 v1 대본도 계속 받음), `contracts/ai-io.md` 10.11, `contracts/deviations.md`, `contracts/fixtures/practice-start-package.json`, 시험(`ScenarioLunaProviderTest` · `ScenarioContractTest` · `PlannedGenerationIT`).

## 4. (1-부록) 생성 지시 v4 — core

- 지시는 '판 올림 비용이 크면 생략'이었다. 실제 비용은 상수 · 자원 · 스키마 enum · 고정 자료 · 문서 둘 · 시험 기대값 다섯 곳이라 크지 않다고 보았고, 2.1절의 서버 검사 실패(BASE 6/12)를 같이 줄일 수 있어 올렸다(LIVE-FIX-A10).
- v3에 더한 것: ① 모든 장면 `sceneDescription`에 그 장면의 `opportunitySentence`를, BASE에는 입력 `opportunityCondition`도 글자 그대로 넣는다. ② 처음 들리는 두 필드는 핵심 단서 · 상대 속마음 · 해야 할 행동을 설명하지 않고 대사 · 행동 · 상황 사실로만 드러낸다. ③ `reexplanation`에 아이가 이미 실수했다고 쓰지 않는다(`x4` 둘째 대본: "나는 그 부탁에 답하지 않고 내 이야기만 했던 상황이야").
- 출력 스키마 · 서버 검사는 그대로다. 저장된 v2 · v3 대본은 공개 대본 계약에서 계속 받는다.

## 5. (3) 생성 지연 줄이기 — core

### 5.1 바꾼 것

- `mujoong.luna.purpose.scenario-generation` · `scenario-review`의 `reasoning-effort`를 `low`로. 다른 목적(씨앗 · 결과 글 · 목표 후보 · 재판정)은 `medium` 그대로다(LIVE-FIX-A11). `contracts/ai-io.md` 10.9 설정 표에 적었다.
- `PracticeQualityLiveTest`는 이미 `application.yml`의 `mujoong.luna.purpose`를 읽어(`ProductionBudgets.load`) 실제 호출에 쓴다 — 하드코딩이 없어 고칠 것이 없었다. 다음 실행이 어느 설정을 쟀는지 결과에 남도록 운영 예산 칸에 목적별 추론 강도(`lunaReasoningEffort`)를 더했다. `PracticeLiveSettingsTest`가 이 묶음에서 두 목적이 `low`인지 기본 시험으로 본다.

### 5.2 15초 예산이 걸리는 곳 (코드로 확인)

1. 보호자가 18 화면(내 연습 목표)에서 목표를 골라 「연습 시작」을 누르면 `POST /api/activities/practice` → `PracticeStartService.start` → `prepare`.
2. `prepare`는 준비 행(`PracticePreparationRepository`, 멱등 키별 PREPARING · 임대 now + 15초)을 잡고, **그 요청 안에서 동기로** 첫 목표 대본을 만든다(`ScenarioService.generatePrepared` → `StructuredScenarioFlow` → `ScenarioScriptGenerator`). 끝나면 READY로 바꾸고 realtime 세션을 시작한다.
3. 시한: 전체 `mujoong.timing.practice.scenario-request-timeout`(15초, 선점 임대와 같음). 호출 하나는 `mujoong.text-ai.request-timeout`(**3초**)로 자르고, 시간 초과는 기술 재시도 1회(대기 250ms) 뒤 실패다. 호출 수 상한 6.
4. 둘째 목표부터는 realtime이 앞 목표를 시작할 때 `POST /internal/practice/scenarios`로 다음 목표 대본을 미리 요청한다(같은 15초 · 3초). 앞 목표를 진행하는 동안 만들어지므로 지연이 가려진다.
5. **'목표를 담을 때 미리 준비'는 없다.** 목표를 담거나(후보 담기 · S-18) 고를 때 대본을 만들어 두는 작업 · 끝점이 없다. READY 준비 행은 같은 멱등 키로 다시 보낸 시작 요청(프런트 `startPort`가 응답 불명 때 같은 요청으로 확인)에서만 재사용된다. `PracticeStart.buildPackage` 포트도 같은 동기 준비를 하고 운영 코드에서 부르는 곳이 없다.

결론: 실측 지연(생성 10 ~ 32초 · 검토 5 ~ 15초, medium)으로는 호출당 3초를 넘어 운영 경로에서 첫 대본이 늘 실패한다. `low`로 얼마나 줄어드는지는 재야 한다(8절). 데모에서 쓸 수 있는 '미리 준비' 경로가 지금 코드에는 없으므로 아래는 제안만이다(큰 구조 변경은 하지 않았다).

### 5.3 제안 (도연과 정할 것)

| 안 | 내용 | 장점 | 비용 · 위험 |
|---|---|---|---|
| P1 | 상황 생성 전용 호출 시한 키를 두고(예: 생성 · 검토 호출 각 N초) 15초 전체 예산은 유지 | 작은 변경. 결과 글 · 옛 어댑터의 3초에 영향 없음 | 시간 값 변경이라 R-M5(두 앱 `application.yml` · `contracts/timing.md` · 프런트 `timing-defaults.json`)와 `TimeLiteralScanTest` 대조. 15초 안에 6호출이 안 되면 내용 수정 · 기술 재시도 여유가 사라짐 |
| P2 | 목표를 담을 때(또는 18 화면에 들어올 때) 첫 대본을 미리 만들어 READY 준비 행 · 발급 대본으로 둔다 | 시작 지연이 사라짐. 예산을 넉넉히 둘 수 있음 | 새 작업 · 끝점 · 만료 · 노출 이력(만든 시각과 연습 시각이 다름) 설계. 프런트 · 계약 변경 |
| P3 | 시작 응답을 202 + 준비 상태 조회로 바꾸고 예산을 늘린다 | 동기 대기 제거 | 프런트 12 · 18 화면 흐름 · 계약 변경 |

## 6. (2) 머뭇거림에 도움 주기 — realtime 새 연습 흐름

### 6.1 고치기 전 동작 (코드로 확인)

- 실행부(`SessionExecutor.afterExtensionJudgment`)는 보류를 `JUDGMENT_ABSTAINED{point, abstainReason}` 사건으로 보냈다(1순위 코드 없음).
- `PracticeConversation.onJudgmentAbstained` → `FlowTransitions.onJudgmentUnavailable` → 첫째는 같은 질문 재안내(`unreadableGuided` 표지), 둘째는 `SCENE_WRAP(ABSTAINED)` · 진행 중 판정 시점 ABSTAINED · 변형 생략 NO_VARIANT. 요청 거절(`requestJudgment` 거짓) · 모양이 틀린 결과도 같은 길이다.

### 6.2 고친 동작

- 실행부: 문턱 미달 보류(LOW_CONFIDENCE · LOW_MARGIN)는 판정기가 남긴 확정 안 된 1순위 코드를 `leadingOption`으로 더 싣는다. 확률은 넘기지 않는다. 판정 기록(`JudgmentLiveRecord`)은 그대로 ABSTAINED다.
- 전이표 `FlowTransitions.onJudgmentAbstained(state, point, abstainReason, leadingOption)`: OPPORTUNITY(J2)에서 사유가 LOW_CONFIDENCE · LOW_MARGIN이고 ① `leadingOption`이 HELP이거나 ② 이 기회에서 이미 한 번 재안내했으면 `HELP_OFFER`로 들어간다. 그 밖은 6.1과 같다.
- HELP_OFFER부터는 기존 도움 흐름 그대로다: 친구가 도와줘도 되는지 묻고(J4) → 수락이면 역할 전환 알림 · 코치 단서(FOCUS) → 다시 해 보기(RETRY, J5) → 복귀 알림 → 정리. 거절이면 HELP_DECLINED로 정리(변형 제안 규칙은 기존 HELP_DECLINED와 같음). 보호자 화면 진행 표시는 HELP_OFFER → CUE → AFTER_CUE_RESPONSE로 바뀐다(12 화면의 6값 접기 그대로).
- 기록: 새 값은 없다. 수락 경로는 사실 HELP_ACCEPTED · COACH_SWITCH · FOCUS_HELP · COACH_RETURN · ORIGINAL_SCENE_RETURN, 도움 기록 ROLE_SWITCH(동의 ACCEPT) · FOCUS_HELP · ROLE_RETURN, 진행 중 판정 시점 AFTER_HELP. 거절 경로는 HELP_DECLINED · VARIANT_SKIPPED, 시점 NONE. 두 경로 모두 `practice-record-v2` 스키마를 통과하는 것을 시험으로 봤다. core 결과 집계(U14)는 이 값들을 이미 J3 STILL_SHORT 경로에서 받으므로 읽기 코드 변경이 없다.
- 맞춘 문서: `docs/units/u12/design.md` 편차 U12-D4, `docs/units/u11/design.md` W4 사건 값, `PracticeSessionExtension` Javadoc. 실제 AI 하니스(`PracticeDialogueLiveTest`)도 실행부와 같이 `leadingOption`을 싣는다.

### 6.3 기준 문서와의 관계

- AI 동작 명세 v0.2 §5 표는 J2 보류 → `D_HOLD` → 다시 J2, 반복 한도에 이르면 `D_WRAP`이다. 이 고침은 한도에 이른 '확신 부족' 보류를 정리 대신 `D_OFFER_HELP`(J3 여전히 부족과 같은 도움 제안)로 보낸다. 코드에 이미 있는 선례: J2 NOT_UNDERSTOOD · IRRELEVANT가 다시 설명 한도에 이르면 HELP_OFFER로 간다.
- 연습 요구(`mujungi_Luna_JEV_guardrail_implementation_handoff_v2` 8.2 '유연한 연습'): "어떻게 말해야 할지 모르겠어"에는 코치 전환 · 도움 · 복귀를 고를 수 있어야 하고, 집중 도움을 강제하지 않는다. 확정 HELP가 아니라 동의 확인으로 보내므로 강제가 아니다.
- 화면 설계 v1.5j는 도움 단계의 세부를 정하지 않는다(12 화면이 진행 6값을 보일 뿐). 화면 변경은 없다.

## 7. 가정 (LIVE-FIX-A)

- **LIVE-FIX-A1** 시험 DB: 이 컨테이너에 Docker 데몬이 떠 있지 않아(`docker.sock`만 있음) `-Pmujoong.test.db=local`(호스트 PostgreSQL 16, `MujoongPostgres` local 백엔드)로 돌렸다. Docker 데몬은 켜지 않았다.
- **LIVE-FIX-A2** `reexplanation`은 지시서와 달리 도움 동의 없이 들린다(3.2절). v2는 EARLY_CUE 대상에서 빼고 목표 표현을 그대로 담을 때만 ANSWER_INCLUDED로 본다.
- **LIVE-FIX-A3** `opportunityCondition`을 글자 그대로 옮긴 부분은 EARLY_CUE가 아니다. 서버가 그 문장을 요구하므로 검토가 막으면 그 목표는 발급될 수 없다. 목표 상황 문장 자체에 단서가 든 경우(`s4` "공룡을 잘 모르는 친구에게")는 목표 후보(4요소) 쪽 문제로 남긴다.
- **LIVE-FIX-A4** 불변 문장을 '아이가 놓치기 쉬운 순간의 요약'으로 풀었다. 씨앗 문장이 실수 장면을 과거형으로 적어("상대가 부탁을 했는데 대답 없이 내 이야기만 했다") 검토가 '장면에 그 실수가 없다'를 INVARIANT_MISMATCH로 읽었다고 보았다. `contracts/knowledge-graph.md`에 정의가 없어 가정이다.
- **LIVE-FIX-A5** 도움 제안 경로를 확신 · 차이 문턱 미달 보류로만 좁혔다. 시간 초과 · 공급자 장애 · 스키마 오류 · 요청 거절은 판정 쪽 실패라 J4도 판정하지 못할 가능성이 크고, 아이에게 묻고 대답을 버리게 되므로 지금처럼(재안내 1회 → ABSTAINED) 둔다. 지시의 '두 번째 보류'를 이 범위 안에서만 적용했다.
- **LIVE-FIX-A6** '같은 기회에서 두 번째'는 이 기회 문장에서 재안내를 한 번 한 뒤(`unreadableGuided` 표지, 첫째가 판독 불가 · 거절이었어도)로 센다. 상황 다시 설명 뒤 OPPORTUNITY로 들어오면 표지가 지워져 다시 첫째부터 센다(기존 규칙).
- **LIVE-FIX-A7** 보류의 1순위 코드는 확정 행동(곧바로 코치 도움 · 동의 REQUESTED)에 쓰지 않고 동의 확인(HELP_OFFER → J4)에만 쓴다. U11 NFR2('보류를 판정 성공으로 표시하지 않는다')와 맞추려는 것이다.
- **LIVE-FIX-A8** 적용 단계는 OPPORTUNITY(J2)만이다. REASK(J3)는 STILL_SHORT가 이미 HELP_OFFER로 가고, RETRY(J5)는 이미 도움 안이며, HELP_OFFER · VARIANT_OFFER(J4)는 동의 질문 자체라 그대로 둔다. 변형 장면의 OPPORTUNITY에도 같이 적용된다.
- **LIVE-FIX-A9** 실행부(`SessionExecutor`, U11 · U12가 함께 고치는 파일)에 사건 값 한 칸을 더했다. `contracts/`에 확장 사건 값 표가 없어 Javadoc · U11 설계에만 적었다.
- **LIVE-FIX-A10** 생성 판을 v4로 올렸다(4절). 다른 브랜치가 생성 판을 같이 올리면 판 이름이 부딪히므로 병합 때 확인한다.
- **LIVE-FIX-A11** 추론 강도 `low`는 상황 생성 · 검토만이다. 목표 후보 사슬도 길었지만(68초, 호출 17 ~ 32초, 운영 예산 30초 · 호출 10초) 지시 범위 밖이라 바꾸지 않았다.
- **LIVE-FIX-A12** 시간 값(15초 · 3초)은 바꾸지 않았다. 바꾸려면 R-M5 세 곳과 담당자 합의가 필요해 5.3절 제안으로 남겼다.
- **LIVE-FIX-A13** 검토가 VARIANT 없는 대본에 변형 사유만 내도 서버는 판정을 고쳐 읽지 않는다. 지시 · 입력으로만 막았다(서버가 검토 결과를 덮어쓰면 별도 검토의 뜻이 약해진다).

## 8. 원 담당자와 맞출 것

- **도연(core U10 · U14)**
  - 검토 v2 문구(특히 A2 · A3 · A4의 풀이), BASE 단독 검토에서 `variantPlan`을 빼는 것, 생성 v4 문구.
  - 서버 포함 검사(BASE 설명 ⊇ `opportunityCondition` · `opportunitySentence`)를 그대로 둘지. 목표 상황 문장이 "…할 때"로 끝나 아이에게 들리는 설명에 그대로 들어가면 말투가 어색해지고(`s10` "…빌리고 싶을 때가 기회야") 단서가 섞인다(A3). 완화하려면 U5a 이후의 `goalIncluded` 뜻이 바뀐다.
  - 5.3절 P1 · P2 · P3 중 무엇으로 첫 대본 지연을 풀지.
- **성민(realtime U12)**
  - U12-D4(확신 부족 보류 → 도움 제안)와 A5 ~ A8의 범위, 실행부 사건의 `leadingOption` 칸(A9).
  - 판정 시한 4초 대 실측(11회 중 7회만 4초 안, 머뭇거림 실행 0/2): 운영에서는 TIMEOUT 보류가 되어 이 고침(문턱 미달 보류만)이 닿지 않을 수 있다. `judgment-live` 추론 강도(`medium`)를 낮출지는 판정 프롬프트 v2를 준비 중인 브랜치와 함께 정한다.
- **둘 다**: `contracts/fixtures/practice-start-package.json`의 판(v4 · review v2)이 바뀌어 core · realtime 교차 시험이 같이 읽는다.

## 9. 사람이 할 일

1. **실제 AI 재측정**(키를 env에만 넣고, R-F15 확인 기록이 이 실행을 덮는지 먼저 확인):
   - `cd backend && ./gradlew :core-api:lunaLiveTest --tests "*PracticeQualityLiveTest*"` — 볼 것: 발급 건수(지금 0/6), 검토 사유 분포(특히 BASE 단독 검토의 UNCHANGED_VARIANT · TRANSFER_MISMATCH가 0인지, ANSWER_INCLUDED가 줄었는지), 서버 포함 검사 실패(지금 BASE 6/12), 호출 지연(low에서 3초 안 비율), 결과 칸 `lunaReasoningEffort`가 low인지.
   - 이어서 realtime `PracticeDialogueLiveTest` — 볼 것: SILENT_THEN_HELP 반응이 도움 제안 → 수락 → 코치 도움 → 다시 해 보기로 가는지, 판정이 4초 안에 끝나는지.
   - 결과는 '실제 API · 합성 자료' 증거로 따로 적는다(R-M11).
2. 8절 결정을 도연 · 성민과 맞춘다.
3. PR을 연다(이 브랜치는 `claude/practice-path-merge` 위에 쌓였으므로 그 브랜치가 먼저 들어가야 한다). 리뷰어: core 도연, realtime 성민, `contracts/`는 둘 다.

## 10. 시험 (테스트 대역 · 합성 자료)

| 명령(`cd backend`, `-Pmujoong.test.db=local`) | 결과 |
|---|---|
| `:core-api:test --tests "kr.mujoong.core.practice.*" --tests "kr.mujoong.core.luna.*" --tests "...PracticeLiveSettingsTest"` + `:contract-model:test` | 680건 통과 |
| `:realtime-activity:test --tests "kr.mujoong.realtime.practiceflow.*"` | 206건 통과 |
| `:realtime-activity:test --tests "kr.mujoong.realtime.session.*" --tests "kr.mujoong.realtime.judgment.*"` | 584건 통과 |
| 전체 `:contract-model:test :core-api:test :realtime-activity:test --continue`(마지막 커밋 ca34cfb) | contract-model 325건 · realtime 1932건 통과. core 1763건 중 2건 실패: `Neo4jIT` · `GraphWorkflowIT` 초기화 오류("Could not find a valid Docker environment") — Neo4j Testcontainers가 Docker 데몬을 못 찾은 환경 문제이고 이 브랜치 변경과 무관하다(기준선, LIVE-FIX-A1) |

더한 시험: `ScenarioLunaProviderTest`(검토 v2 · 생성 v4 판 · 지시 문장), `PlannedGenerationIT.baseOnlyReviewSeesStructureButNotTheNextVariantPlan`, `ScenarioContractTest.storedV1AndNewV2ReviewsBothMatchThePracticeScenarioContract`, `PracticeLiveSettingsTest`, `FlowTransitionsTest`(확신 부족 보류 5종), `PracticeConversationTest`(머뭇거림 → 도움 수락 끝까지 · 두 번째 보류 → 도움 거절 · 판정 쪽 실패는 그대로), `JudgmentExtensionPortTest.anUnsureAbstainCarriesOnlyTheUnconfirmedLeadingCodeAndAPlainAbstainCarriesNone`.
