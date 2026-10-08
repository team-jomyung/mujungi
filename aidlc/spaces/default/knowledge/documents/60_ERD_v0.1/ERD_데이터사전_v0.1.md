# 무중 ERD 데이터 사전 v0.1 (2026-10-07, 워드 → 글)

원본 설명: 초안 v0.1 · 현재 ERD v1 설명용 기준: 2026-10-07 현재 ERDCloud v1 화면 + 기존 ERDCloud import 초안

같은 폴더에 PostgreSQL DDL(mujoong_ERD_PostgreSQL_1007.sql)과 ERD 그림(png)이 있다. 화면 설계 v1.5j 기준으로 고칠 곳은 개발전환_점검표 2절(1 · 2 · 3 · 4 · 7 · 13 · 14 · 15 · 17 · 20)과 개발명세 '빈칸' 표.

## core_guardian
보호자 서비스 계정

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK. 보호자 식별자 |
| status | VARCHAR(30) | 보호자 계정 상태 |
| created_at | TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMP | 수정 시각 |

## core_social_account
소셜 로그인 계정 연결

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| guardian_id | UUID | FK → core_guardian.id |
| provider | VARCHAR(20) | 소셜 제공자 |
| provider_user_id | VARCHAR(255) | 제공자 사용자 식별자 |
| provider_email | VARCHAR(255) | 제공자 이메일 |
| created_at | TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMP | 수정 시각 |

## core_child
보호자가 관리하는 아동

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| guardian_id | UUID | FK → core_guardian.id |
| display_name | VARCHAR(100) | 이름 또는 별명 |
| age | INT | 연령 |
| interests_json | JSONB | 관심사 |
| status | VARCHAR(30) | 아동 상태 |
| created_at | TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMP | 수정 시각 |
| deleted_at | TIMESTAMP | Soft Delete 시각 |

## core_consent
동의 이력

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| actor_guardian_id | UUID | FK → core_guardian.id. 해당 동의 행위를 수행한 보호자 |
| child_id | UUID | FK → core_child.id |
| consent_type | VARCHAR(50) | 동의 유형 |
| consent_status | VARCHAR(30) | 동의 상태 |
| consent_version | INT | 동의 버전 |
| policy_version | VARCHAR(50) | 정책 버전 |
| effective_from | TIMESTAMP | 효력 시작 시각 |
| effective_to | TIMESTAMP | 효력 종료 시각 |
| created_at | TIMESTAMP | 생성 시각 |

## core_stop_expression
아동별 종료 표현 설정

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| child_id | UUID | FK → core_child.id |
| expression_suffix | VARCHAR(10) | '무중아' 뒤 추가 종료 표현 |
| normalized_suffix | VARCHAR(20) | 비교용 정규화 표현 |
| sort_order | INT | 표시 순서 |
| created_at | TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMP | 수정 시각 |

## core_activity_context
자유대화 시작 전 보호자 입력 문맥

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| guardian_id | UUID | FK → core_guardian.id |
| child_id | UUID | FK → core_child.id |
| session_id | UUID | Realtime ActivitySession 논리 참조, DB FK 없음 |
| purpose | VARCHAR(50) | 자유대화 목적 |
| known_content | TEXT | 보호자가 알고 있는 내용 |
| situation_type | VARCHAR(100) | 관련 상황 |
| source_type | VARCHAR(50) | 정보 출처 |
| question | TEXT | 궁금한 내용 |
| status | VARCHAR(30) | Context 상태 |
| created_at | TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMP | 수정 시각 |
| deleted_at | TIMESTAMP | Soft Delete 시각 |

## core_approved_content
승인 콘텐츠의 논리 정의

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| content_code | VARCHAR(100) | 콘텐츠 코드 |
| content_type | VARCHAR(50) | 콘텐츠 유형 |
| created_at | TIMESTAMP | 생성 시각 |

## core_approved_content_version
승인 콘텐츠의 버전별 본문/오디오

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| content_id | UUID | FK → core_approved_content.id |
| version | INT | 콘텐츠 버전 |
| content_text | TEXT | 승인 문구 |
| audio_path | VARCHAR(500) | 승인된 고정 오디오 경로 |
| status | VARCHAR(30) | 버전 상태 |
| created_at | TIMESTAMP | 생성 시각 |

## core_structured_experience
자유대화 전사/문맥을 구조화한 경험 데이터

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| child_id | UUID | FK → core_child.id |
| activity_context_id | UUID | FK → core_activity_context.id, 선택 |
| session_id | UUID | Realtime ActivitySession 논리 참조, DB FK 없음 |
| version | INT | 구조화 결과 버전 |
| content_json | JSONB | 구조화된 경험 내용 |
| model_version | VARCHAR(100) | 생성 모델 버전 |
| prompt_version | VARCHAR(100) | 프롬프트 버전 |
| status | VARCHAR(30) | VALID/INVALIDATED 등 상태 |
| created_at | TIMESTAMP | 생성 시각 |
| invalidated_at | TIMESTAMP | 무효화 시각 |
| invalidation_reason | VARCHAR(100) | 무효화 사유 |

## core_goal_recommendation
아동에게 제안된 연습 목표 후보

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| child_id | UUID | FK → core_child.id |
| structured_experience_id | UUID | FK → core_structured_experience.id |
| source_session_id | UUID | 근거 ActivitySession 논리 참조, DB FK 없음 |
| behavior_key | VARCHAR(150) | 화용 행동 식별 키 |
| reason_text | TEXT | 추천 이유 설명 |
| status | VARCHAR(30) | 추천 상태 |
| created_at | TIMESTAMP | 생성 시각 |
| invalidated_at | TIMESTAMP | 무효화 시각 |
| invalidation_reason | VARCHAR(100) | 무효화 사유 |

## core_recommendation_evidence
GoalRecommendation의 실제 근거

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| recommendation_id | UUID | FK → core_goal_recommendation.id |
| structured_experience_id | UUID | FK → core_structured_experience.id, 선택 |
| source_transcript_id | UUID | Realtime Transcript 논리 참조, DB FK 없음 |
| evidence_type | VARCHAR(50) | 근거 유형 |
| created_at | TIMESTAMP | 생성 시각 |

## core_practice_goal_selection
보호자가 선택/담아 둔 연습 목표

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| guardian_id | UUID | FK → core_guardian.id |
| child_id | UUID | FK → core_child.id |
| recommendation_id | UUID | FK → core_goal_recommendation.id |
| selection_type | VARCHAR(30) | 선택 방식/유형 |
| status | VARCHAR(30) | 목표 상태 |
| selected_at | TIMESTAMP | 선택 시각 |
| created_at | TIMESTAMP | 생성 시각 |
| invalidated_at | TIMESTAMP | 무효화 시각 |
| invalidation_reason | VARCHAR(100) | 무효화 사유 |

## core_scenario
연습 목표를 위한 AI 생성 상황

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| child_id | UUID | FK → core_child.id |
| goal_selection_id | UUID | FK → core_practice_goal_selection.id |
| target_behavior_key | VARCHAR(150) | 목표 화용 행동 키 |
| scenario_type | VARCHAR(30) | 상황 유형 |
| relation_type | VARCHAR(100) | 관계 유형 |
| opportunity_condition | TEXT | 행동 기회 조건 |
| scenario_text | TEXT | 상황 본문 |
| partner_role_text | TEXT | AI 파트너 역할 지시 |
| model_version | VARCHAR(100) | 생성 모델 버전 |
| prompt_version | VARCHAR(100) | 프롬프트 버전 |
| validation_status | VARCHAR(30) | 생성물 검증 상태 |
| created_at | TIMESTAMP | 생성 시각 |
| status | VARCHAR(30) | 재사용 가능/무효화 상태 |
| invalidated_at | TIMESTAMP | 무효화 시각 |
| invalidation_reason | VARCHAR(100) | 무효화 사유 |

## core_support_item
생활 지원/팁 콘텐츠

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| behavior_key | VARCHAR(150) | 관련 화용 행동 키 |
| condition_text | TEXT | 적용 조건 |
| content_text | TEXT | 보호자에게 제시할 지원 내용 |
| status | VARCHAR(30) | 사용 상태 |
| created_at | TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMP | 수정 시각 |

## core_result_job
비실시간 결과 생성 작업

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | Realtime ActivitySession 논리 참조, DB FK 없음 |
| snapshot_id | UUID | Realtime ResultInputSnapshot 논리 참조, DB FK 없음 |
| job_type | VARCHAR(30) | 결과 생성 작업 유형 |
| status | VARCHAR(30) | 작업 상태 |
| idempotency_key | VARCHAR(100) | 중복 실행 방지 키 |
| requested_by | VARCHAR(30) | 요청 주체/유형 |
| attempt_count | INT | 실행 시도 횟수 |
| last_error | TEXT | 마지막 오류 |
| created_at | TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMP | 수정 시각 |

## core_result_job_attempt
Result Worker의 개별 실행 시도

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| result_job_id | UUID | FK → core_result_job.id |
| attempt_no | INT | 시도 번호 |
| trigger_type | VARCHAR(30) | 자동 재시도/수동 등 트리거 유형 |
| status | VARCHAR(30) | 시도 상태 |
| error_code | VARCHAR(100) | 오류 코드 |
| error_message | TEXT | 오류 내용 |
| started_at | TIMESTAMP | 시작 시각 |
| ended_at | TIMESTAMP | 종료 시각 |

## core_result
보호자에게 제공 가능한 결과 버전

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | Realtime ActivitySession 논리 참조, DB FK 없음 |
| result_job_id | UUID | FK → core_result_job.id, 선택 |
| source_snapshot_id | UUID | Realtime ResultInputSnapshot 논리 참조, DB FK 없음 |
| version | INT | 세션별 Result 버전 |
| result_type | VARCHAR(40) | FULL/PARTIAL/FAILURE_REPORT 등 |
| status | VARCHAR(30) | VALID/INVALIDATED 등 상태 |
| content_json | JSONB | 결과 본문 |
| model_version | VARCHAR(100) | 생성 모델 버전 |
| prompt_version | VARCHAR(100) | 프롬프트 버전 |
| created_at | TIMESTAMP | 생성 시각 |
| invalidated_at | TIMESTAMP | 무효화 시각 |
| invalidation_reason | VARCHAR(100) | 무효화 사유 |

## core_session_result_state
세션별 현재 노출 Result 포인터

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| session_id | UUID | PK. Realtime ActivitySession 논리 참조, DB FK 없음 |
| current_result_id | UUID | FK → core_result.id |
| updated_at | TIMESTAMP | 현재 Result 변경 시각 |

## core_report
상담용 리포트의 최종 Snapshot

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| guardian_id | UUID | FK → core_guardian.id |
| child_id | UUID | FK → core_child.id |
| status | VARCHAR(30) | Report 상태 |
| content_json | JSONB | 생성 당시 최종 리포트 내용 |
| generated_at | TIMESTAMP | 생성 완료 시각 |
| invalidated_at | TIMESTAMP | 무효화 시각 |
| invalidation_reason | VARCHAR(100) | 무효화 사유 |
| deleted_at | TIMESTAMP | 직접 삭제/Soft Delete 시각 |
| created_at | TIMESTAMP | 생성 시각 |

## core_report_activity
Report와 포함 활동/Result 버전 연결

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| report_id | UUID | FK → core_report.id |
| session_id | UUID | Realtime ActivitySession 논리 참조, DB FK 없음 |
| result_id | UUID | FK → core_result.id |
| included_at | TIMESTAMP | 리포트 포함 시각 |

## core_analytics_event
서비스 분석 이벤트

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| event_id | UUID | PK |
| event_name | VARCHAR(100) | 이벤트 이름 |
| event_time | TIMESTAMP | 이벤트 발생 시각 |
| session_id | UUID | Realtime ActivitySession 논리 참조 |
| activity_type | VARCHAR(30) | FREE_TALK/PRACTICE 등 |
| target_skill_key | VARCHAR(150) | 관련 목표/행동 키 |
| trigger_source | VARCHAR(50) | parent_app/child_voice/system 등 |
| app_version | VARCHAR(50) | 앱 버전 |
| model_version | VARCHAR(100) | 모델 버전 |
| prompt_version | VARCHAR(100) | 프롬프트 버전 |
| created_at | TIMESTAMP | 적재 시각 |
| guardian_id | UUID | FK → core_guardian.id |
| child_id | UUID | FK → core_child.id |
| scenario_id | UUID | FK → core_scenario.id |

## core_data_deletion_request
개인 기록 삭제 요청과 처리 상태

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| guardian_id | UUID | FK → core_guardian.id |
| child_id | UUID | FK → core_child.id |
| session_id | UUID | Realtime ActivitySession 논리 참조, DB FK 없음 |
| target_type | VARCHAR(50) | 삭제 대상 유형 |
| target_id | UUID | 삭제 대상 식별자 |
| requested_scope_json | JSONB | 삭제 범위 |
| status | VARCHAR(30) | 요청 처리 상태 |
| idempotency_key | VARCHAR(100) | 중복 요청 방지 키 |
| blocked_at | TIMESTAMP | 조회/AI 재사용 즉시 차단 시각 |
| requested_at | TIMESTAMP | 요청 시각 |
| completed_at | TIMESTAMP | 최종 처리 완료 시각 |
| last_error | TEXT | 처리 실패 사유 |

## core_outbox_event
Core에서 외부 서비스로 전달할 비동기 이벤트

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| event_id | UUID | PK |
| event_type | VARCHAR(100) | 이벤트 유형 |
| aggregate_type | VARCHAR(100) | 연관 Aggregate 유형 |
| aggregate_id | UUID | 연관 Aggregate ID |
| payload_json | JSONB | 전달 Payload |
| status | VARCHAR(30) | PENDING/PUBLISHED 등 |
| retry_count | INT | 전송 재시도 횟수 |
| available_at | TIMESTAMP | 전송 가능 시각 |
| published_at | TIMESTAMP | 전송 성공 시각 |
| last_error | TEXT | 마지막 전송 오류 |
| created_at | TIMESTAMP | 생성 시각 |

## core_inbox_event
Realtime 등에서 수신한 이벤트의 중복 방지/처리 상태

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| event_id | UUID | PK/UNIQUE. 이벤트 중복 처리 방지 |
| source_service | VARCHAR(50) | 발신 서비스 |
| event_type | VARCHAR(100) | 이벤트 유형 |
| payload_json | JSONB | 수신 Payload |
| status | VARCHAR(30) | 처리 상태 |
| retry_count | INT | 처리 재시도 횟수 |
| received_at | TIMESTAMP | 수신 시각 |
| processed_at | TIMESTAMP | 처리 완료 시각 |
| last_error | TEXT | 마지막 처리 오류 |

## core_spring_session
Spring Session JDBC 로그인 세션

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| primary_id | CHAR(36) | PK. Spring Session 내부 PK |
| session_id | CHAR(36) | 외부 세션 식별자 |
| creation_time | BIGINT | 세션 생성 Epoch time |
| last_access_time | BIGINT | 마지막 접근 Epoch time |
| max_inactive_interval | INT | 비활성 만료 시간(초) |
| expiry_time | BIGINT | 만료 Epoch time |
| principal_name | VARCHAR(100) | 인증 Principal |

## core_spring_session_attributes
Spring Session의 직렬화된 속성

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| session_primary_id | CHAR(36) | FK → core_spring_session.primary_id |
| attribute_name | VARCHAR(200) | 세션 Attribute 이름 |
| attribute_bytes | BYTEA | 직렬화된 Attribute 값 |

## realtime_activity_session
자유대화/연습 실시간 활동의 중심 세션

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| guardian_id | UUID | Core Guardian 논리 참조, DB FK 없음 |
| child_id | UUID | Core Child 논리 참조, DB FK 없음 |
| activity_context_id | UUID | Core ActivityContext 논리 참조, DB FK 없음 |
| activity_type | VARCHAR(30) | FREE_TALK/PRACTICE |
| status | VARCHAR(30) | PREPARING/ACTIVE/ENDING/ENDED 등 |
| current_practice_stage | VARCHAR(50) | 현재 연습 단계 |
| start_idempotency_key | VARCHAR(100) | 시작 중복 방지 키 |
| voice_client_id | VARCHAR(100) | 기기/Voice Client 식별자 |
| gpt_live_session_id | VARCHAR(255) | GPT-Live 세션 식별자 |
| consent_version | INT | 세션 시작 시 Consent 버전 Snapshot |
| consent_checked_at | TIMESTAMP | Consent 확인 시각 |
| data_collection_allowed | BOOLEAN | 수집 허용 Snapshot |
| transcript_storage_allowed | BOOLEAN | 전사 저장 허용 Snapshot |
| child_context_json | JSONB | 실시간 대화에 필요한 최소 아동 Context |
| intro_content_version_id | UUID | Core ApprovedContentVersion 논리 참조 |
| closing_content_version_id | UUID | Core ApprovedContentVersion 논리 참조 |
| end_reason | VARCHAR(50) | 종료 사유 |
| end_detail | TEXT | 종료 상세 |
| termination_requested_at | TIMESTAMP | 종료 요청 확정 시각 |
| output_stopped_at | TIMESTAMP | 기기 출력 중단 확인 시각 |
| transport_closed_at | TIMESTAMP | 전송 채널 정리 시각 |
| provider_closed_at | TIMESTAMP | GPT-Live/provider 정리 시각 |
| started_at | TIMESTAMP | 실제 활동 시작 시각 |
| ended_at | TIMESTAMP | 활동 종료 시각 |
| row_version | BIGINT | 낙관적 잠금/동시성 버전 |
| created_at | TIMESTAMP | 생성 시각 |
| updated_at | TIMESTAMP | 수정 시각 |
| practice_goal_selection_id | UUID | Core PracticeGoalSelection 논리 참조. 현 v1은 단일 목표 Snapshot |

## realtime_session_stop_expression
세션 시작 시 고정한 종료 표현 Snapshot

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | FK → realtime_activity_session.id |
| expression_text | VARCHAR(100) | 세션에 적용되는 종료 표현 |
| is_default | BOOLEAN | 기본 종료 표현 여부 |
| sort_order | INT | 적용/표시 순서 |
| created_at | TIMESTAMP | 생성 시각 |

## realtime_conversation_turn
AI 질문/기회 단위의 대화 Turn

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | FK → realtime_activity_session.id |
| turn_no | INT | 세션 내 Turn 순번 |
| turn_type | VARCHAR(50) | Turn 유형 |
| status | VARCHAR(30) | Turn 상태 |
| started_at | TIMESTAMP | 시작 시각 |
| ended_at | TIMESTAMP | 종료 시각 |
| created_at | TIMESTAMP | 생성 시각 |

## realtime_transcript
아동/AI 발화 전사

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | FK → realtime_activity_session.id |
| turn_id | UUID | FK → realtime_conversation_turn.id, 선택 |
| sequence_no | INT | 세션 내 전사 순번 |
| speaker | VARCHAR(30) | CHILD/AI 등 화자 |
| text | TEXT | 전사 본문 |
| assembly_status | VARCHAR(30) | 전사 조립 상태 |
| review_needed | BOOLEAN | 불확실/검토 필요 여부 |
| started_at | TIMESTAMP | 발화 시작 시각 |
| ended_at | TIMESTAMP | 발화 종료 시각 |
| created_at | TIMESTAMP | 저장 시각 |
| deleted_at | TIMESTAMP | Soft Delete 시각 |

## realtime_audio_recording
선택 원음 보관 메타데이터 - 현 v1에 존재

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | FK → realtime_activity_session.id |
| speaker | VARCHAR(30) | 화자 |
| storage_path | VARCHAR(500) | 원음 저장 경로 |
| started_at | TIMESTAMP | 녹음 시작 시각 |
| ended_at | TIMESTAMP | 녹음 종료 시각 |
| created_at | TIMESTAMP | 생성 시각 |
| deleted_at | TIMESTAMP | 삭제 시각 |

## realtime_session_event
세션 상태 변화/운영 이벤트

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | FK → realtime_activity_session.id |
| event_type | VARCHAR(100) | 이벤트 유형 |
| op_id | VARCHAR(100) | 명령/연산 중복 방지 식별자 |
| transcript_id | UUID | FK → realtime_transcript.id, 선택 |
| payload_json | JSONB | 민감 원문을 제외한 추가 정보 |
| created_at | TIMESTAMP | 발생 시각 |

## realtime_scenario_exposure
아동에게 실제 제시된 Scenario Snapshot

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | FK → realtime_activity_session.id |
| child_id | UUID | Core Child 논리 참조, DB FK 없음 |
| scenario_id | UUID | Core Scenario 논리 참조, DB FK 없음 |
| sequence_no | INT | 세션 내 노출 순서 |
| exposure_type | VARCHAR(30) | 첫 상황/새 상황 등 노출 유형 |
| scenario_snapshot_json | JSONB | 실행 당시 Scenario Snapshot |
| exposed_at | TIMESTAMP | 노출 시각 |

## realtime_practice_attempt
Scenario에서 수행한 연습 시도

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | FK → realtime_activity_session.id |
| scenario_exposure_id | UUID | FK → realtime_scenario_exposure.id |
| goal_selection_id | UUID | Core PracticeGoalSelection 논리 참조, DB FK 없음 |
| attempt_type | VARCHAR(30) | 첫 반응/재시도/새 상황 등 시도 유형 |
| sequence_no | INT | 시도 순번 |
| status | VARCHAR(30) | 시도 상태 |
| created_at | TIMESTAMP | 생성 시각 |
| ended_at | TIMESTAMP | 종료 시각 |

## realtime_practice_response
연습 Attempt에서 수집한 아동 반응

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| attempt_id | UUID | FK → realtime_practice_attempt.id |
| transcript_id | UUID | FK → realtime_transcript.id, 선택 |
| response_type | VARCHAR(30) | FIRST/AFTER_CUE/NEW_SCENARIO 등 |
| status | VARCHAR(30) | 반응 상태 |
| response_latency_ms | BIGINT | 반응 지연 |
| created_at | TIMESTAMP | 생성 시각 |

## realtime_cue_delivery
실제로 아동에게 전달한 도움(Cue)

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| attempt_id | UUID | FK → realtime_practice_attempt.id |
| source_type | VARCHAR(30) | Cue 생성/선택 출처 |
| delivered_text | TEXT | 실제 전달된 Cue 문구 |
| cue_level | VARCHAR(30) | 도움 수준 |
| sequence_order | INT | Cue 순서 |
| model_version | VARCHAR(100) | 생성 모델 버전 |
| prompt_version | VARCHAR(100) | 프롬프트 버전 |
| given_at | TIMESTAMP | Cue 제공 시각 |

## realtime_evaluation
PracticeResponse 기능 충족 판정

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| response_id | UUID | FK → realtime_practice_response.id |
| label | VARCHAR(30) | functional/partial/none/uncertain 등 |
| review_needed | BOOLEAN | 검토 필요 여부 |
| reason | TEXT | 판정 사유 |
| raw_json | JSONB | 모델의 구조화 원출력 |
| model_version | VARCHAR(100) | 평가 모델 버전 |
| prompt_version | VARCHAR(100) | 평가 프롬프트 버전 |
| created_at | TIMESTAMP | 생성 시각 |
| invalidated_at | TIMESTAMP | 무효화 시각 |
| invalidation_reason | VARCHAR(100) | 무효화 사유 |
| status | VARCHAR(30) | 평가 상태 |

## realtime_result_input_snapshot
Result 생성용 입력 기준점/Snapshot

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| id | UUID | PK |
| session_id | UUID | FK → realtime_activity_session.id |
| snapshot_version | INT | Snapshot 버전 |
| status | VARCHAR(30) | VALID/INVALIDATED 등 |
| cutoff_at | TIMESTAMP | 이 시각까지의 데이터를 입력으로 고정 |
| consent_version | INT | 결과 생성 시 Consent 버전 |
| consent_checked_at | TIMESTAMP | Consent 확인 시각 |
| data_usage_allowed | BOOLEAN | 결과 생성 데이터 사용 허용 여부 |
| snapshot_hash | VARCHAR(128) | 입력 집합 무결성/비교용 Hash |
| captured_at | TIMESTAMP | Snapshot 생성 시각 |
| invalidated_at | TIMESTAMP | 무효화 시각 |
| invalidation_reason | VARCHAR(100) | 무효화 사유 |
| input_json | JSONB | Core 전달용 결과 입력 Snapshot |

## realtime_outbox_event
Realtime에서 Core로 전달할 비동기 이벤트

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| event_id | UUID | PK |
| event_type | VARCHAR(100) | 이벤트 유형 |
| aggregate_type | VARCHAR(100) | 연관 Aggregate 유형 |
| aggregate_id | UUID | 연관 Aggregate ID |
| payload_json | JSONB | 전달 Payload |
| status | VARCHAR(30) | PENDING/PUBLISHED 등 |
| retry_count | INT | 전송 재시도 횟수 |
| available_at | TIMESTAMP | 전송 가능 시각 |
| published_at | TIMESTAMP | 전송 성공 시각 |
| last_error | TEXT | 마지막 전송 오류 |
| created_at | TIMESTAMP | 생성 시각 |

## realtime_inbox_event
Core에서 수신한 이벤트의 중복 방지/처리 상태

| 필드 | 타입 | 역할 · 비고 |
|---|---|---|
| event_id | UUID | PK/UNIQUE. 이벤트 중복 처리 방지 |
| source_service | VARCHAR(50) | 발신 서비스 |
| event_type | VARCHAR(100) | 이벤트 유형 |
| payload_json | JSONB | 수신 Payload |
| status | VARCHAR(30) | 처리 상태 |
| retry_count | INT | 처리 재시도 횟수 |
| received_at | TIMESTAMP | 수신 시각 |
| processed_at | TIMESTAMP | 처리 완료 시각 |
| last_error | TEXT | 마지막 처리 오류 |
