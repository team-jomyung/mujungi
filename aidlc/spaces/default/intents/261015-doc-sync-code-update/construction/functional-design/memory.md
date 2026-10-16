<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-16T03:20:00Z — U8 질문을 만들기 전에 읽기 전용 조사로 contracts/ · 코드 사실(승인 문구 정본은 core DB, core에 같은 이름 ApprovedContentCatalog 존재, NEO4J_AUTH 기존 비밀, ConfigKeyContractTest 표 규칙)을 먼저 확인했다; Contract Design 검토에서 짐작으로 쓴 이름이 Major 지적을 받은 것을 되풀이하지 않으려는 것이다.
- 2026-10-16T03:21:00Z — 시작 패키지 approvedContent 항목은 audioUrl이 필수라 음성 없는 승인 발화를 넣으면 깨는 변경이 된다; 새 선택 속성 approvedSpeech로 따로 넘겨 더하기만으로 처리했다.
- 2026-10-16T03:22:00Z — 스토리가 없는 U8의 추적표를 FR로만 만들었더니 자동 점검이 스토리 대응표에서 U8이 함께 걸치는 US4.2의 AC를 상위 ID로 요구했다; AC4.2.3 · AC4.2.4는 기록 형식 규칙 BR6.1로, AC4.2.1 · AC4.2.2는 U14 몫으로 N/A 처리했다.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
- 2026-10-16T03:50:00Z — U8 재검토(반복 2)에 R-01 일부가 남았다: 004 시드에서 HELP_OFFER 부모 행 content_type을 APPROVED_SPEECH로 바꾸는 멱등 UPDATE, 새 code의 고정 UUID 범위(SeedIds와 같게), approvedSpeech 조립은 sessionContent가 아니라 latestApproved/pin 경로 — U8 Code Generation 계획에 넣는다.
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
