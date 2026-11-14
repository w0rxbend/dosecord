-- V10: encryption at rest, the expand step (ROADMAP G2.4, G2.4b; ARCHITECTURE
-- section 15.2, SEC-01 to SEC-04, SEC-07; supervisor decision, 2026-11-14).
-- Adds the wrapped data keys and a sealed column next to every Class A
-- column that holds a value today. The plaintext columns stay until V11:
-- between the two, the start-up step of the application seals every existing
-- value in batches (SealingBackfill), and V11 empties the plaintext columns
-- and forbids a value in them from then on.

-- ---------------------------------------------------------------------------
-- Keys (ARCHITECTURE section 15.2).
--
-- account_keys: an account's 256-bit data keys, each wrapped by the
-- key-encryption key named in kek_id, which is never in the database. The
-- highest version seals new values; every version keeps opening its own
-- values (SEC-07). Erasure destroys these rows first (SEC-06); deleting the
-- account deletes them with it.
--
-- service_keys: the same for rows that belong to no account (the bytes of a
-- drawn image).
-- ---------------------------------------------------------------------------

CREATE TABLE account_keys (account_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  version int NOT NULL CHECK (version > 0), kek_id text NOT NULL, wrapped_key bytea NOT NULL,
  created_at timestamptz NOT NULL, PRIMARY KEY (account_id, version));

CREATE TABLE service_keys (purpose text NOT NULL CHECK (purpose ~ '^[a-z_]{1,32}$'),
  version int NOT NULL CHECK (version > 0), kek_id text NOT NULL, wrapped_key bytea NOT NULL,
  created_at timestamptz NOT NULL, PRIMARY KEY (purpose, version));

-- ---------------------------------------------------------------------------
-- Medications, schedules, occurrences: the sealed columns (AES-256-GCM, the
-- associated data binds account, table, column, row and key version).
-- name_hmac is the keyed lookup hash of the normalised name; it replaces
-- name_norm for find-or-create and keeps one live medication per name.
-- ---------------------------------------------------------------------------

ALTER TABLE medications ALTER COLUMN name DROP NOT NULL,
  ADD COLUMN name_enc bytea, ADD COLUMN name_hmac bytea, ADD COLUMN display_alias_enc bytea,
  ADD COLUMN instructions_enc bytea,
  ADD CONSTRAINT medications_name_present CHECK (name IS NOT NULL OR (name_enc IS NOT NULL AND name_hmac IS NOT NULL));
CREATE UNIQUE INDEX uq_medication_name_hmac ON medications (user_id, name_hmac)
  WHERE status <> 'archived' AND name_hmac IS NOT NULL;

ALTER TABLE schedule_revisions ALTER COLUMN dose_snapshot DROP NOT NULL, ADD COLUMN dose_snapshot_enc bytea,
  ADD CONSTRAINT schedule_revisions_dose_snapshot_present CHECK (dose_snapshot IS NOT NULL OR dose_snapshot_enc IS NOT NULL);

ALTER TABLE dose_occurrences ALTER COLUMN dose_snapshot DROP NOT NULL, ADD COLUMN dose_snapshot_enc bytea,
  ADD CONSTRAINT dose_occurrences_dose_snapshot_present CHECK (dose_snapshot IS NOT NULL OR dose_snapshot_enc IS NOT NULL);

ALTER TABLE dose_actions ADD COLUMN note_enc bytea;

-- ---------------------------------------------------------------------------
-- Tracking entries: the words of a meal. description_hmac groups the recent
-- words of a meal type without reading them.
-- ---------------------------------------------------------------------------

ALTER TABLE meals ADD COLUMN description_enc bytea, ADD COLUMN description_hmac bytea;

-- ---------------------------------------------------------------------------
-- Import and export, and the stores of the edges: the export file, the bytes
-- of a drawn image (service key) and the stored answer of a creating request.
-- ---------------------------------------------------------------------------

ALTER TABLE export_jobs ADD COLUMN content_enc bytea,
  ADD CONSTRAINT export_jobs_content_enc CHECK (content_enc IS NULL OR (status IN ('ready','downloaded') AND byte_size IS NOT NULL));

ALTER TABLE render_assets ALTER COLUMN bytes DROP NOT NULL, ADD COLUMN bytes_enc bytea,
  ADD CONSTRAINT render_assets_bytes_present CHECK (bytes IS NOT NULL OR bytes_enc IS NOT NULL);

-- An answered key holds its response in one of the two forms.
ALTER TABLE idempotency_keys ADD COLUMN response_enc bytea, DROP CONSTRAINT idempotency_keys_outcome,
  ADD CONSTRAINT idempotency_keys_outcome CHECK ((request_hash IS NULL) = (response_json IS NULL AND response_enc IS NULL));

-- ---------------------------------------------------------------------------
-- The append-only and immutable guards accept one more UPDATE: sealing, with
-- the session setting dosegram.sealing = 'on'. Sealing may only set a sealed
-- or lookup column (name ending in _enc or _hmac) that was NULL, and empty a
-- plaintext column whose sealed column holds a value. No fact of the row can
-- change through it.
-- ---------------------------------------------------------------------------

CREATE FUNCTION dosegram_is_sealing(old_row jsonb, new_row jsonb) RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT current_setting('dosegram.sealing', true) IS NOT DISTINCT FROM 'on' AND NOT EXISTS (
    SELECT 1 FROM jsonb_each(new_row) AS n
    WHERE n.value IS DISTINCT FROM old_row -> n.key
      AND NOT ((n.key LIKE '%\_enc' OR n.key LIKE '%\_hmac') AND old_row -> n.key = 'null'::jsonb)
      AND NOT (n.value = 'null'::jsonb AND coalesce(jsonb_typeof(new_row -> (n.key || '_enc')), 'null') <> 'null'))
$$;

CREATE OR REPLACE FUNCTION dosegram_guard_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF dosegram_is_sealing(to_jsonb(OLD), to_jsonb(NEW)) THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'dosegram: % is append-only; UPDATE is not allowed', TG_TABLE_NAME;
  ELSIF TG_OP = 'DELETE'
        AND (current_setting('dosegram.erasure', true) IS DISTINCT FROM 'on'
             OR NOT (current_user = 'dosegram_erasure' OR EXISTS (
               SELECT 1 FROM pg_auth_members m
               JOIN pg_roles r ON r.oid = m.roleid
               WHERE r.rolname = 'dosegram_erasure'
                 AND m.member = (SELECT oid FROM pg_roles WHERE rolname = current_user)))) THEN
    RAISE EXCEPTION 'dosegram: % is append-only; DELETE requires the erasure role with SET LOCAL dosegram.erasure = ''on''', TG_TABLE_NAME;
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END
$$;

CREATE OR REPLACE FUNCTION dosegram_guard_entry_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF dosegram_is_sealing(to_jsonb(OLD), to_jsonb(NEW)) THEN
    RETURN NEW;
  ELSIF OLD.voided_at IS NOT NULL THEN
    RAISE EXCEPTION 'dosegram: % rows are immutable; this row is already voided', TG_TABLE_NAME;
  ELSIF (to_jsonb(NEW) - 'voided_at' - 'void_reason') IS DISTINCT FROM (to_jsonb(OLD) - 'voided_at' - 'void_reason') THEN
    RAISE EXCEPTION 'dosegram: % rows are immutable; only a void is allowed', TG_TABLE_NAME;
  END IF;
  RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------
-- Grants. The blanket grant of V1 covers only the tables that existed then.
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON account_keys, service_keys TO dosegram_app;
-- Crypto-shredding (SEC-06): erasure destroys an account's keys first.
GRANT SELECT, DELETE ON account_keys TO dosegram_erasure;
