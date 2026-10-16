<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-16T01:20:00Z — 사람이 '2~3명이 병렬 개발하기에 가장 좋은 안'을 물어, 단위 수보다 파일 소유(realtime의 SessionExecutor · OutputGate를 고치는 단위는 한 사람이 차례로)가 병렬의 실제 제약이라고 설명했다; 단위 경계는 A(8개)로 정했고 담당 줄 배치는 Delivery Planning 입력으로 넘긴다.
- 2026-10-16T01:21:00Z — Q2-B(U8부터 이어 매기기)를 골라 작업 기록의 단위 ID도 U8~U15로 쓴다; 단계 형식(U{n})은 지키면서 저장소 이름(`u8`~`u15` 태그 · 범위 파일 · 브랜치)과 번호가 같아져 대응표가 필요 없다.
- 2026-10-16T01:22:00Z — 결과 단위(U14)가 새 연습 기록 사실을 받으려면 실시간 연습 단위(U12)에 기대야 하지만, 그러면 U12 → U10 → U14 → U12 순환이 생긴다; 기록 캡처 이벤트의 필드를 계약 단위(U8)가 먼저 정하고 U12는 그 계약대로 내보내며 U14는 계약만 기대게 해 순환을 끊었다.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
