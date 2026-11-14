package dosegram.infra.db

import dosegram.core.ports.KeyOwner
import dosegram.core.ports.KeyService
import dosegram.core.ports.SealedField

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import java.util.UUID

/** The keyed lookup column next to a sealed column: what the hash is computed over, and for which purpose. */
final case class LookupColumn(column: String, purpose: String, normalise: String => String)

/** Where the owner of a sealed value comes from: an account column of the row (an SQL expression over alias `t`), or a
  * service key.
  */
enum SealOwner:
  case AccountOf(expression: String)
  case Service(purpose: String)

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

object SealedColumns:

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

/** Text over a [[KeyService]]: UTF-8 in, UTF-8 out. */
extension (keys: KeyService)
  def sealText(field: SealedField, text: String): Array[Byte] = keys.seal(field, text.getBytes(UTF_8))
  def openText(field: SealedField, value: Array[Byte]): String = String(keys.open(field, value), UTF_8)
  def lookup(column: SealedColumn, account: UUID, value: String): Array[Byte] =
    val spec = column.lookup.getOrElse(throw IllegalArgumentException(s"${column.table}.${column.plain} has no lookup"))
    keys.lookupHash(account, spec.purpose, spec.normalise(value))
