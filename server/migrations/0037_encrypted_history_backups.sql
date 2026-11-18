-- Stable identity for AEAD context; unrelated to hostnames/IP changes.
CREATE TABLE history_backup_server (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    server_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid()
);
INSERT INTO history_backup_server(singleton) VALUES(TRUE);
-- Opaque encrypted backups are separate from message delivery and Signal state.
-- Keep the revision row after deletion: delayed requests must not resurrect data.
CREATE TABLE history_backup_accounts (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    desired_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
CREATE TABLE history_backup_objects (
    user_id UUID NOT NULL REFERENCES history_backup_accounts(user_id) ON DELETE CASCADE,
    backup_id UUID NOT NULL,
    format_version INTEGER NOT NULL CHECK (format_version = 1),
    ciphertext BYTEA NOT NULL CHECK (octet_length(ciphertext) BETWEEN 29 AND 8388608),
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
    state TEXT NOT NULL CHECK (state IN ('staged', 'active')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, backup_id),
    UNIQUE (user_id, state),
    CHECK ((state = 'active') = (verified_at IS NOT NULL))
);
