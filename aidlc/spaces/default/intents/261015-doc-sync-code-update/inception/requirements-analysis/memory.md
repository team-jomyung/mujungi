<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
- 2026-10-15T08:55:00Z — 사람이 넣은 지식 그래프 압축 파일을 바로 열어 확인해, 실그래프 원본(graph_out) 부재와 재배포 불가 자료 포함(라이선스)을 요구사항의 열린 질문으로 올렸다.
- 2026-10-15T08:20:00Z — 처음 요청 「업데이트 된 문서」를 2026-10-15 문서 변경분(00_읽는순서 · 30 · 40 · 50 · 80의 모델 역할 수정)과 새 15_기술명세_AI동작_1015 두 문서, 그리고 사람이 추가로 지정한 Luna·JEV 인계서 v2로 읽었다; git diff로 기존 문서의 실제 변경이 모델 역할(Luna 판정 · 제브 가드레일)에 몰려 있음을 확인했다.
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->

## Deviations
- 2026-10-15T08:55:00Z — 문서끼리 부딪힐 때 인계서(C)를 따르기로 했지만, 사람이 목표 후보 생성(Q6)과 아이별 그래프 저장(Q10)에서 기술명세 쪽을 명시적으로 골라, 그 두 점만 예외로 요구사항에 적었다; 일반 우선순위보다 질문별 명시적 선택을 앞세웠다.
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->

## Tradeoffs
- 2026-10-15T08:55:00Z — 문서 · 코드 차이 조사를 읽기 전용 조사 담당에게 맡겨 갭 보고서로 받고, 요구사항 질문은 문서끼리의 충돌과 범위만 묻는 데 썼다; 질문 수를 줄이고 나머지 13개는 가정 묶음으로 확인받았다.
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->

## Open questions
- 2026-10-15T08:20:00Z — 기술명세서 v0.3과 인계서 v2가 제브 검사 대상 · 시점, 진행 중 판정 방식에서 부딪힌다. 어느 쪽을 따를지 사람 결정이 필요하다.
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
