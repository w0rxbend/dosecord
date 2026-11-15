-- V19: the chat state, the event log and the last plaintext Class A columns
-- sealed (ROADMAP G2.4b, ARCHITECTURE section 15.2 and SEC-04; supervisor
-- decisions, 2026-11-15).
--
-- Each store gets a sealed column next to its plaintext column. The owner of
-- the seal is the row's account where the row has one, else the service key
-- 'transport' (the onboarding, and the stores keyed by chat before any account
-- exists). The plaintext columns stay, nullable, as in V13: the start-up step
-- seals every value left in them right after this migration and empties it in
-- the same UPDATE (SealingBackfill, stage 21), and the release writes the
-- sealed column only.
--
--   inbound_inbox.body              the update as received: message text
--   conversation_sessions.data      an open wizard's draft: a typed name
--   conversation_sessions.last_prompt
--   form_runs.answers               an open form's answers
--   callback_slots.payload          the state behind a button
--   domain_events.data, actor       the event log: names and words in events
--   reminders.title                 a reminder's own words
--   medications.dose_unit           a unit code, or the word a user typed
--
-- users.display_name and platform_identities.vendor_username hold a person's
-- name. The release never reads either; the first is never written (K8) and
-- the second is no longer written. Both are emptied here and kept empty.

ALTER TABLE inbound_inbox ADD COLUMN body_enc bytea,
  DROP CONSTRAINT inbound_inbox_check,
  ADD CONSTRAINT inbound_inbox_body_while_pending
    CHECK (status = 'quarantined' OR (status = 'pending') = (body IS NOT NULL OR body_enc IS NOT NULL));

ALTER TABLE conversation_sessions ALTER COLUMN data DROP NOT NULL, ALTER COLUMN data DROP DEFAULT,
  ADD COLUMN data_enc bytea, ADD COLUMN last_prompt_enc bytea,
  ADD CONSTRAINT conversation_sessions_data_present CHECK (data IS NOT NULL OR data_enc IS NOT NULL);

ALTER TABLE form_runs ALTER COLUMN answers DROP NOT NULL, ALTER COLUMN answers DROP DEFAULT,
  ADD COLUMN answers_enc bytea,
  ADD CONSTRAINT form_runs_answers_present CHECK (answers IS NOT NULL OR answers_enc IS NOT NULL);

ALTER TABLE callback_slots ALTER COLUMN payload DROP NOT NULL, ADD COLUMN payload_enc bytea,
  ADD CONSTRAINT callback_slots_payload_present CHECK (payload IS NOT NULL OR payload_enc IS NOT NULL);

ALTER TABLE domain_events ALTER COLUMN data DROP NOT NULL, ADD COLUMN data_enc bytea, ADD COLUMN actor_enc bytea,
  ADD CONSTRAINT domain_events_data_present CHECK (data IS NOT NULL OR data_enc IS NOT NULL);

ALTER TABLE reminders ALTER COLUMN title DROP NOT NULL, ADD COLUMN title_enc bytea,
  ADD CONSTRAINT reminders_title_present CHECK (title IS NOT NULL OR title_enc IS NOT NULL);

ALTER TABLE medications ADD COLUMN dose_unit_enc bytea;

-- The backfill looks for values still in a plaintext column at every start;
-- on the stores that grow, these keep that look to the rows it has to seal.
CREATE INDEX ix_domain_events_unsealed ON domain_events (id) WHERE data IS NOT NULL;
CREATE INDEX ix_domain_events_actor_unsealed ON domain_events (id) WHERE actor IS NOT NULL;
CREATE INDEX ix_callback_slots_unsealed ON callback_slots (id) WHERE payload IS NOT NULL;

UPDATE users SET display_name = NULL WHERE display_name IS NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_display_name_unused CHECK (display_name IS NULL);
UPDATE platform_identities SET vendor_username = NULL WHERE vendor_username IS NOT NULL;
ALTER TABLE platform_identities ADD CONSTRAINT platform_identities_vendor_username_unused
  CHECK (vendor_username IS NULL);

-- Performance (docs/PERFORMANCE.md; supervisor decision, 2026-11-15). Every
-- reminder, prompt and digest looks up the account's primary delivery
-- channels, and delivery_channels had no index on its account: each lookup
-- read the whole table under its row-level security policy. The reminder loop
-- asks for the next open dose of a schedule after the current one; the index
-- on open doses by schedule and time answers that without reading the
-- schedule's history.
CREATE INDEX ix_delivery_channels_account ON delivery_channels (account_id, priority);
CREATE INDEX ix_occ_schedule_open ON dose_occurrences (schedule_id, scheduled_for)
  WHERE status IN ('pending', 'due', 'snoozed');
