package dosegram.infra.db

/** How the full export reads one table of the schema (PRODUCT_SPEC R-IMX-1, ROADMAP G3.10). */
enum Coverage:

  /** Every row whose `column` is the account. */
  case Owned(column: String)

  /** The rows that `select` returns for the account, bound to its one `?`; for a table keyed through a parent, or one
    * with columns that must not leave the service.
    */
  case Selected(select: String)

  /** Not a record of the user: a store of the edges, a queue, a secret or a figure derived from the exported rows. */
  case Excluded(reason: String)

/** The export registry: every table of the schema and how the full export covers it. A table that is not listed here
  * and has an `account_id` column is exported whole; any other table that is not listed fails `ExportCoveragePgSuite`,
  * so a new domain cannot be merged without an export decision (ROADMAP G3.10).
  */
object ExportCoverage:

  val OwnerColumn = "account_id"

  val tables: Map[String, Coverage] = Map(
    "users" -> Coverage.Owned("id"),
    "platform_identities" -> Coverage.Owned("user_id"),
    "user_credentials" -> Coverage.Excluded("password hashes and lockout counters: secrets, not records"),
    "auth_challenges" -> Coverage.Excluded("single-use sign-in tokens"),
    "recovery_codes" -> Coverage.Excluded("hashes of recovery codes"),
    "auth_audit_log" -> Coverage.Owned("user_id"),
    "medications" -> Coverage.Owned("user_id"),
    "medication_schedules" -> Coverage.Owned("user_id"),
    "schedule_revisions" -> Coverage.Selected(
      """SELECT r.* FROM schedule_revisions r JOIN medication_schedules s ON s.id = r.schedule_id
         WHERE s.user_id = ? ORDER BY r.schedule_id, r.revision"""
    ),
    "dose_occurrences" -> Coverage.Owned(OwnerColumn),
    "dose_actions" -> Coverage.Owned(OwnerColumn),
    "outbox_messages" -> Coverage.Excluded("the delivery queue of bot messages"),
    "rendered_messages" -> Coverage.Excluded("handles of the messages the bot sent"),
    "inbound_events" -> Coverage.Excluded("the idempotency record of received updates"),
    "inbound_inbox" -> Coverage.Excluded("received updates before processing, at most 24 hours"),
    "domain_events" -> Coverage.Excluded("the internal event log of changes to the exported records"),
    "worker_heartbeat" -> Coverage.Excluded("process liveness; no account"),
    "callback_slots" -> Coverage.Excluded("the state of buttons"),
    "conversation_sessions" -> Coverage.Excluded("the state of an open chat wizard"),
    "form_runs" -> Coverage.Excluded("the state of an open chat form"),
    "delivery_channels" -> Coverage.Owned(OwnerColumn),
    "reminders" -> Coverage.Owned(OwnerColumn),
    "hydration_entries" -> Coverage.Owned(OwnerColumn),
    "hydration_goals" -> Coverage.Owned(OwnerColumn),
    "hydration_presets" -> Coverage.Owned(OwnerColumn),
    "account_tags" -> Coverage.Owned(OwnerColumn),
    "mood_checkins" -> Coverage.Owned(OwnerColumn),
    "daily_rollups" -> Coverage.Excluded("daily figures derived from the exported entries"),
    "rollup_dirty" -> Coverage.Excluded("the work queue of the daily figures"),
    "consent_events" -> Coverage.Owned(OwnerColumn),
    "consent_state" -> Coverage.Owned(OwnerColumn),
    "render_assets" -> Coverage.Excluded("drawn images, at most one hour"),
    "telegram_file_cache" -> Coverage.Excluded("upload handles of drawn images"),
    "miniapp_sessions" -> Coverage.Excluded("hashes of session tokens"),
    "idempotency_keys" -> Coverage.Excluded("stored answers to repeated requests, at most 24 hours"),
    "sleep_checkins" -> Coverage.Owned(OwnerColumn),
    "sleep_episodes" -> Coverage.Owned(OwnerColumn),
    "sleep_goals" -> Coverage.Owned(OwnerColumn),
    "day_profiles" -> Coverage.Owned(OwnerColumn),
    "prompt_schedules" -> Coverage.Owned(OwnerColumn),
    "prompt_occurrences" -> Coverage.Owned(OwnerColumn),
    "meals" -> Coverage.Owned(OwnerColumn),
    "meal_plans" -> Coverage.Owned(OwnerColumn),
    // The history of the account's exports, part of the access history (R-IMX-1); never the bytes.
    "export_jobs" -> Coverage.Selected(
      """SELECT id, kind, params, status, file_name, byte_size, created_at, confirmed_at, ready_at, expires_at,
                downloaded_at
         FROM export_jobs WHERE account_id = ? ORDER BY created_at, id"""
    ),
    "export_download_tickets" -> Coverage.Excluded("hashes of download tickets"),
    "flyway_schema_history" -> Coverage.Excluded("the migration history of the schema; no account")
  )
end ExportCoverage
