ALTER TABLE attachments ADD COLUMN upload_lease_until TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE attachments ADD COLUMN cleanup_pending BOOLEAN NOT NULL DEFAULT false;
