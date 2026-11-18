ALTER TABLE chat_preferences ADD COLUMN is_pinned BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX chat_preferences_pinned ON chat_preferences(user_id,chat_id) WHERE is_pinned;
