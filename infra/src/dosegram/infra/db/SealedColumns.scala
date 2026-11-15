package dosegram.infra.db

import dosegram.core.ports.KeyOwner
import dosegram.core.ports.KeyService
import dosegram.core.ports.SealedField

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import java.util.UUID

/** The keyed lookup column next to a sealed column: what the hash is computed over, and for which purpose. */
final case class LookupColumn(column: String, purpose: String, normalise: String => String)

/** Where the owner of a sealed value comes from: an account column of the row (an SQL expression over alias `t`), a
  * service key, or the account column when the row has an account and the service key when it has none.
  */
enum SealOwner:
  case AccountOf(expression: String)
  case Service(purpose: String)
  case AccountOr(expression: String, purpose: String)

/** One Class A column and its sealed form (ARCHITECTURE SEC-01, SEC-04; V10, V11). `plain` is the column V11 emptied;
  * `encrypted` is the column the value lives in, bound to the row by `rowKey`. The repositories, the backfill, the
  * archive and the schema test all take the places from here, so the associated data cannot drift between writer and
  * reader.
  */
final case class SealedColumn(
    table: String,
    plain: String,
    encrypted: String,
    rowKey: String,
    owner: SealOwner,
    lookup: Option[LookupColumn] = None
):
  def field(owner: KeyOwner, row: Any): SealedField = SealedField(owner, table, encrypted, row.toString)

  def forAccount(account: UUID, row: Any): SealedField = field(KeyOwner.Account(account), row)

  /** The field of a row of an [[SealOwner.AccountOr]] column: its account's key, or the service key without one. */
  def forRow(account: Option[UUID], row: Any): SealedField = owner match
    case SealOwner.AccountOr(_, purpose) => field(account.fold(KeyOwner.Service(purpose))(KeyOwner.Account(_)), row)
    case other => throw IllegalArgumentException(s"$table.$encrypted is not sealed by row ($other)")

  /** The field of a row of a [[SealOwner.Service]] column. */
  def forService(row: Any): SealedField = owner match
    case SealOwner.Service(purpose) => field(KeyOwner.Service(purpose), row)
    case other => throw IllegalArgumentException(s"$table.$encrypted is not sealed by a service key ($other)")

object SealedColumns:

  /** The service key of the transport rows written before an account existed (the onboarding). */
  val TransportPurpose: String = "transport"

  /** The normalised form of a medication name, the same on write and on search: trimmed, lower case. */
  def medicationNameNorm(name: String): String = name.trim.toLowerCase(Locale.ROOT)

  val MedicationName: SealedColumn = SealedColumn(
    "medications",
    "name",
    "name_enc",
    "id",
    SealOwner.AccountOf("t.user_id"),
    Some(LookupColumn("name_hmac", "medication_name", medicationNameNorm))
  )
  val MedicationAlias: SealedColumn =
    SealedColumn("medications", "display_alias", "display_alias_enc", "id", SealOwner.AccountOf("t.user_id"))
  val MedicationInstructions: SealedColumn =
    SealedColumn("medications", "instructions", "instructions_enc", "id", SealOwner.AccountOf("t.user_id"))
  val RevisionSnapshot: SealedColumn = SealedColumn(
    "schedule_revisions",
    "dose_snapshot",
    "dose_snapshot_enc",
    "id",
    SealOwner.AccountOf("(SELECT s.user_id FROM medication_schedules s WHERE s.id = t.schedule_id)")
  )
  val OccurrenceSnapshot: SealedColumn =
    SealedColumn("dose_occurrences", "dose_snapshot", "dose_snapshot_enc", "id", SealOwner.AccountOf("t.account_id"))
  val DoseNote: SealedColumn =
    SealedColumn("dose_actions", "note", "note_enc", "id", SealOwner.AccountOf("t.account_id"))
  val MealWords: SealedColumn = SealedColumn(
    "meals",
    "description",
    "description_enc",
    "id",
    SealOwner.AccountOf("t.account_id"),
    Some(LookupColumn("description_hmac", "meal_words", identity))
  )
  val ExportContent: SealedColumn =
    SealedColumn("export_jobs", "content", "content_enc", "id", SealOwner.AccountOf("t.account_id"))
  val RenderBytes: SealedColumn =
    SealedColumn("render_assets", "bytes", "bytes_enc", "asset_key", SealOwner.Service("render"))
  val IdempotencyResponse: SealedColumn =
    SealedColumn("idempotency_keys", "response_json", "response_enc", "key", SealOwner.AccountOf("t.account_id"))

  /** The text of a message the bot queued (V13): medication names and instructions among it. */
  val OutboxPayload: SealedColumn = SealedColumn(
    "outbox_messages",
    "payload",
    "payload_enc",
    "id",
    SealOwner.AccountOr("t.account_id", TransportPurpose)
  )

  /** The stored answer to an update, replayed on a repeated delivery (V13). */
  val InboundReply: SealedColumn = SealedColumn(
    "inbound_events",
    "reply",
    "reply_enc",
    "event_id",
    SealOwner.AccountOr("t.account_id", TransportPurpose)
  )

  /** An update as received (V21): the message text. The inbox keys a row by chat before any account exists. */
  val InboxBody: SealedColumn =
    SealedColumn("inbound_inbox", "body", "body_enc", "vendor_event_id", SealOwner.Service(TransportPurpose))

  /** An open wizard's draft and its last question (V21): a typed medication name among them. A session is keyed by the
    * chat's principal, which is an identity before the account exists, so the service key seals it.
    */
  val SessionData: SealedColumn =
    SealedColumn("conversation_sessions", "data", "data_enc", "id", SealOwner.Service(TransportPurpose))
  val SessionPrompt: SealedColumn =
    SealedColumn("conversation_sessions", "last_prompt", "last_prompt_enc", "id", SealOwner.Service(TransportPurpose))

  /** An open form's answers (V21), keyed by its session. */
  val FormAnswers: SealedColumn =
    SealedColumn("form_runs", "answers", "answers_enc", "session_id", SealOwner.Service(TransportPurpose))

  /** The state behind a button (V21). */
  val SlotPayload: SealedColumn = SealedColumn(
    "callback_slots",
    "payload",
    "payload_enc",
    "id",
    SealOwner.AccountOr("t.account_id", TransportPurpose)
  )

  /** The event log (V21): the envelope and the actor of each event. */
  val EventData: SealedColumn =
    SealedColumn("domain_events", "data", "data_enc", "id", SealOwner.AccountOr("t.account_id", TransportPurpose))
  val EventActor: SealedColumn =
    SealedColumn("domain_events", "actor", "actor_enc", "id", SealOwner.AccountOr("t.account_id", TransportPurpose))

  /** A reminder's own words (V21). */
  val ReminderTitle: SealedColumn =
    SealedColumn("reminders", "title", "title_enc", "id", SealOwner.AccountOf("t.account_id"))

  /** The unit of a strength (V21): a catalogue code, or the word the user typed. */
  val MedicationDoseUnit: SealedColumn =
    SealedColumn("medications", "dose_unit", "dose_unit_enc", "id", SealOwner.AccountOf("t.user_id"))

  /** A staged record of an open import (V15): the whole normalised record, sealed. It has no plaintext column, so it is
    * in neither list below: the backfill has nothing to seal and the archive does not export the staging.
    */
  val ImportPayload: SealedColumn =
    SealedColumn("import_staging", "payload", "payload_enc", "id", SealOwner.AccountOf("t.account_id"))

  /** Every sealed column, in the order of V11. */
  val all: List[SealedColumn] = List(
    MedicationName,
    MedicationAlias,
    MedicationInstructions,
    RevisionSnapshot,
    OccurrenceSnapshot,
    DoseNote,
    MealWords,
    ExportContent,
    RenderBytes,
    IdempotencyResponse
  )

  /** The sealed columns of V13. No contract migration empties their plaintext: the backfill right after V13 seals each
    * value and empties its plaintext column in the same UPDATE, and the release writes the sealed column only.
    */
  val emptiedOnSeal: List[SealedColumn] = List(OutboxPayload, InboundReply)

  /** The sealed columns of V21, which the backfill right after V21 seals and empties like those of V13. */
  val chatState: List[SealedColumn] = List(
    InboxBody,
    SessionData,
    SessionPrompt,
    FormAnswers,
    SlotPayload,
    EventData,
    EventActor,
    ReminderTitle,
    MedicationDoseUnit
  )

/** Text over a [[KeyService]]: UTF-8 in, UTF-8 out. */
extension (keys: KeyService)
  def sealText(field: SealedField, text: String): Array[Byte] = keys.seal(field, text.getBytes(UTF_8))
  def openText(field: SealedField, value: Array[Byte]): String = String(keys.open(field, value), UTF_8)
  def lookup(column: SealedColumn, account: UUID, value: String): Array[Byte] =
    val spec = column.lookup.getOrElse(throw IllegalArgumentException(s"${column.table}.${column.plain} has no lookup"))
    keys.lookupHash(account, spec.purpose, spec.normalise(value))
