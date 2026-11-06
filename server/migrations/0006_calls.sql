-- 1:1 WebRTC call signaling/state coordination. Backend never stores media,
-- and only stores SDP/ICE transiently in memory during relay (never
-- persisted to this table) - see calls::handlers for the relay path.

CREATE TABLE calls (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    caller_user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    callee_user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    media_kind           TEXT NOT NULL CHECK (media_kind IN ('audio', 'video')),
    state                TEXT NOT NULL CHECK (state IN ('created', 'ringing', 'accepted', 'connecting', 'connected', 'ended')),
    end_reason           TEXT CHECK (end_reason IN ('rejected', 'cancelled', 'busy', 'missed', 'hangup', 'failed', 'timeout')),
    -- Winning callee device once accepted (multi-device first-answer-wins).
    accepted_device_id   UUID REFERENCES devices (id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at          TIMESTAMPTZ,
    connected_at         TIMESTAMPTZ,
    ended_at             TIMESTAMPTZ,
    -- Server-authoritative deadlines enforced by a background sweep, not
    -- just an Android-side timer (a caller/callee that vanishes must not
    -- leave the call ringing/connecting forever).
    ringing_deadline     TIMESTAMPTZ,
    connecting_deadline  TIMESTAMPTZ,
    CONSTRAINT calls_caller_ne_callee CHECK (caller_user_id <> callee_user_id),
    CONSTRAINT calls_end_reason_requires_ended CHECK (
        (state = 'ended' AND end_reason IS NOT NULL) OR (state <> 'ended' AND end_reason IS NULL)
    )
);

CREATE INDEX calls_caller_idx ON calls (caller_user_id);
CREATE INDEX calls_callee_idx ON calls (callee_user_id);
-- Background timeout sweep scans only non-terminal calls.
CREATE INDEX calls_active_state_idx ON calls (state) WHERE state <> 'ended';

-- One row per user while they are involved in a non-terminal call - PK
-- collision is what atomically enforces "at most one active call per user"
-- (and therefore also "at most one active call per pair") with zero
-- advisory locks: creating a call inserts both participants' rows in the
-- SAME transaction as the `calls` insert, so a concurrent second call
-- attempt for either user simply fails the INSERT and rolls back. Deleted
-- (both rows) the moment a call reaches a terminal state.
CREATE TABLE call_participants_active (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    call_id UUID NOT NULL REFERENCES calls (id) ON DELETE CASCADE
);

CREATE INDEX call_participants_active_call_id_idx ON call_participants_active (call_id);
