-- Existing administrators keep password + paper key + authenticator.
-- The policy is server-owned; clients cannot weaken it at unlock time.
ALTER TABLE server_administrators
    ADD COLUMN auth_policy TEXT NOT NULL DEFAULT 'both'
        CHECK (auth_policy IN ('authenticator','paper_key','both')),
    ADD COLUMN auth_revision BIGINT NOT NULL DEFAULT 0 CHECK (auth_revision >= 0),
    ADD COLUMN pending_policy TEXT CHECK (pending_policy IN ('authenticator','paper_key','both')),
    ADD COLUMN pending_revision BIGINT;
ALTER TABLE admin_sessions
    ADD COLUMN auth_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE admin_key_challenges DROP CONSTRAINT admin_key_challenges_purpose_check;
ALTER TABLE admin_key_challenges ADD CONSTRAINT admin_key_challenges_purpose_check
    CHECK (purpose IN ('unlock','rotate','authorize','enroll'));

-- An administrator may open a browser console once per native session.
-- Retain only the newest duplicate if old clients raced before this constraint.
DELETE FROM admin_console_sessions old USING admin_console_sessions newer
 WHERE old.admin_session=newer.admin_session
   AND (old.created_at,old.token_hash)<(newer.created_at,newer.token_hash);
CREATE UNIQUE INDEX admin_console_sessions_one_per_native
    ON admin_console_sessions(admin_session);
