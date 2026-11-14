package dosegram.core.domain.insights

import dosegram.core.domain.Adherence
import dosegram.core.domain.AdherenceInput

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** The four dose time slots of IR-07, by the local clock time a dose was scheduled for. The bounds are PROPOSED: the
  * specification names the slots and not their hours.
  */
enum DoseSlot(val fromHour: Int):
  case Morning extends DoseSlot(4)
  case Midday extends DoseSlot(11)
  case Evening extends DoseSlot(16)
  case Night extends DoseSlot(21)

object DoseSlot:

  def of(hour: Int): DoseSlot =
    values.reverse.find(slot => hour >= slot.fromHour).getOrElse(Night)

/** One slot of the timing statement: of `resolved` doses, `lateOrUnanswered` were taken late or got no answer. */
final case class SlotShare(slot: DoseSlot, lateOrUnanswered: Int, resolved: Int):
  def share: Double = lateOrUnanswered.toDouble / resolved.toDouble

/** IR-07, the single-domain timing statement of PRODUCT_SPEC R-INS-7: the slot with the highest share of doses answered
  * late or not at all, and the slot with the lowest, as counts. It compares slots with each other and with nothing
  * else. `Shown` can only be built by [[TimingStatements.of]].
  */
enum TimingStatement:
  case Shown private[insights] (highest: SlotShare, lowest: SlotShare, window: DateRange)

  /** Fewer than two slots have the 10 resolved doses each slot needs; `n` is the best slot's count below it. */
  case TooFewDoses(n: Int, required: Int, window: DateRange)
  case Withheld(reason: WithheldReason, window: DateRange)

object TimingStatements:

  /** Each slot compared needs at least this many resolved doses (VISUALS section 8.4). */
  val MinimumDosesPerSlot = 10

  /** The smallest difference of shares between the highest and the lowest slot that is stated. */
  val MinimumDifference = 0.15

  private val Epsilon = 1e-9

  /** Over the scheduled doses resolved in the window: taken late or missed is "late or not at all"; a skip is an
    * answer. As-needed logs, unknown, open and cancelled doses are in no slot.
    */
  def of(doses: Iterable[AdherenceInput], zone: ZoneId, window: DateRange): TimingStatement =
    val resolved = doses.toList.flatMap(dose =>
      Adherence
        .classify(dose)
        .map(counts => (LocalDays.dateOf(dose.scheduledFor, zone), slotOf(dose, zone), counts))
        .filter((date, _, _) => window.contains(date))
    )
    val shares = sharesOf(resolved.map((_, slot, counts) => (slot, counts.takenLate + counts.missed)))
    val eligible = shares.filter(_.resolved >= MinimumDosesPerSlot)
    if eligible.size < 2 then
      val best = shares.map(_.resolved).filter(_ < MinimumDosesPerSlot).maxOption.getOrElse(0)
      TimingStatement.TooFewDoses(best, MinimumDosesPerSlot, window)
    else
      val highest = eligible.maxBy(share => (share.share, -share.slot.ordinal))
      val lowest = eligible.minBy(share => (share.share, share.slot.ordinal))
      if highest.share - lowest.share + Epsilon < MinimumDifference then
        TimingStatement.Withheld(WithheldReason.BelowMinimumDifference, window)
      else if !holdsInBothHalves(resolved, highest.slot, lowest.slot) then
        TimingStatement.Withheld(WithheldReason.UnstableBetweenHalves, window)
      else TimingStatement.Shown(highest, lowest, window)

  /** The standardised size of a shown statement: the difference of shares over the pooled `sqrt(p (1 - p))`. */
  def standardisedSize(shown: TimingStatement.Shown): Double =
    val pooled = (shown.highest.lateOrUnanswered + shown.lowest.lateOrUnanswered).toDouble /
      (shown.highest.resolved + shown.lowest.resolved).toDouble
    val sd = Math.sqrt(pooled * (1 - pooled))
    if sd <= Epsilon then Double.PositiveInfinity else (shown.highest.share - shown.lowest.share) / sd

  private def slotOf(dose: AdherenceInput, zone: ZoneId): DoseSlot =
    DoseSlot.of(dose.scheduledFor.atZone(zone).getHour)

  private def sharesOf(doses: List[(DoseSlot, Int)]): List[SlotShare] =
    doses
      .groupMapReduce(_._1)(dose => (dose._2, 1))((a, b) => (a._1 + b._1, a._2 + b._2))
      .toList
      .map { case (slot, (late, resolved)) => SlotShare(slot, late, resolved) }
      .sortBy(_.slot.ordinal)

  /** Stability check 1 for shares: in the earlier and the later half of the window, each of the two slots has at least
    * two doses and the order of the two slots is the same.
    */
  private def holdsInBothHalves(
      resolved: List[(LocalDate, DoseSlot, dosegram.core.domain.AdherenceCounts)],
      high: DoseSlot,
      low: DoseSlot
  ): Boolean =
    val sorted = resolved.sortBy(_._1.toEpochDay)
    val (earlier, later) = sorted.splitAt(sorted.size / 2)
    List(earlier, later).forall { half =>
      val shares = sharesOf(half.map((_, slot, counts) => (slot, counts.takenLate + counts.missed)))
      (shares.find(_.slot == high), shares.find(_.slot == low)) match
        case (Some(h), Some(l)) => h.resolved >= 2 && l.resolved >= 2 && h.share > l.share
        case _                  => false
    }
end TimingStatements

/** The single-domain headline metrics IR-11 compares with the user's own baseline. */
enum BaselineMetric(val minimumDifference: Double):
  case SleepMinutes extends BaselineMetric(10)
  case WaterMillilitres extends BaselineMetric(100)
  case MoodLevel extends BaselineMetric(0.2)

/** IR-11: the mean of the last seven days against the mean of the previous four weeks that each meet the weekly mean
  * gate (VISUALS section 7.1, "Own baseline"; PRODUCT_SPEC R-INS-6). `weeks` is the number of those weeks.
  */
enum BaselineStatement:
  case Shown private[insights] (
      metric: BaselineMetric,
      current: Double,
      baseline: Double,
      weeks: Int,
      window: DateRange
  )
  case TooFewWeeks(metric: BaselineMetric, n: Int, required: Int, window: DateRange)
  case TooFewDays(metric: BaselineMetric, n: Int, required: Int, window: DateRange)
  case AboutTheSame(metric: BaselineMetric, window: DateRange)

object BaselineStatements:

  val WeekDays = 7
  val BaselineWeeks = 4

  /** The own baseline is shown only when at least two earlier weeks meet the gate. */
  val MinimumWeeks = 2

  private val Epsilon = 1e-9

  /** `values` are the recorded days of the metric; the current week ends on `today`. */
  def of(metric: BaselineMetric, values: Map[LocalDate, Double], today: LocalDate): BaselineStatement =
    val current = DateRange.endingOn(today, WeekDays)
    val gate = MinimumSample.WeeklyMeanDays.required
    meanOver(values, current, gate) match
      case None =>
        BaselineStatement.TooFewDays(metric, values.keys.count(current.contains), gate, current)
      case Some(now) =>
        val earlier = (1 to BaselineWeeks).toList.flatMap { back =>
          meanOver(values, DateRange.endingOn(today.minusDays(WeekDays.toLong * back), WeekDays), gate)
        }
        if earlier.size < MinimumWeeks then BaselineStatement.TooFewWeeks(metric, earlier.size, MinimumWeeks, current)
        else
          val baseline = earlier.sum / earlier.size.toDouble
          if Math.abs(now - baseline) + Epsilon < metric.minimumDifference then
            BaselineStatement.AboutTheSame(metric, current)
          else BaselineStatement.Shown(metric, now, baseline, earlier.size, current)

  /** The difference over the spread of the weekly means, for the ranking. */
  def standardisedSize(shown: BaselineStatement.Shown): Double =
    Math.abs(shown.current - shown.baseline) / shown.metric.minimumDifference

  /** The window the statement reads: the current week and the four before it. */
  def window(today: LocalDate): DateRange = DateRange.endingOn(today, WeekDays * (BaselineWeeks + 1))

  private def meanOver(values: Map[LocalDate, Double], week: DateRange, gate: Int): Option[Double] =
    val days = week.dates.flatMap(values.get)
    Option.when(days.size >= gate)(days.sum / days.size.toDouble)
end BaselineStatements

/** One insight that passed every check, with the size it is ranked by. */
enum Insight:
  case Pattern(comparison: Comparison.Shown, size: Double)
  case Timing(statement: TimingStatement.Shown, size: Double)
  case Baseline(statement: BaselineStatement.Shown, size: Double)

  def size: Double

  /** The rule number of the catalogue, for ties. */
  def number: Int =
    this match
      case Pattern(comparison, _) => comparison.rule.number
      case Timing(_, _)           => 7
      case Baseline(_, _)         => 11

/** A rule that is short of its minimum data: what it still needs, as a count (VISUALS section 8.5, "Insights tab"). */
enum InsightGap:
  case Pattern(rule: InsightRule, missing: Int, inSmallerGroup: Boolean)
  case Timing(missing: Int)

/** What the engine read for one account. A source the user did not consent to is `None` and no rule reads it;
  * `crossDomain` is the `cross_domain_patterns` consent. `today` is the account's current local day.
  */
final case class InsightInputs(
    today: LocalDate,
    window: DateRange,
    zone: ZoneId,
    hydration: Option[Map[LocalDate, HydrationDay]],
    mood: Option[Map[LocalDate, MoodDay]],
    nights: Option[Map[LocalDate, NightFacts]],
    doses: Option[List[AdherenceInput]],
    workDays: Set[DayOfWeek],
    crossDomain: Boolean
)

/** The current insights of an account: the passing rules in rank order, at most five, and the rules close to their
  * minimum data. Nothing here searches for associations: it evaluates the closed registry.
  */
final case class InsightList(window: DateRange, shown: List[Insight], gaps: List[InsightGap])

/** The procedure of VISUALS_AND_ANALYTICS section 8.5 over the closed registry: every rule whose sources have consent
  * is evaluated, the ones that pass are ranked by standardised size with ties broken by rule number, and the rules that
  * are short of data are listed with the count they still need. A withheld rule is neither shown nor listed.
  */
object InsightEngine:

  /** The Insights tab shows up to five passing rules (section 8.5). */
  val MaximumShown = 5

  /** A rule is "close to its minimum data" when it has at least this share of the paired days it needs. */
  private val CloseShare = 0.5

  def evaluate(in: InsightInputs): InsightList =
    val comparisons = patterns(in)
    val timing = in.doses.map(TimingStatements.of(_, in.zone, in.window))
    val baselines = baselineStatements(in)
    val shown =
      comparisons.collect { case (shown: Comparison.Shown, paired) =>
        Insight.Pattern(shown, Comparisons.standardisedSize(paired))
      } ++
        timing.collect { case shown: TimingStatement.Shown =>
          Insight.Timing(shown, TimingStatements.standardisedSize(shown))
        } ++
        baselines.collect { case shown: BaselineStatement.Shown =>
          Insight.Baseline(shown, BaselineStatements.standardisedSize(shown))
        }
    val gaps =
      comparisons.flatMap((result, _) => patternGap(result)) ++ timing.toList.flatMap(timingGap)
    InsightList(in.window, shown.sortBy(insight => (-insight.size, insight.number)).take(MaximumShown), gaps)

  /** The first insight of the list that the weekly report may carry (VISUALS section 8.5, "Selection"). */
  def forReport(list: InsightList): Option[Insight] = list.shown.headOption

  private def patterns(in: InsightInputs): List[(Comparison, List[PairedDay])] =
    if !in.crossDomain then Nil
    else
      InsightRule.values.toList.flatMap(rule =>
        Comparisons.pairedDays(rule, in).map(paired => Comparisons.compare(rule, paired, in.window) -> paired)
      )

  private def baselineStatements(in: InsightInputs): List[BaselineStatement] =
    List(
      in.nights.map(nights =>
        BaselineStatements.of(BaselineMetric.SleepMinutes, nights.view.mapValues(_.minutes.toDouble).toMap, in.today)
      ),
      in.hydration.map(days =>
        BaselineStatements.of(BaselineMetric.WaterMillilitres, days.view.mapValues(_.totalMl.toDouble).toMap, in.today)
      ),
      in.mood.map(days => BaselineStatements.of(BaselineMetric.MoodLevel, days.view.mapValues(_.mean).toMap, in.today))
    ).flatten

  private def patternGap(result: Comparison): Option[InsightGap] =
    result match
      case Comparison.TooFewDays(rule, n, required, _) if n >= required * CloseShare =>
        Some(InsightGap.Pattern(rule, required - n, inSmallerGroup = false))
      case Comparison.TooFewInGroup(rule, n, required, _) if n > 0 =>
        Some(InsightGap.Pattern(rule, required - n, inSmallerGroup = true))
      case _ => None

  private def timingGap(result: TimingStatement): Option[InsightGap] =
    result match
      case TimingStatement.TooFewDoses(n, required, _) if n >= required * CloseShare =>
        Some(InsightGap.Timing(required - n))
      case _ => None
end InsightEngine
