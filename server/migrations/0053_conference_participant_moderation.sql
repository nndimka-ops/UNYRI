-- Room-scoped transport policy, never E2EE key material. Keep an account's
-- removal/audio restriction across voluntary leave, session refresh and rejoin.
CREATE TABLE conference_participant_policy (
    conference_id UUID NOT NULL REFERENCES conferences(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    admission_id UUID NOT NULL,
    audio_publish_blocked BOOLEAN NOT NULL DEFAULT false,
    removed BOOLEAN NOT NULL DEFAULT false,
    needs_sync BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(conference_id,user_id)
);
CREATE INDEX conference_participant_policy_pending_idx
    ON conference_participant_policy(conference_id) WHERE needs_sync;
