<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
- 2026-10-16T02:30:00Z — U9 재검토 메모: 키가 있는 환경에서 시험 대역 빈을 넣으면 TextAiProvider · ResultTextProvider 빈이 둘이 될 수 있다; 시험 환경에는 키가 없어 막지 않았지만, U9 Code Generation 컨텍스트 시험에서 키 있음 + 대역 조합을 다루거나 대역 쪽에 @Primary를 둔다.
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
