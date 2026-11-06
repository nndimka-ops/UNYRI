-- Durable participant-scoped tombstones; live signaling records remain intact.
CREATE TABLE call_history_deletions (
    sequence BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    call_id UUID NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
    UNIQUE (user_id, call_id)
);
CREATE INDEX call_history_deletions_user_sequence ON call_history_deletions(user_id, sequence);
