package dosegram.core.domain.insights

import java.time.DayOfWeek
import java.time.LocalDate

/** Where a measure of an insight rule comes from. `Calendar` is no record at all: the day of the week, read against the
  * user's own work days. `Medication` is read by the single-domain timing statement IR-07 only.
  */
enum InsightSource:
  case Hydration, Mood, Sleep, Calendar, Medication

/** A measure a comparison rule may read, with the source it comes from. No medication measure is in this list, and none
  * may be added: taken, skipped, missed, timing, punctuality and as-needed logs are excluded from both sides of every
  * cross-domain rule (VISUALS_AND_ANALYTICS section 8.3, PRODUCT_SPEC R-INS-9).
  */
enum InsightVariable(val source: InsightSource):
  case WaterGoalReached extends InsightVariable(InsightSource.Hydration)
  case DailyMoodMean extends InsightVariable(InsightSource.Mood)
  case SleepGoalReached extends InsightVariable(InsightSource.Sleep)
  case BedtimeNearMedian extends InsightVariable(InsightSource.Sleep)
  case WorkDay extends InsightVariable(InsightSource.Calendar)
  case SleepDuration extends InsightVariable(InsightSource.Sleep)
  case WaterVolume extends InsightVariable(InsightSource.Hydration)

/** The unit of a rule's outcome, which decides how its values are worded. */
enum OutcomeUnit:
  case MoodLevels, Minutes, Millilitres

/** The closed rule registry of VISUALS section 8.4 (ARCHITECTURE INS-09). The system never searches for the strongest
  * association: it evaluates the rules listed here and nothing else. `minimumDifference` is the smallest difference of
  * group means that is displayed, in the unit of the outcome. A rule whose grouping is a record of another domain than
  * its outcome is cross-domain and runs only with the `cross_domain_patterns` consent (VA-IN-01); IR-06 groups by the
  * calendar and is held to the same consent, because section 8.2 exempts only IR-07 and IR-11.
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

  /** IR-01: sleep days whose night reached the user's own goal against shorter nights; mood of the same local day. */
  case SleepGoalAndMood
      extends InsightRule("IR-01", InsightVariable.SleepGoalReached, InsightVariable.DailyMoodMean, 0.3)

  /** IR-02: nights that began within 30 minutes of the user's own median bedtime against the others; mood that day. */
  case BedtimeAndMood
      extends InsightRule("IR-02", InsightVariable.BedtimeNearMedian, InsightVariable.DailyMoodMean, 0.3)

  /** IR-06: the user's work days against free days; the night's duration on its sleep day. */
  case WorkDaysAndSleep extends InsightRule("IR-06", InsightVariable.WorkDay, InsightVariable.SleepDuration, 30)

  /** IR-06: the user's work days against free days; the day's water. */
  case WorkDaysAndWater extends InsightRule("IR-06", InsightVariable.WorkDay, InsightVariable.WaterVolume, 300)

  /** IR-06: the user's work days against free days; the day's mood mean. */
  case WorkDaysAndMood extends InsightRule("IR-06", InsightVariable.WorkDay, InsightVariable.DailyMoodMean, 0.3)

  def unit: OutcomeUnit =
    outcome match
      case InsightVariable.SleepDuration => OutcomeUnit.Minutes
      case InsightVariable.WaterVolume   => OutcomeUnit.Millilitres
      case _                             => OutcomeUnit.MoodLevels

  /** The number of the rule in the catalogue, which breaks ties in the ranking (VISUALS section 8.5). */
  def number: Int = id.stripPrefix("IR-").toInt

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
    compare(InsightRule.WaterGoalAndMood, waterGoalPairs(hydration, mood, window), window)

  /** The paired days of a rule over the sources of `in`; `None` when a source the rule reads has no consent. */
  def pairedDays(rule: InsightRule, in: InsightInputs): Option[List[PairedDay]] =
    val window = in.window
    rule match
      case InsightRule.WaterGoalAndMood =>
        for water <- in.hydration; mood <- in.mood yield waterGoalPairs(water, mood, window)
      case InsightRule.SleepGoalAndMood =>
        for nights <- in.nights; mood <- in.mood yield sleepGoalPairs(nights, mood, window)
      case InsightRule.BedtimeAndMood =>
        for nights <- in.nights; mood <- in.mood yield bedtimePairs(nights, mood, window)
      case InsightRule.WorkDaysAndSleep =>
        in.nights.map(nights => workDayPairs(nights.view.mapValues(_.minutes.toDouble).toMap, in.workDays, window))
      case InsightRule.WorkDaysAndWater =>
        in.hydration.map(days => workDayPairs(days.view.mapValues(_.totalMl.toDouble).toMap, in.workDays, window))
      case InsightRule.WorkDaysAndMood =>
        in.mood.map(days => workDayPairs(days.view.mapValues(_.mean).toMap, in.workDays, window))

  private def waterGoalPairs(
      hydration: Map[LocalDate, HydrationDay],
      mood: Map[LocalDate, MoodDay],
      window: DateRange
  ): List[PairedDay] =
    for
      date <- window.dates
      progress <- hydration.get(date).flatMap(_.progress)
      checkins <- mood.get(date)
    yield PairedDay(date, progress.reached, checkins.mean)

  /** IR-01. A sleep day is paired when its night ended under a goal in force that day and the day has a check-in. */
  private def sleepGoalPairs(
      nights: Map[LocalDate, NightFacts],
      mood: Map[LocalDate, MoodDay],
      window: DateRange
  ): List[PairedDay] =
    for
      date <- window.dates
      night <- nights.get(date)
      goal <- night.goalMinutes
      checkins <- mood.get(date)
    yield PairedDay(date, night.minutes >= goal, checkins.mean)

  /** IR-02. The median is the user's own, over the nights of the window whose clock did not shift; a night that began
    * within 30 minutes of it on the clock is in the first group.
    */
  private def bedtimePairs(
      nights: Map[LocalDate, NightFacts],
      mood: Map[LocalDate, MoodDay],
      window: DateRange
  ): List[PairedDay] =
    val steady = window.dates.flatMap(date => nights.get(date).filterNot(_.clockShift).map(date -> _))
    if steady.isEmpty then Nil
    else
      val median = SleepRegularity.circularMedian(steady.map(_._2.bedtimeMinute))
      for
        (date, night) <- steady
        checkins <- mood.get(date)
      yield PairedDay(date, clockDistance(night.bedtimeMinute, median) <= BedtimeBandMinutes, checkins.mean)

  /** IR-06: a recorded day is a work day or a free day of the user's own week; for sleep, the sleep day. */
  private def workDayPairs(
      outcomes: Map[LocalDate, Double],
      workDays: Set[DayOfWeek],
      window: DateRange
  ): List[PairedDay] =
    window.dates.flatMap(date => outcomes.get(date).map(PairedDay(date, workDays(date.getDayOfWeek), _)))

  /** The pooled standard deviation of the outcome over both groups: the divisor of the ranking (section 8.5). */
  def standardisedSize(paired: List[PairedDay]): Double =
    val (first, second) = paired.partition(_.inFirstGroup)
    val pooledVariance =
      (squares(first) + squares(second)) / Math.max(1, paired.size - 2).toDouble
    val sd = Math.sqrt(pooledVariance)
    if sd <= Epsilon then Double.PositiveInfinity else Math.abs(difference(paired)) / sd

  private def squares(days: List[PairedDay]): Double =
    if days.isEmpty then 0.0
    else
      val centre = mean(days)
      days.map(day => (day.outcome - centre) * (day.outcome - centre)).sum

  private val BedtimeBandMinutes = 30
  private val DayMinutes = 1440

  private def clockDistance(a: Int, b: Int): Int =
    val gap = Math.floorMod(a - b, DayMinutes)
    Math.min(gap, DayMinutes - gap)

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
