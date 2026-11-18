-- Schema/provisioning only. Historical accounts are provisioned by the bounded,
-- resumable identity_backfill worker and on demand, never in this DDL transaction.
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS public_number_ranges (
    first_number bigint PRIMARY KEY,
    last_number bigint NOT NULL,
    CHECK (first_number >= 1 AND last_number <= 999999999 AND first_number <= last_number)
);
INSERT INTO public_number_ranges VALUES (1,99999999) ON CONFLICT DO NOTHING;

-- Intentionally no user FK: permanent tombstones survive account deletion.
CREATE TABLE IF NOT EXISTS public_numbers (
    number bigint PRIMARY KEY CHECK (number BETWEEN 1 AND 999999999),
    user_id uuid,
    canonical boolean NOT NULL DEFAULT false,
    CHECK (NOT canonical OR (user_id IS NOT NULL AND number >= 100000000))
);
CREATE UNIQUE INDEX IF NOT EXISTS public_numbers_canonical_user ON public_numbers(user_id) WHERE canonical;
CREATE OR REPLACE FUNCTION public_number_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'number allocations are permanent'; END IF;
    IF NEW.number<>OLD.number OR NEW.canonical<>OLD.canonical OR
       (OLD.user_id IS NOT NULL AND NEW.user_id IS DISTINCT FROM OLD.user_id) THEN
        RAISE EXCEPTION 'number allocations are immutable';
    END IF;
    RETURN NEW;
END $$;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='public_number_immutable') THEN
        CREATE TRIGGER public_number_immutable BEFORE UPDATE OR DELETE ON public_numbers
        FOR EACH ROW EXECUTE FUNCTION public_number_immutable();
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS user_identities (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    unyri_number bigint NOT NULL UNIQUE REFERENCES public_numbers(number),
    public_username text UNIQUE,
    discoverability text NOT NULL DEFAULT 'everyone' CHECK (discoverability IN ('everyone','contacts','nobody')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    username_changed_at timestamptz,
    CHECK (public_username IS NULL OR
        (public_username COLLATE "C" ~ '^[a-z][a-z0-9_]{4,31}$' AND position('__' in public_username)=0))
);
CREATE OR REPLACE FUNCTION identity_number_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.user_id<>OLD.user_id OR NEW.unyri_number<>OLD.unyri_number THEN
        RAISE EXCEPTION 'canonical identity is immutable';
    END IF;
    RETURN NEW;
END $$;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='identity_number_immutable') THEN
        CREATE TRIGGER identity_number_immutable BEFORE UPDATE ON user_identities
        FOR EACH ROW EXECUTE FUNCTION identity_number_immutable();
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS public_usernames (
    name text COLLATE "C" PRIMARY KEY CHECK (name ~ '^[a-z][a-z0-9_]{0,31}$' AND position('__' in name)=0),
    user_id uuid UNIQUE REFERENCES users(id) ON DELETE SET NULL,
    reserved boolean NOT NULL DEFAULT false,
    frozen_until timestamptz,
    CHECK (user_id IS NULL OR (NOT reserved AND frozen_until IS NULL AND length(name)>=5))
);
INSERT INTO public_usernames(name,reserved)
SELECT name,true FROM unnest(ARRAY['unyri','support','admin','administrator','official','help',
    'security','system','moderator','root','staff','team','service','contact','abuse','privacy','deleted','null']) name
ON CONFLICT DO NOTHING;

-- Fallback for inserts from old server processes or other account provisioning.
-- The first 32 UUID bits are cryptographically random (outside version/variant).
-- 3,600,000,000 is the largest multiple of 900,000,000 below 2^32.
CREATE OR REPLACE FUNCTION allocate_public_number(account uuid) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE sample bigint; candidate bigint; allocated bigint;
BEGIN
    SELECT number INTO allocated FROM public_numbers WHERE user_id=account AND canonical;
    IF FOUND THEN RETURN allocated; END IF;
    FOR attempt IN 1..128 LOOP
        sample := ('x'||substr(replace(gen_random_uuid()::text,'-',''),1,8))::bit(32)::bigint;
        IF sample>=3600000000 THEN CONTINUE; END IF;
        candidate := 100000000 + sample % 900000000;
        IF EXISTS(SELECT 1 FROM public_number_ranges WHERE candidate BETWEEN first_number AND last_number) THEN CONTINUE; END IF;
        INSERT INTO public_numbers(number,user_id,canonical) VALUES(candidate,account,true)
        ON CONFLICT DO NOTHING RETURNING number INTO allocated;
        IF FOUND THEN RETURN allocated; END IF;
    END LOOP;
    RAISE EXCEPTION 'public number allocation exhausted' USING ERRCODE='P0001';
END $$;
CREATE OR REPLACE FUNCTION create_public_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO user_identities(user_id,unyri_number) VALUES(NEW.id,allocate_public_number(NEW.id))
    ON CONFLICT(user_id) DO NOTHING;
    RETURN NEW;
END $$;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='users_create_public_identity') THEN
        CREATE TRIGGER users_create_public_identity AFTER INSERT ON users
        FOR EACH ROW EXECUTE FUNCTION create_public_identity();
    END IF;
END $$;

CREATE OR REPLACE FUNCTION release_deleted_public_username() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE public_usernames SET user_id=NULL,frozen_until=now()+interval '30 days' WHERE user_id=OLD.id;
    UPDATE user_identities SET public_username=NULL,revision=revision+1 WHERE user_id=OLD.id AND public_username IS NOT NULL;
    RETURN OLD;
END $$;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='users_delete_public_username') THEN
        CREATE TRIGGER users_delete_public_username BEFORE DELETE ON users
        FOR EACH ROW EXECUTE FUNCTION release_deleted_public_username();
        CREATE TRIGGER users_soft_delete_public_username AFTER UPDATE OF deleted_at ON users
        FOR EACH ROW WHEN (OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL)
        EXECUTE FUNCTION release_deleted_public_username();
    END IF;
END $$;
