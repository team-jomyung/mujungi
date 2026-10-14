<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
- 2026-10-14T00:30:16Z — 유닛 작업 기록의 코드 스캔은 지금 체크아웃이 아니라 main에서 새로 딴 유닛 브랜치를 기준으로 한다; 시작 때 체크아웃이 앞 유닛(U4b) 중간 브랜치였고, 그 코드로 스캔하면 다음 유닛 설계가 병합 전 코드에 묶인다. 스캔 전에 브랜치를 바꿨다.
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
- 2026-10-14T00:30:16Z — 사람은 Full rescan을 골랐지만 개발자가 저장소 전체를 깊게 보지 않아 범위 블록을 kind: partial로 기록했다; 결과적으로 이전 store가 깊게 본 realtime · contract-model · test-support · contracts가 shallow로 내려갔다(NARROWER). U5b처럼 realtime을 손대는 다음 기록은 다시 스캔해야 한다.
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
- 2026-10-14T00:30:16Z — 지시서의 브랜치 이름은 unit/u6-results-report인데 만든 브랜치는 unit/u6-result-report다; 커밋 전 이름을 맞출지 사람에게 확인한다.
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
