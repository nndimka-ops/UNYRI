-- B-033: repair only the legacy login-derived moderation fallback. A name
-- subsequently chosen by the account is left alone. Re-running is a no-op.
UPDATE users u
SET display_name = COALESCE(
        '@' || NULLIF(i.public_username, ''),
        CASE WHEN length(i.unyri_number::text) = 9
            THEN substring(i.unyri_number::text, 1, 3) || U&'\202F' ||
                 substring(i.unyri_number::text, 4, 3) || U&'\202F' ||
                 substring(i.unyri_number::text, 7, 3)
            ELSE i.unyri_number::text END, ''),
    profile_version = u.profile_version + 1
FROM (SELECT u0.id, i0.public_username, i0.unyri_number
      FROM users u0 LEFT JOIN user_identities i0 ON i0.user_id = u0.id) i
WHERE u.id = i.id
  AND lower(u.display_name) = u.username_normalized
  AND EXISTS (SELECT 1 FROM admin_audit a WHERE a.target = u.id
      AND a.action = 'profile:moderate' AND a.detail->>'display_name_cleared' = 'true');

-- Report retention contains public identity only, even after rename/deletion.
CREATE OR REPLACE FUNCTION snapshot_chat_report() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.chat_id_snapshot := NEW.chat_id;
    NEW.reporter_user_id_snapshot := NEW.reporter_user_id;
    NEW.reported_user_id_snapshot := NEW.reported_user_id;
    SELECT created_at INTO NEW.chat_created_at_snapshot FROM chats WHERE id=NEW.chat_id;
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'id',u.id,'user_id',u.id,'display_name',u.display_name,
        'public_username',i.public_username,'unyri_number',i.unyri_number::text)
        ORDER BY u.id),'[]'::jsonb) INTO NEW.participants_snapshot
        FROM chat_members cm JOIN users u ON u.id=cm.user_id
        LEFT JOIN user_identities i ON i.user_id=u.id WHERE cm.chat_id=NEW.chat_id;
    RETURN NEW;
END $$;

UPDATE chat_reports r SET participants_snapshot = COALESCE((
    SELECT jsonb_agg(jsonb_build_object(
        'id',p->>'id','user_id',COALESCE(p->>'user_id',p->>'id'),
        'display_name',CASE WHEN u.id IS NOT NULL THEN u.display_name
            WHEN lower(p->>'display_name') IN (lower(p->>'username'),p->>'username_normalized') THEN ''
            ELSE COALESCE(p->>'display_name','') END,
        'public_username',CASE WHEN u.id IS NOT NULL THEN i.public_username ELSE p->>'public_username' END,
        'unyri_number',CASE WHEN u.id IS NOT NULL THEN i.unyri_number::text ELSE p->>'unyri_number' END)
        ORDER BY p->>'id')
    FROM jsonb_array_elements(r.participants_snapshot) p
    LEFT JOIN users u ON u.id::text=p->>'id'
    LEFT JOIN user_identities i ON i.user_id=u.id), '[]'::jsonb);

-- Old account-deletion audit rows outlive the deleted account. Keep its UUID
-- when its public number is no longer available; never retain the login.
UPDATE admin_audit a SET detail = (a.detail - 'username' - 'username_normalized') ||
    jsonb_build_object('user_id',a.target,'identity',COALESCE(
        (SELECT i.unyri_number::text FROM user_identities i WHERE i.user_id=a.target),
        a.target::text))
WHERE a.action='account_delete' AND (a.detail ? 'username' OR a.detail ? 'username_normalized');

ALTER TABLE registration_invites ADD COLUMN IF NOT EXISTS login_conflicts integer
    NOT NULL DEFAULT 0 CHECK (login_conflicts >= 0);
