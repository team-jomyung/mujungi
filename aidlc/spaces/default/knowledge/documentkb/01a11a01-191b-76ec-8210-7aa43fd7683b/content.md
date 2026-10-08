# 개발 전환 점검표 1008 (글 판)

화면 설계 v1.5j · 개발 명세 · ADR_2S · ERD v0.1 · WBS v0.5 2차 대조. 화면 설계가 기준이라 제안은 ADR · ERD · WBS를 화면에 맞추는 쪽이다. 그림 판은 같은 폴더의 PDF.

## 1. 오늘 결정
| 번호 | 결정 | 화면에 미친 것 |
|---|---|---|
| D-05 | 연습 방식 = 2안(상황 반응 조절형) + 제브 판정 | 12 머리 n/N 목표 · 27은 고른 목표만(판정 글 없음). 단계 값 · 판정 기준은 AI 입출력 계약(WBS 1.7.3) |
| D-21 | 05에서 보호자 폰 인터넷 줄 삭제 | 05 두 줄 + 판정 카드. 폰 인터넷 문제는 공통 오류 토스트 |
| D-22 | 28 리포트 범위 단추 전부 정의 | 빠른 선택 3개 · 달력 두 번 누르기 · 활동 줄 체크 · 만드는 중 취소 · 실패 두 단추. 날 토글 삭제 |
| D-23 | 미정의 단추 마무리 | 30 나중에 · 18 체크/별/빈 상태 · SET-01 · 05a ← · 17 탭 · 03 검증 · 27 담김 취소 · 08 네 칸 선택 사항 · 26 빈 날 |
| D-24 | 한 계정에 아이 하나, 기기 하나 | 03 아이 정보 한 화면 · 03-1 · P4 삭제 · 다른 아이 잠금 삭제 · 01/02 분기는 아이 등록 여부 · 카드 25장 |

## 2. 네 문서가 서로 다른 곳
| 항목 | 화면 설계 · 명세 | ADR_2S | ERD v0.1 | WBS v0.5 | 제안 |
|---|---|---|---|---|---|
| 1. 기기 등록 방식 | 05a: 아이 기기 웹이 받은 8자리 코드를 보호자가 입력해 등록. D0: 서버가 코드 발급(제안 D-04). 기기 토큰으로 WSS 접속 | ADR-003 · 002 · 008 §7 · §12 · §16: 데모는 허용 기기 ID를 운영자가 사전 시드. Pairing Code · QR claim · 보호자 기반 등록은 검토 후보일 뿐 미채택, 운영자 일회용 발급 코드는 폐기한 이전 선택. 기기 WSS는 허용 Origin + 고정 ID로 접속(auth.md), 추가 토큰 · 만료는 TBD | Device 테이블 없음 | 3.1.3 고정 데모 ID · 기기 권한 연결 (A, 10/15) | 화면대로 코드 등록 유지. ADR-003 · 008의 'Pairing Code 미채택'을 채택으로 고치고, Device 테이블(아이 1:1 · 코드 · 상태 · 토큰)을 ERD에 추가, WBS 3.1.3을 '기기 코드 등록 · 05a'로. 데모에서도 시드 대신 서버 발급 코드 + 05a 입력 |
| 2. 원음 저장 | D-06 원음 항상 저장. 02 동의 4항목 중 '원음 보관' 필수 | ADR-002 · 004 · 006 · protocol · sequences · first-bolt 전부: 실시간 원음은 임시 메모리에서만 쓰고 폐기. DB · Outbox · 로그 · 디스크 저장 금지. 첫 볼트 완료 조건에 '원음 비저장' | realtime_audio_recording '선택 원음 보관, 현 v1에 존재' | 1.7.5 보존 · 원음 · 철회 미결 정리 (A, 10/8~13) | 화면대로 원음 저장(동의 4항목 유지). ADR 여섯 곳의 '원음 미저장' 문장을 '동의 받은 원음은 저장'으로 고치고 저장 위치 · 보존(계정 삭제까지)을 ADR-006에 추가. ERD realtime_audio_recording 유지. WBS 1.7.5에서 확정 |
| 3. 세션 상태 이름 | REQUESTED · PREPARING · DEVICE_ACK · WAITING_CHILD · ACTIVE · CLOSING · ENDED · ENDED_UNCONFIRMED · ABORTED · DECLINED · FAILED | session-state · ADR-005: PREPARING · ACTIVE · PAUSED · ENDING · PARTIAL · RESULT_PENDING · RESULT · INSUFFICIENT · RESULT_FAILED · DECLINED · CANCELLED · ERROR. 준비 단계 셋은 PREPARING 하나에 흡수. '미확인'은 DB Enum으로 넣지 않고 별도 축으로 표시. ADR-005 §2.2 'ENDING은 보호자 화면에 노출하지 않는다' | status 'PREPARING/ACTIVE/ENDING/ENDED 등'. 종료 시각 넷(termination_requested · output_stopped · transport_closed · provider_closed)은 있음 | 3.3.3.4 종료 사유 · 결과 · 미실시 분리 (E) | 화면 11개 이름 유지. ADR-005 · session-state 상태표를 화면 기준으로 바꾸고 ENDED_UNCONFIRMED를 Enum에 넣는다(ADR-007 §8.2 문장 수정). ERD status 값 목록을 화면대로. 17의 '마무리 중 · 정리 중' 노출도 화면대로 |
| 4. 종료 사유 값 | GUARDIAN_STOP · CHILD_STOP · SILENCE · DISCONNECT · CONSENT_WITHDRAWN · UNCONFIRMED · DECLINED · FAILED | session-state: normal · guardian_stop · child_stop · no_response · paused_timeout · technical_error · server_restart(후보, 최종은 데이터 모델에서). 동의 철회는 자동 분류 안 함(D-08 TBD) | end_reason VARCHAR(50), 값 목록 없음 | 3.3.3.4 | 화면 8개 값을 ERD end_reason enum으로 확정. ADR-005 §2.1 예시를 교체. 17 사유 줄 매핑은 지금 명세대로 |
| 5. 보호자가 끝낼 때 마무리 인사 | P1 네 → 17 '마무리 중: 기기가 마무리 인사하고 멈추는 1~3초' | sequences §3 · first-bolt: 보호자 STOP에는 마지막 인사 예외를 적용하지 않음. 인사는 아동 종료 표현 때만, GPT 생성이 아닌 고정 승인 콘텐츠(PLAY_APPROVED_CONTENT) 1회 | core_approved_content_version(closing) · session.closing_content_version_id | 4.2.4 실제 기기 · 음성 종료 시험: 일반 출력 차단 및 승인된 마지막 인사 | 화면대로 보호자가 끝낼 때도 승인 마무리 인사 1회(1~3초). sequences §3 · first-bolt 9항 수정. 인사는 ADR대로 고정 승인 콘텐츠로(화면과 충돌 없음) |
| 6. 아이 무응답 | 참여 대기 무응답 N초 → P3(DECLINED, 기록 없음). 진행 중 공백 4분 경고 · 6분 SILENCE 자동 종료(D-07) | session-state · sequences: 참여 대기 무응답 60초 → CANCELLED, 명시 거부 → DECLINED(대안 제안 1회 뒤 재거부). 진행 중은 실제 재생 완료 → 10초 → 재안내 1회, 그 뒤 TBD(Q-TIME-01). '무응답만으로 종료를 확정하지 않는다'. 4분 · 6분 없음 | end_reason 값 없음 | 3.3.2.4 무응답 재안내 · 즉시 ENDING (B, 10/22) | 화면대로: 참여 대기 무응답 → P3(DECLINED, 기록 없음), 진행 중 4분 경고 · 6분 SILENCE. ADR-005 §5.1 · §5.2 TBD를 이 값으로 채움. 참여 대기 시간은 화면에 N초로 비어 있어 20초로 적는다(준하 제안) |
| 7. 기기 상태 단계와 끊김 시간 | 04 · 05 기기 상태 5단계(안정 · 불안정 · 끊김 · 응답 없음 · 없음), 지연 1초 · 5분 안 끊김 2회 → 불안정, 40초 무응답 → 끊김 · P2 재연결 대기, 시작 때 기기 응답 없음 20초 → FAILED | ADR-005 §2.1 · §6: VoiceClient는 ONLINE · OFFLINE 두 값, 홈은 연결됨 · 연결 안 됨. heartbeat 5초, 15초 무메시지면 OFFLINE. WSS 끊김 5초 + Grace 6~7초 → ERROR, '30초 재개 유예는 쓰지 않는다', 재접속 대기 미채택. 20초 준비 Timeout · 30초 멈춤 확인은 TBD | voice_client_id 한 컬럼. rtt · 끊김 횟수 없음 | 3.3.1.3 연결 끊김 · 복구 · 기술 종료 (B), 1.7.6 종료 · 복구 시간 계약 (B) | 화면대로 기기 5단계 · 40초 유지. protocol · ADR-005 §6의 heartbeat · OFFLINE 값을 화면 규칙(지연 1초 · 5분 안 2회 불안정 · 40초 끊김)으로 바꾸고 Device.status enum을 ERD에. 20초 · 30초는 1.7.6 계약에 |
| 8. 쉬기 · 재개 | 없음(D-07: 쉬기 · 건너뛰기 · 재개 없음) | session-state: PAUSED 상태 · Pause 10분 · paused_timeout 사유. first-bolt에서는 제외 | 없음 | 3.3.2.3 호출어 · 쉬기 · 재개 · 종료, 3.3.4 쉬기 · 재개 상태 전환 시험 | 화면대로 쉬기 · 재개 없음. ADR-003 · 005 · session-state의 PAUSED를 '미구현'으로, WBS 3.3.2.3 · 3.3.4 문구 수정 |
| 9. 보호자 앱 실시간 수신 | 명세: 12는 SSE 스트림(transcript.delta 등 7개 이벤트), 실시간 말풍선 | protocol · first-bolt: /parent는 REST 1~2초 Polling. 상태 · 종료 응답은 전사 원문을 포함하지 않음. '진행 화면에 실시간 전사를 추가하지 않는다' | realtime_transcript에 sequence_no · assembly_status | 1.7.1 REST · TS 클라이언트 계약 (A) | 화면대로 12 실시간 말풍선 유지. 전송 수단만 ADR이 허용하는 1~2초 폴링으로 바꾸고(명세 API 수정, 준하), protocol의 '전사 원문 미포함'을 '세션 조회 응답에 전사 포함'으로, first-bolt 4.1 문장 수정 |
| 10. 푸시 알림 | D-17 알림 진입: 푸시를 누르면 04가 재조회 → 12 · 17. 푸시 토큰 테이블 빈칸 | 모바일 푸시 없음. ADR의 'push'는 서버 → 기기 시작 명령 | core_push_token 없음 | 행 없음 | 화면대로 알림 진입 유지. ADR-004 §2.1의 Push 대안(D)을 채택으로 고치고 core_push_token 추가, WBS에 '푸시 토큰 · 알림 진입' 행 추가(A). 7일 안에 못 넣으면 11/5 뒤 백로그로 두되 화면은 그대로 |
| 11. 아이 선택 · 목적 입력 | D-24 아이 하나. 08은 네 칸(알고 있는 내용 · 관련 상황 · 정보 출처 · 궁금한 내용), 목적 칸 없음 | ADR-003 /parent: 아동 선택, 목적 · 상황 입력 | core_activity_context.purpose VARCHAR(50) | 3.2.1.2 아동 등록 · 수정 · 선택, 3.2.2.1 목적 · 배경 입력 | 화면대로 아이 선택 · 목적 칸 없음. ADR-003 /parent 책임에서 '아동 선택' 삭제, ERD purpose 컬럼 삭제, WBS 3.2.1.2 · 3.2.2.1 문구 수정 |
| 12. 08 네 칸의 쓰임 | 자유대화 시작 전 네 칸 입력 → 세션 문맥 | result-pipeline: 보호자 원문 · 판정 기준은 대화 모델에 전달하지 않음(관심사 · 허용 재료만). 첫 볼트는 '시험용 목적 · 빈 배경' | core_activity_context → core_structured_experience(activity_context_id) 입력 | 3.4.2.3 경험 구조화 · 실제 근거 연결 (D) | 충돌 아님. 명세에 '네 칸은 결과 구조화(경험 · 목표 후보)의 입력이고 대화 모델엔 안 들어감' 한 줄(준하) |
| 13. 동의 저장 · 철회 · 계정 삭제 | 01 → 02 동의 → 03 아이 등록 순서(아이가 없을 때 동의). 철회 화면 없음, 거두려면 계정 삭제(DELETE /api/me → 삭제 요청) | ADR-008 §5 · auth.md: 매 요청 현재 동의 재확인, 철회를 활동 중 독립 이벤트로 크게 다룸(G-03). 계정 삭제 API 없음, ADR-008 §3.2 '탈퇴 · 재가입 TBD'. data-model Consent는 withdrawn_at | core_consent.child_id FK → core_child(아이가 먼저 있어야 함), effective_to(withdrawn_at 아님). 세션 Snapshot Boolean 2개(수집 · 전사 저장)뿐. 삭제 접수는 core_data_deletion_request만 | 3.2.1.3 필수 · 선택 동의, 1.7.5 보존 · 원음 · 철회 미결 정리 | 화면대로 철회 없음 · 계정 삭제(P7)만. ADR-008 §3.2 TBD에 DELETE /api/me를 넣고 G-03을 '계정 삭제 중 세션'으로 좁힘. ERD core_consent.child_id는 nullable(아이 등록 전 동의), Snapshot Boolean은 4항목으로 |
| 14. 종료 표현 입력 · 인식 | SET-01: '이제 그만' 같은 문장 전체 입력, 2~20자, 추가 5개 | ADR-004 · 005 · session-state: 음성 제어는 호출어 + 명령어, 키워드 없는 '그만할래'는 명령 아님. 인식은 Realtime이 GPT-Live 전사로 판정(기기는 의미 이벤트 안 만듦). 추가 5개 · 설정 UI는 첫 볼트 뒤(FR-A09). 기준은 동료 명세 L-D03 | core_stop_expression.expression_suffix VARCHAR(10) '무중아 뒤 추가 종료 표현' | 3.3.2.3 호출어 · 종료 | 화면대로 문장 전체 입력 · 2~20자 · 추가 5개. ERD expression_suffix를 expression_text VARCHAR(20)로. 인식은 ADR대로 Realtime이 전사로 판정(화면과 충돌 없음). SET-01을 WBS 3.3.2.3에 명시 |
| 15. 연습 세션의 목표 수 | 18에서 여러 개 골라 시작, 12 머리에 n/N | result-pipeline §4 · session-state Cue: 선택 · 활동 모두 '선택한 행동 버전' 단수 | realtime_activity_session.practice_goal_selection_id '현 v1은 단일 목표 Snapshot' | 3.2.3.1 목표 목록 · 최종 선택 | 화면대로 여러 개. ERD에 세션-목표 연결 테이블(realtime_session_goal) 추가, result-pipeline §4 '단수' 문장 수정. 7일 안에 벅차면 1개로 줄이는 건 사용자 결정 |
| 16. 결과의 소유권과 내용 | 27 = 대화 기록 · 생활 팁(core_result TIPS) · 목표 후보(core_goal_recommendation) · 담기(core_practice_goal_selection). 판정 글 없음 | result-pipeline · protocol: 목표 정의 · 버전 · 선택의 소유는 Realtime practice, Core는 산출물 · 참조만. 후보 필드 recommendationId · behaviorDefinitionId/Version · displayName · recommendationReason · evidenceRefs. 결과엔 요약 · 판정값 포함. 생활 팁은 없음 | core_goal_recommendation · core_practice_goal_selection · core_scenario · core_activity_context · core_support_item(생활 팁)이 전부 Core. data-model.md는 ActivityContext · Scenario를 Realtime 소유로, 목표 정의 · 버전 테이블을 따로 둠(ERD엔 behavior_key 문자열만). ResultJob 모델도 둘이 다름 | 3.4.2.5 목표 등록 (C), 3.4.5 일상 지원 매칭 (D), 3.4.4.4 AI 요약 | 화면엔 영향 없음. ERD(Core)와 ADR(Realtime) 중 하나로 정하기. 생활 팁(core_support_item)과 판정값 비노출은 화면대로 |
| 17. 리포트 내용 | D-13: AI 없이 아이 정보 + 날짜별 대화 원문 PDF. 28 → 만드는 중(몇 초) → 30 | result-pipeline FR-F12: 리포트 설명을 AI가 저장 구조화 데이터 · 판정값으로 생성(불일치 시 고정 템플릿). ADR-002 Backend 표: Core가 '결과 · 리포트와 사후 텍스트 AI 호출' 소유. PDF · 취소는 후속 | core_report_activity.result_id (결과 버전을 리포트에 포함) | 3.4.4.4 AI 요약 · 추천 · 결과 문장, 3.5.1 자유대화 결과 · 리포트 → 실제 AI | 화면대로 AI 없이 원문 PDF. WBS 3.4.4.4 · 3.5.1 수정, ERD core_report에 period_from · period_to 추가 · result_id는 선택, result-pipeline FR-F12 문장 수정, PDF 생성 방법 행 추가(E) |
| 18. 출력 게이트 | 없음. TS-N01 왕복 p95 2.5초 · 중계 증가분 0.5초 · 전사 1.5초 · 끼어들기 0.5초 | ADR-004 · protocol: AI 음성을 구간 분할 → 전사 대응 → 검사 → 승인된 음성만 기기로. 수치 목표 없음, 지연은 미측정 POC. 끼어들기는 기기가 로컬로 멈추고 playbackMark 보고(계약 TBD) | core_approved_content(_version) 승인 문구 · 오디오 | 3.3.1.4 출력 게이트 (B, 10/21), 3.4.1 가드레일 검사 (D), 4.4.2 성능 측정(11/2) | 서버 내부 일이라 ADR대로 둔다. 명세에 '12 말풍선은 승인된 AI 말만' 한 줄(준하). 지연 수치는 11/2 측정 뒤 확정 |
| 19. 플랫폼 | D-10: Next.js PWA 제안 | ADR-002 · 003: Next.js 한 앱, /parent와 /device 두 접점. PWA 아님 |  | 2.2.1 Spring · React 골격 | D-10은 '제안'이었으니 ADR대로 Next.js 웹으로 확정(준하). 10번 푸시를 유지하면 웹 푸시로 |
| 20. 아이 수 모델 | D-24: 계정 1 = 아이 1. 아이 선택 · 추가 · 삭제 없음. 형제는 계정을 따로, 아이 교체는 계정 삭제 뒤 새로 | data-model.md: GuardianChild 관계 엔터티(N:M까지 열림). ADR-008 §4 · auth.md: GuardianChild 검사, 아동 조회 · 수정 · 삭제 API, {childId} 경로 변수 | core_child 1:N(guardian_id FK만, UNIQUE 없음), deleted_at으로 아이 삭제 전제. GuardianChild 테이블은 없음(data-model과 다름) | 3.2.1.2 아동 선택, 4.1.1 시드 '보호자 2명 · 아동 3명' | 화면대로 1:1. data-model GuardianChild 삭제, ERD core_child.guardian_id UNIQUE, ADR-008 §4 아동 삭제 API 삭제, WBS 3.2.1.2 수정, 시드 보호자 2 · 아동 2 |
| 21. 목표 후보가 목록에 들어가는 방식 | 27에서 보호자가 「담기」를 눌러야 18 목록에 쌓임 | decision-log DL-GOAL-01 · 02: 4요소를 갖춘 후보는 사람 사전 승인 없이 아동별 목록에 자동 추가, 검토 통과 후보만 Realtime에 등록 | core_goal_recommendation(status) → core_practice_goal_selection(selection_type · status) | 3.4.2.4 목표 4요소 생성 (D), 3.4.2.5 목표 등록 (C), 3.2.3.1 목표 선택 (C) | 충돌 아님. 후보 생성 · 등록은 자동(ADR), 연습할 것 고르기는 보호자 「담기」(화면). 명세 27 · 18에 한 줄(준하) |

## 3. 어디에도 없는 것
| 무엇 | 지금 상태 | 담당 |
|---|---|---|
| 동료 GPT-Live · AI 명세(L-D02~L-D05) | ADR 20문서가 호출어 · 판정 기준 · 프롬프트 · 지시문을 전부 이 명세로 넘기는데, 그 파일이 어디에도 없다. 제브 정의도 여기 있을 것 | D |
| REST API 계약(OpenAPI) | WBS 1.7.1에 10/8~13 계획만 있음. 명세의 화면별 API 문자열과 protocol.md REST 표를 입력으로 쓰면 하루 거리 | A |
| 기기 WSS 계약의 물리값 | protocol.md에 메시지 이름은 있고(시작 push · STOP_OUTPUT · PLAY_APPROVED_CONTENT · HEARTBEAT · playbackMark 등) 필드 · 단위 · 오디오 포맷 · Envelope이 TBD. WBS 1.7.2 | B |
| 연습 2안 · 제브 판정의 단계 값 | ERD에 attempt_type · response_type · evaluation.label 자리만 있음. ADR이 정한 흐름: 상황 생성(재생성 1회) → 첫 반응(이 단계와 새 상황 단계에는 Cue 금지) → 도움 동의 → Cue(GPT-Live 실시간 생성, 5초 예산, 정답 직접 제시 금지) → 도움 후 반응 1회 → 새 상황 → 판정. 판정 라벨 functional · partial · none · uncertain, 무반응은 NOT_PERFORMED(NONE과 구분), 판정 LLM 5초 + 재시도 1회, 실패면 UNCERTAIN. 기준 자체는 L-D05 소유. current_practice_stage 값 목록은 없음 | C · D |
| 약관 본문 · 개인정보 처리방침 1.0 | 02-1 항목의 실제 문장. policy_version을 쓰려면 본문이 있어야 함 | A |
| 디자인 자산 | 아이콘 · 무중 캐릭터 그림 · 로고 · 폰트 파일 · 빈 상태 그림. 토큰 14개와 컴포넌트 표만 있음 | 팀장 |
| 인프라 담당 | WBS 결정 5 '클라우드 담당 미확정'. EC2 · Docker · Nginx(WSS) · HTTPS가 담당 없이 10/12 시작 | 팀장 |
| PDF 생성 방법 | 서버 라이브러리인지 브라우저 인쇄인지. WBS 3.2.4.3 · 4.2.7 '지원 브라우저 1종' | E |
| 데모 시나리오 · 시드 | WBS 4.1.1(10/20~27)에 있으나 11/5 시연 경로는 없음 | C |
| 03 아이 정보 · 05 기기 화면의 WBS 행 | 3.2.1.2 · 3.2.1.4가 옛 구조(선택 · 고정 기기) 문구 | A |
| 계정 삭제(탈퇴) API와 흐름 | ADR-008 §3.2 '탈퇴 · 재가입 TBD'. data-model엔 삭제 요청 없음, ERD에만 core_data_deletion_request. 화면 P7은 있음 | A |
| HTTPS 방식 · 도메인 · 저장소 공개 여부 | ADR-006 §3.2 HTTPS 방식 미정, ADR-001 Public 저장소 여부 미결. 마이크 권한은 HTTPS가 있어야 함 | 팀장 |

## 4. WBS 문구 수정 제안
| 행 | 현재 | 바꿀 문구 | 근거 |
|---|---|---|---|
| 3.2.1.2 | 아동 등록 · 수정 · 선택 | 아이 정보 한 화면: 처음 등록 · 보기 · 수정 (선택 없음) | D-24 |
| 3.2.1.3 | 필수 · 선택 동의 · 고지 · 안내 | 필수 동의 3항목(원음 결정 뒤) · 약관 시트 · P5 나가기 (선택 · 철회 없음) | D-02 · 충돌 2 |
| 3.2.1.4 | 홈 · 아동 · 고정 기기 상태 | 홈 구역 셋(아이 · 대화 기기 · 지금 활동) · 탭 잠금은 기기 안정 아님 하나 | D-12 · D-18 · D-21 |
| 3.2.2.1 | 목적 · 배경 입력 · 확인 · 수정 | 자유대화 네 칸(모두 선택 사항, 결과 구조화용) · 시작 | 08 · 충돌 12 |
| 3.2.3.1 | 목표 목록 · 첫 이용 · 최종 선택 | 목표 목록(즐겨찾기 · 삭제 ×) · 하나 골라 시작 · 빈 상태 → 자유대화 | D-14 · 충돌 15 |
| 3.2.3.3 | 연습 단계 · 쉬기 · 종료 표시 | 연습 진행 표시 · 끝내기 (쉬기 없음) | D-07 · D-15 |
| 3.2.3.4 | 연습 · 부분 결과 · 일상 지원 화면 (시험 4단계) | 연습 끝 17 공통 · 27 연습 기록(고른 목표만) · 생활 팁 | D-05 · D-15 |
| 3.2.4.1 | 기록 목록 · 기간 · 유형 필터 | 기록 달력 · 그날 목록 · ‹ › 달 넘기기 (필터 없음) | 26 |
| 3.2.4.2 | 기록 상세 · 다음 활동 연결 | 기록 상세 세 칸(대화 · 팁 · 목표 후보 담기) | 27 |
| 3.2.4.3 | 상담 리포트 · 확인 · PDF 저장 (시험 결과 리포트) | 리포트 범위(28) → 만드는 중 → 저장 전 확인(30) → PDF(원문만) | D-13 · D-22 |
| 3.3.2.3 | 호출어 · 쉬기 · 재개 · 종료 | 호출어 + 종료 표현 (쉬기 · 재개 없음) | D-07 |
| 3.4.4.4 | AI 요약 · 추천 · 결과 문장 생성 | 연습 목표 후보 · 근거 문장 · 생활 팁 매칭 (요약 없음) | D-13 · 27 |
| 3.5.1 | 자유대화 결과 · 리포트 → 실제 AI | 자유대화 결과(27) → 실제 AI. 리포트는 AI 없음 | D-13 |
| 4.1.1 | 시드: 보호자 2명 · 아동 3명 | 보호자 2명 · 아동 2명 · 기기 2대(시드) | D-24 |

## 5. 담당별로 볼 자리
| 담당 | 화면 설계에서 | 개발 명세 · 점검표에서 |
|---|---|---|
| A 계정 | 01 · 02 · 02-1 · 03 · 04 · SET0 · P5 · P7 · 05 · 05a | 명세 2절 그 화면들 · 11절 D-02 · D-24 · 점검표 충돌 1 · 2 · 10 · 11 · 13 · 19 |
| B 음성 | 12 · 17 · P1 · P2 · P3 · D0 · T19 · SET1 | 명세 4절 실시간 이벤트 · 5절 상태 기계 · 점검표 충돌 3 ~ 9 · 14 · 18 |
| C 연습 | 18 · 12(연습) · 27(연습 기록) | 명세 11절 D-05 · 점검표 충돌 15 · 16 · 빈칸 4 |
| D AI | 27 생활 팁 · 목표 후보 · 08 네 칸 | 명세 3절 테이블 · 점검표 충돌 12 · 16 · 17 · 빈칸 1 · 4 |
| E 결과 | 26 · 27 · 28 · 30 · PDF | 명세 2절 그 화면들 · 8절 TS · 점검표 충돌 16 · 17 · 빈칸 8 |

## 6. 10/9 스크럼에서 확인할 일곱 묶음
1. 기기 등록은 화면대로 서버 발급 코드 + 05a 입력. ADR-003 · 008을 고치고 Device 테이블을 ERD에 넣는다.
2. 원음은 화면대로 저장(동의 4항목). ADR의 '미저장' 문장을 고치고 저장 위치 · 보존 기간을 ADR-006에 적는다.
3. 세션 상태 11개 · 종료 사유 8개 · 시간값(20초 · 30초 · 40초 · 4분 · 6분)은 화면 기준으로 ADR-005 · session-state · ERD를 맞춘다.
4. 보호자가 끝낼 때도 마무리 인사 1회(화면대로). sequences · first-bolt 수정.
5. 12 실시간 말풍선은 유지하되 전송은 1~2초 폴링으로. 푸시 알림(ADR 미채택)을 채택할지, 11/5 뒤 백로그로 둘지.
6. 연습 세션 목표 여러 개(화면대로, ERD 연결 테이블 추가)로 갈지, 7일 일정 때문에 1개로 줄일지.
7. 결과 · 목표 소유는 Core(ERD)인지 Realtime(ADR)인지 하나로. 리포트는 화면대로 원문만.

## 7. AI-DLC에 넣을 것
| 할 일 | 내용 |
|---|---|
| 설치 (PowerShell, 관리자 아님) | irm https://github.com/awslabs/aidlc-workflows/releases/latest/download/install.ps1 \| iex   →   cd <레포>   →   aidlc config --harness claude   →   aidlc doctor |
| 문서 넣기 | 레포 안 aidlc/spaces/default/knowledge/documents/ 에 Markdown · PDF · Word · 텍스트로 넣고 Claude Code에서 /aidlc knowledge onboard aidlc/spaces/default/knowledge/documents 실행. HTML은 공식 목록에 없으니 Markdown으로 바꿔 넣는다 |
| 시작 | /aidlc Read ./docs/project-description.md and build what it describes 처럼 설명 문서를 가리켜 시작. 200,000자 넘는 글은 파일로 |
| 스코프 | 스코프 확인 질문에서 Change scope → classic (초기화 + Inception 전부 + Construction의 Build and Test까지 18단계, Ideation · CI · 운영 생략). 코드가 없으니 2.1 Reverse Engineering은 자동 생략 |
| 진행 방식 | 첫 단계에서 모드를 묻는다. Edit File을 고르면 질문 파일의 [Answer]: 줄을 우리가 문서를 보고 채우고 'done'. 단계마다 산출물이 생기고 Approve / Request Changes 게이트가 선다 |
| 기존 문서의 지위 | AI-DLC는 기존 요구사항 문서를 그대로 들여오지 않는다. 신뢰하지 않는 입력 자료로 읽고 질문 → 답 → 생성 → 승인을 거쳐 자기 산출물을 다시 만든다. 그래서 '요구사항 · 기술 · 기능 명세서를 뽑아 넣는다'는 반만 맞다. 넣는 건 참고 문서이고, 명세서는 AI-DLC가 만든 것을 우리가 답과 승인으로 화면 설계에 맞춘다 |

| AI-DLC 단계 → 산출물 | 우리 쪽 재료 | 어떻게 |
|---|---|---|
| 2.3 Requirements Analysis → requirements.md (FR · NFR 번호) | 요구사항 정의서 v1.x + 화면 설계 규칙 상자 + 개발 명세 11절 결정 기록 | 질문 파일에 화면 설계 기준으로 답. FR 번호가 다시 매겨지니 추적표(WBS 1.7.4)는 이 번호로 |
| 2.4 User Stories → stories.md · personas.md | 화면 설계 차선 6줄과 카드별 분기표 | 보호자 · 아이 두 페르소나. 스토리는 차선(처음 한 번 · 기기 · 자유대화 · 연습 · 기록) 단위 |
| 2.5 Refined Mockups → mockups.md · interaction-spec.md · accessibility-checklist.md | 화면 설계 v1.5j 25장 (가장 중요한 입력) | 그림은 Markdown으로 못 넣으니 화면별 요소 · 상태 · 분기 표를 Markdown으로 뽑아 넣는다(개발 명세 2절이 그대로 재료) |
| 2.6 Domain Design → components.md · decisions.md(ADR) | ADR_2S 20편 + ERD v0.1 + 결정 기록 D-01~24 | ADR은 이미 있으니 decisions.md에 요약 · 링크로. 점검표 2절 충돌이 여기서 정리돼야 함 |
| 2.7 Units Generation · 2.9 Delivery Planning → unit-of-work.md · bolt-plan.md | WBS v0.5의 유닛 · 볼트 (같은 용어) | WBS 3.x 유닛 다섯(공통 활동 · 보호자 · 음성 · AI · 실제 연결)을 Unit으로, 볼트는 그대로 |
| 2.8 Contract Design → contract-summary.md | 개발 명세 4절 API · 실시간 이벤트 · 내부 이벤트 + protocol.md | WBS 1.7.1 · 1.7.2 계약 작업이 이 단계 |
| 3.1 Functional Design → functional-spec.md · entities.md · rules.md (유닛별) | 개발 명세 2절 화면별 명세(검증 · 오류 · 요소와 동작) + ERD 테이블 | '기능 명세서'가 여기 해당. 화면 설계 분기표가 rules.md의 재료 |
| 3.2 NFR Requirements · 3.4 Infrastructure Design | 개발 명세 8절 TS-N01~08 + ADR-006 배포 | '기술 명세서'가 여기 해당. 지연 수치는 11/2 측정 뒤 확정으로 적는다 |
