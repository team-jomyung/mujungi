# 스토리 ↔ 작업 단위 대응

> 근거: `../user-stories/stories.md`(US1.1~US5.2), `unit-of-work.md`, `unit-of-work-dependency.md`, `../domain-design/components.md`, `../requirements-analysis/requirements.md`.
> 스토리마다 주 단위 하나를 정한다. 다른 단위가 있어야 완성되는 스토리는 '함께 걸치는 단위'에 적는다.

## 스토리별 단위

| Story | 제목(줄임) | Unit ID | Directory | 함께 걸치는 단위 |
|---|---|---|---|---|
| US1.1 | 시작 때 참여 · 휴식 · 거절 | U12 | u12-practice-conversation | U11(J1 판정) |
| US1.2 | 되묻기로 해결되면 그대로 | U12 | u12-practice-conversation | U11, U13(친구 역할 새 발화가 들리려면) |
| US1.3 | 상황을 모르면 먼저 설명 | U12 | u12-practice-conversation | U10(다시 설명 필드) |
| US1.4 | 도움은 원할 때만 | U12 | u12-practice-conversation | U11(J4) |
| US1.5 | 코치와 짧게 연습 후 복귀 | U12 | u12-practice-conversation | U11(J5), U13(코치 발화 · 전환 알림이 들리려면), U10(집중 요점 · 표현 예시) |
| US1.6 | 여유 있으면 변형 장면 | U12 | u12-practice-conversation | U10(변형 장면), U15(보존 판정) |
| US1.7 | 쉬거나 그만하면 바로 | U11 | u11-judgment-turn | U12(연습 중 종료 · 휴식 처리) |
| US1.8 | 판단 못 할 때 고치려 들지 않기 | U11 | u11-judgment-turn | U12(보류를 교정으로 바꾸지 않음) |
| US1.9 | 무응답이면 한 번만 다시 | U12 | u12-practice-conversation | U8(시간 값) |
| US1.10 | 판정 중 미확인 말 차단 · '생각 중' | U11 | u11-judgment-turn | — |
| US2.1 | 새 발화는 안전 검사 통과분만 | U13 | u13-jev-guardrail | U11(게이트 보류 · 출력 세대) |
| US2.2 | 승인된 말은 바로 재생 | U13 | u13-jev-guardrail | U8(승인 원문 · 세션 고정 대본) |
| US2.3 | 운영 환경에서 검사 꺼짐 금지 | U13 | u13-jev-guardrail | U9(키 없으면 실제 어댑터 끔) |
| US2.4 | Luna 생성물은 제 경로로 검사 | U9 | u9-luna-clients | U10, U14, U15(각 Luna 경로) |
| US2.5 | 자유대화도 같은 안전 규칙 | U11 | u11-judgment-turn | U13(FREE_TALK 검사) |
| US3.1 | 정답을 흘리지 않는 연습 상황 | U10 | u10-scenario-generation | U9, U14(담은 목표 입력) |
| US3.2 | 그래프가 있으면 맞춤, 없어도 안전 | U15 | u15-knowledge-graph | U10(기본 경로) |
| US4.1 | 전달 시점이 정확한 결과 값 | U14 | u14-goal-result | U8(기록 캡처 이벤트 계약) |
| US4.2 | 하지 않은 장면을 실패로 보이지 않기 | U14 | u14-goal-result | U8, U12(장면 노출 기록) |
| US4.3 | 예전 결과는 예전 그대로 | U14 | u14-goal-result | — |
| US4.4 | 결과 정리 재시도 | U14 | u14-goal-result | — |
| US4.5 | 근거가 있는 목표 후보 | U14 | u14-goal-result | U9, U15(씨앗 목록) |
| US4.6 | 담은 목표가 다음 연습으로 | U14 | u14-goal-result | U10(상황 입력), U11(판정 기준) |
| US5.1 | 새 씨앗이 다음 연습에 쓰임 | U15 | u15-knowledge-graph | U14(NONE 후보) |
| US5.2 | 아이 자료 삭제 시 그래프도 삭제 | U15 | u15-knowledge-graph | — |

## 단위별 스토리와 단위 안 구현 순서

단위 안에서 먼저 만들어야 뒤 스토리가 시험 가능한 순서다. 단위 사이 순서는 Delivery Planning이 정한다.

| Unit ID | Directory | 스토리(단위 안 순서) |
|---|---|---|
| U8 | u8-contracts-catalog | 스토리 없음 — FR10 · FR11(계약 · 버전 리소스 · 시간 값 · 오류 코드 · 비밀 이름 · 이벤트 필드) |
| U9 | u9-luna-clients | US2.4 |
| U10 | u10-scenario-generation | US3.1 |
| U11 | u11-judgment-turn | US1.10(판정 턴 보류 · '생각 중') → US1.8(보류 규칙 · `judgment_live`) → US1.7(종료 · 휴식) → US2.5(자유대화 J1 · J6) |
| U12 | u12-practice-conversation | US1.1(시작) → US1.2(기회 · 되묻기) → US1.3(다시 설명) → US1.9(무응답) → US1.4(도움 제안) → US1.5(코치) → US1.6(변형) |
| U13 | u13-jev-guardrail | US2.3(프로필 · 모드 · 기동 검사) → US2.1(게이트 제브 단계 · 늦은 결과 · 계측) → US2.2(승인 원문 생략) |
| U14 | u14-goal-result | US4.4(재시도) → US4.3(버전 구분) → US4.1(시점 · 27 접기) → US4.2(새 기록 사실) → US4.5(후보 생성) → US4.6(담기 · 연결) |
| U15 | u15-knowledge-graph | US3.2(어댑터 · 대역 · 기본 경로) → US5.2(삭제 참여) → US5.1(새 씨앗) |

## 여러 단위에 걸치는 관심사

- **삭제 · 동의 철회**(Domain Design 지적 R-02): 새 개인 자료를 담는 단위는 모두 기존 삭제 참여자를 넓혀야 한다. U11(`judgment_live`) · U12(장면 · 도움 기록) · U13(`guardrail_event`) · U10(대본) · U14(후보 · 장면 결과) · U15(그래프 · 씨앗)가 해당한다. 각 단위의 Functional Design에서 정한다.
- **가짜 공급자만 쓰기**(R-F1): U9(Luna) · U13(제브) · U11(GPT-Live 지시 형식)의 가짜 공급자가 전 단위 시험의 기반이다.
- **증거 나눠 보고**(R-M11): 대역 · 합성 · 실호출 · 실기기 결과를 단위마다 따로 적는다.

## 커버리지 확인

- 스토리 25개가 모두 주 단위 하나에 들어갔다. 빠진 스토리는 없다.
- 스토리가 없는 단위는 U8 하나다. U8은 스토리 밖 운영 · 개발 요구(FR10 · FR11)를 담고, stories.md의 '스토리 밖 추적' 표가 시험 항목을 잇는다.
- FR12(실호출 · 실기기)는 단위가 아니라 Build and Test에서 다룬다.

## Sources

- `../user-stories/stories.md`('의존과 순서' · '스토리 밖 추적')
- `unit-of-work.md`, `unit-of-work-dependency.md`
- `../domain-design/components.md` · `../domain-design/decisions.md`
- `../requirements-analysis/requirements.md`

## Assumptions & Open Questions

- 단위 안 구현 순서는 시험 가능성 기준의 제안이다. 실제 작업 순서는 Construction의 Code Generation 계획에서 확정한다.
