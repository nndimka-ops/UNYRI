-- Refresh proofs survive hard deletion only until their original expiry. They
-- contain no user/device identity and authorize no action other than a terminal answer.
CREATE TABLE deleted_session_proofs (
    session_id uuid PRIMARY KEY,
    refresh_token_hash text NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX deleted_session_proofs_expiry ON deleted_session_proofs(expires_at);

-- A last content-free wake must survive device/token/account cascades. Tokens
-- are retained solely for this bounded delivery and erased on success/failure/expiry.
CREATE TABLE session_check_wakes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider text NOT NULL CHECK(provider IN ('fcm','apns')),
    token text NOT NULL,
    environment text,
    topic text,
    registration_id uuid NOT NULL,
    revision bigint NOT NULL,
    token_registered_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL DEFAULT now()+interval '1 hour',
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX session_check_wakes_due ON session_check_wakes(next_attempt_at);

-- Allocation is permanent; its former owner is not. Only allow detachment once
-- the account has disappeared, never reassignment or number reuse.
ALTER TABLE public_numbers DROP CONSTRAINT public_numbers_check;
ALTER TABLE public_numbers ADD CONSTRAINT public_numbers_check CHECK(NOT canonical OR number>=100000000);
CREATE OR REPLACE FUNCTION public_number_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'number allocations are permanent'; END IF;
    IF NEW.number<>OLD.number OR NEW.canonical<>OLD.canonical OR
       (NEW.user_id IS DISTINCT FROM OLD.user_id AND
        (OLD.user_id IS NULL OR NEW.user_id IS NOT NULL OR EXISTS(SELECT 1 FROM users WHERE id=OLD.user_id))) THEN
        RAISE EXCEPTION 'number allocations are immutable';
    END IF;
    RETURN NEW;
END $$;
-- Repair retained snapshots from accounts deleted before this release too.
UPDATE chat_reports r SET participants_snapshot=COALESCE((
    SELECT jsonb_agg(CASE WHEN NOT EXISTS(SELECT 1 FROM users u WHERE u.id::text=COALESCE(p->>'user_id',p->>'id'))
        THEN jsonb_build_object('id',p->>'id','user_id',COALESCE(p->>'user_id',p->>'id'),
            'unyri_number',COALESCE(p->>'unyri_number',(SELECT n.number::text FROM public_numbers n WHERE n.user_id::text=COALESCE(p->>'user_id',p->>'id') AND n.canonical)),
            'display_name','Удалённый аккаунт') ELSE p END)
    FROM jsonb_array_elements(r.participants_snapshot) p),'[]'::jsonb);
UPDATE admin_audit a SET detail=jsonb_build_object('unyri_number',
    (SELECT number::text FROM public_numbers n WHERE n.user_id=a.target AND canonical),
    'identity',COALESCE((SELECT number::text FROM public_numbers n WHERE n.user_id=a.target AND canonical),a.target::text))
    WHERE a.target IS NOT NULL AND NOT EXISTS(SELECT 1 FROM users u WHERE u.id=a.target)
    AND a.action IN ('account_delete','suspend','suspend_with_installations','unsuspend','unsuspend_with_installations',
        'profile:moderate','read_only:enable','read_only:disable','note:add','notice:send','diagnostics:export');
UPDATE admin_audit a SET detail=detail-'actor_name'-'actor_username'-'actor_display_name'
    WHERE actor IS NOT NULL AND NOT EXISTS(SELECT 1 FROM users u WHERE u.id=a.actor);
UPDATE installation_suspensions b SET source_user_id=gen_random_uuid(),
    reason='Deleted account',actor='00000000-0000-0000-0000-000000000000'
    WHERE NOT EXISTS(SELECT 1 FROM users u WHERE u.id=b.source_user_id);
UPDATE installation_suspensions b SET actor='00000000-0000-0000-0000-000000000000'
    WHERE NOT EXISTS(SELECT 1 FROM users u WHERE u.id=b.actor);
UPDATE public_numbers p SET user_id=NULL WHERE user_id IS NOT NULL
    AND NOT EXISTS(SELECT 1 FROM users u WHERE u.id=p.user_id);
