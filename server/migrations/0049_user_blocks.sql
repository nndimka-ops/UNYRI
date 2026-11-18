-- A privacy preference belongs to an account, not to a conversation. Removing
-- a conversation must never silently allow its blocked participant again.
CREATE TABLE user_blocks (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, blocked_user_id),
    CHECK (user_id <> blocked_user_id)
);
CREATE INDEX user_blocks_reverse_idx ON user_blocks (blocked_user_id, user_id);

CREATE FUNCTION user_pair_blocked(first_user UUID, second_user UUID)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS(SELECT 1 FROM user_blocks
        WHERE (user_id=first_user AND blocked_user_id=second_user)
           OR (user_id=second_user AND blocked_user_id=first_user));
$$;

-- Keep old clients/server instances and legacy chat preference writes compatible.
-- Intentionally no DELETE trigger: deleting a chat preserves its account block.
CREATE FUNCTION mirror_chat_preference_block() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.is_blocked AND
       (NOT NEW.is_blocked OR OLD.blocked_user_id IS DISTINCT FROM NEW.blocked_user_id) THEN
        DELETE FROM user_blocks b
        WHERE b.user_id = OLD.user_id AND b.blocked_user_id = OLD.blocked_user_id
          AND NOT EXISTS (
              SELECT 1 FROM chat_preferences p WHERE p.user_id = OLD.user_id
                AND p.is_blocked AND p.blocked_user_id = OLD.blocked_user_id
          );
    END IF;
    IF NEW.is_blocked AND NEW.blocked_user_id <> NEW.user_id THEN
        INSERT INTO user_blocks (user_id, blocked_user_id)
        VALUES (NEW.user_id, NEW.blocked_user_id) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER chat_preference_block_mirror
    AFTER INSERT OR UPDATE OF is_blocked, blocked_user_id ON chat_preferences
    FOR EACH ROW EXECUTE FUNCTION mirror_chat_preference_block();

INSERT INTO user_blocks (user_id, blocked_user_id, created_at)
SELECT user_id, blocked_user_id, MIN(updated_at)
FROM chat_preferences WHERE is_blocked AND blocked_user_id <> user_id
GROUP BY user_id, blocked_user_id ON CONFLICT DO NOTHING;
