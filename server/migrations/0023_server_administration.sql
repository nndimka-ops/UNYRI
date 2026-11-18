-- Server roles are separate from chat-member roles and never granted by registration.
CREATE TABLE server_administrators (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('owner', 'moderator')),
    secret BYTEA,
    pending_secret BYTEA,
    pending_session UUID,
    pending_expires TIMESTAMPTZ,
    last_step BIGINT NOT NULL DEFAULT -1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE admin_recovery_codes (
    user_id UUID NOT NULL REFERENCES server_administrators(user_id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL,
    PRIMARY KEY(user_id, code_hash)
);
CREATE TABLE admin_sessions (
    token_hash TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES server_administrators(user_id) ON DELETE CASCADE,
    auth_session UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    last_used TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX admin_sessions_owner ON admin_sessions(user_id);
CREATE TABLE admin_audit (
    id BIGSERIAL PRIMARY KEY,
    actor UUID,
    action TEXT NOT NULL,
    target UUID,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE chat_reports ADD COLUMN review_status TEXT NOT NULL DEFAULT 'open' CHECK(review_status IN ('open','resolved','dismissed'));
ALTER TABLE chat_reports ADD COLUMN review_note TEXT;
CREATE TABLE server_suspensions (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    until_date TIMESTAMPTZ,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
