-- K: FCM push reliability. Extend push_tokens with freshness/invalidation state
-- and add a durable outbox so a backend crash after a call/message commit never
-- silently drops the wake push. Outbox rows only ever carry routing metadata
-- (event type, ids, sequence, target device) - NEVER E2E plaintext, Signal
-- envelopes, attachment keys, SDP, ICE, or TURN credentials.

ALTER TABLE push_tokens
    ADD COLUMN last_success_at TIMESTAMPTZ,
    ADD COLUMN invalidated_at   TIMESTAMPTZ,
    ADD COLUMN last_error_code  TEXT;

-- One ACTIVE registration per device is already enforced by the existing
-- `push_tokens.device_id` UNIQUE constraint; `invalidated_at` marks a row dead
-- until a re-registration (upsert) writes a fresh token. `provider` stays the
-- only supported value ('fcm') for now.

CREATE TABLE push_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      TEXT NOT NULL,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id       UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    last_error_code TEXT
);

-- Worker scans due (undelivered) rows by next_attempt_at; the partial index
-- keeps the hot scan small as delivered rows accumulate.
CREATE INDEX push_outbox_due_idx ON push_outbox (next_attempt_at) WHERE delivered_at IS NULL;
