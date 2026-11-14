package dosegram.core.application.medication

import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.copy.MedicationDepthCopy
import dosegram.core.ports.OccurrenceOrigin
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.Tx

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The limits a user entered for an as-needed medication, and what the chat says about them (supervisor decision,
  * 2026-11-14, owner asleep; PRODUCT_SPEC R-INT-9 amended): the values are stated as the user's own, never computed,
  * and a log above them asks once and is never refused.
  */
final case class AsNeededLimits(maxPerDay: Option[Int], minGapMinutes: Option[Int])

object AsNeededLimits:

  /** The as-needed rule both surfaces write when the user enters no limit (the chat wizard and the API default): its
    * values are not the user's and are never shown.
    */
  val NotSet: Rule.AsNeeded = Rule.AsNeeded(maxPerDay = 10, minGapMinutes = 0)

  def of(rule: Rule): Option[AsNeededLimits] = rule match
    case r: Rule.AsNeeded if r != NotSet =>
      Some(
        AsNeededLimits(
          Option.when(r.maxPerDay != NotSet.maxPerDay)(r.maxPerDay),
          Option.when(r.minGapMinutes > 0)(r.minGapMinutes)
        )
      )
    case _ => None

  /** The limits of the medication's live as-needed schedule, if the user set any. */
  def forMedication(tx: Tx, medicationId: UUID): Option[AsNeededLimits] =
    tx.schedules
      .listForMedication(medicationId)
      .filter(_.status == ScheduleStatus.Active)
      .flatMap(schedule => tx.revisions.latest(schedule.id))
      .flatMap(revision => of(revision.rule))
      .headOption

  /** The effective times of today's as-needed logs of the medication that still count (not undone). */
  def loggedToday(tx: Tx, accountId: UUID, medicationId: UUID, zone: ZoneId, now: Instant): List[Instant] =
    val today = now.atZone(zone).toLocalDate
    tx.occurrences
      .listForAccountBetween(accountId, today, today)
      .filter(o => o.medicationId == medicationId && o.origin == OccurrenceOrigin.Manual)
      .filter(_.status == OccurrenceStatus.Taken)
      .flatMap(o => o.state.effectiveAt.orElse(Some(o.state.scheduledFor)))

  /** The facts line of a log's outcome, when the user set a limit. */
  def facts(tx: Tx, accountId: UUID, medicationId: UUID, zone: ZoneId, now: Instant): Option[String] =
    forMedication(tx, medicationId).map: limits =>
      MedicationDepthCopy.prnFacts(
        limits.maxPerDay,
        limits.minGapMinutes,
        loggedToday(tx, accountId, medicationId, zone, now).size
      )

  /** The facts before a log that would go past the user's own limit: today's count at the maximum, or the last log
    * closer than the gap. `None` logs at once.
    */
  def aboveLimit(tx: Tx, accountId: UUID, medicationId: UUID, zone: ZoneId, now: Instant): Option[String] =
    forMedication(tx, medicationId).flatMap: limits =>
      val logged = loggedToday(tx, accountId, medicationId, zone, now)
      val atMax = limits.maxPerDay.exists(logged.size >= _)
      val tooSoon = limits.minGapMinutes.exists: gap =>
        logged.maxOption.exists(last => Duration.between(last, now).toMinutes < gap)
      Option.when(atMax || tooSoon)(MedicationDepthCopy.prnFacts(limits.maxPerDay, limits.minGapMinutes, logged.size))
end AsNeededLimits
