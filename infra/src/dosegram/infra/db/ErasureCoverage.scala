package dosegram.infra.db

import dosegram.core.ports.DataDomain

/** How the erasure job treats one table (ROADMAP G2.17, G5.7; ARCHITECTURE SEC-16, SEC-17). A predicate is the WHERE
  * clause of a DELETE; every `?` in it is bound to the account id.
  */
enum Erasure:

  /** Rows of the account that belong to one or more domains: `account` selects them all, `byDomain` the rows of each
    * domain the table holds, and a domain deletion deletes those and nothing else.
    */
  case Domain(account: String, byDomain: Map[DataDomain, String])

  /** Rows of the account that are not one domain's records; a domain deletion leaves them. */
  case AccountWide(account: String, why: String)

  /** Rows that must outlive the account without naming it: the account is replaced by the tombstone. */
  case Pseudonymised(why: String)

  /** No row names an account: a process table, the schema history, or a receipt that holds counts only. */
  case NotOwned(why: String)

/** The erasure registry: every table of the schema, in the order the account deletion deletes them (rows that point at
  * a row come before it). `ErasurePgSuite` fails on a table of the schema that is not listed, so a new table cannot be
  * merged without an erasure decision, and on a domain table that the domain eraser does not know.
  */
object ErasureCoverage:

  private val Own = "account_id = ?"
  private val OwnUser = "user_id = ?"
  private val Occurrences = "occurrence_id IN (SELECT id FROM dose_occurrences WHERE account_id = ?)"

  /** The wizard sessions of the account: keyed by the account, or by an identity of it before the account existed. */
  private val Sessions =
    "principal_key = ?::text OR principal_key IN (SELECT id::text FROM platform_identities WHERE user_id = ?)"

  /** The chats of the account, while its `platform_identities` rows still exist. */
  private val Chats =
    "SELECT vendor, dm_channel_id FROM platform_identities WHERE user_id = ? AND dm_channel_id IS NOT NULL"

  /** The images the bot sent to the account: the asset keys its outbox messages name, and its export files. */
  private val AssetKeys =
    """SELECT key #>> '{}' FROM outbox_messages, jsonb_path_query(payload, 'lax $.**.assetKey') AS key
       WHERE account_id = ?
       UNION SELECT 'export.' || id FROM export_jobs WHERE account_id = ?"""

  private def promptKinds(kinds: String): String = s"account_id = ? AND kind IN ($kinds)"
  private def rollups(domain: String): String = s"account_id = ? AND domain = '$domain'"
  private def tags(domain: String): String = s"account_id = ? AND domain = '$domain'"

  private def only(domain: DataDomain): Erasure = Erasure.Domain(Own, Map(domain -> Own))

  val tables: List[(String, Erasure)] = List(
    // Crypto-shredding first (SEC-06, ROADMAP G2.4b): without its data keys no sealed value of the account opens, in
    // the database, its WAL or a backup, whatever the deletes after this one find.
    "account_keys" -> Erasure.AccountWide(Own, "the account's wrapped data keys: destroyed first"),
    // The stores of the edges that name no account: found through the account's messages before those go.
    "telegram_file_cache" -> Erasure.AccountWide(s"asset_key IN ($AssetKeys)", "upload handles of images sent"),
    "render_assets" -> Erasure.AccountWide(s"asset_key IN ($AssetKeys)", "drawn images, at most one hour"),
    "export_download_tickets" -> Erasure.AccountWide(Own, "download tickets of the account's exports"),
    "export_jobs" -> Erasure.AccountWide(Own, "the account's exports"),
    "report_requests" -> Erasure.AccountWide(Own, "images waiting to be drawn"),
    // Medication.
    "inventory_events" -> only(DataDomain.Medication),
    "medication_inventory" -> only(DataDomain.Medication),
    "dose_actions" -> only(DataDomain.Medication),
    "outbox_messages" -> Erasure.Domain(Own, Map(DataDomain.Medication -> Occurrences)),
    "dose_occurrences" -> only(DataDomain.Medication),
    "schedule_revisions" -> Erasure.Domain(
      "schedule_id IN (SELECT id FROM medication_schedules WHERE user_id = ?)",
      Map(DataDomain.Medication -> "schedule_id IN (SELECT id FROM medication_schedules WHERE user_id = ?)")
    ),
    "medication_schedules" -> Erasure.Domain(OwnUser, Map(DataDomain.Medication -> OwnUser)),
    "medications" -> Erasure.Domain(OwnUser, Map(DataDomain.Medication -> OwnUser)),
    "reminders" -> Erasure.AccountWide(Own, "generic reminders, not one domain"),
    // Water, mood, sleep, food.
    "hydration_entries" -> only(DataDomain.Hydration),
    "hydration_goals" -> only(DataDomain.Hydration),
    "hydration_presets" -> only(DataDomain.Hydration),
    "mood_checkins" -> only(DataDomain.Mood),
    "account_tags" -> Erasure.Domain(
      Own,
      Map(DataDomain.Mood -> tags("mood"), DataDomain.Nutrition -> tags("meal"))
    ),
    "sleep_checkins" -> only(DataDomain.Sleep),
    "sleep_episodes" -> only(DataDomain.Sleep),
    "sleep_goals" -> only(DataDomain.Sleep),
    "meals" -> only(DataDomain.Nutrition),
    "meal_plans" -> only(DataDomain.Nutrition),
    // The prompts of a domain, after the entries that answer them.
    "prompt_occurrences" -> Erasure.Domain(
      Own,
      Map(
        DataDomain.Hydration -> promptKinds("'water'"),
        DataDomain.Mood -> promptKinds("'mood'"),
        DataDomain.Sleep -> promptKinds("'sleep_bedtime', 'sleep_morning'"),
        DataDomain.Nutrition -> promptKinds("'meal'")
      )
    ),
    "prompt_schedules" -> Erasure.Domain(
      Own,
      Map(
        DataDomain.Hydration -> promptKinds("'water'"),
        DataDomain.Mood -> promptKinds("'mood'"),
        DataDomain.Sleep -> promptKinds("'sleep_bedtime', 'sleep_morning'"),
        DataDomain.Nutrition -> promptKinds("'meal'")
      )
    ),
    "day_profiles" -> Erasure.AccountWide(Own, "the day profile that every prompt reads"),
    // Derived rows, after every entry: deleting an entry queues its day.
    "daily_rollups" -> Erasure.Domain(Own, DataDomain.values.map(d => d -> rollups(d.code)).toMap),
    "rollup_dirty" -> Erasure.Domain(Own, DataDomain.values.map(d => d -> rollups(d.code)).toMap),
    // Consent, sessions and the state of the chat.
    "consent_events" -> Erasure.AccountWide(Own, "the ledger of every purpose; the audit log keeps the events"),
    "consent_state" -> Erasure.AccountWide(Own, "the consent of every purpose"),
    "miniapp_sessions" -> Erasure.AccountWide(Own, "hashes of session tokens"),
    "idempotency_keys" -> Erasure.AccountWide(Own, "stored answers to repeated requests"),
    "callback_slots" -> Erasure.AccountWide(Own, "the state of buttons"),
    "form_runs" -> Erasure.AccountWide(
      s"session_id IN (SELECT id FROM conversation_sessions WHERE $Sessions)",
      "answers of an open chat form"
    ),
    "conversation_sessions" -> Erasure.AccountWide(Sessions, "the state of an open chat wizard"),
    "rendered_messages" -> Erasure.AccountWide(Own, "handles of the messages the bot sent"),
    "inbound_events" -> Erasure.AccountWide(Own, "the idempotency record of received updates"),
    "inbound_inbox" -> Erasure.AccountWide(
      s"status <> 'pending' AND (vendor, chat_key) IN ($Chats)",
      "processed updates of the account's chat; the update being processed goes with the inbox job"
    ),
    "domain_events" -> Erasure.AccountWide(Own, "the internal event log"),
    "delivery_channels" -> Erasure.AccountWide(Own, "where reminders go"),
    // Identity, last.
    "auth_audit_log" -> Erasure.AccountWide(OwnUser, "the sign-in log of the account"),
    "recovery_codes" -> Erasure.AccountWide(OwnUser, "hashes of recovery codes"),
    "auth_challenges" -> Erasure.AccountWide(OwnUser, "single-use sign-in tokens"),
    "user_credentials" -> Erasure.AccountWide(OwnUser, "credentials"),
    "platform_identities" -> Erasure.AccountWide(OwnUser, "the link to the chat account: the bot forgets the chat"),
    "users" -> Erasure.AccountWide("id = ?", "the account"),
    // Kept without the account.
    "audit_log" -> Erasure.Pseudonymised("evidence of what was done, 13 months; the tombstone replaces the account"),
    "deletion_receipts" -> Erasure.NotOwned("the receipt of a deletion: a tombstone and counts"),
    "service_keys" -> Erasure.NotOwned("wrapped keys of rows that belong to no account"),
    "worker_heartbeat" -> Erasure.NotOwned("process liveness"),
    "flyway_schema_history" -> Erasure.NotOwned("the migration history of the schema")
  )

  require(tables.map(_._1).distinct.size == tables.size, "a table is listed twice")
end ErasureCoverage
