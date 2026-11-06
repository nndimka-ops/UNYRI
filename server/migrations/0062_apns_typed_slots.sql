-- Retain one legacy FCM registration while admitting independent iOS alert/VoIP slots.
ALTER TYPE push_provider ADD VALUE IF NOT EXISTS 'apns';
ALTER TYPE push_provider ADD VALUE IF NOT EXISTS 'apns_voip';

ALTER TABLE push_tokens
    DROP CONSTRAINT push_tokens_device_id_key,
    ADD CONSTRAINT push_tokens_device_provider_key UNIQUE (device_id, provider),
    ADD COLUMN environment TEXT,
    ADD COLUMN topic TEXT,
    ADD COLUMN registration_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD COLUMN token_registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN message_alert_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN message_sound_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN permission_allowed BOOLEAN NOT NULL DEFAULT true,
    ADD CONSTRAINT push_tokens_apns_context CHECK (
        provider::text = 'fcm' OR
        (revision > 0 AND ((invalidated_at IS NOT NULL AND token='' AND environment IS NULL AND topic IS NULL) OR
        (environment IS NOT NULL AND environment IN ('production', 'sandbox') AND topic IS NOT NULL AND length(topic)>0
         AND length(token)>0 AND length(token)<=4096 AND length(token)%2=0 AND token ~ '^[0-9a-f]+$')))
    );

-- Existing queued/delivered rows keep their FCM semantics and contain no token.
ALTER TABLE push_outbox ADD COLUMN provider push_provider NOT NULL DEFAULT 'fcm';
