package dosegram.core.domain.insights

import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepKind

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** One episode on the regularity chart: its sleep day and its edges in minutes after 18:00 of the evening before, kept
  * inside the 24 hours of the axis.
  */
final case class SleepBar(date: LocalDate, fromMinute: Int, toMinute: Int, isNap: Boolean)

/** IMG-12 as numbers: the bars of a window and the figures under the chart. A figure is absent below its gate. Clock
  * times are minutes after local midnight.
  */
final case class SleepRegularityFigures(
    window: DateRange,
    bars: List[SleepBar],
    nights: Int,
    averageMinutes: Option[Int],
    bedtimeSpreadMinutes: Option[Int],
    wakeSpreadMinutes: Option[Int],
    typicalBedtime: Option[Int],
    typicalWake: Option[Int],
    goalMinutes: Option[Int]
)

/** The regularity metrics of VISUALS_AND_ANALYTICS section 7.7 over explicit nights (tier 2): the circular standard
  * deviation of bedtimes and wake times ("bedtime varied by about 35 min") and their circular medians, on 10 of 14
  * nights. Naps are drawn and never counted. No index and no score is computed here: the Sleep Regularity Index is not
  * shown in chat images (VISUALS section 7.7, decision Q8).
  */
object SleepRegularity:
  val WindowDays = 14
  private val DayMinutes = 1440
  private val AxisStart = LocalTime.of(18, 0)

  /** 5 of 7, 10 of 14 or 20 of 28 nights. */
  def consistencyGate(window: DateRange): Int = (window.days * 5 + 6) / 7

  def view(episodes: List[SleepEpisode], window: DateRange, goalMinutes: Option[Int]): SleepRegularityFigures =
    val shown = episodes.filter(episode => episode.isLive && window.contains(episode.sleepDay))
    val nights = SleepStats.byDay(shown).keySet
    val mains =
      shown.filter(_.kind == SleepKind.Main).groupBy(_.sleepDay).values.map(_.maxBy(_.interval.wake.at)).toList
    val consistent = mains.size >= consistencyGate(window)
    val minutes = mains.map(_.duration.toMinutes.toInt)
    val average = Stat.gated(minutes.size, MinimumSample.meanOver(window), window, Basis.SelfReported)(
      minutes.sum.toDouble / minutes.size.toDouble
    )
    def clock(edge: SleepEpisode => SleepEdge): List[Int] = mains.map(episode => clockMinute(edge(episode)))
    SleepRegularityFigures(
      window = window,
      bars = shown.sortBy(_.interval.wake.at).map(barOf),
      nights = nights.size,
      averageMinutes = average.shown.map(mean => Math.round(mean).toInt),
      bedtimeSpreadMinutes = Option.when(consistent)(Math.round(circularSd(clock(_.interval.onset))).toInt),
      wakeSpreadMinutes = Option.when(consistent)(Math.round(circularSd(clock(_.interval.wake))).toInt),
      typicalBedtime = Option.when(consistent)(circularMedian(clock(_.interval.onset))),
      typicalWake = Option.when(consistent)(circularMedian(clock(_.interval.wake))),
      goalMinutes = goalMinutes
    )

  /** `sd = sqrt(-2 ln R) * 1440 / 2 pi` over the clock times as angles (section 7.7). */
  def circularSd(clockMinutes: List[Int]): Double =
    val angles = clockMinutes.map(minute => 2 * Math.PI * minute / DayMinutes)
    val c = angles.map(Math.cos).sum / angles.size
    val s = angles.map(Math.sin).sum / angles.size
    val r = Math.min(1.0, Math.sqrt(c * c + s * s))
    Math.sqrt(-2 * Math.log(r)) * DayMinutes / (2 * Math.PI)

  /** The observed clock time with the least total distance around the clock to the others. */
  def circularMedian(clockMinutes: List[Int]): Int =
    def distance(a: Int, b: Int): Int =
      val gap = Math.floorMod(a - b, DayMinutes)
      Math.min(gap, DayMinutes - gap)
    clockMinutes.minBy(candidate => (clockMinutes.map(distance(candidate, _)).sum, candidate))

  private def clockMinute(edge: SleepEdge): Int =
    val local = edge.at.atZone(edge.zone).toLocalTime
    local.getHour * 60 + local.getMinute

  private def barOf(episode: SleepEpisode): SleepBar =
    val wake = episode.interval.wake
    val origin = episode.sleepDay.minusDays(1).atTime(AxisStart).atZone(wake.zone).toInstant
    def axis(edge: SleepEdge): Int =
      Math.clamp(Duration.between(origin, edge.at).toMinutes, 0L, DayMinutes.toLong).toInt
    SleepBar(episode.sleepDay, axis(episode.interval.onset), axis(wake), episode.kind == SleepKind.Nap)
