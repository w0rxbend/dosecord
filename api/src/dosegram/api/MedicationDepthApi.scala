package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.jsonBody
import dosegram.core.application.medication.InventoryService
import dosegram.core.application.medication.InventoryView
import dosegram.core.application.medication.MedicationDepthChat
import dosegram.core.domain.copy.MedicationDepthCopy
import dosegram.core.domain.inventory.RefillThreshold
import dosegram.core.ports.Clock
import dosegram.core.ports.InventoryRefusal
import dosegram.core.ports.InventorySettings
import dosegram.core.ports.StoredMedication
import dosegram.core.ports.UnitOfWork
import sttp.model.StatusCode
import sttp.tapir.*

import java.time.LocalDate
import java.util.UUID
import scala.util.matching.Regex

/** A count as the inventory panel shows it (PRODUCT_SPEC R-REG-11 to R-REG-16; ROADMAP G9.10). Decimals are strings
  * (MINI_APP 15.1). `daysOfSupply` and `coversUntil` come from the forward walk of the schedule and are absent for an
  * as needed medication; `summary` is the same facts as one sentence.
  */
final case class InventoryDocument(
    medicationId: UUID,
    onHand: String,
    perIntake: String,
    thresholdDays: Option[Int],
    thresholdUnits: Option[String],
    lastRefill: Option[String],
    daysOfSupply: Option[Int],
    coversUntil: Option[LocalDate],
    belowThreshold: Boolean,
    countMayBeOff: Boolean,
    noticesStopped: Boolean,
    version: Long,
    summary: String
) derives ReadWriter,
      Schema

/** The read of a medication's count: absent while the user keeps none, so the panel's first read is not an error. */
final case class InventoryReading(count: Option[InventoryDocument] = None) derives ReadWriter, Schema

/** What the user enters: the units on hand, the units of one intake, and at most one threshold (none: 7 days). */
final case class InventoryInput(
    onHand: String,
    perIntake: String,
    thresholdDays: Option[Int] = None,
    thresholdUnits: Option[String] = None
) derives ReadWriter,
      Schema

/** A refill adds `quantity` units (R-REG-15). */
final case class RefillInput(quantity: String) derives ReadWriter, Schema

/** The inventory routes of ARCHITECTURE section 9.5 (V1). Each is scoped to the session's account. */
object MedicationDepthEndpoints:
  private val id = path[UUID]("id")
  private val etag = header[String]("ETag").description("\"v<version>\" of the representation (API-17)")
  private val ifMatch =
    header[Option[String]]("If-Match").description("The ETag the edit is based on; absent only for a first count")
  private val idempotencyKey =
    header[Option[String]]("Idempotency-Key").description("A UUID per user action; required (API-16)")

  val getInventory =
    Endpoints.secured.get
      .in("medications" / id / "inventory")
      .out(etag)
      .out(jsonBody[InventoryReading])
      .name("getInventory")
      .description("The count of the medication; `count` is absent while the user keeps none (ETag \"v0\").")

  val saveInventory =
    Endpoints.secured.put
      .in("medications" / id / "inventory")
      .in(ifMatch)
      .in(idempotencyKey)
      .in(jsonBody[InventoryInput])
      .out(etag)
      .out(jsonBody[InventoryDocument])
      .name("saveInventory")
      .description(
        "Creates the count (no If-Match) or replaces it on the version read; a repeated key changes nothing."
      )

  val createRefill =
    Endpoints.secured.post
      .in("medications" / id / "refills")
      .in(idempotencyKey)
      .in(jsonBody[RefillInput])
      .out(statusCode(StatusCode.Created))
      .out(etag)
      .out(jsonBody[InventoryDocument])
      .name("createRefill")

  val documented: List[AnyEndpoint] = List(getInventory, saveInventory, createRefill)
end MedicationDepthEndpoints

/** The inventory use cases over HTTP (ROADMAP G9.10). */
final class MedicationDepthApi(uow: UnitOfWork, clock: Clock):
  import MedicationDepthApi.*

  def inventory(accountId: UUID, medicationId: UUID): Either[ApiFailure, InventoryReading] =
    uow.transaction: tx =>
      owned(tx.medications.get(medicationId), accountId).map: medication =>
        InventoryReading(InventoryService.view(tx, accountId, medicationId, clock.now()).map(documentOf(medication, _)))

  def save(
      accountId: UUID,
      medicationId: UUID,
      version: Option[Long],
      key: UUID,
      input: InventoryInput
  ): Either[ApiFailure, InventoryDocument] =
    validated(input).flatMap: (onHand, perIntake, threshold) =>
      uow.transaction: tx =>
        for
          medication <- owned(tx.medications.get(medicationId), accountId)
          settings = InventorySettings(medicationId, accountId, onHand, perIntake, threshold, s"api:$key")
          saved <- tx.medicationDepth.inventory
            .save(settings, version.map(_.toInt), clock.now())
            .left
            .map:
              case InventoryRefusal.NotFound if version.isEmpty =>
                ApiFailure.invalid("If-Match", "required")
              case InventoryRefusal.NotFound => ApiFailure.notFound()
              // API-17: a count exists, so a write without If-Match is not a first count.
              case InventoryRefusal.VersionConflict(_) if version.isEmpty => ApiFailure.preconditionRequired()
              case InventoryRefusal.VersionConflict(_)                    => ApiFailure.versionConflict()
        yield documentOf(medication, InventoryService.viewOf(tx, saved, clock.now()))

  def refill(
      accountId: UUID,
      medicationId: UUID,
      key: UUID,
      input: RefillInput
  ): Either[ApiFailure, InventoryDocument] =
    decimal(input.quantity)
      .filter(q => q > 0 && q <= MaxUnits)
      .toRight(ApiFailure.invalid("quantity", "invalid"))
      .flatMap: quantity =>
        uow.transaction: tx =>
          for
            medication <- owned(tx.medications.get(medicationId), accountId)
            refilled <- tx.medicationDepth.inventory
              .refill(accountId, medicationId, quantity, s"api:$key", clock.now())
              .toRight(ApiFailure.notFound())
          yield documentOf(medication, InventoryService.viewOf(tx, refilled, clock.now()))

  private def owned(medication: Option[StoredMedication], accountId: UUID): Either[ApiFailure, StoredMedication] =
    medication.filter(_.accountId == accountId).toRight(ApiFailure.notFound())
end MedicationDepthApi

object MedicationDepthApi:
  private val Decimal: Regex = """^\d{1,6}(\.\d{1,3})?$""".r
  val MaxUnits: BigDecimal = BigDecimal(100000)
  val MaxPerIntake: BigDecimal = BigDecimal(1000)

  private def decimal(text: String): Option[BigDecimal] =
    Option(text.trim).filter(Decimal.matches).map(BigDecimal(_))

  def validated(input: InventoryInput): Either[ApiFailure, (BigDecimal, BigDecimal, Option[RefillThreshold])] =
    val onHand = decimal(input.onHand).filter(_ <= MaxUnits)
    val perIntake = decimal(input.perIntake).filter(p => p > 0 && p <= MaxPerIntake)
    val units = input.thresholdUnits.map(text => decimal(text).filter(_ <= MaxUnits))
    val errors = List(
      Option.when(onHand.isEmpty)(FieldError("onHand", "invalid")),
      Option.when(perIntake.isEmpty)(FieldError("perIntake", "invalid")),
      Option.when(input.thresholdDays.exists(d => d < 1 || d > 365))(FieldError("thresholdDays", "out_of_range")),
      Option.when(units.exists(_.isEmpty))(FieldError("thresholdUnits", "invalid")),
      Option.when(input.thresholdDays.isDefined && input.thresholdUnits.isDefined)(
        FieldError("thresholdUnits", "not_allowed")
      )
    ).flatten
    if errors.nonEmpty then Left(ApiFailure.invalid(errors))
    else
      val threshold = input.thresholdDays
        .map(RefillThreshold.Days(_))
        .orElse(units.flatten.map(RefillThreshold.Units(_)))
        .getOrElse(RefillThreshold.Default)
      Right((onHand.get, perIntake.get, Some(threshold)))

  private def text(n: BigDecimal): String = n.bigDecimal.stripTrailingZeros.toPlainString

  def documentOf(medication: StoredMedication, view: InventoryView): InventoryDocument =
    val inventory = view.inventory
    val unit = MedicationDepthChat.countableUnit(medication)
    InventoryDocument(
      medicationId = medication.id,
      onHand = text(inventory.onHand),
      perIntake = text(inventory.perIntake),
      thresholdDays = inventory.threshold.collect { case RefillThreshold.Days(d) => d },
      thresholdUnits = inventory.threshold.collect { case RefillThreshold.Units(u) => text(u) },
      lastRefill = inventory.lastRefill.map(text),
      daysOfSupply = view.supply.daysOfSupply,
      coversUntil = view.supply.lastCovered,
      belowThreshold = view.belowThreshold,
      countMayBeOff = inventory.countOff,
      noticesStopped = inventory.noticesStopped,
      version = inventory.version.toLong,
      summary = MedicationDepthCopy.refillLow(medication.name, inventory.onHand, unit, view.supply.lastCovered)
    )
end MedicationDepthApi
