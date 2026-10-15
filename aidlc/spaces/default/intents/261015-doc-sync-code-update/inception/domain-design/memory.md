<!-- INVARIANT: examples are single-line HTML comments so a fresh template parses to total=0 (MEMORY_EMPTY). Do NOT un-comment or split across lines. t100 guards this. -->
> This file is kept up to date automatically while the stage runs. Add observations at the review step, not by editing here directly.

## Interpretations
<!-- example: 2026-05-29T10:14:32Z — chose REST over GraphQL; the consuming team only needs CRUD, revisit if subscriptions land -->
- 2026-10-16T00:20:00Z — Q2 질문의 전제(후보는 core.result가 저장)를 코드로 다시 확인하니 후보 · 담은 목표 표는 이미 core.practice(`CandidateRegistry` · `PracticeGoalRepository`)가 쓰고, core.result는 `CandidateScreen`으로 고르기만 한다; 답(A)의 뜻대로 새 `PracticeGoal`이 두 엔티티와 Luna 후보 생성을 모두 맡고 core.result는 요청만 한다고 정리했다.
- 2026-10-16T00:21:00Z — 실시간 세션 실행부와 새 연습 흐름 사이 순환을 피하려고, 연습 흐름은 실행부를 부르지 않고 '다음 행동 지시'만 돌려주며 실행부가 그 지시를 수행한다고 정했다; 확장점이 낼 수 있는 출력 능력(재생 · 공급자 지시 · 시계 · 비동기 결과)을 정해 두라는 project.md 교정을 따른 것이다.

## Deviations
<!-- example: 2026-05-29T10:14:32Z — skipped the optional caching layer the stage prose suggested; the dataset is small enough that it adds risk -->
- 2026-10-16T00:50:00Z — 검토가 끝난 뒤 사람이 조원 하네스 압축 파일을 추가 자료로 넣어, 질문 Q4를 더하고 components.md(VoiceRelay · JevGuardrail · 열린 항목)와 decisions.md(ADR-007)를 고쳤다; 검토가 이 변경분은 보지 않았으므로 승인 화면에서 그 사실을 알린다. 검토 지적 R-01~R-08은 advisory 규칙대로 고치지 않고 승인 판단에 넘긴다.

## Tradeoffs
<!-- example: 2026-05-29T10:14:32Z — picked TDD over BDD this run; the team is unit-first and the domain is well-understood -->
- 2026-10-16T00:22:00Z — Q3에서 사람은 추천(C, 칩 생략) 대신 A('바로 전달'로 접기)를 골랐다; 27 모양을 그대로 지키는 쪽을 택한 것이므로, 접기는 보호자 27 표시 매핑에만 두고 API · 리포트 집계 · 저장 값은 `AFTER_REASK`를 유지해 사실 기록이 흐려지지 않게 했다.
- 2026-10-16T00:23:00Z — Luna 호출부를 공용 라이브러리(contract-model)로 묶지 않고 core · realtime에 각자 둔다; contract-model은 Spring · 외부 SDK 없는 계약 타입 모듈이고 두 앱 직접 참조 금지(R-F5)를 지키는 쪽이 중복 비용보다 낫다고 봤다.

## Open questions
- 2026-10-16T00:51:00Z — 서버 쪽 음성 전송이 GPT-Live에서 되는지(ADR-007의 핵심 위험)는 FR12.2 실호출 전까지 모른다; 조원 예제는 WebRTC만 썼다.
- 2026-10-16T00:52:00Z — social-comm-poc.zip이 이미 개인 원격에 올라가 있다; 재배포 불가 자료가 들어 있어 main PR 전 이력 정리가 필요할 수 있다.
<!-- example: 2026-05-29T10:14:32Z — confirm the retention window with compliance before the next stage hardens the schema -->
