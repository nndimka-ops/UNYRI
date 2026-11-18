-- Ringing confirmation (docs/CALL_RINGING_SPEC.md). Booleans only, never timestamps,
-- and both call bits exist only while the call is ringing.
SET LOCAL lock_timeout = '5s';

ALTER TABLE devices ADD COLUMN call_ring_ack boolean NOT NULL DEFAULT false;
ALTER TABLE calls
    ADD COLUMN callee_ack_expected boolean NOT NULL DEFAULT false,
    ADD COLUMN callee_ringing      boolean NOT NULL DEFAULT false;

CREATE FUNCTION calls_clear_ringing_bits() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.callee_ack_expected := false;
    NEW.callee_ringing := false;
    RETURN NEW;
END $$;
-- Every path that leaves 'ringing' (accept, end_call, sweep, block, account-wide
-- revoke, account recovery, admin) updates `state`, so one trigger covers them all.
CREATE TRIGGER calls_clear_ringing_bits BEFORE UPDATE OF state ON calls
    FOR EACH ROW WHEN (NEW.state <> 'ringing') EXECUTE FUNCTION calls_clear_ringing_bits();

ALTER TABLE calls ADD CONSTRAINT calls_ringing_bits_only_while_ringing
    CHECK (state = 'ringing' OR (NOT callee_ack_expected AND NOT callee_ringing)) NOT VALID;
-- Existing rows have both bits false from the column defaults. Enforce new
-- writes without scanning historical calls while holding the DDL locks.
