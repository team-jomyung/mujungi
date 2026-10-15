<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-16T01:50:00Z — Units Generation 지적 R-02(기록 캡처 이벤트 발행 주체)는 코드에 이미 확장점 `practiceRecord` 시점이 있어 질문 없이 가정으로 풀었다; 발행은 세션 실행부(U11), 사실 제공은 U12, 새 형식 구분은 기존 `snapshotVersion`이다.
- 2026-10-16T01:51:00Z — 승인된 ADR-005의 '다음 행동 지시를 돌려준다'를 사람이 Q1-A로 '지금 능력 호출 모델 유지 · 확장'으로 정리했다; Domain Design 문서는 승인 산출물이라 고치지 않고, 계약 요약에 이 해석을 적어 Functional Design이 따르게 한다.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-16T02:20:00Z — 계약 요약을 기존 contracts/ 정본과 대조하지 않고 이름을 짐작해 써서 검토에서 Major 5건이 나왔다(없는 스키마 이름 SceneResult, 이미 있는 result.job-attempts 키 중복, snapshotVersion의 기존 의미 '세션당 한 판' 오해, partnerRole을 모델이 내지 않는다는 ai-io 7절 규칙 위반, U13 → U11 게이트 계약 누락); 계약을 쓰기 전에 바꿀 스키마 · 키 · 필드를 grep으로 확인하고 실제 파일 이름 · 필드 이름 · additionalProperties 여부를 적어야 한다.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
