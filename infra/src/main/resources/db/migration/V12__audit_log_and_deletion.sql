-- V12: the append-only audit log and account and domain deletion (ROADMAP
-- G2.16, G2.17; docs/ARCHITECTURE.md section 10, SEC-10, SEC-16, SEC-17;
-- research brief 06, sections 9.3 and 10).

-- ---------------------------------------------------------------------------
-- Security and audit (docs/ARCHITECTURE.md section 10.17).
--
-- audit_log: who did what to which kind of record, when, through which
-- channel, with which outcome (brief 06 section 10.2). It never holds health
-- content: the codes are closed lists checked by shape, so no free text fits,
-- and no column holds a name, a value, a note or a vendor identifier.
-- actor_ref is an account id, a tombstone id or a service name; subject_ref is
-- the account whose data was touched. On account deletion both are replaced by
-- the tombstone id of the deletion: the only UPDATE the table accepts, and only
-- under the erasure role with the erasure GUC. prev_hash and hash stay NULL
-- until the hash chain of ROADMAP G11.6b; they exist now so the shape does not
-- change then.
--
-- Not as section 10 lists it: the table is not partitioned by month (one owner;
-- the retention sweeper of G11.8 deletes by occurred_at), and no row has a
-- session_id or ip_hash yet.
--
-- deletion_receipts: one row per deletion, with the tombstone id, the scope
-- (the account or one domain) and the number of rows deleted per table. No
-- account id.
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log (id uuid PRIMARY KEY, occurred_at timestamptz NOT NULL,
  event_type text NOT NULL CHECK (event_type ~ '^[a-z_]+(\.[a-z_]+)+$'),
  actor_type text NOT NULL CHECK (actor_type IN ('user','caregiver','operator','system')),
  actor_ref text NOT NULL CHECK (actor_ref ~ '^[a-z0-9_.:-]{1,64}$'),
  subject_ref uuid,
  object_type text NOT NULL CHECK (object_type ~ '^[a-z_]{1,32}$'),
  object_id uuid,
  action text NOT NULL CHECK (action ~ '^[a-z_]{1,32}$'),
  outcome text NOT NULL CHECK (outcome IN ('success','denied','error')),
  reason_code text CHECK (reason_code ~ '^[a-z_]{1,64}$'),
  channel text NOT NULL CHECK (channel IN ('bot','miniapp','admin_cli','scheduler')),
  request_id text CHECK (request_id ~ '^[A-Za-z0-9_.:-]{1,128}$'),
  session_id text, ip_hash bytea,
  policy_version text CHECK (policy_version ~ '^[a-z0-9_.:-]{1,64}$'),
  changed_fields text[] NOT NULL DEFAULT '{}' CHECK (array_to_string(changed_fields, ',') ~ '^[a-z_,]*$'),
  prev_hash bytea, hash bytea);
CREATE INDEX ix_audit_log_subject_occurred ON audit_log (subject_ref, occurred_at);
CREATE INDEX ix_audit_log_occurred ON audit_log (occurred_at);

CREATE TABLE deletion_receipts (tombstone_id uuid PRIMARY KEY,
  scope text NOT NULL CHECK (scope IN ('account','medication','hydration','mood','sleep','nutrition')),
  deleted_at timestamptz NOT NULL,
  row_counts jsonb NOT NULL CHECK (jsonb_typeof(row_counts) = 'object'));

-- The erasure test of V1's guard, as a function: the erasure GUC is on and the
-- current role is the erasure role or an explicit member of it.
CREATE FUNCTION dosegram_erasing() RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT current_setting('dosegram.erasure', true) IS NOT DISTINCT FROM 'on'
    AND (current_user = 'dosegram_erasure' OR EXISTS (
      SELECT 1 FROM pg_auth_members m
      JOIN pg_roles r ON r.oid = m.roleid
      WHERE r.rolname = 'dosegram_erasure'
        AND m.member = (SELECT oid FROM pg_roles WHERE rolname = current_user)))
$$;

-- audit_log rejects every UPDATE and DELETE, except under erasure: a DELETE
-- (the retention sweeper), or an UPDATE that changes nothing but actor_ref and
-- subject_ref (the pseudonymisation of a deleted account).
CREATE FUNCTION dosegram_guard_audit_log() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT dosegram_erasing() THEN
    RAISE EXCEPTION 'dosegram: audit_log is append-only; % requires the erasure role with SET LOCAL dosegram.erasure = ''on''', TG_OP;
  END IF;
  IF TG_OP = 'UPDATE' THEN
    IF (to_jsonb(NEW) - 'actor_ref' - 'subject_ref') IS DISTINCT FROM (to_jsonb(OLD) - 'actor_ref' - 'subject_ref') THEN
      RAISE EXCEPTION 'dosegram: audit_log accepts an UPDATE of actor_ref and subject_ref only';
    END IF;
    RETURN NEW;
  END IF;
  RETURN OLD;
END
$$;

CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_audit_log();
CREATE TRIGGER deletion_receipts_append_only BEFORE UPDATE OR DELETE ON deletion_receipts
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_append_only();

-- ---------------------------------------------------------------------------
-- Grants. The blanket grant of V1 covers only the tables that existed then.
-- ---------------------------------------------------------------------------

-- The application role appends and reads; it never changes or removes a row.
GRANT SELECT, INSERT ON audit_log, deletion_receipts TO dosegram_app;
REVOKE UPDATE, DELETE ON audit_log, deletion_receipts FROM dosegram_app;
GRANT SELECT, DELETE ON audit_log TO dosegram_erasure;
GRANT UPDATE (actor_ref, subject_ref) ON audit_log TO dosegram_erasure;

-- The erasure job deletes every account-owned row under the erasure role
-- (SEC-16): the tables whose erasure grant earlier migrations did not give.
GRANT SELECT, DELETE ON users, platform_identities, user_credentials, auth_challenges, recovery_codes,
  medications, medication_schedules, schedule_revisions, dose_occurrences, outbox_messages, rendered_messages,
  inbound_events, inbound_inbox, domain_events, callback_slots, conversation_sessions, form_runs,
  delivery_channels, reminders, hydration_goals, hydration_presets, account_tags, render_assets,
  telegram_file_cache TO dosegram_erasure;
