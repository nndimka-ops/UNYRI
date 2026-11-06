-- ADMIN PRODUCT PACK 2 - cross-platform moderation core (server schema).
--
-- Forward-only. Historical migrations are never edited.
--
-- This migration is intentionally additive: no existing column, constraint or
-- row is rewritten, so a rollback of the application binary (not the schema)
-- leaves the previous admin panel fully functional.

-- ---------------------------------------------------------------------------
-- Internal administrator notes.
--
-- The author is deliberately NOT a foreign key. Deleting an administrator
-- account must not silently erase moderation context - the same rule already
-- applied to admin_audit.actor and installation_suspensions.actor.
-- Notes are append-only: there is no update/delete path, so the moderation
-- record cannot be rewritten after the fact.
-- ---------------------------------------------------------------------------
CREATE TABLE admin_notes (
    id BIGSERIAL PRIMARY KEY,
    subject_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    author_user_id UUID NOT NULL,
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Serves "list one user's notes, newest first" (cursor pagination).
CREATE INDEX admin_notes_subject_idx ON admin_notes (subject_user_id, created_at DESC, id DESC);
-- Serves "which notes did this administrator write" during a review.
CREATE INDEX admin_notes_author_idx ON admin_notes (author_user_id);

-- ---------------------------------------------------------------------------
-- Structured, non-secret context for an audit event.
--
-- Existing writers keep the default, so this is additive for every current
-- caller. It exists so device-scoped moderation can record WHICH installation
-- was affected without stuffing machine-readable data into the free-text
-- reason, and so clients can show technical detail as expandable context
-- instead of raw JSON as the main experience.
-- Never used for message content, tokens, secrets or key material.
-- ---------------------------------------------------------------------------
ALTER TABLE admin_audit ADD COLUMN detail JSONB NOT NULL DEFAULT '{}'::jsonb;

-- ---------------------------------------------------------------------------
-- Technical feedback (bug reports) had no lifecycle at all: every submission
-- was permanently "new", so the dashboard could not report an unresolved
-- count and moderators could not mark feedback as handled.
-- ---------------------------------------------------------------------------
ALTER TABLE bug_reports ADD COLUMN review_status TEXT NOT NULL DEFAULT 'open'
    CHECK (review_status IN ('open', 'resolved', 'dismissed'));
ALTER TABLE bug_reports ADD COLUMN review_note TEXT NOT NULL DEFAULT '';
ALTER TABLE bug_reports ADD COLUMN decision_reason TEXT NOT NULL DEFAULT '';
ALTER TABLE bug_reports ADD COLUMN reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE bug_reports ADD COLUMN reviewed_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------------
-- Indexes for cursor pagination and the new filtered views.
--
-- Every list endpoint orders by (created_at DESC, id DESC) and pages with a
-- row-comparison cursor; the composite indexes below match that ordering
-- exactly, so a page is served by an index scan with no sort step.
-- ---------------------------------------------------------------------------

-- Default reports view (all statuses, newest first).
CREATE INDEX chat_reports_admin_order_idx ON chat_reports (created_at DESC, id DESC);
-- Primary moderation filter: "show me everything that is still open".
CREATE INDEX chat_reports_admin_status_idx
    ON chat_reports (review_status, created_at DESC, id DESC);
-- Moderators filter by report type; chat_reports.reason is a 7-value enum, so
-- this index is selective on created_at rather than on reason.
CREATE INDEX chat_reports_admin_reason_idx ON chat_reports (reason, created_at DESC, id DESC);
-- NOTE: chat_reports_reported_idx (reported_user_id, created_at DESC) from
-- migration 0022 already serves the "reports involving this user" filter.

-- Default feedback view.
CREATE INDEX bug_reports_admin_order_idx ON bug_reports (created_at DESC, id DESC);
-- Unresolved counter + status filter.
CREATE INDEX bug_reports_admin_status_idx
    ON bug_reports (review_status, created_at DESC, id DESC);
-- Platform filter (the payload is JSONB; the expression index keeps it cheap).
CREATE INDEX bug_reports_admin_platform_idx
    ON bug_reports ((payload ->> 'platform'), created_at DESC, id DESC);

-- NOTE: user administration is ordered by username_normalized, which migration
-- 0001 already indexes uniquely (users_username_normalized_key). Because that
-- column is UNIQUE, the ordering is total, so the cursor can compare on the
-- username alone and no second index is needed here. The trigram index from
-- migration 0002 keeps accelerating the substring *search* itself.
--
-- Administrator search additionally matches the display name, because a report
-- identifies people by display name. Normal users never get that capability
-- (their search is username-only), so this index serves the admin surface.
CREATE INDEX users_display_name_trgm_idx
    ON users USING GIN (lower(display_name) gin_trgm_ops)
    WHERE deleted_at IS NULL;

-- The audit journal grows without bound and now supports four filters.
CREATE INDEX admin_audit_order_idx ON admin_audit (created_at DESC, id DESC);
CREATE INDEX admin_audit_actor_idx ON admin_audit (actor, created_at DESC, id DESC);
CREATE INDEX admin_audit_action_idx ON admin_audit (action, created_at DESC, id DESC);
-- Also serves the user-scoped moderation timeline, which reads audit rows by
-- target (the moderated account).
CREATE INDEX admin_audit_target_idx ON admin_audit (target, created_at DESC, id DESC);
