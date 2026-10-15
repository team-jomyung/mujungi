<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-15T01:40:00Z — 병합 확인 뒤 로컬 실행 준비는 기존 U6 기록의 일부가 아니라 별도 기록으로 열었다; 모든 유닛 · 통합 PR(#13, #14)이 이미 origin/main에 들어가 있어 U6 기록의 Build and Test 승인 대기와 범위가 다르다.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-15T01:40:00Z — 스캔 전에 U6 스냅숏 브랜치에서 origin/main 기준 ops/local-run-readiness로 옮겼다; 로컬 main이 오래되어(#5 머지 시점) origin/main에서 직접 땄고, 새 기록의 intents.json 행과 main의 u5b-practice 행이 부딪혀 두 행을 모두 남기는 쪽으로 합쳤다.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-15T01:40:00Z — 전체 재스캔을 골라 U6 때 깊게 본 경로 일부(core.record · practice · knowledge 내부 등)가 얕은 분석으로 내려갔다; 실행 · 통합 관점의 최신 사실이 더 중요해서 받아들였고, 필요하면 해당 유닛 작업 때 집중 스캔으로 다시 올린다.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-10-15T01:40:00Z — "현장"이 같은 PC 시연인지 별도 아이 기기(LAN · 인증서) 접속인지, 실공급자 · 구글 OAuth를 쓸지 Requirements Analysis에서 확인한다.
