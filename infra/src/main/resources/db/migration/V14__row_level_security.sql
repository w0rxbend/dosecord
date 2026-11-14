-- V14: forced row-level security on every account-owned table (ROADMAP G2.3;
-- ARCHITECTURE DATA-02, API-10, SEC-08, SEC-09, section 15.3; supervisor
-- decision, 2026-11-14).
--
-- The claim. Every transaction of the unit of work sets its role and, for one
-- account, the claim app.account_id with set_config(..., true), so both end
-- with the transaction:
--   dosegram_app     one account: a row is visible and writable only when its
--                    account is the claim; without a claim, no row at all.
--   dosegram_worker  the documented cross-account work: the reminder, prompt,
--                    report, export and inventory loops, the outbox
--                    dispatcher, the rollup worker, the sweepers, the purge
--                    replay, and a chat update until its sender is known. It
--                    has the grants of dosegram_app (by membership) and a
--                    policy that admits every account; never ownership, DDL or
--                    BYPASSRLS.
--   dosegram_erasure the erasure job (purge): every account, delete only where
--                    its grants allow.
--   the schema owner the role that applies this migration: migrations and the
--                    start-up stages (sealing backfill, key rewrap). Serving
--                    code never runs as it; the unit of work sets one of the
--                    roles above on every transaction and every pooled
--                    statement.
--
-- Upgrade in place. Nothing here revokes a grant. A login role that is a
-- superuser bypasses row-level security and may take every role, so nothing
-- else is needed. A login role that is not a superuser is made a member of the
-- three roles above when it may grant them (it created them in V1); otherwise
-- this migration still applies, and the start-up check refuses to serve and
-- names the GRANT the owner runs (docs/OPERATIONS.md section 10).

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dosegram_worker') THEN
    CREATE ROLE dosegram_worker NOLOGIN;
  END IF;
END
$$;

-- The worker reaches every table the application role reaches, and nothing more.
GRANT dosegram_app TO dosegram_worker;

-- The chat deletes an ended conversation, its form run and its callback slots.
-- The application ran as the schema owner until now, so the grant was never
-- needed; the blanket grant of V1 gives no DELETE.
GRANT DELETE ON conversation_sessions, form_runs, callback_slots TO dosegram_app;

DO $$
BEGIN
  IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
    EXECUTE format('GRANT dosegram_app, dosegram_worker, dosegram_erasure TO %I', current_user);
  END IF;
EXCEPTION WHEN insufficient_privilege THEN
  RAISE WARNING 'dosegram: % may not grant the application roles; run GRANT dosegram_app, dosegram_worker, dosegram_erasure TO % as a superuser', current_user, current_user;
END
$$;

-- The claim of the current transaction, or NULL. An unset or empty setting
-- matches no row. A plain SQL function, so the planner inlines it and the
-- comparison stays an index condition.
CREATE FUNCTION dosegram_current_account() RETURNS uuid LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT NULLIF(current_setting('app.account_id', true), '')::uuid
$$;

-- The four policies of an account-owned table. `account_column` is the column
-- that names the account; `nullable` lets a row that names no account be
-- written (a row of the onboarding, before the account exists) but never read
-- through the claim. ARCHITECTURE section 10.17: every new account-owned table
-- calls this in the migration that creates it, and RlsSuite fails otherwise.
CREATE FUNCTION dosegram_protect_account_table(target regclass, account_column name, nullable boolean)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  own text := format('%I = dosegram_current_account()', account_column);
  writable text := CASE WHEN nullable THEN format('(%s OR %I IS NULL)', own, account_column) ELSE own END;
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
  EXECUTE format('CREATE POLICY tenant_isolation ON %s TO dosegram_app USING (%s) WITH CHECK (%s)', target, own, writable);
  EXECUTE format('CREATE POLICY cross_account_worker ON %s TO dosegram_worker USING (true) WITH CHECK (true)', target);
  EXECUTE format('CREATE POLICY erasure_job ON %s TO dosegram_erasure USING (true) WITH CHECK (true)', target);
  EXECUTE format('CREATE POLICY schema_owner ON %s TO %I USING (true) WITH CHECK (true)', target, current_user);
END
$$;

DO $$
BEGIN
  -- The account itself.
  PERFORM dosegram_protect_account_table('users', 'id', false);

  -- Identity and the retired credential tables.
  PERFORM dosegram_protect_account_table('platform_identities', 'user_id', true);
  PERFORM dosegram_protect_account_table('user_credentials', 'user_id', false);
  PERFORM dosegram_protect_account_table('auth_challenges', 'user_id', true);
  PERFORM dosegram_protect_account_table('recovery_codes', 'user_id', false);
  PERFORM dosegram_protect_account_table('auth_audit_log', 'user_id', true);
  PERFORM dosegram_protect_account_table('account_keys', 'account_id', false);
  PERFORM dosegram_protect_account_table('account_tags', 'account_id', false);
  PERFORM dosegram_protect_account_table('consent_events', 'account_id', false);
  PERFORM dosegram_protect_account_table('consent_state', 'account_id', false);
  PERFORM dosegram_protect_account_table('miniapp_sessions', 'account_id', false);
  PERFORM dosegram_protect_account_table('idempotency_keys', 'account_id', false);

  -- Medications.
  PERFORM dosegram_protect_account_table('medications', 'user_id', false);
  PERFORM dosegram_protect_account_table('medication_schedules', 'user_id', false);
  PERFORM dosegram_protect_account_table('dose_occurrences', 'account_id', false);
  PERFORM dosegram_protect_account_table('dose_actions', 'account_id', false);
  PERFORM dosegram_protect_account_table('medication_inventory', 'account_id', false);
  PERFORM dosegram_protect_account_table('inventory_events', 'account_id', false);
  PERFORM dosegram_protect_account_table('reminders', 'account_id', false);

  -- Delivery and the chat.
  PERFORM dosegram_protect_account_table('delivery_channels', 'account_id', false);
  PERFORM dosegram_protect_account_table('outbox_messages', 'account_id', true);
  PERFORM dosegram_protect_account_table('rendered_messages', 'account_id', true);
  PERFORM dosegram_protect_account_table('inbound_events', 'account_id', true);
  PERFORM dosegram_protect_account_table('domain_events', 'account_id', true);
  PERFORM dosegram_protect_account_table('callback_slots', 'account_id', true);

  -- Tracking, rollups, prompts, reports and exports.
  PERFORM dosegram_protect_account_table('hydration_entries', 'account_id', false);
  PERFORM dosegram_protect_account_table('hydration_goals', 'account_id', false);
  PERFORM dosegram_protect_account_table('hydration_presets', 'account_id', false);
  PERFORM dosegram_protect_account_table('mood_checkins', 'account_id', false);
  PERFORM dosegram_protect_account_table('sleep_episodes', 'account_id', false);
  PERFORM dosegram_protect_account_table('sleep_checkins', 'account_id', false);
  PERFORM dosegram_protect_account_table('sleep_goals', 'account_id', false);
  PERFORM dosegram_protect_account_table('meals', 'account_id', false);
  PERFORM dosegram_protect_account_table('meal_plans', 'account_id', false);
  PERFORM dosegram_protect_account_table('daily_rollups', 'account_id', false);
  PERFORM dosegram_protect_account_table('rollup_dirty', 'account_id', false);
  PERFORM dosegram_protect_account_table('day_profiles', 'account_id', false);
  PERFORM dosegram_protect_account_table('prompt_schedules', 'account_id', false);
  PERFORM dosegram_protect_account_table('prompt_occurrences', 'account_id', false);
  PERFORM dosegram_protect_account_table('report_requests', 'account_id', false);
  PERFORM dosegram_protect_account_table('export_jobs', 'account_id', false);
  PERFORM dosegram_protect_account_table('export_download_tickets', 'account_id', false);
END
$$;

-- schedule_revisions names its account through its schedule. The subquery runs
-- under the same policies, so the tenant sees the revisions of its own
-- schedules only.
ALTER TABLE schedule_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE schedule_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON schedule_revisions TO dosegram_app
  USING (schedule_id IN (SELECT id FROM medication_schedules WHERE user_id = dosegram_current_account()))
  WITH CHECK (schedule_id IN (SELECT id FROM medication_schedules WHERE user_id = dosegram_current_account()));
CREATE POLICY cross_account_worker ON schedule_revisions TO dosegram_worker USING (true) WITH CHECK (true);
CREATE POLICY erasure_job ON schedule_revisions TO dosegram_erasure USING (true) WITH CHECK (true);
DO $$
BEGIN
  EXECUTE format('CREATE POLICY schema_owner ON schedule_revisions TO %I USING (true) WITH CHECK (true)', current_user);
END
$$;

-- The view of V1 reads dose_occurrences with the privileges of the caller, so
-- the policies of dose_occurrences apply through it.
ALTER VIEW reminder_occurrences SET (security_invoker = true);
GRANT SELECT ON reminder_occurrences TO dosegram_app;
