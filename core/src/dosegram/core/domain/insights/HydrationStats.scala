package dosegram.core.domain.insights

import dosegram.core.domain.hydration.GoalChange
import dosegram.core.domain.hydration.HydrationEntry

import java.time.LocalDate

/** A day's total against the user's own goal (VISUALS_AND_ANALYTICS section 7.4). */
final case class GoalProgress(totalMl: Int, goalMl: Int):
  require(goalMl > 0, s"a goal is positive, got $goalMl")

  /** `min(1, E / G)`: what the vessel draws. */
  def completion: Double = Math.min(1.0, totalMl.toDouble / goalMl.toDouble)

  /** `floor(100 * E / G)`: the true figure for text, above 100 when the total passed the goal (R-HYD-5). */
  def percent: Int = Math.floorDiv(100L * totalMl, goalMl.toLong).toInt

  def reached: Boolean = totalMl >= goalMl

/** The hydration rollup of one recorded local day. `goalMl` is the goal in force on that day, if the user had one. */
final case class HydrationDay(totalMl: Int, entries: Int, goalMl: Option[Int]):
  def progress: Option[GoalProgress] = goalMl.map(GoalProgress(totalMl, _))
  def isGoalDay: Boolean = progress.exists(_.reached)

/** The period figures of VISUALS section 7.4. `goalDays` is absent when no recorded day of the period had a goal: the
  * product then shows totals only and no rate (R-HYD-2).
  */
final case class HydrationPeriod(
    window: DateRange,
    recordedDays: Int,
    totalMl: Int,
    meanMl: Stat[Int],
    goalDays: Option[Stat[Ratio]]
)

/** Hydration metrics. A day without an entry is "no record": it has no [[HydrationDay]] and is never counted as zero.
  * Every entry counts in full; the optional per-drink factor is a later wave.
  */
object HydrationStats:

  /** The goal in force on `date`: the latest change that took effect on or before it. */
  def goalOn(date: LocalDate, goals: Iterable[GoalChange]): Option[Int] =
    goals
      .filterNot(_.effectiveFrom.isAfter(date))
      .maxByOption(_.effectiveFrom.toEpochDay)
      .flatMap(_.targetMl)

  /** Live entries grouped by the local date stored on each entry. */
  def byDay(entries: Iterable[HydrationEntry], goals: Iterable[GoalChange]): Map[LocalDate, HydrationDay] =
    entries
      .filter(_.isLive)
      .groupBy(_.localDate)
      .map { (date, own) =>
        date -> HydrationDay(own.map(_.amount.millilitres).sum, own.size, goalOn(date, goals))
      }

  def period(days: Map[LocalDate, HydrationDay], window: DateRange): HydrationPeriod =
    val recorded = days.view.filterKeys(window.contains).values.toList
    val total = recorded.map(_.totalMl).sum
    val minimum = MinimumSample.meanOver(window)
    val withGoal = recorded.filter(_.goalMl.isDefined)
    HydrationPeriod(
      window = window,
      recordedDays = recorded.size,
      totalMl = total,
      meanMl = Stat.gated(recorded.size, minimum, window, Basis.Recorded)(
        Math.round(total.toDouble / recorded.size.toDouble).toInt
      ),
      goalDays = Option.when(withGoal.nonEmpty)(
        Stat.gated(withGoal.size, minimum, window, Basis.Recorded)(Ratio(withGoal.count(_.isGoalDay), withGoal.size))
      )
    )
end HydrationStats
