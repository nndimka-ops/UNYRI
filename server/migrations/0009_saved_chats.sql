-- M: Saved Messages - a server-synced, owner-only E2E chat with exactly one
-- member (the owner). Reuses the existing `chats`/`chat_members`/`messages`
-- machinery: plaintext never reaches the server, envelopes route to the
-- owner's own active devices, and the receipts fan-out trigger naturally
-- produces zero receipt rows (the only member is also the sender).
--
-- NOTE: `ALTER TYPE ... ADD VALUE` cannot be used in the same transaction
-- as any statement that references the new value. This migration only adds
-- the enum value + the nullable owner column; the CHECK/unique index that
-- reference 'saved' live in 0010 (a separate transaction).

ALTER TYPE chat_type ADD VALUE IF NOT EXISTS 'saved';

ALTER TABLE chats ADD COLUMN owner_user_id UUID REFERENCES users (id) ON DELETE CASCADE;

