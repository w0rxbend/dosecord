package dosegram.core.application.exports

import dosegram.contracts.Weekday
import dosegram.core.application.insights.RollupService
import dosegram.core.domain.Rule
import dosegram.core.domain.copy.ExportCopy
import dosegram.core.domain.copy.MoodLabels
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.domain.insights.SleepStats
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.StoredMedication
import dosegram.core.ports.SummaryBlock
import dosegram.core.ports.SummaryDocument
import dosegram.core.ports.SummarySection
import dosegram.core.ports.Tx

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** The record summary of a period (PRODUCT_SPEC R-IMX-6, VISUALS_AND_ANALYTICS DOC-01) as a document of text: the
  * medications with their schedules in words, the dose records by month per medication, and the monthly sleep, mood and
  * water figures. Each figure comes from the same pure functions and daily rollups as the statistics of the chat and
  * the Mini App (VA-DOC-01a), read on the caller's transaction. A domain without its consent is named as not included
  * and shows nothing (R-IDC-9). The document carries no account identifier and no interpretation.
  */
final class RecordSummaries(rollups: RollupService):
  import RecordSummaries.*

  def document(tx: Tx, accountId: UUID, period: DateRange, zone: ZoneId, at: Instant): SummaryDocument =
    def granted(purpose: ConsentPurpose): Boolean = tx.consents.isGranted(accountId, purpose)
    val months = monthsOf(period)
    val body = List(
      ExportCopy.sectionMedications -> Option.when(granted(ConsentPurpose.Medication))(
        medications(tx, accountId, period)
      ),
      ExportCopy.sectionMedicationRecords -> Option.when(granted(ConsentPurpose.Medication))(
        doseRecords(tx, accountId, period, months)
      ),
      ExportCopy.sectionSleep -> Option.when(granted(ConsentPurpose.Sleep))(sleep(tx, accountId, period, months)),
      ExportCopy.sectionMood -> Option.when(granted(ConsentPurpose.Mood))(mood(tx, accountId, period, months)),
      ExportCopy.sectionWater -> Option.when(granted(ConsentPurpose.Hydration))(water(tx, accountId, period, months))
    ).map((heading, blocks) =>
      SummarySection(heading, blocks.getOrElse(List(SummaryBlock.Paragraph(ExportCopy.notShownWithoutConsent))))
    )
    val definitions =
      SummarySection(ExportCopy.sectionDefinitions, ExportCopy.definitions.map(SummaryBlock.Paragraph(_)))
    val scope = SummarySection(
      ExportCopy.sectionScope,
      List(
        SummaryBlock.Paragraph(ExportCopy.scopeStatement),
        SummaryBlock.Paragraph(ExportCopy.generated(at, zone)),
        SummaryBlock.Paragraph(ExportCopy.includes(body.map(_.heading) :+ definitions.heading))
      )
    )
    SummaryDocument(
      title = ExportCopy.summaryTitle,
      period = ExportCopy.periodText(period),
      notice = ExportCopy.notMedicalAdvice,
      footer = ExportCopy.summaryTitle,
      createdAt = at,
      sections = scope :: body ::: List(definitions)
    )

  // ---------- medications ----------

  private def medications(tx: Tx, accountId: UUID, period: DateRange): List[SummaryBlock] =
    val shown = listed(tx, accountId, period)
    if shown.isEmpty then List(SummaryBlock.Paragraph(ExportCopy.noMedications))
    else
      val rows = shown.map: medication =>
        List(
          medication.name,
          doseOf(medication).getOrElse(""),
          schedulesOf(tx, medication.id).mkString("; "),
          statusOf(medication.status),
          medication.instructions.getOrElse("")
        )
      List(SummaryBlock.Table(ExportCopy.medicationHeader, rows, numeric = Set.empty))

  /** The medications that are not archived, and the archived ones that have a dose record in the period. */
  private def listed(tx: Tx, accountId: UUID, period: DateRange): List[StoredMedication] =
    val recorded = medicationDays(tx, accountId, period).values.flatMap(_.perMedication.keys).toSet
    tx.medications
      .listAllForAccount(accountId)
      .filter(medication => medication.status != MedicationStatus.Archived || recorded(medication.id))
      .sortBy(_.nameNorm)

  private def schedulesOf(tx: Tx, medicationId: UUID): List[String] =
    tx.schedules
      .listForMedication(medicationId)
      .filter(_.status.dbValue != "archived")
      .flatMap(schedule => tx.revisions.get(schedule.id, schedule.currentRevision))
      .map(revision => ruleInWords(revision.rule))

  private def doseRecords(tx: Tx, accountId: UUID, period: DateRange, months: List[DateRange]): List[SummaryBlock] =
    val days = medicationDays(tx, accountId, period)
    val names = tx.medications.listAllForAccount(accountId).map(m => m.id -> m).toMap
    val withRecords = MedicationStats.perMedication(days.values).filterNot(_._2.isEmpty)
    if withRecords.isEmpty then List(SummaryBlock.Paragraph(ExportCopy.noRecords))
    else
      withRecords.toList
        .sortBy((id, _) => names.get(id).map(_.nameNorm).getOrElse(""))
        .flatMap: (id, total) =>
          val title = names.get(id).map(m => (m.name :: doseOf(m).toList).mkString(" ")).getOrElse("")
          val rows = months.flatMap: month =>
            val buckets = bucketsIn(days, id, month)
            Option.unless(buckets.isEmpty)(monthName(month) :: doseCounts(buckets))
          val figures = MedicationStats.figures(total, period)
          val resolved = total.resolved
          val taken = resolved.takenOnTime + resolved.takenLate
          val counted = taken + resolved.skipped + resolved.missed
          List(
            SummaryBlock.Subheading(title),
            SummaryBlock.Table(ExportCopy.doseHeader, rows, numeric = Set(1, 2, 3, 4, 5, 6)),
            SummaryBlock.Paragraph(ExportCopy.taken(taken, counted, figures.adherence.shown.map(_.percent)))
          ) ++ Option.when(total.asNeeded > 0)(SummaryBlock.Paragraph(ExportCopy.asNeeded(total.asNeeded)))

  private def bucketsIn(days: Map[java.time.LocalDate, MedicationDay], id: UUID, month: DateRange): DoseBuckets =
    days
      .collect { case (date, day) if month.contains(date) => day.perMedication.getOrElse(id, DoseBuckets.zero) }
      .foldLeft(DoseBuckets.zero)(_.combine(_))

  private def doseCounts(buckets: DoseBuckets): List[String] =
    val scheduled =
      buckets.takenOnTime + buckets.takenLate + buckets.skipped + buckets.missed + buckets.unknown + buckets.open
    List(scheduled, buckets.takenOnTime, buckets.takenLate, buckets.skipped, buckets.missed, buckets.unknown)
      .map(_.toString)

  private def medicationDays(tx: Tx, accountId: UUID, period: DateRange): Map[java.time.LocalDate, MedicationDay] =
    rollups.refresh(tx, accountId, RollupDomain.Medication, period)
    tx.tracking.rollups
      .list(accountId, RollupDomain.Medication, period)
      .collect { case (date, RollupMetrics.Medication(day)) => date -> day }

  // ---------- sleep, mood, water ----------

  private def sleep(tx: Tx, accountId: UUID, period: DateRange, months: List[DateRange]): List[SummaryBlock] =
    val nights = SleepStats.byDay(tx.tracking.sleep.liveEpisodes(accountId, period))
    if nights.isEmpty then List(SummaryBlock.Paragraph(ExportCopy.noRecords))
    else
      val rows = months.flatMap: month =>
        val figures = SleepStats.period(nights, month)
        Option.when(figures.nights > 0):
          val mean = figures.averageMinutes.shown.map(m => ExportCopy.minutes(math.round(m).toInt))
          List(monthName(month), figures.nights.toString, mean.getOrElse(ExportCopy.tooFew))
      List(SummaryBlock.Table(ExportCopy.sleepHeader, rows, numeric = Set(1, 2)))

  private def mood(tx: Tx, accountId: UUID, period: DateRange, months: List[DateRange]): List[SummaryBlock] =
    val checkins = tx.tracking.mood.listLive(accountId, period)
    if checkins.isEmpty then List(SummaryBlock.Paragraph(ExportCopy.noRecords))
    else
      val days = MoodStats.byDay(checkins)
      val rows = months.flatMap: month =>
        val figures = MoodStats.period(days, month)
        Option.when(figures.checkIns > 0)(
          List(monthName(month), figures.checkIns.toString, figures.checkInDays.toString)
        )
      val steps = Valence.steps.reverse.map: step =>
        List(MoodLabels.valence(step), checkins.count(_.valence.step == step.step).toString)
      List(
        SummaryBlock.Table(ExportCopy.moodHeader, rows, numeric = Set(1, 2)),
        SummaryBlock.Table(ExportCopy.moodStepHeader, steps, numeric = Set(1))
      )

  private def water(tx: Tx, accountId: UUID, period: DateRange, months: List[DateRange]): List[SummaryBlock] =
    rollups.refresh(tx, accountId, RollupDomain.Hydration, period)
    val days: Map[java.time.LocalDate, HydrationDay] = tx.tracking.rollups
      .list(accountId, RollupDomain.Hydration, period)
      .collect { case (date, RollupMetrics.Hydration(day)) => date -> day }
    if days.isEmpty then List(SummaryBlock.Paragraph(ExportCopy.noRecords))
    else
      val rows = months.flatMap: month =>
        val figures = HydrationStats.period(days, month)
        Option.when(figures.recordedDays > 0):
          List(
            monthName(month),
            figures.recordedDays.toString,
            ExportCopy.millilitres(figures.totalMl),
            figures.meanMl.shown.map(ExportCopy.millilitres).getOrElse(ExportCopy.tooFew),
            figures.goalDays
              .map(_.shown.map(r => ExportCopy.goalDays(r.numerator, r.denominator)).getOrElse(ExportCopy.tooFew))
              .getOrElse(ExportCopy.noGoal)
          )
      List(SummaryBlock.Table(ExportCopy.waterHeader, rows, numeric = Set(1, 2, 3, 4)))
end RecordSummaries

object RecordSummaries:

  private val MonthFormat = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

  /** The calendar months of the period, each cut to the period. */
  def monthsOf(period: DateRange): List[DateRange] =
    val first = YearMonth.from(period.start)
    val last = YearMonth.from(period.end)
    Iterator
      .iterate(first)(_.plusMonths(1))
      .takeWhile(!_.isAfter(last))
      .map: month =>
        val start = if month == first then period.start else month.atDay(1)
        val end = if month == last then period.end else month.atEndOfMonth
        DateRange.between(start, end)
      .toList

  private def monthName(month: DateRange): String = MonthFormat.format(month.start)

  private def doseOf(medication: StoredMedication): Option[String] =
    val number = medication.doseAmount.map(_.bigDecimal.stripTrailingZeros.toPlainString)
    Option.when(number.isDefined || medication.doseUnit.isDefined)(
      List(number, medication.doseUnit).flatten.mkString(" ")
    )

  private def statusOf(status: MedicationStatus): String =
    status match
      case MedicationStatus.Active   => "Active"
      case MedicationStatus.Paused   => "Paused"
      case MedicationStatus.Archived => "Archived"

  /** A schedule in the words of the chat wizard: `every day 09:00, 21:00`. */
  def ruleInWords(rule: Rule): String = rule match
    case Rule.FixedTimes(groups) =>
      val days = groups.flatMap(_.days).distinct.sortBy(_.ordinal)
      val times = groups.flatMap(_.times).map(_.value).distinct.sorted
      val cadence =
        if days.size == Weekday.values.length then WizardCopy.cadenceEveryDay
        else if days == Weekday.values.toList.take(5) then WizardCopy.cadenceWeekdays
        else WizardCopy.cadenceDays(days.map(_.toString))
      s"$cadence ${times.mkString(", ")}"
    case _: Rule.AsNeeded => "as needed"
    case _: Rule.EveryNDays | _: Rule.EveryNWeeks | _: Rule.Cycle | _: Rule.IntervalFixedStart | _: Rule.Chain |
        _: Rule.Taper | Rule.Paused =>
      Rule.kindTag(rule)
end RecordSummaries
