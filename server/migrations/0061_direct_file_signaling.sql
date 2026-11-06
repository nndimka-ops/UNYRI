-- Only routing metadata and pairwise Signal ciphertext. Never file contents.
CREATE TABLE direct_file_transfers (
    id UUID PRIMARY KEY,
    chat_id UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    offer_message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    offer_client_message_id UUID NOT NULL,
    sender_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (sender_user_id <> recipient_user_id),
    CHECK (sender_device_id IS DISTINCT FROM recipient_device_id),
    UNIQUE(sender_device_id, offer_client_message_id)
);
CREATE INDEX direct_file_transfers_expiry ON direct_file_transfers(expires_at);
CREATE INDEX direct_file_transfers_sender ON direct_file_transfers(sender_user_id, expires_at);

CREATE TABLE direct_file_signals (
    sequence BIGSERIAL PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES direct_file_transfers(id) ON DELETE CASCADE,
    client_signal_id UUID NOT NULL,
    sender_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    recipient_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    message_type SMALLINT NOT NULL CHECK (message_type IN (1,2)),
    ciphertext BYTEA NOT NULL CHECK (octet_length(ciphertext) BETWEEN 1 AND 65536),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(sender_device_id,client_signal_id)
);
CREATE INDEX direct_file_signals_inbox ON direct_file_signals(transfer_id,recipient_device_id,sequence);
