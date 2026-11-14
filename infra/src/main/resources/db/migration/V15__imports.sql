-- V15: imports (ROADMAP G10.5, G10.7; PRODUCT_SPEC section 8.17, R-IMX-7 to
-- R-IMX-13; ARCHITECTURE section 10.13). A versioned migration after the
-- baseline freeze (ADR-002 addendum of 2026-11-13, ARCHITECTURE section
-- 10.17). It adds two tables and changes three rules of the entry tables.
--
-- import_jobs: one row per import the user starts, from the Dosegram CSV
-- template or an Apple Health export. Class D metadata only: the source, the
-- state and counts (per domain new, duplicate and conflicting records, the
-- invalid and ignored rows, the error lines as line numbers and codes, never a
-- value). An open job collects batches into import_staging; committing writes
-- the staged records into the entry tables and empties the staging; undoing
-- voids every entry the job wrote. The row stays as the history of the
-- account's imports.
--
-- import_staging: the normalised records of an open job, each sealed with the
-- account's data key (payload_enc), with the keyed hash of its identity
-- (external_ref_hmac). Never the file: the Mini App reads the file on the
-- device and sends normalised records or the rows of a CSV file. Deleted at
-- commit, at cancel and, for an abandoned job, after 24 hours.
--
-- Entry tables: an imported row carries import_job_id and external_ref_hmac
-- (baseline columns). The unique indexes below make a repeated import a no-op
-- (R-IMX-9); they hold only while the row is live, so a record whose import
-- was undone can be imported again. sleep_episodes accepts the origin
-- 'import_file' that ARCHITECTURE section 10.9 lists.
-- ---------------------------------------------------------------------------

CREATE TABLE import_jobs (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  source text NOT NULL CHECK (source IN ('dosegram_csv','apple_health')),
  status text NOT NULL CHECK (status IN ('open','committed','cancelled','undone')),
  counts jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(counts) = 'object'),
  created_at timestamptz NOT NULL, committed_at timestamptz, closed_at timestamptz,
  CONSTRAINT import_jobs_committed CHECK ((status IN ('committed','undone')) = (committed_at IS NOT NULL)),
  CONSTRAINT import_jobs_closed CHECK ((status IN ('cancelled','undone')) = (closed_at IS NOT NULL)));
CREATE INDEX ix_import_jobs_account_created ON import_jobs (account_id, created_at);
CREATE INDEX ix_import_jobs_open ON import_jobs (created_at) WHERE status = 'open';

CREATE TABLE import_staging (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  import_job_id uuid NOT NULL REFERENCES import_jobs,
  domain text NOT NULL CHECK (domain IN ('hydration','mood','sleep','nutrition')),
  payload_enc bytea NOT NULL, external_ref_hmac bytea NOT NULL,
  state text NOT NULL CHECK (state IN ('new','duplicate','conflict')),
  created_at timestamptz NOT NULL);
CREATE INDEX ix_import_staging_job_state ON import_staging (import_job_id, state);
CREATE UNIQUE INDEX uq_import_staging_job_ref ON import_staging (import_job_id, external_ref_hmac);

-- ---------------------------------------------------------------------------
-- Entry tables.
-- ---------------------------------------------------------------------------

CREATE UNIQUE INDEX uq_hydration_entries_import_ref ON hydration_entries (account_id, external_ref_hmac)
  WHERE external_ref_hmac IS NOT NULL AND voided_at IS NULL;
CREATE UNIQUE INDEX uq_mood_checkins_import_ref ON mood_checkins (account_id, external_ref_hmac)
  WHERE external_ref_hmac IS NOT NULL AND voided_at IS NULL;
CREATE UNIQUE INDEX uq_sleep_episodes_import_ref ON sleep_episodes (account_id, external_ref_hmac)
  WHERE external_ref_hmac IS NOT NULL AND voided_at IS NULL;
CREATE UNIQUE INDEX uq_meals_import_ref ON meals (account_id, external_ref_hmac)
  WHERE external_ref_hmac IS NOT NULL AND voided_at IS NULL;

-- Undo finds a job's entries by these.
CREATE INDEX ix_hydration_entries_import_job ON hydration_entries (import_job_id) WHERE import_job_id IS NOT NULL;
CREATE INDEX ix_mood_checkins_import_job ON mood_checkins (import_job_id) WHERE import_job_id IS NOT NULL;
CREATE INDEX ix_sleep_episodes_import_job ON sleep_episodes (import_job_id) WHERE import_job_id IS NOT NULL;
CREATE INDEX ix_meals_import_job ON meals (import_job_id) WHERE import_job_id IS NOT NULL;

ALTER TABLE sleep_episodes DROP CONSTRAINT sleep_episodes_origin_check,
  ADD CONSTRAINT sleep_episodes_origin_check
    CHECK (origin IN ('checkin','retrospective','passive_confirmed','self_report_corrected','import_file'));

-- ---------------------------------------------------------------------------
-- Grants. The blanket grant of V1 covers only the tables that existed then.
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON import_jobs, import_staging TO dosegram_app;
-- The staging is emptied at commit and cancel; a job row stays.
GRANT DELETE ON import_staging TO dosegram_app;
GRANT SELECT, DELETE ON import_jobs, import_staging TO dosegram_erasure;
