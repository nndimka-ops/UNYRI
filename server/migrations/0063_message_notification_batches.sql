-- Claims, ciphertext envelopes and eligible push jobs commit together.
ALTER TABLE message_envelopes ADD COLUMN notification_batch_alert BOOLEAN;
CREATE TABLE message_notification_batch_claims (
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    batch_id UUID NOT NULL CHECK (batch_id <> '00000000-0000-0000-0000-000000000000'),
    recipient_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    first_message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    PRIMARY KEY (sender_id, chat_id, batch_id, recipient_device_id)
);
CREATE INDEX message_notification_batch_first_idx ON message_notification_batch_claims(first_message_id);
