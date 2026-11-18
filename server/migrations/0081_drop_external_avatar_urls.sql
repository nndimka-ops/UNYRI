-- External avatar URLs made every phone that showed the profile fetch an
-- address chosen by the account owner (IP leak, unvetted image bytes).
-- Only uploaded objects (avatars/<key>) remain; profile_version moves so
-- clients drop the old reference.
UPDATE users
   SET avatar_url = NULL,
       profile_version = profile_version + 1
 WHERE avatar_url IS NOT NULL
   AND avatar_url NOT LIKE 'avatars/%';
