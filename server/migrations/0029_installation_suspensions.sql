-- Source account is intentionally not an FK: deleting an account must not erase a ban.
CREATE TABLE installation_suspensions (
 installation_id UUID NOT NULL,
 source_user_id UUID NOT NULL,
 until_date TIMESTAMPTZ,
 reason TEXT NOT NULL,
 actor UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(installation_id,source_user_id)
);
CREATE INDEX installation_suspensions_source ON installation_suspensions(source_user_id);
CREATE INDEX IF NOT EXISTS devices_installation_id ON devices(installation_id);
