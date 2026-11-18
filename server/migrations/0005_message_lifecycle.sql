-- Этап I: message lifecycle events (reply/edit/delete/reaction) as rows in
-- the existing `messages` table - no new endpoints, no new WS event types,
-- no new /sync path (см. Этап I architecture decision). The server never
-- sees plaintext edited text/reaction emoji/reply quote/forwarded content -
-- all of that lives inside the existing per-device Signal ciphertext
-- (message_envelopes), completely unchanged by this migration.

-- `kind` is deliberately NOT named `message_type` - that name is already
-- taken by `message_envelopes.message_type` (the Signal PreKey/Whisper
-- envelope type, an unrelated concept).
ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'content';
ALTER TABLE messages ADD CONSTRAINT messages_kind_check CHECK (kind IN ('content', 'edit', 'delete', 'reaction'));

-- Dual-purpose by `kind`: for 'content' this is an optional reply-to
-- reference; for 'edit'/'delete'/'reaction' this is the required lifecycle
-- target. Never both meanings on the same row (mutually exclusive by kind).
ALTER TABLE messages ADD COLUMN target_message_id UUID REFERENCES messages (id);

CREATE INDEX messages_target_message_id_idx ON messages (target_message_id) WHERE target_message_id IS NOT NULL;
