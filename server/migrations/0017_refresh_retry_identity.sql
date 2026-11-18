-- A random client attempt secret distinguishes retrying a lost response from
-- reuse of an old refresh credential. Only its hash is retained.
ALTER TABLE sessions ADD COLUMN refresh_request_hash TEXT;
