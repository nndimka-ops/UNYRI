-- Recipient-scoped durable removals survive missing realtime events and old cursors.
CREATE TABLE deleted_chats (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chat_id uuid NOT NULL,
    deleted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id, chat_id)
);
-- No account FK: storage cleanup must survive deleting its former owner.
CREATE TABLE account_object_deletions (
    object_key text PRIMARY KEY,
    multipart_upload_id text,
    delete_after timestamptz NOT NULL
);
