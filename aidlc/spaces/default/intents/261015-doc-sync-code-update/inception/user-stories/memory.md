<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
- 2026-10-15T09:20:00Z — 인계서 §8의 모델 판단 품질 항목(능력 단정 차단 등)은 가짜 공급자 CI로는 판정할 수 없어, 인수 기준을 서버 동작([자동-대역], 합격 기준)과 분류 품질([실호출-합성], 관측 보고)로 나눴다.
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
- 2026-10-15T09:20:00Z — 운영 요구(FR1 · FR10 · FR11 · FR12)를 스토리로 쓰지 않는 대신(Q1-B) traceability에 N/A · Deferred로 두고 stories.md에 '스토리 밖 추적' 표로 시험 항목을 이었다.
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
- 2026-10-15T09:20:00Z — 화면 설계와 부딪히는 27 칩 표시 · 막힌 발화 대체 문구는 모브 중에 사람에게 물어 정하고, 나머지 검토 지적(기본값 · 프로필 · 삭제 실패 구분 · 분할)은 근거가 분명해 바로 반영했다; 2라운드 없이 마쳤다.
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
