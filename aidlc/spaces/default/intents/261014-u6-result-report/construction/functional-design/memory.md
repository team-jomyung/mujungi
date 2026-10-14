<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-14T01:55:00Z — 리포트 AI 항목(2 · 5 · 6 · 10)은 ai-io 6.5 출력 하나로 한 번에 받고, 금지 2차 분류도 작업당 한 호출로 묶는다; 60초 절대 마감 안에서 시도당 AI 호출을 2번으로 줄여 재시도 여유를 남겼다. SESSION_RESULT도 많아야 4번이다.
- 2026-10-14T01:55:00Z — 결과 수준을 AI 전 1단계(유효 기록)와 AI 뒤 2단계(FULL 여부)로 나눴다; 1단계가 INSUFFICIENT면 AI 없이 DONE이 되어, 공급자 UNAVAILABLE 기본값에서도 짧은 활동은 정리 끝이 된다.
- 2026-10-14T01:55:00Z — traceability 자동 점검은 요구사항의 묶음 머리 ID(FR1 ~ FR16)와 '원 FR7.4' 같은 원래 기록 ID도 상위 ID로 센다; 묶음 머리는 "하위 행이 추적", 원래 기록 ID는 "이 기록 FR로 다시 묶어 추적"으로 N/A 행을 둬 통과시켰다.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-14T01:55:00Z — 지시서의 계정 삭제 대상 "PDF 파일"을 뺐다(편차 (U6-k)); PDF는 요청마다 메모리 렌더링이라 서버에 파일이 없다. 계약 C5 discard는 204만 두므로 저장된 리포트 버림도 바꾸지 않고 204로 답한다(새 오류 코드를 만들지 않음).

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-14T01:55:00Z — 생활 팁은 금지 2차 AI 분류 없이 1차 사전에 걸리면 숨긴다; 승인된 항목이라 1차로 충분하고 AI 호출 하나를 줄인다. 대신 사전에 없는 미묘한 표현은 승인 단계에서 걸러야 한다.
- 2026-10-14T01:55:00Z — FR15(재시작 FAILED 노출)로 바꿀 U4a 단언을 찾았더니 백엔드 시험에는 기록 목록 · 상세의 FAILED 제외를 단언하는 줄이 없었다; 코드(LISTED_STATUSES · 상세 필터) · 스키마 enum만 고치고 새 시험을 U6에 더하는 쪽으로 정했다.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-10-14T01:55:00Z — 미실시 사유 사상표(BR4.4)와 practice null 규칙은 U5b 담당과 맞춰야 한다. 구조화 · 후보 · 2차 묶음 AI 형식(5.5)과 금지 사전 v1 · 팁 시드는 D 담당 확인 전 초안이다.
