package dosegram.core.domain.insights

import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepKind

import java.time.LocalDate

/** The main night of one sleep day (VISUALS_AND_ANALYTICS section 7.7): its duration in whole minutes, a difference of
  * instants, and whether the user rated it. Every stored episode is explicit (tier 2): a quiet window is never an
  * episode, so nothing here can average one (VA-ME-60).
  */
final case class SleepNight(minutes: Int, quality: Option[Int])

/** The sleep figures of a period: the nights with a record and their mean duration behind the mean gate. */
final case class SleepPeriod(window: DateRange, nights: Int, averageMinutes: Stat[Double])

/** Sleep metrics. A sleep day without a main night is a gap: it has no [[SleepNight]] and enters no figure; naps are
  * not nights and are left out of every figure here.
  */
object SleepStats:

  /** The live main night of each sleep day. Two main nights on one day cannot be stored; should a store ever hold them,
    * the one that ended last is the night of that day.
    */
  def byDay(episodes: Iterable[SleepEpisode]): Map[LocalDate, SleepNight] =
    episodes
      .filter(episode => episode.isLive && episode.kind == SleepKind.Main)
      .groupBy(_.sleepDay)
      .map: (date, own) =>
        val night = own.maxBy(_.interval.wake.at)
        date -> SleepNight(night.duration.toMinutes.toInt, night.quality.map(_.step))

  def period(days: Map[LocalDate, SleepNight], window: DateRange): SleepPeriod =
    val minutes = days.view.filterKeys(window.contains).values.map(_.minutes).toList
    SleepPeriod(
      window = window,
      nights = minutes.size,
      averageMinutes = Stat.gated(minutes.size, MinimumSample.meanOver(window), window, Basis.SelfReported)(
        minutes.sum.toDouble / minutes.size.toDouble
      )
    )

/** What the insight rules read about one sleep day's main night: its length, the clock time it began (minutes after
  * local midnight in the zone at onset), whether the clock offset changed during the night, and the user's own goal in
  * force on that sleep day. A night whose clock shifted is dropped from every rule that reads a clock time (VISUALS
  * section 8.5, "Evidence filter").
  */
final case class NightFacts(minutes: Int, bedtimeMinute: Int, clockShift: Boolean, goalMinutes: Option[Int])

object NightFacts:

  /** The live main night of each sleep day, as [[SleepStats.byDay]] chooses it, with the goal in force on that day. */
  def of(
      episodes: Iterable[dosegram.core.domain.sleep.SleepEpisode],
      goals: Iterable[dosegram.core.domain.sleep.SleepGoalChange]
  ): Map[LocalDate, NightFacts] =
    episodes
      .filter(episode => episode.isLive && episode.kind == SleepKind.Main)
      .groupBy(_.sleepDay)
      .map: (date, own) =>
        val night = own.maxBy(_.interval.wake.at)
        val onset = night.interval.onset.at.atZone(night.interval.onset.zone)
        val wake = night.interval.wake.at.atZone(night.interval.wake.zone)
        date -> NightFacts(
          minutes = night.duration.toMinutes.toInt,
          bedtimeMinute = onset.getHour * 60 + onset.getMinute,
          clockShift = onset.getOffset != wake.getOffset,
          goalMinutes = goalOn(date, goals)
        )

  /** The goal in force on `date`: the latest change that took effect on or before it. */
  def goalOn(date: LocalDate, goals: Iterable[dosegram.core.domain.sleep.SleepGoalChange]): Option[Int] =
    goals
      .filterNot(_.effectiveFrom.isAfter(date))
      .maxByOption(_.effectiveFrom.toEpochDay)
      .flatMap(_.targetMinutes)
