package dosegram.api

import dosegram.contracts.Weekday
import dosegram.core.domain.DstKind
import dosegram.core.domain.OccurrenceCandidate
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.copy.MedicationDepthCopy
import dosegram.core.domain.copy.MoodLabels
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.MoodDay
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.StoredMedication
import dosegram.core.ports.StoredOccurrence
import dosegram.core.ports.StoredSchedule
import dosegram.core.ports.StoredScheduleRevision
import dosegram.core.ports.Tx

import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The sentences the API writes (MINI_APP section 15.1): the client composes none of them. They record what was
  * configured and say nothing about health.
  */
object ApiCopy:
  private val DayLabel = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
  private val DayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
  private val Clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
  private val FullDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
  private val Weekday3 = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
  private val MonthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
  private val MonthOnly = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
  private val Workdays = List(Weekday.Mon, Weekday.Tue, Weekday.Wed, Weekday.Thu, Weekday.Fri)

  val noSchedule = "No schedule"
  val asNeeded = "as needed"
  def paused(summary: String): String = s"paused: $summary"
  def ended(summary: String): String = s"ended: $summary"

  def dayLabel(date: LocalDate): String = date.format(DayLabel)

  /** `13 Nov`: a date under a chart. */
  def dayMonth(date: LocalDate): String = date.format(DayMonth)

  /** `1 check-in`, `3 check-ins`. */
  def count(n: Int, noun: String): String = if n == 1 then s"1 $noun" else s"$n ${noun}s"

  /** `7 Nov to 13 Nov`: the days a statistics period covers. */
  def rangeLabel(window: DateRange): String = s"${window.start.format(DayMonth)} to ${window.end.format(DayMonth)}"

  val noDataInPeriod = "No data in this period"
  val noConsentedDomain = "Statistics appear here for the records you turned on in the chat."

  def insightTitle(domain: InsightDomain): String = domain match
    case InsightDomain.Medication => "Medication"
    case InsightDomain.Hydration  => "Water"
    case InsightDomain.Mood       => "Mood"
    case InsightDomain.Sleep      => "Sleep"
  def overviewTitle(domain: OverviewDomain): String = domain match
    case OverviewDomain.Medication => "Medication"
    case OverviewDomain.Hydration  => "Water"
    case OverviewDomain.Mood       => "Mood"
    case OverviewDomain.Sleep      => "Sleep"
    case OverviewDomain.Nutrition  => "Food"
  def clock(at: Instant, zone: ZoneId): String = Clock.format(at.atZone(zone))

  // ---------- tracking ----------

  val nothingLogged = "Nothing logged yet"
  val noCheckIn = "No check-in yet"

  /** MINI_APP MA-MOOD-7: shown on every mood screen, for every value. */
  val moodStatement = "Dosegram records how you feel. It does not assess it."

  def millilitres(amount: Int): String = s"$amount ml"

  /** `Thursday 12 November`: the date in the accessible name of a calendar cell (MINI_APP MA-HIST-1). */
  def fullDay(date: LocalDate): String = date.format(FullDay)

  /** `Thu`: a day of the mood strip. */
  def weekday(date: LocalDate): String = date.format(Weekday3)

  def monthLabel(month: YearMonth): String = month.format(MonthYear)
  def monthName(month: YearMonth): String = month.format(MonthOnly)

  /** The daily mean with the name of its level: `3.5 (slightly pleasant)`. No surface shows a bare mood number. */
  def moodMean(day: MoodDay): String =
    val mean = java.math.BigDecimal.valueOf(day.mean).setScale(1, RoundingMode.HALF_UP).toPlainString
    s"$mean (${MoodLabels.level(day.level).toLowerCase(Locale.ROOT)})"

  // ---------- consents and settings ----------

  /** Research brief 06 fact F2, shown with the consents (MINI_APP section 7.15). */
  val chatIsNotEncrypted =
    "Telegram can read the messages and images in your chat with Dosegram. Bot chats are not end-to-end encrypted."
  val withdrawInChat = "To withdraw a consent, send /privacy in the chat. The app records no consent of its own."

  /** How a purpose is turned on: in the chat only (ARCHITECTURE API-19). */
  def consentOff(purpose: ConsentPurpose): String = purpose match
    case ConsentPurpose.Medication          => "Off. Send /meds in the chat to turn it on."
    case ConsentPurpose.Hydration           => "Off. Send /water in the chat to turn it on."
    case ConsentPurpose.Mood                => "Off. Send /mood in the chat to turn it on."
    case ConsentPurpose.Sleep               => "Off. The chat cannot turn sleep records on yet."
    case ConsentPurpose.CrossDomainPatterns => "Off. The chat asks for it when a comparison is first requested."
    case ConsentPurpose.ImagesInChat        => "Off. The chat asks for it when an image is first requested."
    case ConsentPurpose.Nutrition           => "Off. Send /food in the chat to turn it on."

  val quietHoursNote =
    "A medication reminder that falls inside quiet hours follows the quiet-hours rule of its schedule."
  val discreetNote =
    "This choice is saved with your account. Reminders in the chat do not use it yet: they still show the name."

  def timeThere(time: String): String = s"It is $time there now."
  val noSchedulesMove = "No schedule is changed by this."
  def schedulesFollow(count: Int): String =
    s"${ApiCopy.count(count, "schedule")} ${if count == 1 then "follows" else "follow"} your time zone and " +
      s"${if count == 1 then "keeps its" else "keep their"} clock times."
  def scheduleStays(medicationName: String, zone: ZoneId): String = s"$medicationName stays on ${zone.getId} time."

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
    case _: Rule.AsNeeded                          => asNeeded
    case Rule.EveryNDays(everyDays, times, anchor) =>
      s"${MedicationDepthCopy.everyNDays(everyDays, anchor)} ${times.map(_.value).sorted.mkString(", ")}"
    case Rule.Cycle(onDays, offDays, times, anchor) =>
      s"${MedicationDepthCopy.cycle(onDays, offDays, anchor)} ${times.map(_.value).sorted.mkString(", ")}"
    case _: Rule.EveryNWeeks | _: Rule.IntervalFixedStart | _: Rule.Chain | _: Rule.Taper | Rule.Paused =>
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
      endDate = stored.endDate,
      everyDays = view.everyDays,
      onDays = view.onDays,
      offDays = view.offDays,
      anchorDate = view.anchorDate,
      resumeOn = tx.medicationDepth.pauses.resumeOn(stored.id)
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
