-- Upload intents bind objects to their creator, including existing avatars.
CREATE TABLE avatar_uploads (
    object_key text PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO avatar_uploads (object_key, user_id)
SELECT DISTINCT ON (avatar_key) avatar_key, id FROM users
WHERE avatar_key IS NOT NULL ORDER BY avatar_key, created_at, id;
UPDATE users SET avatar_version = 1 WHERE avatar_key IS NOT NULL AND avatar_version = 0;
