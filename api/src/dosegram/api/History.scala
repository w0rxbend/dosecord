package dosegram.api

import dosegram.core.domain.copy.MoodLabels
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.UkForms
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.copy.tr
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.mood.MoodLevel
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** `GET /insights/calendar` (ARCHITECTURE section 9.5; MINI_APP section 7.12): one month of one domain, one cell per
  * day, with the accessible name and the printed value of each cell. tr("Nothing scheduled", "Нічого не заплановано")
  * and tr("no entry", "немає запису") are a state of their own and never the lowest step of a ramp; a day after today
  * is blank (MA-HIST-4). A domain without consent is refused (API-19).
  */
final class History(uow: UnitOfWork, tracking: TrackingUseCases, clock: Clock):
  import History.*

  def calendar(accountId: UUID, domain: InsightDomain, month: Option[String]): Either[ApiFailure, InsightCalendar] =
    uow.transaction: tx =>
      for
        zone <- tx.accounts.timezoneOf(accountId).map(ZoneId.of).toRight(ApiFailure.notFound())
        today = LocalDays.dateOf(clock.now(), zone)
        shown <- monthOf(month, YearMonth.from(today))
        cells <- cellsOf(tx, accountId, domain, monthRange(shown, today))
      yield InsightCalendar(
        domain = domain,
        month = shown.toString,
        monthLabel = ApiCopy.monthLabel(shown),
        previousMonth = shown.minusMonths(1).toString,
        steps = cells.steps,
        weekStart = MondayFirst,
        days = (1 to shown.lengthOfMonth).toList.map(day => cells.day(shown.atDay(day), today)),
        legend = cells.legend,
        summary = cells.summary(shown, YearMonth.from(today) == shown),
        domains = consented(tx, accountId),
        nextMonth = Option.when(shown.isBefore(YearMonth.from(today)))(shown.plusMonths(1).toString)
      )

  private def cellsOf(
      tx: Tx,
      accountId: UUID,
      domain: InsightDomain,
      range: Option[DateRange]
  ): Either[ApiFailure, Cells] =
    def read[A](query: DateRange => Either[ApiFailure, Map[LocalDate, A]]): Either[ApiFailure, Map[LocalDate, A]] =
      range.fold(Right(Map.empty[LocalDate, A]))(query)
    domain match
      case InsightDomain.Medication =>
        read(tracking.stats.medicationCalendar(tx, accountId, _).left.map(Refusals.of)).map(MedicationCells(_))
      case InsightDomain.Hydration =>
        read(tracking.stats.hydrationCalendar(tx, accountId, _).left.map(Refusals.of)).map(WaterCells(_))
      case InsightDomain.Mood =>
        read(tracking.stats.moodCalendar(tx, accountId, _).left.map(Refusals.of)).map(MoodCells(_))
      case InsightDomain.Sleep =>
        read(tracking.stats.sleepCalendar(tx, accountId, _).left.map(Refusals.of)).map(SleepCells(_))

  private def consented(tx: Tx, accountId: UUID): List[InsightDomain] =
    InsightDomain.values.toList.filter(domain => tx.consents.isGranted(accountId, purposeOf(domain)))

object History:
  private val MondayFirst = 1

  def purposeOf(domain: InsightDomain): ConsentPurpose = domain match
    case InsightDomain.Medication => ConsentPurpose.Medication
    case InsightDomain.Hydration  => ConsentPurpose.Hydration
    case InsightDomain.Mood       => ConsentPurpose.Mood
    case InsightDomain.Sleep      => ConsentPurpose.Sleep

  /** `YYYY-MM`, not after the current month; absent is the current month. */
  private def monthOf(month: Option[String], current: YearMonth): Either[ApiFailure, YearMonth] =
    month match
      case None      => Right(current)
      case Some(raw) =>
        scala.util
          .Try(YearMonth.parse(raw))
          .toOption
          .toRight(ApiFailure.invalid("month", "invalid"))
          .filterOrElse(!_.isAfter(current), ApiFailure.invalid("month", "in_future"))

  /** The days of the month up to today; `None` for a month that has not begun. */
  private def monthRange(month: YearMonth, today: LocalDate): Option[DateRange] =
    val first = month.atDay(1)
    val last = Seq(month.atEndOfMonth(), today).minBy(_.toEpochDay)
    Option.when(!last.isBefore(first))(DateRange.between(first, last))

  private val Empty = CalendarSwatch(CalendarCellState.Empty)
  private val Future = CalendarSwatch(CalendarCellState.Future)

  /** The cells of one domain: the swatch and the words of each recorded day, the legend and the month line. */
  private sealed trait Cells:
    def steps: Int
    def legend: List[CalendarLegendEntry]
    def summary(month: YearMonth, isCurrent: Boolean): String
    protected def emptyWords: String
    protected def recorded(date: LocalDate): Option[(CalendarSwatch, String, String)]

    final def day(date: LocalDate, today: LocalDate): CalendarDay =
      val prefix = ApiCopy.fullDay(date)
      if date.isAfter(today) then CalendarDay(date, Future, tr(s"$prefix, still ahead", s"$prefix, ще попереду"))
      else
        recorded(date) match
          case Some((swatch, words, value)) => CalendarDay(date, swatch, s"$prefix, $words", Some(value))
          case None                         => CalendarDay(date, Empty, s"$prefix, $emptyWords")

  /** The start of a summary sentence, so the Ukrainian month, lower case on its own, takes a capital. */
  private def period(month: YearMonth, isCurrent: Boolean): String =
    val name = ApiCopy.monthName(month).capitalize
    if isCurrent then tr(s"$name so far", s"$name на цю мить") else name

  private final case class MedicationCells(days: Map[LocalDate, MedicationDay]) extends Cells:
    val steps = 3
    protected val emptyWords = tr("nothing scheduled", "нічого не заплановано")

    // Steps: none taken, some taken, all taken. The "no record" marker is a second encoding beside the step.
    protected def recorded(date: LocalDate): Option[(CalendarSwatch, String, String)] =
      days
        .get(date)
        .map(_.total)
        .filter(Charts.scheduledIn(_) > 0)
        .map: buckets =>
          val taken = buckets.resolved.taken
          val scheduled = Charts.scheduledIn(buckets)
          val step = if taken == 0 then 1 else if taken < scheduled then 2 else 3
          (
            CalendarSwatch(CalendarCellState.Value, Some(step), hasNoRecord = buckets.unknown > 0),
            Charts.dosesOf(buckets),
            s"$taken/$scheduled"
          )

    val legend: List[CalendarLegendEntry] = List(
      CalendarLegendEntry(tr("All taken", "Усі прийнято"), CalendarSwatch(CalendarCellState.Value, Some(3))),
      CalendarLegendEntry(tr("Some taken", "Частину прийнято"), CalendarSwatch(CalendarCellState.Value, Some(2))),
      CalendarLegendEntry(tr("None taken", "Жодної не прийнято"), CalendarSwatch(CalendarCellState.Value, Some(1))),
      CalendarLegendEntry(tr("Nothing scheduled", "Нічого не заплановано"), Empty),
      CalendarLegendEntry(
        tr("No record", "Немає запису"),
        CalendarSwatch(CalendarCellState.Value, Some(2), hasNoRecord = true)
      )
    )

    def summary(month: YearMonth, isCurrent: Boolean): String =
      val total = days.values.map(_.total).foldLeft(DoseBuckets.zero)(_.combine(_))
      val scheduled = Charts.scheduledIn(total)
      if scheduled == 0 then
        tr(s"${period(month, isCurrent)}: no scheduled dose.", s"${period(month, isCurrent)}: запланованих доз немає.")
      else
        tr(
          s"${period(month, isCurrent)}: ${total.resolved.taken} of $scheduled doses taken.",
          s"${period(month, isCurrent)}: прийнято ${total.resolved.taken} з $scheduled доз."
        )

  private final case class WaterCells(days: Map[LocalDate, HydrationDay]) extends Cells:
    val steps = 4
    protected val emptyWords = tr("no entry", "немає запису")

    // Steps: quarters of the user's own goal, capped at the goal. A day without a goal has a value and no step.
    protected def recorded(date: LocalDate): Option[(CalendarSwatch, String, String)] =
      days
        .get(date)
        .filter(_.entries > 0)
        .map: day =>
          val step = day.progress.map(progress => Math.max(1, Math.min(4, (progress.percent + 24) / 25)))
          val words = day.progress.fold(WaterCopy.volume(day.totalMl))(_ => WaterCopy.figures(day))
          (CalendarSwatch(CalendarCellState.Value, step), words, WaterCopy.volume(day.totalMl).stripSuffix(" l"))

    val legend: List[CalendarLegendEntry] = List(
      CalendarLegendEntry(tr("Goal reached", "Ціль досягнуто"), CalendarSwatch(CalendarCellState.Value, Some(4))),
      CalendarLegendEntry(
        tr("Up to three quarters", "До трьох чвертей"),
        CalendarSwatch(CalendarCellState.Value, Some(3))
      ),
      CalendarLegendEntry(tr("Up to half", "До половини"), CalendarSwatch(CalendarCellState.Value, Some(2))),
      CalendarLegendEntry(tr("Up to a quarter", "До чверті"), CalendarSwatch(CalendarCellState.Value, Some(1))),
      CalendarLegendEntry(tr("No entry", "Немає запису"), Empty)
    )

    def summary(month: YearMonth, isCurrent: Boolean): String =
      val recorded = days.values.filter(_.entries > 0).toList
      val goal = Option.when(recorded.exists(_.goalMl.isDefined))(
        tr(
          s" The goal was reached on ${recorded.count(_.isGoalDay)} of them.",
          s" Ціль досягнуто в ${recorded.count(_.isGoalDay)} із них."
        )
      )
      if recorded.isEmpty then
        tr(s"${period(month, isCurrent)}: no entry.", s"${period(month, isCurrent)}: записів немає.")
      else
        tr(
          s"${period(month, isCurrent)}: water on ${ApiCopy.count(recorded.size, "day")}.${goal.getOrElse("")}",
          s"${period(month, isCurrent)}: воду записано за ${UkForms("день", "дні", "днів").count(recorded.size)}.${goal.getOrElse("")}"
        )

  private final case class MoodCells(days: Map[LocalDate, MoodDay]) extends Cells:
    val steps = 5
    protected val emptyWords = tr("no check-in", "немає позначки")

    // Steps: the daily mean rounded half up, with the colour of the blob state of that mean.
    protected def recorded(date: LocalDate): Option[(CalendarSwatch, String, String)] =
      days
        .get(date)
        .map: day =>
          val swatch =
            CalendarSwatch(CalendarCellState.Value, Some(day.calendarStep), blobState = Some(Mood.blobStateOf(day)))
          (swatch, ApiCopy.moodMean(day), day.calendarStep.toString)

    val legend: List[CalendarLegendEntry] =
      List(5, 4, 3, 2, 1).map: step =>
        val level = MoodLevel.ofMean(step.toDouble)
        CalendarLegendEntry(
          s"$step ${MoodLabels.level(level).toLowerCase(Locale.ROOT)}",
          CalendarSwatch(CalendarCellState.Value, Some(step), blobState = Some(BlobStates.of(level, None)))
        )
      :+ CalendarLegendEntry(tr("No check-in", "Немає позначки"), Empty)

    def summary(month: YearMonth, isCurrent: Boolean): String =
      if days.isEmpty then
        tr(s"${period(month, isCurrent)}: no check-in.", s"${period(month, isCurrent)}: позначок немає.")
      else
        val checkins = days.values.map(_.n).sum
        tr(
          s"${period(month, isCurrent)}: ${ApiCopy.count(checkins, "check-in")} on ${ApiCopy.count(days.size, "day")}.",
          s"${period(month, isCurrent)}: ${ApiCopy.count(checkins, "check-in")} за ${ApiCopy.count(days.size, "day")}."
        )

  // One step only: a ramp of durations would be a reference value, and none exists for sleep (MINI_APP MA-SLEEP-4).
  private final case class SleepCells(days: Map[LocalDate, SleepNight]) extends Cells:
    val steps = 1
    protected val emptyWords = tr("no night logged", "ніч не записано")

    protected def recorded(date: LocalDate): Option[(CalendarSwatch, String, String)] =
      days
        .get(date)
        .map: night =>
          val words = s"${StatsCopy.duration(night.minutes)}, ${StatsCopy.SleepSource}"
          (CalendarSwatch(CalendarCellState.Value, Some(1)), words, f"${night.minutes / 60}:${night.minutes % 60}%02d")

    val legend: List[CalendarLegendEntry] = List(
      CalendarLegendEntry(tr("Night logged", "Ніч записано"), CalendarSwatch(CalendarCellState.Value, Some(1))),
      CalendarLegendEntry(tr("No night logged", "Ніч не записано"), Empty)
    )

    def summary(month: YearMonth, isCurrent: Boolean): String =
      if days.isEmpty then
        tr(s"${period(month, isCurrent)}: no night logged.", s"${period(month, isCurrent)}: ночей не записано.")
      else
        tr(
          s"${period(month, isCurrent)}: ${ApiCopy.count(days.size, "night")} logged, from your check-ins.",
          s"${period(month, isCurrent)}: записано ${ApiCopy.count(days.size, "night")} з ваших позначок."
        )
end History
