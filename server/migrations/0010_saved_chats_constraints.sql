-- M (part 2 of saved-chat schema): the CHECK constraint + unique index that
-- reference the 'saved' enum value added in 0009. Must be a separate
-- migration because PostgreSQL forbids using a newly-added enum value in the
-- same transaction that added it (error 55P04 "unsafe use of new value").

-- Rewrite the shape constraint to be explicit per type. `owner_user_id` is
-- NULL for direct and group; `user_a_id`/`user_b_id` are NULL for saved and
-- group (direct keeps the canonical user_a_id < user_b_id ordering).
ALTER TABLE chats DROP CONSTRAINT chats_direct_pair_shape;
ALTER TABLE chats ADD CONSTRAINT chats_direct_pair_shape CHECK (
    (type = 'direct' AND user_a_id IS NOT NULL AND user_b_id IS NOT NULL AND user_a_id < user_b_id AND owner_user_id IS NULL)
    OR (type = 'saved' AND owner_user_id IS NOT NULL AND user_a_id IS NULL AND user_b_id IS NULL)
    OR (type = 'group' AND user_a_id IS NULL AND user_b_id IS NULL AND owner_user_id IS NULL)
);

-- DB-enforced guarantee: exactly one Saved Messages chat per user.
CREATE UNIQUE INDEX chats_saved_owner_key ON chats (owner_user_id) WHERE type = 'saved';
