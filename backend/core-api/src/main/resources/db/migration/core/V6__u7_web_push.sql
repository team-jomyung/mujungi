-- U7 Notification: 새 표만 추가한다. U6 V5 뒤에 병합하며 시각은 앱 시계가 넣는다.
CREATE TABLE core.core_push_subscription (
    id uuid PRIMARY KEY,
    guardian_id uuid NOT NULL REFERENCES core.core_guardian(id) ON DELETE RESTRICT,
    endpoint text NOT NULL UNIQUE,
    p256dh text NOT NULL,
    auth text NOT NULL,
    platform varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz,
    CHECK (octet_length(endpoint) BETWEEN 1 AND 2048 AND lower(left(endpoint, 8)) = 'https://'),
    CHECK (btrim(p256dh) <> ''),
    CHECK (btrim(auth) <> ''),
    CHECK (btrim(platform) <> '')
);
CREATE INDEX core_push_subscription_active_guardian_idx
    ON core.core_push_subscription(guardian_id) WHERE revoked_at IS NULL;

CREATE TABLE core.core_push_dispatch (
    id uuid PRIMARY KEY,
    guardian_id uuid NOT NULL REFERENCES core.core_guardian(id) ON DELETE RESTRICT,
    session_id uuid NOT NULL,
    event_type varchar(40) NOT NULL,
    event_key text NOT NULL UNIQUE,
    status varchar(20) NOT NULL,
    attempt_count integer NOT NULL,
    last_error_code varchar(80),
    requested_at timestamptz NOT NULL,
    sent_at timestamptz,
    activity_type varchar(20) NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    claim_id uuid,
    lease_until timestamptz,
    recipient_progress jsonb NOT NULL,
    CHECK (event_type IN ('VOICE_PROBLEM', 'DEVICE_STATUS_UNKNOWN', 'ENDED_BY_CONNECTION', 'ACTIVITY_COMPLETED')),
    CHECK (status IN ('REQUESTED', 'SENT', 'FAILED', 'SKIPPED')),
    CHECK (attempt_count >= 0),
    CHECK (btrim(event_key) <> ''),
    CHECK (activity_type IN ('FREE_TALK', 'PRACTICE')),
    CHECK ((claim_id IS NULL) = (lease_until IS NULL)),
    CHECK (jsonb_typeof(recipient_progress) = 'object')
);
CREATE INDEX core_push_dispatch_guardian_idx ON core.core_push_dispatch(guardian_id);
CREATE INDEX core_push_dispatch_due_idx
    ON core.core_push_dispatch(next_attempt_at, requested_at) WHERE status = 'REQUESTED';
