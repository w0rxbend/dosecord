package dosegram.api

import dosegram.contracts.Weekday
import dosegram.core.domain.DstKind
import dosegram.core.domain.OccurrenceCandidate
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.StoredMedication
import dosegram.core.ports.StoredOccurrence
import dosegram.core.ports.StoredSchedule
import dosegram.core.ports.StoredScheduleRevision
import dosegram.core.ports.Tx

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The sentences the API writes (MINI_APP section 15.1): the client composes none of them. They record what was
  * configured and say nothing about health.
  */
object ApiCopy:
  private val DayLabel = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
  private val Clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
  private val Workdays = List(Weekday.Mon, Weekday.Tue, Weekday.Wed, Weekday.Thu, Weekday.Fri)

  val noSchedule = "No schedule"
  val asNeeded = "as needed"
  def paused(summary: String): String = s"paused: $summary"
  def ended(summary: String): String = s"ended: $summary"

  def dayLabel(date: LocalDate): String = date.format(DayLabel)
  def clock(at: Instant, zone: ZoneId): String = Clock.format(at.atZone(zone))

  /** `every day 09:00, 21:00`, in the words of the chat wizard. */
  def ruleSummary(rule: Rule): String = rule match
    case Rule.FixedTimes(groups) =>
      val days = groups.flatMap(_.days).distinct
      val times = groups.flatMap(_.times).map(_.value).distinct.sorted
      val cadence =
        if days.size == Weekday.values.length then WizardCopy.cadenceEveryDay
        else if days.sortBy(_.ordinal) == Workdays then WizardCopy.cadenceWeekdays
        else WizardCopy.cadenceDays(days.sortBy(_.ordinal).map(_.toString))
      s"$cadence ${times.mkString(", ")}"
    case _: Rule.AsNeeded => asNeeded
    case _: Rule.EveryNDays | _: Rule.EveryNWeeks | _: Rule.Cycle | _: Rule.IntervalFixedStart | _: Rule.Chain |
        _: Rule.Taper | Rule.Paused =>
      Rule.kindTag(rule)

  def doseText(amount: Option[BigDecimal], unit: Option[String]): Option[String] =
    val number = amount.map(_.bigDecimal.stripTrailingZeros.toPlainString)
    Option.when(number.isDefined || unit.isDefined)(List(number, unit).flatten.mkString(" "))

  def clockChangeNote(candidate: OccurrenceCandidate, zone: ZoneId): Option[String] = candidate.dstKind match
    case DstKind.None => None
    case DstKind.Gap  =>
      val actual = clock(candidate.scheduledFor, zone)
      Some(s"Clock change: ${candidate.localTime.value} does not exist on this day. The reminder comes at $actual.")
    case DstKind.Fold =>
      Some(s"Clock change: ${candidate.localTime.value} happens twice on this day. The reminder comes the first time.")

/** Stored rows to API documents. Every function takes rows the caller has already authorised. */
object Rendering:

  /** `updated_at` in microseconds: the version behind `ETag` until the row has a version column (API-17). */
  def versionOf(medication: StoredMedication): Long =
    ChronoUnit.MICROS.between(Instant.EPOCH, medication.updatedAt)

  def instantOf(version: Long): Instant = Instant.EPOCH.plus(version, ChronoUnit.MICROS)

  def etag(version: Long): String = s"\"v$version\""

  /** The state a list shows. The chat pauses a schedule without touching the medication row, so `paused` is read from
    * the schedules: a medication is paused when it has schedules that have not ended and every one of them is paused.
    */
  def medicationState(tx: Tx, stored: StoredMedication): MedicationState =
    val live = tx.schedules.listForMedication(stored.id).filterNot(_.status == ScheduleStatus.Archived)
    if stored.status == MedicationStatus.Archived then MedicationState.Archived
    else if live.nonEmpty && live.forall(_.status == ScheduleStatus.Paused) then MedicationState.Paused
    else MedicationState.Active

  def scheduleState(status: ScheduleStatus): ScheduleState = status match
    case ScheduleStatus.Active   => ScheduleState.Active
    case ScheduleStatus.Paused   => ScheduleState.Paused
    case ScheduleStatus.Archived => ScheduleState.Ended

  /** The revision whose rule describes the schedule: a pause is a `Paused` revision, so a paused schedule is described
    * by the revision before it, the one a resume restores (`ScheduleLifecycle.resumeInTx`).
    */
  def describingRevision(tx: Tx, schedule: StoredSchedule): StoredScheduleRevision =
    val latest = tx.revisions
      .latest(schedule.id)
      .getOrElse(throw IllegalStateException(s"schedule ${schedule.id} has no revisions"))
    latest.rule match
      case Rule.Paused =>
        tx.revisions
          .get(schedule.id, latest.revision - 1)
          .getOrElse(throw IllegalStateException(s"schedule ${schedule.id} has no rule before its pause"))
      case _ => latest

  def schedule(tx: Tx, stored: StoredSchedule): Schedule =
    val rule = describingRevision(tx, stored).rule
    val view = ScheduleRules.view(rule)
    Schedule(
      id = stored.id,
      medicationId = stored.medicationId,
      status = scheduleState(stored.status),
      revision = stored.currentRevision,
      pattern = view.pattern,
      times = view.times,
      weekdays = view.weekdays,
      timeZone = stored.tz.getId,
      startDate = stored.startDate,
      summary = scheduleSummary(stored.status, rule),
      maxPerDay = view.maxPerDay,
      minGapMinutes = view.minGapMinutes,
      endDate = stored.endDate
    )

  private def scheduleSummary(status: ScheduleStatus, rule: Rule): String =
    val summary = ApiCopy.ruleSummary(rule)
    status match
      case ScheduleStatus.Active   => summary
      case ScheduleStatus.Paused   => ApiCopy.paused(summary)
      case ScheduleStatus.Archived => ApiCopy.ended(summary)

  def medication(tx: Tx, stored: StoredMedication): Medication =
    Medication(
      id = stored.id,
      name = stored.name,
      status = medicationState(tx, stored),
      version = versionOf(stored),
      createdAt = stored.createdAt,
      schedules = tx.schedules.listForMedication(stored.id).map(schedule(tx, _)),
      doseAmount = stored.doseAmount.map(_.bigDecimal.stripTrailingZeros.toPlainString),
      doseUnit = stored.doseUnit,
      instructions = stored.instructions
    )

  def medicationSummary(tx: Tx, stored: StoredMedication): MedicationSummary =
    val live = tx.schedules.listForMedication(stored.id).filterNot(_.status == ScheduleStatus.Archived)
    val summaries = live.map(row => scheduleSummary(row.status, describingRevision(tx, row).rule))
    MedicationSummary(
      id = stored.id,
      name = stored.name,
      status = medicationState(tx, stored),
      version = versionOf(stored),
      scheduleSummary = if summaries.isEmpty then ApiCopy.noSchedule else summaries.mkString("; "),
      doseText = ApiCopy.doseText(stored.doseAmount, stored.doseUnit)
    )

  def doseState(row: StoredOccurrence): DoseState = row.status match
    case OccurrenceStatus.Pending => DoseState.Pending
    case OccurrenceStatus.Due     => DoseState.Due
    case OccurrenceStatus.Snoozed => DoseState.Snoozed
    case OccurrenceStatus.Taken   =>
      // The engine's own rule (Decide: `taken_late` past `due_window_end`).
      if row.state.effectiveAt.exists(_.isAfter(row.state.dueWindowEnd)) then DoseState.TakenLate else DoseState.Taken
    case OccurrenceStatus.Skipped   => DoseState.Skipped
    case OccurrenceStatus.Missed    => DoseState.Missed
    case OccurrenceStatus.Unknown   => DoseState.Unknown
    case OccurrenceStatus.Cancelled =>
      throw IllegalStateException(s"cancelled occurrence ${row.id} has no place in a day view")

  def occurrence(tx: Tx, row: StoredOccurrence): DoseOccurrence =
    val snapshot = row.doseSnapshot
    val name = snapshot.map(_.medicationName).orElse(tx.medications.get(row.medicationId).map(_.name)).getOrElse("")
    DoseOccurrence(
      id = row.id,
      medicationId = row.medicationId,
      medicationName = name,
      scheduledTime = ApiCopy.clock(row.scheduledFor, row.tz),
      scheduledAt = row.scheduledFor,
      state = doseState(row),
      doseText = snapshot.flatMap(dose => ApiCopy.doseText(dose.doseAmount, dose.doseUnit)),
      resolvedTime = row.state.effectiveAt.orElse(row.state.skippedAt).map(ApiCopy.clock(_, row.tz)),
      snoozedUntil =
        row.state.snoozedUntil.filter(_ => row.status == OccurrenceStatus.Snoozed).map(ApiCopy.clock(_, row.tz))
    )
