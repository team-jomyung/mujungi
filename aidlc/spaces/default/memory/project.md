# Project-Level Rules

> Project-specific specialisation and corrections. Loaded after `org.md` and
> `team.md` as strict-additive guidance; contradictions with broader policy
> are rejected. Populated by practices-discovery and the self-learning loop.
>
> Use sparingly: most teams don't need a project layer. Reach for it
> only when this specific project needs stable, durable guidance beyond the
> team practice (for example, package-specific release checks or an additional
> regression suite for a legacy component).

## Way of Working

<!-- Project-specific specialisation. Example: -->
<!-- This monorepo requires package-scoped branch names and a package owner -->
<!-- review in addition to the team's normal merge policy. -->

## Walking Skeleton

<!-- Project-specific specialisation. Example: -->
<!-- The walking skeleton must exercise the legacy service adapter as well -->
<!-- as the new service boundary. -->

## Testing Posture

<!-- Project-specific specialisation. -->

## Guard Policy

<!-- Project-specific. Mode: strict, relaxed, or off. Strict here holds for every intent and cannot be changed from chat. A section under the retired Change Control heading, written by an earlier release, is still read. -->

## Deployment

<!-- Project-specific specialisation. -->

## Code Style

<!-- Project-specific specialisation. -->

## Tech Stack

<!-- Technology choices locked for this project. -->

## Decided

<!-- Decisions made in earlier stages that should not be re-asked. -->
<!-- Format: DECIDED: [decision] (Stage [slug], [date]) -->

## Scope Overrides

<!-- Custom scope rules for this project. -->

## Forbidden

<!-- Populated by practices-discovery affirmation gate. -->
<!-- Format: NEVER [behavior] (affirmed [date]) -->
<!-- Example: NEVER throw exceptions across service layer boundaries (affirmed 2026-05-17) -->

## Mandated

<!-- Populated by practices-discovery affirmation gate. -->
<!-- Format: ALWAYS [behavior] (affirmed [date]) -->
<!-- Example: ALWAYS use Result<T,E> for fallible operations in service layer (affirmed 2026-05-17) -->

## Corrections

<!-- Project-specific corrections from human feedback. -->
<!-- Format: NEVER/ALWAYS [behavior] (learned [date]) -->
- 커밋 전에 git diff --cached --summary로 mode change를 확인하고, 의도하지 않은 실행 권한 변경(예: 755→644)은 git reset으로 스테이지에서 뺀다. WSL에서 Windows 도구가 같은 저장소를 건드리면 스크립트 실행 비트가 스테이지에서 빠질 수 있다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:reverse-engineering:21c43b3cc63a09b87bce07ac5367b367a9a504cf40462bf3a6d9bb0cacde1c3b -->
- 유닛 작업 기록의 Requirements Analysis는 새 업무 요구사항을 만들지 않고, 원래 기록(261008-mujoong-service)의 요구사항과 유닛 설계를 구현 단위로 다시 묶는다. 원래 ID는 '원 FR7.1'처럼 구분해 추적한다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:requirements-analysis:ea128ec96de73d6505c6d044c9a7eb830ba8ccaaee93fe4f8481daac5129448b -->
- 정본 설계가 이미 있는 유닛은 정본끼리 부딪히거나 정본에 답이 없는 것만 질문하고, 설계가 정한 시간 값 · 전이 · 가정은 다시 묻지 않는다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:requirements-analysis:a93a89e29ff30ba2472d5beec6b6075c4685e248674d7fad1e38eb87dd82250f -->
- 단계 산출물(설계 문서 · traceability.json 등)은 셸 스크립트가 아니라 파일 쓰기 도구로 저장한다. 셸로 쓰면 쓰기 기록이 남지 않아 요약 확인 뒤 산출물로 인정되지 않고 검토 요청이 거절된다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:c55237f5ad4c9ff08b9ea393b4c208f6382d1a1b9e3e0a5944f07c3ad8da86a6 -->
- 지운 코드를 되살려 쓰는 설계는 그 커밋의 실제 동작을 설계 정본으로 옮기고, 설계 문서의 표현과 다르면 코드 동작에 맞춰 결정표로 정리한다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:92b3ccb14c7efe04fe002e3d4b22d0de9ec7186fb2d7fbe173ad3ec69e4dfdcd -->
- 유닛 나누기 단계를 건너뛴 유닛 작업 기록은 설계 문서를 유닛 폴더 없이 construction/<stage>/에 쓰고, traceability의 상위 ID는 그 기록의 요구사항 FR/NFR ID로 잡는다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:d57312c6c0a27a26f0f8daf790fec4626dc5e435b09c0b41c44c090bd559e610 -->
- 다른 유닛이 붙을 확장점은 들어오는 시점만이 아니라 확장점이 낼 수 있는 출력 능력(재생 · 공급자 지시 · 시계 · 비동기 결과)과 금지 능력까지 정해, 붙는 쪽이 실행기를 고치지 않게 설계한다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:15f7831c11db5eb1678a97d9ec32253c1fcd70a5515f3890a376ff5a6b88c4d9 -->
- 시험 · 문서 정비 같은 작업 방식 요구사항은 업무 규칙(BR)을 만들지 않고 traceability에 N/A로 추적해 Code Generation 계획에서 단계로 다룬다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:functional-design:8c1a6e455170c995e35e9c45a3a2745f785fe0c166c6c8c52bc64040663257d6 -->
- 세션을 넘겨 Code Generation을 개발자에게 다시 맡길 때는 계획 파일의 단계 체크박스가 비워진 채 전달되므로, 표시를 믿지 말고 커밋 기록 · 저장소 상태로 끝난 단계를 먼저 확인하라고 지시서에 적고 이어갈 단계를 명시한다. (learned 2026-10-13) <!-- cid:261013-u4b-activity-realtime:code-generation:350b106d3beabbc7777f5fb2f8ffd2c0dd1ac9ac83c4004dfb75ffaf27c43542 -->
