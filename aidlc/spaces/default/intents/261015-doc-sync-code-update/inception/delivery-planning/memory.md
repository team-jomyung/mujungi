<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-16T02:40:00Z — 담당 방식 질문은 팀 문서(docs/units/README.md)의 기존 방식(공통 인셉션 → 유닛 지시서 → 담당자 PC별 새 작업 기록)을 추천안으로 두었고 사람이 골랐다; 이 기록의 Construction은 이 PC 담당 볼트(U8 · U9)만 하고 U10~U15는 지시서로 넘긴다.
- 2026-10-16T02:41:00Z — 사람이 외부 준비에서 '압축 자료 라이선스 결정 준비됨'을 골랐지만 결정 내용이 없어 후속 질문(Q5-1)으로 확인했다; 답은 '둘 다 커밋 허용'이라 이력 정리 없이 U15가 저장소 안 압축 파일을 원천으로 쓴다.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-16T02:42:00Z — realtime 줄 순서를 U11 → U13 → U12로 두었다; U12는 core 줄의 U10을 기다려야 하고 U13(제브)은 형식 위험을 일찍 확인하는 편이 낫기 때문이다. U12와 U13은 같은 파일을 고쳐 한 담당자가 차례로 맡는다.

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
