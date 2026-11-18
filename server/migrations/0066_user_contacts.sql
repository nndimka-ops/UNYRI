-- Personal address book, private to its owner; the contact is never told.
-- removed_at keeps the fact that a pair was already added once, so a later
-- message never silently re-adds someone the owner removed.
CREATE TABLE user_contacts (
    owner_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    contact_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source     TEXT NOT NULL CHECK (source IN ('manual','chat','request')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    PRIMARY KEY (owner_id, contact_id),
    CHECK (owner_id <> contact_id)
);
-- Deleting an account cascades by contact_id too; keep that an index lookup.
CREATE INDEX user_contacts_contact_idx ON user_contacts (contact_id);
-- Existing conversations: everyone a user already wrote to is a contact,
-- matching the rule for new chats.
INSERT INTO user_contacts (owner_id, contact_id, source, created_at)
SELECT m.sender_id,
       CASE WHEN m.sender_id = c.user_a_id THEN c.user_b_id ELSE c.user_a_id END,
       'chat', MIN(m.created_at)
FROM messages m JOIN chats c ON c.id = m.chat_id AND c.type = 'direct'
WHERE m.kind = 'content' AND m.sender_id IN (c.user_a_id, c.user_b_id)
GROUP BY 1, 2
ON CONFLICT DO NOTHING;
