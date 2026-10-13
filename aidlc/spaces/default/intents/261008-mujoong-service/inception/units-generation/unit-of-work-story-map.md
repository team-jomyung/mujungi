# 무중 스토리 ↔ 유닛 지도 (Units Generation 2.7)

> Units Generation(2.7) 산출물 · 2026-10-09 (클라우드 세션, 팀장 대리 판단)
> 입력: `stories.md`(US1.1~US6.6, 30개), `requirements.md`, `unit-of-work.md`(U1~U7), `components.md`.
> 규칙: 스토리마다 **주 유닛 하나**(그 스토리의 수용 기준을 완료로 만드는 유닛)를 둔다. 다른 유닛이 칸 · 데이터를 보태면 '함께 만드는 유닛'에 적는다(J18). 유닛 안 스토리 순서는 의존(화면 흐름 · 데이터 주인) 순서이며 유닛 사이 순서는 2.9가 정한다.

## 지도

| 스토리 | 제목 | 우선순위 | 주 유닛 | Directory | 함께 만드는 유닛 (무엇을) |
|---|---|---|---|---|---|
| US1.1 | 구글 로그인 | Must | U2 | u2-guardian-account | U1(시험 로그인 · 로그인 세션 골격) |
| US1.2 | 기록 · 동의 안내 | Must | U2 | u2-guardian-account | — |
| US1.3 | 아이 정보 | Must | U2 | u2-guardian-account | U3(03 보기 카드의 기기 상태 줄 값) |
| US1.4 | 홈 | Must | U2 | u2-guardian-account | U1(대화 기기 구역 최소) · U3(기기 5단계 값) · U4(지금 활동 구역 · 진행 중 탭 → 12) |
| US1.5 | 설정 시트 · 로그아웃 | Must | U2 | u2-guardian-account | — |
| US1.6 | 계정 삭제 | Should | U2 | u2-guardian-account | U3 · U4 · U5 · U6 · U7(각자 데이터 삭제 참가자), U4(진행 세션 CONSENT_WITHDRAWN 종료) |
| US1.7 | 알림으로 돌아오기 | Must | U7 | u7-web-push | U4(재조회 → 12 · 17 · 04 로직) |
| US2.1 | 우리 집 기기 상태 | Must | U2 | u2-guardian-account | U1(상태 사본 · 판정 카드 합산) · U3(연결 5단계 판정 · 보고) |
| US2.2 | 기기 등록 | Must | U1 | u1-walking-skeleton | — |
| US2.3 | 종료 표현 설정 | Should | U2 | u2-guardian-account | U3(종료 의도 판단에 보조 정보로 사용) · U4(세션 스냅숏) |
| US3.1 | 자유대화 시작 | Must | U4 | u4-activity-session | U1(시작 최소 경로) · U2(동의 게이트 · 아이 문맥) |
| US3.2 | 활동 진행 보기 | Must | U4 | u4-activity-session | U3(전사 · 승인된 AI 말 · 끊김 구간) · U5(연습 n/N 머리) |
| US3.3 | 끝내기 | Must | U4 | u4-activity-session | U1(끝내기 최소 경로) · U3(STOP_OUTPUT · 마무리 인사 재생) |
| US3.4 | 예상치 못한 종료 | Should | U4 | u4-activity-session | U3(끊김 시계 · 공급자 끊김 감지) |
| US3.5 | 아이 무응답 | Should | U4 | u4-activity-session | U3(참여 판단 DialogPolicy) |
| US3.6 | 활동 끝 | Must | U4 | u4-activity-session | U6(정리 끝 · 정리 실패 · 「기록에서 다시 시도」) |
| US4.1 | 내 연습 목표 | Must | U5 | u5-practice | — |
| US4.2 | 연습 세션 진행 | Must | U5 | u5-practice | U4(세션 상태 · 12 틀) · U3(역할 · Cue 지시 · 출력 규칙) |
| US4.3 | 연습 목표 후보 담기 | Must | U6 | u6-results-report | U5(담기 · 취소 API · 후보 저장) |
| US5.1 | 기록 달력 | Must | U6 | u6-results-report | U4(활동 기록 조회 API) |
| US5.2 | 활동 기록 상세 | Must | U6 | u6-results-report | U4(대화 기록 칸 데이터 · 끝난 이유) · U5(생활 팁 목록 · 목표 이름 · 상황 대본 · 연습 기록) |
| US5.3 | 리포트 범위 고르기 | Must | U6 | u6-results-report | U4(기간 안 활동) |
| US5.4 | 리포트 확인 · PDF 저장 · 공유 | Must | U6 | u6-results-report | — |
| US5.5 | 리포트 10개 항목과 PDF | Must | U6 | u6-results-report | U4(전사 · 종료 사유) · U5(목표 정의) · U2(아이 이름 · 나이) |
| US6.1 | 처음 켜기 · 코드 | Must | U1 | u1-walking-skeleton | — |
| US6.2 | 상태 화면 · 켜기 끄기 | Must | U3 | u3-voice-conversation | U1(T19 최소) |
| US6.3 | 시작 안내와 참여 확인 | Must | U4 | u4-activity-session | U3(참여 판단 · 재안내 문구 재생) · U2(승인 문구) |
| US6.4 | 자유대화 · 종료 의도 · 마무리 | Must | U3 | u3-voice-conversation | U4(CHILD_STOP 처리 · 마무리 인사 · 종료) · U2(승인 문구) |
| US6.5 | 연습 음성 흐름 | Must | U5 | u5-practice | U3(출력 게이트 Cue 차단 · 지시 적용) |
| US6.6 | 연결 끊김 대응 | Should | U4 | u4-activity-session | U3(재연결 · T19 다시 연결 중 · 공급자 끊김) |

## 유닛별 스토리와 유닛 안 순서

| 유닛 | 스토리(유닛 안 순서) | 개수 |
|---|---|---|
| U1 u1-walking-skeleton | US6.1 → US2.2 | 2 |
| U2 u2-guardian-account | US1.1 → US1.2 → US1.3 → US1.4 → US2.1 → US1.5 → US2.3 → US1.6 | 8 |
| U3 u3-voice-conversation | US6.2 → US6.4 | 2 |
| U4 u4-activity-session | US3.1 → US6.3 → US3.2 → US3.3 → US3.6 → US3.5 → US3.4 → US6.6 | 8 |
| U5 u5-practice | US4.1 → US4.2 → US6.5 | 3 |
| U6 u6-results-report | US5.1 → US5.2 → US4.3 → US5.3 → US5.5 → US5.4 | 6 |
| U7 u7-web-push | US1.7 | 1 |
| 합계 | | 30 |

유닛 안 순서의 까닭: U2는 처음 한 번 흐름(01 → 02 → 03 → 04) 뒤에 기기 화면 · 설정 · Should 순. U4는 시작 → 참여 → 진행 → 끝 순으로 Must 다음에 장애 경로(Should). U6은 기록(26 · 27)이 있어야 담기(27 → 18)와 리포트(28 → 10개 항목 → 30 → PDF)가 선다.

## 여러 유닛에 걸친 스토리 (교차 관심사)

| 관심사 | 걸친 스토리 | 처리 |
|---|---|---|
| 화면 틀과 데이터 주인이 다름 | US1.4(04) · US2.1(05) · US3.6(17) · US5.2(27) | 주 유닛이 틀, 데이터 주인 유닛이 칸(J18) |
| 확장으로 붙는 연습 경로 | US3.2(12 연습 머리) · US4.2 | U5가 U4 컴포넌트를 확장(J17) |
| 계정 삭제 | US1.6 | U2 지휘 틀 + 각 유닛 참가자(J19) |
| 음성 경로 공유 | US6.3 · US6.4 · US6.5 · US6.6 | 음성 · 게이트 · 판단은 U3, 상태 · 시간 규칙은 U4, 연습 단계는 U5 |
| 스켈레톤 최소 경로 | US1.1 · US1.4 · US3.1 · US3.3 · US6.2 | U1이 최소 경로만, 수용 기준 완료는 주 유닛 |

## 확인

- 스토리 30개 모두 주 유닛이 하나씩 있다(위 표).
- 유닛 7개 모두 주 스토리가 하나 이상 있다(U3 · U7 각 1~2개도 독립 시험 가능한 화면 · 음성 흐름을 갖는다).
- Must 스토리 25개, Should 5개(US1.6 · US2.3 · US3.4 · US3.5 · US6.6). 웹 푸시(US1.7, U7)는 시연 네 경로 밖이지만 Must다.
