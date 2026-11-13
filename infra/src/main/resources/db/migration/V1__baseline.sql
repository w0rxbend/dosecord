-- Dosegram baseline schema (docs/DESIGN.md section 8, ADR-002/003/004/011).
-- Rule: V1 is edited in place until the 0.1.0 tag (the dev volume is dropped on
-- every change); no V2 exists before M2 exit. All instants are timestamptz, all
-- ids uuid. The application never calls SQL now() outside DEFAULT clauses;
-- every query takes `now` as a bind parameter from the injected Clock.

CREATE EXTENSION IF NOT EXISTS citext;

-- Roles. The application connects as dosegram_app; the erasure path of M4.4
-- connects as dosegram_erasure, the only role the append-only triggers accept
-- DELETEs from (and only while the session GUC dosegram.erasure = 'on' is set).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dosegram_app') THEN
    CREATE ROLE dosegram_app NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dosegram_erasure') THEN
    CREATE ROLE dosegram_erasure NOLOGIN;
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
  image_theme text NOT NULL DEFAULT 'auto' CHECK (image_theme IN ('auto','light','dark')),   -- the theme of the images the bot sends
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
  status occ_status NOT NULL, cancel_reason text CHECK (cancel_reason IS NULL OR cancel_reason IN ('superseded','paused','archived','anchor_undone','consent_withdrawn')),
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

CREATE TABLE outbox_messages (id uuid PRIMARY KEY, send_key text UNIQUE NOT NULL, op text NOT NULL CHECK (op IN ('send','edit','finalize','delete')),
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
-- Satellites: generic reminders
-- ---------------------------------------------------------------------------

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
-- dosegram.erasure = 'on' is set AND the current role is an explicit member of
-- dosegram_erasure. A custom GUC is settable by any role (GRANT SET ON
-- PARAMETER does not restrict placeholder parameters), and pg_has_role treats
-- superusers as members of every role, so membership is checked in
-- pg_auth_members directly: the app role cannot erase even with the GUC set
-- (the M4.4 erasure path connects under the erasure role).
-- ---------------------------------------------------------------------------

CREATE FUNCTION dosegram_guard_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
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

CREATE TRIGGER dose_actions_append_only BEFORE UPDATE OR DELETE ON dose_actions
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_append_only();
CREATE TRIGGER auth_audit_log_append_only BEFORE UPDATE OR DELETE ON auth_audit_log
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_append_only();

-- ---------------------------------------------------------------------------
-- Tracking entries: hydration and mood (docs/ARCHITECTURE.md sections 10.1,
-- 10.6 and 10.8). An entry is immutable: it can be voided once, and a
-- correction is a new row that names the row it supersedes. local_date and tz
-- are fixed when the entry is made, so a later change of the account zone does
-- not move it to another day. The bounds are input guards, not guidance.
-- ---------------------------------------------------------------------------

CREATE TABLE hydration_entries (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  occurred_at timestamptz NOT NULL, local_date date NOT NULL, tz text NOT NULL,
  source text NOT NULL CHECK (source IN ('bot','miniapp','import','integration','system')),
  client_key text, import_job_id uuid, external_ref_hmac bytea, supersedes_id uuid REFERENCES hydration_entries,
  amount_ml int NOT NULL CHECK (amount_ml BETWEEN 1 AND 5000),
  entered_value numeric(8,3) NOT NULL, entered_unit text NOT NULL CHECK (entered_unit IN ('ml','l','floz','glass')),
  beverage_code smallint NOT NULL DEFAULT 0 CHECK (beverage_code BETWEEN 0 AND 6),   -- 0 = water; the other codes arrive with drink types
  created_at timestamptz NOT NULL, voided_at timestamptz,
  void_reason text CHECK (void_reason IS NULL OR void_reason IN ('undone','deleted','superseded')),
  CONSTRAINT hydration_entries_void CHECK ((voided_at IS NULL) = (void_reason IS NULL)));
CREATE UNIQUE INDEX uq_hydration_entries_account_client_key ON hydration_entries (account_id, client_key);   -- one tap records once
CREATE INDEX ix_hydration_entries_account_local_date ON hydration_entries (account_id, local_date);
CREATE INDEX ix_hydration_entries_account_occurred_at_id ON hydration_entries (account_id, occurred_at, id);

-- target_ml NULL means "no goal from that day": the user may have none.
CREATE TABLE hydration_goals (account_id uuid NOT NULL REFERENCES users, effective_from date NOT NULL,
  target_ml int CHECK (target_ml BETWEEN 100 AND 10000), created_at timestamptz NOT NULL,
  PRIMARY KEY (account_id, effective_from));

CREATE TABLE hydration_presets (account_id uuid NOT NULL REFERENCES users, slot smallint NOT NULL CHECK (slot BETWEEN 1 AND 4),
  amount_ml int NOT NULL CHECK (amount_ml BETWEEN 1 AND 5000),
  beverage_code smallint NOT NULL DEFAULT 0 CHECK (beverage_code BETWEEN 0 AND 6),
  PRIMARY KEY (account_id, slot));

-- The account's own tag list. A label is content: it is stored encrypted, with
-- a keyed hash for equality inside one account (ARCHITECTURE section 10.1).
CREATE TABLE account_tags (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  domain text NOT NULL CHECK (domain IN ('mood','symptom','meal')), label_enc bytea NOT NULL, label_hmac bytea NOT NULL,
  archived_at timestamptz, created_at timestamptz NOT NULL);
CREATE UNIQUE INDEX uq_account_tags_account_domain_label_hmac ON account_tags (account_id, domain, label_hmac);

-- Valence and energy are steps 1 to 5. There is no questionnaire score and no
-- interpretation column. note_enc holds the encrypted note; no query reads it.
CREATE TABLE mood_checkins (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  occurred_at timestamptz NOT NULL, local_date date NOT NULL, tz text NOT NULL,
  source text NOT NULL CHECK (source IN ('bot','miniapp','import','integration','system')),
  client_key text, import_job_id uuid, external_ref_hmac bytea, supersedes_id uuid REFERENCES mood_checkins,
  valence smallint NOT NULL CHECK (valence BETWEEN 1 AND 5), energy smallint CHECK (energy BETWEEN 1 AND 5),
  tag_ids uuid[] NOT NULL DEFAULT '{}' CHECK (cardinality(tag_ids) <= 5), note_enc bytea, prompt_occurrence_id uuid,
  created_at timestamptz NOT NULL, voided_at timestamptz,
  void_reason text CHECK (void_reason IS NULL OR void_reason IN ('undone','deleted','superseded')),
  CONSTRAINT mood_checkins_void CHECK ((voided_at IS NULL) = (void_reason IS NULL)));
CREATE UNIQUE INDEX uq_mood_checkins_account_client_key ON mood_checkins (account_id, client_key);
CREATE INDEX ix_mood_checkins_account_local_date ON mood_checkins (account_id, local_date);
CREATE INDEX ix_mood_checkins_account_occurred_at_id ON mood_checkins (account_id, occurred_at, id);

-- The only UPDATE an entry accepts is its first void. DELETE follows the
-- append-only rule above: the erasure role with the erasure GUC.
CREATE FUNCTION dosegram_guard_entry_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.voided_at IS NOT NULL THEN
    RAISE EXCEPTION 'dosegram: % rows are immutable; this row is already voided', TG_TABLE_NAME;
  ELSIF (to_jsonb(NEW) - 'voided_at' - 'void_reason') IS DISTINCT FROM (to_jsonb(OLD) - 'voided_at' - 'void_reason') THEN
    RAISE EXCEPTION 'dosegram: % rows are immutable; only a void is allowed', TG_TABLE_NAME;
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER hydration_entries_immutable BEFORE UPDATE ON hydration_entries
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_entry_immutable();
CREATE TRIGGER hydration_entries_no_delete BEFORE DELETE ON hydration_entries
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_append_only();
CREATE TRIGGER mood_checkins_immutable BEFORE UPDATE ON mood_checkins
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_entry_immutable();
CREATE TRIGGER mood_checkins_no_delete BEFORE DELETE ON mood_checkins
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_append_only();

-- ---------------------------------------------------------------------------
-- Daily rollups (docs/ARCHITECTURE.md section 11.1, ADR-024). daily_rollups is
-- derived and rebuildable: one row per account, local day and domain, holding
-- counts and sums only. rollup_dirty is its work queue. A write to a source
-- table queues the affected days in the same transaction, by trigger, so no
-- write path can forget it and a crash cannot lose the marker.
--
-- The markers upsert with DO UPDATE on purpose: the row lock makes a
-- concurrent claim (FOR UPDATE SKIP LOCKED) skip a key whose source write has
-- not committed yet, instead of deleting the marker before the new row is
-- visible. marked_at is queue order only; it takes the database clock through
-- its DEFAULT.
-- ---------------------------------------------------------------------------

CREATE TABLE daily_rollups (account_id uuid NOT NULL REFERENCES users, local_date date NOT NULL,
  domain text NOT NULL CHECK (domain IN ('medication','hydration','nutrition','mood','sleep','symptoms')),
  metrics jsonb NOT NULL, rollup_version smallint NOT NULL, computed_at timestamptz NOT NULL,
  PRIMARY KEY (account_id, local_date, domain));

CREATE TABLE rollup_dirty (account_id uuid NOT NULL, local_date date NOT NULL,
  domain text NOT NULL CHECK (domain IN ('medication','hydration','nutrition','mood','sleep','symptoms')),
  marked_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (account_id, local_date, domain));
CREATE INDEX ix_rollup_dirty_marked_at ON rollup_dirty (marked_at);

-- An entry counts on the local_date stored on it; TG_ARGV[0] is the domain.
CREATE FUNCTION dosegram_entry_rollup_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  entry record;
BEGIN
  IF TG_OP = 'DELETE' THEN entry := OLD; ELSE entry := NEW; END IF;
  INSERT INTO rollup_dirty (account_id, local_date, domain)
  VALUES (entry.account_id, entry.local_date, TG_ARGV[0])
  ON CONFLICT (account_id, local_date, domain) DO UPDATE SET marked_at = EXCLUDED.marked_at;
  RETURN NULL;
END
$$;

CREATE TRIGGER hydration_entries_rollup_dirty AFTER INSERT OR UPDATE OR DELETE ON hydration_entries
  FOR EACH ROW EXECUTE FUNCTION dosegram_entry_rollup_dirty('hydration');
CREATE TRIGGER mood_checkins_rollup_dirty AFTER INSERT OR UPDATE OR DELETE ON mood_checkins
  FOR EACH ROW EXECUTE FUNCTION dosegram_entry_rollup_dirty('mood');

-- The hydration rollup carries the goal in force on its day, so a goal change
-- queues every recorded day from its first day on.
CREATE FUNCTION dosegram_goal_rollup_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO rollup_dirty (account_id, local_date, domain)
  SELECT DISTINCT e.account_id, e.local_date, 'hydration'
  FROM hydration_entries e
  WHERE e.account_id = NEW.account_id AND e.local_date >= NEW.effective_from
  ORDER BY 2
  ON CONFLICT (account_id, local_date, domain) DO UPDATE SET marked_at = EXCLUDED.marked_at;
  RETURN NULL;
END
$$;

CREATE TRIGGER hydration_goals_rollup_dirty AFTER INSERT OR UPDATE ON hydration_goals
  FOR EACH ROW EXECUTE FUNCTION dosegram_goal_rollup_dirty();

-- A dose counts on the local day of its effective, skip, miss or scheduled
-- instant in the account zone (ADR-012). The trigger does not resolve that
-- zone: it queues the UTC date of each instant and its two neighbours, which
-- covers every zone offset. Queuing a day too many only costs a recomputation.
CREATE FUNCTION dosegram_dose_rollup_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  instants timestamptz[];
BEGIN
  instants := ARRAY[NEW.scheduled_for, NEW.effective_at, NEW.skipped_at, NEW.missed_at];
  IF TG_OP = 'UPDATE' THEN
    instants := instants || ARRAY[OLD.scheduled_for, OLD.effective_at, OLD.skipped_at, OLD.missed_at];
  END IF;
  INSERT INTO rollup_dirty (account_id, local_date, domain)
  SELECT DISTINCT NEW.account_id, (ts AT TIME ZONE 'UTC')::date + shift, 'medication'
  FROM unnest(instants) AS ts, generate_series(-1, 1) AS shift
  WHERE ts IS NOT NULL
  ORDER BY 2
  ON CONFLICT (account_id, local_date, domain) DO UPDATE SET marked_at = EXCLUDED.marked_at;
  RETURN NULL;
END
$$;

CREATE TRIGGER dose_occurrences_rollup_dirty_insert AFTER INSERT ON dose_occurrences
  FOR EACH ROW EXECUTE FUNCTION dosegram_dose_rollup_dirty();
-- The reminder loop's own updates change no figure and queue nothing: a move between the open statuses (pending,
-- due, snoozed), reminder_seq, next_action_at, error_count, and the due window of a dose that is not taken. The due
-- window matters only for a taken dose, where it decides between on time and late.
CREATE TRIGGER dose_occurrences_rollup_dirty_update AFTER UPDATE ON dose_occurrences
  FOR EACH ROW WHEN (
    (OLD.status IS DISTINCT FROM NEW.status
      AND NOT (OLD.status IN ('pending','due','snoozed') AND NEW.status IN ('pending','due','snoozed')))
    OR OLD.effective_at IS DISTINCT FROM NEW.effective_at
    OR OLD.skipped_at IS DISTINCT FROM NEW.skipped_at OR OLD.missed_at IS DISTINCT FROM NEW.missed_at
    OR OLD.scheduled_for IS DISTINCT FROM NEW.scheduled_for
    OR (NEW.status = 'taken' AND OLD.due_window_end IS DISTINCT FROM NEW.due_window_end))
  EXECUTE FUNCTION dosegram_dose_rollup_dirty();

-- Day attribution of doses follows the account zone, so a zone change queues
-- every medication day of the account.
CREATE FUNCTION dosegram_zone_rollup_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO rollup_dirty (account_id, local_date, domain)
  SELECT NEW.id, days.local_date, 'medication'
  FROM (
    SELECT (ts AT TIME ZONE 'UTC')::date + shift AS local_date
    FROM dose_occurrences o,
         unnest(ARRAY[o.scheduled_for, o.effective_at, o.skipped_at, o.missed_at]) AS ts,
         generate_series(-1, 1) AS shift
    WHERE o.account_id = NEW.id AND ts IS NOT NULL
    UNION
    SELECT r.local_date FROM daily_rollups r WHERE r.account_id = NEW.id AND r.domain = 'medication'
  ) days
  ORDER BY 2
  ON CONFLICT (account_id, local_date, domain) DO UPDATE SET marked_at = EXCLUDED.marked_at;
  RETURN NULL;
END
$$;

CREATE TRIGGER users_zone_rollup_dirty AFTER UPDATE OF timezone ON users
  FOR EACH ROW WHEN (OLD.timezone IS DISTINCT FROM NEW.timezone)
  EXECUTE FUNCTION dosegram_zone_rollup_dirty();

-- ---------------------------------------------------------------------------
-- Consent ledger (docs/ARCHITECTURE.md section 10.2). consent_events is
-- append-only; consent_state is its projection and is written in the same
-- transaction. The purposes are exactly those of PRODUCT_SPEC R-IDC-6, plus
-- the ledger kind 'attestation'. notice_hash is the SHA-256 of the statement
-- the user confirmed; the statement itself is copy, not data. A grant carries
-- both taps of the two-step consent: agreed_at is the first tap ("I agree"),
-- occurred_at the confirming one, and locale is the locale the statement was
-- shown in (PRODUCT_SPEC R-IDC-7, R-IDC-8). The Mini App never records a
-- consent, so 'bot' is the only channel.
-- ---------------------------------------------------------------------------

CREATE TABLE consent_events (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  purpose text NOT NULL CHECK (purpose IN ('medication','hydration','mood','sleep','nutrition','vitals','images_in_chat',
    'sleep_passive_estimate','export_to_chat','photo_storage','cross_domain_patterns','caregiver_sharing','import','attestation')),
  action text NOT NULL CHECK (action IN ('granted','withdrawn','attested')),
  policy_version int NOT NULL CHECK (policy_version > 0), notice_hash bytea, locale text, agreed_at timestamptz,
  channel text NOT NULL CHECK (channel = 'bot'), subject_ref uuid, occurred_at timestamptz NOT NULL,
  CONSTRAINT consent_events_grant_taps CHECK (action <> 'granted'
    OR (notice_hash IS NOT NULL AND locale IS NOT NULL AND agreed_at IS NOT NULL AND agreed_at <= occurred_at)));
CREATE INDEX consent_events_account_purpose_idx ON consent_events (account_id, purpose, occurred_at);

CREATE TABLE consent_state (account_id uuid NOT NULL REFERENCES users,
  purpose text NOT NULL CHECK (purpose IN ('medication','hydration','mood','sleep','nutrition','vitals','images_in_chat',
    'sleep_passive_estimate','export_to_chat','photo_storage','cross_domain_patterns','caregiver_sharing','import')),
  granted boolean NOT NULL, policy_version int NOT NULL CHECK (policy_version > 0), updated_at timestamptz NOT NULL,
  PRIMARY KEY (account_id, purpose));

CREATE TRIGGER consent_events_append_only BEFORE UPDATE OR DELETE ON consent_events
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_append_only();

-- ---------------------------------------------------------------------------
-- Stores of the edges (docs/ARCHITECTURE.md sections 9.4, 10.2 and 10.11): what
-- the process used to hold in memory and lost on a restart. None of them is a
-- record of the user: each row is derived, has a deleting job, and may be
-- dropped at any time at the cost of one render, one upload, one sign-in or
-- one repeated request.
--
-- render_assets holds the bytes of a drawn image until the adapter has
-- uploaded it; purge_after is at most one hour after the image was drawn.
-- telegram_file_cache maps an asset to the file id the Bot API returned, per
-- bot and media kind (TG-35, TG-36). miniapp_sessions holds the SHA-256 of a
-- session token and of the launch data, never the values (API-05).
-- idempotency_keys holds the outcome of a creating request for 24 hours
-- (API-16); a row without a response is a request still in flight.
--
-- Not yet as section 10 specifies them: the bytes, the file ids of personal
-- images and the stored responses are not encrypted, and no row names the
-- account of a personal image. Both arrive with the account data keys
-- (ROADMAP G2.4, G2.4b), before real data enters.
-- ---------------------------------------------------------------------------

CREATE TABLE render_assets (asset_key text PRIMARY KEY, mime text NOT NULL, byte_size int NOT NULL CHECK (byte_size > 0),
  bytes bytea NOT NULL, created_at timestamptz NOT NULL, purge_after timestamptz NOT NULL,
  CONSTRAINT render_assets_lifetime CHECK (purge_after > created_at AND purge_after <= created_at + interval '1 hour'));
CREATE INDEX ix_render_assets_purge_after ON render_assets (purge_after);

CREATE TABLE telegram_file_cache (asset_key text NOT NULL, bot_id bigint NOT NULL,
  media_kind text NOT NULL CHECK (media_kind IN ('photo','document','animation','sticker')), file_id text NOT NULL,
  created_at timestamptz NOT NULL, last_used_at timestamptz NOT NULL, PRIMARY KEY (asset_key, bot_id, media_kind));
CREATE INDEX ix_telegram_file_cache_last_used_at ON telegram_file_cache (last_used_at);

CREATE TABLE miniapp_sessions (token_hash text PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  initdata_hash text NOT NULL, created_at timestamptz NOT NULL, last_used_at timestamptz NOT NULL);
CREATE INDEX ix_miniapp_sessions_account ON miniapp_sessions (account_id);
CREATE INDEX ix_miniapp_sessions_initdata_hash ON miniapp_sessions (initdata_hash);

CREATE TABLE idempotency_keys (account_id uuid NOT NULL REFERENCES users, key uuid NOT NULL,
  request_hash text, response_json text, created_at timestamptz NOT NULL, PRIMARY KEY (account_id, key),
  CONSTRAINT idempotency_keys_outcome CHECK ((request_hash IS NULL) = (response_json IS NULL)));
CREATE INDEX ix_idempotency_keys_created_at ON idempotency_keys (created_at);

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO dosegram_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO dosegram_app;
REVOKE UPDATE ON dose_actions, auth_audit_log FROM dosegram_app;
GRANT SELECT, DELETE ON dose_actions, auth_audit_log TO dosegram_erasure;
-- Derived rows and the quick-add settings are replaced by deleting them. Entries are deleted by erasure only.
GRANT DELETE ON daily_rollups, rollup_dirty, hydration_presets TO dosegram_app;
GRANT SELECT, DELETE ON hydration_entries, mood_checkins TO dosegram_erasure;
GRANT SELECT, DELETE ON consent_events, consent_state TO dosegram_erasure;
-- The stores of the edges are emptied by their own jobs, and by erasure for the rows that name an account.
GRANT DELETE ON render_assets, telegram_file_cache, miniapp_sessions, idempotency_keys TO dosegram_app;
GRANT SELECT, DELETE ON miniapp_sessions, idempotency_keys TO dosegram_erasure;
-- Deleting an entry queues its day through the rollup trigger, and erasure removes the derived rows as well.
GRANT SELECT, INSERT, UPDATE, DELETE ON rollup_dirty, daily_rollups TO dosegram_erasure;
