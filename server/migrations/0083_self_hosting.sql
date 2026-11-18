-- Self-hosting bootstrap is permanently retired by the first owner, including
-- owners granted through the existing CLI. Never reopen it after deletion.
CREATE TABLE server_bootstrap (
    id BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),
    completed BOOLEAN NOT NULL DEFAULT false,
    code_hash BYTEA CHECK (code_hash IS NULL OR octet_length(code_hash) = 32)
);
INSERT INTO server_bootstrap(id, completed)
VALUES(true, EXISTS(SELECT 1 FROM server_administrators WHERE role='owner'));

CREATE FUNCTION retire_owner_bootstrap() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.role = 'owner' THEN
        UPDATE server_bootstrap SET completed=true, code_hash=NULL WHERE id=true;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER retire_owner_bootstrap AFTER INSERT OR UPDATE OF role
ON server_administrators FOR EACH ROW EXECUTE FUNCTION retire_owner_bootstrap();

ALTER TABLE server_policy ADD COLUMN registration_mode TEXT NOT NULL DEFAULT 'open'
    CHECK (registration_mode IN ('open','invite_only'));

CREATE TABLE registration_invites (
    id UUID PRIMARY KEY,
    -- A public random selector permits indexed lookup; the secret is compared
    -- in constant time in the backend, never as a variable-length SQL string.
    code_hash BYTEA NOT NULL CHECK (octet_length(code_hash)=32),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now()+interval '7 days',
    max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses BETWEEN 1 AND 10000),
    uses INTEGER NOT NULL DEFAULT 0 CHECK (uses >= 0 AND uses <= max_uses),
    revoked_at TIMESTAMPTZ
);
CREATE INDEX registration_invites_created_idx ON registration_invites(created_at DESC, id DESC);
