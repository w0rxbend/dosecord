package dosegram.core.domain.insights

import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.mood.MoodCheckin
import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepKind

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A quarter of the local day, as the rhythm counts records in it: night from 00:00, morning from 06:00, afternoon from
  * 12:00 and evening from 18:00, each to the next.
  */
enum DayPart(val fromHour: Int):
  case Morning extends DayPart(6)
  case Afternoon extends DayPart(12)
  case Evening extends DayPart(18)
  case Night extends DayPart(0)

  def hours: Range = fromHour until fromHour + DayPart.Hours

object DayPart:
  val Hours = 6

/** How many records of an area fell in each hour of the local day: `counts(h)` is the hour from `h`:00 to the next. */
final case class HourBand(counts: Vector[Int]):
  require(counts.size == HourBand.Hours, s"a band has ${HourBand.Hours} hours, got ${counts.size}")
  require(counts.forall(_ >= 0), "a count of records is never negative")

  def total: Int = counts.sum

  /** The hour with the most records, the earliest of a tie; `None` without a record. */
  def peak: Option[Int] = Option.when(total > 0)(counts.indices.maxBy(hour => (counts(hour), -hour)))

  /** The records of each quarter of the day, morning first. */
  def byPart: List[(DayPart, Int)] = DayPart.values.toList.map(part => part -> part.hours.map(counts).sum)

object HourBand:
  val Hours = 24

  def of(hours: Iterable[Int]): HourBand =
    val counts = Array.fill(Hours)(0)
    hours.foreach(hour => counts(hour) += 1)
    HourBand(counts.toVector)

/** A typical clock time and how much it varied: the circular median and standard deviation of VISUALS section 7.7, in
  * minutes after local midnight and in minutes.
  */
final case class ClockSpread(typicalMinute: Int, spreadMinutes: Int)

/** The timing of the taken doses of a schedule: the median of `recorded - scheduled` (negative before the scheduled
  * time), and the nearest-rank 90th percentile of its size, so that 9 in 10 doses lie within it.
  */
final case class DoseTiming(medianMinutes: Int, withinMinutes: Int)

/** One meal type: how many entries it has in the window and its typical clock time. */
final case class MealTime(mealType: MealType, entries: Int, typicalMinute: Int)

/** What an area's rhythm says beyond its band. */
enum RhythmDetail:
  /** Water: the band counts the entries. */
  case Entries

  /** Mood: the band counts the check-ins. Their values are not part of the rhythm. */
  case CheckIns

  /** Medication: the band counts the doses recorded as taken by the hour they were recorded; their timing against the
    * scheduled time needs 5 such doses whose time was not corrected (VISUALS section 7.2, delay statistics).
    */
  case Doses(timing: Stat[DoseTiming])

  /** Sleep: the band counts the main sleeps that covered at least half of each hour; the bedtime and the wake time. */
  case Nights(bedtime: ClockSpread, wake: ClockSpread)

  /** Food: the band counts every meal and snack; each type with an entry, in the order of [[MealType]]. */
  case Meals(types: List[MealTime])

/** An area's rhythm once its gate is met. */
final case class RhythmPattern(band: HourBand, detail: RhythmDetail)

/** One area of the rhythm: its days with records in the window and its pattern behind the 14-day gate. */
final case class RhythmRow(domain: TrendDomain, recordedDays: Int, pattern: Stat[RhythmPattern])

/** The rhythm of the day over the last 30 local days (supervisor decision, 2026-11-28): one row per area whose consent
  * is active, in the order medication, water, mood, sleep, food, and the window's days with records under the one
  * definition (BOT_UX section 12.5).
  */
final case class RhythmFigures(window: DateRange, records: RecordedDays, rows: List[RhythmRow]):
  def hasRecords: Boolean = records.withRecords > 0

  /** Whether any row has a pattern to draw: what "Show as image" is offered for. */
  def hasPattern: Boolean = rows.exists(_.pattern.shown.isDefined)

/** What the rhythm reads: the days of the window per area (`None` without its consent), the account's zone for the
  * doses, and each consented area's live records of the window. A record's clock time is read in the zone it was
  * recorded in (VISUALS section 7.1, local day).
  */
final case class RhythmSources(
    days: YearSources,
    zone: ZoneId,
    doses: List[AdherenceInput] = Nil,
    water: List[HydrationEntry] = Nil,
    mood: List[MoodCheckin] = Nil,
    sleep: List[SleepEpisode] = Nil,
    meals: List[MealEntry] = Nil
)

/** The rhythm of the day (supervisor decision, 2026-11-28): when in the local day the user's own records fall, per
  * area. It describes and never judges: no target time, no score, no adherence figure and no counter of any kind
  * (R-GAM-2). An area shows its rhythm only from 14 days with records in the window, the gate of PRODUCT_SPEC R-INS-11;
  * fewer show those days. A day without a record enters nothing (VA-PR-03).
  */
object DayRhythm:
  val WindowDays = 30
  val Gate: MinimumSample = MinimumSample.MonthlyReportDays
  private val HalfHour = Duration.ofMinutes(30)

  /** The last 30 local days, today included. */
  def window(today: LocalDate): DateRange = DateRange.endingOn(today, WindowDays)

  def of(window: DateRange, sources: RhythmSources): RhythmFigures =
    val rows = YearStats
      .cells(sources.days, window, Gate)
      .map((domain, cell) => row(domain, cell.recordedDays, window, sources))
    RhythmFigures(window, YearStats.daysWithRecords(sources.days, window), rows)

  private def row(domain: TrendDomain, recordedDays: Int, window: DateRange, sources: RhythmSources): RhythmRow =
    val basis = if domain == TrendDomain.Sleep then Basis.SelfReported else Basis.Recorded
    RhythmRow(domain, recordedDays, Stat.gated(recordedDays, Gate, window, basis)(pattern(domain, window, sources)))

  private def pattern(domain: TrendDomain, window: DateRange, sources: RhythmSources): RhythmPattern =
    domain match
      case TrendDomain.Medication => doses(sources.doses, sources.zone, window)
      case TrendDomain.Hydration  =>
        RhythmPattern(
          HourBand.of(sources.water.filter(_.isLive).map(e => hourOf(e.occurredAt, e.zone))),
          RhythmDetail.Entries
        )
      case TrendDomain.Mood =>
        RhythmPattern(
          HourBand.of(sources.mood.filter(_.isLive).map(c => hourOf(c.occurredAt, c.zone))),
          RhythmDetail.CheckIns
        )
      case TrendDomain.Sleep     => nights(sources.sleep, window)
      case TrendDomain.Nutrition => meals(sources.meals.filter(_.isLive))

  /** The doses of a schedule recorded as taken; as-needed logs have no scheduled time and are left out. */
  private def doses(inputs: List[AdherenceInput], zone: ZoneId, window: DateRange): RhythmPattern =
    val taken = inputs.filter(input => !input.manual && input.status == OccurrenceStatus.Taken)
    val recorded = taken.flatMap(input => input.effectiveAt.map(input -> _))
    val band = HourBand.of(recorded.map((_, at) => hourOf(at, zone)))
    val delays = recorded.collect { case (input, at) if !input.corrected => minutesBetween(input.scheduledFor, at) }
    val timing = Stat.gated(delays.size, MinimumSample.DelaySamples, window, Basis.Recorded)(timingOf(delays))
    RhythmPattern(band, RhythmDetail.Doses(timing))

  private def timingOf(delays: List[Int]): DoseTiming =
    val sorted = delays.sorted
    val n = sorted.size
    val median = if n % 2 == 1 then sorted(n / 2) else Math.floorDiv(sorted(n / 2 - 1) + sorted(n / 2), 2)
    val sizes = delays.map(Math.abs).sorted
    DoseTiming(median, sizes(Math.max(0, Math.ceil(0.9 * n).toInt - 1)))

  /** The main sleeps of the window, the one that ended last on a sleep day as its night (VISUALS section 7.7); naps are
    * left out.
    */
  private def nights(episodes: List[SleepEpisode], window: DateRange): RhythmPattern =
    val mains = episodes
      .filter(episode => episode.isLive && episode.kind == SleepKind.Main && window.contains(episode.sleepDay))
      .groupBy(_.sleepDay)
      .values
      .map(_.maxBy(_.interval.wake.at))
      .toList
    val band = HourBand.of(mains.flatMap(asleepHours))
    def spread(edge: SleepEpisode => SleepEdge): ClockSpread =
      val minutes = mains.map(episode => minuteOf(edge(episode).at, edge(episode).zone))
      ClockSpread(SleepRegularity.circularMedian(minutes), Math.round(SleepRegularity.circularSd(minutes)).toInt)
    RhythmPattern(band, RhythmDetail.Nights(spread(_.interval.onset), spread(_.interval.wake)))

  /** The clock hours of the wake zone that a night covers for at least half an hour. */
  private def asleepHours(episode: SleepEpisode): List[Int] =
    val zone = episode.interval.wake.zone
    val onset = episode.interval.onset.at
    val wake = episode.interval.wake.at
    Iterator
      .iterate(onset.atZone(zone).truncatedTo(ChronoUnit.HOURS))(_.plusHours(1))
      .takeWhile(_.toInstant.isBefore(wake))
      .filter: start =>
        val from = latest(start.toInstant, onset)
        val to = earliest(start.plusHours(1).toInstant, wake)
        Duration.between(from, to).compareTo(HalfHour) >= 0
      .map(_.getHour)
      .toList
      .distinct

  private def meals(entries: List[MealEntry]): RhythmPattern =
    val band = HourBand.of(entries.map(entry => hourOf(entry.occurredAt, entry.zone)))
    val types = MealType.values.toList.flatMap: mealType =>
      val own = entries.filter(_.mealType == mealType)
      Option.when(own.nonEmpty):
        val minutes = own.map(entry => minuteOf(entry.occurredAt, entry.zone))
        MealTime(mealType, own.size, SleepRegularity.circularMedian(minutes))
    RhythmPattern(band, RhythmDetail.Meals(types))

  private def hourOf(at: Instant, zone: ZoneId): Int = at.atZone(zone).getHour

  private def minuteOf(at: Instant, zone: ZoneId): Int =
    val local = at.atZone(zone).toLocalTime
    local.getHour * 60 + local.getMinute

  private def minutesBetween(from: Instant, to: Instant): Int = Duration.between(from, to).toMinutes.toInt

  private def latest(a: Instant, b: Instant): Instant = if a.isAfter(b) then a else b
  private def earliest(a: Instant, b: Instant): Instant = if a.isBefore(b) then a else b
end DayRhythm
