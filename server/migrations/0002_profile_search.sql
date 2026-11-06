-- Этап C: profile editing + username search.

-- Avatars are stored as an opaque reference (URL/key), never as image bytes,
-- so this column can later be repointed at object storage (S3 key -> CDN
-- URL) without changing the API contract. Guardrails enforce that at the DB
-- level: bounded length, and no `data:` URIs (which would smuggle image
-- bytes into Postgres as text).
ALTER TABLE users
    ADD CONSTRAINT users_avatar_url_length CHECK (avatar_url IS NULL OR char_length(avatar_url) <= 2048),
    ADD CONSTRAINT users_avatar_url_not_data_uri CHECK (avatar_url IS NULL OR avatar_url !~* '^data:');

-- Username search needs substring matching (ILIKE '%q%'), not just prefix
-- matching, so a plain btree index on username_normalized can't help. Since
-- usernames are ASCII-only (see the Этап A charset CHECK), trigram indexing
-- is cheap and precise here. pg_trgm's GIN index is specifically designed to
-- accelerate ILIKE/LIKE '%...%' queries, avoiding a sequential scan.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Partial index (deleted_at IS NULL) mirrors the WHERE clause every search
-- query uses, keeping the index smaller and skipping soft-deleted accounts.
CREATE INDEX users_username_trgm_idx
    ON users USING GIN (username_normalized gin_trgm_ops)
    WHERE deleted_at IS NULL;
