-- U6 FR1~FR14: 활동 결과와 상담용 리포트. 기존 표의 제약은 변경하지 않는다.
CREATE TABLE core.core_result_job (
 id uuid PRIMARY KEY, job_type varchar(30) NOT NULL CHECK(job_type IN ('SESSION_RESULT','REPORT')),
 subject_id uuid NOT NULL, guardian_id uuid NOT NULL, idempotency_key varchar(200) NOT NULL UNIQUE,
 trigger_type varchar(30) NOT NULL CHECK(trigger_type IN ('ACTIVITY_RECORDED','RECONCILE','RETRY','REPORT_REQUEST')),
 status varchar(20) NOT NULL CHECK(status IN ('REQUESTED','RUNNING','DONE','FAILED')),
 attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 3),
 next_attempt_at timestamptz NOT NULL, lease_until timestamptz, deadline_at timestamptz,
 last_error_code varchar(30) CHECK(last_error_code IN ('PROVIDER_UNAVAILABLE','PROVIDER_ERROR','TIMEOUT','SCHEMA_INVALID',
 'DEADLINE_EXCEEDED','SERVER_RESTART','ACCOUNT_DELETING','REPORT_DISCARDED','INTERNAL')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, finished_at timestamptz
);
CREATE INDEX core_result_job_due_idx ON core.core_result_job(status,next_attempt_at);
CREATE UNIQUE INDEX core_result_job_subject_active_idx ON core.core_result_job(subject_id)
 WHERE status IN ('REQUESTED','RUNNING');
CREATE TABLE core.core_result_job_attempt (
 id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES core.core_result_job(id),
 attempt_no integer NOT NULL CHECK(attempt_no BETWEEN 1 AND 3), started_at timestamptz NOT NULL,
 ended_at timestamptz, outcome varchar(20) CHECK(outcome IN ('SUCCEEDED','FAILED','ABANDONED')),
 error_code varchar(30) CHECK(error_code IN ('PROVIDER_UNAVAILABLE','PROVIDER_ERROR','TIMEOUT','SCHEMA_INVALID',
 'DEADLINE_EXCEEDED','SERVER_RESTART','ACCOUNT_DELETING','REPORT_DISCARDED','INTERNAL')),
 UNIQUE(job_id,attempt_no)
);
CREATE TABLE core.core_activity_result (
 id uuid PRIMARY KEY, session_id uuid NOT NULL, version integer NOT NULL CHECK(version>0),
 job_id uuid NOT NULL REFERENCES core.core_result_job(id),
 result_level varchar(20) NOT NULL CHECK(result_level IN ('FULL','PARTIAL','INSUFFICIENT')),
 sentences_json jsonb NOT NULL, help_json jsonb NOT NULL, tips_json jsonb NOT NULL,
 no_tip_reason varchar(30) CHECK(no_tip_reason IN ('NEED_MORE_OBSERVATION')),
 matching_log_json jsonb NOT NULL, pending_candidates_json jsonb NOT NULL,
 candidates_registered_at timestamptz, created_at timestamptz NOT NULL, invalidated_at timestamptz,
 UNIQUE(session_id,version)
);
CREATE TABLE core.core_session_result_state (
 session_id uuid PRIMARY KEY, guardian_id uuid NOT NULL, child_id uuid NOT NULL,
 activity_type varchar(20) NOT NULL CHECK(activity_type IN ('FREE_TALK','PRACTICE')),
 eligibility varchar(20) NOT NULL CHECK(eligibility IN ('ELIGIBLE','NOT_ELIGIBLE')),
 current_job_id uuid REFERENCES core.core_result_job(id),
 job_status varchar(20) NOT NULL CHECK(job_status IN ('NONE','REQUESTED','RUNNING','DONE','FAILED')),
 current_result_id uuid REFERENCES core.core_activity_result(id), run_ordinal integer NOT NULL CHECK(run_ordinal>=0),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX core_session_result_guardian_idx ON core.core_session_result_state(guardian_id);
CREATE TABLE core.core_structured_experience (
 id uuid PRIMARY KEY, result_id uuid NOT NULL UNIQUE REFERENCES core.core_activity_result(id),
 session_id uuid NOT NULL, content_json jsonb NOT NULL, created_at timestamptz NOT NULL
);
CREATE TABLE core.core_scene_judgment (
 id uuid PRIMARY KEY, result_id uuid NOT NULL REFERENCES core.core_activity_result(id), session_id uuid NOT NULL,
 goal_selection_id uuid NOT NULL, goal_order integer NOT NULL CHECK(goal_order>0),
 scene_no integer NOT NULL CHECK(scene_no IN (1,2)),
 exposure_type varchar(20) NOT NULL CHECK(exposure_type IN ('ORIGINAL','NEW_SCENARIO')), scenario_id uuid,
 value varchar(20) CHECK(value IN ('functional','partial','none','uncertain')),
 timing varchar(20) CHECK(timing IN ('IMMEDIATE','AFTER_HELP')), rationale text,
 live_label varchar(20) CHECK(live_label IN ('functional','partial','none','uncertain','NOT_PERFORMED')),
 differs_from_live boolean NOT NULL, not_done_reason varchar(30)
 CHECK(not_done_reason IN ('TIME_OVER','SCENARIO_FAILED','ACTIVITY_ENDED','NO_RESPONSE')),
 scene_chip varchar(30) NOT NULL CHECK(scene_chip IN ('DONE_IMMEDIATE','DONE_AFTER_HELP','NEXT_TIME','ON_HOLD','NOT_DONE')),
 created_at timestamptz NOT NULL, UNIQUE(result_id,goal_selection_id,scene_no),
 CHECK((value IS NULL)=(scene_chip='NOT_DONE') AND (value IS NULL)=(not_done_reason IS NOT NULL))
);
CREATE TABLE core.core_report (
 id uuid PRIMARY KEY, guardian_id uuid NOT NULL, child_id uuid NOT NULL,
 idempotency_key varchar(200) NOT NULL, request_hash varchar(64) NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('GENERATING','READY','FAILED','SAVED','DISCARDED')),
 period_from date NOT NULL, period_to date NOT NULL CHECK(period_to>=period_from),
 activity_count integer CHECK(activity_count>=1), citation_count integer CHECK(citation_count>=0),
 level varchar(20) CHECK(level IN ('FULL','PARTIAL','INSUFFICIENT')),
 job_id uuid REFERENCES core.core_result_job(id), child_display_name text, child_age integer,
 created_at timestamptz NOT NULL, ready_at timestamptz, saved_at timestamptz, discarded_at timestamptz,
 UNIQUE(guardian_id,idempotency_key)
);
CREATE INDEX core_report_guardian_idx ON core.core_report(guardian_id,status);
CREATE TABLE core.core_report_activity (
 report_id uuid NOT NULL REFERENCES core.core_report(id), session_id uuid NOT NULL, activity_date date NOT NULL,
 activity_type varchar(20) NOT NULL CHECK(activity_type IN ('FREE_TALK','PRACTICE')),
 result_status_at_build varchar(20) CHECK(result_status_at_build IN ('NONE','REQUESTED','RUNNING','DONE','FAILED')),
 PRIMARY KEY(report_id,session_id)
);
CREATE TABLE core.core_report_section (
 report_id uuid NOT NULL REFERENCES core.core_report(id), section_no integer NOT NULL CHECK(section_no BETWEEN 1 AND 10),
 title text NOT NULL, text text NOT NULL, missing boolean NOT NULL, templated boolean NOT NULL,
 PRIMARY KEY(report_id,section_no)
);
CREATE TABLE core.core_report_citation (
 id uuid PRIMARY KEY, report_id uuid NOT NULL REFERENCES core.core_report(id),
 section_no integer NOT NULL CHECK(section_no BETWEEN 1 AND 10), session_id uuid NOT NULL,
 utterance_seq bigint CHECK(utterance_seq>=1), quote text, spoken_at timestamptz,
 source varchar(20) NOT NULL CHECK(source IN ('TRANSCRIPT','GUARDIAN_INPUT','CHILD_RECALL')),
 CHECK((source='GUARDIAN_INPUT' AND quote IS NULL) OR
       (source IN ('TRANSCRIPT','CHILD_RECALL') AND utterance_seq IS NOT NULL AND quote IS NOT NULL))
);
ALTER TABLE core.core_support_item ADD COLUMN title text;
