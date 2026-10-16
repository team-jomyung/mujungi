-- U14: additive generated goals, immutable versions and pinned selections; legacy tables remain writable.
CREATE TABLE core.core_goal_candidate (
 id uuid PRIMARY KEY, child_id uuid NOT NULL REFERENCES core.core_child(id),
 source_session_id uuid NOT NULL, source_key varchar(160) NOT NULL,
 current_version integer NOT NULL CHECK(current_version>0),
 status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','INVALIDATED')),
 created_at timestamptz NOT NULL, UNIQUE(child_id,source_session_id,source_key), UNIQUE(id,child_id)
);
CREATE TABLE core.core_goal_candidate_version (
 id uuid PRIMARY KEY, candidate_id uuid NOT NULL, child_id uuid NOT NULL,
 version integer NOT NULL CHECK(version>0), display_name varchar(12) NOT NULL CHECK(btrim(display_name)<>''),
 display_evidence varchar(40) NOT NULL CHECK(btrim(display_evidence)<>''),
 target_expression varchar(200) NOT NULL CHECK(btrim(target_expression)<>''),
 four_elements_json jsonb NOT NULL, skill_ref varchar(80) NOT NULL CHECK(btrim(skill_ref)<>''),
 reason_text varchar(500) NOT NULL CHECK(btrim(reason_text)<>''), evidence_json jsonb NOT NULL,
 structure_signature_json jsonb, created_at timestamptz NOT NULL,
 FOREIGN KEY(candidate_id,child_id) REFERENCES core.core_goal_candidate(id,child_id),
 UNIQUE(candidate_id,version), UNIQUE(id,candidate_id,child_id)
);
CREATE TABLE core.core_saved_goal (
 id uuid PRIMARY KEY, guardian_id uuid NOT NULL REFERENCES core.core_guardian(id),
 child_id uuid NOT NULL, candidate_id uuid NOT NULL, candidate_version_id uuid NOT NULL,
 snapshot_json jsonb NOT NULL, display_name varchar(12) NOT NULL,
 is_favorite boolean NOT NULL DEFAULT false, sort_order bigint NOT NULL CHECK(sort_order>0),
 status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','DELETED')),
 selected_at timestamptz NOT NULL, deleted_at timestamptz, restore_until timestamptz,
 FOREIGN KEY(candidate_version_id,candidate_id,child_id) REFERENCES core.core_goal_candidate_version(id,candidate_id,child_id),
 CHECK((status='ACTIVE' AND deleted_at IS NULL AND restore_until IS NULL) OR
       (status='DELETED' AND deleted_at IS NOT NULL AND restore_until>deleted_at))
);
CREATE UNIQUE INDEX core_saved_goal_active_candidate_idx ON core.core_saved_goal(child_id,candidate_id) WHERE status='ACTIVE';
CREATE INDEX core_saved_goal_guardian_idx ON core.core_saved_goal(guardian_id,status,sort_order);
-- Only widen existing accepted values. V1--V8 and all legacy writes remain valid.
ALTER TABLE core.core_result_job DROP CONSTRAINT core_result_job_attempt_count_check;
ALTER TABLE core.core_result_job ADD CONSTRAINT core_result_job_attempt_count_check CHECK(attempt_count BETWEEN 0 AND 4);
ALTER TABLE core.core_result_job_attempt DROP CONSTRAINT core_result_job_attempt_attempt_no_check;
ALTER TABLE core.core_result_job_attempt ADD CONSTRAINT core_result_job_attempt_attempt_no_check CHECK(attempt_no BETWEEN 1 AND 4);
ALTER TABLE core.core_scene_judgment DROP CONSTRAINT core_scene_judgment_scene_no_check;
ALTER TABLE core.core_scene_judgment ADD CONSTRAINT core_scene_judgment_scene_no_check CHECK(scene_no>0);
ALTER TABLE core.core_scene_judgment DROP CONSTRAINT core_scene_judgment_timing_check;
ALTER TABLE core.core_scene_judgment ADD CONSTRAINT core_scene_judgment_timing_check CHECK(timing IN ('IMMEDIATE','AFTER_REASK','AFTER_HELP'));
ALTER TABLE core.core_scene_judgment ADD COLUMN scene_run_id uuid;
ALTER TABLE core.core_scene_judgment ADD COLUMN flow_version varchar(80);
ALTER TABLE core.core_scene_judgment ADD COLUMN facts_json jsonb;
ALTER TABLE core.core_scene_judgment ADD COLUMN variant_skip_reason varchar(40);
