-- Conference media travels through a separately configured self-hosted SFU.
-- Only an invitation verifier is stored here, never the clients' media key.
CREATE TABLE conferences (
    id UUID PRIMARY KEY,
    -- Cleanup ownership must survive account/device/session deletion.
    owner_user_id UUID NOT NULL,
    owner_device_id UUID NOT NULL,
    owner_session_id UUID NOT NULL,
    title TEXT NOT NULL CHECK (char_length(title) BETWEEN 1 AND 80),
    max_participants SMALLINT NOT NULL CHECK (max_participants BETWEEN 2 AND 30),
    invite_secret_hash BYTEA NOT NULL CHECK (octet_length(invite_secret_hash)=32),
    state TEXT NOT NULL CHECK (state IN ('provisioning','open','ending','ended')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A lost create response must not reserve an invisible owner's room quota
    -- for four hours. Once used, an empty room retains its invitation lifetime.
    first_joined_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    end_reason TEXT,
    sfu_deleted_at TIMESTAMPTZ
);
CREATE INDEX conferences_cleanup_idx ON conferences(state,expires_at) WHERE state<>'ended';
CREATE TABLE conference_members (
    conference_id UUID NOT NULL REFERENCES conferences(id) ON DELETE CASCADE,
    user_id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    session_id UUID NOT NULL,
    admission_id UUID NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_authorized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(conference_id,user_id)
);
CREATE INDEX conference_members_room_idx ON conference_members(conference_id);

-- Both kinds of admission lock the same account row. These triggers also cover
-- existing private-call handlers and make cross-kind admission transactional.
CREATE FUNCTION conference_check_busy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM users WHERE id=NEW.user_id FOR UPDATE;
    IF TG_TABLE_NAME='conference_members' THEN
        IF EXISTS(SELECT 1 FROM call_participants_active WHERE user_id=NEW.user_id) THEN
            RAISE EXCEPTION 'media participant busy' USING ERRCODE='23505';
        END IF;
    ELSE
        IF EXISTS(SELECT 1 FROM conference_members WHERE user_id=NEW.user_id) THEN
            RAISE EXCEPTION 'media participant busy' USING ERRCODE='23505';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER private_call_conference_busy BEFORE INSERT ON call_participants_active
FOR EACH ROW EXECUTE FUNCTION conference_check_busy();
CREATE TRIGGER conference_private_call_busy BEFORE INSERT ON conference_members
FOR EACH ROW EXECUTE FUNCTION conference_check_busy();

-- A refresh rotation preserves authorization. Logout, device/account deletion,
-- expiry, and server/installation suspensions do not. Follow only the original
-- admitted session's family, never an unrelated later login on the device.
CREATE FUNCTION conference_session_active(admitted_session UUID, account UUID, device UUID)
RETURNS boolean LANGUAGE sql STABLE AS $$
    WITH RECURSIVE family AS (
        SELECT id,replaced_by,revoked_at,expires_at FROM sessions
        WHERE id=admitted_session AND user_id=account AND device_id=device
        UNION
        SELECT s.id,s.replaced_by,s.revoked_at,s.expires_at FROM sessions s
        JOIN family f ON s.id=f.replaced_by WHERE s.user_id=account AND s.device_id=device
    )
    SELECT EXISTS(SELECT 1 FROM family WHERE revoked_at IS NULL AND expires_at>now())
    AND EXISTS(SELECT 1 FROM users WHERE id=account AND deleted_at IS NULL)
    AND EXISTS(SELECT 1 FROM devices d WHERE d.id=device AND d.user_id=account AND d.revoked_at IS NULL
        AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id=d.installation_id AND (b.until_date IS NULL OR b.until_date>now())))
    AND NOT EXISTS(SELECT 1 FROM server_suspensions b WHERE b.user_id=account AND (b.until_date IS NULL OR b.until_date>now()));
$$;
