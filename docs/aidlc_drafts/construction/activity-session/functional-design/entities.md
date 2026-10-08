# entities.md (초안) · Unit `activity-session`

> 초안. 화면 설계 v1.5j(2026-10-08)와 개발 명세 1008에서 뽑았다. AI-DLC Edit File 모드에서 붙여넣기용이며 번호 · 절 구성은 AI-DLC가 다시 매긴다. 화면 설계가 기준이다.


이 유닛의 화면이 읽거나 쓰는 테이블(ERD v0.1)과 컬럼. [없음] 표시는 개발명세 빈칸 표.

| 테이블 | 컬럼 | 쓰는 화면 |
|---|---|---|
| core_result_job | id, session_id, snapshot_id, job_type, status, idempotency_key, requested_by, attempt_count, last_error, created_at, updated_at | S-17 |
| core_session_result_state | session_id, current_result_id, updated_at | S-17 |
| realtime_activity_session | id, guardian_id, child_id, activity_context_id, activity_type, status, current_practice_stage, start_idempotency_key, voice_client_id, gpt_live_session_id, consent_version, consent_checked_at, data_collection_allowed, transcript_storage_allowed, child_context_json, intro_content_version_id, closing_content_version_id, end_reason, end_detail, termination_requested_at, output_stopped_at, transport_closed_at, provider_closed_at, started_at, ended_at, row_version, created_at, updated_at, practice_goal_selection_id | P-01, P-02, P-03, S-12, S-17 |
| realtime_session_event | id, session_id, event_type, op_id, transcript_id, payload_json, created_at | S-12 |
| realtime_transcript | id, session_id, turn_id, sequence_no, speaker, text, assembly_status, review_needed, started_at, ended_at, created_at, deleted_at | S-12 |
