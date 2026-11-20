-- The operator enables enforcement after a compatible-client release.
ALTER TABLE server_policy ADD COLUMN device_bound_sessions_required boolean NOT NULL DEFAULT false;
ALTER TABLE sessions ADD COLUMN device_public_key bytea;
ALTER TABLE sessions ADD COLUMN device_key_protection text;
ALTER TABLE sessions ADD CONSTRAINT session_device_key_shape CHECK (
    (device_public_key IS NULL AND device_key_protection IS NULL) OR
    (device_public_key IS NOT NULL AND device_key_protection IS NOT NULL AND octet_length(device_public_key)=65 AND
     device_key_protection IN ('secure_enclave','keychain','strongbox','tee'))
);

-- Scope is the session UUID, or installation UUID during password sign-in.
-- No identity/token/proof is retained. Shared DB uniqueness covers all workers.
CREATE TABLE device_proof_replays (
    scope_id uuid NOT NULL,
    jti uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY(scope_id,jti)
);
CREATE INDEX device_proof_replays_expiry ON device_proof_replays(expires_at);

CREATE FUNCTION preserve_session_device_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.device_public_key IS NOT NULL AND
       (NEW.device_public_key IS DISTINCT FROM OLD.device_public_key OR
        NEW.device_key_protection IS DISTINCT FROM OLD.device_key_protection) THEN
        RAISE EXCEPTION 'A bound session cannot change or remove its device key';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER session_device_binding_immutable BEFORE UPDATE ON sessions
FOR EACH ROW EXECUTE FUNCTION preserve_session_device_binding();
