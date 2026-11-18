-- N.2R: server-authoritative liveness for CONNECTED calls. The backend is not
-- a media proxy, so it cannot observe P2P media; instead every device that is
-- actually in a call sends a periodic `call.keep_alive` over the signaling
-- socket, refreshing last_activity_at. A connected call whose
-- last_activity_at goes stale (every device killed / network lost with no
-- chance to send hangup) is swept as `failed`, releasing the
-- call_participants_active "busy" rows that would otherwise block both users
-- forever.
ALTER TABLE calls ADD COLUMN last_activity_at timestamptz NOT NULL DEFAULT now();
