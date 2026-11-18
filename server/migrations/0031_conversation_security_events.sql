-- Checkpoint B: conversation security events (contact-visible device/identity
-- changes shown inside an existing chat). Additive only - extends the existing
-- security_events table with first-class scoping columns and a durable
-- idempotency key. Every existing row is preserved untouched; the new `scope`
-- column defaults to 'account' so legacy owner-facing events stay exactly as
-- they were.

ALTER TABLE security_events ADD COLUMN scope TEXT NOT NULL DEFAULT 'account';
ALTER TABLE security_events ADD COLUMN chat_id UUID REFERENCES chats (id) ON DELETE CASCADE;
ALTER TABLE security_events ADD COLUMN subject_user_id UUID REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE security_events ADD COLUMN event_key TEXT;

ALTER TABLE security_events ADD CONSTRAINT security_events_scope_check
    CHECK (scope IN ('account', 'conversation'));

-- Idempotency: the same transition for the same recipient must produce exactly
-- one row. Partial unique index so legacy account rows (event_key IS NULL)
-- never conflict with each other or with conversation rows.
CREATE UNIQUE INDEX security_events_recipient_event_key
    ON security_events (user_id, event_key) WHERE event_key IS NOT NULL;

-- Conversation events are queried per chat in later checkpoints.
CREATE INDEX security_events_chat_idx ON security_events (chat_id, id DESC);
