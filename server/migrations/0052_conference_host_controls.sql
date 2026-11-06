-- Lock only NEW admissions. Existing authorized members retain their E2EE
-- identity and may reconnect; no member or key material is removed here.
ALTER TABLE conferences
    ADD COLUMN admission_locked BOOLEAN NOT NULL DEFAULT false;
