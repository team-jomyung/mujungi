<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
- 2026-10-13T08:07:50Z — 참여 결정표는 되살릴 JoinTracker(5354a32)의 동작을 정본으로 옮김; '거부 2회'는 대안 뒤 다시 거부, '무응답 2회'는 재안내 뒤 다시 만료로 읽었다. 불명확은 재안내를 이미 썼으면 안내 없이 같은 창을 계속 기다린다.
- 2026-10-13T07:54:31Z — 유닛 DAG가 없는 작업 기록이라 기능 설계를 유닛 폴더 없이 construction/functional-design/ 한 곳에 씀; traceability.json의 상위 ID는 이야기 AC 대신 이 기록의 요구사항 FR/NFR ID로 잡았다(스토리 단계를 건너뛴 범위).
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
- 2026-10-13T08:07:50Z — 연습 확장점을 들어오는 시점 넷에서 다섯(확장점 사건 추가)으로 늘리고 출력 능력(재생 · 공급자 지시 · 시계 · post)을 더함; 질문 Q1(네 시점)을 넘어서는 보강이지만, 검토 지적(U5b가 실행기를 고치지 않고 붙을 수 없음)을 막으려면 필요했다.
- 2026-10-13T07:54:31Z — 추적 표를 셸 스크립트로 처음 저장해 쓰기 기록이 남지 않아 검토 요청이 거절됨; 같은 내용을 파일 쓰기 도구로 다시 저장해 해결했다. 산출물은 셸이 아니라 파일 쓰기 도구로 저장해야 한다.
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
- 2026-10-13T08:07:50Z — 연습 정상 종료 사유로 새 열거 값 대신 CHILD_STOP + end_detail PRACTICE_COMPLETED를 사람이 골랐음(Q6); 계약 · U6 · U7 · 기기 명령을 바꾸지 않고 완료 푸시도 나가지만, 사유만으로 '아이가 그만하자고 함'과 구별되지 않아 end_detail을 봐야 한다.
- 2026-10-13T07:54:31Z — FR11(시험 · 문서 정비)은 업무 규칙이 아니라 작업 방식 요구사항이라 BR을 만들지 않고 N/A로 추적해 Code Generation 계획으로 넘김; 규칙 목록을 업무 판단만으로 유지하기 위해서다.
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
