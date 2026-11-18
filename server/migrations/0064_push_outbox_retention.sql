-- Finished push_outbox rows are purged after 7 days (push::outbox::purge_finished).
-- This index keeps each batched DELETE an index range scan instead of a full scan.
--
-- sqlx runs every migration inside a transaction, so CONCURRENTLY is not possible
-- here. A plain CREATE INDEX holds a SHARE lock on push_outbox (inserts and updates
-- wait, reads do not) for the duration of the build. Migrations run at startup,
-- before this instance serves traffic; on a table of a few million rows the build
-- takes seconds. If the table is far larger, build the index by hand first with
-- CREATE INDEX CONCURRENTLY under this name and this statement becomes a no-op.
CREATE INDEX IF NOT EXISTS push_outbox_finished_idx
    ON push_outbox (delivered_at)
    WHERE delivered_at IS NOT NULL;
