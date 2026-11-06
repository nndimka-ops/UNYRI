-- A private list preference, independent of chat membership, mute and pins.
-- Existing accounts remain unarchived. No history is moved or deleted.
ALTER TABLE chat_preferences ADD COLUMN is_archived BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX chat_preferences_archived ON chat_preferences(user_id, chat_id) WHERE is_archived;
