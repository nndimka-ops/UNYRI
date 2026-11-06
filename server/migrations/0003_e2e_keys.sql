-- Этап F: E2E encryption (Signal Protocol via the official libsignal-client
-- library on Android - no homemade cryptography). The server performs ZERO
-- cryptographic operations here: it only stores and relays opaque public
-- key material and ciphertext blobs. This is the correct, zero-trust Signal
-- Protocol server architecture - all signature verification, session setup,
-- and encrypt/decrypt happens on-device.

-- Signal Protocol addresses a remote party as (user, small integer device
-- id) - SignalProtocolAddress takes an int device id, incompatible with our
-- UUID devices.id. Assign a small, per-user sequential integer once per
-- device (assigned in upsert_device under an advisory lock - see
-- auth::handlers). Backfill any pre-existing rows deterministically first.
ALTER TABLE devices ADD COLUMN signal_device_id SMALLINT;

WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at, id) AS rn
    FROM devices
)
UPDATE devices SET signal_device_id = numbered.rn
FROM numbered WHERE devices.id = numbered.id;

ALTER TABLE devices ALTER COLUMN signal_device_id SET NOT NULL;
ALTER TABLE devices ADD CONSTRAINT devices_user_signal_device_id_key UNIQUE (user_id, signal_device_id);

-- ---------------------------------------------------------------------------
-- Per-device Signal Protocol key material (public parts only). One row per
-- device for identity/signed-prekey/last-resort Kyber prekey (replaced
-- wholesale on rotation - UPSERT); many rows per device for the one-time
-- EC prekey pool (each consumed exactly once when handed out).
-- ---------------------------------------------------------------------------

CREATE TABLE device_identity_keys (
    device_id       UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    identity_key    BYTEA NOT NULL,
    registration_id INTEGER NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE signed_prekeys (
    device_id  UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    key_id     INTEGER NOT NULL,
    public_key BYTEA NOT NULL,
    signature  BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE one_time_prekeys (
    id         BIGSERIAL PRIMARY KEY,
    device_id  UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    key_id     INTEGER NOT NULL,
    public_key BYTEA NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (device_id, key_id)
);

-- Fast "give me one unused key for this device" lookup when handing out a bundle.
CREATE INDEX one_time_prekeys_available_idx ON one_time_prekeys (device_id) WHERE used_at IS NULL;

-- Single signed last-resort Kyber (PQXDH) prekey per device - reused across
-- session establishments rather than a consumable pool. A deliberate scope
-- reduction: PQXDH permits this usage (it's what "last-resort" means in the
-- protocol), and a full consumable Kyber pool adds real complexity for
-- marginal benefit at this stage.
CREATE TABLE kyber_prekeys (
    device_id  UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    key_id     INTEGER NOT NULL,
    public_key BYTEA NOT NULL,
    signature  BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- message_envelopes: per-recipient-device Signal Protocol ciphertext.
-- `messages` stays the logical/ordering row (sequence, chat_id, sender); the
-- actual encrypted payload for each recipient device lives here, since
-- Signal Protocol sessions - and therefore ciphertext - are per-device, not
-- per-chat. `messages.ciphertext` is no longer written for E2E sends.
-- ---------------------------------------------------------------------------

ALTER TABLE messages ALTER COLUMN ciphertext DROP NOT NULL;

CREATE TABLE message_envelopes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id          UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    recipient_device_id UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    -- 1 = PreKeySignalMessage (establishes/re-establishes a session), 2 = SignalMessage (established session).
    message_type        SMALLINT NOT NULL,
    ciphertext          BYTEA NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, recipient_device_id)
);

CREATE INDEX message_envelopes_recipient_idx ON message_envelopes (recipient_device_id, message_id);
