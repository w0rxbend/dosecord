-- V4: the meal journal (ROADMAP G8.1, G8.3; PRODUCT_SPEC section 8.12; ADR-026).
--
-- A versioned migration after the baseline freeze (ADR-002 addendum of
-- 2026-11-13, ARCHITECTURE section 10.17). It only adds.

-- ---------------------------------------------------------------------------
-- Meals (docs/ARCHITECTURE.md sections 10.1 and 10.7). A meal entry has the
-- entry shape of the tracking area: immutable, voided once, a change is a new
-- row that names the row it supersedes, and local_date and tz are fixed when
-- the entry is made (R-NUT-2). The journal holds a meal type, a time and the
-- user's optional words; no energy or nutrient column exists in this wave
-- (ROADMAP G8 non-goals, X-10), and no photo column: photos arrive with G12.6.
--
-- description holds the user's words as typed, at most 300 characters
-- (R-NUT-1). No query of a figure reads it. It is not encrypted yet: no table
-- is before the account data keys (ROADMAP G2.4, G2.4b), which move it into an
-- encrypted column by a later migration, as they do the medication names.
--
-- meal_plans holds the main meals the user plans (VISUALS section 7.5); an
-- account without a row has the default plan of breakfast, lunch and dinner.
-- An empty plan is allowed: such a day is shown as a count, never a rate.
-- ---------------------------------------------------------------------------

CREATE TABLE meals (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  occurred_at timestamptz NOT NULL, local_date date NOT NULL, tz text NOT NULL,
  source text NOT NULL CHECK (source IN ('bot','miniapp','import','integration','system')),
  client_key text, import_job_id uuid, external_ref_hmac bytea, supersedes_id uuid REFERENCES meals,
  meal_type text NOT NULL CHECK (meal_type IN ('breakfast','lunch','dinner','snack')),
  description text CHECK (description IS NULL OR char_length(description) BETWEEN 1 AND 300),
  created_at timestamptz NOT NULL, voided_at timestamptz,
  void_reason text CHECK (void_reason IS NULL OR void_reason IN ('undone','deleted','superseded')),
  CONSTRAINT meals_void CHECK ((voided_at IS NULL) = (void_reason IS NULL)));
CREATE UNIQUE INDEX uq_meals_account_client_key ON meals (account_id, client_key);   -- one tap records once
CREATE INDEX ix_meals_account_local_date ON meals (account_id, local_date);
CREATE INDEX ix_meals_account_occurred_at_id ON meals (account_id, occurred_at, id);

CREATE TABLE meal_plans (account_id uuid PRIMARY KEY REFERENCES users,
  planned text[] NOT NULL CHECK (planned <@ ARRAY['breakfast','lunch','dinner']::text[]),
  updated_at timestamptz NOT NULL);

-- The entry rules of the baseline: the only UPDATE is the first void, DELETE
-- only by the erasure role, and every write queues the day's rollup.
CREATE TRIGGER meals_immutable BEFORE UPDATE ON meals
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_entry_immutable();
CREATE TRIGGER meals_no_delete BEFORE DELETE ON meals
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_append_only();
CREATE TRIGGER meals_rollup_dirty AFTER INSERT OR UPDATE OR DELETE ON meals
  FOR EACH ROW EXECUTE FUNCTION dosegram_entry_rollup_dirty('nutrition');

-- ---------------------------------------------------------------------------
-- Grants. The blanket grant of V1 covers only the tables that existed then.
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON meals, meal_plans TO dosegram_app;
-- Meals and the plan: deleted by erasure only.
GRANT SELECT, DELETE ON meals, meal_plans TO dosegram_erasure;
