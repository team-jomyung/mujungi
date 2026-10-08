# unit-of-work.md (초안)

> 초안. 화면 설계 v1.5j(2026-10-08)와 개발 명세 1008에서 뽑았다. AI-DLC Edit File 모드에서 붙여넣기용이며 번호 · 절 구성은 AI-DLC가 다시 매긴다. 화면 설계가 기준이다.


유닛은 WBS 3.x 중간 유닛과 같다. 3.5 실제 연결은 유닛이 아니라 10/28 통합 작업.

## Unit `guardian-app` · 보호자 앱 (Next.js /parent + Core API)
- WBS: WBS 3.2 보호자 서비스 (A · C · E)
- 화면: S-01 보호자 로그인, S-02 기록 · 동의 안내, S-02-1 약관 자세히 보기, S-03 아이 정보 (처음 등록 · 보기 · 수정), S-04 홈 (구역 셋: 아이 · 대화 기기 · 지금 활동), S-SET0 설정 시트, P-05 팝업 · 동의 전 나가기, P-07 팝업 · 계정 삭제 확인, S-05 우리 집 기기 (두 줄 + 판정 카드), S-05A 기기 등록, S-SET1 종료 표현 설정, S-08 자유대화, S-18 내 연습 목표, S-26 기록 (달력), S-27 활동 기록 상세, S-28 리포트 범위 고르기, S-30 PDF 저장 전 확인, R-PDF 리포트 PDF 템플릿
- API 후보: `DELETE /api/goal-selections/{id} (소프트 삭제 · 3초 안 복구 가능)`; `DELETE /api/me`; `DELETE /api/reports/{id} (GENERATING일 때 취소)`; `GET /api/child`; `GET /api/consents`; `GET /api/device`; `GET /api/device/status (10초 폴링 또는 SSE)`; `GET /api/goal-selections?childId`; `GET /api/home`; `GET /api/policies/current`; `GET /api/policies/current`; `GET /api/records?childId&date=2026-10-07` …
- 테이블: core_activity_context, core_approved_content, core_child, core_consent, core_goal_recommendation, core_practice_goal_selection, core_recommendation_evidence, core_report, core_report_activity, core_result, core_social_account, core_spring_session, core_stop_expression, core_support_item, realtime_activity_session, realtime_practice_attempt, realtime_transcript

## Unit `activity-session` · 활동 세션 (Core ↔ Realtime 공통: 시작 · 진행 · 끝)
- WBS: WBS 3.1 공통 활동 (B · C)
- 화면: S-12 활동 진행 (자유대화 · 연습 공통), S-17 활동 끝 (자유대화 · 연습 공통), P-01 팝업 · 끝내기, P-02 팝업 · 예상치 못한 종료, P-03 팝업 · 아이 무응답
- API 후보: `GET /api/sessions/{id}`; `GET /api/sessions/{id}/result/status (폴링 3초)`; `GET /api/sessions/{id}/stream (SSE: transcript.delta, transcript.done,`; `POST /api/sessions/{id}/end`; `POST /api/sessions/{id}/end {reason:GUARDIAN_STOP}`; `POST /api/sessions/{id}/result/retry`
- 테이블: core_result_job, core_session_result_state, realtime_activity_session, realtime_session_event, realtime_transcript

## Unit `voice-device` · 아이 기기 웹 (/device) · 음성 중계 · 게이트
- WBS: WBS 3.3 연결 · 음성 (B · E)
- 화면: D-00 아이 기기 웹 · 처음 켜기, D-19 아이 기기 웹 · 상태 화면
- API 후보: `GET /api/device/bootstrap/status`; `POST /api/device/bootstrap {deviceSecret?}`; `POST /api/device/heartbeat (WS 없을 때)`; `WS /ws/device?token= ← state{IDLE|LISTENING|THINKING|SPEAKING|CLOSING|`
- 테이블: realtime_activity_session

## Unit `ai-goals-results` · AI · 목표 · 연습 · 결과 (Result Worker · Neo4j · 판정)
- WBS: WBS 3.4 AI · 목표 · 연습 (D · C · E)
- 화면: S-27 활동 기록 상세, S-18 내 연습 목표, S-28 리포트 범위 고르기, R-PDF 리포트 PDF 템플릿
- API 후보: `DELETE /api/goal-selections/{id} (소프트 삭제 · 3초 안 복구 가능)`; `DELETE /api/reports/{id} (GENERATING일 때 취소)`; `GET /api/goal-selections?childId`; `GET /api/reports/preview?childId&from&to`; `GET /api/reports/{id} (폴링 2초)`; `GET /api/sessions/{id}/result`; `PATCH /api/goal-selections/{id} {favorite}`; `POST /api/activities/practice {childId, goalSelectionIds[], idempotenc`; `POST /api/goal-selections {recommendationId}`; `POST /api/goal-selections/{id}/restore`; `POST /api/reports {childId, from, to, sessionIds[], idempotencyKey}`; `서버 렌더: HTML 템플릿`
- 테이블: core_child, core_goal_recommendation, core_practice_goal_selection, core_recommendation_evidence, core_report, core_report_activity, core_result, core_support_item, realtime_activity_session, realtime_practice_attempt, realtime_transcript

## 의존
- guardian-app → activity-session의 세션 API(시작 · 조회 · 끝) · ai-goals-results의 결과 조회.
- voice-device ↔ activity-session(WSS · 시작 push · 종료 명령).
- ai-goals-results ← activity-session의 전사 · 세션 종료 이벤트(Outbox).
- 셋 다 Core의 인증 · 동의 게이트에 기댄다.
