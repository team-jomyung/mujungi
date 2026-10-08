# 무중 개발 명세 1008 — 결정 기록 · 역할 · 플랫폼 · ERD 빈칸

## 결정 기록 D-01~D-24
| 번호 | 날짜 | 결정 | 출처 · 경위 | 상태 | 영향 |
|---|---|---|---|---|---|
| D-01 | 10/6 → 10/7 | 로그인은 구글 하나 | TA · 챗지피티 · 준하 · 팀 v1.4 모두 같음 | 확정 | FR-A01 · 화면 01 |
| D-02 | 10/7 → 10/8 | 동의 4항목 전부 필수 + 약관 시트. 변경 · 철회 · 기록 삭제 · 아이에게 할 말 없음 | 준하 전부 필수(10/7) → 팀 v1.4 항목별 선택 + 관리 → 준하 10/8 다시 전부 필수 · 관리 삭제. FR-A03(선택 분리) · A05 · A08(철회)와 어긋남 | 준하 결정(팀 확인 필요) | 02 · 02-1 · SET0 · 28 |
| D-03 | 10/6 → 10/8 | 생활 녹음 업로드는 MVP 제외 | 10/6 팀 Won't → 10/7 준하 포함 → v1.4 Won't | 확정(Won't · 백로그) | FR-B04 · 08 |
| D-04 | 10/7 | 기기는 집 단위, 아이 기기 웹의 코드로 등록, 기기 토큰 | 준하 제안 · v1.4 유지. 코드 발급 주체는 v1.5 제안(서버 발급 · 기기 저장) | 화면 확정 · 코드 정체 미결 | 05 · 05a · D0 · Device 테이블 |
| D-05 | 10/8 → 10/8 밤 | 연습 방식: 2안(상황 반응 조절형) + 제브 판정으로 결정. ERD 기준 흐름: 상황(core_scenario · realtime_scenario_exposure) → 첫 반응(FIRST) → 필요하면 Cue 1회(realtime_cue_delivery) → 반응(AFTER_CUE) → 판정(realtime_evaluation: functional · partial · none · uncertain) → 새 상황(NEW_SCENARIO). 27에는 판정 글을 보여 주지 않음(고른 목표만). 단계 값(current_practice_stage) · 판정 기준 · 프롬프트는 AI 입출력 계약(WBS 1.7.3)에서 | 팀 v1.4 2안 → 10/8 밤 사용자 결정 | 결정(세부 계약 대기) | 12 · 18 · 27 · ERD practice_* · evaluation · WBS 1.7.3 · 3.4 |
| D-06 | 10/8 | 원음은 항상 저장(원음 보관이 필수 동의) | 팀 v1.4 선택 → 준하 10/8 필수 | 준하 결정 | realtime_audio_recording |
| D-07 | 10/6 | 공백 4분 경고 + 2분 뒤 자동 종료, 쉬기 · 건너뛰기 · 재개 없음 | 10/6 팀(v0.8.1) · v1.5 화면 반영 | 확정 | 12 · 17 |
| D-08 | 10/8 | 끝내기 뒤 바로 17(마무리 중). 30초 안에 기기 확인 없으면 서버가 ENDED_UNCONFIRMED로 자동 처리, 보호자 단추 없음 | 준하 v1.5 제안 → 10/8 밤 단순화 | 준하 결정 | 12 · 17 · 세션 상태 |
| D-09 | 10/8 | 아이 삭제(P4)는 그 아이의 기록 · 리포트 · 담은 목표도 삭제 요청 | 준하 v1.5 제안 → 10/8 밤 | 폐기(D-24: P4 없음 · 계정 삭제 P7로) | P7 · core_data_deletion_request |
| D-10 | 10/8 | 보호자 앱 플랫폼: Next.js PWA로 시작 | 준하 제안(ADR) | 제안 | 푸시 · PDF · 실시간 |
| D-11 | 10/8 | 03은 카드 안 수정 · 맨 아래 빈 카드 추가 · 카드 삭제 | 팀 v1.4 → 10/8 밤 사용자 | 폐기(D-24: 아이 하나 · 03은 아이 정보 한 화면) | 03 |
| D-12 | 10/8 | 탭 잠금 조건: 기기 안정 아님 (다른 아이 사용 중은 10/8 밤 D-24로 삭제 · 팀 v1.4의 필수 동의 없음 · 종료 미확인은 준하가 뒤에 뺌) | 팀 v1.4 → 준하 | 준하 결정 | 04 |
| D-13 | 10/7 → 10/8 | 리포트 범위는 달력 드래그 · 빠른 선택 · 활동 체크. 내용은 AI 없이 아이 정보 + 날짜별 대화 원문 묶음 PDF, 29 확인 화면 없음 | 준하 · v1.4 유지 · 10/8 내용 단순화 | 준하 결정 | 28 · 30 · PDF 템플릿 · 리포트 10항목 폐기 |
| D-14 | 10/7 → 10/8 | 기록은 달력, 상세는 세 칸(대화 · 팁 · 목표 후보 담기). 18 목표 삭제는 별 옆 「×」(길게 누르기 없음) | 준하 · v1.4 유지 · 10/8 삭제 단추 | 확정 | 26 · 27 · 18 |
| D-15 | 10/7 → 10/8 | 12 하나로 자유대화 · 연습 진행을, 17 하나로 끝 화면을 다 보여 준다(머리에 종류 표시). 실시간 말풍선(전사 정확도 낮으면 상태 줄만) | 준하 · 10/8 12 · 21 · 17 통합 | 준하 결정 | 12 · 리서치 5절 |
| D-20 | 10/8 | 홈은 구역 셋(아이 · 대화 기기 · 지금 활동). 기기 5상태 · 활동 5상태는 구역별 상태로 적고 그림은 대표 5장 | 준하 (상태가 화면 변형 7개로 쌓이자 구역별로 정리) | 준하 결정 | 04 |
| D-18 | 10/8 | 05 두 줄: 기기 등록 · 기기 네트워크 현황(안정 · 불안정 · 끊김 · 응답 없음). 두 줄은 서로 독립, 판정 카드가 합산. 안정이 아니면 시작 불가(탭 잠김). 내 기기 인터넷 줄은 10/8 밤 삭제(D-21). 다른 아이 잠금은 D-24로 삭제(아이 하나). 기기 해제 기능 없음 | 준하 | 준하 결정 | 04 · 05 · Device 상태 판정 규칙 |
| D-19 | 10/8 | 03 첫 등록은 같은 화면(10/8 밤 D-24로 아이 정보 한 화면), SET-01 입력 칸, 05a 8칸 입력, 27 연습 기록은 고른 목표만, P5 동의 전 나가기 · P7 계정 삭제 팝업 | 준하 | 준하 결정 | 03 · SET1 · 05a · 27 · P5 · P7 |
| D-16 | 10/8 | 아이 기기 켜기 · 끄기 단추 | 팀 v1.4 (브라우저 마이크 정책) | 확정 | T19 |
| D-17 | 10/8 | 알림 진입 · 로그인 만료 · 약관 개정 · 공통 오류 규칙 | 준하 v1.5 | 제안 | 04 · 01 · 02 · 공통 |
| D-21 | 10/8 | 05에서 보호자 폰 인터넷 줄 삭제(두 줄 + 판정 카드). 폰 인터넷 문제는 공통 오류 토스트로만 | 준하 | 준하 결정 | 05 · 04 |
| D-22 | 10/8 | 28 단추 전부 정의: 빠른 선택 3개 · 달력 첫 탭 시작일 둘째 탭 끝일(드래그도) · 활동 줄 체크 토글 · 만드는 중 「취소」=「←」(범위 유지) · 실패 「다시 시도」「범위 다시」 · 만들기는 몇 초라 배경 진행 없음. 날 토글은 뺌(시작 · 끝 탭과 손짓이 겹침) | 준하 (단추 전수 점검) | 준하 결정 | 28 · 30 · 리포트 API |
| D-24 | 10/8 밤 | 한 계정에 아이 하나, 기기 하나. 03은 아이 정보 한 화면(처음 등록 · 보기 · 수정), 03-1 · P4 · 「이 아이로」 · 「변경」 · 다른 아이 사용 중 잠금 삭제. 01 · 02 분기는 아이 등록 여부. 형제는 계정을 따로, 아이 교체는 계정 삭제(P7) 뒤 새로. core_child는 guardian과 1:1, selected_child 불필요, 계정당 세션 1개, API는 /api/child 단수(childId 인자는 생략 가능) | 사용자 결정(10/8 밤). 기기 공유 모델의 기록 귀속 문제 때문. 아이마다 기기 1(B안)도 검토했으나 아이 하나로 | 결정(팀 공유 · 요구사항 반영 필요) | 01 · 02 · 03 · 04 · P7 · core_child · /api/child |
| D-23 | 10/8 | 미정의 단추 마무리: 30 「나중에」=26(리포트 버림) · 18 줄 체크 · 별 즐겨찾기 · 빈 상태→08 · SET-01 · 05a 「←」=취소 · 17 하단 탭(마무리 중엔 자유대화 · 연습 잠김) · 03 · 03-1 이름 · 나이 검증 · P4 진행 중 아이 삭제 불가 · 27 담김 취소 · 08 네 칸 선택 사항 · 26 빈 날 한 줄 | 준하 (단추 전수 점검 · 각각 작은 결정) | 준하 제안 | 30 · 18 · SET1 · 05a · 17 · 03 · 03-1 · P4 · 27 · 08 · 26 |

## 역할 · 권한 경계
| 역할 | 할 수 있음 | 없음 |
|---|---|---|
| 보호자 | 계정 1 = 아이 1 = 기기 1. 모든 화면 조작 | 공동 보호자(두 계정이 한 아이) 없음 · 가족 초대 없음 |
| 아이 | 기기 웹에서 「켜기」 · 「끄기」만. 음성으로만 참여. 종료 표현으로 끝냄 | 화면 조작 없음 · 계정 없음 |
| 전문가 | 보호자가 건넨 PDF만 봄 | 계정 · 로그인 없음 |
| 운영 | ?debug=1 상태 로그(기기 웹) · 서버 로그 | 관리자 화면 없음(MVP) |

## 플랫폼 ADR(제안)
- **question**: 보호자 쪽을 무엇으로 만드나(아이 쪽은 웹으로 확정).
- **options**: [['A. Next.js 웹 + PWA', '한 코드 · TA가 Next.js 배포 언급 · 11/5 안에 가능', 'iOS 푸시는 홈 화면 추가 뒤에만(16.4+) · 백그라운드 WS 유지 안 됨(화면 꺼지면 SSE 끊김 → 푸시로 보완) · PDF 저장은 다운로드 + Web Share API(파일 공유는 iOS Safari 15+ · Android Chrome)'], ['B. React Native/Expo', '푸시 · 백그라운드 · 파일 공유가 자연스러움', '두 번째 코드베이스 · 스토어 배포 시간 · 팀 경험 없음(추정)'], ['C. A로 시작, 11/5 뒤 B 검토', '지금 범위(데모 · 11/5 제출)에 맞음', '푸시가 핵심 데모 항목이면 iOS에서 설치 안내 화면이 하나 더 필요']]
- **recommend**: C. Next.js PWA로 간다. 알림은 Android Chrome 웹 푸시로 데모하고 iOS는 '홈 화면에 추가' 안내 한 줄. PDF는 서버가 만들어 내려주고 Web Share API로 공유(지원 안 되면 다운로드만). 실시간은 SSE + 화면 꺼짐 시 재연결 + 푸시 보완.
- **decides**: 팀 스크럼 · TA 확인

## ERD 10/7에 없는 것
| 화면 | 필요한 것 | 테이블 · 컬럼 |
|---|---|---|
| S-03 | 아이 | core_child.display_name / age / interests_json / status (guardian과 1:1 · guardian_id UNIQUE 제약) |
| S-03 | 기기 상태 한 줄 | device.status |
| S-04 | 기기 상태 · 마지막 확인 | device.status(STABLE · UNSTABLE · DISCONNECTED · UNRESPONSIVE · NONE) / last_seen_at (realtime 측 heartbeat) |
| S-05 | 등록 여부 · 코드 앞자리 | device.code_prefix / registered_at |
| S-05 | 기기 네트워크 현황(안정 · 불안정 · 끊김) | device.status / last_seen_at / rtt_ms · 끊김 횟수(Realtime heartbeat로 판정: 지연 1초 넘거나 5분 안 끊김 2회 이상이면 불안정, 40초 무응답이면 끊김) |
| S-05A | 코드 | device.code (서버 발급 · 미등록 상태) |
| S-18 | 즐겨찾기 · 담은 순서 | core_practice_goal_selection.is_favorite / sort_order |
| S-30 | 기간 | core_report.period_from / period_to (컬럼) |
| D-00 | 코드 | device.code (상태 UNREGISTERED) · 기기 저장소 deviceSecret |
| Device | 기기 테이블 전체 | device(id, guardian_id, code, code_status, device_secret_hash, device_token_hash, status, last_seen_at, registered_at, released_at) |
| S-18 | 즐겨찾기 · 담은 순서 | core_practice_goal_selection.is_favorite boolean, sort_order int |
| core_child | 1:1 제약 | core_child.guardian_id UNIQUE (계정당 아이 하나 · D-24) |
| 푸시 | 푸시 토큰 | core_push_token(guardian_id, token, platform, created_at) |
| 세션 | end_reason 값 목록 | realtime_activity_session.end_reason을 enum으로: GUARDIAN_STOP · CHILD_STOP · SILENCE · DISCONNECT · CONSENT_WITHDRAWN · UNCONFIRMED · DECLINED · FAILED, status에 ENDED_UNCONFIRMED 추가 |
| S-28 | 리포트 기간 | core_report.period_from, period_to (지금은 content_json 안에만 들어갈 수 있음) |
| S-12 연습 | 연습 단계 값(D-05 결정 뒤 계약) | realtime_activity_session.current_practice_stage 값 목록 · practice_attempt.attempt_type · practice_response.response_type · evaluation.label 기준은 AI 입출력 계약(WBS 1.7.3)에서 확정 · 세션당 목표 수(ERD v1은 단일 목표 Snapshot) |
