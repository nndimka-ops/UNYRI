-- Per-account signaling budget (audit server-038) sums by sender.
CREATE INDEX IF NOT EXISTS direct_file_signals_sender ON direct_file_signals(sender_user_id);
