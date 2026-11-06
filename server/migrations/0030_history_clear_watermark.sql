-- Recover the last explicitly audited full wipe, without deleting current data.
-- Sequence boundary preserves post-wipe sends even when their local clock is old.
CREATE TABLE history_clear_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    before_ms BIGINT NOT NULL CHECK (before_ms >= 0),
    through_sequence BIGINT NOT NULL CHECK (through_sequence >= 0)
);
LOCK TABLE messages IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO history_clear_state(singleton,before_ms,through_sequence)
SELECT TRUE, floor(extract(epoch FROM max(created_at))*1000)::bigint,
       COALESCE((SELECT MIN(sequence)-1 FROM messages),
                (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM messages_sequence_seq))
FROM admin_audit WHERE action='local:clear-message-call-history'
HAVING max(created_at) IS NOT NULL;
