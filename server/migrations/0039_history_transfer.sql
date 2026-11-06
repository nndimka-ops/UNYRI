-- Ephemeral, opaque key handoff; never contains a plaintext recovery key.
CREATE TABLE history_transfers (
    transfer_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    recipient_public_key TEXT NOT NULL CHECK (length(recipient_public_key)=44),
    expires_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'waiting' CHECK(status IN ('waiting','ready','consumed','cancelled')),
    sender_device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    sender_public_key TEXT,
    backup_id UUID,
    ciphertext TEXT,
    publication_hash TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status='ready')=(ciphertext IS NOT NULL)),
    CHECK (ciphertext IS NULL OR length(ciphertext)=80),
    CHECK (sender_public_key IS NULL OR length(sender_public_key)=44)
);
CREATE INDEX history_transfers_owner ON history_transfers(owner_id,expires_at);
CREATE INDEX history_transfers_expiry ON history_transfers(expires_at);
