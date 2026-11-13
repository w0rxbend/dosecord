package dosegram.core.domain.insights

import java.time.LocalDate

/** A measure a comparison rule may read, with the domain it comes from. No medication measure is in this list, and none
  * may be added: taken, skipped, missed, timing, punctuality and as-needed logs are excluded from both sides of every
  * cross-domain rule (VISUALS_AND_ANALYTICS section 8.3, PRODUCT_SPEC R-INS-9).
  */
enum InsightVariable(val domain: RollupDomain):
  case WaterGoalReached extends InsightVariable(RollupDomain.Hydration)
  case DailyMoodMean extends InsightVariable(RollupDomain.Mood)

/** The closed rule registry of VISUALS section 8.4 (ARCHITECTURE INS-09). The system never searches for the strongest
  * association: it evaluates the rules listed here and nothing else. `minimumDifference` is the smallest difference of
  * group means that is displayed, in the unit of the outcome.
  */
enum InsightRule(
    val id: String,
    val grouping: InsightVariable,
    val outcome: InsightVariable,
    val minimumDifference: Double
):
  /** IR-04: days on which the user's own water goal was reached against the other recorded days; daily mood mean. */
  case WaterGoalAndMood
      extends InsightRule("IR-04", InsightVariable.WaterGoalReached, InsightVariable.DailyMoodMean, 0.3)

/** One local day that has both the grouping value and the outcome. */
final case class PairedDay(date: LocalDate, inFirstGroup: Boolean, outcome: Double)

final case class GroupMean(days: Int, mean: Double)

/** Why a comparison with enough data is still not displayed. A withheld comparison is omitted: it is never worded as
  * "no link" (ARCHITECTURE INS-10).
  */
enum WithheldReason:
  case BelowMinimumDifference, UnstableBetweenHalves, UnstableWithoutOneDay

/** The outcome of one rule over one window. `Shown` can only be built by [[Comparisons.compare]], after the whole
  * procedure of VISUALS section 8.5 has passed. `first` is the group the rule names (for IR-04: goal reached).
  */
enum Comparison:
  case Shown private[insights] (rule: InsightRule, first: GroupMean, second: GroupMean, window: DateRange)

  /** Fewer paired days than the rule needs in total. */
  case TooFewDays(rule: InsightRule, n: Int, required: Int, window: DateRange)

  /** Enough paired days, but the smaller group has `n` of them and needs `required`. */
  case TooFewInGroup(rule: InsightRule, n: Int, required: Int, window: DateRange)
  case NoVariation(rule: InsightRule, n: Int, window: DateRange)
  case Withheld(rule: InsightRule, reason: WithheldReason, window: DateRange)

/** The comparison procedure of VISUALS_AND_ANALYTICS section 8.5: a difference of group means over the user's own days,
  * guarded by minimum data, a minimum difference and two stability checks. It states no probability and no cause.
  */
object Comparisons:

  /** IR-04. A day is paired only when it has a hydration record under a goal and a mood check-in. */
  def waterGoalAndMood(
      hydration: Map[LocalDate, HydrationDay],
      mood: Map[LocalDate, MoodDay],
      window: DateRange
  ): Comparison =
    val paired =
      for
        date <- window.dates
        progress <- hydration.get(date).flatMap(_.progress)
        checkins <- mood.get(date)
      yield PairedDay(date, progress.reached, checkins.mean)
    compare(InsightRule.WaterGoalAndMood, paired, window)

  def compare(rule: InsightRule, paired: List[PairedDay], window: DateRange): Comparison =
    val (first, second) = paired.partition(_.inFirstGroup)
    val smaller = Math.min(first.size, second.size)
    if paired.size < MinimumSample.PairedDays.required then
      Comparison.TooFewDays(rule, paired.size, MinimumSample.PairedDays.required, window)
    else if smaller == 0 then Comparison.NoVariation(rule, paired.size, window)
    else if smaller < MinimumSample.GroupDays.required then
      Comparison.TooFewInGroup(rule, smaller, MinimumSample.GroupDays.required, window)
    else
      withheld(rule, paired) match
        case Some(reason) => Comparison.Withheld(rule, reason, window)
        case None         =>
          Comparison.Shown(rule, GroupMean(first.size, mean(first)), GroupMean(second.size, mean(second)), window)

  private def withheld(rule: InsightRule, paired: List[PairedDay]): Option[WithheldReason] =
    val overall = difference(paired)
    if isBelow(overall, rule.minimumDifference) then Some(WithheldReason.BelowMinimumDifference)
    else if !holdsInBothHalves(paired, overall) then Some(WithheldReason.UnstableBetweenHalves)
    else if !holdsWithoutAnyDay(paired, overall, rule.minimumDifference / 2.0) then
      Some(WithheldReason.UnstableWithoutOneDay)
    else None

  /** Stability check 1: the earlier and the later half each have at least 2 days per group and the same sign. */
  private def holdsInBothHalves(paired: List[PairedDay], overall: Double): Boolean =
    val (earlier, later) = paired.sortBy(_.date.toEpochDay).splitAt(paired.size / 2)
    List(earlier, later).forall { half =>
      val (first, second) = half.partition(_.inFirstGroup)
      first.size >= MinGroupDaysPerHalf && second.size >= MinGroupDaysPerHalf &&
      Math.signum(difference(half)) == Math.signum(overall)
    }

  /** Stability check 2: no single day carries the sign or more than half of the minimum difference. */
  private def holdsWithoutAnyDay(paired: List[PairedDay], overall: Double, floor: Double): Boolean =
    paired.indices.forall { index =>
      val remaining = difference(paired.patch(index, Nil, 1))
      Math.signum(remaining) == Math.signum(overall) && !isBelow(remaining, floor)
    }

  private val MinGroupDaysPerHalf = 2

  /** Tolerance for sums of decimal fractions: 3.3 - 3.0 must count as 0.3. */
  private val Epsilon = 1e-9

  private def isBelow(difference: Double, minimum: Double): Boolean = Math.abs(difference) + Epsilon < minimum

  private def difference(paired: List[PairedDay]): Double =
    val (first, second) = paired.partition(_.inFirstGroup)
    mean(first) - mean(second)

  private def mean(days: List[PairedDay]): Double = days.map(_.outcome).sum / days.size.toDouble
end Comparisons
