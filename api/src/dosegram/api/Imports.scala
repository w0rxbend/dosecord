package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import dosegram.api.ApiJson.jsonBody
import dosegram.core.application.imports.ImportBatch
import dosegram.core.application.imports.ImportRefusal
import dosegram.core.application.imports.ImportService
import dosegram.core.domain.imports.CsvTemplate
import dosegram.core.domain.imports.DeviceRecord
import dosegram.core.domain.imports.ImportBounds
import dosegram.core.domain.imports.ImportSource
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ImportJob
import dosegram.core.ports.ImportStatus
import dosegram.core.ports.UnitOfWork
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.server.model.EndpointExtensions.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---------- documents (MINI_APP section 15.1, S-14) ----------

enum ImportSourceWire(val wire: String, val source: ImportSource):
  case DosegramCsv extends ImportSourceWire("dosegram_csv", ImportSource.DosegramCsv)
  case AppleHealth extends ImportSourceWire("apple_health", ImportSource.AppleHealth)

object ImportSourceWire:
  given ReadWriter[ImportSourceWire] = ApiJson.wireEnum(values, _.wire)
  given Schema[ImportSourceWire] = Schema.derivedEnumeration[ImportSourceWire](encode = Some(_.wire))
  def of(source: ImportSource): ImportSourceWire = values.find(_.source == source).get

enum ImportState(val wire: String):
  case Open extends ImportState("open")
  case Committed extends ImportState("committed")
  case Cancelled extends ImportState("cancelled")
  case Undone extends ImportState("undone")

object ImportState:
  given ReadWriter[ImportState] = ApiJson.wireEnum(values, _.wire)
  given Schema[ImportState] = Schema.derivedEnumeration[ImportState](encode = Some(_.wire))

/** The type of a device record: a drink, a night or a nap. */
enum DeviceRecordType(val wire: String):
  case Water extends DeviceRecordType("water")
  case SleepMain extends DeviceRecordType("sleep_main")
  case SleepNap extends DeviceRecordType("sleep_nap")

object DeviceRecordType:
  given ReadWriter[DeviceRecordType] = ApiJson.wireEnum(values, _.wire)
  given Schema[DeviceRecordType] = Schema.derivedEnumeration[DeviceRecordType](encode = Some(_.wire))

final case class ImportInput(source: ImportSourceWire) derives ReadWriter, Schema

/** A record of a health export as the Mini App normalised it: a drink with `at` and `amountMl`, or a night or a nap
  * with `onset` and `wake`. The raw file never reaches the server (PRODUCT_SPEC R-IMX-7).
  */
final case class DeviceRecordInput(
    `type`: DeviceRecordType,
    at: Option[Instant] = None,
    amountMl: Option[Int] = None,
    onset: Option[Instant] = None,
    wake: Option[Instant] = None
) derives ReadWriter,
      Schema

/** One batch: the rows of a Dosegram CSV file with its header (`firstLine` is the file line of the first row), or
  * device records with the number of records the device ignored (`firstIndex` numbers them for the error list).
  */
final case class ImportBatchInput(
    csv: Option[String] = None,
    firstLine: Option[Int] = None,
    records: Option[List[DeviceRecordInput]] = None,
    ignored: Option[Int] = None,
    firstIndex: Option[Int] = None
) derives ReadWriter,
      Schema

/** The records of one domain: new, duplicates, conflicts (a night overlapping another) and, once committed, imported.
  */
final case class ImportDomainView(domain: String, fresh: Int, duplicates: Int, conflicts: Int, imported: Int)
    derives ReadWriter,
      Schema

/** A refused row: its line in the file, or its number among the device records, the column and a code. */
final case class ImportErrorView(line: Int, field: String, code: String) derives ReadWriter, Schema

/** An import and its preview (PRODUCT_SPEC R-IMX-8): counts per domain, the date range, and the rows not imported. */
final case class ImportView(
    id: UUID,
    source: ImportSourceWire,
    state: ImportState,
    createdAt: Instant,
    committedAt: Option[Instant],
    closedAt: Option[Instant],
    domains: List[ImportDomainView],
    invalid: Int,
    skipped: Int,
    ignored: Int,
    withoutConsent: Int,
    noOffset: Int,
    errors: List[ImportErrorView],
    from: Option[LocalDate],
    to: Option[LocalDate]
) derives ReadWriter,
      Schema

final case class ImportList(items: List[ImportView]) derives ReadWriter, Schema

// ---------- use cases ----------

/** The import routes (ARCHITECTURE section 9.5; ROADMAP G10.5, G10.7). Starting, adding a batch and committing need the
  * `import` consent, which only the chat records; removing an import needs none.
  */
final class Imports(uow: UnitOfWork, service: ImportService):
  import Imports.*

  def start(accountId: UUID, input: ImportInput): Either[ApiFailure, ImportView] =
    uow.transactionAs(accountId)(service.start(_, accountId, input.source.source)).left.map(failureOf).map(viewOf)

  def list(accountId: UUID): ImportList = ImportList(
    uow.transactionAs(accountId)(service.recent(_, accountId)).map(viewOf)
  )

  def get(accountId: UUID, id: UUID): Either[ApiFailure, ImportView] =
    uow.transactionAs(accountId)(service.find(_, accountId, id)).left.map(failureOf).map(viewOf)

  def addBatch(accountId: UUID, id: UUID, input: ImportBatchInput): Either[ApiFailure, ImportView] =
    batchOf(input)
      .flatMap(batch => uow.transactionAs(accountId)(service.addBatch(_, accountId, id, batch)).left.map(failureOf))
      .map(viewOf)

  def commit(accountId: UUID, id: UUID): Either[ApiFailure, ImportView] =
    uow.transactionAs(accountId)(service.commit(_, accountId, id)).left.map(failureOf).map(viewOf)

  def remove(accountId: UUID, id: UUID): Either[ApiFailure, ImportView] =
    uow.transactionAs(accountId)(service.remove(_, accountId, id)).left.map(failureOf).map(viewOf)

  /** Cancels the imports left open for a day; the caller runs it on a period with the other sweeps. */
  def sweep(): Int = uow.transaction(service.sweep)
end Imports

object Imports:

  /** ARCHITECTURE section 9.4: a normalised import batch is at most 2 MB. */
  val MaxBatchBytes: Long = 2L * 1024L * 1024L

  /** The template file: a header row, no record. Nothing of an account is in it, so the route needs no session. */
  def template(kind: String): Either[ApiFailure, (String, String, Array[Byte])] =
    CsvTemplate
      .ofKind(kind)
      .map(t => (s"attachment; filename=\"${t.fileName}\"", CsvTemplate.Mime, t.content.getBytes(UTF_8)))
      .toRight(ApiFailure.notFound())

  private def batchOf(input: ImportBatchInput): Either[ApiFailure, ImportBatch] =
    (input.csv, input.records) match
      case (Some(csv), None)     => Right(ImportBatch.CsvRows(csv, input.firstLine.getOrElse(2).max(2)))
      case (None, Some(records)) =>
        if records.size > ImportBounds.MaxBatchRecords then Left(ApiFailure.payloadTooLarge())
        else
          records.zipWithIndex
            .foldRight[Either[ApiFailure, List[DeviceRecord]]](Right(Nil)): (pair, rest) =>
              rest.flatMap(list => deviceRecordOf(pair._1, pair._2).map(_ :: list))
            .map(list =>
              ImportBatch.DeviceRecords(list, input.ignored.getOrElse(0).max(0), input.firstIndex.getOrElse(1))
            )
      case _ => Left(ApiFailure.invalid("csv", "one_of_csv_or_records"))

  private def deviceRecordOf(input: DeviceRecordInput, index: Int): Either[ApiFailure, DeviceRecord] =
    def missing(field: String) = ApiFailure.invalid(s"records[$index].$field", "required")
    input.`type` match
      case DeviceRecordType.Water =>
        for
          at <- input.at.toRight(missing("at"))
          millilitres <- input.amountMl.toRight(missing("amountMl"))
        yield DeviceRecord.Water(at, millilitres)
      case DeviceRecordType.SleepMain | DeviceRecordType.SleepNap =>
        val kind = if input.`type` == DeviceRecordType.SleepMain then SleepKind.Main else SleepKind.Nap
        for
          onset <- input.onset.toRight(missing("onset"))
          wake <- input.wake.toRight(missing("wake"))
        yield DeviceRecord.Sleep(onset, wake, kind)

  private def failureOf(refusal: ImportRefusal): ApiFailure =
    refusal match
      case ImportRefusal.ConsentMissing                          => ApiFailure.consentRequired(ConsentPurpose.Import)
      case ImportRefusal.UnknownAccount | ImportRefusal.NotFound => ApiFailure.notFound()
      case ImportRefusal.NotOpen | ImportRefusal.AlreadyClosed   => ApiFailure.versionConflict()
      case ImportRefusal.WrongSource                             => ApiFailure.invalid("source", "other_source")
      case ImportRefusal.Unreadable                              => ApiFailure.invalid("csv", "unreadable")
      case ImportRefusal.UnknownColumns                          => ApiFailure.invalid("csv", "unknown_columns")
      case ImportRefusal.TooManyRecords                          => ApiFailure.quotaExceeded()

  def viewOf(job: ImportJob): ImportView =
    val counts = job.counts
    ImportView(
      id = job.id,
      source = ImportSourceWire.of(job.source),
      state = job.status match
        case ImportStatus.Open      => ImportState.Open
        case ImportStatus.Committed => ImportState.Committed
        case ImportStatus.Cancelled => ImportState.Cancelled
        case ImportStatus.Undone    => ImportState.Undone,
      createdAt = job.createdAt,
      committedAt = job.committedAt,
      closedAt = job.closedAt,
      domains = counts.domains.toList
        .sortBy(_._1.ordinal)
        .map: (domain, tally) =>
          ImportDomainView(domain.code, tally.fresh, tally.duplicate, tally.conflict, tally.imported),
      invalid = counts.invalid,
      skipped = counts.skipped,
      ignored = counts.ignored,
      withoutConsent = counts.withoutConsent,
      noOffset = counts.noOffset,
      errors = counts.errors.map(e => ImportErrorView(e.line, e.field, e.code)),
      from = counts.from,
      to = counts.to
    )
end Imports

// ---------- endpoints ----------

/** The Tapir definitions of the import routes, mounted under `/api/v1` like the others. */
object ImportEndpoints:

  private val id = path[UUID]("id")
  private val idempotencyKey =
    header[Option[String]]("Idempotency-Key").description("A UUID per user action; required (API-16)")

  val getImportTemplate =
    Endpoints.v1.get
      .in("import-templates" / path[String]("kind"))
      .out(header[String]("Content-Disposition"))
      .out(header[String]("Content-Type"))
      .out(header("Access-Control-Allow-Origin", Exports.AllowedOrigin))
      .out(byteArrayBody)
      .name("getImportTemplate")
      .description(
        "The empty Dosegram CSV template of a domain (water, mood, sleep, meals): a header row only. It holds no " +
          "record, so downloadFile fetches it without a session."
      )

  val createImport =
    Endpoints.secured.post
      .in("imports")
      .in(idempotencyKey)
      .in(jsonBody[ImportInput])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[ImportView])
      .name("createImport")
      .description("Starts an import from one source. Needs the import consent, which is given in the chat.")

  val listImports = Endpoints.secured.get.in("imports").out(jsonBody[ImportList]).name("listImports")

  val getImport = Endpoints.secured.get.in("imports" / id).out(jsonBody[ImportView]).name("getImport")

  val addImportBatch =
    Endpoints.secured.post
      .in("imports" / id / "batches")
      .in(idempotencyKey)
      .in(jsonBody[ImportBatchInput])
      .maxRequestBodyLength(Imports.MaxBatchBytes)
      .out(jsonBody[ImportView])
      .name("addImportBatch")
      .description(
        "Reads one batch of at most 2 MB and 5000 rows into the preview: CSV rows with their header, or device " +
          "records. Nothing is stored as an entry before the commit."
      )

  val commitImport =
    Endpoints.secured.post
      .in("imports" / id / "commit")
      .in(idempotencyKey)
      .out(jsonBody[ImportView])
      .name("commitImport")
      .description("Writes the new records of the preview as imported entries.")

  val deleteImport =
    Endpoints.secured.delete
      .in("imports" / id)
      .out(jsonBody[ImportView])
      .name("deleteImport")
      .description("Cancels an open import, or undoes a committed one: exactly its entries are removed.")

  val documented: List[AnyEndpoint] =
    List(getImportTemplate, createImport, listImports, getImport, addImportBatch, commitImport, deleteImport)
end ImportEndpoints
