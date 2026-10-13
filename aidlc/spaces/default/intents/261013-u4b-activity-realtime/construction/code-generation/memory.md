<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-13T11:00:00Z — 계획 머리글의 저장소 경로를 `/home/jdmea/mujungi`에서 실제 경로 `/home/jdmea/finalProject`로 고치고 브랜치도 현재 브랜치(master)로 맞췄다. 계획 단계 내용은 그대로 두었고, 이 한 줄 수정 때문에 승인 지문을 다시 만들어 재승인을 받았다.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-13T11:00:00Z — aidlc 2.8.2가 시험 규약의 ordering 문장을 "…then write and run"에서 잘라 계약 해시를 바꿔 놓아 승인이 무효가 됐다. 2.11.0으로 올리니 원래 문장과 해시(84a32ec0…)로 돌아왔다. 2.8.2 기준으로 계획서를 맞추지 않고, 업데이트한 뒤 원래 값으로 되돌리는 쪽을 택했다.
- 2026-10-13T11:00:00Z — 개발자에게 인계할 때 계획 파일의 단계 체크박스가 모두 비워진다. Step 1 ~ 5(L1)는 이미 커밋돼 있으므로, 표시를 믿지 말고 저장소 상태를 먼저 확인한 뒤 Step 6부터 이어가라고 지시서에 명시했다.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
- 2026-10-13T11:00:00Z — 세션이 바뀌면 계획 승인 영수증을 새 세션에서 다시 받아야 한다. 승인 기록은 사람이 직접 타이핑한 입력이나 그 세션의 승인 질문 응답에만 묶이므로, 긴 코드 생성 작업을 여러 세션에 걸쳐 하면 매 세션 승인 단계가 한 번씩 더 생긴다. 다음 작업에서 이 비용을 줄일 방법이 있는지 확인할 것.
