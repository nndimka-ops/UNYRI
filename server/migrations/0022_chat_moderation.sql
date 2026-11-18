-- Per-user chat controls: mute notifications and local block state.

CREATE TABLE chat_preferences (
    chat_id UUID NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    is_muted BOOLEAN NOT NULL DEFAULT false,
    muted_until TIMESTAMPTZ,
    is_blocked BOOLEAN NOT NULL DEFAULT false,
    blocked_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, user_id),
    CONSTRAINT chat_preferences_block_requires_user CHECK (
        (is_blocked = false AND blocked_user_id IS NULL)
        OR (is_blocked = true AND blocked_user_id IS NOT NULL)
    )
);

CREATE INDEX chat_preferences_user_idx ON chat_preferences (user_id, is_muted) WHERE is_muted OR is_blocked;
CREATE INDEX chat_preferences_block_idx ON chat_preferences (blocked_user_id) WHERE is_blocked;

CREATE TRIGGER chat_preferences_set_updated_at
    BEFORE UPDATE ON chat_preferences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Lightweight report/abuse table for in-app user reports.
CREATE TYPE chat_report_reason AS ENUM (
    'spam',
    'harassment',
    'pornography',
    'scam',
    'self_harm',
    'copyright',
    'other'
);

CREATE TABLE chat_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id UUID NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    reporter_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reported_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    reason chat_report_reason NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (chat_id, reporter_user_id, created_at)
);

CREATE INDEX chat_reports_chat_idx ON chat_reports (chat_id, created_at DESC);
CREATE INDEX chat_reports_reporter_idx ON chat_reports (reporter_user_id, created_at DESC);
CREATE INDEX chat_reports_reported_idx ON chat_reports (reported_user_id, created_at DESC);
