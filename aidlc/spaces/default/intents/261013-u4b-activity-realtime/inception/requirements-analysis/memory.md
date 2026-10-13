<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
- 2026-10-13T07:37:08Z — U4b 요구사항은 새로 만들지 않고 원래 기록 요구사항과 U4b 설계를 구현 단위 FR1 ~ FR11로 다시 묶음; 원래 ID는 '원 FR7.1'처럼 구분해 추적하고, 이 작업 기록 안에서만 쓰는 FR/NFR 번호를 새로 붙였다.
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
- 2026-10-13T07:37:08Z — 질문은 Standard 깊이 기준 6개로, 정본끼리 부딪히는 곳(시험 단언 불변 vs V3 기대값, timing.md 병합 충돌, 확장점 소유)만 물음; 설계가 이미 정한 시간 값 · 전이 · 가정은 다시 묻지 않았다.
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
