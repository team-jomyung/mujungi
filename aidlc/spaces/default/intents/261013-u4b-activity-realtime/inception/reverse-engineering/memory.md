<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
- 2026-10-13T06:49:20Z — 첫 스캔(NO_STORE)을 전체가 아닌 집중 스캔으로 정함; U4b가 손대는 backend/realtime-activity · contract-model · test-support · build-logic · contracts만 깊게 보고 core-api · frontend · infra는 얕게 훑는다. 저장소 전체를 깊게 보면 시간이 크게 들고 U4b 설계에 필요한 범위를 넘는다.
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
- 2026-10-13T06:57:34Z — 스캔 도중 스크립트 41개의 실행 권한(755→644)이 스테이지에 올라가 있던 것을 git reset으로 비움; 작업 트리 파일은 그대로이고 HEAD와 같은 755이다. 실행 비트가 빠진 채 커밋되는 것을 막으려는 조치다.
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
- 2026-10-13T06:57:34Z — 흔들리는 RealtimeInternalApiIT 수정과 시간 키 연결(contracts/timing.md · application*.yml · RealtimeProperties 동시 수정)을 U4b 범위로 확정해야 한다; timing.md는 U5b와 병합 충돌 지점이다.
- 2026-10-13T06:57:34Z — realtime V3를 넣으면 스키마 판을 정확히 단언하는 U1 시험(RealtimeSchemaIT · core-api FlywayConfIT · SchemaPrivilegeIT)이 깨진다; brief는 U1·U3 시험 단언과 core-api 수정을 금하므로 Requirements Analysis에서 기대값 갱신 예외를 허용할지 정해야 한다(U3도 V2 때 같은 수정을 했다).
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
