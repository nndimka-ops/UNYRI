-- Defaults retain existing visibility. Presence is projected at every read;
-- persisted last_seen_at is never itself an authorization decision.
CREATE TABLE user_privacy (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    online_visibility text NOT NULL DEFAULT 'everyone' CHECK (online_visibility IN ('everyone','nobody')),
    last_seen_visibility text NOT NULL DEFAULT 'everyone' CHECK (last_seen_visibility IN ('everyone','nobody')),
    always_allow uuid[] NOT NULL DEFAULT '{}',
    never_allow uuid[] NOT NULL DEFAULT '{}',
    send_read_receipts boolean NOT NULL DEFAULT true,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (cardinality(always_allow) <= 256 AND cardinality(never_allow) <= 256)
);

CREATE FUNCTION user_presence_visible(owner uuid, viewer uuid, field text)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT owner = viewer OR COALESCE((
        SELECT NOT (viewer = ANY(p.never_allow))
            AND (viewer = ANY(p.always_allow)
                OR CASE field WHEN 'online' THEN p.online_visibility
                              WHEN 'last_seen' THEN p.last_seen_visibility ELSE 'nobody' END = 'everyone')
        FROM user_privacy p WHERE p.user_id = owner
    ), true)
$$;

-- Account read state continues to clear own unread and restore across devices.
-- Only this separate column may be exposed to other accounts. Re-enabling the
-- preference cannot publish reads performed while the preference was disabled.
ALTER TABLE message_receipts ADD COLUMN exposed_read_at timestamptz;
UPDATE message_receipts SET exposed_read_at = read_at WHERE read_at IS NOT NULL;
