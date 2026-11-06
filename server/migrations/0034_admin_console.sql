-- ADMIN CONSOLE PACK 3 - server-hosted administration console.
--
-- Why two tables and nothing else:
--
-- The console does not introduce a second security model. Both tables are bound
-- with `REFERENCES admin_sessions(token_hash) ON DELETE CASCADE`, so every
-- existing revocation rule already wired to the native administration session
-- (explicit lock, signing-key rotation, 2FA reset, role revoke, auth-session
-- revocation, account deletion) destroys console authority in the same
-- statement. Nothing in the console path can outlive the native session it came
-- from, and no new revocation code path has to be remembered.
--
-- Only hashes are stored: a database read yields no usable credential.

-- One-time handoff, issued by an unlocked native client and redeemed by a
-- browser. It is a credential for exactly one exchange.
CREATE TABLE admin_console_handoffs (
    token_hash TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES server_administrators(user_id) ON DELETE CASCADE,
    auth_session UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    -- The approved native admin session that authorized this handoff.
    admin_session TEXT NOT NULL REFERENCES admin_sessions(token_hash) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('owner', 'moderator')),
    expires_at TIMESTAMPTZ NOT NULL,
    -- Set exactly once, by the UPDATE that consumes it: replay is impossible by
    -- construction rather than by a check that a later edit could remove.
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Sweep of expired/consumed handoffs and per-user revocation lookups.
CREATE INDEX admin_console_handoffs_expiry_idx ON admin_console_handoffs (expires_at);

-- A browser session, derived from one handoff. The token is a cookie value and
-- the CSRF token is a second value that the page must echo in a header, so a
-- cross-site request cannot be forged even though the cookie is sent
-- automatically (SameSite=Strict is the second, independent barrier).
CREATE TABLE admin_console_sessions (
    token_hash TEXT PRIMARY KEY,
    csrf_hash TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES server_administrators(user_id) ON DELETE CASCADE,
    auth_session UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    admin_session TEXT NOT NULL REFERENCES admin_sessions(token_hash) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('owner', 'moderator')),
    last_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Absolute expiry, independent of the idle window kept in `last_seen`.
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Idle and absolute expiry are bounds on the whole set, so the sweep is by user.
CREATE INDEX admin_console_sessions_owner_idx ON admin_console_sessions (user_id, last_seen DESC);
-- Closing every console for a native session (lock, rotate) is a cascade; this
-- index keeps the reverse lookup from the audit trail cheap.
CREATE INDEX admin_console_sessions_admin_idx ON admin_console_sessions (admin_session);
