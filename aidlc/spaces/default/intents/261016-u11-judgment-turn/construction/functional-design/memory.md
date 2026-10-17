<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-16T07:18:03Z — 요구사항 FR3.1의 '버린 출력 전사는 judgment_live에만'을 '취소 전에 받은 조각이 있으면 남긴다'로 읽었다; 사람이 Q2-A(즉시 취소)를 골라 남길 전사가 대개 비기 때문이다.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-16T07:18:03Z — 승인된 요구사항 FR6.4 · AC1.7.6의 D_REST_ACK 재생을 일반 지시로 대신했다(U11-D1); 음성 파일 없는 승인 발화를 실행부가 재생할 길이 없고 사람이 Q1-C를 골랐다. 승인 문서는 고치지 않고 설계 문서에 편차로 적었다.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-16T07:18:03Z — J1 · J6 보류는 HOLD_REASK 지시 대신 지금의 재안내 · 확인 질문 흐름으로 보냈다; 이미 승인 음성이 있는 기존 흐름이 '교정 없는 진행'을 만족하고 U3 · U4b 동작을 덜 바꾼다.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-10-16T07:18:03Z — GPT-Live 격리 실호출의 합성 입력 음성을 무엇으로 쓸지 Code Generation 계획에서 정해야 한다.
