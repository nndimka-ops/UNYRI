-- Discovery metadata and per-device opaque Signal relay. Never plaintext keys.
ALTER TABLE conferences
    ADD COLUMN description TEXT NOT NULL DEFAULT '' CHECK(char_length(description)<=240 AND octet_length(description)<=960),
    ADD COLUMN listed BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN access_mode TEXT NOT NULL DEFAULT 'invite' CHECK(access_mode IN ('open','password','invite','invite_password')),
    ADD COLUMN password_hash TEXT,
    ADD COLUMN settings_revision BIGINT NOT NULL DEFAULT 1 CHECK(settings_revision>0),
    ADD COLUMN access_revision BIGINT NOT NULL DEFAULT 1 CHECK(access_revision>0),
    ADD CONSTRAINT conference_password_mode CHECK ((access_mode IN ('password','invite_password'))=(password_hash IS NOT NULL));
CREATE INDEX conference_directory_idx ON conferences(created_at DESC,id DESC) WHERE listed AND state='open';

ALTER TABLE conference_members
    ADD COLUMN supports_key_handoff BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN key_service_seen_at TIMESTAMPTZ,
    -- No FK: an admitted device may reconnect after mailbox TTL cleanup.
    ADD COLUMN catalog_request_id UUID;

CREATE TABLE conference_key_requests (
    id UUID PRIMARY KEY,
    conference_id UUID NOT NULL REFERENCES conferences(id) ON DELETE CASCADE,
    requester_user_id UUID NOT NULL,
    requester_device_id UUID NOT NULL,
    requester_session_id UUID NOT NULL,
    nonce BYTEA NOT NULL CHECK(octet_length(nonce)=32),
    body_fingerprint BYTEA NOT NULL CHECK(octet_length(body_fingerprint)=32),
    access_revision BIGINT NOT NULL CHECK(access_revision>0),
    expires_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','ready','consumed','cancelled','expired','revoked')),
    provider_user_id UUID,
    provider_device_id UUID,
    provider_admission_id UUID,
    assignment_revision BIGINT NOT NULL DEFAULT 0 CHECK(assignment_revision>=0),
    message_type SMALLINT CHECK(message_type IN (1,2)),
    ciphertext BYTEA CHECK(octet_length(ciphertext)<=8192),
    publication_hash BYTEA CHECK(octet_length(publication_hash)=32),
    consumed_admission_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((provider_user_id IS NULL)=(provider_device_id IS NULL) AND (provider_user_id IS NULL)=(provider_admission_id IS NULL)),
    CHECK (provider_user_id IS NULL OR assignment_revision>0),
    CHECK ((ciphertext IS NULL)=(message_type IS NULL)),
    CHECK (status<>'ready' OR (ciphertext IS NOT NULL AND publication_hash IS NOT NULL)),
    CHECK ((status='consumed')=(consumed_admission_id IS NOT NULL))
);
CREATE UNIQUE INDEX conference_one_pending_key_request ON conference_key_requests(conference_id,requester_device_id) WHERE status IN ('pending','ready');
CREATE INDEX conference_key_request_cleanup ON conference_key_requests(expires_at);
CREATE INDEX conference_key_request_provider ON conference_key_requests(conference_id,provider_user_id) WHERE status='pending';

-- Cancellation can precede request INSERT while an authorized password KDF is
-- running. No synthetic request/nonce/key envelope is manufactured for it.
CREATE TABLE conference_key_request_cancellations (
    conference_id UUID NOT NULL REFERENCES conferences(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    requester_user_id UUID NOT NULL,
    requester_device_id UUID NOT NULL,
    requester_session_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(conference_id,request_id,requester_user_id,requester_device_id,requester_session_id)
);
CREATE INDEX conference_key_request_cancellation_expiry ON conference_key_request_cancellations(expires_at);
