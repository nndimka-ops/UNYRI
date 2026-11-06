-- Per-account administrative controls and user-visible operator notices.
-- Additive only: existing accounts remain unrestricted and no user data is
-- rewritten by this migration.

CREATE TABLE admin_user_controls (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    read_only_until TIMESTAMPTZ,
    read_only_reason TEXT,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT admin_user_controls_read_only_reason
        CHECK (read_only_reason IS NULL OR char_length(read_only_reason) BETWEEN 1 AND 2000)
);

CREATE TABLE admin_user_notices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL CHECK (char_length(title) BETWEEN 1 AND 120),
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    severity TEXT NOT NULL CHECK (severity IN ('info', 'warning', 'critical')),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX admin_user_notices_user_created_idx
    ON admin_user_notices(user_id, created_at DESC);

