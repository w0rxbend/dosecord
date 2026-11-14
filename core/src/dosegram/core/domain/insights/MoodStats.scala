package dosegram.core.domain.insights

import dosegram.core.domain.mood.MoodCheckin
import dosegram.core.domain.mood.MoodLevel

import java.time.DayOfWeek
import java.time.LocalDate

/** The mood rollup of one local day with at least one check-in. Sums are stored, not means, so the day value is exact
  * and a rebuilt rollup equals the first one bit for bit. Energy counts only the check-ins that gave it.
  */
final case class MoodDay(n: Int, valenceSum: Int, valenceMin: Int, valenceMax: Int, energyN: Int, energySum: Int):
  require(n > 0, "a mood day has at least one check-in")

  /** `Md`: the arithmetic mean of the day's valences on the stored 1 to 5 scale (PRODUCT_SPEC R-MOO-5). */
  def mean: Double = valenceSum.toDouble / n.toDouble

  /** The day value rounded half up, for a calendar cell. */
  def calendarStep: Int = (2 * valenceSum + n) / (2 * n)

  def level: MoodLevel = MoodLevel.ofMean(mean)
  def energyMean: Option[Double] = Option.when(energyN > 0)(energySum.toDouble / energyN.toDouble)

/** The lowest and the highest daily mean of a period. */
final case class MoodRange(low: Double, high: Double)

/** The period figures of VISUALS_AND_ANALYTICS section 7.6. Variability is reported as numbers only: `spread` is the
  * sample standard deviation of the daily means, `dayToDayChange` the mean absolute change between consecutive calendar
  * days that both have a check-in. No figure is ever labelled, and no threshold is applied to one.
  */
final case class MoodPeriod(
    window: DateRange,
    checkIns: Int,
    checkInDays: Int,
    average: Stat[Double],
    range: Stat[MoodRange],
    distribution: Map[MoodLevel, Int],
    spread: Stat[Double],
    dayToDayChange: Stat[Double],
    energyAverage: Stat[Double]
)

/** The check-in days of one day of the week in a period and the mean of their daily means, behind its gate. */
final case class WeekdayMood(day: DayOfWeek, days: Int, mean: Stat[Double])

/** Mood metrics. A day without a check-in is a gap: it has no [[MoodDay]] and enters no figure. */
object MoodStats:

  /** Live check-ins grouped by the local date stored on each check-in. */
  def byDay(checkins: Iterable[MoodCheckin]): Map[LocalDate, MoodDay] =
    checkins.filter(_.isLive).groupBy(_.localDate).map((date, own) => date -> day(own))

  private def day(checkins: Iterable[MoodCheckin]): MoodDay =
    val valences = checkins.map(_.valence.step)
    val energies = checkins.flatMap(_.energy).map(_.step)
    MoodDay(valences.size, valences.sum, valences.min, valences.max, energies.size, energies.sum)

  def period(days: Map[LocalDate, MoodDay], window: DateRange): MoodPeriod =
    val inWindow = days.view.filterKeys(window.contains).toMap
    val means = inWindow.values.map(_.mean).toList
    val energyMeans = inWindow.values.flatMap(_.energyMean).toList
    val changes = dayToDayChanges(inWindow)
    val meanMinimum = MinimumSample.meanOver(window)
    MoodPeriod(
      window = window,
      checkIns = inWindow.values.map(_.n).sum,
      checkInDays = means.size,
      average = Stat.gated(means.size, meanMinimum, window, Basis.Recorded)(mean(means)),
      range = Stat.gated(means.size, MinimumSample.AnyRecord, window, Basis.Recorded)(MoodRange(means.min, means.max)),
      distribution = inWindow.values.groupMapReduce(_.level)(_ => 1)(_ + _),
      spread = Stat.gated(means.size, MinimumSample.SpreadDays, window, Basis.Recorded)(sampleDeviation(means)),
      dayToDayChange = Stat.gated(changes.size, MinimumSample.DayToDayPairs, window, Basis.Recorded)(mean(changes)),
      energyAverage = Stat.gated(energyMeans.size, meanMinimum, window, Basis.Recorded)(mean(energyMeans))
    )

  /** Monday to Sunday: the mean of the daily means of each weekday's check-in days in the window. A description of the
    * user's own days with no comparison drawn between them (VISUALS section 9.2, supervisor decision of 2026-11-14).
    */
  def byWeekday(days: Map[LocalDate, MoodDay], window: DateRange): List[WeekdayMood] =
    val inWindow = days.view.filterKeys(window.contains).toList
    DayOfWeek.values.toList.map: weekday =>
      val means = inWindow.collect { case (date, day) if date.getDayOfWeek == weekday => day.mean }
      WeekdayMood(
        weekday,
        means.size,
        Stat.gated(means.size, MinimumSample.WeekdayMeanDays, window, Basis.Recorded)(mean(means))
      )

  private def dayToDayChanges(days: Map[LocalDate, MoodDay]): List[Double] =
    days.toList.flatMap { (date, today) =>
      days.get(date.minusDays(1)).map(yesterday => Math.abs(today.mean - yesterday.mean))
    }

  private def mean(values: List[Double]): Double = values.sum / values.size.toDouble

  private def sampleDeviation(values: List[Double]): Double =
    val centre = mean(values)
    Math.sqrt(values.map(value => (value - centre) * (value - centre)).sum / (values.size - 1).toDouble)
end MoodStats
