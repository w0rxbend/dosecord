-- V11: encryption at rest, the contract step (ROADMAP G2.4, G2.4b; ARCHITECTURE
-- section 15.2; supervisor decision, 2026-11-14). Runs only after the sealing
-- backfill of the start-up step has sealed every value V10 left in a
-- plaintext column: it refuses to run while one is left, so it never empties
-- a value that has no sealed copy. Then it empties each plaintext column and
-- adds a CHECK that keeps it empty. The columns themselves stay, so the
-- previous release can still read its own schema; a later migration drops
-- them.

-- ---------------------------------------------------------------------------
-- Medications, schedules, occurrences; tracking entries; import and export;
-- the stores of the edges (the areas of V10).
-- ---------------------------------------------------------------------------

SET LOCAL dosegram.sealing = 'on';

DO $$
DECLARE
  c record;
  unsealed bigint;
BEGIN
  FOR c IN SELECT * FROM (VALUES
      ('medications', 'name', 'name_enc'),
      ('medications', 'display_alias', 'display_alias_enc'),
      ('medications', 'instructions', 'instructions_enc'),
      ('schedule_revisions', 'dose_snapshot', 'dose_snapshot_enc'),
      ('dose_occurrences', 'dose_snapshot', 'dose_snapshot_enc'),
      ('dose_actions', 'note', 'note_enc'),
      ('meals', 'description', 'description_enc'),
      ('export_jobs', 'content', 'content_enc'),
      ('render_assets', 'bytes', 'bytes_enc'),
      ('idempotency_keys', 'response_json', 'response_enc')) AS v(tbl, plain, sealed)
  LOOP
    EXECUTE format('SELECT count(*) FROM %I WHERE %I IS NOT NULL AND %I IS NULL', c.tbl, c.plain, c.sealed)
      INTO unsealed;
    IF unsealed > 0 THEN
      RAISE EXCEPTION 'dosegram: %.% holds % value(s) that are not sealed yet; V11 runs only after the sealing backfill (dosegram migrate)',
        c.tbl, c.plain, unsealed;
    END IF;
    EXECUTE format('UPDATE %I SET %I = NULL WHERE %I IS NOT NULL', c.tbl, c.plain, c.plain);
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%I IS NULL)', c.tbl, c.tbl || '_' || c.plain || '_sealed', c.plain);
  END LOOP;
END
$$;
