-- Initial schema: users, devices, sessions, chats, messages, receipts, push tokens.
-- gen_random_uuid() is built into PostgreSQL core since v13 (no extension needed).

-- ---------------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------------

CREATE TYPE device_platform AS ENUM ('android', 'ios', 'desktop', 'web');
CREATE TYPE push_provider AS ENUM ('fcm');
CREATE TYPE chat_type AS ENUM ('direct', 'group');
CREATE TYPE chat_member_role AS ENUM ('member', 'admin');

-- ---------------------------------------------------------------------------
-- Shared trigger: keep `updated_at` current on UPDATE.
-- ---------------------------------------------------------------------------

CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username            TEXT NOT NULL,
    -- Case-insensitive uniqueness without the citext extension.
    username_normalized TEXT GENERATED ALWAYS AS (lower(username)) STORED,
    display_name        TEXT NOT NULL,
    avatar_url          TEXT,
    password_hash       TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT users_username_length CHECK (char_length(username) BETWEEN 3 AND 32),
    CONSTRAINT users_username_charset CHECK (username ~ '^[a-zA-Z0-9_.]+$')
);

CREATE UNIQUE INDEX users_username_normalized_key ON users (username_normalized);

CREATE TRIGGER users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- devices
-- ---------------------------------------------------------------------------

CREATE TABLE devices (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    platform     device_platform NOT NULL,
    display_name TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    -- Devices are revoked (soft-deleted), never hard-deleted, so message
    -- history referencing sender_device_id always remains valid.
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX devices_active_user_idx ON devices (user_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- sessions (refresh-token store; access tokens are stateless JWTs)
-- ---------------------------------------------------------------------------

CREATE TABLE sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id           UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    -- SHA-256 hex digest of the refresh token. The token itself is
    -- high-entropy random data, so a fast cryptographic hash is appropriate
    -- here (unlike Argon2id, which is for low-entropy human passwords).
    refresh_token_hash  TEXT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    -- Rotation chain: reuse of a revoked token is a theft signal.
    replaced_by         UUID REFERENCES sessions (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX sessions_refresh_token_hash_key ON sessions (refresh_token_hash);
CREATE INDEX sessions_user_device_idx ON sessions (user_id, device_id);
CREATE INDEX sessions_active_expiry_idx ON sessions (expires_at) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- push_tokens
-- ---------------------------------------------------------------------------

CREATE TABLE push_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id  UUID NOT NULL UNIQUE REFERENCES devices (id) ON DELETE CASCADE,
    provider   push_provider NOT NULL DEFAULT 'fcm',
    token      TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER push_tokens_set_updated_at
    BEFORE UPDATE ON push_tokens
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- chats
-- ---------------------------------------------------------------------------

CREATE TABLE chats (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type            chat_type NOT NULL DEFAULT 'direct',
    -- Only populated for type = 'direct'; canonically ordered so a partial
    -- unique index below prevents duplicate 1:1 chats between two users.
    user_a_id       UUID REFERENCES users (id) ON DELETE CASCADE,
    user_b_id       UUID REFERENCES users (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at TIMESTAMPTZ,
    CONSTRAINT chats_direct_pair_shape CHECK (
        (type = 'direct' AND user_a_id IS NOT NULL AND user_b_id IS NOT NULL AND user_a_id < user_b_id)
        OR (type <> 'direct' AND user_a_id IS NULL AND user_b_id IS NULL)
    )
);

CREATE UNIQUE INDEX chats_direct_pair_key ON chats (user_a_id, user_b_id) WHERE type = 'direct';

-- ---------------------------------------------------------------------------
-- chat_members
-- ---------------------------------------------------------------------------

CREATE TABLE chat_members (
    chat_id   UUID NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role      chat_member_role NOT NULL DEFAULT 'member',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at   TIMESTAMPTZ,
    PRIMARY KEY (chat_id, user_id)
);

CREATE INDEX chat_members_user_id_idx ON chat_members (user_id);

-- ---------------------------------------------------------------------------
-- messages
-- ---------------------------------------------------------------------------

CREATE TABLE messages (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Global monotonic cursor used for SYNC; independent of wall-clock time.
    sequence           BIGINT GENERATED ALWAYS AS IDENTITY,
    chat_id            UUID NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    sender_id          UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- RESTRICT: devices are revoked, never hard-deleted, so this never blocks.
    sender_device_id   UUID NOT NULL REFERENCES devices (id) ON DELETE RESTRICT,
    -- Client-generated idempotency key; a resend after a lost ACK is a no-op.
    client_message_id  UUID NOT NULL,
    -- Plaintext UTF-8 bytes today (encryption_version = 0); real Signal
    -- ciphertext later without changing this column or the delivery model.
    ciphertext         BYTEA NOT NULL,
    encryption_version SMALLINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT messages_idempotency_key UNIQUE (sender_device_id, client_message_id)
);

CREATE INDEX messages_chat_sequence_idx ON messages (chat_id, sequence);

CREATE FUNCTION touch_chat_last_message_at() RETURNS trigger AS $$
BEGIN
    UPDATE chats SET last_message_at = NEW.created_at WHERE id = NEW.chat_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER messages_touch_chat
    AFTER INSERT ON messages
    FOR EACH ROW EXECUTE FUNCTION touch_chat_last_message_at();

-- ---------------------------------------------------------------------------
-- message_receipts (per-recipient delivery/read fan-out; scales to groups)
-- ---------------------------------------------------------------------------

CREATE TABLE message_receipts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id   UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Nullable: per-device receipt addressing lands with full multi-device E2E.
    device_id    UUID REFERENCES devices (id) ON DELETE SET NULL,
    -- Denormalized copy of messages.sequence so SYNC needs no JOIN.
    sequence     BIGINT NOT NULL,
    delivered_at TIMESTAMPTZ,
    read_at      TIMESTAMPTZ,
    CONSTRAINT message_receipts_unique UNIQUE (message_id, user_id),
    CONSTRAINT message_receipts_read_after_delivered CHECK (read_at IS NULL OR delivered_at IS NOT NULL)
);

CREATE INDEX message_receipts_user_sequence_idx ON message_receipts (user_id, sequence);
CREATE INDEX message_receipts_message_id_idx ON message_receipts (message_id);

CREATE FUNCTION fan_out_message_receipts() RETURNS trigger AS $$
BEGIN
    INSERT INTO message_receipts (message_id, user_id, sequence)
    SELECT NEW.id, cm.user_id, NEW.sequence
    FROM chat_members cm
    WHERE cm.chat_id = NEW.chat_id
      AND cm.user_id <> NEW.sender_id
      AND cm.left_at IS NULL;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER messages_fan_out_receipts
    AFTER INSERT ON messages
    FOR EACH ROW EXECUTE FUNCTION fan_out_message_receipts();
