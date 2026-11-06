-- Commands retain their exact boundaries even after a chat is removed/recreated.
CREATE TABLE bounded_chat_clear_requests (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    chat_id UUID NOT NULL,
    for_everyone BOOLEAN NOT NULL,
    through_sequence BIGINT NOT NULL CHECK (through_sequence >= 0),
    calls_before TIMESTAMPTZ,
    PRIMARY KEY (user_id, request_id)
);
