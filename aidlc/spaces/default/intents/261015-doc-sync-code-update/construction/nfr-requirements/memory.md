<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-16T04:10:00Z — U8 NFR 문서에 확인 도구 · 읽기 방식을 코드로 확인하지 않고 적어 검토 Major 2건이 나왔다(log-hygiene-check는 운영 컨테이너 로그 점검이라 단위 요구를 검증 못 함, Jackson 직접 사용은 J139로 JsonCodec 한 곳에 묶임); 사람 선택으로 로그 수집 단위 시험과 JsonCodec 읽기로 고쳤고 재검토는 하지 않았다.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
- 2026-10-16T02:05:00Z — U9 응답 크기 상한 1MB는 기존 ResultTextGateway가 문자 수(output.length())로 세는 값에 맞췄다; U9 Code Generation에서 바이트로 셀지 문자로 셀지 한 줄로 정한다.
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
