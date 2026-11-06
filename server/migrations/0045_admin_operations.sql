-- Administrative operations centre. Additive and backward-compatible: older
-- server binaries continue to use the original server_policy columns.

ALTER TABLE server_policy
    ADD COLUMN min_android_version TEXT NOT NULL DEFAULT '',
    ADD COLUMN min_ios_version TEXT NOT NULL DEFAULT '',
    ADD COLUMN min_desktop_version TEXT NOT NULL DEFAULT '',
    ADD COLUMN registration_open BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN automatic_login_protection BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN login_ip_limit BIGINT NOT NULL DEFAULT 10 CHECK (login_ip_limit BETWEEN 1 AND 10),
    ADD COLUMN login_account_limit BIGINT NOT NULL DEFAULT 20 CHECK (login_account_limit BETWEEN 1 AND 20);

CREATE TABLE server_announcements (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL CHECK (char_length(title) BETWEEN 1 AND 120),
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    severity TEXT NOT NULL CHECK (severity IN ('info','warning','critical')),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    disabled_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX server_announcements_active_idx
    ON server_announcements (expires_at DESC, created_at DESC)
    WHERE disabled_at IS NULL;

-- Codes issued by an administrator are intentionally short-lived. Existing
-- user-created recovery codes keep their previous no-expiry behaviour.
ALTER TABLE account_recovery
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN issued_by_admin UUID;

