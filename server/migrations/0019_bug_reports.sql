CREATE TABLE bug_reports (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload JSONB NOT NULL
);
CREATE INDEX bug_reports_created_idx ON bug_reports(created_at);
CREATE INDEX bug_reports_owner_idx ON bug_reports(user_id, created_at);
