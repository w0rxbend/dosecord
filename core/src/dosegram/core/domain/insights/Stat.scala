package dosegram.core.domain.insights

/** Where a displayed value comes from (VISUALS_AND_ANALYTICS section 7.1 "evidence type"; ARCHITECTURE INS-12). */
enum Basis:
  case Recorded, SelfReported, Estimated, Device

/** The display thresholds of VISUALS_AND_ANALYTICS sections 7.1, 7.2, 7.6 and 8.5. The list is closed: a statistic
  * names its threshold, it never passes a number of its own.
  */
enum MinimumSample(val required: Int):
  case AnyRecord extends MinimumSample(1)
  case PercentageDenominator extends MinimumSample(5)
  case WeeklyMeanDays extends MinimumSample(3)
  case MonthlyMeanDays extends MinimumSample(10)
  case SpreadDays extends MinimumSample(5)
  case DayToDayPairs extends MinimumSample(4)
  case DelaySamples extends MinimumSample(5)
  case PairedDays extends MinimumSample(14)
  case GroupDays extends MinimumSample(5)

object MinimumSample:

  /** "A weekly average is shown only with at least 3 recorded days; a monthly average with at least 10." A window of up
    * to seven days is judged by the weekly rule, every longer window by the monthly rule.
    */
  def meanOver(window: DateRange): MinimumSample =
    if window.days <= 7 then WeeklyMeanDays else MonthlyMeanDays

/** A derived statistic as a view model holds it (ARCHITECTURE section 11.3). `Shown` can only be built inside this
  * package, by [[Stat.gated]], so a renderer, a caption or an API encoder never receives a number whose sample is below
  * its minimum: it has to match on all three cases.
  */
enum Stat[+A]:
  case Shown private[insights] (value: A, n: Int, window: DateRange, basis: Basis)
  case Insufficient(n: Int, required: Int, window: DateRange)
  case NoVariation(n: Int, window: DateRange)

  def shown: Option[A] =
    this match
      case Shown(value, _, _, _) => Some(value)
      case _: Insufficient[?]    => None
      case _: NoVariation[?]     => None

object Stat:

  /** The one gate: `value` is evaluated and shown only when the sample `n` reaches `minimum`. */
  private[insights] def gated[A](n: Int, minimum: MinimumSample, window: DateRange, basis: Basis)(
      value: => A
  ): Stat[A] =
    if n < minimum.required then Insufficient(n, minimum.required, window)
    else Shown(value, n, window, basis)

/** A share as two counts, so that every surface can print the counts next to the percentage (VISUALS VA-ME-02). */
final case class Ratio(numerator: Int, denominator: Int):
  require(denominator > 0, s"a ratio needs a positive denominator, got $denominator")
  require(numerator >= 0 && numerator <= denominator, s"numerator $numerator is outside 0..$denominator")

  def fraction: Double = numerator.toDouble / denominator.toDouble

  /** The displayed whole percentage (VISUALS section 7.1): rounded half up, except that 100 is shown only for all and 0
    * only for none; every other value is clamped to 1..99.
    */
  def percent: Int =
    if numerator == denominator then 100
    else if numerator == 0 then 0
    else Math.clamp((200L * numerator + denominator) / (2L * denominator), 1L, 99L).toInt
