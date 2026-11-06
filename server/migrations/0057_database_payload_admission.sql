-- Inline ciphertext and public keys consume PostgreSQL, independently of S3.
-- Reservations deliberately retain owner IDs without a users FK: cascades can
-- remove the parent before a child trigger runs. Counter changes never look up
-- or lock users/devices/messages; lock order is always global -> owner.
ALTER TABLE server_policy
    ADD COLUMN database_user_quota_bytes BIGINT NOT NULL DEFAULT 268435456 CHECK (database_user_quota_bytes > 0),
    ADD COLUMN database_budget_bytes BIGINT NOT NULL DEFAULT 2147483648 CHECK (database_budget_bytes > 0);

ALTER TABLE device_identity_keys
    ADD COLUMN one_time_prekey_retired_through INTEGER NOT NULL DEFAULT -1;

CREATE TABLE message_payload_reservations (
    message_id UUID PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    bytes BIGINT NOT NULL CHECK (bytes >= 0)
);
CREATE TABLE device_payload_reservations (
    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    bytes BIGINT NOT NULL CHECK (bytes >= 0)
);
CREATE TABLE security_event_payload_reservations (
    event_id BIGINT PRIMARY KEY REFERENCES security_events(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    bytes BIGINT NOT NULL CHECK (bytes >= 0)
);
CREATE TABLE database_payload_accounts (
    owner_id UUID PRIMARY KEY,
    bytes BIGINT NOT NULL CHECK (bytes >= 0)
);
CREATE TABLE database_payload_global (
    id BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),
    bytes BIGINT NOT NULL CHECK (bytes >= 0)
);

-- Initialize existing reservations before enabling admission triggers. Existing
-- history is preserved even if already above a newly configured budget.
INSERT INTO message_payload_reservations(message_id,owner_id,bytes)
SELECT m.id,m.sender_id,1024 + COALESCE(octet_length(m.ciphertext),0)
    + COALESCE((SELECT SUM(octet_length(e.ciphertext)+256) FROM message_envelopes e WHERE e.message_id=m.id),0)
FROM messages m;
INSERT INTO device_payload_reservations(device_id,owner_id,bytes)
SELECT d.id,d.user_id,512
    + COALESCE((SELECT SUM(octet_length(k.identity_key)+128) FROM device_identity_keys k WHERE k.device_id=d.id),0)
    + COALESCE((SELECT SUM(octet_length(k.public_key)+octet_length(k.signature)+128) FROM signed_prekeys k WHERE k.device_id=d.id),0)
    + COALESCE((SELECT SUM(octet_length(k.public_key)+octet_length(k.signature)+128) FROM kyber_prekeys k WHERE k.device_id=d.id),0)
    + COALESCE((SELECT SUM(octet_length(k.public_key)+128) FROM one_time_prekeys k WHERE k.device_id=d.id),0)
FROM devices d WHERE EXISTS(SELECT 1 FROM device_identity_keys k WHERE k.device_id=d.id);
INSERT INTO security_event_payload_reservations(event_id,owner_id,bytes)
SELECT id,COALESCE(subject_user_id,user_id),512+octet_length(COALESCE(metadata::text,''))+octet_length(COALESCE(event_key,''))+octet_length(event_type)
FROM security_events WHERE scope='conversation';
INSERT INTO database_payload_accounts(owner_id,bytes)
SELECT owner_id,SUM(bytes) FROM (
    SELECT owner_id,bytes FROM message_payload_reservations
    UNION ALL SELECT owner_id,bytes FROM device_payload_reservations
    UNION ALL SELECT owner_id,bytes FROM security_event_payload_reservations
) r GROUP BY owner_id;
INSERT INTO database_payload_global(id,bytes)
SELECT true,COALESCE(SUM(bytes),0) FROM database_payload_accounts;

CREATE FUNCTION account_database_payload_delta() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    charge_owner UUID;
    delta BIGINT;
    total_bytes BIGINT;
    owner_bytes BIGINT;
    user_limit BIGINT;
    global_limit BIGINT;
    owner_setting TEXT;
BEGIN
    IF TG_OP='DELETE' THEN
        charge_owner:=OLD.owner_id; delta:=-OLD.bytes;
    ELSIF TG_OP='INSERT' THEN
        charge_owner:=NEW.owner_id; delta:=NEW.bytes;
    ELSE
        IF NEW.owner_id<>OLD.owner_id THEN
            RAISE EXCEPTION 'Payload reservation owner is immutable';
        END IF;
        charge_owner:=NEW.owner_id; delta:=NEW.bytes-OLD.bytes;
    END IF;
    IF delta=0 THEN RETURN NULL; END IF;
    SELECT bytes INTO STRICT total_bytes FROM database_payload_global WHERE id=true FOR UPDATE;
    INSERT INTO database_payload_accounts(owner_id,bytes) VALUES(charge_owner,0) ON CONFLICT DO NOTHING;
    SELECT bytes INTO STRICT owner_bytes FROM database_payload_accounts WHERE owner_id=charge_owner FOR UPDATE;
    -- Preserve the transaction's initial allocation, not an intermediate delta.
    -- This allows deletion/replacement and legacy over-budget shrinkage.
    IF NULLIF(current_setting('coco.payload_global_baseline',true),'') IS NULL THEN
        PERFORM set_config('coco.payload_global_baseline',total_bytes::text,true);
    END IF;
    owner_setting:='coco.payload_owner_'||replace(charge_owner::text,'-','');
    IF NULLIF(current_setting(owner_setting,true),'') IS NULL THEN
        PERFORM set_config(owner_setting,owner_bytes::text,true);
    END IF;
    UPDATE database_payload_global SET bytes=bytes+delta WHERE id=true;
    UPDATE database_payload_accounts SET bytes=bytes+delta WHERE owner_id=charge_owner;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER message_payload_accounting AFTER INSERT OR UPDATE OR DELETE ON message_payload_reservations DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION account_database_payload_delta();
CREATE CONSTRAINT TRIGGER device_payload_accounting AFTER INSERT OR UPDATE OR DELETE ON device_payload_reservations DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION account_database_payload_delta();
CREATE CONSTRAINT TRIGGER security_event_payload_accounting AFTER INSERT OR UPDATE OR DELETE ON security_event_payload_reservations DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION account_database_payload_delta();

CREATE FUNCTION check_database_payload_final_budget() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE used BIGINT; maximum BIGINT; baseline BIGINT;
BEGIN
    IF TG_TABLE_NAME='database_payload_global' THEN
        SELECT bytes INTO used FROM database_payload_global WHERE id=true;
        SELECT database_budget_bytes INTO maximum FROM server_policy WHERE id=true;
        baseline:=NULLIF(current_setting('coco.payload_global_baseline',true),'')::bigint;
    ELSE
        SELECT bytes INTO used FROM database_payload_accounts WHERE owner_id=NEW.owner_id;
        SELECT database_user_quota_bytes INTO maximum FROM server_policy WHERE id=true;
        baseline:=NULLIF(current_setting('coco.payload_owner_'||replace(NEW.owner_id::text,'-',''),true),'')::bigint;
    END IF;
    IF used>maximum AND used>COALESCE(baseline,0) THEN
        RAISE EXCEPTION 'Database payload storage quota is full'
            USING ERRCODE='54000',CONSTRAINT='database_payload_budget';
    END IF;
    RETURN NULL;
END $$;
-- These checks are queued by the deferred delta pass, after all pending source
-- deltas. They read final counters and never acquire user/device/message locks.
CREATE CONSTRAINT TRIGGER database_payload_global_budget AFTER UPDATE ON database_payload_global DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_database_payload_final_budget();
CREATE CONSTRAINT TRIGGER database_payload_owner_budget AFTER UPDATE ON database_payload_accounts DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_database_payload_final_budget();

CREATE FUNCTION reserve_conversation_security_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.scope='conversation' THEN
        INSERT INTO security_event_payload_reservations(event_id,owner_id,bytes)
        VALUES(NEW.id,COALESCE(NEW.subject_user_id,NEW.user_id),512+octet_length(COALESCE(NEW.metadata::text,''))+octet_length(COALESCE(NEW.event_key,''))+octet_length(NEW.event_type));
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER conversation_security_event_reservation AFTER INSERT ON security_events
FOR EACH ROW EXECUTE FUNCTION reserve_conversation_security_event();

-- Repair old delete-for-everyone tombstones. Revocation is immediate; durable
-- cleanup keeps S3 bytes charged until deletion succeeds and upload leases end.
INSERT INTO account_object_deletions(object_key,multipart_upload_id,delete_after,reserved_bytes,owner_user_id)
SELECT a.object_key,a.multipart_upload_id,GREATEST(a.upload_lease_until,now()+interval '24 hours'),a.encrypted_size,a.uploader_user_id
FROM attachments a JOIN messages m ON m.id=a.message_id WHERE m.deleted_at IS NOT NULL
ON CONFLICT(object_key) DO UPDATE SET
    delete_after=GREATEST(account_object_deletions.delete_after,EXCLUDED.delete_after),
    reserved_bytes=GREATEST(account_object_deletions.reserved_bytes,EXCLUDED.reserved_bytes),
    owner_user_id=COALESCE(account_object_deletions.owner_user_id,EXCLUDED.owner_user_id);
DELETE FROM attachments a USING messages m WHERE a.message_id=m.id AND m.deleted_at IS NOT NULL;
