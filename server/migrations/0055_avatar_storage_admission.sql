-- Keep upload capabilities charged until their leases and confirmed deletion.
ALTER TABLE avatar_uploads
    ADD COLUMN expected_bytes BIGINT CHECK (expected_bytes > 0 AND expected_bytes <= 3145728),
    ADD COLUMN reserved_bytes BIGINT NOT NULL DEFAULT 3145728 CHECK (reserved_bytes >= 0),
    ADD COLUMN upload_lease_until TIMESTAMPTZ NOT NULL DEFAULT (now()+interval '24 hours'),
    ADD COLUMN cleanup_pending BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN is_complete BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN legacy_reconciled BOOLEAN NOT NULL DEFAULT false;
UPDATE avatar_uploads a SET is_complete=true
WHERE EXISTS(SELECT 1 FROM users u WHERE u.avatar_key=a.object_key)
   OR a.object_key LIKE 'avatar-renditions/%';
CREATE INDEX avatar_uploads_gc ON avatar_uploads(upload_lease_until,created_at);
ALTER TABLE account_object_deletions ADD COLUMN reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0);
ALTER TABLE account_object_deletions ADD COLUMN owner_user_id UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE account_object_deletions ADD COLUMN legacy_unverified BOOLEAN NOT NULL DEFAULT false;
UPDATE account_object_deletions SET legacy_unverified=true;
UPDATE account_object_deletions SET reserved_bytes=3145728
WHERE object_key LIKE 'avatars/%' OR object_key LIKE 'avatar-renditions/%';
CREATE INDEX avatar_uploads_legacy_unverified ON avatar_uploads(object_key) WHERE expected_bytes IS NULL AND NOT legacy_reconciled;
CREATE INDEX account_object_deletions_legacy_unverified ON account_object_deletions(object_key) WHERE legacy_unverified;

CREATE VIEW storage_reservations AS
SELECT uploader_user_id AS owner_id, encrypted_size AS bytes, status='created' AS pending FROM attachments
UNION ALL
SELECT owner_id, bytes, delete_after IS NOT NULL OR EXISTS(
    SELECT 1 FROM history_backup_media m WHERE m.sealed_key=s.object_key AND NOT m.sealed)
FROM history_backup_storage s
UNION ALL
SELECT user_id, reserved_bytes, NOT is_complete OR cleanup_pending FROM avatar_uploads
UNION ALL
SELECT owner_user_id, reserved_bytes, true FROM account_object_deletions WHERE reserved_bytes>0;
