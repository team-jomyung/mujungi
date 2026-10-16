# 볼트 담당

> **볼트**는 단위 하나를 설계 · 코드 · 시험까지 끝내 실제로 돌아가는 결과를 내는 작업 한 바퀴다. 이 작업은 단위 하나가 볼트 하나다.
> 근거: `bolt-plan.md`(같은 폴더), `../units-generation/unit-of-work.md` · `unit-of-work-dependency.md`, `../contract-design/contract-summary.md`, 팀 규칙 `../practices-discovery/team-practices.md` · `team.md`, 팀 방식 문서 `docs/units/README.md`, 질문 · 답 `delivery-planning-questions.md`(Q3-A 팀 방식 · Q4-A · Q4-1 A · 가정 7).
> Team Formation 단계는 건너뛰었다(`classic` 범위). 그래서 담당은 'AI 개발 에이전트(aidlc-developer-agent) + 사람 담당자'로 적고, 사람 이름은 지시서를 넘길 때 정한다.

## 담당 방식

팀 방식을 그대로 쓴다(Q3-A).
- 이 작업 기록(`261015-doc-sync-code-update`)은 공통 인셉션 기록이다. 이 PC 담당자는 공통 기반 볼트(B1 · B2)만 여기서 Construction 한다.
- 나머지 볼트는 단위마다 지시서(`docs/units/u10` ~ `u15/brief.md`)로 넘긴다. 담당자가 **자기 PC에서 새 작업 기록**을 만들어 구현한다. 승인 증빙이 PC마다 따로라 남의 기록은 이어 가지 않는다(team.md).

## 담당 줄 (2~3명 기준)

같은 큰 파일을 고치는 볼트는 한 사람이 차례로 맡는다(Units Generation 설명, project.md 교정).

| 줄 | 볼트(순서) | 담당 기록 | 이유 |
|---|---|---|---|
| 공통 기반 | B1 U8 → B2 U9 | 이 기록(이 PC 담당자) | 모든 볼트가 기대므로 가장 먼저 끝내 PR로 합친다 |
| realtime | B3 U11 → B5 U13 → B8 U12 | 담당자 A의 새 기록(볼트마다 또는 줄 하나로) | 셋 다 `SessionExecutor` · `OutputGate`를 고친다. 한 사람이 차례로 하면 병합 충돌이 없다 |
| core | B4 U15 → B6 U14 → B7 U10 | 담당자 B의 새 기록 | core 안에서 의존이 직렬(U15 → U14 → U10)이고 `core.practice`를 함께 고친다 |

- **3명일 때:** 이 PC 담당자는 B1 · B2 뒤 core 줄이나 realtime 줄 한쪽을 돕는다. 같은 파일을 고치는 볼트를 두 사람에게 나누지는 않는다.
- **2명일 때:** 이 PC 담당자가 B1 · B2 뒤 core 줄을 이어 맡고, 다른 한 명이 realtime 줄을 맡는다. 이때 이 PC 담당자의 core 줄 볼트도 새 작업 기록으로 시작한다(이 기록은 B1 · B2까지).
- 두 줄이 만나는 곳은 B8(U12 → U10 대본 계약)이다. B7이 먼저 합쳐져야 B8이 대본 필드를 실제로 받는다. 그 전에는 계약 C-DS1의 대역으로 만든다.

## 볼트 → 담당

| 볼트 | 단위 | 담당 | 브랜치 | 시험 태그 · 범위 파일 |
|---|---|---|---|---|
| B1 | U8 계약 · 버전 리소스 | AI 개발 에이전트 + 이 PC 담당자 | `unit/u8-contracts-catalog` | `@Tag("u8")` · `coverage-scope/u8.*`(코드가 있는 경우) |
| B2 | U9 Luna 호출부 | AI 개발 에이전트 + 이 PC 담당자 | `unit/u9-luna-clients` | `@Tag("u9")` · `coverage-scope/u9.*` |
| B3 | U11 판정 턴 기반 | AI 개발 에이전트 + realtime 담당자(미정) | `unit/u11-judgment-turn` | `@Tag("u11")` · `coverage-scope/u11.*` |
| B4 | U15 지식 그래프 | AI 개발 에이전트 + core 담당자(미정) | `unit/u15-knowledge-graph` | `@Tag("u15")` · `coverage-scope/u15.*` |
| B5 | U13 제브 게이트 | AI 개발 에이전트 + realtime 담당자(미정) | `unit/u13-jev-guardrail` | `@Tag("u13")` · `coverage-scope/u13.*` |
| B6 | U14 목표 · 결과 | AI 개발 에이전트 + core 담당자(미정) | `unit/u14-goal-result` | `@Tag("u14")` · `coverage-scope/u14.*` |
| B7 | U10 상황 생성 | AI 개발 에이전트 + core 담당자(미정) | `unit/u10-scenario-generation` | `@Tag("u10")` · `coverage-scope/u10.*` |
| B8 | U12 새 연습 흐름 | AI 개발 에이전트 + realtime 담당자(미정) | `unit/u12-practice-conversation` | `@Tag("u12")` · `coverage-scope/u12.*` |

- 범위 파일을 새로 쓰면 빈 목록 · 없는 파일이면 측정이 실패하는 지금 방식을 그대로 따른다(team.md J137). CI가 실제로 막는 범위에 새 단위를 넣을지는 각 단위 Build and Test에서 정한다.

## 조율 규칙

- **계약 변경:** 공통 계약은 B1이 소유한다. 다른 볼트는 더하기만 하고, 깨는 변경은 제공 · 소비 담당이 함께 고쳐 같은 날 머지한다(`contracts/README.md`).
- **마이그레이션 번호:** `bolt-plan.md`의 표대로 미리 나눠 준다. 번호를 바꾸면 같은 앱의 다음 볼트 담당에게 바로 알린다.
- **삭제 참여자 순서 상수:** 새 참여자는 기존 값(60 · 70 · 90 · 100 · 150 · 200 · 250 · 300 · 900)과 겹치지 않게 지시서에서 미리 정한다.
- **같은 파일 충돌:** `contracts/deviations.md` · `contracts/timing.md`는 여러 볼트가 행을 더한다. 병합할 때 양쪽 행을 모두 둔다(`docs/units/README.md`).

## Sources

- `bolt-plan.md`, `risk-and-sequencing-rationale.md`, `external-dependency-map.md`(같은 폴더)
- `../units-generation/unit-of-work.md`, `../units-generation/unit-of-work-dependency.md`, `../contract-design/contract-summary.md`
- `../practices-discovery/team-practices.md`, `aidlc/spaces/default/memory/team.md`, `docs/units/README.md`
- `delivery-planning-questions.md`

## Assumptions & Open Questions

- 사람 담당자 이름과 인원(2명 / 3명)은 지시서를 넘길 때 정한다.
- realtime 줄을 볼트마다 새 기록으로 할지, 줄 하나를 한 기록으로 할지는 담당자가 지시서를 받은 뒤 정한다. 어느 쪽이든 볼트 하나가 PR 하나다.
