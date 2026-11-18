-- Optional creator approval; no invitation secrets, passwords or media keys.
ALTER TABLE conferences ADD COLUMN approval_required BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE conference_join_requests (
    id UUID PRIMARY KEY,
    conference_id UUID NOT NULL REFERENCES conferences(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    session_id UUID NOT NULL,
    access_revision BIGINT NOT NULL CHECK(access_revision>0),
    status TEXT NOT NULL CHECK(status IN ('pending','approved','consumed','denied','cancelled','expired')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    consumed_admission_id UUID,
    CHECK ((status='consumed')=(consumed_admission_id IS NOT NULL))
);
CREATE UNIQUE INDEX conference_one_waiting_account ON conference_join_requests(conference_id,user_id) WHERE status='pending';
CREATE INDEX conference_join_request_owner ON conference_join_requests(conference_id,created_at,id) WHERE status='pending';
CREATE INDEX conference_join_request_cleanup ON conference_join_requests(expires_at);
CREATE INDEX conference_join_request_grant ON conference_join_requests(conference_id,user_id,device_id) WHERE status IN ('approved','consumed');

CREATE TABLE conference_join_cooldowns (
    conference_id UUID NOT NULL REFERENCES conferences(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    denial_level SMALLINT NOT NULL CHECK(denial_level BETWEEN 1 AND 2),
    blocked_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(conference_id,user_id)
);
-- A cancellation may win the race against an authorized request's password KDF.
CREATE TABLE conference_join_cancellations (
    conference_id UUID NOT NULL REFERENCES conferences(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    session_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(conference_id,request_id,user_id,device_id,session_id)
);
CREATE INDEX conference_join_cancellation_cleanup ON conference_join_cancellations(expires_at);
