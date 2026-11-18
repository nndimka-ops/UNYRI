-- L: Device & account security. Extends the existing device model with
-- client-supplied (display-only, UNTRUSTED) metadata and a server-side
-- security activity log. No duplicate columns: `last_seen_at` is reused as
-- the "last active" signal (renamed for clarity) rather than adding a second
-- timestamp.

-- ---------------------------------------------------------------------------
-- Device metadata (display-only; client-supplied values are untrusted)
-- ---------------------------------------------------------------------------
ALTER TABLE devices ADD COLUMN os_version TEXT;
ALTER TABLE devices ADD COLUMN app_version TEXT;

-- Reuse the existing activity timestamp as `last_active_at` (L.3: coalesced
-- updates on login/refresh/WS-connect, never per-request).
ALTER TABLE devices RENAME COLUMN last_seen_at TO last_active_at;

-- ---------------------------------------------------------------------------
-- Server-side security activity log. Metadata must be non-secret only -
-- never a token, FCM token, Signal private key, password hash, or access
-- token. Devices are soft-revoked (never hard-deleted) in normal operation,
-- so FK references are stable; ON DELETE SET NULL only fires for a full user
-- cascade.
-- ---------------------------------------------------------------------------
CREATE TABLE security_events (
    id                BIGSERIAL PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_type        TEXT NOT NULL,
    actor_device_id   UUID REFERENCES devices (id) ON DELETE SET NULL,
    subject_device_id UUID REFERENCES devices (id) ON DELETE SET NULL,
    metadata          JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Keyset pagination over a user's recent security events (newest first).
CREATE INDEX security_events_user_idx ON security_events (user_id, id DESC);

-- ---------------------------------------------------------------------------
-- Extend the call end_reason enum with `device_revoked` so a call whose
-- accepted device was revoked can end with an accurate reason (L.15) instead
-- of a misleading `failed`.
-- ---------------------------------------------------------------------------
ALTER TABLE calls DROP CONSTRAINT calls_end_reason_check;
ALTER TABLE calls ADD CONSTRAINT calls_end_reason_check CHECK (
    end_reason = ANY (ARRAY['rejected','cancelled','busy','missed','hangup','failed','timeout','device_revoked'])
);
