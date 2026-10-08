# entities.md (초안) · Unit `ai-goals-results`

> 초안. 화면 설계 v1.5j(2026-10-08)와 개발 명세 1008에서 뽑았다. AI-DLC Edit File 모드에서 붙여넣기용이며 번호 · 절 구성은 AI-DLC가 다시 매긴다. 화면 설계가 기준이다.


이 유닛의 화면이 읽거나 쓰는 테이블(ERD v0.1)과 컬럼. [없음] 표시는 개발명세 빈칸 표.

| 테이블 | 컬럼 | 쓰는 화면 |
|---|---|---|
| core_child | id, guardian_id, display_name, age, interests_json, status, created_at, updated_at, deleted_at | R-PDF |
| core_goal_recommendation | id, child_id, structured_experience_id, source_session_id, behavior_key, reason_text, status, created_at, invalidated_at, invalidation_reason | S-18, S-27 |
| core_practice_goal_selection | id, guardian_id, child_id, recommendation_id, selection_type, status, selected_at, created_at, invalidated_at, invalidation_reason | S-18, S-27 |
| core_recommendation_evidence | id, recommendation_id, structured_experience_id, source_transcript_id, evidence_type, created_at | S-27 |
| core_report | id, guardian_id, child_id, status, content_json, generated_at, invalidated_at, invalidation_reason, deleted_at, created_at | R-PDF |
| core_report_activity | id, report_id, session_id, result_id, included_at | R-PDF |
| core_result | id, session_id, result_job_id, source_snapshot_id, version, result_type, status, content_json, model_version, prompt_version, created_at, invalidated_at, invalidation_reason | S-27, S-28 |
| core_support_item | id, behavior_key, condition_text, content_text, status, created_at, updated_at | S-27 |
| realtime_activity_session | id, guardian_id, child_id, activity_context_id, activity_type, status, current_practice_stage, start_idempotency_key, voice_client_id, gpt_live_session_id, consent_version, consent_checked_at, data_collection_allowed, transcript_storage_allowed, child_context_json, intro_content_version_id, closing_content_version_id, end_reason, end_detail, termination_requested_at, output_stopped_at, transport_closed_at, provider_closed_at, started_at, ended_at, row_version, created_at, updated_at, practice_goal_selection_id | R-PDF, S-28 |
| realtime_practice_attempt | id, session_id, scenario_exposure_id, goal_selection_id, attempt_type, sequence_no, status, created_at, ended_at | S-18 |
| realtime_transcript | id, session_id, turn_id, sequence_no, speaker, text, assembly_status, review_needed, started_at, ended_at, created_at, deleted_at | R-PDF, S-27 |
