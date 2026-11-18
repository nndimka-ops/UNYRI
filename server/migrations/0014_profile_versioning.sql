-- Этап PROFILE.1 E.1: authoritative avatar reference + optimistic versions.
-- Non-destructive: additive columns only; existing rows keep their data.
--
--   profile_version  BIGINT NOT NULL DEFAULT 1  - increments once per actual
--                     user-visible profile change (display_name / username /
--                     bio / avatar set / avatar removed).
--   avatar_version   BIGINT NOT NULL DEFAULT 0  - increments once per
--                     successful avatar replacement or removal. 0 = no
--                     avatar / legacy no-version state.
--   avatar_key       TEXT   NULL                - authoritative S3 object key
--                     (`avatars/{uuid}`). Never exposed in DTOs; the download
--                     URL is always derived/temporary (presigned GET).
--
-- `avatar_url` is KEPT for JSON compatibility with older clients but is no
-- longer an independently editable source: every write path updates both
-- `avatar_key` (authoritative) and `avatar_url` (legacy mirror) in the same
-- statement/transaction.

ALTER TABLE users
    ADD COLUMN profile_version bigint NOT NULL DEFAULT 1,
    ADD COLUMN avatar_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN avatar_key text;

-- Backfill the authoritative key from the legacy column for our own objects.
UPDATE users SET avatar_key = avatar_url
 WHERE avatar_url IS NOT NULL AND avatar_url LIKE 'avatars/%';

-- avatar_key is internal and only ever holds one of our `avatars/` objects.
ALTER TABLE users
    ADD CONSTRAINT users_avatar_key_format
        CHECK (avatar_key IS NULL OR (avatar_key LIKE 'avatars/%' AND char_length(avatar_key) <= 512));
