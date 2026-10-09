# User Stories — 스토리 계획과 질문

> 무중(Mujoong) · 깊이 Standard · 2026-10-08
> 입력: `../requirements-analysis/requirements.md`, 화면 설계 v1.5j(화면과 분기), docs/aidlc_drafts/inception/user-stories/ 초안
> 아래 Q1~Q4는 사람이 2026-10-08 승인 메시지에서 채팅으로 미리 답한 내용을 그대로 옮긴 것이다(**Mode:** chat). Q0만 비어 있다.

## 스토리 계획

- **페르소나**: 보호자(P1, 주 사용자 · 폰 웹 /parent) · 아이(P2, 음성 기기 웹 /device · 켜기/끄기만) · 운영자(P3, 데모 준비 · 화면 없음) · 전문가(비사용자, 받은 PDF만). personas.md에 목표 · 불편 · 숙련도 · 빈도 · 보호 규칙을 적는다.
- **스토리 형식**: "**[페르소나]**로서, [하고 싶은 것]. 그래야 [가치]." + INVEST 메모 + 의존. ID는 `US{에픽}.{순번}`, 수용 기준은 `AC{에픽}.{순번}.{기준}`.
- **묶는 단위**: 화면 설계의 차선(LANE) 여섯 개를 에픽으로 — US1 처음 한 번과 홈(01 · 02 · 02-1 · P5 · 03 · 04 · SET0 · P7) · US2 기기(05 · 05a · SET-01) · US3 자유대화와 활동 진행 · 끝(08 · 12 · P1 · P2 · P3 · 17) · US4 연습(18 · 12 연습 · 27 담기) · US5 기록과 리포트(26 · 27 · 28 · 30 · R-PDF) · US6 아이 기기 웹(D0 · T19 + 음성 흐름 3개). 스토리 하나 = 화면 카드 하나(아이 기기는 음성 흐름 하나).
- **수용 기준**: 화면 분기표 한 줄 = AC 한 줄, `Given 화면 · 상태 / When 단추 · 사건 / Then 어디로 · 무엇이 보이나` 꼴로 옮긴다(Inception 규칙의 Given/When/Then). 개발 명세의 검증 규칙은 "검증:" 접두로 덧붙인다. 서버 기능(FR7)은 별도 스토리 대신 그것이 드러나는 스토리의 AC에 넣는다.
- **우선순위**: requirements.md와 동일(Must = 11/5 시연 네 경로, Should = 구현하되 시연 필수 아님). MVP 경계는 Delivery Planning에서 확정.
- **예상 규모**: 페르소나 3 + 비사용자 1, 에픽 6, 스토리 30, AC 약 200.

## Q0. 계획 확인 — 답하는 방식

위 계획(Q1~Q4 답 포함)대로 스토리를 만들고 디자이너 · 개발자 · 품질 엔지니어 검토를 돌릴까요? 질문 답하는 방식을 하나 골라 주세요. 채팅으로 이미 답해 주셨으므로 **Chat**이 자연스럽습니다.

A. Guide me
B. I'll edit the file
C. Chat
X. Other (please specify)

[Answer]: Chat — 사람 답 원문: "Chat. 이미 준 답(페르소나 3 + 비사용자 전문가, 에픽 6 = 차선 그대로, 스토리 30, 수용 기준은 분기표 한 줄 = Given/When/Then 한 줄, 우선순위는 requirements.md와 동일)을 그대로 기록하고 진행하라. 검토 반영과 traceability.json까지 마친 뒤 승인 요청에서 멈춰라."

## Q1. 페르소나는 누구인가

A. 보호자 · 아이 두 명
B. 보호자 · 아이 · 운영자(데모 준비) 세 명 + 전문가(PDF 수신)는 비사용자 이해관계자
C. 보호자 · 아이 · 전문가 세 명(전문가 계정 가정)
X. Other (please specify)

[Answer]: B — 2026-10-08T14:47:23Z **Mode:** chat. 사람 지시: 초안 personas.md(보호자 · 아이 · 운영자) 참고. 전문가는 계정이 없어(G22) 비사용자로 둔다.

## Q2. 스토리를 어떤 단위로 묶는가

A. 기능 영역(요구사항 FR1~FR7)별
B. 화면 설계의 차선(LANE) 여섯 개별 — 스토리 하나 = 화면 카드 하나
C. 페르소나별
D. 담당자(A · B · C · D · E)별
X. Other (please specify)

[Answer]: B — 2026-10-08T14:47:23Z **Mode:** chat. 사람 지시 원문: "스토리는 화면 설계의 차선(처음 한 번 · 기기 · 자유대화 · 연습 · 기록 · 아이 기기 웹) 단위로 묶고"

## Q3. 수용 기준은 무엇으로 쓰는가

A. 화면 분기표 한 줄을 그대로 한 줄로(Given/When/Then 꼴로만 정리)
B. 요구사항 완료 기준을 스토리별로 다시 풀어 쓴다
C. 분기표 + 요구사항 완료 기준 둘 다
X. Other (please specify)

[Answer]: A — 2026-10-08T14:47:23Z **Mode:** chat. 사람 지시 원문: "수용 기준은 화면 분기표를 그대로 쓰면 된다(docs/aidlc_drafts/inception/user-stories/stories.md 초안 참고)". 개발 명세의 검증 규칙(글자 수 · 범위)은 "검증:" 줄로 덧붙이고, 화면이 없는 아이 음성 흐름은 FR5의 완료 기준을 Given/When/Then으로 옮긴다.

## Q4. 스토리 우선순위는 어떻게 매기는가

A. requirements.md의 Must/Should/Could를 그대로(Must = 시연 네 경로)
B. 모든 스토리를 Must로 두고 Delivery Planning에서 정리
C. 담당자별로 다시 매긴다
X. Other (please specify)

[Answer]: A — 2026-10-08T14:47:23Z **Mode:** chat. Requirements Analysis Q6 답(Must = 처음 한 번 · 자유대화 1회 · 연습 1회 · 기록·리포트, 장애 경로는 Should)을 그대로 쓴다.
