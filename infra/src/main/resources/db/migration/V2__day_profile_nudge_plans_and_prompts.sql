-- V2: day profile, nudge plans and prompts (ADR-023, ROADMAP G5.1, G5.2, G5.4).
--
-- The first migration after the baseline freeze (ADR-002 addendum of
-- 2026-11-13, ARCHITECTURE section 10.17): V1__baseline.sql is frozen at the
-- schema the owner's live database was created with, and every schema change
-- from then on is a new versioned migration. These tables were first written
-- into the baseline in place; they move here unchanged.

-- ---------------------------------------------------------------------------
-- Day profile, nudge plans and prompts (docs/ARCHITECTURE.md section 8.4,
-- ADR-023). day_profiles holds the anchors of the user's day and the work
-- hours; the quiet hours stay on users.quiet_hours. prompt_schedules holds one
-- nudge plan per prompt kind and account: the preset it came from and the plan
-- as jsonb. A prompt_occurrences row is written when a slot is decided, in the
-- state the decision gives it, so every due slot has a record and a restart
-- never decides a slot twice. send_key names the outbox row of a sent prompt.
-- ---------------------------------------------------------------------------

CREATE TABLE day_profiles (account_id uuid PRIMARY KEY REFERENCES users,
  wake time NOT NULL, breakfast time NOT NULL, lunch time NOT NULL, dinner time NOT NULL, bedtime time NOT NULL,
  work_days smallint NOT NULL CHECK (work_days BETWEEN 1 AND 127), work_start time NOT NULL, work_end time NOT NULL,
  prompts_paused_until timestamptz, updated_at timestamptz NOT NULL,
  CHECK (work_start < work_end));

CREATE TABLE prompt_schedules (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  kind text NOT NULL CHECK (kind IN ('water','mood','sleep_bedtime','sleep_morning','meal','report_weekly','report_monthly')),
  preset text NOT NULL, plan jsonb NOT NULL, own_cap boolean NOT NULL, enabled boolean NOT NULL,
  sound boolean NOT NULL DEFAULT false, paused_until timestamptz, active_from timestamptz NOT NULL,
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  UNIQUE (account_id, kind));
CREATE INDEX ix_prompt_schedules_enabled ON prompt_schedules (id) WHERE enabled;

CREATE TABLE prompt_occurrences (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  schedule_id uuid NOT NULL REFERENCES prompt_schedules, kind text NOT NULL,
  local_date date NOT NULL, slot_key text NOT NULL, due_at timestamptz NOT NULL,
  status text NOT NULL CHECK (status IN ('sent','answered','expired','suppressed','cancelled')),
  reason text CHECK (reason IN ('no_consent','unreachable','quiet_hours','paused','budget','goal_reached',
    'recent_entry','no_goal','on_track','ignored','backoff','crowded','catch_up','stale')),
  notice text CHECK (notice IN ('widened','paused_today')),
  send_key text UNIQUE, budgeted boolean NOT NULL, decided_at timestamptz NOT NULL, answered_at timestamptz,
  UNIQUE (schedule_id, local_date, slot_key),
  CHECK ((status = 'suppressed') = (reason IS NOT NULL)),
  CHECK ((status IN ('sent','answered','expired')) = (send_key IS NOT NULL)));
CREATE INDEX ix_prompt_occurrences_account_day ON prompt_occurrences (account_id, local_date);

-- ---------------------------------------------------------------------------
-- Grants. The blanket grant of V1 covers only the tables that existed then.
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON day_profiles, prompt_schedules, prompt_occurrences TO dosegram_app;
-- Day profile, nudge plans and prompts: deleted by erasure only.
GRANT SELECT, DELETE ON day_profiles, prompt_schedules, prompt_occurrences TO dosegram_erasure;
