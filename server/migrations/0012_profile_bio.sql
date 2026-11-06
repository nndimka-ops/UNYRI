-- Этап PROFILE.1: add an optional, bounded `bio` column to users.
-- Additive only: no DROP/TRUNCATE/recreate; existing rows are untouched
-- (bio is NULL for everyone until they set it).
ALTER TABLE users
    ADD COLUMN bio text,
    ADD CONSTRAINT users_bio_length CHECK (bio IS NULL OR char_length(bio) <= 160);
