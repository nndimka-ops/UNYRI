CREATE TABLE user_chat_appearance (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    values jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(values) = 'object'),
    updated_at timestamptz NOT NULL DEFAULT now()
);
