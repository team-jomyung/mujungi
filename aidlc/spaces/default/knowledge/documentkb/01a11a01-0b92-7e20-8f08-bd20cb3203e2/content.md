CREATE TABLE "core_result_job_attempt" (
	"id"	UUID		NOT NULL,
	"result_job_id"	UUID		NOT NULL,
	"attempt_no"	INT		NOT NULL,
	"trigger_type"	VARCHAR(30)		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"error_code"	VARCHAR(100)		NULL,
	"error_message"	TEXT		NULL,
	"started_at"	TIMESTAMP		NOT NULL,
	"ended_at"	TIMESTAMP		NULL
);

CREATE TABLE "core_recommendation_evidence" (
	"id"	UUID		NOT NULL,
	"recommendation_id"	UUID		NOT NULL,
	"structured_experience_id"	UUID		NULL,
	"source_transcript_id"	UUID		NULL,
	"evidence_type"	VARCHAR(50)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_structured_experience" (
	"id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"activity_context_id"	UUID		NULL,
	"session_id"	UUID		NOT NULL,
	"version"	INT		NOT NULL,
	"content_json"	JSONB		NOT NULL,
	"model_version"	VARCHAR(100)		NULL,
	"prompt_version"	VARCHAR(100)		NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"invalidated_at"	TIMESTAMP		NULL,
	"invalidation_reason"	VARCHAR(100)		NULL
);

CREATE TABLE "realtime_result_input_snapshot" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"snapshot_version"	INT		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"cutoff_at"	TIMESTAMP		NOT NULL,
	"consent_version"	INT		NOT NULL,
	"consent_checked_at"	TIMESTAMP		NOT NULL,
	"data_usage_allowed"	BOOLEAN		NOT NULL,
	"snapshot_hash"	VARCHAR(128)		NULL,
	"captured_at"	TIMESTAMP		NOT NULL,
	"invalidated_at"	TIMESTAMP		NULL,
	"invalidation_reason"	VARCHAR(100)		NULL,
	"input_json"	JSONB		NOT NULL
);

COMMENT ON COLUMN "realtime_result_input_snapshot"."input_json" IS 'PROPOSAL: immutable ResultInputSnapshotDto input and source-ID manifest fixed at cutoff. Same snapshot for technical retry; new snapshot for regeneration. INVALIDATED blocks reads/reuse. Sensitive content retention follows deletion policy.';

CREATE TABLE "realtime_session_stop_expression" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"expression_text"	VARCHAR(100)		NOT NULL,
	"is_default"	BOOLEAN	DEFAULT FALSE	NOT NULL,
	"sort_order"	INT		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

COMMENT ON COLUMN "realtime_session_stop_expression"."is_default" IS 'Fixed default phrase is always included once per session; up to three current custom expressions are snapshotted at start.';

CREATE TABLE "core_scenario" (
	"id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"goal_selection_id"	UUID		NOT NULL,
	"target_behavior_key"	VARCHAR(150)		NOT NULL,
	"scenario_type"	VARCHAR(30)		NOT NULL,
	"relation_type"	VARCHAR(100)		NULL,
	"opportunity_condition"	TEXT		NULL,
	"scenario_text"	TEXT		NOT NULL,
	"partner_role_text"	TEXT		NOT NULL,
	"model_version"	VARCHAR(100)		NOT NULL,
	"prompt_version"	VARCHAR(100)		NOT NULL,
	"validation_status"	VARCHAR(30)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"status"	VARCHAR(30)	DEFAULT 'VALID'	NOT NULL,
	"invalidated_at"	TIMESTAMP		NULL,
	"invalidation_reason"	VARCHAR(100)		NULL
);

COMMENT ON COLUMN "core_scenario"."status" IS 'PROPOSAL: VALID allows new reuse; INVALIDATED retains past scenario content but blocks new practice. Content remains immutable.';

CREATE TABLE "realtime_practice_attempt" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"scenario_exposure_id"	UUID		NOT NULL,
	"goal_selection_id"	UUID		NOT NULL,
	"attempt_type"	VARCHAR(30)		NOT NULL,
	"sequence_no"	INT		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"ended_at"	TIMESTAMP		NULL
);

CREATE TABLE "realtime_audio_recording" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"speaker"	VARCHAR(30)		NOT NULL,
	"storage_path"	VARCHAR(500)		NOT NULL,
	"started_at"	TIMESTAMP		NULL,
	"ended_at"	TIMESTAMP		NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"deleted_at"	TIMESTAMP		NULL
);

CREATE TABLE "core_spring_session" (
	"primary_id"	CHAR(36)		NOT NULL,
	"session_id"	CHAR(36)		NOT NULL,
	"creation_time"	BIGINT		NOT NULL,
	"last_access_time"	BIGINT		NOT NULL,
	"max_inactive_interval"	INT		NOT NULL,
	"expiry_time"	BIGINT		NOT NULL,
	"principal_name"	VARCHAR(100)		NULL
);

CREATE TABLE "core_practice_goal_selection" (
	"id"	UUID		NOT NULL,
	"guardian_id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"recommendation_id"	UUID		NOT NULL,
	"selection_type"	VARCHAR(30)		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"selected_at"	TIMESTAMP		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"invalidated_at"	TIMESTAMP		NULL,
	"invalidation_reason"	VARCHAR(100)		NULL
);

COMMENT ON COLUMN "core_practice_goal_selection"."invalidated_at" IS 'Set when recommendation evidence becomes invalid; block new practice, retain history.';

CREATE TABLE "realtime_practice_response" (
	"id"	UUID		NOT NULL,
	"attempt_id"	UUID		NOT NULL,
	"transcript_id"	UUID		NULL,
	"response_type"	VARCHAR(30)		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"response_latency_ms"	BIGINT		NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_child" (
	"id"	UUID		NOT NULL,
	"guardian_id"	UUID		NOT NULL,
	"display_name"	VARCHAR(100)		NOT NULL,
	"age"	INT		NULL,
	"interests_json"	JSONB		NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"updated_at"	TIMESTAMP		NOT NULL,
	"deleted_at"	TIMESTAMP		NULL
);

CREATE TABLE "realtime_activity_session" (
	"id"	UUID		NOT NULL,
	"guardian_id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"activity_context_id"	UUID		NULL,
	"activity_type"	VARCHAR(30)		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"current_practice_stage"	VARCHAR(50)		NULL,
	"start_idempotency_key"	VARCHAR(100)		NOT NULL,
	"voice_client_id"	VARCHAR(100)		NULL,
	"gpt_live_session_id"	VARCHAR(255)		NULL,
	"consent_version"	INT		NOT NULL,
	"consent_checked_at"	TIMESTAMP		NOT NULL,
	"data_collection_allowed"	BOOLEAN		NOT NULL,
	"transcript_storage_allowed"	BOOLEAN		NOT NULL,
	"child_context_json"	JSONB		NULL,
	"intro_content_version_id"	UUID		NULL,
	"closing_content_version_id"	UUID		NULL,
	"end_reason"	VARCHAR(50)		NULL,
	"end_detail"	TEXT		NULL,
	"termination_requested_at"	TIMESTAMP		NULL,
	"output_stopped_at"	TIMESTAMP		NULL,
	"transport_closed_at"	TIMESTAMP		NULL,
	"provider_closed_at"	TIMESTAMP		NULL,
	"started_at"	TIMESTAMP		NULL,
	"ended_at"	TIMESTAMP		NULL,
	"row_version"	BIGINT		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"updated_at"	TIMESTAMP		NOT NULL,
	"practice_goal_selection_id"	UUID		NULL
);

COMMENT ON COLUMN "realtime_activity_session"."practice_goal_selection_id" IS 'External UUID -> core.practice_goal_selection.id; NO DB FK. NULL for conversation; snapshot start goal for practice even with zero attempts.';

CREATE TABLE "core_result_job" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"snapshot_id"	UUID		NOT NULL,
	"job_type"	VARCHAR(30)		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"idempotency_key"	VARCHAR(100)		NOT NULL,
	"requested_by"	VARCHAR(30)		NOT NULL,
	"attempt_count"	INT		NOT NULL,
	"last_error"	TEXT		NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"updated_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_approved_content_version" (
	"id"	UUID		NOT NULL,
	"content_id"	UUID		NOT NULL,
	"version"	INT		NOT NULL,
	"content_text"	TEXT		NOT NULL,
	"audio_path"	VARCHAR(500)		NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_outbox_event" (
	"event_id"	UUID		NOT NULL,
	"event_type"	VARCHAR(100)		NOT NULL,
	"aggregate_type"	VARCHAR(100)		NULL,
	"aggregate_id"	UUID		NULL,
	"payload_json"	JSONB		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"retry_count"	INT		NOT NULL,
	"available_at"	TIMESTAMP		NOT NULL,
	"published_at"	TIMESTAMP		NULL,
	"last_error"	TEXT		NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_stop_expression" (
	"id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"expression_suffix"	VARCHAR(10)		NOT NULL,
	"normalized_suffix"	VARCHAR(20)		NOT NULL,
	"sort_order"	INT		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"updated_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "realtime_scenario_exposure" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"scenario_id"	UUID		NOT NULL,
	"sequence_no"	INT		NOT NULL,
	"exposure_type"	VARCHAR(30)		NOT NULL,
	"scenario_snapshot_json"	JSONB		NOT NULL,
	"exposed_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_result" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"result_job_id"	UUID		NULL,
	"source_snapshot_id"	UUID		NOT NULL,
	"version"	INT		NOT NULL,
	"result_type"	VARCHAR(40)		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"content_json"	JSONB		NOT NULL,
	"model_version"	VARCHAR(100)		NULL,
	"prompt_version"	VARCHAR(100)		NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"invalidated_at"	TIMESTAMP		NULL,
	"invalidation_reason"	VARCHAR(100)		NULL
);

COMMENT ON COLUMN "core_result"."version" IS 'REQUIRED DB UNIQUE(session_id, version), version > 0. Allocate under Core session lock only when a deliverable result is complete, including FAILURE_REPORT. Technical retries create attempts only.';

CREATE TABLE "core_goal_recommendation" (
	"id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"structured_experience_id"	UUID		NOT NULL,
	"source_session_id"	UUID		NOT NULL,
	"behavior_key"	VARCHAR(150)		NOT NULL,
	"reason_text"	TEXT		NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"invalidated_at"	TIMESTAMP		NULL,
	"invalidation_reason"	VARCHAR(100)		NULL
);

CREATE TABLE "realtime_outbox_event" (
	"event_id"	UUID		NOT NULL,
	"event_type"	VARCHAR(100)		NOT NULL,
	"aggregate_type"	VARCHAR(100)		NULL,
	"aggregate_id"	UUID		NULL,
	"payload_json"	JSONB		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"retry_count"	INT		NOT NULL,
	"available_at"	TIMESTAMP		NOT NULL,
	"published_at"	TIMESTAMP		NULL,
	"last_error"	TEXT		NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_report" (
	"id"	UUID		NOT NULL,
	"guardian_id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"content_json"	JSONB		NOT NULL,
	"generated_at"	TIMESTAMP		NOT NULL,
	"invalidated_at"	TIMESTAMP		NULL,
	"invalidation_reason"	VARCHAR(100)		NULL,
	"deleted_at"	TIMESTAMP		NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "realtime_conversation_turn" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"turn_no"	INT		NOT NULL,
	"turn_type"	VARCHAR(50)		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"started_at"	TIMESTAMP		NOT NULL,
	"ended_at"	TIMESTAMP		NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "realtime_session_event" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"event_type"	VARCHAR(100)		NOT NULL,
	"op_id"	VARCHAR(100)		NULL,
	"transcript_id"	UUID		NULL,
	"payload_json"	JSONB		NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_spring_session_attributes" (
	"attribute_name"	VARCHAR(200)		NOT NULL,
	"session_primary_id"	CHAR(36)		NOT NULL,
	"attribute_bytes"	BYTEA		NOT NULL
);

CREATE TABLE "realtime_transcript" (
	"id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"turn_id"	UUID		NULL,
	"sequence_no"	INT		NOT NULL,
	"speaker"	VARCHAR(30)		NOT NULL,
	"text"	TEXT		NOT NULL,
	"assembly_status"	VARCHAR(30)		NOT NULL,
	"review_needed"	BOOLEAN		NOT NULL,
	"started_at"	TIMESTAMP		NULL,
	"ended_at"	TIMESTAMP		NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"deleted_at"	TIMESTAMP		NULL
);

CREATE TABLE "core_social_account" (
	"id"	UUID		NOT NULL,
	"guardian_id"	UUID		NOT NULL,
	"provider"	VARCHAR(20)		NOT NULL,
	"provider_user_id"	VARCHAR(255)		NOT NULL,
	"provider_email"	VARCHAR(255)		NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"updated_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_consent" (
	"id"	UUID		NOT NULL,
	"actor_guardian_id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"consent_type"	VARCHAR(50)		NOT NULL,
	"consent_status"	VARCHAR(30)		NOT NULL,
	"consent_version"	INT		NOT NULL,
	"policy_version"	VARCHAR(50)		NULL,
	"effective_from"	TIMESTAMP		NOT NULL,
	"effective_to"	TIMESTAMP		NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_analytics_event" (
	"event_id"	UUID		NOT NULL,
	"event_name"	VARCHAR(100)		NOT NULL,
	"event_time"	TIMESTAMP		NOT NULL,
	"session_id"	UUID		NULL,
	"activity_type"	VARCHAR(30)		NULL,
	"target_skill_key"	VARCHAR(150)		NULL,
	"trigger_source"	VARCHAR(30)		NULL,
	"app_version"	VARCHAR(100)		NULL,
	"model_version"	VARCHAR(100)		NULL,
	"properties_json"	JSONB		NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"guardian_id"	UUID		NULL,
	"child_id"	UUID		NULL,
	"scenario_id"	UUID		NULL
);

COMMENT ON COLUMN "core_analytics_event"."properties_json" IS 'NO sensitive raw text, transcripts, audio, prompts or child profile in properties. Store approved operational metrics only. Realtime analytics arrives via Outbox -> Core Inbox; event_id PK deduplicates.';

COMMENT ON COLUMN "core_analytics_event"."guardian_id" IS 'Core-internal optional FK. No raw personal data in analytics properties.';

COMMENT ON COLUMN "core_analytics_event"."child_id" IS 'Core-internal optional FK. Child records are soft-deleted; analytics must not retain raw child content.';

COMMENT ON COLUMN "core_analytics_event"."scenario_id" IS 'Core-internal optional FK to immutable scenario. Deleted evidence blocks sensitive reuse even when analytics metadata is retained.';

CREATE TABLE "realtime_cue_delivery" (
	"id"	UUID		NOT NULL,
	"attempt_id"	UUID		NOT NULL,
	"source_type"	VARCHAR(30)		NOT NULL,
	"delivered_text"	TEXT		NOT NULL,
	"cue_level"	VARCHAR(30)		NULL,
	"sequence_order"	INT		NOT NULL,
	"model_version"	VARCHAR(100)		NULL,
	"prompt_version"	VARCHAR(100)		NULL,
	"given_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_guardian" (
	"id"	UUID		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"updated_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_session_result_state" (
	"session_id"	UUID		NOT NULL,
	"current_result_id"	UUID		NULL,
	"updated_at"	TIMESTAMP		NOT NULL
);

COMMENT ON COLUMN "core_session_result_state"."current_result_id" IS 'Core-owned FK to result.id. On invalidation, atomically select highest VALID version for SAME session_id; if none, set NULL. Never expose blocked evidence, including older versions. Lock per session for concurrent generation/invalidation.';

CREATE TABLE "realtime_inbox_event" (
	"event_id"	UUID		NOT NULL,
	"source_service"	VARCHAR(50)		NOT NULL,
	"event_type"	VARCHAR(100)		NOT NULL,
	"payload_json"	JSONB		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"retry_count"	INT		NOT NULL,
	"received_at"	TIMESTAMP		NOT NULL,
	"processed_at"	TIMESTAMP		NULL,
	"last_error"	TEXT		NULL
);

CREATE TABLE "realtime_evaluation" (
	"id"	UUID		NOT NULL,
	"response_id"	UUID		NOT NULL,
	"label"	VARCHAR(30)		NOT NULL,
	"review_needed"	BOOLEAN		NOT NULL,
	"reason"	TEXT		NULL,
	"raw_json"	JSONB		NULL,
	"model_version"	VARCHAR(100)		NULL,
	"prompt_version"	VARCHAR(100)		NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"invalidated_at"	TIMESTAMP		NULL,
	"invalidation_reason"	VARCHAR(100)		NULL,
	"status"	VARCHAR(30)	DEFAULT 'VALID'	NOT NULL
);

COMMENT ON COLUMN "realtime_evaluation"."status" IS 'VALID / INVALIDATED. Invalidated evaluation is excluded from results and AI reuse.';

CREATE TABLE "core_data_deletion_request" (
	"id"	UUID		NOT NULL,
	"guardian_id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"session_id"	UUID		NULL,
	"target_type"	VARCHAR(50)		NOT NULL,
	"target_id"	UUID		NULL,
	"requested_types_json"	JSONB		NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"idempotency_key"	VARCHAR(100)		NOT NULL,
	"blocked_at"	TIMESTAMP		NOT NULL,
	"requested_at"	TIMESTAMP		NOT NULL,
	"completed_at"	TIMESTAMP		NULL,
	"last_error"	TEXT		NULL
);

CREATE TABLE "core_inbox_event" (
	"event_id"	UUID		NOT NULL,
	"source_service"	VARCHAR(50)		NOT NULL,
	"event_type"	VARCHAR(100)		NOT NULL,
	"payload_json"	JSONB		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"retry_count"	INT		NOT NULL,
	"received_at"	TIMESTAMP		NOT NULL,
	"processed_at"	TIMESTAMP		NULL,
	"last_error"	TEXT		NULL
);

CREATE TABLE "core_approved_content" (
	"id"	UUID		NOT NULL,
	"content_code"	VARCHAR(100)		NOT NULL,
	"content_type"	VARCHAR(50)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_report_activity" (
	"id"	UUID		NOT NULL,
	"report_id"	UUID		NOT NULL,
	"session_id"	UUID		NOT NULL,
	"result_id"	UUID		NOT NULL,
	"included_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_support_item" (
	"id"	UUID		NOT NULL,
	"behavior_key"	VARCHAR(150)		NOT NULL,
	"condition_text"	TEXT		NULL,
	"content_text"	TEXT		NOT NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"updated_at"	TIMESTAMP		NOT NULL
);

CREATE TABLE "core_activity_context" (
	"id"	UUID		NOT NULL,
	"guardian_id"	UUID		NOT NULL,
	"child_id"	UUID		NOT NULL,
	"session_id"	UUID		NULL,
	"purpose"	VARCHAR(50)		NOT NULL,
	"known_content"	TEXT		NULL,
	"situation_type"	VARCHAR(100)		NULL,
	"source_type"	VARCHAR(50)		NULL,
	"question"	TEXT		NULL,
	"status"	VARCHAR(30)		NOT NULL,
	"created_at"	TIMESTAMP		NOT NULL,
	"updated_at"	TIMESTAMP		NOT NULL,
	"deleted_at"	TIMESTAMP		NULL
);

COMMENT ON COLUMN "core_activity_context"."deleted_at" IS 'PROPOSAL: guardian-provided raw context is soft-deleted; block reads/AI reuse immediately and invalidate dependent derived data.';

ALTER TABLE "core_result_job_attempt" ADD CONSTRAINT "PK_CORE_RESULT_JOB_ATTEMPT" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_recommendation_evidence" ADD CONSTRAINT "PK_CORE_RECOMMENDATION_EVIDENCE" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_structured_experience" ADD CONSTRAINT "PK_CORE_STRUCTURED_EXPERIENCE" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_result_input_snapshot" ADD CONSTRAINT "PK_REALTIME_RESULT_INPUT_SNAPSHOT" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_session_stop_expression" ADD CONSTRAINT "PK_REALTIME_SESSION_STOP_EXPRESSION" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_scenario" ADD CONSTRAINT "PK_CORE_SCENARIO" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_practice_attempt" ADD CONSTRAINT "PK_REALTIME_PRACTICE_ATTEMPT" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_audio_recording" ADD CONSTRAINT "PK_REALTIME_AUDIO_RECORDING" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_spring_session" ADD CONSTRAINT "PK_CORE_SPRING_SESSION" PRIMARY KEY (
	"primary_id"
);

ALTER TABLE "core_practice_goal_selection" ADD CONSTRAINT "PK_CORE_PRACTICE_GOAL_SELECTION" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_practice_response" ADD CONSTRAINT "PK_REALTIME_PRACTICE_RESPONSE" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_child" ADD CONSTRAINT "PK_CORE_CHILD" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_activity_session" ADD CONSTRAINT "PK_REALTIME_ACTIVITY_SESSION" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_result_job" ADD CONSTRAINT "PK_CORE_RESULT_JOB" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_approved_content_version" ADD CONSTRAINT "PK_CORE_APPROVED_CONTENT_VERSION" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_outbox_event" ADD CONSTRAINT "PK_CORE_OUTBOX_EVENT" PRIMARY KEY (
	"event_id"
);

ALTER TABLE "core_stop_expression" ADD CONSTRAINT "PK_CORE_STOP_EXPRESSION" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_scenario_exposure" ADD CONSTRAINT "PK_REALTIME_SCENARIO_EXPOSURE" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_result" ADD CONSTRAINT "PK_CORE_RESULT" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_goal_recommendation" ADD CONSTRAINT "PK_CORE_GOAL_RECOMMENDATION" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_outbox_event" ADD CONSTRAINT "PK_REALTIME_OUTBOX_EVENT" PRIMARY KEY (
	"event_id"
);

ALTER TABLE "core_report" ADD CONSTRAINT "PK_CORE_REPORT" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_conversation_turn" ADD CONSTRAINT "PK_REALTIME_CONVERSATION_TURN" PRIMARY KEY (
	"id"
);

ALTER TABLE "realtime_session_event" ADD CONSTRAINT "PK_REALTIME_SESSION_EVENT" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_spring_session_attributes" ADD CONSTRAINT "PK_CORE_SPRING_SESSION_ATTRIBUTES" PRIMARY KEY (
	"attribute_name",
	"session_primary_id"
);

ALTER TABLE "realtime_transcript" ADD CONSTRAINT "PK_REALTIME_TRANSCRIPT" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_social_account" ADD CONSTRAINT "PK_CORE_SOCIAL_ACCOUNT" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_consent" ADD CONSTRAINT "PK_CORE_CONSENT" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_analytics_event" ADD CONSTRAINT "PK_CORE_ANALYTICS_EVENT" PRIMARY KEY (
	"event_id"
);

ALTER TABLE "realtime_cue_delivery" ADD CONSTRAINT "PK_REALTIME_CUE_DELIVERY" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_guardian" ADD CONSTRAINT "PK_CORE_GUARDIAN" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_session_result_state" ADD CONSTRAINT "PK_CORE_SESSION_RESULT_STATE" PRIMARY KEY (
	"session_id"
);

ALTER TABLE "realtime_inbox_event" ADD CONSTRAINT "PK_REALTIME_INBOX_EVENT" PRIMARY KEY (
	"event_id"
);

ALTER TABLE "realtime_evaluation" ADD CONSTRAINT "PK_REALTIME_EVALUATION" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_data_deletion_request" ADD CONSTRAINT "PK_CORE_DATA_DELETION_REQUEST" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_inbox_event" ADD CONSTRAINT "PK_CORE_INBOX_EVENT" PRIMARY KEY (
	"event_id"
);

ALTER TABLE "core_approved_content" ADD CONSTRAINT "PK_CORE_APPROVED_CONTENT" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_report_activity" ADD CONSTRAINT "PK_CORE_REPORT_ACTIVITY" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_support_item" ADD CONSTRAINT "PK_CORE_SUPPORT_ITEM" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_activity_context" ADD CONSTRAINT "PK_CORE_ACTIVITY_CONTEXT" PRIMARY KEY (
	"id"
);

ALTER TABLE "core_spring_session_attributes" ADD CONSTRAINT "FK_core_spring_session_TO_core_spring_session_attributes_1" FOREIGN KEY (
	"session_primary_id"
)
REFERENCES "core_spring_session" (
	"primary_id"
);

