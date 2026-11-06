-- Checkpoint B.1: harden conversation security-event DB invariants.
-- 0031 is already applied locally, so this is a NEW additive migration that
-- tightens the schema without rewriting migration history.
--
-- Invariant 1 (completeness): a CONVERSATION-scope event can never exist in an
-- incomplete state. Account-scope rows (legacy owner events) are unaffected and
-- may keep NULL chat_id / subject_user_id / event_key.

ALTER TABLE security_events ADD CONSTRAINT security_events_conversation_complete
    CHECK (
        scope = 'account'
        OR (
            scope = 'conversation'
            AND chat_id IS NOT NULL
            AND subject_user_id IS NOT NULL
            AND event_key IS NOT NULL
            AND length(trim(event_key)) > 0
        )
    );

-- Invariant 2 (idempotency scope): uniqueness is per (recipient, chat, event_key).
-- A single source transition belongs to exactly one chat for a given recipient;
-- retrying it is a no-op. The SAME event_key in a DIFFERENT chat for the same
-- recipient is a distinct, legitimate row and must never be suppressed.
-- The previous index (user_id, event_key) is replaced: it would have wrongly
-- collapsed the same transition across two different chats.

DROP INDEX security_events_recipient_event_key;
CREATE UNIQUE INDEX security_events_conversation_idempotency
    ON security_events (user_id, chat_id, event_key)
    WHERE scope = 'conversation';
