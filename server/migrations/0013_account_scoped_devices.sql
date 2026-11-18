-- Этап P0: account-scoped device identity.
-- Before: devices.id was set to the client's installation UUID, so a single
-- physical installation could never be used by more than one account.
-- After: devices.id stays a server-generated account-scoped identity, and the
-- client installation UUID moves to a dedicated `installation_id` column.
-- Non-destructive: existing rows keep their id and get installation_id = id.

ALTER TABLE devices ADD COLUMN installation_id uuid;

UPDATE devices SET installation_id = id WHERE installation_id IS NULL;

ALTER TABLE devices ALTER COLUMN installation_id SET NOT NULL;

-- Same-account relogin dedup: one account-device per (user, installation).
CREATE UNIQUE INDEX devices_user_installation_key ON devices (user_id, installation_id);
