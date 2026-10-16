# Code Generation Plan Approval

AI-DLC writes this file when it asks you to approve the plan. To answer here
instead of in chat, write your answer after `[Answer]:` and say done.

## Plan Approval

Approve the code plan for u9-luna-clients?

- 18 plan steps
- Tests: see unit-test-instructions.md

Full plan: aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/code-generation/code-generation-plan.md
Test instructions: aidlc/spaces/default/intents/261015-doc-sync-code-update/construction/u9-luna-clients/code-generation/unit-test-instructions.md

[Approval Fingerprint]: sha256:v3:54647f6a6139ed0b1ff087845718c463bbf657e5a77676fefb9b5193cd9d5991
[Planned Source]: ed2298c02c4acf789892f56867ffae6db32af116329ecea884607ac90da1c40d

- A. Approve Plan
- B. Request Changes
- C. I'll edit the files

[Answer]: A. Approve Plan

## Q1. (검토 뒤) 코드 검토 지적을 어떻게 처리할까

코드 검토는 '진행 가능'이었지만 Major 1건 · Minor 7건이 남았다(`reviews/review-01.md`). R-01(Major): 별칭 치환이 순서가 정해지지 않은 표를 차례로 `replace`해서, 한 이름이 다른 이름에 포함되면(예: '민수' · '수') 긴 이름이 일부만 바뀌어 원래 이름 조각이 공급자로 나갈 수 있다. Minor: R-02 금지 키 비교가 정확 일치뿐(`child_name` · `api_key` 같은 변형 키 통과), R-03 마감 직전 몇 밀리초짜리 재시도, R-04 공급자를 부르지 않은 거부도 `attempt=1`로 기록, R-05 realtime 호출부가 일부 런타임 예외를 `CompletionStage` 대신 바로 던짐, R-06 `HttpClient`를 닫지 않음, R-07 기존 시험 단언 17 → 19종 변경(근거 있음), R-08 상황 생성 입력에서 관심사 제외(U10 설계 몫).

A. 지금 고친 뒤 진행 — R-01(긴 이름부터 치환 + 포함 관계 시험)과 R-02 ~ R-06을 코드와 시험으로 고치고, R-07은 편차 기록 확인, R-08은 U10 열린 질문으로 넘긴 뒤 다시 검토를 받는다 (추천)
B. R-01만 고치고 나머지는 Build and Test · 뒤 단위로 넘긴다
X. Other (please specify)

[Answer]: A
