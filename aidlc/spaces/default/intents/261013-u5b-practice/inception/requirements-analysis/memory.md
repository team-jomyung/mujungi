<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
- 2026-10-14T00:35:19Z — 제품 리드 검토는 새 상황 생성 실패·시간 초과의 기록/전이, 도움 후·새 상황 반응 무응답 시한, AC4.2.10 추적 연결을 설계 전 보완 사항으로 제시했다. 자문 검토 결과는 사람의 판단에 넘기며 요구사항을 임의 수정하지 않는다.
- 2026-10-14T00:33:21Z — U4b 별도 브랜치 구현과 CI 진행은 사용자가 전달받은 정보이며 최종 인터페이스 합의·실제 코드·CI 통과는 미확인이다. Functional Design에서 구현을 검토하고 합의 전 계약 대역과 실제 통합 뒤 시험을 구분한다.
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
