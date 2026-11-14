package dosegram.core.ports

import dosegram.core.domain.inventory.Inventory
import dosegram.core.domain.inventory.RefillThreshold

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What the user enters for a count (PRODUCT_SPEC R-REG-11): the units on hand, the units of one intake and the
  * threshold. A changed `onHand` is recorded as a `set` event under `key`.
  */
final case class InventorySettings(
    medicationId: UUID,
    accountId: UUID,
    onHand: BigDecimal,
    perIntake: BigDecimal,
    threshold: Option[RefillThreshold],
    key: String
)

/** Why a count write changed nothing. */
enum InventoryRefusal:
  case NotFound
  case VersionConflict(current: Inventory)

/** `medication_inventory` and `inventory_events` (V8). Taken doses change the count in the database, on every write
  * path, by the trigger of V8 (R-REG-12); these calls are the user's own entries and the notice bookkeeping.
  */
trait InventoryRepository:
  def get(accountId: UUID, medicationId: UUID): Option[Inventory]
  def listForAccount(accountId: UUID): List[Inventory]

  /** Creates the count or replaces it while it still carries `expectedVersion` (no version: create only). A new
    * `onHand` clears the "count may be off" mark and a stopped notice, and is a `set` event; a repeated `key` changes
    * nothing and answers the row as it is.
    */
  def save(settings: InventorySettings, expectedVersion: Option[Int], now: Instant): Either[InventoryRefusal, Inventory]

  /** Adds `quantity` (R-REG-15) once per `key`, remembers it as the last refill and clears a stopped notice. */
  def refill(accountId: UUID, medicationId: UUID, quantity: BigDecimal, key: String, now: Instant): Option[Inventory]

  /** Counts with a threshold whose notice is not stopped and was last sent before `sentBefore` (or never). */
  def noticeCandidates(sentBefore: Instant, limit: Int): List[Inventory]

  def markNotice(medicationId: UUID, at: Instant): Unit
  def stopNotices(accountId: UUID, medicationId: UUID, now: Instant): Boolean
  def clearCountOff(medicationId: UUID, now: Instant): Unit

/** A schedule paused until a date (R-REG-5). */
final case class PausedSchedule(scheduleId: UUID, accountId: UUID, resumeOn: LocalDate)

/** `medication_schedules.resume_on` and `end_date` (V8; R-REG-5, R-SCH-4). */
trait SchedulePauseRepository:
  def setResumeOn(scheduleId: UUID, resumeOn: Option[LocalDate], now: Instant): Unit
  def resumeOn(scheduleId: UUID): Option[LocalDate]

  /** Paused schedules whose resume date has begun in their own zone at `now` (`FOR UPDATE SKIP LOCKED`). */
  def dueResumes(now: Instant, limit: Int): List[PausedSchedule]

  def setEndDate(scheduleId: UUID, endDate: Option[LocalDate], now: Instant): Unit

/** The repositories of the medication-depth track on one transaction (ARCHITECTURE section 10.17 rule 6). */
trait MedicationDepthRepositories:
  def inventory: InventoryRepository
  def pauses: SchedulePauseRepository
