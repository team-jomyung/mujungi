-- U5b 시작 준비. V5(U6), V6(U7) 다음 번호이며 기존 표는 바꾸지 않는다.
CREATE TABLE core.core_practice_preparation (
 session_id uuid PRIMARY KEY,
 guardian_id uuid NOT NULL REFERENCES core.core_guardian(id) ON DELETE RESTRICT,
 child_id uuid NOT NULL REFERENCES core.core_child(id) ON DELETE RESTRICT,
 idempotency_key uuid NOT NULL,
 request_hash varchar(64) NOT NULL,
 goals_json jsonb NOT NULL CHECK (jsonb_typeof(goals_json) = 'array'),
 status varchar(20) NOT NULL CHECK (status IN ('PREPARING','READY','FAILED')),
 claim_id uuid NOT NULL,
 lease_until timestamptz NOT NULL,
 first_scenario_id uuid,
 exposure_reconciled_at timestamptz,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL,
 UNIQUE (guardian_id,idempotency_key),
 CHECK ((status = 'READY') = (first_scenario_id IS NOT NULL))
);
CREATE INDEX core_practice_preparation_unreconciled_idx
 ON core.core_practice_preparation(status, exposure_reconciled_at, created_at);
