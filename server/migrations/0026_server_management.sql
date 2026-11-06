ALTER TABLE chat_reports DROP CONSTRAINT chat_reports_review_status_check;
ALTER TABLE chat_reports ADD CONSTRAINT chat_reports_review_status_check CHECK (review_status IN ('open','in_progress','resolved','dismissed'));
ALTER TABLE chat_reports ADD COLUMN decision_reason TEXT NOT NULL DEFAULT '';
ALTER TABLE chat_reports ADD COLUMN reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE chat_reports ADD COLUMN reviewed_at TIMESTAMPTZ;
CREATE TABLE server_policy (
 id BOOLEAN PRIMARY KEY DEFAULT true CHECK(id),
 name TEXT NOT NULL DEFAULT 'COCO', operator TEXT NOT NULL DEFAULT '', contact TEXT NOT NULL DEFAULT '',
 rules TEXT NOT NULL DEFAULT '', retention TEXT NOT NULL DEFAULT '',
 max_file_bytes BIGINT NOT NULL DEFAULT 104861696 CHECK(max_file_bytes BETWEEN 4097 AND 104861696),
 user_quota_bytes BIGINT NOT NULL DEFAULT 0 CHECK(user_quota_bytes >= 0),
 storage_budget_bytes BIGINT NOT NULL DEFAULT 0 CHECK(storage_budget_bytes >= 0)
);
INSERT INTO server_policy(id) VALUES(true);
CREATE INDEX IF NOT EXISTS attachments_uploader_usage ON attachments(uploader_user_id);
CREATE TABLE storage_cleanup_plans (
 id UUID PRIMARY KEY, actor UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 session_hash TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL, attachment_ids UUID[] NOT NULL
);
