-- The public, client-selected installation UUID is not a proof. Only possession
-- of a server-issued random credential can join this separate privacy identity.
CREATE TABLE verified_installations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credential_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(credential_hash)=32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE devices ADD COLUMN verified_installation_id UUID REFERENCES verified_installations(id);
CREATE INDEX devices_verified_installation_idx ON devices (verified_installation_id) WHERE verified_installation_id IS NOT NULL;
CREATE TABLE verified_installation_users (
    installation_id UUID NOT NULL REFERENCES verified_installations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (installation_id,user_id)
);
CREATE INDEX verified_installation_users_user_idx ON verified_installation_users (user_id,installation_id);

-- A blocked account may delete itself. Preserve a manageable tombstone and its
-- installation restrictions until the blocker explicitly removes this entry.
ALTER TABLE user_blocks DROP CONSTRAINT user_blocks_blocked_user_id_fkey;
CREATE TABLE user_block_installations (
    user_id UUID NOT NULL,
    blocked_user_id UUID NOT NULL,
    installation_id UUID NOT NULL REFERENCES verified_installations(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id,blocked_user_id,installation_id),
    FOREIGN KEY (user_id,blocked_user_id) REFERENCES user_blocks(user_id,blocked_user_id) ON DELETE CASCADE
);
CREATE INDEX user_block_installations_lookup_idx ON user_block_installations (user_id,installation_id);

CREATE FUNCTION capture_user_block_installations() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO user_block_installations(user_id,blocked_user_id,installation_id)
    SELECT NEW.user_id,NEW.blocked_user_id,installation_id FROM verified_installation_users
    WHERE user_id=NEW.blocked_user_id ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER user_blocks_capture_installations AFTER INSERT ON user_blocks
    FOR EACH ROW EXECUTE FUNCTION capture_user_block_installations();

CREATE FUNCTION user_device_blocked(viewer_device UUID, peer UUID)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT COALESCE((SELECT CASE WHEN d.user_id=peer THEN false ELSE user_pair_blocked(d.user_id,peer) OR EXISTS (
        SELECT 1 FROM user_block_installations b
        WHERE b.user_id=peer AND b.installation_id=d.verified_installation_id)
        END FROM devices d WHERE d.id=viewer_device),true);
$$;
CREATE FUNCTION user_device_chat_blocked(viewer_device UUID, conversation UUID)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT COALESCE((SELECT CASE WHEN c.type='direct' THEN
        user_device_blocked(viewer_device,CASE WHEN c.user_a_id=d.user_id THEN c.user_b_id ELSE c.user_a_id END)
        ELSE false END FROM chats c JOIN devices d ON d.id=viewer_device
        WHERE c.id=conversation AND (c.type<>'direct' OR d.user_id IN(c.user_a_id,c.user_b_id))),true);
$$;
CREATE FUNCTION user_device_call_blocked(viewer_device UUID, conversation_call UUID)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT COALESCE((SELECT user_device_blocked(viewer_device,
        CASE WHEN c.caller_user_id=d.user_id THEN c.callee_user_id ELSE c.caller_user_id END)
        FROM calls c JOIN devices d ON d.id=viewer_device WHERE c.id=conversation_call
        AND d.user_id IN(c.caller_user_id,c.callee_user_id)),true);
$$;
