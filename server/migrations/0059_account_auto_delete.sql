-- Opt-in only. Existing accounts keep NULL and cannot become due on upgrade.
ALTER TABLE users
    ADD COLUMN auto_delete_inactive_days INTEGER
        CHECK (auto_delete_inactive_days BETWEEN 1 AND 1095),
    ADD COLUMN auto_delete_last_active_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN auto_delete_revision BIGINT NOT NULL DEFAULT 0
        CHECK (auto_delete_revision >= 0);
CREATE INDEX users_auto_delete_candidates ON users(auto_delete_last_active_at, id)
    WHERE auto_delete_inactive_days IS NOT NULL AND deleted_at IS NULL;

-- Account deletion removes the call itself. Keep the other participant's
-- deletion projection so devices which missed realtime cannot restore it.
ALTER TABLE call_history_deletions
    ADD COLUMN call_created_at TIMESTAMPTZ;
UPDATE call_history_deletions d SET call_created_at=c.created_at FROM calls c WHERE c.id=d.call_id;
ALTER TABLE call_history_deletions DROP CONSTRAINT call_history_deletions_call_id_fkey;

-- Redis may be temporarily unavailable at account deletion. Ownership of the
-- user-tagged performance samples must outlive the users row until purge wins.
CREATE TABLE account_performance_deletions (
    user_id UUID PRIMARY KEY,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
