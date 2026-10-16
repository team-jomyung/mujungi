-- U15 BR2·BR4·BR5: 성공 원장과 미완료 외부 쓰기를 구분한다.
CREATE TABLE core.core_graph_version (
    version varchar(100) PRIMARY KEY,
    source_digest varchar(64) NOT NULL,
    loaded_at timestamptz NOT NULL
);
CREATE TABLE core.core_child_graph_state (
    child_id uuid PRIMARY KEY REFERENCES core.core_child(id) ON DELETE RESTRICT,
    guardian_id uuid NOT NULL REFERENCES core.core_guardian(id) ON DELETE RESTRICT,
    base_version varchar(100),
    next_generated_number bigint NOT NULL DEFAULT 1 CHECK (next_generated_number > 0),
    graph_may_exist boolean NOT NULL DEFAULT false,
    initialization_transaction_id varchar(200),
    state varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','DELETING'))
);
CREATE INDEX core_child_graph_guardian_idx ON core.core_child_graph_state(guardian_id);
CREATE TABLE core.core_seed_generation_operation (
    child_id uuid NOT NULL REFERENCES core.core_child_graph_state(child_id) ON DELETE RESTRICT,
    activity_id uuid NOT NULL,
    candidate_ref varchar(160) NOT NULL,
    seed_id varchar(80) NOT NULL,
    state varchar(30) NOT NULL CHECK (state IN ('RESERVED','PROJECTING','COMMITTED','REUSED','NONE','CLEANUP_REQUIRED')),
    reused_seed_id varchar(80),
    graph_transaction_id varchar(200),
    validated_seed jsonb,
    model varchar(100),
    prompt_version varchar(100),
    check_codes jsonb NOT NULL DEFAULT '[]'::jsonb,
    deadline timestamptz NOT NULL,
    PRIMARY KEY (child_id,candidate_ref),
    UNIQUE (child_id,seed_id),
    CHECK (state <> 'REUSED' OR reused_seed_id IS NOT NULL),
    CHECK (state NOT IN ('PROJECTING','CLEANUP_REQUIRED') OR (validated_seed IS NOT NULL AND graph_transaction_id IS NOT NULL))
);
CREATE INDEX core_seed_operation_activity_idx ON core.core_seed_generation_operation(child_id,activity_id,state);
CREATE TABLE core.generated_seed (
    child_id uuid NOT NULL REFERENCES core.core_child_graph_state(child_id) ON DELETE RESTRICT,
    seed_id varchar(80) NOT NULL,
    activity_id uuid NOT NULL,
    candidate_ref varchar(160) NOT NULL,
    seed jsonb NOT NULL,
    model varchar(100) NOT NULL,
    prompt_version varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(child_id,seed_id),
    UNIQUE(child_id,candidate_ref)
);
CREATE TABLE core.core_graph_deleted_source (
    child_id uuid NOT NULL REFERENCES core.core_child_graph_state(child_id) ON DELETE RESTRICT,
    activity_id uuid NOT NULL,
    PRIMARY KEY(child_id,activity_id)
);
-- 아동 행 삭제 전후 재진입을 막는 비참조 표식. 계정 삭제 참가자가 마지막에 정리한다.
CREATE TABLE core.core_graph_deleted_child (
    child_id uuid PRIMARY KEY,
    guardian_id uuid NOT NULL
);
