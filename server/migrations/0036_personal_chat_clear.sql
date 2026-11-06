CREATE TABLE personal_chat_clears (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
 through_sequence BIGINT NOT NULL CHECK(through_sequence>=0),
 PRIMARY KEY(user_id,chat_id)
);
CREATE TABLE personal_clear_requests (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 request_id UUID NOT NULL,
 chat_id UUID NOT NULL,
 PRIMARY KEY(user_id,request_id)
);
