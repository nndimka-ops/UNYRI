ALTER TABLE history_backup_objects DROP CONSTRAINT history_backup_objects_format_version_check;
ALTER TABLE history_backup_objects ADD CHECK (format_version IN (1,2));

-- Intentionally independent of users: deletion must never discard physical GC work.
-- Includes upload AND seal destinations before either object can be written.
CREATE TABLE history_backup_storage (
    object_key TEXT PRIMARY KEY CHECK (object_key LIKE 'history-media/%'),
    owner_id UUID NOT NULL,
    bytes BIGINT NOT NULL CHECK (bytes BETWEEN 1 AND 104861696),
    delete_after TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX history_backup_storage_gc ON history_backup_storage(delete_after) WHERE delete_after IS NOT NULL;
CREATE INDEX history_backup_storage_owner ON history_backup_storage(owner_id);
CREATE TABLE history_backup_media (
    user_id UUID NOT NULL REFERENCES history_backup_accounts(user_id) ON DELETE CASCADE,
    blob_id UUID NOT NULL,
    bytes BIGINT NOT NULL CHECK (bytes BETWEEN 1 AND 104861696),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    upload_key TEXT NOT NULL UNIQUE,
    sealed_key TEXT NOT NULL UNIQUE,
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    upload_lease_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,blob_id)
);
CREATE TABLE history_backup_media_links (
    user_id UUID NOT NULL,
    backup_id UUID NOT NULL,
    blob_id UUID NOT NULL,
    PRIMARY KEY(user_id,backup_id,blob_id),
    FOREIGN KEY(user_id,backup_id) REFERENCES history_backup_objects(user_id,backup_id) ON DELETE CASCADE,
    FOREIGN KEY(user_id,blob_id) REFERENCES history_backup_media(user_id,blob_id)
);
CREATE INDEX history_backup_media_links_blob ON history_backup_media_links(user_id,blob_id);
CREATE FUNCTION history_backup_media_enqueue_gc() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE history_backup_storage SET delete_after=GREATEST(now()+interval '2 minutes', OLD.upload_lease_until)
      WHERE object_key IN (OLD.upload_key,OLD.sealed_key);
    RETURN OLD;
END $$;
CREATE TRIGGER history_backup_media_deleted AFTER DELETE ON history_backup_media
FOR EACH ROW EXECUTE FUNCTION history_backup_media_enqueue_gc();
