-- Этап H: generic E2E attachment foundation (photos/files/voice). The server
-- NEVER sees plaintext file content NOR the attachment's encryption
-- key/nonce - those live only inside the Signal-Protocol-encrypted message
-- content (see messages::models content versioning), exactly like every
-- other secret this project handles. This table is pure storage-authorization
-- bookkeeping: which opaque encrypted blob exists in MinIO/S3, who is
-- allowed to reference/download it, and its lifecycle state.

CREATE TYPE attachment_status AS ENUM ('created', 'uploaded', 'attached');

CREATE TABLE attachments (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Idempotency: a client retry of the same upload-intent request (after a
    -- lost response) must not create a second orphan object. Scoped to the
    -- uploading device, same pattern as messages_idempotency_key.
    client_attachment_id UUID NOT NULL,
    uploader_user_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    uploader_device_id UUID NOT NULL REFERENCES devices (id) ON DELETE RESTRICT,
    -- Opaque random storage key, e.g. "attachments/<uuid>" - never derived
    -- from user id/chat id/filename (see object_key generation in Rust).
    object_key         TEXT NOT NULL,
    -- Declared by the client at upload-intent time and re-checked against
    -- the real object size at /complete - never trusted alone.
    encrypted_size     BIGINT NOT NULL,
    status             attachment_status NOT NULL DEFAULT 'created',
    -- Set once a message references this attachment (see п.13); until then
    -- only the uploader may ever get a download URL (п.4/п.38).
    message_id         UUID REFERENCES messages (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploaded_at        TIMESTAMPTZ,
    attached_at        TIMESTAMPTZ,
    CONSTRAINT attachments_encrypted_size_positive CHECK (encrypted_size > 0),
    CONSTRAINT attachments_client_id_key UNIQUE (uploader_device_id, client_attachment_id)
);

-- Orphan-cleanup sweep: "created/uploaded, never attached, older than N".
CREATE INDEX attachments_orphan_sweep_idx ON attachments (created_at) WHERE status <> 'attached';
-- "download authorization via the chat this message belongs to" join path.
CREATE INDEX attachments_message_id_idx ON attachments (message_id) WHERE message_id IS NOT NULL;
