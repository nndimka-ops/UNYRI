CREATE TABLE call_reads (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    call_id UUID NOT NULL REFERENCES calls(id) ON DELETE CASCADE,
    seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id, call_id)
);
-- Existing call history never had an unread badge; do not suddenly badge old calls.
INSERT INTO call_reads(user_id,call_id) SELECT callee_user_id,id FROM calls WHERE state='ended';
-- A deleted conversation must not reappear from a previous call-history refresh.
ALTER TABLE personal_chat_clears ADD COLUMN calls_before TIMESTAMPTZ;
UPDATE personal_chat_clears SET calls_before=now();
