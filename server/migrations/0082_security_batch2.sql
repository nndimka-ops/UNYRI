-- Moderation context is independent of chat and account lifetimes.
ALTER TABLE chat_reports
    DROP CONSTRAINT chat_reports_chat_id_fkey,
    DROP CONSTRAINT chat_reports_reporter_user_id_fkey,
    ALTER COLUMN chat_id DROP NOT NULL,
    ALTER COLUMN reporter_user_id DROP NOT NULL,
    ADD CONSTRAINT chat_reports_chat_id_fkey FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE SET NULL,
    ADD CONSTRAINT chat_reports_reporter_user_id_fkey FOREIGN KEY (reporter_user_id) REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN chat_id_snapshot uuid,
    ADD COLUMN chat_created_at_snapshot timestamptz,
    ADD COLUMN reporter_user_id_snapshot uuid,
    ADD COLUMN reported_user_id_snapshot uuid,
    ADD COLUMN participants_snapshot jsonb NOT NULL DEFAULT '[]';

UPDATE chat_reports r SET chat_id_snapshot=r.chat_id, chat_created_at_snapshot=c.created_at,
    reporter_user_id_snapshot=r.reporter_user_id, reported_user_id_snapshot=r.reported_user_id,
    participants_snapshot=COALESCE((SELECT jsonb_agg(jsonb_build_object(
        'id',u.id,'username',u.username,'display_name',u.display_name) ORDER BY u.id)
        FROM chat_members cm JOIN users u ON u.id=cm.user_id WHERE cm.chat_id=r.chat_id),'[]')
FROM chats c WHERE c.id=r.chat_id;
ALTER TABLE chat_reports ALTER COLUMN chat_id_snapshot SET NOT NULL,
    ALTER COLUMN chat_created_at_snapshot SET NOT NULL,
    ALTER COLUMN reporter_user_id_snapshot SET NOT NULL;

CREATE FUNCTION snapshot_chat_report() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.chat_id_snapshot := NEW.chat_id;
    NEW.reporter_user_id_snapshot := NEW.reporter_user_id;
    NEW.reported_user_id_snapshot := NEW.reported_user_id;
    SELECT created_at INTO NEW.chat_created_at_snapshot FROM chats WHERE id=NEW.chat_id;
    SELECT COALESCE(jsonb_agg(jsonb_build_object('id',u.id,'username',u.username,
        'display_name',u.display_name) ORDER BY u.id),'[]') INTO NEW.participants_snapshot
        FROM chat_members cm JOIN users u ON u.id=cm.user_id WHERE cm.chat_id=NEW.chat_id;
    RETURN NEW;
END $$;
CREATE TRIGGER chat_reports_snapshot BEFORE INSERT ON chat_reports
    FOR EACH ROW EXECUTE FUNCTION snapshot_chat_report();

-- Non-content events must never acknowledge app activity, including old rows.
CREATE OR REPLACE FUNCTION fan_out_message_receipts() RETURNS trigger AS $$
BEGIN
    IF NEW.kind = 'content' THEN
        INSERT INTO message_receipts (message_id,user_id,sequence)
        SELECT NEW.id,cm.user_id,NEW.sequence FROM chat_members cm
        WHERE cm.chat_id=NEW.chat_id AND cm.user_id<>NEW.sender_id AND cm.left_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DELETE FROM message_receipts r USING messages m WHERE r.message_id=m.id AND m.kind<>'content';

-- The recipient's own address book defines a contact (one-way, active only).
CREATE FUNCTION user_delivery_visible(owner uuid, viewer uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT owner=viewer
        OR COALESCE((SELECT online_visibility FROM user_privacy WHERE user_id=owner),'everyone')='everyone'
        OR EXISTS(SELECT 1 FROM user_contacts WHERE owner_id=owner AND contact_id=viewer AND removed_at IS NULL)
$$;
