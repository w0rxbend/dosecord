package dosegram.api

import dosegram.contracts.Weekday
import dosegram.core.domain.DstKind
import dosegram.core.domain.OccurrenceCandidate
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.copy.DoseUnits
import dosegram.core.domain.copy.Formats
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.MedicationDepthCopy
import dosegram.core.domain.copy.MoodLabels
import dosegram.core.domain.copy.UkForms
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.domain.copy.decimal
import dosegram.core.domain.copy.tr
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
  private def DayLabel = DateTimeFormatter.ofPattern("EEE d MMM", Formats.locale)
  private def DayMonth = DateTimeFormatter.ofPattern("d MMM", Formats.locale)
  private val Clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
  private def FullDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Formats.locale)
  private def Weekday3 = DateTimeFormatter.ofPattern("EEE", Formats.locale)
  private def MonthYear = DateTimeFormatter.ofPattern("LLLL yyyy", Formats.locale)
  private def MonthOnly = DateTimeFormatter.ofPattern("LLLL", Formats.locale)
  private val Workdays = List(Weekday.Mon, Weekday.Tue, Weekday.Wed, Weekday.Thu, Weekday.Fri)

  def noSchedule: String = tr("No schedule", "Без розкладу")
  def asNeeded: String = tr("as needed", "за потреби")
  def paused(summary: String): String = tr(s"paused: $summary", s"призупинено: $summary")
  def ended(summary: String): String = tr(s"ended: $summary", s"завершено: $summary")

  def dayLabel(date: LocalDate): String = date.format(DayLabel)

  /** `13 Nov`: a date under a chart. */
  def dayMonth(date: LocalDate): String = date.format(DayMonth)

  /** `1 check-in`, `3 check-ins`. */
  def count(n: Int, noun: String): String =
    Lang.current match
      case Lang.En => if n == 1 then s"1 $noun" else s"$n ${noun}s"
      case Lang.Uk => UkrainianNouns.get(noun).fold(s"$n $noun")(_.count(n))

  /** The Ukrainian forms of the nouns this API counts (ROADMAP G2.22). */
  private val UkrainianNouns: Map[String, UkForms] = Map(
    "check-in" -> UkForms("позначка", "позначки", "позначок"),
    "day" -> UkForms("день", "дні", "днів"),
    "night" -> UkForms("ніч", "ночі", "ночей"),
    "schedule" -> UkForms("розклад", "розклади", "розкладів"),
    "scheduled dose" -> UkForms("запланована доза", "заплановані дози", "запланованих доз"),
    "meal" -> UkForms("прийом їжі", "прийоми їжі", "прийомів їжі"),
    "snack" -> UkForms("перекус", "перекуси", "перекусів")
  )

  /** `7 Nov to 13 Nov`: the days a statistics period covers. */
  def rangeLabel(window: DateRange): String = tr(
    s"${window.start.format(DayMonth)} to ${window.end.format(DayMonth)}",
    s"${window.start.format(DayMonth)} – ${window.end.format(DayMonth)}"
  )

  def noDataInPeriod: String = tr("No data in this period", "За цей період даних немає")
  def noConsentedDomain: String = tr(
    "Statistics appear here for the records you turned on in the chat.",
    "Тут з’являється статистика для записів, які ви ввімкнули в чаті."
  )

  def insightTitle(domain: InsightDomain): String = domain match
    case InsightDomain.Medication => tr("Medication", "Ліки")
    case InsightDomain.Hydration  => tr("Water", "Вода")
    case InsightDomain.Mood       => tr("Mood", "Настрій")
    case InsightDomain.Sleep      => tr("Sleep", "Сон")
  def overviewTitle(domain: OverviewDomain): String = domain match
    case OverviewDomain.Medication => tr("Medication", "Ліки")
    case OverviewDomain.Hydration  => tr("Water", "Вода")
    case OverviewDomain.Mood       => tr("Mood", "Настрій")
    case OverviewDomain.Sleep      => tr("Sleep", "Сон")
    case OverviewDomain.Nutrition  => tr("Food", "Їжа")
  def clock(at: Instant, zone: ZoneId): String = Clock.format(at.atZone(zone))

  // ---------- tracking ----------

  def nothingLogged: String = tr("Nothing logged yet", "Поки нічого не записано")
  def noCheckIn: String = tr("No check-in yet", "Позначок поки немає")

  /** MINI_APP MA-MOOD-7: shown on every mood screen, for every value. */
  def moodStatement: String = tr(
    "Dosegram records how you feel. It does not assess it.",
    "Dosegram записує, як ви почуваєтеся. Він цього не оцінює."
  )

  def millilitres(amount: Int): String = tr(s"$amount ml", s"$amount мл")

  /** `Thursday 12 November`: the date in the accessible name of a calendar cell (MINI_APP MA-HIST-1). */
  def fullDay(date: LocalDate): String = date.format(FullDay)

  /** `Thu`: a day of the mood strip. */
  def weekday(date: LocalDate): String = date.format(Weekday3)

  /** `November 2026`, `Листопад 2026`: a heading, so the Ukrainian standalone month starts with a capital. */
  def monthLabel(month: YearMonth): String = month.format(MonthYear).capitalize
  def monthName(month: YearMonth): String = month.format(MonthOnly)

  /** The daily mean with the name of its level: `3.5 (slightly pleasant)`. No surface shows a bare mood number. */
  def moodMean(day: MoodDay): String =
    val mean = decimal(java.math.BigDecimal.valueOf(day.mean).setScale(1, RoundingMode.HALF_UP).toPlainString)
    s"$mean (${MoodLabels.level(day.level).toLowerCase(Locale.ROOT)})"

  // ---------- consents and settings ----------

  /** Research brief 06 fact F2, shown with the consents (MINI_APP section 7.15). */
  def chatIsNotEncrypted: String =
    tr(
      "Telegram can read the messages and images in your chat with Dosegram. Bot chats are not end-to-end encrypted.",
      "Telegram може читати повідомлення й зображення у вашому чаті з Dosegram. Чати з ботами не мають наскрізного шифрування."
    )
  def withdrawInChat: String = tr(
    "To withdraw a consent, send /privacy in the chat. The app records no consent of its own.",
    "Щоб відкликати згоду, надішліть /privacy у чаті. Застосунок сам згод не записує."
  )

  /** `On since 14 November 2026.`: the line of a granted consent (MINI_APP section 7.15). */
  def consentOnSince(day: String): String = tr(s"On since $day.", s"Увімкнено з $day.")

  /** How a purpose is turned on: in the chat only (ARCHITECTURE API-19). */
  def consentOff(purpose: ConsentPurpose): String = purpose match
    case ConsentPurpose.Medication =>
      tr("Off. Send /meds in the chat to turn it on.", "Вимкнено. Надішліть /meds у чаті, щоб увімкнути.")
    case ConsentPurpose.Hydration =>
      tr("Off. Send /water in the chat to turn it on.", "Вимкнено. Надішліть /water у чаті, щоб увімкнути.")
    case ConsentPurpose.Mood =>
      tr("Off. Send /mood in the chat to turn it on.", "Вимкнено. Надішліть /mood у чаті, щоб увімкнути.")
    case ConsentPurpose.Sleep =>
      tr("Off. The chat cannot turn sleep records on yet.", "Вимкнено. Чат поки не вміє вмикати записи сну.")
    case ConsentPurpose.CrossDomainPatterns =>
      tr(
        "Off. The chat asks for it when a comparison is first requested.",
        "Вимкнено. Чат попросить згоду, коли ви вперше запросите порівняння."
      )
    case ConsentPurpose.ImagesInChat =>
      tr(
        "Off. The chat asks for it when an image is first requested.",
        "Вимкнено. Чат попросить згоду, коли ви вперше запросите зображення."
      )
    case ConsentPurpose.Nutrition =>
      tr("Off. Send /food in the chat to turn it on.", "Вимкнено. Надішліть /food у чаті, щоб увімкнути.")

  def quietHoursNote: String =
    tr(
      "A medication reminder that falls inside quiet hours follows the quiet-hours rule of its schedule.",
      "Нагадування про ліки, що припадає на тихі години, діє за правилом тихих годин свого розкладу."
    )
  def discreetNote: String =
    tr(
      "This choice is saved with your account. Reminders in the chat do not use it yet: they still show the name.",
      "Цей вибір зберігається з вашим акаунтом. Нагадування в чаті поки його не враховують: вони й далі показують назву."
    )

  def timeThere(time: String): String = tr(s"It is $time there now.", s"Там зараз $time.")
  def noSchedulesMove: String = tr("No schedule is changed by this.", "Жоден розклад від цього не зміниться.")
  def schedulesFollow(count: Int): String =
    tr(
      s"${ApiCopy.count(count, "schedule")} ${if count == 1 then "follows" else "follow"} your time zone and " +
        s"${if count == 1 then "keeps its" else "keep their"} clock times.",
      s"Розкладів, що йдуть за вашим часовим поясом і зберігають свій час: $count."
    )
  def scheduleStays(medicationName: String, zone: ZoneId): String =
    tr(s"$medicationName stays on ${zone.getId} time.", s"$medicationName лишається за часом ${zone.getId}.")

  /** `every day 09:00, 21:00`, in the words of the chat wizard. */
  def ruleSummary(rule: Rule): String = rule match
    case Rule.FixedTimes(groups) =>
      val days = groups.flatMap(_.days).distinct
      val times = groups.flatMap(_.times).map(_.value).distinct.sorted
      val cadence =
        if days.size == Weekday.values.length then WizardCopy.cadenceEveryDay
        else if days.sortBy(_.ordinal) == Workdays then WizardCopy.cadenceWeekdays
        else WizardCopy.cadenceDays(days.sortBy(_.ordinal).map(day => WizardCopy.weekdayLabels(day.ordinal)))
      s"$cadence ${times.mkString(", ")}"
    case _: Rule.AsNeeded                          => asNeeded
    case Rule.EveryNDays(everyDays, times, anchor) =>
      s"${MedicationDepthCopy.everyNDays(everyDays, anchor)} ${times.map(_.value).sorted.mkString(", ")}"
    case Rule.Cycle(onDays, offDays, times, anchor) =>
      s"${MedicationDepthCopy.cycle(onDays, offDays, anchor)} ${times.map(_.value).sorted.mkString(", ")}"
    case _: Rule.EveryNWeeks | _: Rule.IntervalFixedStart | _: Rule.Chain | _: Rule.Taper | Rule.Paused =>
      Rule.kindTag(rule)

  /** The dose in the account's language, its unit from the catalogue (`DoseUnits`). */
  def doseText(amount: Option[BigDecimal], unit: Option[String]): Option[String] = DoseUnits.doseText(amount, unit)

  def clockChangeNote(candidate: OccurrenceCandidate, zone: ZoneId): Option[String] = candidate.dstKind match
    case DstKind.None => None
    case DstKind.Gap  =>
      val actual = clock(candidate.scheduledFor, zone)
      Some(
        tr(
          s"Clock change: ${candidate.localTime.value} does not exist on this day. The reminder comes at $actual.",
          s"Перехід на інший час: ${candidate.localTime.value} цього дня не існує. Нагадування надійде о $actual."
        )
      )
    case DstKind.Fold =>
      Some(
        tr(
          s"Clock change: ${candidate.localTime.value} happens twice on this day. The reminder comes the first time.",
          s"Перехід на інший час: ${candidate.localTime.value} цього дня буде двічі. Нагадування надійде першого разу."
        )
      )

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
      instructions = stored.instructions,
      doseUnitLabel = stored.doseUnit.map(DoseUnits.label)
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
