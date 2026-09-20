-- Dosecord baseline schema (docs/DESIGN.md section 8, ADR-002/003/004/011).
-- Rule: V1 is edited in place until the 0.1.0 tag (the dev volume is dropped on
-- every change); no V2 exists before M2 exit. All instants are timestamptz, all
-- ids uuid. The application never calls SQL now() outside DEFAULT clauses;
-- every query takes `now` as a bind parameter from the injected Clock.

CREATE EXTENSION IF NOT EXISTS citext;

-- Roles. The application connects as dosecord_app; the erasure path of M4.4
-- connects as dosecord_erasure, the only role the append-only triggers accept
-- DELETEs from (and only while the session GUC dosecord.erasure = 'on' is set).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dosecord_app') THEN
    CREATE ROLE dosecord_app NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dosecord_erasure') THEN
    CREATE ROLE dosecord_erasure NOLOGIN;
  END IF;
END
$$;

CREATE TYPE occ_status AS ENUM ('pending','due','snoozed','taken','skipped','missed','unknown','cancelled');
CREATE TYPE dose_action AS ENUM ('reminder_sent','reminder_deferred','taken','skipped','snoozed','auto_marked_missed',
  'marked_unknown','manually_corrected','undone','note_added','cancelled','catch_up_collapsed','chain_child_created');

-- ---------------------------------------------------------------------------
-- Identity (section 6 of DESIGN.md; auth tables are written from M4.1 on)
-- ---------------------------------------------------------------------------

CREATE TABLE users (id uuid PRIMARY KEY, handle citext UNIQUE, display_name text, timezone text NOT NULL DEFAULT 'UTC',
  locale text, status text NOT NULL CHECK (status IN ('active','pending_credentials','disabled')),
  quiet_hours jsonb, discreet_default boolean NOT NULL DEFAULT false, delivery_policy text NOT NULL DEFAULT 'primary_then_fallback',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE platform_identities (id uuid PRIMARY KEY, user_id uuid REFERENCES users, vendor text NOT NULL, vendor_user_id text NOT NULL,
  vendor_username text, dm_channel_id text, linked_at timestamptz, last_seen_at timestamptz, unlinked_at timestamptz);
CREATE UNIQUE INDEX uq_platform_identity ON platform_identities (vendor, vendor_user_id) WHERE unlinked_at IS NULL;
CREATE UNIQUE INDEX uq_user_vendor ON platform_identities (user_id, vendor) WHERE unlinked_at IS NULL;

CREATE TABLE user_credentials (user_id uuid PRIMARY KEY REFERENCES users,
  password_hash text, argon2_params jsonb, password_set_at timestamptz,
  failed_login_count int NOT NULL DEFAULT 0, locked_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE auth_challenges (id uuid PRIMARY KEY, kind text NOT NULL, user_id uuid, vendor text, vendor_user_id text, token_hash bytea NOT NULL UNIQUE,
  code_hash bytea, expires_at timestamptz NOT NULL, consumed_at timestamptz, created_ip inet);

CREATE TABLE recovery_codes (id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users,
  code_hash bytea NOT NULL UNIQUE, used_at timestamptz, created_at timestamptz NOT NULL DEFAULT now());

-- Append-only; the trigger below rejects UPDATE and (without the erasure role) DELETE.
CREATE TABLE auth_audit_log (id uuid PRIMARY KEY, user_id uuid, platform_identity_id uuid,
  kind text NOT NULL, outcome text NOT NULL, detail jsonb NOT NULL DEFAULT '{}', created_ip inet,
  occurred_at timestamptz NOT NULL DEFAULT now());

-- ---------------------------------------------------------------------------
-- Medications, schedules, occurrences (ADR-004)
-- ---------------------------------------------------------------------------

CREATE TABLE medications (id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users, name text NOT NULL,
  name_norm text GENERATED ALWAYS AS (lower(btrim(name))) STORED, display_alias text, dose_amount numeric, dose_unit text,
  instructions text, discreet boolean NOT NULL DEFAULT false, status text NOT NULL CHECK (status IN ('active','paused','archived')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX uq_medication_name ON medications (user_id, name_norm) WHERE status <> 'archived';

CREATE TABLE medication_schedules (id uuid PRIMARY KEY, medication_id uuid NOT NULL REFERENCES medications, user_id uuid NOT NULL,
  kind text NOT NULL, status text NOT NULL CHECK (status IN ('active','paused','archived')), current_revision int NOT NULL DEFAULT 1,
  tz text NOT NULL, tz_follows_user boolean NOT NULL DEFAULT true, start_date date NOT NULL, end_date date,
  materialized_through timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ix_schedules_materialize ON medication_schedules (status, materialized_through);

CREATE TABLE schedule_revisions (id uuid PRIMARY KEY, schedule_id uuid NOT NULL REFERENCES medication_schedules, revision int NOT NULL,
  effective_from timestamptz NOT NULL, tz text NOT NULL, rule jsonb NOT NULL, reminder_policy jsonb NOT NULL, dose_snapshot jsonb NOT NULL,
  created_by text NOT NULL, reason text, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (schedule_id, revision));

CREATE TABLE dose_occurrences (id uuid PRIMARY KEY, account_id uuid NOT NULL, medication_id uuid NOT NULL, schedule_id uuid, revision int,
  origin text NOT NULL CHECK (origin IN ('scheduled','chain','manual')), local_date date NOT NULL, local_time time, slot_key text NOT NULL,
  tz text NOT NULL, dst_kind text NOT NULL DEFAULT 'none' CHECK (dst_kind IN ('none','gap','fold')),
  scheduled_for timestamptz NOT NULL, due_window_start timestamptz NOT NULL, due_window_end timestamptz NOT NULL, miss_deadline timestamptz NOT NULL,
  status occ_status NOT NULL, cancel_reason text CHECK (cancel_reason IS NULL OR cancel_reason IN ('superseded','paused','archived','anchor_undone')),
  unknown_reason text, missed_reason text,
  epoch int NOT NULL DEFAULT 0, reminder_seq int NOT NULL DEFAULT 0, snooze_count int NOT NULL DEFAULT 0, snoozed_until timestamptz,
  last_reminded_at timestamptz, taken_at timestamptz, effective_at timestamptz, skipped_at timestamptz, missed_at timestamptz, next_action_at timestamptz,
  error_count int NOT NULL DEFAULT 0, dose_snapshot jsonb NOT NULL, anchor_occurrence_id uuid REFERENCES dose_occurrences, version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (schedule_id, revision, local_date, slot_key),
  CONSTRAINT occ_open_has_action CHECK ((status IN ('pending','due','snoozed')) = (next_action_at IS NOT NULL)),
  CONSTRAINT occ_taken_at CHECK ((status = 'taken') = (taken_at IS NOT NULL)),
  CONSTRAINT occ_skipped_at CHECK ((status = 'skipped') = (skipped_at IS NOT NULL)),
  CONSTRAINT occ_snoozed_until CHECK (status <> 'snoozed' OR snoozed_until IS NOT NULL),
  CONSTRAINT occ_unknown_reason CHECK (status <> 'unknown' OR unknown_reason IS NOT NULL),
  CONSTRAINT occ_window_order CHECK (due_window_start <= miss_deadline));
CREATE UNIQUE INDEX uq_occ_live_slot ON dose_occurrences (schedule_id, local_date, slot_key) WHERE status <> 'cancelled';   -- one live dose per slot across revisions
CREATE INDEX ix_occ_next_action ON dose_occurrences (next_action_at) WHERE status IN ('pending','due','snoozed');           -- the loop's only hot index
CREATE INDEX ix_occ_account_day ON dose_occurrences (account_id, local_date, status);
CREATE INDEX ix_occ_account_med ON dose_occurrences (account_id, medication_id, local_date);

-- Append-only: the app role has INSERT+SELECT only; the trigger below rejects
-- UPDATE/DELETE. Projection = fold(actions); a nightly job checks it.
CREATE TABLE dose_actions (id uuid PRIMARY KEY, occurrence_id uuid NOT NULL REFERENCES dose_occurrences, account_id uuid NOT NULL,
  seq int NOT NULL, action dose_action NOT NULL, actor_type text NOT NULL CHECK (actor_type IN ('user','system')),
  vendor text, platform_identity_id uuid, platform_message_id text, interaction_id text, idempotency_key text UNIQUE,
  occurred_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(), prior_status occ_status NOT NULL, new_status occ_status NOT NULL,
  effective_at timestamptz, reason_code text, note text, undoes_seq int, catch_up boolean NOT NULL DEFAULT false,
  correlation_id text NOT NULL, metadata jsonb NOT NULL DEFAULT '{}', UNIQUE (occurrence_id, seq));

-- ---------------------------------------------------------------------------
-- Async edges as tables (ADR-003)
-- ---------------------------------------------------------------------------

CREATE TABLE outbox_messages (id uuid PRIMARY KEY, send_key text UNIQUE NOT NULL, op text NOT NULL CHECK (op IN ('send','edit','finalize','delete','react')),
  kind text NOT NULL, vendor text NOT NULL, account_id uuid, occurrence_id uuid, channel_id uuid, epoch int, items jsonb,
  target jsonb, importance text NOT NULL, payload jsonb NOT NULL,
  status text NOT NULL CHECK (status IN ('queued','sending','sent','failed_retry','failed_permanent','cancelled','dead')),
  attempts int NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL, lease_until timestamptz, attempted_at timestamptz, ops_done int NOT NULL DEFAULT 0,
  possible_duplicate boolean NOT NULL DEFAULT false, platform_message_id text, last_error text, traceparent text,
  created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz);
CREATE INDEX ix_outbox_due ON outbox_messages (vendor, next_attempt_at) WHERE status IN ('queued','failed_retry');
CREATE INDEX ix_outbox_occ ON outbox_messages (occurrence_id, status);

CREATE TABLE rendered_messages (id uuid PRIMARY KEY, account_id uuid, vendor text NOT NULL, chat_id text NOT NULL, message_id text NOT NULL,
  revision int NOT NULL DEFAULT 0, kind text NOT NULL, subject_type text, subject_id uuid, epoch int, choice_map jsonb,
  superseded_by uuid, sent_at timestamptz NOT NULL, last_edited_at timestamptz, controls_removed_at timestamptz,
  UNIQUE (vendor, chat_id, message_id));

CREATE TABLE inbound_events (vendor text NOT NULL, vendor_event_id text NOT NULL, event_id uuid NOT NULL, account_id uuid,
  received_at timestamptz NOT NULL, reply jsonb, processed_at timestamptz, PRIMARY KEY (vendor, vendor_event_id));

CREATE TABLE domain_events (seq bigserial PRIMARY KEY, id uuid NOT NULL, type text NOT NULL, source text NOT NULL, subject text,
  account_id uuid, correlation_id text, causation_id text, traceparent text, actor jsonb, occurred_at timestamptz NOT NULL, data jsonb NOT NULL);

CREATE TABLE worker_heartbeat (instance text PRIMARY KEY, role text NOT NULL, last_tick_at timestamptz NOT NULL);   -- one row per process instance

-- ---------------------------------------------------------------------------
-- Conversation state (M0.12b; versioned row, loaded FOR UPDATE)
-- ---------------------------------------------------------------------------

CREATE TABLE callback_slots (id uuid PRIMARY KEY, account_id uuid, chat jsonb NOT NULL, payload jsonb NOT NULL, session_id uuid, step_seq int, expires_at timestamptz);

CREATE TABLE conversation_sessions (id uuid PRIMARY KEY, principal_key text NOT NULL, vendor text NOT NULL, chat_id text NOT NULL,
  flow text NOT NULL, step text NOT NULL, step_seq int NOT NULL DEFAULT 0, data jsonb NOT NULL DEFAULT '{}', version int NOT NULL DEFAULT 1,
  last_prompt jsonb, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (principal_key, vendor, chat_id));

CREATE TABLE form_runs (session_id uuid PRIMARY KEY, form_id text NOT NULL, answers jsonb NOT NULL DEFAULT '{}', field_index int NOT NULL DEFAULT 0);

CREATE TABLE delivery_channels (id uuid PRIMARY KEY, account_id uuid NOT NULL, platform_identity_id uuid UNIQUE NOT NULL,
  role text NOT NULL, priority int NOT NULL, state text NOT NULL, last_ack_at timestamptz, last_error text, updated_at timestamptz NOT NULL DEFAULT now());

-- ---------------------------------------------------------------------------
-- Satellites: mood, habits, generic reminders
-- ---------------------------------------------------------------------------

CREATE TABLE mood_checkins (id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users,
  mood_level int NOT NULL CHECK (mood_level BETWEEN 1 AND 10), note text, tags text[] NOT NULL DEFAULT '{}',
  platform_identity_id uuid, platform_message_id text,
  recorded_at timestamptz NOT NULL DEFAULT now(), created_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE habits (id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users, name text NOT NULL,
  habit_type text NOT NULL CHECK (habit_type IN ('boolean','count','duration','measurement','avoid')),
  cadence jsonb NOT NULL DEFAULT '{}', streak_policy text NOT NULL DEFAULT 'flexible' CHECK (streak_policy IN ('flexible','strict','none')),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','archived')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX uq_habit_name ON habits (user_id, lower(name)) WHERE status <> 'archived';

CREATE TABLE habit_checkins (id uuid PRIMARY KEY, habit_id uuid NOT NULL REFERENCES habits, user_id uuid NOT NULL REFERENCES users,
  local_date date NOT NULL, status text NOT NULL CHECK (status IN ('done','partial','planned_skip','missed')),
  value numeric, note text, platform_identity_id uuid, platform_message_id text,
  recorded_at timestamptz NOT NULL DEFAULT now(), created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ix_habit_checkins_habit_day ON habit_checkins (habit_id, local_date);

CREATE TABLE reminders (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users, title text NOT NULL,
  repeat text NOT NULL DEFAULT 'none' CHECK (repeat IN ('none','daily','weekly')), next_due_at timestamptz NOT NULL,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','done','snoozed','expired','cancelled')),
  snoozed_until timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());

-- View stub: dose rows only until M3.5 adds the `reminders` kind and the loop
-- starts claiming over this view.
CREATE VIEW reminder_occurrences AS
SELECT 'dose'::text AS kind, id, account_id, medication_id, schedule_id, revision,
  local_date, slot_key, scheduled_for, due_window_start, due_window_end, miss_deadline,
  status, epoch, reminder_seq, snoozed_until, next_action_at, error_count, version
FROM dose_occurrences;

-- ---------------------------------------------------------------------------
-- Append-only guards. One exception: DELETE while the session GUC
-- dosecord.erasure = 'on' is set AND the current role is an explicit member of
-- dosecord_erasure. A custom GUC is settable by any role (GRANT SET ON
-- PARAMETER does not restrict placeholder parameters), and pg_has_role treats
-- superusers as members of every role, so membership is checked in
-- pg_auth_members directly: the app role cannot erase even with the GUC set
-- (the M4.4 erasure path connects under the erasure role).
-- ---------------------------------------------------------------------------

CREATE FUNCTION dosecord_guard_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION 'dosecord: % is append-only; UPDATE is not allowed', TG_TABLE_NAME;
  ELSIF TG_OP = 'DELETE'
        AND (current_setting('dosecord.erasure', true) IS DISTINCT FROM 'on'
             OR NOT (current_user = 'dosecord_erasure' OR EXISTS (
               SELECT 1 FROM pg_auth_members m
               JOIN pg_roles r ON r.oid = m.roleid
               WHERE r.rolname = 'dosecord_erasure'
                 AND m.member = (SELECT oid FROM pg_roles WHERE rolname = current_user)))) THEN
    RAISE EXCEPTION 'dosecord: % is append-only; DELETE requires the erasure role with SET LOCAL dosecord.erasure = ''on''', TG_TABLE_NAME;
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END
$$;

CREATE TRIGGER dose_actions_append_only BEFORE UPDATE OR DELETE ON dose_actions
  FOR EACH ROW EXECUTE FUNCTION dosecord_guard_append_only();
CREATE TRIGGER auth_audit_log_append_only BEFORE UPDATE OR DELETE ON auth_audit_log
  FOR EACH ROW EXECUTE FUNCTION dosecord_guard_append_only();

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO dosecord_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO dosecord_app;
REVOKE UPDATE ON dose_actions, auth_audit_log FROM dosecord_app;
GRANT SELECT, DELETE ON dose_actions, auth_audit_log TO dosecord_erasure;
