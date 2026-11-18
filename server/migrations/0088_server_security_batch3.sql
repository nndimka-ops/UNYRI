-- Additive upgrade; no client fields become mandatory.
ALTER TABLE sessions ADD COLUMN family_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE sessions ADD COLUMN revocation_reason TEXT;
-- Preserve existing refresh lineages, including sockets using a rotated parent.
WITH RECURSIVE families AS (
    SELECT s.id, s.replaced_by, s.family_id FROM sessions s
    WHERE NOT EXISTS (SELECT 1 FROM sessions p WHERE p.replaced_by=s.id)
    UNION ALL
    SELECT s.id, s.replaced_by, f.family_id FROM sessions s JOIN families f ON s.id=f.replaced_by
)
UPDATE sessions s SET family_id=f.family_id FROM families f WHERE s.id=f.id;
CREATE INDEX sessions_active_family ON sessions(family_id) WHERE revoked_at IS NULL;
CREATE INDEX sessions_family ON sessions(family_id);
CREATE INDEX sessions_family_revocation_reason ON sessions(family_id,created_at DESC) WHERE revocation_reason IS NOT NULL;
CREATE INDEX admin_sessions_auth_session ON admin_sessions(auth_session);
CREATE INDEX admin_console_handoffs_auth_session ON admin_console_handoffs(auth_session);
CREATE INDEX admin_console_sessions_auth_session ON admin_console_sessions(auth_session);

ALTER TABLE calls ADD COLUMN caller_device_id UUID REFERENCES devices(id) ON DELETE SET NULL;
ALTER TABLE calls ADD COLUMN caller_session_family UUID;
ALTER TABLE calls ADD COLUMN accepted_session_family UUID;
ALTER TABLE calls ADD COLUMN request_id UUID;
ALTER TABLE calls ADD COLUMN create_response JSONB;
CREATE UNIQUE INDEX calls_create_request ON calls(caller_device_id,request_id) WHERE request_id IS NOT NULL;
CREATE INDEX calls_active_caller_device ON calls(caller_device_id) WHERE state<>'ended';
-- Old active calls have no provable initiating endpoint. End them safely once;
-- established history stays intact and legacy clients recover the ended state.
UPDATE calls SET state='ended',end_reason='failed',ended_at=clock_timestamp() WHERE state<>'ended';
DELETE FROM call_participants_active p USING calls c WHERE p.call_id=c.id AND c.state='ended';

ALTER TABLE verified_installations ADD COLUMN client_installation_id UUID;
CREATE TABLE verified_installation_aliases (
    installation_id UUID NOT NULL REFERENCES verified_installations(id) ON DELETE CASCADE,
    client_installation_id UUID NOT NULL,
    PRIMARY KEY(installation_id,client_installation_id)
);
INSERT INTO verified_installation_aliases SELECT DISTINCT verified_installation_id,installation_id FROM devices WHERE verified_installation_id IS NOT NULL;
CREATE INDEX verified_installation_aliases_client ON verified_installation_aliases(client_installation_id,installation_id);
CREATE FUNCTION installation_devices(client_ids UUID[]) RETURNS SETOF UUID LANGUAGE sql STABLE AS $$
    SELECT d.id FROM devices d WHERE d.installation_id=ANY(client_ids)
    OR d.verified_installation_id IN (SELECT a.installation_id FROM verified_installation_aliases a WHERE a.client_installation_id=ANY(client_ids));
$$;
UPDATE verified_installations v SET client_installation_id=(SELECT d.installation_id FROM devices d WHERE d.verified_installation_id=v.id ORDER BY d.created_at,d.id LIMIT 1);

CREATE OR REPLACE FUNCTION conference_session_active(admitted_session UUID, account UUID, device UUID)
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
        AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id IN (SELECT d.installation_id UNION SELECT va.client_installation_id FROM verified_installation_aliases va WHERE va.installation_id=d.verified_installation_id) AND (b.until_date IS NULL OR b.until_date>now())))
    AND NOT EXISTS(SELECT 1 FROM server_suspensions b WHERE b.user_id=account AND (b.until_date IS NULL OR b.until_date>now()));
$$;

-- Constant-time endpoint lookup using the request's indexed session id. A socket
-- may retain a rotated parent, but must still have a live leaf in that family.
CREATE FUNCTION call_endpoint_allowed(call_id UUID, actor UUID, device UUID, admitted_session UUID)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS(
        SELECT 1 FROM calls c JOIN sessions s ON s.id=admitted_session AND s.user_id=actor AND s.device_id=device
        JOIN devices d ON d.id=device AND d.user_id=actor AND d.revoked_at IS NULL
        LEFT JOIN devices caller ON caller.id=c.caller_device_id
        WHERE c.id=call_id AND NOT user_device_call_blocked(device,c.id)
        AND (c.state='ended' OR (caller.id IS NOT NULL AND caller.revoked_at IS NULL))
        AND EXISTS(SELECT 1 FROM sessions leaf WHERE leaf.family_id=s.family_id AND leaf.revoked_at IS NULL AND leaf.expires_at>now())
        AND ((c.caller_user_id=actor AND c.caller_device_id=device AND c.caller_session_family=s.family_id)
          OR (c.callee_user_id=actor AND (c.accepted_device_id IS NULL OR
              (c.accepted_device_id=device AND c.accepted_session_family=s.family_id))))
        AND (c.state='ended' OR c.accepted_device_id IS NULL OR EXISTS(SELECT 1 FROM devices callee WHERE callee.id=c.accepted_device_id AND callee.revoked_at IS NULL))
    );
$$;

-- All revocation writers (owner devices, admin, recovery, device retirement)
-- terminate either endpoint atomically, even if the process dies after commit.
CREATE FUNCTION end_revoked_device_calls() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE sessions SET revoked_at=COALESCE(revoked_at,clock_timestamp()),
        revocation_reason=COALESCE(revocation_reason,'device_revoked')
        WHERE device_id=NEW.id AND (revoked_at IS NULL OR replaced_by IS NULL);
    WITH ended AS (
        UPDATE calls SET state='ended',end_reason='device_revoked',ended_at=clock_timestamp()
        WHERE state<>'ended' AND (caller_device_id=NEW.id OR accepted_device_id=NEW.id) RETURNING id
    ) DELETE FROM call_participants_active WHERE call_id IN (SELECT id FROM ended);
    RETURN NEW;
END;
$$;
CREATE TRIGGER devices_end_revoked_calls AFTER UPDATE OF revoked_at ON devices
    FOR EACH ROW WHEN (OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL)
    EXECUTE FUNCTION end_revoked_device_calls();
