# Code Generation 질문 — 구조 · 이름 리팩토링

> 이 단계는 설계 단계에서 이미 정해진 것(구조, 규칙, 합격 기준)을 실행 계획으로 옮긴다. 따로 묻는 설계 질문은 없고, 아래 **Plan Approval**이 유일한 필수 질문이다. 계획은 `code-generation-plan.md`(Testing Contract 포함), 시험 지침은 `unit-test-instructions.md`다.
> 계획 승인은 **계획 내용**만 승인한다. 코드는 승인 뒤에 한 단계씩 바꾸고, 단계가 끝날 때마다 `verify.sh`를 통과해야 다음으로 간다. 계획에서 사람에게 다시 묻기로 한 지점은 두 곳이다: Step 17(게이트 경계 · 연습 확장점 분리 여부)과 Step 24(`team.md` 규칙 개정 승인).

## Plan Approval

[Approval Fingerprint]: sha256:v3:1ac49aa41d9c634de376ad0170e599b8ed69da226129f6c84c2ed1d309755441
[Planned Source]: 96ca27a4d5eaca580520f587d558d0a23b73c7b76b9720606f1186e8cb206193

- "Approve Plan": proceed to code generation
- "Request Changes": revise the plan

[Answer]: Approve Plan
