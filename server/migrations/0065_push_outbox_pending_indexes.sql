-- Indexes over unfinished push_outbox rows only, so they stay small however
-- many finished rows the table keeps for diagnostics.
--
-- push_outbox_pending_idx: each worker lane claims WHERE provider = '<lane>'
-- ORDER BY created_at, id. The scan walks that provider in claim order and
-- stops at the first due, unlocked row, instead of reading every due row of
-- every provider and sorting them (push_outbox_due_idx stays for other scans).
--
-- push_outbox_pending_device_idx: the APNs alert lane sends a device's alerts
-- in order (a row waits while an older one of the same device is unfinished),
-- and a message's background wake folds into the device's waiting wake. Both
-- look up unfinished rows by device.
--
-- sqlx runs every migration inside a transaction, so CONCURRENTLY is not possible
-- here. A plain CREATE INDEX holds a SHARE lock on push_outbox (inserts and updates
-- wait, reads do not) while it scans the table; message sends insert into
-- push_outbox in their own transaction and wait too. Migrations run at startup,
-- before this instance serves traffic; on a table of a few million rows each build
-- takes seconds. If the table is far larger, build both indexes by hand first with
-- CREATE INDEX CONCURRENTLY under these names and these statements become no-ops.
CREATE INDEX IF NOT EXISTS push_outbox_pending_idx
    ON push_outbox (provider, created_at, id)
    WHERE delivered_at IS NULL;

CREATE INDEX IF NOT EXISTS push_outbox_pending_device_idx
    ON push_outbox (device_id, created_at, id)
    WHERE delivered_at IS NULL;
