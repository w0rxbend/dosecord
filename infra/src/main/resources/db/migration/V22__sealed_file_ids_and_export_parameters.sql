-- V22: the last plaintext of ROADMAP G2.4b sealed (ARCHITECTURE section 15.2
-- and SEC-04; supervisor decision, 2026-11-15).
--
--   telegram_file_cache.file_id   the Bot API's handle of an uploaded image or
--                                 export file: whoever holds it and the bot
--                                 token can fetch the user's card or archive.
--                                 Sealed with the service key 'render' (the
--                                 table has no account column), bound to the
--                                 asset key.
--   export_jobs.params            the parameters of an export, which the
--                                 step-up of a Mini App request confirms: the
--                                 period of a record summary. Sealed with the
--                                 row's account key.
--
-- As in V13 and V19, each store gets a sealed column next to its plaintext
-- column, which stays, nullable: the start-up step seals every value left in
-- it right after this migration and empties it in the same UPDATE
-- (SealingBackfill, stage 22), and the release writes the sealed column only.

ALTER TABLE telegram_file_cache ALTER COLUMN file_id DROP NOT NULL, ADD COLUMN file_id_enc bytea,
  ADD CONSTRAINT telegram_file_cache_file_id_present CHECK (file_id IS NOT NULL OR file_id_enc IS NOT NULL);

ALTER TABLE export_jobs ALTER COLUMN params DROP NOT NULL, ALTER COLUMN params DROP DEFAULT,
  ADD COLUMN params_enc bytea,
  ADD CONSTRAINT export_jobs_params_present CHECK (params IS NOT NULL OR params_enc IS NOT NULL);
