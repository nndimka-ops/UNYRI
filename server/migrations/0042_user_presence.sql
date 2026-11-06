-- Last server-confirmed foreground activity, never derived from push delivery.
ALTER TABLE users ADD COLUMN last_seen_at TIMESTAMPTZ;
