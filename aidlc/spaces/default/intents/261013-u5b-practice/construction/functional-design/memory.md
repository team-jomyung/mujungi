<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
- 2026-10-14T01:21:24Z — 확인된 Q1~Q5로 독립 데이터·규칙·흐름·화면 설계를 작성했다. 실제 재생/실패 기록 보존/시계 결합/새 상황 실패 투영/Core 준비 API·저장의 I1~I5는 상대 합의가 미확정이며 대역 검증과 운영 통합 완료를 구분한다. 상위 B14 무반응 뒤 도움 정책도 임의 확정하지 않았다.
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
