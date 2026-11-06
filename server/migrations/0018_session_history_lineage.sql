-- Reverse traversal from a refresh token to the original login.
CREATE INDEX IF NOT EXISTS sessions_replaced_by_idx ON sessions(replaced_by) WHERE replaced_by IS NOT NULL;
