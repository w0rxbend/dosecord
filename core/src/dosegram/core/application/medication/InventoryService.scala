package dosegram.core.application.medication

import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.ScheduleRevision
import dosegram.core.domain.inventory.Inventory
import dosegram.core.domain.inventory.Supply
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.StoredSchedule
import dosegram.core.ports.Tx

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** A count with how far it reaches on the user's schedule (PRODUCT_SPEC R-REG-13). */
final case class InventoryView(inventory: Inventory, supply: Supply, belowThreshold: Boolean)

/** The read side of the inventory (ROADMAP G9.10): the forward walk of the medication's schedules from now. An active
  * schedule counts from now; a paused one only from its resume date; one paused without a date and an ended one count
  * nothing.
  */
object InventoryService:

  def view(tx: Tx, accountId: UUID, medicationId: UUID, now: Instant): Option[InventoryView] =
    tx.medicationDepth.inventory.get(accountId, medicationId).map(viewOf(tx, _, now))

  def viewOf(tx: Tx, inventory: Inventory, now: Instant): InventoryView =
    val zone = ZoneId.of(tx.accounts.timezoneOf(inventory.accountId).getOrElse("UTC"))
    val schedules = tx.schedules.listForMedication(inventory.medicationId)
    val supply = Supply.of(
      inventory.onHand,
      inventory.perIntake,
      schedules.flatMap(revisionAhead(tx, _)),
      schedules.map(openEarlierToday(tx, _, now, zone)).sum,
      now,
      zone
    )
    InventoryView(inventory, supply, Supply.belowThreshold(inventory, supply))

  private def revisionAhead(tx: Tx, schedule: StoredSchedule): Option[ScheduleRevision] =
    val from = schedule.status match
      case ScheduleStatus.Active => Some(schedule.startDate)
      case ScheduleStatus.Paused =>
        tx.medicationDepth.pauses.resumeOn(schedule.id).map(Ordering[java.time.LocalDate].max(_, schedule.startDate))
      case ScheduleStatus.Archived => None
    for
      start <- from
      rule <- describingRule(tx, schedule.id)
    yield ScheduleRevision(
      rule,
      schedule.tz,
      effectiveFrom = start,
      effectiveUntil = schedule.endDate.map(_.plusDays(1))
    )

  /** The rule the user set: a pause is a `Paused` revision on top of it. */
  private def describingRule(tx: Tx, scheduleId: UUID): Option[Rule] =
    tx.revisions.list(scheduleId).filterNot(_.rule == Rule.Paused).maxByOption(_.revision).map(_.rule)

  private val Open = Set(OccurrenceStatus.Pending, OccurrenceStatus.Due, OccurrenceStatus.Snoozed)

  private def openEarlierToday(tx: Tx, schedule: StoredSchedule, now: Instant, zone: ZoneId): Int =
    if schedule.status != ScheduleStatus.Active then 0
    else
      val today = now.atZone(zone).toLocalDate
      tx.occurrences
        .listBySchedule(schedule.id)
        .count(o => Open(o.status) && o.scheduledFor.isBefore(now) && o.scheduledFor.atZone(zone).toLocalDate == today)
end InventoryService
