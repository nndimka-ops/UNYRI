-- Only a completed, client-verified restore opts this device into old call metadata.
CREATE TABLE device_call_history_restore (
    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    backup_id UUID NOT NULL,
    restored_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
